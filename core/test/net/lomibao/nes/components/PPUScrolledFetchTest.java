package net.lomibao.nes.components;

import net.lomibao.nes.components.ppu.NameTableMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the per-cycle background nametable fetcher honours
 * scrollX, scrollY, and the PPUCTRL base-nametable bits, INCLUDING
 * cross-nametable wrapping (the bugzmanov ch. 7 scroll model).
 *
 * <p>We populate the nametable RAM with a pattern where each tile-id
 * encodes its (NT, row, col) so the fetched bgNextTileId tells us
 * exactly where the fetch went.
 */
class PPUScrolledFetchTest {

    private PPU ppu;
    private NameTableMemory nametables;

    @BeforeEach
    void setUp() {
        ppu = new PPU();
        nametables = new NameTableMemory();
        // Force VERTICAL mirroring → NT0 ($2000) and NT1 ($2400) are
        // independent; $2800 mirrors $2000, $2C00 mirrors $2400. This is
        // SMB's mirroring (horizontal scroll across NT0 + NT1).
        nametables.setMirroringOverride(net.lomibao.nes.components.ppu.MirroringMode.VERTICAL);
        PPUBus bus = new PPUBus();
        bus.connect(nametables);
        ppu.connectPPUBus(bus);
        bus.connectPPU(ppu);
        // Enable background rendering so the fetch path runs.
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
    }

    /**
     * Write a marker tile-id into nametable[nt][row][col]. Encoding:
     * tileId = (nt << 6) | ((row & 0x07) << 3) | (col & 0x07)
     * — fits in 8 bits for nt=0..1, row 0..7, col 0..7. We only test
     * within the first 8x8 tile region of each NT.
     */
    private void writeMarker(int nt, int row, int col) {
        int ntBase = 0x2000 + nt * 0x400;
        int addr = ntBase + row * 32 + col;
        // Use the PPU bus directly (bypasses CPU-side $2006/$2007 protocol).
        ppu.ppuBus.write(addr, (byte) ((nt << 6) | ((row & 0x07) << 3) | (col & 0x07)));
    }

    private int decodeNt(int tileId) { return (tileId >> 6) & 0x03; }
    private int decodeRow(int tileId) { return (tileId >> 3) & 0x07; }
    private int decodeCol(int tileId) { return tileId & 0x07; }

    /** Tick the PPU forward to a specific cycle/scanline, then return the most-recently-fetched tile id. */
    private int fetchTileIdAt(int targetScanline, int targetCycle) {
        int safety = 0;
        while ((ppu.getScanline() != targetScanline || ppu.getCycle() != targetCycle)
                && safety++ < 4 * 341 * 262) {
            ppu.clock();
        }
        return ppu.getBgNextTileId();
    }

    // ---- baseline: no scroll ----

    @Test
    void scrollZero_baseNt0_fetchesNt0Row0Col0_atFirstFetch() {
        writeMarker(0, 0, 0);
        // First nametable byte is fetched at cycle 1 case 0 → cycle 1 % 8 == 1...
        // Looking at PPU.clock: case 0 fires at cycleMod==0, which is when (cycle-1)%8==0
        // → cycles 1, 9, 17, ... For the FIRST fetch, the tile coordinate is
        // (scanline 0, cycle 1) → tileX=0, tileY=0.
        // After case 0 runs, bgNextTileId holds the byte at NT0[0][0] = (0<<6)|(0<<3)|0 = 0.
        int tileId = fetchTileIdAt(0, 1);
        assertEquals(0, decodeNt(tileId));
        assertEquals(0, decodeRow(tileId));
        assertEquals(0, decodeCol(tileId));
    }

    // ---- horizontal scroll within NT ----

    @Test
    void scrollX8_shiftsFetchByOneTileColumn_intoSameNt() {
        // Set scroll: write 8 to PPUSCROLL (first), then 0 (second).
        ppu.cpuBusWrite(0x2005, (byte) 8);
        ppu.cpuBusWrite(0x2005, (byte) 0);
        // Marker at NT0 row 0 col 1 — should be the first tile fetched
        // when scrollX=8 (8 px = 1 tile shift).
        writeMarker(0, 0, 1);
        int tileId = fetchTileIdAt(0, 1);
        assertEquals(0, decodeNt(tileId), "still in NT 0 (no wrap yet)");
        assertEquals(0, decodeRow(tileId));
        assertEquals(1, decodeCol(tileId), "scrollX=8 → first fetch should be col 1");
    }

    // ---- horizontal cross-NT wrap ----

    @Test
    void scrollX256_wrapsToOtherNt_horizontal() {
        // scrollX = 256 → 32 tiles → wraps fully to the OTHER NT (NT1).
        ppu.cpuBusWrite(0x2005, (byte) 0); // first byte (low 5 bits used; 256 & 0xFF = 0)
        ppu.cpuBusWrite(0x2005, (byte) 0); // second byte
        // 256 doesn't fit in a byte; the real path is via PPUCTRL bit 0 + scrollX=0:
        // PPUCTRL bit 0 = 1 → base NT = NT1. Set this directly.
        ppu.cpuBusWrite(0x2000, (byte) 0x01);
        writeMarker(1, 0, 0);
        int tileId = fetchTileIdAt(0, 1);
        assertEquals(1, decodeNt(tileId), "PPUCTRL bit 0 = 1 → fetches start in NT1");
        assertEquals(0, decodeCol(tileId));
    }

    @Test
    void scrollX_wrappingMidScanline_picksOtherNt() {
        // scrollX = 64 (= 8 tiles offset) in NT0. The visible row spans
        // tiles col 8..39. Cols 8..31 come from NT0; cols 32..39 wrap into
        // NT1 cols 0..7.
        ppu.cpuBusWrite(0x2005, (byte) 64);
        ppu.cpuBusWrite(0x2005, (byte) 0);
        // Marker in NT1 col 0 (which corresponds to viewport tile column 24)
        writeMarker(1, 0, 0);
        // Tile fetch case 0 for viewport col 24 → cycle = 24*8 + 1 = 193
        int tileId = fetchTileIdAt(0, 193);
        assertEquals(1, decodeNt(tileId), "viewport col 24 with scrollX=64 should fetch from NT1 col 0");
        assertEquals(0, decodeCol(tileId));
    }

    // ---- vertical scroll within NT ----

    @Test
    void scrollY8_shiftsFetchByOneTileRow_intoSameNt() {
        ppu.cpuBusWrite(0x2005, (byte) 0);
        ppu.cpuBusWrite(0x2005, (byte) 8);
        writeMarker(0, 1, 0);
        int tileId = fetchTileIdAt(0, 1);
        assertEquals(0, decodeNt(tileId));
        assertEquals(1, decodeRow(tileId), "scrollY=8 → first fetch should be row 1");
        assertEquals(0, decodeCol(tileId));
    }
}
