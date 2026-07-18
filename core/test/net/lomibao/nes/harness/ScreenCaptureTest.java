package net.lomibao.nes.harness;

import net.lomibao.nes.components.PPU;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C1 — {@link ScreenCapture} (headless-harness plan, revision 2).
 *
 * <p>The named regression risk is channel order: the PPU framebuffer ints
 * are <b>ARGB</b> ({@code 0xAARRGGBB} — see {@code PPU.NES_COLOR_PALETTE}
 * entries like {@code 0xFF545454} and {@code clearScreen()}'s
 * {@code 0xFF000000} "black with full alpha"; desktop hosts convert with
 * {@code (argb << 8) | (argb >>> 24)} before handing LibGDX its RGBA8888).
 * A red framebuffer pixel must come back red from the PNG bytes — the
 * 2026-01-01 alpha-flip bug class (CLAUDE.md) is what these tests pin.
 */
class ScreenCaptureTest {

    private static final int ARGB_RED = 0xFFFF0000;
    private static final int ARGB_GREEN = 0xFF00FF00;
    private static final int ARGB_BLUE = 0xFF0000FF;

    @Test
    void capture_dimensionsAreVisibleScreen256x240() {
        ScreenCapture cap = ScreenCapture.of(new PPU());
        assertEquals(256, cap.width());
        assertEquals(240, cap.height());
    }

    @Test
    void pixel_returnsFramebufferArgbValue() {
        PPU ppu = new PPU();
        ppu.setPixel(10, 20, ARGB_RED);
        ScreenCapture cap = ScreenCapture.of(ppu);
        assertEquals(ARGB_RED, cap.pixel(10, 20));
    }

    @Test
    void pixel_outOfBounds_throws() {
        ScreenCapture cap = ScreenCapture.of(new PPU());
        assertThrows(IllegalArgumentException.class, () -> cap.pixel(256, 0));
        assertThrows(IllegalArgumentException.class, () -> cap.pixel(0, 240));
        assertThrows(IllegalArgumentException.class, () -> cap.pixel(-1, 0));
    }

    @Test
    void capture_isSnapshot_notLiveView() {
        PPU ppu = new PPU();
        ppu.setPixel(5, 5, ARGB_RED);
        ScreenCapture cap = ScreenCapture.of(ppu);
        ppu.setPixel(5, 5, ARGB_BLUE);
        assertEquals(ARGB_RED, cap.pixel(5, 5), "capture must snapshot, not alias, the framebuffer");
    }

    /**
     * The channel-order regression test: a red/green/blue framebuffer pixel
     * is red/green/blue in the decoded PNG. Any ARGB/RGBA/ABGR swap turns
     * red into blue (or transparent) and fails loudly here.
     */
    @Test
    void channelOrder_redGreenBlue_surviveToPngAndBack(@TempDir Path dir) throws Exception {
        PPU ppu = new PPU();
        ppu.setPixel(0, 0, ARGB_RED);
        ppu.setPixel(1, 0, ARGB_GREEN);
        ppu.setPixel(2, 0, ARGB_BLUE);

        Path png = ScreenCapture.of(ppu).savePng(dir.resolve("channels.png"));
        BufferedImage read = ImageIO.read(png.toFile());
        assertNotNull(read, "saved file must be a decodable PNG");

        // BufferedImage.getRGB returns ARGB — same convention as the framebuffer.
        assertEquals(ARGB_RED, read.getRGB(0, 0), "red pixel must stay red");
        assertEquals(ARGB_GREEN, read.getRGB(1, 0), "green pixel must stay green");
        assertEquals(ARGB_BLUE, read.getRGB(2, 0), "blue pixel must stay blue");
    }

    @Test
    void savePng_roundTrip_everyPixelIdentical(@TempDir Path dir) throws Exception {
        PPU ppu = new PPU();
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 256; x++) {
                ppu.setPixel(x, y, 0xFF000000 | (x << 16) | (y << 8) | ((x ^ y) & 0xFF));
            }
        }
        ScreenCapture cap = ScreenCapture.of(ppu);
        Path png = cap.savePng(dir.resolve("roundtrip.png"));
        BufferedImage read = ImageIO.read(png.toFile());
        assertEquals(256, read.getWidth());
        assertEquals(240, read.getHeight());
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 256; x++) {
                if (cap.pixel(x, y) != read.getRGB(x, y)) {
                    assertEquals(cap.pixel(x, y), read.getRGB(x, y),
                            "pixel mismatch at (" + x + "," + y + ")");
                }
            }
        }
    }

    @Test
    void savePng_createsMissingParentDirectories(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("a/b/c/frame.png");
        Path written = ScreenCapture.of(new PPU()).savePng(nested);
        assertEquals(nested, written);
        assertTrue(Files.exists(nested));
    }

    /** Plan C1: default output dir is {@code build/test-output/<testClass>/}. */
    @Test
    void savePng_byFileName_landsInPerTestClassDefaultOutputDir() throws Exception {
        Path png = ScreenCapture.of(new PPU()).savePng("default-dir-smoke.png");
        assertTrue(Files.exists(png), "expected " + png + " to exist");
        assertEquals("ScreenCaptureTest", png.getParent().getFileName().toString());
        assertEquals("test-output", png.getParent().getParent().getFileName().toString());
        assertEquals("build", png.getParent().getParent().getParent().getFileName().toString());
    }

    /** The harness facade exposes capture as {@code h.screen()} (plan API sketch). */
    @Test
    void harnessScreen_returnsCaptureOfCurrentFrame() {
        NesHarness h = NesHarnessTestRoms.controllerPollHarness();
        h.runFrames(1);
        ScreenCapture cap = h.screen();
        assertNotNull(cap);
        assertEquals(256, cap.width());
        // Framebuffer starts black-with-full-alpha; whatever the frame drew,
        // alpha must be opaque (the alpha-flip bug renders it 0).
        assertEquals(0xFF, (cap.pixel(0, 0) >>> 24), "alpha channel must be opaque");
    }

    @Test
    void toImage_isArgbAndIndependentCopy() {
        PPU ppu = new PPU();
        ppu.setPixel(3, 4, ARGB_GREEN);
        ScreenCapture cap = ScreenCapture.of(ppu);
        BufferedImage img = cap.toImage();
        assertEquals(BufferedImage.TYPE_INT_ARGB, img.getType());
        assertEquals(ARGB_GREEN, img.getRGB(3, 4));
        img.setRGB(3, 4, ARGB_BLUE);
        assertNotEquals(ARGB_BLUE, cap.pixel(3, 4), "toImage must copy, not wrap, the capture");
    }
}
