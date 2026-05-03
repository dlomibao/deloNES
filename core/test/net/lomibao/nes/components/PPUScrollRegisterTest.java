package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PPUSCROLL ($2005) and PPUADDR ($2006) write protocol with
 * shared latch (Step 7 of the playable-gen1 plan).
 *
 * <p>Real PPU contract:
 * <ul>
 *   <li>$2005 / $2006 share a single internal "write toggle" latch.</li>
 *   <li>First write to either: high-byte / coarse-X-and-fine-X half.</li>
 *   <li>Second write: low-byte / coarse-Y-and-fine-Y half.</li>
 *   <li>Reading $2002 (PPUSTATUS) resets the latch — subsequent first
 *       write to either register goes to the "first" half.</li>
 * </ul>
 */
class PPUScrollRegisterTest {

    private PPU ppu;

    @BeforeEach
    void setUp() {
        ppu = new PPU();
    }

    // ---- PPUSCROLL latch ----

    @Test
    void ppuScroll_firstWrite_storesScrollX() {
        ppu.cpuBusWrite(0x2005, (byte) 100);
        assertEquals(100, ppu.getScrollX(),
                "first $2005 write should set scrollX");
    }

    @Test
    void ppuScroll_secondWrite_storesScrollY_andResetsLatch() {
        ppu.cpuBusWrite(0x2005, (byte) 100);
        ppu.cpuBusWrite(0x2005, (byte) 50);
        assertEquals(100, ppu.getScrollX(), "scrollX should still be 100");
        assertEquals(50, ppu.getScrollY(), "second $2005 write should set scrollY");
    }

    @Test
    void ppuScroll_thirdWrite_isFirstWriteAgain_sinceLatchClearedAfterSecond() {
        ppu.cpuBusWrite(0x2005, (byte) 100);
        ppu.cpuBusWrite(0x2005, (byte) 50);
        // Latch is now back to "first half". Third write should overwrite scrollX.
        ppu.cpuBusWrite(0x2005, (byte) 200);
        assertEquals(200, ppu.getScrollX(), "third $2005 write should be a new first-half write");
        assertEquals(50, ppu.getScrollY(), "scrollY should be unchanged");
    }

    @Test
    void ppuScroll_initialState_isZero() {
        assertEquals(0, ppu.getScrollX(), "scrollX defaults to 0");
        assertEquals(0, ppu.getScrollY(), "scrollY defaults to 0");
    }

    // ---- PPUSTATUS resets latch ----

    @Test
    void ppuStatus_read_resetsScrollLatch() {
        // Half-way through a PPUSCROLL pair, read PPUSTATUS — latch resets.
        ppu.cpuBusWrite(0x2005, (byte) 100);
        // Latch is now in the "second half" state. Reading PPUSTATUS resets.
        ppu.cpuBusRead(0x2002, false);
        // Next $2005 write should be treated as a FIRST write (set scrollX again).
        ppu.cpuBusWrite(0x2005, (byte) 50);
        assertEquals(50, ppu.getScrollX(), "after PPUSTATUS read, $2005 write should hit scrollX");
        assertEquals(0, ppu.getScrollY(), "scrollY should not have been written");
    }

    // ---- shared latch with PPUADDR ----

    @Test
    void ppuScroll_followedBy_ppuAddr_sharesLatch() {
        // PPUSCROLL first write → latch in second-half state.
        // Then PPUADDR write must be treated as a SECOND write (low byte
        // of the 14-bit PPU address), not a first write.
        ppu.cpuBusWrite(0x2005, (byte) 0xAA); // first half — sets scrollX
        // First half of PPUADDR (high 6 bits) goes via the latch to a different
        // path on real HW (loopy t high bits). For our MVP we model the simpler
        // "shared latch toggle" semantics: PPUADDR write here is treated as
        // the SECOND write of the pair, completing the address.
        ppu.cpuBusWrite(0x2006, (byte) 0x12);
        // After this, the latch is back to first-half state.
        // Verify by writing $2005 again — it should set scrollX (first half).
        ppu.cpuBusWrite(0x2005, (byte) 0x55);
        assertEquals(0x55, ppu.getScrollX(),
                "after PPUSCROLL+PPUADDR pair, latch should be back to first-half; next PPUSCROLL is first write");
    }

    @Test
    void ppuAddr_followedBy_ppuScroll_sharesLatch() {
        // Symmetric: first half via PPUADDR, second half via PPUSCROLL.
        ppu.cpuBusWrite(0x2006, (byte) 0x20); // first half — high byte of address
        ppu.cpuBusWrite(0x2005, (byte) 0x77); // second half — should set scrollY
        assertEquals(0x77, ppu.getScrollY(),
                "PPUADDR first then PPUSCROLL second should set scrollY");
    }

    // ---- PPUADDR still works as before ----

    @Test
    void ppuAddr_twoWrites_setsPpuAddress() {
        // Existing protocol: first write = high byte (6 bits), second = low byte
        ppu.cpuBusWrite(0x2006, (byte) 0x21); // high
        ppu.cpuBusWrite(0x2006, (byte) 0x08); // low
        // Verify the address was set by reading PPUDATA which uses ppuAddress.
        // Don't care about the value here — just that no NPE / behavior change.
        assertDoesNotThrow(() -> ppu.cpuBusRead(0x2007, false));
    }

    // ---- reset clears scroll ----

    @Test
    void reset_clearsScrollXAndScrollY() {
        ppu.cpuBusWrite(0x2005, (byte) 100);
        ppu.cpuBusWrite(0x2005, (byte) 50);
        assertEquals(100, ppu.getScrollX(), "precondition: scrollX populated");
        assertEquals(50, ppu.getScrollY(), "precondition: scrollY populated");
        ppu.reset();
        assertEquals(0, ppu.getScrollX(), "reset() must zero scrollX");
        assertEquals(0, ppu.getScrollY(), "reset() must zero scrollY");
    }
}
