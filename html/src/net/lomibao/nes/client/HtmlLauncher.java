package net.lomibao.nes.client;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;
import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.rom.RomLoader;
import org.teavm.jso.JSExport;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.KeyboardEvent;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

/**
 * Web entry point. Boots a {@link NesSystem} against a preloaded ROM and
 * pumps the PPU framebuffer to the canvas every frame. Initial Phase 0
 * probes (resource access, keyboard input, smoke-test CPU run) still
 * fire from {@code create()} so a startup regression surfaces in the
 * console.
 *
 * <p>If emulator setup fails for any reason (asset missing, decode
 * error, unsupported mapper, etc.) the launcher falls back to a moving
 * gradient and logs the cause — keeps the canvas alive and visibly
 * obviously-not-emulating instead of going black.
 *
 * <p>Phase F adds a JS bridge: {@link #loadRomBytes(byte[])} is exported
 * to the global scope via TeaVM {@code @JSExport}, so {@code index.html}'s
 * file picker and drag-drop handlers can hot-swap the running emulator
 * without a page reload.
 *
 * <p>See {@code docs/web-phase0-findings.md} for the per-probe history.
 */
public class HtmlLauncher {

    /**
     * Latest constructed {@link WebLauncher} — set in {@code create()}, read by
     * the {@code @JSExport} bridge {@link #loadRomBytes(byte[])}. Single
     * static instance because there's exactly one {@code WebApplication} per
     * page; no thread safety needed (TeaVM has no real threads).
     */
    private static WebLauncher INSTANCE;

    public static void main(String[] args) {
        WebApplicationConfiguration config = new WebApplicationConfiguration();
        config.width = 0;
        config.height = 0;
        config.useGL30 = true;
        config.showDownloadLogs = true;

        new WebApplication(new WebLauncher(), config);
    }

    /**
     * JS bridge — called from the browser ROM picker / drag-drop handlers in
     * {@code index.html}. The argument is the raw bytes of an iNES file
     * pushed in via {@code FileReader.readAsArrayBuffer} then converted to a
     * {@code Uint8Array}/{@code Int8Array} which TeaVM marshals as a Java
     * {@code byte[]}.
     *
     * <p>The actual emulator swap is deferred to the next render tick via
     * {@link com.badlogic.gdx.Application#postRunnable(Runnable)} so we don't
     * stomp on a frame mid-tick. Returns true on success, false if no
     * {@link WebLauncher} has booted yet (i.e. called before
     * {@code create()}) or if the bytes look obviously wrong before we even
     * post the runnable — the deferred swap still validates again and logs
     * any iNES / mapper-construction error.
     *
     * @param romBytes raw .nes file bytes
     * @return {@code true} if the swap was scheduled, {@code false} otherwise
     */
    @JSExport
    public static boolean loadRomBytes(byte[] romBytes) {
        if (INSTANCE == null) {
            // Launcher hasn't booted yet — JS picker fired faster than the
            // gdx-teavm preload loop. The user can just retry; we don't queue.
            return false;
        }
        if (romBytes == null || romBytes.length == 0) {
            return false;
        }
        WebLauncher target = INSTANCE;
        Gdx.app.postRunnable(() -> target.swapRom(romBytes));
        return true;
    }

    private static class WebLauncher extends ApplicationAdapter {
        private static final int NES_W = 256;
        private static final int NES_H = 240;
        /** ROM to load. nestest is always available; DonkeyKong only if dev has it locally. */
        private static final String PRIMARY_ROM = "roms/DonkeyKong.nes";
        private static final String FALLBACK_ROM = "roms/nestest.nes";

        private SpriteBatch batch;
        private Pixmap pixmap;
        private Texture texture;
        private byte[] frameBytes;
        private int frame;
        private long lastFpsLogMs;

