package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C1 — cycle-exact $4017 semantics (docs/apu-plan.md Phase C;
 * research §1.2 quirks): the 3-or-4-cycle write delay by write-cycle
 * parity, delayed sequencer reset + bit-7 immediate clock at the delayed
 * point, immediate bit-6 flag clear, the 3-cycle IRQ window with
 * re-set-after-mid-window-read, and the boot/reset offset.
 */
class FrameCounterPhaseCTest {

    private static final int QH = FrameCounter.QUARTER | FrameCounter.HALF;

    /** Clock n cycles, return OR of all event masks. */
    private static int clockAll(FrameCounter fc, int n) {
        int mask = 0;
        for (int i = 0; i < n; i++) {
            mask |= fc.clock();
        }
        return mask;
    }

    // ------------------------------------------------------------------
    // $4017 write delay: 3 (during APU cycle) or 4 (between APU cycles)
    // ------------------------------------------------------------------

    @Test
    void writeDelay_duringApuCycle_resetsSequencerOnThirdCycleAfterWrite() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 100);
        fc.write4017(0x00, true); // during an APU cycle → 3
        assertEquals(100, fc.cycle(), "sequencer reset must NOT be immediate");
        fc.clock(); // W+1
        fc.clock(); // W+2
        assertEquals(102, fc.cycle(), "old sequence still running during the delay");
        fc.clock(); // W+3 — delayed reset applies here
        assertEquals(0, fc.cycle(), "sequencer resets exactly 3 cycles after the write");
    }

    @Test
    void writeDelay_betweenApuCycles_resetsSequencerOnFourthCycleAfterWrite() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 100);
        fc.write4017(0x00, false); // between APU cycles → 4
        fc.clock(); // W+1
        fc.clock(); // W+2
        fc.clock(); // W+3
        assertEquals(103, fc.cycle(), "reset must not land at W+3 for off-parity writes");
        fc.clock(); // W+4
        assertEquals(0, fc.cycle(), "sequencer resets exactly 4 cycles after the write");
    }

    @Test
    void write4017_bit7_quarterHalfClockFiresAtDelayedResetPoint() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 100);
        fc.write4017(0x80, true);
        assertEquals(0, fc.clock(), "W+1: no immediate clock");
        assertEquals(0, fc.clock(), "W+2: no immediate clock");
        assertEquals(QH, fc.clock(), "W+3: quarter+half fire at the delayed reset point");
        assertTrue(fc.isMode5(), "mode bit applies at the delayed reset point");
    }

    @Test
    void write4017_modeBitAppliesAtDelayedPoint_notImmediately() {
        FrameCounter fc = new FrameCounter();
        fc.write4017(0x80, false);
        assertFalse(fc.isMode5(), "mode 5 must not apply before the delayed reset");
        clockAll(fc, 4);
        assertTrue(fc.isMode5());
    }

    @Test
    void write4017_bit6_clearsFlagImmediately_noDelay() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 29828); // flag set
        assertTrue(fc.isFrameIrqFlag());
        fc.write4017(0x40, true);
        assertFalse(fc.isFrameIrqFlag(), "bit-6 flag clear is immediate, not delayed");
        assertTrue(fc.isIrqInhibit(), "inhibit flag applies immediately");
    }

    @Test
    void oldSequenceKeepsRunning_duringDelay_flagStillSetsInWindow() {
        // Write $4017=$00 two cycles before 29828: the delayed reset lands
        // at 29830(+1), so the old sequence still reaches the flag window.
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 29826);
        fc.write4017(0x00, true); // reset applies at 29829
        fc.clock(); // 29827
        fc.clock(); // 29828 — old sequence: flag sets
        assertTrue(fc.isFrameIrqFlag(),
                "old sequence must keep running (and set the flag) during the write delay");
    }

    @Test
    void secondWriteDuringDelay_lastWriteWins() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 50);
        fc.write4017(0x80, true);  // would apply mode 5 at W+3
        fc.clock(); // W+1
        fc.write4017(0x00, true);  // supersedes: mode 0, applies 3 cycles from now
        clockAll(fc, 3);
        assertFalse(fc.isMode5(), "the second write's value must win");
        assertEquals(0, fc.cycle(), "second write's delayed reset applied");
    }

    // ------------------------------------------------------------------
    // 3-cycle IRQ window + mid-window read re-set
    // ------------------------------------------------------------------

    @Test
    void flagWindow_readOnEachWindowCycle_neverEndsUpClear() {
        // A $4015 read that clears the flag mid-window sees it re-set on the
        // next window cycle; a read ON a set cycle races and does not clear.
        for (int readAt = 29828; readAt <= 29830; readAt++) {
            FrameCounter fc = new FrameCounter();
            clockAll(fc, readAt);
            assertTrue(fc.isFrameIrqFlag(), "flag set at " + readAt);
            fc.clearFrameIrqFlagOnRead(); // same-cycle race: no clear
            assertTrue(fc.isFrameIrqFlag(),
                    "read on set cycle " + readAt + " must not clear (race)");
            fc.clock(); // next cycle
            assertTrue(fc.isFrameIrqFlag(),
                    "flag still held after window cycle " + readAt);
        }
    }

    @Test
    void flagClearedMidWindow_reSetByNextWindowCycle() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 29828);
        fc.clearFrameIrqFlag(); // unconditional (models a clear landing mid-window)
        assertFalse(fc.isFrameIrqFlag());
        fc.clock(); // 29829 — window re-sets the flag
        assertTrue(fc.isFrameIrqFlag(), "mid-window clear is re-set on the next window cycle");
    }

    @Test
    void afterWindow_readClearsAndNothingReSets() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 29830 + 2); // past the window
        assertTrue(fc.isFrameIrqFlag());
        fc.clearFrameIrqFlagOnRead();
        assertFalse(fc.isFrameIrqFlag(), "post-window read clears normally");
        clockAll(fc, 100);
        assertFalse(fc.isFrameIrqFlag(), "nothing re-sets the flag until the next period");
    }

    // ------------------------------------------------------------------
    // Boot/reset offset
    // ------------------------------------------------------------------

    @Test
    void reset_appliesValueImmediately_atBootOffset_droppingPendingWrite() {
        FrameCounter fc = new FrameCounter();
        clockAll(fc, 5000);
        fc.write4017(0x00, true); // pending...
        fc.reset(0xC0, 7);
        assertEquals(7, fc.cycle(), "reset positions the sequencer at the boot offset");
        assertTrue(fc.isMode5(), "reset applies the retained $4017 value immediately");
        assertTrue(fc.isIrqInhibit());
        assertFalse(fc.isFrameIrqFlag());
        // The pre-reset pending write must not fire afterwards.
        clockAll(fc, 10);
        assertEquals(17, fc.cycle(), "pending pre-reset $4017 write was dropped");
    }

    @Test
    void bootOffset_shiftsFirstQuarterEventEarlier() {
        FrameCounter fc = new FrameCounter();
        fc.reset(0x00, 10);
        int mask = clockAll(fc, 7457 - 10 - 1);
        assertEquals(0, mask, "no event before the shifted 7457 position");
        assertEquals(FrameCounter.QUARTER, fc.clock(),
                "boot offset shifts the first quarter clock earlier by the offset");
    }
}
