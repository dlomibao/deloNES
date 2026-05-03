package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the coarse sprite-0 hit detection (Step 6 of the playable-gen1
 * plan). The real PPU sets PPUSTATUS bit 6 when a non-transparent pixel of
 * sprite 0 overlaps a non-transparent pixel of the background. Many games
 * (most famously SMB) poll this to time their status-bar split.
 *
 * <p>This MVP implements the bugzmanov ch. 7 approximation: bit 6 is set
 * when the current scanline+cycle falls inside sprite 0's bounding box
 * AND both background and sprite rendering are enabled. Pixel opacity is
 * NOT checked — that is the exact-pixel test ROM territory and a future
 * upgrade.
 *
 * <h2>Contract covered</h2>
 * <ul>
 *   <li>Bit 6 sets when (scanline ∈ [Y+1, Y+1+height)) AND (cycle-1 ≥ X)
 *       AND background AND sprites are both enabled AND we're in the
 *       visible cycle window (cycle 1..256).</li>
 *   <li>Bit 6 stays cleared if either rendering layer is disabled.</li>
 *   <li>Bit 6 stays cleared before the predicate is satisfied (cycle below
 *       sprite-0 X, or scanline outside sprite-0 Y range).</li>
 *   <li>Bit 6 is cleared at the pre-render scanline (261, cycle 1) — this
 *       is the existing reset path, asserted here for regression cover.</li>
 * </ul>
 */
class PPUSpriteZeroHitTest {

    private PPU ppu;

    @BeforeEach
    void setUp() {
        ppu = new PPU();
    }

    /** Place sprite 0 at (X, Y) in OAM. tile-id and attr default to 0. */
    private void putSprite0(int oamY, int x) {
        ppu.writeOam(0, (byte) oamY);
        ppu.writeOam(1, (byte) 0);
        ppu.writeOam(2, (byte) 0);
        ppu.writeOam(3, (byte) x);
    }

    /** Enable background AND sprites in PPUMASK (bits 3 and 4). */
    private void enableBothLayers() {
        ppu.cpuBusWrite(0x2001, (byte) (0x08 | 0x10));
    }

    /**
     * Tick PPU forward until it reaches (targetScanline, targetCycle).
     * Idempotent across multiple calls in the same test — uses the PPU's
     * own scanline/cycle accessors as the loop condition rather than
     * tracking ticks externally. Stops with a safety cap to prevent
     * infinite loops on bad target coords.
     */
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
        // Sprite 0 at OAM (Y=49, X=100) → occupies scanlines 50..57, cols 100..107
        putSprite0(49, 100);
        enableBothLayers();
        // Tick to (scanline 50, cycle 105) — well inside the box
        tickTo(50, 105);
        assertTrue(spriteZeroHit(),
                "bit 6 should be set when both layers are on and (scanline, cycle) is inside sprite 0 box");
    }

    // ---- gating by PPUMASK ----

    @Test
    void bit6_notSet_whenSpritesDisabled() {
        putSprite0(49, 100);
        // Background ON (bit 3), sprites OFF (bit 4 clear)
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        tickTo(50, 105);
        assertFalse(spriteZeroHit(),
                "bit 6 must not be set when sprites are disabled");
    }

    @Test
    void bit6_notSet_whenBackgroundDisabled() {
        putSprite0(49, 100);
        // Sprites ON (bit 4), background OFF (bit 3 clear)
        ppu.cpuBusWrite(0x2001, (byte) 0x10);
        tickTo(50, 105);
        assertFalse(spriteZeroHit(),
                "bit 6 must not be set when background is disabled");
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
        // Tick only as far as cycle 50 of the right scanline — before X=100
        tickTo(50, 50);
        assertFalse(spriteZeroHit(),
                "bit 6 must not be set when cycle is to the left of sprite-0 X");
    }

    @Test
    void bit6_notSet_aboveSprite0Y() {
        putSprite0(49, 100); // sprite occupies scanlines 50..57
        enableBothLayers();
        // Stop at scanline 49, cycle 200 — above the sprite
        tickTo(49, 200);
        assertFalse(spriteZeroHit(),
                "bit 6 must not be set on a scanline above sprite 0");
    }

    @Test
    void bit6_notSet_belowSprite0YPlusHeight() {
        // Sprite Y=49, 8x8 → bottom = scanline 57. Scanline 58 is past it.
        putSprite0(49, 100);
        enableBothLayers();
        tickTo(58, 200);
        assertFalse(spriteZeroHit(),
                "bit 6 must not be set on a scanline below sprite 0");
    }

    @Test
    void bit6_setOnFirstScanlineOfSprite0() {
        // OAM Y=49 → top scanline = 50. Bit should set there.
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(50, 100);
        assertTrue(spriteZeroHit(),
                "bit 6 should set on the first scanline (Y+1) of sprite 0");
    }

    @Test
    void bit6_setOnLastScanlineOfSprite0() {
        // Y=49, 8x8 → scanlines 50..57. Bit should still set on scanline 57.
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(57, 100);
        assertTrue(spriteZeroHit(),
                "bit 6 should set on the bottom scanline of sprite 0");
    }

    // ---- clear at pre-render ----

    @Test
    void bit6_clearedAt_preRender_scanline261_cycle1() {
        // Set the bit, then tick into pre-render and assert it cleared.
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(50, 100);
        assertTrue(spriteZeroHit(), "precondition: bit 6 set");

        // Walk to scanline 261, cycle 1 (the exact reset point).
        tickTo(261, 1);

        assertFalse(spriteZeroHit(),
                "bit 6 must be cleared at pre-render scanline (261, cycle 1)");
    }

    // ---- persistence ----

    @Test
    void bit6_persists_acrossSubsequentVisibleCycles_until_preRender() {
        // Set the bit early, tick forward a bunch within visible area.
        putSprite0(49, 50);
        enableBothLayers();
        tickTo(50, 100);
        assertTrue(spriteZeroHit(), "precondition: bit 6 set");

        // Tick forward to scanline 200, cycle 100 — still in visible area,
        // not yet at pre-render. Bit must still be set.
        tickTo(200, 100);
        assertTrue(spriteZeroHit(),
                "bit 6 must persist through visible scanlines once set");
    }
}