        /**
         * Real-time accumulator pacing NES frames to the console's rate.
         * The browser calls render() at the DISPLAY refresh rate (rAF):
         * 60 Hz monitors happened to match NES speed, but 120/144 Hz
         * displays ran the emulation (and the APU sample stream) 2x+ fast
         * — audibly, a permanently overflowing ring (huge ringDropped).
         * Only run a NES frame once 1/60.0988 s of wall time has accrued.
         */
        private double emuTimeAccum;
        private static final double NES_FRAME_SECONDS = 1.0 / 60.0988;
        /** Max NES frames per render tick — bounds catch-up after jank. */
        private static final int MAX_CATCHUP_FRAMES = 3;
        // Microbenchmark: cumulative wall-clock time spent inside nes.runFrame()
        // since the last FPS log. Reveals whether emulation is rAF-throttled
        // (work < 16ms but FPS low) or CPU-bound (work ≈ 100/FPS ms).
        private long runFrameNsAccum;
        private int runFrameSamples;

        // Emulator state — null if setup failed; render() falls back to gradient.
        private NesSystem nes;
        private CPU6502 cpu;
        private PPU ppu;
        private Controller controller;
        private String loadedRom;

        /**
         * Cached opcode CSV bytes. Loaded once on boot from the gdx-teavm
         * preload cache. Phase F's {@link #swapRom(byte[])} re-uses this for
         * every hot-swap; refetching via {@code Gdx.files.internal(...)} on
         * each pick would be wasted work and would also make the swap
         * sensitive to a hypothetical asset-cache eviction.
         */
        private byte[] opcodeCsvBytes;

        /**
         * Phase 0 APU POC-W (derisk; docs/apu-plan.md "Phase 0 / 0-W") —
         * non-null only when the page URL carries {@code ?audioPoc=1}.
         * Flag off ⇒ stays null and no WebAudio object is ever created.
         */
        private WebAudioTonePoc audioPoc;

        /**
         * Phase E3 production audio sink — null when WebAudio is
         * unavailable (emulator runs silent). Created BEFORE
         * {@link #setupEmulator()} so {@link #installRom} can bind the
         * APU's sample rate/ring on the very first ROM.
         */
        private WebAudioOut audioOut;

        @Override
        public void create() {
            batch = new SpriteBatch();
            pixmap = new Pixmap(NES_W, NES_H, Format.RGBA8888);
            texture = new Texture(NES_W, NES_H, Format.RGBA8888);
            frameBytes = new byte[NES_W * NES_H * 4];
            lastFpsLogMs = System.currentTimeMillis();

            Gdx.app.log("web", "boot — NES_W=" + NES_W + " NES_H=" + NES_H);
            probeResources();
            probeInput();
            installDomKeyHooks();
            // Cache the opcode CSV before the first emulator build so the
            // Phase F swap path doesn't have to re-fetch on every ROM pick.
            try {
                opcodeCsvBytes = Gdx.files.internal("opcodes/opcodes.csv").readBytes();
            } catch (Throwable t) {
                Gdx.app.error("web", "OPCODES CSV LOAD FAIL: " + t.getMessage(), t);
            }
            // Phase E3: build the WebAudio chain before the first ROM
            // install so installRom() can bind sample rate + ring (D12).
            audioOut = WebAudioOut.createOrNull();

            setupEmulator();

            // Phase 0 APU POC-W probe — flag-gated, see WebAudioTonePoc.
            audioPoc = WebAudioTonePoc.createIfEnabled();

            // Publish ourselves so the @JSExport bridge can reach the live
            // launcher. Done LAST so an INSTANCE is never visible in a
            // half-initialized state.
            INSTANCE = this;
        }

