package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B4 — triangle channel, spec research doc §1.4. No automated
 * ROM covers the linear counter or sequencer (research §5 gap): these
 * tests ARE the spec enforcement.
 *
 * <p>Hardware rules pinned here: the 32-step 15…0,0…15 sequence; the
 * CPU-rate timer (f = CPU / (32·(t+1))); sequencer gated on linear
 * counter &gt; 0 AND length counter &gt; 0 with the phase frozen (not
 * reset) when gated — no click; the linear-counter reload-flag /
 * control-flag dance; and the ultrasonic guard (no stepping when
 * t &lt; 2).
 */
class TriangleChannelTest {

    /**
     * Audible channel: length enabled+loaded, control flag set with
     * linear reload value R, timer t, phase 0, countdown armed.
     */
    private static TriangleChannel audible(int t, int linearReload) {
        TriangleChannel tri = new TriangleChannel();
        tri.lengthCounter().setEnabled(true);
        tri.writeLinear(0x80 | linearReload); // control set, R
        tri.writeTimerLow(t & 0xFF);
        tri.writeTimerHigh((t >> 8) & 0x07);  // length load + linear reload flag
        tri.clockQuarterFrame();              // linear counter ← R
        tri.clockTimer();                     // consume the power-up empty counter
        tri.resetPhase();                     // pin phase back to 0 for assertions
        return tri;
    }

    /** Advance the sequencer exactly one step (t+1 CPU cycles). */
    private static void step(TriangleChannel tri, int t) {
        for (int i = 0; i <= t; i++) {
            tri.clockTimer();
        }
    }

    @Test
    void sequence_is15Down0Then0Up15_symmetric() {
        int t = 4;
        TriangleChannel tri = audible(t, 0x7F);
        int[] expected = new int[32];
        for (int i = 0; i < 16; i++) {
            expected[i] = 15 - i;      // 15,14,…,0
            expected[16 + i] = i;      // 0,1,…,15
        }
        for (int i = 0; i < 32; i++) {
            assertEquals(expected[i], tri.output(), "sequence step " + i);
            step(tri, t);
        }
        assertEquals(15, tri.output(), "wraps back to 15 after 32 steps");
    }

    @Test
    void timer_clocksAtCpuRate_stepEveryTPlusOneCycles() {
        int t = 10;
        TriangleChannel tri = audible(t, 0x7F);
        assertEquals(0, tri.sequencePhase());
        for (int i = 0; i < t; i++) {
            tri.clockTimer();
        }
        assertEquals(0, tri.sequencePhase(), "no step before t+1 CPU cycles");
        tri.clockTimer();
        assertEquals(1, tri.sequencePhase(),
                "step on the (t+1)th CPU cycle — f = CPU/(32·(t+1))");
    }

    @Test
    void sequencerGated_onLinearCounterZero_phaseFreezesNotResets() {
        int t = 4;
        TriangleChannel tri = audible(t, 1); // linear counter = 1
        // Let the linear counter run out: clear the control flag, let one
        // quarter consume the still-set reload flag, then one decrements.
        tri.writeLinear(0x01); // control clear, R=1
        tri.clockQuarterFrame(); // reload flag still set → counter=1; flag cleared
        tri.clockQuarterFrame(); // decrement 1 → 0
        assertEquals(0, tri.linearCounter());
        step(tri, t); // timer fires but the gate is closed
        assertEquals(0, tri.sequencePhase(),
                "linear counter 0 freezes the sequencer");
        assertEquals(15, tri.output(),
                "output holds the frozen sequence value — no click");
    }

    @Test
    void sequencerGated_onLengthCounterZero() {
        int t = 4;
        TriangleChannel tri = audible(t, 0x7F);
        step(tri, t); // phase 1
        assertEquals(1, tri.sequencePhase());
        tri.lengthCounter().setEnabled(false); // force length to 0
        step(tri, t);
        assertEquals(1, tri.sequencePhase(), "length 0 freezes the sequencer");
        assertEquals(14, tri.output(), "output holds the last sequence value");
    }

    @Test
    void linearCounter_reloadFlagAndControlFlagDance() {
        TriangleChannel tri = new TriangleChannel();
        tri.lengthCounter().setEnabled(true);
        tri.writeLinear(0x85); // control SET, R=5
        tri.writeTimerHigh(0x08); // sets the linear reload flag
        assertTrue(tri.isLinearReloadFlag(), "$400B sets the reload flag");
        tri.clockQuarterFrame(); // reload flag → counter = 5
        assertEquals(5, tri.linearCounter());
        assertTrue(tri.isLinearReloadFlag(),
                "control flag set → reload flag NOT cleared");
        tri.clockQuarterFrame(); // flag still set → reloads again, no decrement
        assertEquals(5, tri.linearCounter(),
                "with control set the counter keeps reloading, never counts");
        tri.writeLinear(0x05); // control CLEAR, R=5
        tri.clockQuarterFrame(); // reload (flag still set), THEN clear the flag
        assertEquals(5, tri.linearCounter());
        assertFalse(tri.isLinearReloadFlag(),
                "control clear → the quarter clock clears the reload flag");
        tri.clockQuarterFrame(); // now decrements
        assertEquals(4, tri.linearCounter());
        tri.clockQuarterFrame();
        assertEquals(3, tri.linearCounter());
    }

    @Test
    void linearCounter_stopsAtZero_untilNextReloadFlag() {
        TriangleChannel tri = new TriangleChannel();
        tri.lengthCounter().setEnabled(true);
        tri.writeLinear(0x02); // control clear, R=2
        tri.writeTimerHigh(0x08); // reload flag
        tri.clockQuarterFrame(); // counter = 2, flag cleared
        tri.clockQuarterFrame(); // 1
        tri.clockQuarterFrame(); // 0
        tri.clockQuarterFrame(); // stays 0
        assertEquals(0, tri.linearCounter(), "linear counter holds at 0");
        tri.writeTimerHigh(0x08); // new reload flag
        tri.clockQuarterFrame();
        assertEquals(2, tri.linearCounter(), "$400B write re-arms the reload");
    }

    @Test
    void ultrasonicGuard_noSteppingWhenTimerBelow2() {
        TriangleChannel tri = audible(1, 0x7F); // t = 1 < 2
        for (int i = 0; i < 20; i++) {
            tri.clockTimer();
        }
        assertEquals(0, tri.sequencePhase(),
                "t < 2 (~ultrasonic) skips stepping to avoid aliasing garbage");
        assertEquals(15, tri.output());
        tri.writeTimerLow(2); // t = 2 → stepping resumes
        step(tri, 2);
        assertEquals(1, tri.sequencePhase());
    }

    @Test
    void timerRegisters_assembleElevenBits_and400BLoadsLength() {
        TriangleChannel tri = new TriangleChannel();
        tri.lengthCounter().setEnabled(true);
        tri.writeTimerLow(0xCD);
        tri.writeTimerHigh(0x0A); // bits 10-8 = 2, length index 1
        assertEquals(0x2CD, tri.timerPeriod());
        assertEquals(254, tri.lengthCounter().value(),
                "$400B bits 7-3 load the length counter (index 1 → 254)");
        tri.writeTimerLow(0x01);
        assertEquals(0x201, tri.timerPeriod(), "low write preserves high bits");
    }

    @Test
    void outputHasNoVolumeControl_alwaysSequenceValue() {
        int t = 4;
        TriangleChannel tri = audible(t, 0x7F);
        step(tri, t);
        step(tri, t);
        assertEquals(13, tri.output(), "output is the raw sequence value 0-15");
    }
}
