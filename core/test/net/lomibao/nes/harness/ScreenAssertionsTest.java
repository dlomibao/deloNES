package net.lomibao.nes.harness;

import net.lomibao.nes.components.PPU;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C2 — pixel/region assertions (headless-harness plan, revision 2).
 * D8: plain {@link AssertionError}, exact golden matches (the emulator is
 * deterministic; tolerance would hide one-pixel regressions), failure
 * artifacts (actual + XOR-diff PNG) written next to the report with their
 * paths in the error message.
 */
class ScreenAssertionsTest {

    /** A PPU whose visible screen is a deterministic gradient pattern. */
    private static PPU patternPpu() {
        PPU ppu = new PPU();
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 256; x++) {
                ppu.setPixel(x, y, 0xFF000000 | (x << 16) | (y << 8) | ((x + y) & 0xFF));
            }
        }
        return ppu;
    }

    // -------------------------------------------------------------------------
    // assertPixel
    // -------------------------------------------------------------------------

    @Test
    void assertPixel_matchingArgb_passes() {
        ScreenCapture cap = ScreenCapture.of(patternPpu());
        assertDoesNotThrow(() -> cap.assertPixel(10, 20, 0xFF0A141E));
    }

    @Test
    void assertPixel_mismatch_throwsPlainAssertionErrorWithCoordsAndHex() {
        ScreenCapture cap = ScreenCapture.of(patternPpu());
        AssertionError e = assertThrows(AssertionError.class,
                () -> cap.assertPixel(10, 20, 0xFFFFFFFF));
        assertEquals(AssertionError.class, e.getClass(), "must be plain AssertionError (D8)");
        assertTrue(e.getMessage().contains("(10,20)"), e.getMessage());
        assertTrue(e.getMessage().contains("FFFFFFFF"), e.getMessage());
        assertTrue(e.getMessage().contains("FF0A141E"), e.getMessage());
    }

    // -------------------------------------------------------------------------
    // Golden loading
    // -------------------------------------------------------------------------

    @Test
    void goldenPng_missingResource_throwsWithResourcePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScreenAssertions.goldenPng("no-such-golden.png"));
        assertTrue(e.getMessage().contains("/golden/no-such-golden.png"), e.getMessage());
    }

    // -------------------------------------------------------------------------
    // assertRegionEquals
    // -------------------------------------------------------------------------

    @Test
    void assertRegionEquals_identicalFullScreen_passes(@TempDir Path dir) {
        ScreenCapture cap = ScreenCapture.of(patternPpu());
        Path png = cap.savePng(dir.resolve("full.png"));
        ScreenAssertions.Golden golden = ScreenAssertions.goldenFromFile(png);
        assertDoesNotThrow(() -> cap.assertRegionEquals(golden, 0, 0, 256, 240));
    }

    @Test
    void assertRegionEquals_subRegionAtOffset_comparesOnlyThatRegion(@TempDir Path dir) throws Exception {
        ScreenCapture cap = ScreenCapture.of(patternPpu());
        // Golden = the 32x16 region at (100, 50), cropped from the capture.
        BufferedImage crop = cap.toImage().getSubimage(100, 50, 32, 16);
        Path png = dir.resolve("crop.png");
        ImageIO.write(crop, "png", png.toFile());
        ScreenAssertions.Golden golden = ScreenAssertions.goldenFromFile(png);

        assertDoesNotThrow(() -> cap.assertRegionEquals(golden, 100, 50, 32, 16));
        // Same golden compared at the wrong offset must fail.
        assertThrows(AssertionError.class, () -> cap.assertRegionEquals(golden, 101, 50, 32, 16));
    }

    @Test
    void assertRegionEquals_mismatch_writesActualAndDiffArtifactsAndNamesThemInMessage(@TempDir Path dir) throws Exception {
        PPU ppu = patternPpu();
        ScreenCapture golden = ScreenCapture.of(ppu);
        Path goldenPng = golden.savePng(dir.resolve("mismatch-golden.png"));

        ppu.setPixel(7, 3, 0xFF123456); // one-pixel regression
        ScreenCapture actual = ScreenCapture.of(ppu);

        AssertionError e = assertThrows(AssertionError.class, () -> actual.assertRegionEquals(
                ScreenAssertions.goldenFromFile(goldenPng), 0, 0, 256, 240));

        Path outDir = ScreenCapture.defaultOutputDir();
        Path actualPng = outDir.resolve("mismatch-golden-actual.png");
        Path diffPng = outDir.resolve("mismatch-golden-diff.png");
        assertTrue(Files.exists(actualPng), "actual artifact missing: " + actualPng);
        assertTrue(Files.exists(diffPng), "diff artifact missing: " + diffPng);
        assertTrue(e.getMessage().contains("mismatch-golden-actual.png"), e.getMessage());
        assertTrue(e.getMessage().contains("mismatch-golden-diff.png"), e.getMessage());
        // First mismatch is reported with coordinates and both values.
        assertTrue(e.getMessage().contains("(7,3)"), e.getMessage());
        assertTrue(e.getMessage().contains("FF123456"), e.getMessage());
    }

    @Test
    void assertRegionEquals_diffArtifact_isXorOfRgbOpaqueElsewhereBlack(@TempDir Path dir) throws Exception {
        PPU ppu = patternPpu();
        ScreenCapture golden = ScreenCapture.of(ppu);
        Path goldenPng = golden.savePng(dir.resolve("xor-golden.png"));
        int old = golden.pixel(7, 3);

        ppu.setPixel(7, 3, 0xFF123456);
        ScreenCapture actual = ScreenCapture.of(ppu);
        assertThrows(AssertionError.class, () -> actual.assertRegionEquals(
                ScreenAssertions.goldenFromFile(goldenPng), 0, 0, 256, 240));

        BufferedImage diff = ImageIO.read(
                ScreenCapture.defaultOutputDir().resolve("xor-golden-diff.png").toFile());
        int expectedXor = 0xFF000000 | ((old ^ 0xFF123456) & 0xFFFFFF);
        assertEquals(expectedXor, diff.getRGB(7, 3), "diff pixel must be RGB xor, opaque");
        assertEquals(0xFF000000, diff.getRGB(0, 0), "matching pixels must be opaque black");
    }

    @Test
    void assertRegionEquals_goldenDimensionMismatch_failsWithClearMessage(@TempDir Path dir) throws Exception {
        ScreenCapture cap = ScreenCapture.of(patternPpu());
        BufferedImage crop = cap.toImage().getSubimage(0, 0, 32, 16);
        Path png = dir.resolve("dims.png");
        ImageIO.write(crop, "png", png.toFile());

        AssertionError e = assertThrows(AssertionError.class, () -> cap.assertRegionEquals(
                ScreenAssertions.goldenFromFile(png), 0, 0, 64, 16));
        assertTrue(e.getMessage().contains("32x16"), e.getMessage());
        assertTrue(e.getMessage().contains("64x16"), e.getMessage());
    }

    @Test
    void assertRegionEquals_regionOutsideScreen_throwsIllegalArgument(@TempDir Path dir) throws Exception {
        ScreenCapture cap = ScreenCapture.of(patternPpu());
        BufferedImage crop = cap.toImage().getSubimage(0, 0, 32, 16);
        Path png = dir.resolve("oob.png");
        ImageIO.write(crop, "png", png.toFile());
        ScreenAssertions.Golden golden = ScreenAssertions.goldenFromFile(png);

        assertThrows(IllegalArgumentException.class,
                () -> cap.assertRegionEquals(golden, 240, 0, 32, 16));
        assertThrows(IllegalArgumentException.class,
                () -> cap.assertRegionEquals(golden, -1, 0, 32, 16));
    }
}