        /**
         * Build a {@link NesSystem} against the preloaded ROM. Tries
         * {@link #PRIMARY_ROM} first; falls back to {@link #FALLBACK_ROM} if the
         * primary file isn't in the asset cache (common case: dev doesn't have
         * DonkeyKong.nes locally so it never got copied into the webapp).
         */
        private void setupEmulator() {
            byte[] romBytes = tryLoad(PRIMARY_ROM);
            String romName;
            if (romBytes == null) {
                romBytes = tryLoad(FALLBACK_ROM);
                romName = FALLBACK_ROM;
            } else {
                romName = PRIMARY_ROM;
            }
            if (romBytes == null) {
                Gdx.app.error("web",
                        "EMULATOR SETUP FAIL: no ROM in asset cache "
                        + "(tried " + PRIMARY_ROM + " and " + FALLBACK_ROM + ")");
                return;
            }
            installRom(romBytes, romName);
        }

        /**
         * Phase F hot-swap entry point. Replaces the live emulator state
         * with one built from {@code newRomBytes}. Runs on the GDX render
         * thread (scheduled via {@link com.badlogic.gdx.Application#postRunnable})
         * so it never races a render frame.
         */
        void swapRom(byte[] newRomBytes) {
            Gdx.app.log("web",
                    "ROM swap requested — " + newRomBytes.length + " bytes");
            // Drop any references to the prior emulator state so the GC can
            // collect it. PPU/Cartridge/NesSystem are pure Java objects
            // (no GL handles) — the SpriteBatch/Pixmap/Texture are owned by
            // this launcher and re-used across ROMs (their sizes never change),
            // so we don't dispose them here.
            // D18: gain-mute + ring clear across the swap; installRom()'s
            // success path rebinds (and unmutes) against the new APU.
            if (audioOut != null) {
                audioOut.detach();
            }
            nes = null;
            cpu = null;
            ppu = null;
            controller = null;
            installRom(newRomBytes, "user-picked.nes");
        }

        /**
         * Construct a {@link NesSystem} from the given iNES bytes and adopt
         * it as the live emulator. Errors are caught and logged; the
         * launcher falls back to the gradient until the user picks something
         * that loads.
         */
        private void installRom(byte[] romBytes, String romName) {
            if (opcodeCsvBytes == null) {
                Gdx.app.error("web",
                        "EMULATOR SETUP FAIL: opcodes.csv not available — "
                        + "preload cache missing the asset?");
                return;
            }
            try {
                RomLoader.Loaded loaded = RomLoader.loadFromBytes(
                        romBytes, romName,
                        new ByteArrayInputStream(opcodeCsvBytes));
                this.nes = loaded.nes;
                this.cpu = loaded.cpu;
                this.ppu = loaded.ppu;
                this.controller = loaded.controller;
                this.loadedRom = romName;
                // Phase E3 (D12): tell the fresh APU the browser's real
                // output rate BEFORE its first frame, then point the SPN
                // at the new core ring (clears it + unmutes).
                if (audioOut != null && nes.getApu() != null) {
                    nes.getApu().setSampleRate(audioOut.sampleRate());
                    audioOut.setSource(nes.getApu().sampleBuffer());
                }
                Gdx.app.log("web",
                        "EMULATOR READY rom=" + loadedRom
                        + " mapper=" + loaded.cartridge.header.getMapperNumber()
                        + " prgBytes=" + loaded.cartridge.header.getPRGROMSizeBytes()
                        + " chrBytes=" + loaded.cartridge.header.getCHRROMSizeBytes()
                        + " initialPC=0x" + Integer.toHexString(cpu.getPc()));
            } catch (Throwable t) {
                Gdx.app.error("web", "EMULATOR SETUP FAIL: " + t.getMessage(), t);
                nes = null;
                cpu = null;
                ppu = null;
                controller = null;
            }
        }

        private byte[] tryLoad(String path) {
            try {
                if (!Gdx.files.internal(path).exists()) {
                    return null;
                }
                return Gdx.files.internal(path).readBytes();
            } catch (Throwable t) {
                return null;
            }
        }

