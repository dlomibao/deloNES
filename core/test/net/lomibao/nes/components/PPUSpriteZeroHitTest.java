package net.lomibao.nes.components;

import net.lomibao.nes.components.ppu.NameTableMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for per-pixel sprite-0 hit detection. Real-hardware semantics:
 * PPUSTATUS bit 6 sets when an opaque sprite-0 pixel overlaps an opaque
 * background pixel — both rendering layers must be on, the cycle must be
 * 1..256 (and never 256, i.e. x != 255), and if the overlap is in the
 * leftmost 8 pixels then both PPUMASK bit 1 AND bit 2 must be set.
 *
 * <p>Setup populates an 8x16-aware CHR pattern table for sprite-0 tile 0
 * (all pixels opaque) and seeds the BG pattern shadow with non-zero across
 * the entire frame so existing-shape tests need no per-case priming.
 */
class PPUSpriteZeroHitTest {

    private PPU ppu;
    private FakeChr chr;

    /** 8KB CHR provider on the PPU bus at $0000-$1FFF. */
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

        // Make sprite-0 tile 0 fully opaque (low plane = 0xFF, high plane = 0x00 → pattern pixel = 1).
        // Tile 0: bytes [0..7] = low plane, [8..15] = high plane. Tile 1 (8x16 bottom): [16..23], [24..31].
        for (int row = 0; row < 8; row++) {
            chr.data[row]      = (byte) 0xFF; // tile 0 low plane
            chr.data[8 + row]  = 0x00;        // tile 0 high plane
            chr.data[16 + row] = (byte) 0xFF; // tile 1 low plane (8x16 bottom half)
            chr.data[24 + row] = 0x00;        // tile 1 high plane
        }
        // Seed BG pattern shadow with non-zero across the visible frame.
        for (int y = 0; y < PPU.VISIBLE_HEIGHT; y++) {
            for (int x = 0; x < PPU.VISIBLE_WIDTH; x++) {
                ppu.setBgPatternPixelForTest(y, x, 1);
            }
        }
    }

    /** Place sprite 0 at (X, Y) in OAM. tile-id 0, attr 0. */
    private void putSprite0(int oamY, int x) {
        ppu.writeOam(0, (byte) oamY);
        ppu.writeOam(1, (byte) 0);
        ppu.writeOam(2, (byte) 0);
        ppu.writeOam(3, (byte) x);
    }

    /** Enable both BG and sprite rendering AND left-8 masks (PPUMASK bits 1..4). */
    private void enableBothLayers() {
        ppu.cpuBusWrite(0x2001, (byte) (0x02 | 0x04 | 0x08 | 0x10));
    }

    private void tickTo(int targetScanline, int targetCycle) {
        int safety = 0;
        while ((ppu.getScanline() != targetScanline || ppu.getCycle() != targetCycle)
                && safety++ < 4 * 341 * 262) {
            ppu.clock();
        }
        assertEquals(targetScanline, ppu.getScanline(), "tickTo: scanline target not reached");
        assertEquals(targetCycle, ppu.getCycle(), "tickTo: cycle target not reached");
    }

    private boolean spriteZeroHit() {
        return (ppu.cpuBusRead(0x2002, true) & 0x40) != 0;
    }

    // ---- happy path ----

    @Test
    void bit6_setWhenSprite0_overlapsScanlineAndCycle_andBothLayersEnabled() {
        putSprite0(49, 100);
        enableBothLayers();
        tickTo(50, 105);
        assertTrue(spriteZeroHit());
    }

    // ---- gating by PPUMASK ----

    @Test
    void bit6_notSet_whenSpritesDisabled() {
        putSprite0(49, 100);
        ppu.cpuBusWrite(0x2001, (byte) 0x08); // BG on, sprites off
        tickTo(50, 105);
        assertFalse(spriteZeroHit());
    }

    @Test
    void bit6_notSet_whenBackgroundDisabled() {
        putSprite0(49, 100);
        ppu.cpuBusWrite(0x2001, (byte) 0x10); // sprites on, BG off
        tickTo(50, 105);
        assertFalse(spriteZeroHit());
    }

    @Test
    void bit6_notSet_whenBothLayersDisabled() {
        putSprite0(49, 100);
        ppu.cpuBusWrite(0x2001, (byte) 0x00);
        tickTo(50, 105);
        assertFalse(spriteZeroHit());
    }

    // ---- gating by position ----

    @Test
    void bit6_notSet_beforeCycleReachesSprite0X() {
        putSprite0(49, 100);
        enableBothLayers();
        tickTo(50, 50);
        assertFalse(spriteZeroHit());
    }

    @Test
    void bit6_notSet_whenWalkedOnlyToScanlineAboveSprite0() {
        putSprite0(49, 100);
        enableBothLayers();
        tickTo(49, 200);
        assertFalse(spriteZeroHit());
    }

    @Test
    void bit6_notSet_whenSpriteYIsBelowVisibleArea() {
        putSprite0(239, 100);
        enableBothLayers();
        tickTo(239, 256);
        assertFalse(spriteZeroHit());
    }

    @Test
    void bit6_setOnFirstScanlineOfSprite0() {
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(50, 100);
        assertTrue(spriteZeroHit());
    }

    @Test
    void bit6_setOnLastScanlineOfSprite0() {
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(57, 100);
        assertTrue(spriteZeroHit());
    }

    @Test
    void bit6_setOnLastScanlineOfSprite0_in8x16Mode() {
        ppu.cpuBusWrite(0x2000, (byte) 0x20);
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(65, 100);
        assertTrue(spriteZeroHit());
    }

    // ---- clear at pre-render ----

    @Test
    void bit6_clearedAt_preRender_scanline261_cycle1() {
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(50, 100);
        assertTrue(spriteZeroHit(), "precondition");
        tickTo(261, 1);
        assertFalse(spriteZeroHit());
    }

    @Test
    void bit6_persists_acrossSubsequentVisibleCycles_until_preRender() {
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(50, 100);
        assertTrue(spriteZeroHit(), "precondition");
        tickTo(200, 100);
        assertTrue(spriteZeroHit());
    }

    // ---- opacity gates (new in per-pixel impl) ----

    @Test
    void bit6_notSet_whenSpritePixelIsTransparent_atTopRowButSetsLater() {
        // Zero the top row of sprite-0's pattern; rows 1..7 remain opaque.
        chr.data[0] = 0x00;  // low plane row 0
        putSprite0(49, 50);  // sprite occupies scanlines 50..57
        enableBothLayers();
        // Top row of sprite at scanline 50 → must NOT trigger
        tickTo(50, 100);
        assertFalse(spriteZeroHit(),
                "transparent sprite row at scanline 50 must not trigger bit 6");
        // Row 1 (scanline 51) is opaque → must trigger
        tickTo(51, 100);
        assertTrue(spriteZeroHit(),
                "opaque sprite row at scanline 51 must trigger bit 6");
    }

    @Test
    void bit6_notSet_inLeftmost8Pixels_whenBgLeft8Disabled() {
        putSprite0(49, 2);                            // sprite at X=2 → covers x=2..9
        // Sprites on (bit 4) + sprite-left-8 on (bit 2) + BG on (bit 3), BUT bg-left-8 (bit 1) OFF.
        ppu.cpuBusWrite(0x2001, (byte) (0x04 | 0x08 | 0x10));
        tickTo(50, 5);                                // cycle 5 → x = 4 (in leftmost 8)
        assertFalse(spriteZeroHit(),
                "bit 6 must not set in leftmost 8 px when BG-left-8 (bit 1) is off");
    }

    @Test
    void bit6_notSet_inLeftmost8Pixels_whenSpriteLeft8Disabled() {
        putSprite0(49, 2);
        // BG-left-8 (bit 1) + BG (bit 3) + sprites (bit 4), but sprite-left-8 (bit 2) OFF.
        ppu.cpuBusWrite(0x2001, (byte) (0x02 | 0x08 | 0x10));
        tickTo(50, 5);
        assertFalse(spriteZeroHit(),
                "bit 6 must not set in leftmost 8 px when sprite-left-8 (bit 2) is off");
    }

    // ---- NESdev corner cases (B10) ----

    /**
     * Per NESdev: sprite-0 hit never fires at x=255 (the last visible pixel of
     * a scanline). Place sprite-0 at OAM X=255 so its only in-range overlap
     * column is x=255, drive the PPU all the way to (and through) cycle 256 on
     * the sprite's first scanline with both rendering layers fully enabled,
     * and confirm PPUSTATUS bit 6 stays clear.
     */
    @Test
    void bit6_notSet_atX255_evenWhenBothLayersEnabled() {
        // OAM X=255 → checkSpriteZeroHit only considers x in [255,262], and only x=255
        // is on-screen. Cycle 256 maps to x=255 — the gated column.
        putSprite0(49, 255);
        enableBothLayers();
        // Walk through every cycle on scanline 50 up to and including cycle 256.
        // The only overlap candidate is cycle 256 (x=255), which the x=255 rule must reject.
        tickTo(50, 256);
        assertFalse(spriteZeroHit(),
                "bit 6 must not set at x=255 even when both layers are enabled (NESdev rule)");
        // And it must stay clear for the rest of the scanline too.
        tickTo(50, 340);
        assertFalse(spriteZeroHit(),
                "bit 6 must remain clear through the remainder of the scanline");
    }

    /**
     * If the BG pattern pixel at every sprite-0 overlap cell on a scanline is
     * transparent (palette index 0), sprite-0 hit must NOT fire on that
     * scanline — even though both rendering layers are on and the sprite
     * pixel itself is opaque. Exercises the BG-transparent gate in
     * {@link PPU#checkSpriteZeroHit()} that consults the bg-pattern shadow.
     */
    @Test
    void bit6_notSet_whenBgPixelIsTransparent_atOverlapCell() {
        putSprite0(49, 100);            // sprite-0 spans x=100..107 on scanlines 50..57
        enableBothLayers();
        // Zero the BG pattern shadow across the full 8-pixel overlap row on scanline 50
        // (sprite columns x=100..107). With BG transparent here, no overlap cycle on
        // this scanline may set bit 6.
        for (int x = 100; x < 108; x++) {
            ppu.setBgPatternPixelForTest(50, x, 0);
        }
        tickTo(50, 256);
        assertFalse(spriteZeroHit(),
                "bit 6 must not set when the BG pixel at the overlap cell is transparent");
        // Sanity: scanline 51's bg-pattern shadow is still opaque, so the hit fires there.
        tickTo(51, 105);
        assertTrue(spriteZeroHit(),
                "bit 6 must fire on the next scanline where BG is opaque again");
    }

}
