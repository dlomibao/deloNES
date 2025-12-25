package net.lomibao.nes;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.CPUBus;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.FullAddressRam;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import net.lomibao.nes.components.ppu.ColorPalette;
import net.lomibao.nes.render.PixelRenderer;
import net.lomibao.nes.render.PixelRendererTest;

@Log4j2
public class NesEmulator extends ApplicationAdapter {
    public static final boolean ENABLE_TEST_RAM = false;// test ram covers the whole address range and gets top priority
                                                        // if enabled
    public static final boolean ENABLE_TEST_CARTRIDGE = true;
    SpriteBatch batch;
    Texture img;
    BitmapFont font;

    CPUBus cpuBus;
    PixelRenderer pixelRenderer;

    public void setup() {
        cpuBus = CPUBus.builder()
                .cpu(new CPU6502())
                .testRam(ENABLE_TEST_RAM ? new FullAddressRam() : null)// test ram covers full address range and gets
                                                                       // top priority for reading/writing to an address
                .ram(new Ram())
                .ppu(new PPU())
                .build()
                .connect();

        log.info(cpuBus);
        ColorPalette colorPalette = new ColorPalette(this.getClass().getResourceAsStream("/palettes/ntscpalette.pal"),
                "ntscpalette.pal");

    }

    public static byte[] hexStringtoByteArray(String hexString) {
        String[] hexVals = hexString.split(" ");
        byte[] byteArray = new byte[hexVals.length];
        for (int i = 0; i < hexVals.length; i++) {
            byteArray[i] = (byte) Integer.parseInt(hexVals[i], 16);
        }
        return byteArray;
    }

    @Override
    public void create() {
        setup();
        if (ENABLE_TEST_CARTRIDGE) {
            try {
                cpuBus.setCartridge(new Cartridge(this.getClass().getResourceAsStream("/nestest.nes"), "nestest.nes"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        batch = new SpriteBatch();
        img = new Texture("badlogic.jpg");
        // font = new BitmapFont(Gdx.files.internal("SpaceMono-Regular.ttf"),false);
        // font.getData().setScale(0.2f);
        // FreeTypeFontGenerator generator= new FreeType
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.internal("SpaceMono-Regular.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        parameter.size = 24; // Font size
        parameter.color = Color.WHITE; // Font color
        font = generator.generateFont(parameter);
        generator.dispose();

        pixelRenderer = new PixelRenderer(256, 240);
        int[] testPixels = new int[256 * 240];
        for (int i = 0; i < testPixels.length; i++) {
            int x = i % 256;
            int y = i / 256;
            // Create a simple gradient pattern
            int r = (x * 255 / 256);
            int g = (y * 255 / 240);
            int b = 128;
            testPixels[i] = (r << 24) | (g << 16) | (b << 8) | 0xFF;
        }
        pixelRenderer.setPixels(testPixels);

        // Add line drawing test
        PixelRendererTest.runLineDrawingTest(pixelRenderer);
    }

    public int i = 0;

    @Override
    public void render() {
        i++;
        // ScreenUtils.clear(1, 0, 0, .1f);
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.begin();
        // batch.draw(img, i / 10, 0);
        pixelRenderer.render(batch, 50, 50, 512, 480); // Scaled for visibility
        font.draw(batch, "PixelRenderer Test", 100, 200);
        batch.end();

    }

    @Override
    public void dispose() {
        batch.dispose();
        img.dispose();
        if (pixelRenderer != null) {
            pixelRenderer.dispose();
        }
    }
}