        private void probeResources() {
            try {
                byte[] bytes = Gdx.files.internal("roms/nestest.nes").readBytes();
                Gdx.app.log("web",
                        "RESOURCE PROBE OK: nestest.nes loaded, " + bytes.length + " bytes, "
                        + "first 4 = "
                        + Integer.toHexString(bytes[0] & 0xff) + " "
                        + Integer.toHexString(bytes[1] & 0xff) + " "
                        + Integer.toHexString(bytes[2] & 0xff) + " "
                        + Integer.toHexString(bytes[3] & 0xff)
                        + " (expect 4e 45 53 1a)");
            } catch (Throwable t) {
                Gdx.app.error("web", "RESOURCE PROBE FAIL: " + t.getMessage(), t);
            }
        }

        private void probeInput() {
            // Keyboard is handled by installDomKeyHooks() — gdx-teavm's
            // WebInput key path is focus-gated (hasFocus is only set by
            // mousedown on the canvas) and effectively dead here, and a
            // second keymap maintained in parallel is a divergence
            // hazard (review be7cf87 round 1). Kept as a named probe
            // point should a Gdx InputProcessor ever be needed again.
        }

        /**
         * Capture-phase DOM key listeners feeding the NES controller
         * directly. gdx-teavm's own WebInput keydown listener sits on
         * document in the BUBBLE phase, and the canvas-level GL handler
         * stops propagation of canvas-targeted key events before they
         * get there — observed as "keys reach the page but the game
         * never sees them". Capture fires ahead of any element handler,
         * so this path works no matter which element holds focus. Same
         * pattern as WebAudioOut's gesture-resume hooks.
         */
        private void installDomKeyHooks() {
            Window.current().getDocument().addEventListener(
                    "keydown", e -> onDomKey((KeyboardEvent) e, true), true);
            Window.current().getDocument().addEventListener(
                    "keyup", e -> onDomKey((KeyboardEvent) e, false), true);
            Gdx.app.log("web", "DOM key hooks installed (capture)");
        }

        private void onDomKey(KeyboardEvent e, boolean down) {
            Button b = mapDomCode(e.getCode());
            if (b == null || controller == null) {
                return;
            }
            // Stop the browser acting on game keys (arrow scroll, Enter
            // re-activating a focused control). Only for mapped keys so
            // devtools/shortcuts stay usable.
            e.preventDefault();
            controller.setButton(0, b, down);
        }

        /** KeyboardEvent.code (layout-independent) → NES button. */
        private static Button mapDomCode(String code) {
            if (code == null) {
                return null;
            }
            switch (code) {
                case "ArrowUp":     return Button.UP;
                case "ArrowDown":   return Button.DOWN;
                case "ArrowLeft":   return Button.LEFT;
                case "ArrowRight":  return Button.RIGHT;
                case "KeyZ":        return Button.A;
                case "KeyX":        return Button.B;
                case "Enter":
                case "NumpadEnter": return Button.START;
                case "ShiftRight":  return Button.SELECT;
                default:            return null;
            }
        }


