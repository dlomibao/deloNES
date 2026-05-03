package net.lomibao.nes.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.PPUBus;
import net.lomibao.nes.components.Ram;
import net.lomibao.nes.components.ppu.NameTableMemory;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * LibGDX {@link ApplicationAdapter} that runs an arbitrary NES ROM at
 * NTSC pace and uploads each frame to a window-filling texture.
 * Step 8 of the playable-gen1 plan — the shipping demo path.
 *
 * <p>Frame budget: each {@code render()} call invokes
 * {@link NesSystem#advance(double)} with the LibGDX delta-time. NesSystem
 * runs as many full frames as the elapsed time covers (capped) and our
 * {@link com.badlogic.gdx.utils.viewport.Viewport}-free draw simply
 * uploads the latest framebuffer to a single reusable {@link Texture}.
 */
public class RomRenderer extends ApplicationAdapter {

    private final String romPath;

    private NesSystem sys;
    private Controller controller;
    private SpriteBatch batch;
    private Pixmap framePixmap;
    private Texture frameTexture;

    public RomRenderer(String romPath) {
        this.romPath = romPath;
    }

    @Override
    public void create() {
        System.out.println("Loading ROM: " + romPath);
        Cartridge cartridge;
        try (InputStream in = new FileInputStream(romPath)) {
            cartridge = new Cartridge(in, romPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load ROM " + romPath, e);
        }

        // Wire the system. PPU needs its own bus for CHR/nametable/palette;
        // CPUBus connects everything else and routes $4014 to DMA.
        PPU ppu = new PPU();
        PPUBus ppuBus = new PPUBus();
        NameTableMemory nameTables = new NameTableMemory();
        nameTables.setCartridge(cartridge);
        ppuBus.connect(nameTables);
        ppuBus.connectCartridge(cartridge);
        ppu.connectPPUBus(ppuBus);
        ppuBus.connectPPU(ppu);
        ppu.setCartridge(cartridge); // for CHR access in some legacy paths

        controller = new Controller();
        sys = NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(ppu)
                .cartridge(cartridge)
                .controller(controller)
                .dma(new DmaController())
                .build();
        sys.reset();

        batch = new SpriteBatch();
        framePixmap = new Pixmap(PPU.VISIBLE_WIDTH, PPU.VISIBLE_HEIGHT, Pixmap.Format.RGBA8888);
        frameTexture = new Texture(framePixmap);
        System.out.println("RomRenderer ready: " + PPU.VISIBLE_WIDTH + "x" + PPU.VISIBLE_HEIGHT + " framebuffer");
    }

    @Override
    public void render() {
        // Poll input every frame from the LibGDX render thread.
        KeyboardInput.pollAll(controller);

        // Time-driven pacing: NesSystem runs as many emulated frames as
        // the host's delta time covers (capped to avoid death spirals).
        sys.advance(Gdx.graphics.getDeltaTime());

        // Upload the latest frame to the GPU texture.
        uploadFrame();

        // Draw it window-filling.
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.begin();
        batch.draw(frameTexture, 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        batch.end();
    }

    /**
     * Copy the PPU's int-RGBA screen buffer into the {@link Pixmap}'s
     * native byte buffer, then upload as a texture.
     */
    private void uploadFrame() {
        int[][] screen = sys.getPpu().getScreen();
        for (int y = 0; y < PPU.VISIBLE_HEIGHT; y++) {
            int[] row = screen[y];
            for (int x = 0; x < PPU.VISIBLE_WIDTH; x++) {
                framePixmap.drawPixel(x, y, row[x]);
            }
        }
        frameTexture.draw(framePixmap, 0, 0);
    }

    @Override
    public void dispose() {
        if (batch != null) batch.dispose();
        if (frameTexture != null) frameTexture.dispose();
        if (framePixmap != null) framePixmap.dispose();
    }
}
