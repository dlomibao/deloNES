package net.lomibao.nes.client;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
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
            // Cache the opcode CSV before the first emulator build so the
            // Phase F swap path doesn't have to re-fetch on every ROM pick.
            try {
                opcodeCsvBytes = Gdx.files.internal("opcodes/opcodes.csv").readBytes();
            } catch (Throwable t) {
                Gdx.app.error("web", "OPCODES CSV LOAD FAIL: " + t.getMessage(), t);
            }
            setupEmulator();

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
            // Keyboard → NES controller (player 0). Matches the desktop
            // ControlsConfig defaults: Arrows = D-pad, Z = A, X = B,
            // Enter = START, Right Shift = SELECT. Pressing/releasing any
            // bound key flips the corresponding bit in the live controller
            // state; the cart polls it via the $4016 shift register.
            Gdx.input.setInputProcessor(new InputAdapter() {
                @Override
                public boolean keyDown(int keycode) {
                    Button b = mapKey(keycode);
                    if (b != null && controller != null) {
                        controller.setButton(0, b, true);
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean keyUp(int keycode) {
                    Button b = mapKey(keycode);
                    if (b != null && controller != null) {
                        controller.setButton(0, b, false);
                        return true;
                    }
                    return false;
                }
            });
        }

        private static Button mapKey(int keycode) {
            switch (keycode) {
                case Input.Keys.UP:           return Button.UP;
                case Input.Keys.DOWN:         return Button.DOWN;
                case Input.Keys.LEFT:         return Button.LEFT;
                case Input.Keys.RIGHT:        return Button.RIGHT;
                case Input.Keys.Z:            return Button.A;
                case Input.Keys.X:            return Button.B;
                case Input.Keys.ENTER:        return Button.START;
                case Input.Keys.SHIFT_RIGHT:  return Button.SELECT;
                default:                      return null;
            }
        }

        @Override
        public void render() {
            frame++;

            if (nes != null) {
                renderEmulatorFrame();
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
