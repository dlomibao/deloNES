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
import net.lomibao.nes.render.NesMasterPalette;
import net.lomibao.nes.render.PixelRenderer;

import java.io.InputStream;

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
                .dma(new DmaController())
                .build();
        cpuBus = nesSystem.getCpuBus();

        // NMI dispatch is owned by NesSystem (it polls PPU.consumeNmi() after
        // each tick). PPU.setCPU is a no-op stub retained for API compatibility
        // — we deliberately do NOT call it here, since the wiring isn't needed.
    }

    private void loadROM() {
        try (InputStream in = rom.open()) {
            cartridge = new Cartridge(in, rom.displayName());

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

    @Override
    public void dispose() {
        if (batch != null) { batch.dispose(); batch = null; }
        if (font != null) { font.dispose(); font = null; }
        if (pixelRenderer != null) { pixelRenderer.dispose(); pixelRenderer = null; }
        if (paletteTexture != null) { paletteTexture.dispose(); paletteTexture = null; }
        if (palettePixmap != null) { palettePixmap.dispose(); palettePixmap = null; }
        shown = false;
    }
}
