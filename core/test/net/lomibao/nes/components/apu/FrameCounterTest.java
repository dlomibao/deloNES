package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A1 — frame-counter event cycles, table-driven against research
 * doc §1.2 (docs/apu-plan.md).
 */
class FrameCounterTest {

    /** Clock {@code n} CPU cycles, recording {cycleIndex, mask} for events. */
    private static List<int[]> events(FrameCounter fc, int n) {
        List<int[]> out = new ArrayList<int[]>();
        for (int i = 1; i <= n; i++) {
            int mask = fc.clock();
            if (mask != 0) {
                out.add(new int[] {i, mask});
            }
        }
        return out;
    }

    private static void assertEvents(List<int[]> actual, int[][] expected) {
        assertEquals(expected.length, actual.size(), "event count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], actual.get(i)[0], "event cycle #" + i);
            assertEquals(expected[i][1], actual.get(i)[1], "event mask at cycle " + expected[i][0]);
        }
    }

    private static final int Q = FrameCounter.QUARTER;
    private static final int QH = FrameCounter.QUARTER | FrameCounter.HALF;

    // ---------------------------------------------------------------------
    // Mode 0 (4-step)
    // ---------------------------------------------------------------------

    @Test
    void mode0_eventTable_firstPeriod() {
        FrameCounter fc = new FrameCounter();
        assertEvents(events(fc, 29830), new int[][] {
                {7457, Q}, {14913, QH}, {22371, Q}, {29829, QH},
        });
    }

    @Test
    void mode0_irqFlag_setAt29828() {
        FrameCounter fc = new FrameCounter();
        for (int i = 1; i <= 29827; i++) {
            fc.clock();
            assertFalse(fc.isFrameIrqFlag(), "flag must be clear before 29828 (cycle " + i + ")");
        }
        fc.clock(); // 29828
        assertTrue(fc.isFrameIrqFlag(), "flag set at CPU cycle 29828");
    }

    @Test
    void mode0_irqFlag_setAcross29828To29830_tableRows() {
        // §1.2: the flag-set rows are 29828, 29829 and the wrap cycle 29830.
        FrameCounter fc = new FrameCounter();
        for (int i = 1; i <= 29827; i++) {
            fc.clock();
        }
        for (int c = 29828; c <= 29830; c++) {
            fc.clearFrameIrqFlag();
            fc.clock();
            assertTrue(fc.isFrameIrqFlag(), "flag set on CPU cycle " + c);
        }
    }

    @Test
    void mode0_wrapsAt29830_periodRepeats() {
        FrameCounter fc = new FrameCounter();
        events(fc, 29830);
        assertEquals(0, fc.cycle(), "sequencer wraps 29830 → 0");
        // Second period produces the identical event table.
        assertEvents(events(fc, 29830), new int[][] {
                {7457, Q}, {14913, QH}, {22371, Q}, {29829, QH},
        });
    }

    // ---------------------------------------------------------------------
    // Mode 1 (5-step)
    // ---------------------------------------------------------------------

    @Test
    void mode1_eventTable_firstPeriod() {
        FrameCounter fc = new FrameCounter();
        fc.write4017(0x80, true); // C1: effects land 3 cycles after the write
        fc.clock();
        fc.clock();
        assertEquals(QH, fc.clock(), "$4017 bit 7 fires a quarter+half at the delayed reset");
        assertEvents(events(fc, 37282), new int[][] {
                {7457, Q}, {14913, QH}, {22371, Q}, {37281, QH},
        });
        assertEquals(0, fc.cycle(), "sequencer wraps 37282 → 0");
    }

    @Test
    void mode1_deadStepAt29829_noEvent() {
        FrameCounter fc = new FrameCounter();
        fc.write4017(0x80, true);
        fc.clock();
        fc.clock();
        fc.clock(); // delayed reset applies; sequencer at 0
        for (int i = 1; i <= 29827; i++) {
            fc.clock();
        }
        assertEquals(0, fc.clock(), "29828: nothing in mode 1");
        assertEquals(0, fc.clock(), "29829: dead step in mode 1");
        assertEquals(0, fc.clock(), "29830: nothing in mode 1");
    }

