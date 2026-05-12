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

    /**
     * Real-NES PPU pipeline: BG fetcher is +2 tile columns ahead of the
     * renderer. At cycle 2 of scanline N (the first visible NT-fetch),
     * the fetcher reads viewport tile col 2. Cols 0 and 1 are fetched
     * during cycles 322-336 of scanline N-1 (the prefetch window).
     *
     * <p>Test convention: {@code firstVisibleFetchCycle()} = cycle 2,
     * which fetches viewport col 2. To observe what's fetched at viewport
     * col K (K >= 2), use {@code visibleFetchCycle(K)}.
     */
    private int firstVisibleFetchCycle() { return 2; }
    private int visibleFetchCycle(int viewportCol) { return (viewportCol - 2) * 8 + 2; }

    @Test
    void scrollZero_baseNt0_fetchesNt0Row0Col2_atFirstVisibleFetch() {
        writeMarker(0, 0, 2); // first visible NT fetch reads viewport col 2
        int tileId = fetchTileIdAt(0, firstVisibleFetchCycle());
        assertEquals(0, decodeNt(tileId));
        assertEquals(0, decodeRow(tileId));
        assertEquals(2, decodeCol(tileId));
    }

    // ---- horizontal scroll within NT ----

    @Test
    void scrollX8_shiftsFetchByOneTileColumn_intoSameNt() {
        ppu.cpuBusWrite(0x2005, (byte) 8);
        ppu.cpuBusWrite(0x2005, (byte) 0);
        // First visible fetch = viewport col 2; with scrollX=8 (+1 tile),
        // fetcher reads NT 0 col 3.
        writeMarker(0, 0, 3);
        int tileId = fetchTileIdAt(0, firstVisibleFetchCycle());
        assertEquals(0, decodeNt(tileId), "still in NT 0 (no wrap yet)");
        assertEquals(0, decodeRow(tileId));
        assertEquals(3, decodeCol(tileId), "scrollX=8 → col 2 + 1 = col 3");
    }

    // ---- horizontal cross-NT wrap (PPUCTRL base NT) ----

    @Test
    void ppuCtrlBit0_baseNt1_fetchesFromNt1() {
        // PPUCTRL bit 0 = 1 → base NT = NT1. First visible fetch reads
        // viewport col 2 from NT1.
        ppu.cpuBusWrite(0x2000, (byte) 0x01);
        writeMarker(1, 0, 2);
        int tileId = fetchTileIdAt(0, firstVisibleFetchCycle());
        assertEquals(1, decodeNt(tileId), "PPUCTRL bit 0 = 1 → fetches start in NT1");
        assertEquals(2, decodeCol(tileId));
    }

    @Test
    void scrollX_wrappingMidScanline_picksOtherNt() {
        // scrollX = 64 (= 8 tiles). visibleFetchCycle(24) returns the cycle
        // where the fetcher reads viewport col 24; with scrollX=8, virtTileX
        // = 24 + 8 = 32 → wraps to NT1 col 0.
        ppu.cpuBusWrite(0x2005, (byte) 64);
        ppu.cpuBusWrite(0x2005, (byte) 0);
        writeMarker(1, 0, 0);
        int tileId = fetchTileIdAt(0, visibleFetchCycle(24));
        assertEquals(1, decodeNt(tileId), "viewport col 24 with scrollX=64 wraps to NT1 col 0");
        assertEquals(0, decodeCol(tileId));
    }

    // ---- vertical scroll within NT ----

    @Test
    void scrollY8_shiftsFetchByOneTileRow_intoSameNt() {
        ppu.cpuBusWrite(0x2005, (byte) 0);
        ppu.cpuBusWrite(0x2005, (byte) 8);
        writeMarker(0, 1, 2); // row 1 (scrollY=8 = +1 row), col 2 (+2 lookahead)
        int tileId = fetchTileIdAt(0, firstVisibleFetchCycle());
        assertEquals(0, decodeNt(tileId));
        assertEquals(1, decodeRow(tileId), "scrollY=8 → first fetch should be row 1");
        assertEquals(2, decodeCol(tileId));
    }

    // ---- vertical cross-NT wrap ----

    @Test
    void scrollY240_wrapsToOtherNt_vertical() {
        // scrollY = 240 → 30 tile rows → exact NT boundary; first fetch
        // wraps to NT2's row 0. Use HORIZONTAL mirroring so NT2 != NT0.
        nametables.setMirroringOverride(net.lomibao.nes.components.ppu.MirroringMode.HORIZONTAL);
        ppu.cpuBusWrite(0x2005, (byte) 0);
        ppu.cpuBusWrite(0x2005, (byte) 240);
        writeMarker(2, 0, 2); // NT2 row 0 col 2 (+2 lookahead)
        int tileId = fetchTileIdAt(0, firstVisibleFetchCycle());
        assertEquals(2, decodeNt(tileId),
                "scrollY=240 (= 30 tiles) should wrap from NT0 to NT2");
        assertEquals(0, decodeRow(tileId));
        assertEquals(2, decodeCol(tileId));
    }

    // ---- combined horizontal + vertical scroll ----

    @Test
    void combinedScroll_64x16_picksRightNtAndCell() {
        // scrollX = 64 (8 tiles), scrollY = 16 (2 tiles). At viewport col 24,
        // virtTileX = 24+8 = 32 → wrap to NT1 col 0. virtTileY = 0+2 = 2.
        ppu.cpuBusWrite(0x2005, (byte) 64);
        ppu.cpuBusWrite(0x2005, (byte) 16);
        writeMarker(1, 2, 0);
        int tileId = fetchTileIdAt(0, visibleFetchCycle(24));
        assertEquals(1, decodeNt(tileId), "combined scroll should land in NT1 col 0");
        assertEquals(2, decodeRow(tileId), "combined scroll should land in row 2");
        assertEquals(0, decodeCol(tileId));
    }
}
