package net.lomibao.nes.components;

import net.lomibao.nes.components.ppu.NameTableMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SpriteRenderer} (Step 5 of the playable-gen1 plan).
 *
 * <p>Tests drive the renderer directly via {@code SpriteRenderer.render(ppu)}
 * — no full-frame clock loop. Each test sets up a controlled PPU + CHR
 * environment, populates OAM and palette RAM, then asserts screen pixels.
 *
 * <h2>Contract covered</h2>
 * <ul>
 *   <li>PPUMASK bit 4 (sprites enabled) gates the entire pass.</li>
 *   <li>PPUMASK bit 2 (sprites in left 8) clips columns 0..7.</li>
 *   <li>OAM is iterated in reverse so lower indices win z-order.</li>
 *   <li>Sprite pattern pixel value 0 is transparent (skipped).</li>
 *   <li>Attribute byte: bit 5 priority, bit 6 h-flip, bit 7 v-flip,
 *       bits 0-1 sprite-palette select (palettes 4-7 in palette RAM).</li>
 *   <li>OAM byte 0 is "Y - 1" — sprite is drawn one scanline LOWER than
 *       the value indicates.</li>
 *   <li>8x16 mode: PPUCTRL bit 5 selects size; tile-id bit 0 selects
 *       pattern table (NOT PPUCTRL bit 3 in 8x16 mode).</li>
 * </ul>
 */
class SpriteRendererTest {

    private PPU ppu;
    private FakeChr chr;

    /** Test-only CHR provider — 8KB of patternable bytes wired to the PPU bus at $0000-$1FFF. */
    static class FakeChr implements PPUBusComponent {
        final byte[] data = new byte[0x2000];
        @Override public void connectPPUBus(PPUBus ppuBus) {}
        @Override public PPUBus getPPUBus() { return null; }
        @Override public int ppuBusRead(int address, boolean readOnly) { return Byte.toUnsignedInt(data[address & 0x1FFF]); }
        @Override public void ppuBusWrite(int address, byte value) { data[address & 0x1FFF] = value; }
        @Override public int getPPUBusStartAddress() { return 0x0000; }
        @Override public int getPPUBusEndAddress() { return 0x2000; }
    }

    @BeforeEach
    void setUp() {
        ppu = new PPU();
        chr = new FakeChr();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(chr);
        ppuBus.connect(new NameTableMemory());
        ppu.connectPPUBus(ppuBus);
        ppuBus.connectPPU(ppu);
    }

    /** Write a palette-RAM byte via the standard $2006 / $2007 protocol. */
    private void writePalette(int paletteAddr, int nesColor) {
        // Address: $3F00 + paletteAddr
        int target = 0x3F00 + (paletteAddr & 0x1F);
        ppu.cpuBusWrite(0x2006, (byte) ((target >> 8) & 0xFF));
        ppu.cpuBusWrite(0x2006, (byte) (target & 0xFF));
        ppu.cpuBusWrite(0x2007, (byte) (nesColor & 0x3F));
    }

    /** Place an 8x8 tile at the given index in the CHR pattern table. */
    private void putTile(int patternTableBase, int tileIndex, byte[] lowPlane, byte[] highPlane) {
        int base = patternTableBase + tileIndex * 16;
        for (int row = 0; row < 8; row++) {
            chr.data[base + row]     = lowPlane[row];
            chr.data[base + 8 + row] = highPlane[row];
        }
    }

    /** Write a sprite's 4 OAM bytes at the given OAM index (0..63). */
    private void putSprite(int oamIdx, int y, int tileId, int attr, int x) {
        int base = oamIdx * 4;
        ppu.writeOam(base,     (byte) y);
        ppu.writeOam(base + 1, (byte) tileId);
        ppu.writeOam(base + 2, (byte) attr);
        ppu.writeOam(base + 3, (byte) x);
    }