    @Test
    void mode1_neverSetsIrqFlag() {
        FrameCounter fc = new FrameCounter();
        fc.write4017(0x80, true); // 5-step, inhibit clear
        for (int i = 0; i < 2 * 37282; i++) {
            fc.clock();
            assertFalse(fc.isFrameIrqFlag(), "mode 1 never sets the frame IRQ flag");
        }
    }

    // ---------------------------------------------------------------------
    // $4017 write side effects
    // ---------------------------------------------------------------------

    @Test
    void write4017_bit7Clear_noImmediateClock() {
        FrameCounter fc = new FrameCounter();
        fc.write4017(0x00, true);
        for (int i = 0; i < 4; i++) {
            assertEquals(0, fc.clock(), "no delayed clock without bit 7");
        }
        fc.write4017(0x40, true);
        for (int i = 0; i < 4; i++) {
            assertEquals(0, fc.clock(), "bit 6 alone fires nothing");
        }
    }

    @Test
    void write4017_bit6_clearsFlag_clearingBit6DoesNotSet() {
        FrameCounter fc = new FrameCounter();
        for (int i = 0; i < 29828; i++) {
            fc.clock();
        }
        assertTrue(fc.isFrameIrqFlag());
        fc.write4017(0x40, true);
        assertFalse(fc.isFrameIrqFlag(), "$4017 bit 6 clears the frame IRQ flag (immediately)");
        fc.write4017(0x00, true);
        assertFalse(fc.isFrameIrqFlag(), "clearing bit 6 does not set the flag");
    }

    @Test
    void write4017_whileInhibited_flagNeverSets() {
        FrameCounter fc = new FrameCounter();
        fc.write4017(0x40, true); // 4-step, IRQ inhibited
        for (int i = 0; i < 29834; i++) {
            fc.clock();
        }
        assertFalse(fc.isFrameIrqFlag(), "inhibit blocks the flag entirely");
    }

    @Test
    void write4017_resetsSequencer_eventsRealign() {
        FrameCounter fc = new FrameCounter();
        for (int i = 0; i < 5000; i++) {
            fc.clock();
        }
        fc.write4017(0x00, true); // C1: reset lands 3 cycles after the write
        fc.clock();
        fc.clock();
        fc.clock();
        assertEquals(0, fc.cycle());
        assertEvents(events(fc, 7457), new int[][] {{7457, Q}});
    }

    // ---------------------------------------------------------------------
    // $4015-read race seam (consumed by APU in A3)
    // ---------------------------------------------------------------------

    @Test
    void clearOnRead_midWindowReAsserted_postWindowClearsForGood() {
        FrameCounter fc = new FrameCounter();
        for (int i = 0; i < 29828; i++) {
            fc.clock();
        }
        // Set-cycle read: the status was observed as 1 by the caller; the
        // clear lands but the remaining window cycles re-assert (C2 model,
        // pinned by blargg 6-irq_flag_timing #5).
        assertTrue(fc.isFrameIrqFlag());
        fc.clearFrameIrqFlagOnRead();
        assertFalse(fc.isFrameIrqFlag(), "the read itself clears the register");
        fc.clock(); // 29829 re-asserts
        assertTrue(fc.isFrameIrqFlag(), "window re-asserts after a mid-window read");
        // Past the window: the read clears and nothing re-asserts.
        for (int i = 0; i < 5; i++) {
            fc.clock();
        }
        assertTrue(fc.isFrameIrqFlag());
        fc.clearFrameIrqFlagOnRead();
        assertFalse(fc.isFrameIrqFlag(), "post-window read clears for good");
    }
}
