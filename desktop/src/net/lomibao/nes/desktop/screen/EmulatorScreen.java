package net.lomibao.nes.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.CPUBus;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.PPUBus;
import net.lomibao.nes.components.Ram;
import net.lomibao.nes.harness.InputRecorder;
import net.lomibao.nes.harness.MovieFormat;
import net.lomibao.nes.render.NesMasterPalette;
import net.lomibao.nes.render.PixelRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Reusable LibGDX {@link Screen} that runs an NES ROM from any
 * {@link RomSource}. Extracted from {@code NestestBackgroundRenderer} —
 * same render-loop semantics, but parameterized on the ROM, the
 * {@link Controller} instance (so an external input adapter can write
 * to it), an exit callback, and a debug-HUD toggle.
 *
 * <p>Lifecycle hot keys (pause / reset / exit) are NOT handled inside
 * this Screen. {@code NesGame} owns the keyboard adapter and, after each
 * frame's {@code super.render()}, calls {@link #togglePause()},
 * {@link #reset()}, or {@link #requestExit()} on the active instance.
 */
public class EmulatorScreen implements Screen {

    private final RomSource rom;
    private final Controller controller;
    private final Runnable onExit;
    private final boolean debugHud;

    // Rendering — created in show().
    private SpriteBatch batch;
    private BitmapFont font;
    private PixelRenderer pixelRenderer;
    private Texture paletteTexture;
    private Pixmap palettePixmap;

    // Emulator components.
    private NesSystem nesSystem;
    private CPUBus cpuBus;
    private CPU6502 cpu;
    private PPU ppu;
    private PPUBus ppuBus;
    private Cartridge cartridge;

    // Movie recording (headless-harness plan, Phase D2 desktop wiring).
    /**
     * Debug flag enabling live input recording: launch with
     * {@code -Ddelones.recordMovie} (any value). The recorder samples the
     * controller at the same frame boundary headless replay applies input at
     * (immediately before each {@code NesSystem.runFrame()} — decision D9),
     * and the finished movie is dumped to
     * {@code ~/.deloNES/movies/<rom name>.dmov} when the screen is disposed
     * (exit-to-menu or app close). Same per-user location convention as
     * {@code controls.json}.
     */
    public static final String RECORD_MOVIE_PROPERTY = "delones.recordMovie";
    /** Movie output dir, relative to LibGDX external storage (the user home). */
    static final String MOVIES_EXTERNAL_DIR = ".deloNES/movies/";
    private InputRecorder movieRecorder;
    private String romSha256Hex;

    // State.
    private boolean paused = false;
    private int frameCount = 0;
    private boolean shown = false;
    /**
     * Set when {@link #loadROM()} throws inside {@link #show()}. While true,
     * {@link #render(float)} is a no-op so the half-initialised emulator
     * never executes (no NPEs from null cpu/ppu/nesSystem). The {@code show()}
     * path also fires {@code onExit} after disposing partial state, bouncing
     * the user back to the menu rather than bricking the app.
     */
    private boolean loadFailed = false;

    public EmulatorScreen(RomSource rom, Controller controller, Runnable onExit, boolean debugHud) {
        if (rom == null) {
            throw new IllegalArgumentException("rom must not be null");
        }
        if (controller == null) {
            throw new IllegalArgumentException("controller must not be null");
        }
        this.rom = rom;
        this.controller = controller;
        this.onExit = onExit;
        this.debugHud = debugHud;
    }

    // --- Public control surface (called by external input adapter) -------

    /** Toggles paused state. While paused, render() skips emulation but still re-blits. */
    public void togglePause() {
        paused = !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Soft-resets the CPU and PPU. */
    public void reset() {
        if (movieRecorder != null) {
            // A mid-run reset breaks the movie's power-on determinism contract
            // (v1 has no reset events) — abort loudly rather than desync.
            System.err.println("[EmulatorScreen] reset during movie recording — "
                    + "recording discarded (v1 movies are power-on-anchored)");
            movieRecorder = null;
        }
        if (cpu != null) {
            cpu.reset();
        }
        if (ppu != null) {
            ppu.reset();
        }
    }

    /** Invokes the {@code onExit} callback supplied at construction. Never calls {@code Gdx.app.exit()}. */
    public void requestExit() {
        if (onExit != null) {
            onExit.run();
        }
    }

    // Test/glue accessors.
    public Controller getController() { return controller; }
    public PPU getPpu() { return ppu; }
    public CPU6502 getCpu() { return cpu; }
    public int getFrameCount() { return frameCount; }
    public RomSource getRom() { return rom; }

    // --- Screen lifecycle -------------------------------------------------

    @Override
    public void show() {
        if (shown) {
            return;
        }
        shown = true;
        System.out.println("EmulatorScreen.show() — ROM: " + rom.displayName());

        // GL-dependent resources only when a real GL context is present.
        if (Gdx.gl != null) {
            batch = new SpriteBatch();
            font = new BitmapFont();
            pixelRenderer = new PixelRenderer(256, 240);
            palettePixmap = new Pixmap(32 * 20, 20, Pixmap.Format.RGBA8888);
            paletteTexture = new Texture(palettePixmap);
        }

        setupNESSystem();

        // Guard against bad-ROM bricking. LibGDX Game.setScreen() has already
        // swapped this.screen to us *before* calling show(), so if we let
        // loadROM() throw here the next render() runs on a half-initialised
        // instance (nesSystem/cpu/ppu may all be null) and NPEs forever —
        // user has no way back to the menu. Instead: log, mark loadFailed
        // (render() bails out), dispose any partial GL state, and invoke
        // onExit to bounce back to the menu.
        try {
            loadROM();
            initializeTestPattern();
        } catch (RuntimeException e) {
            loadFailed = true;
            System.err.println("EmulatorScreen: failed to load ROM '"
                    + rom.displayName() + "': " + e.getMessage());
            e.printStackTrace();
            dispose();
            if (onExit != null) {
                onExit.run();
            }
            return;
        }

        System.out.println("EmulatorScreen ready");
    }

    private void setupNESSystem() {
        ppu = new PPU();
        ppuBus = new PPUBus();
        // The PPU owns its NameTableMemory and registers it on the bus in
        // connectPPUBus(); loadROM()'s ppu.setCartridge() wires the cartridge
        // into it for mirroring. Do NOT connect a second NameTableMemory —
        // PPUBus routes to the first match, so an extra one would shadow the
        // PPU's and pin mirroring to the cartridge-less HORIZONTAL default.
        ppu.connectPPUBus(ppuBus);

        cpu = new CPU6502();
        Ram ram = new Ram();

        // NesSystem owns the CPUBus and orchestrates ticks (PPU + CPU + DMA + NMI
        // dispatch). We hold the bus reference for cartridge wiring in loadROM().
        nesSystem = NesSystem.builder()
                .cpu(cpu)
                .ram(ram)
                .ppu(ppu)
                .controller(controller)
                // APU wired to match the movie header's pinned romloader-v1
                // replay environment: without it, $4000-$4015 reads return 0
                // here but echo register values under replay — games polling
                // $4015 would branch differently and recorded movies would
                // silently desync (Phase D review finding).
                .apu(new net.lomibao.nes.components.APU())
                .dma(new DmaController())
                .build();
        cpuBus = nesSystem.getCpuBus();

        // NMI dispatch is owned by NesSystem (it polls PPU.consumeNmi() after
        // each tick). PPU.setCPU is a no-op stub retained for API compatibility
        // — we deliberately do NOT call it here, since the wiring isn't needed.
    }

    private void loadROM() {
        try (InputStream in = rom.open()) {
            InputStream cartridgeIn = in;
            if (movieRecordingEnabled()) {
                // Buffer the image so the movie header can pin its sha-256.
                byte[] romBytes = readAllBytesCompat(in);
                romSha256Hex = sha256Hex(romBytes);
                movieRecorder = new InputRecorder();
                cartridgeIn = new ByteArrayInputStream(romBytes);
                System.out.println("[EmulatorScreen] movie recording ON — rom-sha256 "
                        + romSha256Hex);
            }
            cartridge = new Cartridge(cartridgeIn, rom.displayName());

            // Power-on parity with the romloader-v1 replay environment: a
            // controller reused from a prior session must not carry stale
            // shift-register state into a recording.
            controller.resetShiftState();
            cpuBus.setCartridge(cartridge);
            ppu.setCartridge(cartridge);
            ppuBus.connectCartridge(cartridge);
            ppuBus.connectPPU(ppu);

            cpu.reset();
            ppu.reset();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load ROM: " + rom.displayName(), e);
        }
    }

    /** Mirrors the legacy renderer: seed a minimal palette/PPUMASK so the screen isn't blank. */
    private void initializeTestPattern() {
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        ppu.cpuBusWrite(0x2006, (byte) 0x3F);
        ppu.cpuBusWrite(0x2006, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x0F);
        ppu.cpuBusWrite(0x2007, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x10);
        ppu.cpuBusWrite(0x2007, (byte) 0x30);
    }

    @Override
    public void render(float delta) {
        // If the ROM failed to load in show() we've already disposed our
        // GL state and called onExit — but LibGDX may still drive one more
        // render() before the screen swap takes effect. Bail out so we
        // don't NPE on null nesSystem/cpu/ppu.
        if (loadFailed) {
            frameCount++;
            return;
        }

        if (Gdx.gl != null) {
            Gdx.gl.glClearColor(0.2f, 0.2f, 0.2f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        }

        if (!paused) {
            ppu.clearScreen();
            // D9: sample controller state at the exact frame boundary replay
            // applies input at — immediately before runFrame(). Paused frames
            // run no emulation and are therefore not sampled.
            if (movieRecorder != null) {
                movieRecorder.sampleFrame(controller);
            }
            runFrame();
        }

        // Always present the most recent buffer (allows seeing the last frame while paused).
        int[][] screen = ppu.getScreen();
        int[][] visibleScreen = new int[240][256];
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 256; x++) {
                int argb = screen[y][x];
                int rgba = (argb << 8) | (argb >>> 24);
                visibleScreen[y][x] = rgba;
            }
        }

        if (batch != null && pixelRenderer != null) {
            pixelRenderer.setPixels(visibleScreen);

            if (paletteTexture != null) {
                updatePaletteDisplay();
            }

            batch.begin();
            pixelRenderer.render(batch, 50, 50, 512, 480);
            if (debugHud) {
                if (paletteTexture != null) {
                    batch.draw(paletteTexture, 50, 15, 640, 20);
                }
                if (font != null) {
                    font.draw(batch, "NES - " + rom.displayName() + (paused ? "  [PAUSED]" : ""), 50, 550);
                    font.draw(batch, "Frame: " + frameCount, 50, 530);
                    font.draw(batch, "CPU PC: 0x" + Integer.toHexString(cpu.getPc()).toUpperCase(), 50, 510);
                    font.draw(batch, "PPU Scanline: " + ppu.getScanline() + " Cycle: " + ppu.getCycle(), 250, 530);
                    font.draw(batch, "Palette Memory (32 colors):", 50, 5);
                }
            }
            batch.end();
        }

        frameCount++;
    }

    private void runFrame() {
        // NesSystem.runFrame() drives PPU + CPU + DMA + NMI dispatch via
        // CPUBus.clock(). It throws IllegalStateException if the PPU wedges,
        // which surfaces as a loud failure rather than silent black-screen.
        nesSystem.runFrame();
    }

    private void updatePaletteDisplay() {
        palettePixmap.setColor(Color.BLACK);
        palettePixmap.fill();

        for (int i = 0; i < 32; i++) {
            int nesColor = ppu.getPaletteColor(i);
            int argbColor = convertNESColorToARGB(nesColor);
            int r = (argbColor >> 16) & 0xFF;
            int g = (argbColor >> 8) & 0xFF;
            int b = argbColor & 0xFF;
            palettePixmap.setColor(r / 255f, g / 255f, b / 255f, 1f);
            palettePixmap.fillRectangle(i * 20, 0, 20, 20);
        }

        paletteTexture.draw(palettePixmap, 0, 0);
    }

    private int convertNESColorToARGB(int nesColorIndex) {
        return NesMasterPalette.argb(nesColorIndex);
    }

    @Override
    public void resize(int width, int height) {
        // No-op for now; pixelRenderer scales via render() args.
    }

    @Override
    public void pause() {
        // LibGDX lifecycle pause (e.g. app minimized) — not the same as game-pause.
    }

    @Override
    public void resume() {
        // No-op.
    }

    @Override
    public void hide() {
        // No-op; dispose() is the cleanup hook.
    }

    // --- Movie recording plumbing (Phase D2) ------------------------------

    /**
     * Whether live input recording is on — the {@link #RECORD_MOVIE_PROPERTY}
     * debug flag. Protected so tests can force-enable without global state.
     */
    protected boolean movieRecordingEnabled() {
        return System.getProperty(RECORD_MOVIE_PROPERTY) != null;
    }

    /**
     * Where the finished movie is written:
     * {@code ~/.deloNES/movies/<fileName>} (controls.json convention).
     * Protected so tests can redirect to a temp dir.
     */
    protected com.badlogic.gdx.files.FileHandle resolveMovieFile(String fileName) {
        return Gdx.files.external(MOVIES_EXTERNAL_DIR + fileName);
    }

    /** Serialize and dump the recording, if any. Called from {@link #dispose()}. */
    private void dumpMovieIfRecording() {
        if (movieRecorder == null || movieRecorder.frames() == 0) {
            return;
        }
        try {
            String text = MovieFormat.serialize(
                    movieRecorder.toMovie(rom.displayName(), romSha256Hex));
            com.badlogic.gdx.files.FileHandle out =
                    resolveMovieFile(sanitizeFileName(rom.displayName()) + ".dmov");
            com.badlogic.gdx.files.FileHandle parent = out.parent();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out.writeString(text, false, "UTF-8");
            System.out.println("[EmulatorScreen] movie written: "
                    + out.file().getAbsolutePath()
                    + " (" + movieRecorder.frames() + " frames)");
        } catch (RuntimeException e) {
            System.err.println("[EmulatorScreen] WARNING: failed to write movie: "
                    + e.getMessage());
        } finally {
            movieRecorder = null;
        }
    }

    /** Strip path separators so a ROM display name is a safe file name. */
    private static String sanitizeFileName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append((c == '/' || c == '\\' || c == ':') ? '_' : c);
        }
        return sb.toString();
    }

    /** Java-8-source-safe InputStream drain (no InputStream.readAllBytes). */
    private static byte[] readAllBytesCompat(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512 * 1024);
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /** sha-256 of the ROM image as 64 lowercase hex chars (movie header pin). */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM lacks SHA-256", e);
        }
    }

    @Override
    public void dispose() {
        dumpMovieIfRecording();
        if (batch != null) { batch.dispose(); batch = null; }
        if (font != null) { font.dispose(); font = null; }
        if (pixelRenderer != null) { pixelRenderer.dispose(); pixelRenderer = null; }
        if (paletteTexture != null) { paletteTexture.dispose(); paletteTexture = null; }
        if (palettePixmap != null) { palettePixmap.dispose(); palettePixmap = null; }
        shown = false;
    }
}