    /** "All pixels = pattern value 1" — solid colour using bits low=FF, high=00. */
    private static byte[] solidLowPlane() { return new byte[]{(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF}; }
    private static byte[] zeroPlane()     { return new byte[]{0,0,0,0,0,0,0,0}; }

    /** Mark all 64 OAM slots invisible (Y=0xFF puts them off-screen). Call before installing test sprites. */
    private void clearOam() {
        for (int i = 0; i < 256; i += 4) ppu.writeOam(i, (byte) 0xFF);
    }

    /** Enable sprite rendering (PPUMASK bit 4) and sprites-in-left-8 (bit 2). */
    private void enableSprites() {
        ppu.cpuBusWrite(0x2001, (byte) (0x10 | 0x04));
    }

    private void assertPixelMatchesPaletteEntry(int x, int y, int paletteRamIdx) {
        int actualRgb = ppu.getScreen()[y][x];
        // We don't reproduce the NES palette here — just assert that the
        // pixel at (x,y) is the same RGB as a known reference from the
        // PPU's own palette pipeline.
        int expectedRgb = ppu.getPaletteColorRgb(paletteRamIdx);
        assertEquals(expectedRgb, actualRgb,
                String.format("pixel (%d,%d): expected palette entry $%02X (RGB %08X), got RGB %08X",
                        x, y, paletteRamIdx, expectedRgb, actualRgb));
    }

    // ===== TESTS =====

    @Test
    void disabledSprites_inPpuMaskBit4Clear_renderNoSprites() {
        clearOam();
        // PPUMASK bit 4 clear → sprites should be skipped entirely
        ppu.cpuBusWrite(0x2001, (byte) 0x00);

        // Solid sprite at (50, 50)
        putTile(0x0000, 1, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16); // sprite palette 0, color 1 = NES color $16 (red-ish)
        putSprite(0, 49, 1, 0x00, 50);

        // Snapshot screen, render, compare
        int sample = ppu.getScreen()[50][50];
        SpriteRenderer.render(ppu);
        assertEquals(sample, ppu.getScreen()[50][50],
                "sprites disabled — pixel must be unchanged");
    }

    @Test
    void singleSprite_writesPixelsAtExpectedScreenCoords() {
        clearOam();
        enableSprites();
        // Tile 1: solid pattern-pixel-1 (low plane all 1s, high plane all 0s)
        putTile(0x0000, 1, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16);
        // OAM Y=49 → sprite top is scanline 50 (Y + 1 convention)
        putSprite(0, 49, 1, 0x00, 32);

        SpriteRenderer.render(ppu);

        // Expect 8x8 block of palette $11 starting at (32, 50)
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                assertPixelMatchesPaletteEntry(32 + dx, 50 + dy, 0x11);
            }
        }
    }

    @Test
    void transparentPixels_value0_areSkipped() {
        clearOam();
        enableSprites();
        // Pattern: only the leftmost column has pattern-pixel = 1, rest = 0
        byte[] low = new byte[]{(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80};
        putTile(0x0000, 2, low, zeroPlane());
        writePalette(0x11, 0x16);
        putSprite(0, 49, 2, 0x00, 100);

        // Snapshot the would-be-untouched pixel BEFORE render
        int unchangedBefore = ppu.getScreen()[50][101]; // column 1 of sprite — should stay
        SpriteRenderer.render(ppu);

        // Column 0 of sprite (x=100) gets palette $11
        assertPixelMatchesPaletteEntry(100, 50, 0x11);
        // Columns 1..7 are transparent (value 0) → unchanged
        for (int dx = 1; dx < 8; dx++) {
            assertEquals(unchangedBefore, ppu.getScreen()[50][100 + dx],
                    "column " + dx + " should be transparent");
        }
    }

    @Test
    void horizontalFlip_mirrorsLeftRight() {
        clearOam();
        enableSprites();
        // Single column on the LEFT (bit 7 = MSB, drawn at column 0 normally)
        byte[] low = new byte[]{(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80};
        putTile(0x0000, 3, low, zeroPlane());
        writePalette(0x11, 0x16);
        // Attribute bit 6 = horizontal flip → column 0 should appear on the RIGHT
        putSprite(0, 49, 3, 0x40, 200);

        int rightBefore = ppu.getScreen()[50][207];
        SpriteRenderer.render(ppu);
        assertPixelMatchesPaletteEntry(207, 50, 0x11);
        // Column 0 of sprite (x=200) should be untouched after flip (transparent now)
        assertEquals(rightBefore, ppu.getScreen()[50][200],
                "after h-flip, column 0 should be transparent (was on the right)");
    }

    @Test
    void verticalFlip_mirrorsTopBottom() {
        clearOam();
        enableSprites();
        // Single row on the TOP (row 0 has pixels, rows 1-7 = 0)
        byte[] low = new byte[]{(byte)0xFF, 0, 0, 0, 0, 0, 0, 0};
        putTile(0x0000, 4, low, zeroPlane());
        writePalette(0x11, 0x16);
        // Attribute bit 7 = vertical flip → row 0 should appear at the BOTTOM
        putSprite(0, 49, 4, 0x80, 50);

        int topBefore = ppu.getScreen()[50][50];
        SpriteRenderer.render(ppu);
        // Row 0 of sprite (y=50) — should be transparent after flip
        assertEquals(topBefore, ppu.getScreen()[50][50],
                "after v-flip, sprite top row should be transparent");
        // Row 7 of sprite (y=57) — should now have the pattern
        for (int dx = 0; dx < 8; dx++) {
            assertPixelMatchesPaletteEntry(50 + dx, 57, 0x11);
        }
    }

    @Test
    void lowerOamIndex_drawsOnTopOfHigherIndex() {
        clearOam();
        enableSprites();
        putTile(0x0000, 5, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16); // sprite palette 0, color 1 = $16
        writePalette(0x15, 0x2C); // sprite palette 1, color 1 = $2C

        // Two overlapping sprites at the same coords. Sprite 0 = palette 0,
        // sprite 1 = palette 1. Lower index should win.
        putSprite(1, 49, 5, 0x01, 100); // index 1, palette-attr 1
        putSprite(0, 49, 5, 0x00, 100); // index 0, palette-attr 0

        SpriteRenderer.render(ppu);
        // Sprite 0 (palette 0 → palette RAM $11) wins
        assertPixelMatchesPaletteEntry(100, 50, 0x11);
    }

    @Test
    void mask_disablesSpritesInLeft8Pixels_whenBit2Clear() {
        clearOam();
        // Bit 4 set (sprites enabled), bit 2 CLEAR (no sprites in left 8)
        ppu.cpuBusWrite(0x2001, (byte) 0x10);
        putTile(0x0000, 6, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16);
        // Sprite at x=4 → spans columns 4..11
        putSprite(0, 49, 6, 0x00, 4);

        int leftBefore  = ppu.getScreen()[50][4];
        int rightBefore = ppu.getScreen()[50][8];
        SpriteRenderer.render(ppu);
        // Columns 4-7 should be unchanged (in left 8, sprites masked)
        for (int x = 4; x < 8; x++) {
            assertEquals(leftBefore, ppu.getScreen()[50][x],
                    "column " + x + " in left 8 should be masked");
        }
        // Columns 8-11 should be drawn
        for (int x = 8; x < 12; x++) {
            assertPixelMatchesPaletteEntry(x, 50, 0x11);
        }
        // Sanity: pre-render state at column 8 was the same as column 4
        assertEquals(leftBefore, rightBefore, "precondition: pre-render screen was uniform");
    }

    @Test
    void mask_enablesSpritesInLeft8Pixels_whenBit2Set() {
        clearOam();
        enableSprites(); // bit 2 + bit 4 set
        putTile(0x0000, 7, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16);
        putSprite(0, 49, 7, 0x00, 4);
        SpriteRenderer.render(ppu);
        // All 8 columns drawn
        for (int x = 4; x < 12; x++) {
            assertPixelMatchesPaletteEntry(x, 50, 0x11);
        }
    }

    @Test
    void priorityFront_spriteOverridesBg() {
        clearOam();
        enableSprites();
        putTile(0x0000, 8, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16); // sprite color
        // Simulate background opaque pixel under the sprite: write a non-zero
        // pattern value into the bg shadow
        ppu.setBgPatternPixelForTest(50, 100, 2);

        putSprite(0, 49, 8, 0x00, 100); // attr bit 5 = 0 → priority FRONT

        SpriteRenderer.render(ppu);
        // Sprite wins — pixel should be sprite color
        assertPixelMatchesPaletteEntry(100, 50, 0x11);
    }

    @Test
    void priorityBack_spriteHiddenWhereBgPixelOpaque() {
        clearOam();
        enableSprites();
        putTile(0x0000, 9, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16);
        ppu.setBgPatternPixelForTest(50, 100, 2); // bg opaque

        // attr bit 5 = 1 → priority BEHIND
        int unchangedBefore = ppu.getScreen()[50][100];
        putSprite(0, 49, 9, 0x20, 100);

        SpriteRenderer.render(ppu);
        // Bg wins where bg is opaque
        assertEquals(unchangedBefore, ppu.getScreen()[50][100],
                "behind-priority sprite should not overwrite opaque bg pixel");
    }

    @Test
    void priorityBack_spriteVisibleWhereBgPixelTransparent() {
        clearOam();
        enableSprites();
        putTile(0x0000, 10, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16);
        // bg pattern pixel = 0 (transparent — default)
        ppu.setBgPatternPixelForTest(50, 100, 0);

        // attr bit 5 = 1 → priority BEHIND
        putSprite(0, 49, 10, 0x20, 100);

        SpriteRenderer.render(ppu);
        // Where bg is transparent, sprite always wins regardless of priority
        assertPixelMatchesPaletteEntry(100, 50, 0x11);
    }

    @Test
    void reset_clearsBgPatternShadow() {
        // Pre-populate shadow as if a previous frame left opaque pixels.
        for (int y = 0; y < PPU.VISIBLE_HEIGHT; y++) {
            for (int x = 0; x < PPU.VISIBLE_WIDTH; x++) {
                ppu.setBgPatternPixelForTest(y, x, 2);
            }
        }
        // Sanity precondition
        assertEquals(2, ppu.getBgPatternPixel(50, 50), "precondition: shadow populated");

        ppu.reset();
        // Every cell must be back to 0 (transparent).
        for (int y = 0; y < PPU.VISIBLE_HEIGHT; y++) {
            for (int x = 0; x < PPU.VISIBLE_WIDTH; x++) {
                assertEquals(0, ppu.getBgPatternPixel(y, x),
                        "bgPatternPixel[" + y + "][" + x + "] should be cleared by reset()");
            }
        }
    }

    @Test
    void allSpritesOffScreen_yEquals240OrAbove_renderIsNoOp() {
        clearOam(); // sets all 64 sprite Y to 0xFF (= 256, off-screen)
        enableSprites();
        putTile(0x0000, 11, solidLowPlane(), zeroPlane());
        writePalette(0x11, 0x16);

        // Snapshot the screen BEFORE render
        int[][] before = new int[PPU.VISIBLE_HEIGHT][PPU.VISIBLE_WIDTH];
        for (int y = 0; y < PPU.VISIBLE_HEIGHT; y++) {
            System.arraycopy(ppu.getScreen()[y], 0, before[y], 0, PPU.VISIBLE_WIDTH);
        }

        SpriteRenderer.render(ppu);

        // Every pixel unchanged
        for (int y = 0; y < PPU.VISIBLE_HEIGHT; y++) {
            for (int x = 0; x < PPU.VISIBLE_WIDTH; x++) {
                assertEquals(before[y][x], ppu.getScreen()[y][x],
                        "off-screen sprite should not touch pixel (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    void renderer_doesNotCrash_whenPpuBusIsNull() {
        // Construct a PPU with NO ppu bus wired and ensure SpriteRenderer
        // degrades gracefully (logs once, no NPE). This is the production-
        // wiring-error path we don't want to crash the master clock on.
        PPU bareppu = new PPU();
        // Enable sprites so the renderer doesn't bail at the bit-4 gate
        bareppu.cpuBusWrite(0x2001, (byte) 0x10);
        assertDoesNotThrow(() -> SpriteRenderer.render(bareppu),
                "renderer with null ppuBus should warn and return, not NPE");
    }

    @Test
    void eightBySixteenMode_picksPatternTableFromTileIdBit0() {
        clearOam();
        enableSprites();
        // PPUCTRL: bit 5 = 1 (8x16 mode), bit 3 = 0 (would be sprite pattern $0000 in 8x8 mode)
        ppu.cpuBusWrite(0x2000, (byte) 0x20);
        // Put tile #2 in pattern table $1000 (because tile-id bit 0 = 0 → table 0...
        // wait — bit 0 = 0 → pattern table $0000, not $1000.
        // Let's use tile-id with bit 0 = 1 to force the alternate pattern table.
        // tile-id 0x21: bit 0 = 1 → pattern table $1000; tile index = 0x20.
        // 8x16 sprite occupies tile $20 + tile $21 in pattern table $1000.
        putTile(0x1000, 0x20, solidLowPlane(), zeroPlane()); // top half
        putTile(0x1000, 0x21, solidLowPlane(), zeroPlane()); // bottom half
        writePalette(0x11, 0x16);
        putSprite(0, 49, 0x21, 0x00, 50);

        SpriteRenderer.render(ppu);
        // Should render 8x16 block of palette $11
        for (int dy = 0; dy < 16; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                assertPixelMatchesPaletteEntry(50 + dx, 50 + dy, 0x11);
            }
        }
    }
}