        @Override
        public void render() {
            frame++;

            // Phase 0 APU POC-W: produce this frame's tone samples + stats.
            // Runs on top of the live emulation so the 60 FPS main-thread
            // contention soak is real.
            if (audioPoc != null) {
                audioPoc.onFrame();
            }

            if (nes != null) {
                // Clamp delta so a backgrounded tab doesn't fast-forward
                // on return (rAF stops while hidden; delta spikes huge).
                emuTimeAccum += Math.min(Gdx.graphics.getDeltaTime(), 0.25f);
                int framesRun = 0;
                while (nes != null && emuTimeAccum >= NES_FRAME_SECONDS
                        && framesRun < MAX_CATCHUP_FRAMES) {
                    renderEmulatorFrame();
                    emuTimeAccum -= NES_FRAME_SECONDS;
                    framesRun++;
                }
                if (framesRun == MAX_CATCHUP_FRAMES
                        && emuTimeAccum >= NES_FRAME_SECONDS) {
                    // Genuinely still behind after max catch-up — drop the
                    // debt instead of spiraling. A legitimate sub-frame
                    // remainder is kept (zeroing it under sustained rAF
                    // throttling would run the NES a permanent -0.16%
                    // slow and slowly starve the audio ring).
                    emuTimeAccum = 0;
                }
                // Phase E3 (D16): pump the audio sink right after
                // runFrame() — the SPN pulls from the core ring on its
                // own callback; this is production-side accounting +
                // per-second stats for the manual soak checklist.
                if (audioOut != null) {
                    audioOut.onFrame();
                }
            } else {
                renderFallbackGradient();
            }

            ByteBuffer pixels = pixmap.getPixels();
            pixels.rewind();
            pixels.put(frameBytes);
            pixels.rewind();
            texture.draw(pixmap, 0, 0);

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

            batch.begin();
            batch.draw(texture, 0, 0,
                    Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            batch.end();

            long now = System.currentTimeMillis();
            if (now - lastFpsLogMs >= 1000) {
                String state;
                if (nes != null) {
                    double avgRunFrameMs = runFrameSamples == 0 ? 0
                            : (runFrameNsAccum / (double) runFrameSamples) / 1_000_000.0;
                    int ceilingFps = avgRunFrameMs == 0 ? 999
                            : (int) (1000.0 / avgRunFrameMs);
                    state = "rom=" + loadedRom
                            + " pc=0x" + Integer.toHexString(cpu.getPc())
                            + " runFrame=" + String.format("%.2f", avgRunFrameMs) + "ms"
                            + " ceiling=" + ceilingFps + "fps";
                    runFrameNsAccum = 0;
                    runFrameSamples = 0;
                } else {
                    state = "fallback-gradient";
                }
                Gdx.app.log("web",
                        "FPS=" + Gdx.graphics.getFramesPerSecond()
                        + " frame=" + frame + " " + state);
                lastFpsLogMs = now;
            }
        }

        /**
         * Run one NES frame and copy the visible 256x240 portion of the PPU
         * framebuffer into {@link #frameBytes}. PPU stores pixels as ARGB
         * ints; the pixmap is RGBA8888 so we re-order on the fly. The hot
         * inner loop avoids any per-pixel allocations.
         */
        private void renderEmulatorFrame() {
            long t0 = System.nanoTime();
            try {
                nes.runFrame();
            } catch (RuntimeException e) {
                Gdx.app.error("web", "runFrame threw: " + e.getMessage(), e);
                nes = null;
                return;
            }
            runFrameNsAccum += System.nanoTime() - t0;
            runFrameSamples++;
            int[][] screen = ppu.getScreen();
            int idx = 0;
            for (int y = 0; y < NES_H; y++) {
                int[] row = screen[y];
                for (int x = 0; x < NES_W; x++) {
                    int argb = row[x];
                    frameBytes[idx++] = (byte) ((argb >> 16) & 0xff); // R
                    frameBytes[idx++] = (byte) ((argb >> 8) & 0xff);  // G
                    frameBytes[idx++] = (byte) (argb & 0xff);         // B
                    frameBytes[idx++] = (byte) ((argb >> 24) & 0xff); // A
                }
            }
        }

        /** Used when no ROM is available — keep the canvas visibly alive. */
        private void renderFallbackGradient() {
            int offset = frame & 0xff;
            int idx = 0;
            for (int y = 0; y < NES_H; y++) {
                for (int x = 0; x < NES_W; x++) {
                    frameBytes[idx++] = (byte) ((x + offset) & 0xff);
                    frameBytes[idx++] = (byte) ((y + offset) & 0xff);
                    frameBytes[idx++] = (byte) ((x ^ y) & 0xff);
                    frameBytes[idx++] = (byte) 0xff;
                }
            }
        }

        @Override
        public void dispose() {
            if (batch != null) batch.dispose();
            if (texture != null) texture.dispose();
            if (pixmap != null) pixmap.dispose();
        }
    }
}
