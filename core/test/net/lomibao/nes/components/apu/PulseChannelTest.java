package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Phase B3 — complete pulse channel, spec research doc §1.3. No
 * automated ROM covers the tone pipeline (research §5 gap): these tests
 * ARE the spec enforcement.
 *
 * <p>Hardware rules pinned here: the four duty sequences in playback
 * order; the 11-bit timer advancing the sequencer every (t+1) APU
 * cycles (f = CPU / (16·(t+1))); the $4003 write side effects (length
 * load, phase reset, envelope start, timer countdown NOT reset); and
 * the muting precedence (duty bit, length, t &lt; 8, sweep target).
 */
class PulseChannelTest {

    /** Duty sequences in playback order (research §1.3). */
    private static final int[][] DUTY = {
            {0, 1, 0, 0, 0, 0, 0, 0}, // 12.5%
            {0, 1, 1, 0, 0, 0, 0, 0}, // 25%
            {0, 1, 1, 1, 1, 0, 0, 0}, // 50%
            {1, 0, 0, 1, 1, 1, 1, 1}, // 25% negated
    };

    /**
     * Channel with length active, constant volume 15, timer t, phase 0
     * and a fully-armed countdown (the power-up empty counter is
     * consumed so phase assertions count from a clean t+1 period).
     */
    private static PulseChannel audible(int t) {
        PulseChannel p = new PulseChannel(true);
        p.lengthCounter().setEnabled(true);
        p.writeControl(0x1F);        // duty 0, constant volume 15
        p.writeTimerLow(t & 0xFF);
        p.writeTimerHigh((t >> 8) & 0x07); // loads length, resets phase
        p.clockTimer();              // power-up counter is 0 → arms to t
        p.writeTimerHigh((t >> 8) & 0x07); // re-reset phase; countdown kept
        return p;
    }

    /** Advance the sequencer exactly one step (t+1 APU cycles). */
    private static void step(PulseChannel p, int t) {
        for (int i = 0; i <= t; i++) {
            p.clockTimer();
        }
    }

    @Test
    void allFourDutySequences_playInDocumentedOrder() {
        for (int duty = 0; duty < 4; duty++) {
            PulseChannel p = audible(8);
            p.writeControl((duty << 6) | 0x1F); // constant volume 15
            for (int i = 0; i < 8; i++) {
                assertEquals(DUTY[duty][i] * 15, p.output(),
                        "duty " + duty + " step " + i);
                step(p, 8);
            }
        }
    }

    @Test
    void sequencerAdvances_everyTPlusOneApuCycles() {
        int t = 8;
        PulseChannel p = audible(t);
        assertEquals(0, p.sequencePhase());
        for (int i = 0; i < t; i++) {
            p.clockTimer();
        }
        assertEquals(0, p.sequencePhase(), "no advance before t+1 APU cycles");
        p.clockTimer(); // (t+1)th
        assertEquals(1, p.sequencePhase(), "advance lands on the (t+1)th APU cycle");
        for (int i = 0; i <= t; i++) {
            p.clockTimer();
        }
        assertEquals(2, p.sequencePhase(), "period stays t+1 (f = CPU/(16·(t+1)))");
    }

    @Test
    void timerHighWrite_resetsPhase_butNotTimerCountdown() {
        int t = 8;
        PulseChannel p = audible(t);
        step(p, t);          // phase 0 → 1
        p.clockTimer();
        p.clockTimer();      // 2 cycles into the next countdown (counter = t-2)
        assertEquals(1, p.sequencePhase());
        p.writeTimerHigh(0x00); // $4003: phase reset...
        assertEquals(0, p.sequencePhase(), "$4003 resets the duty phase (the click)");
        for (int i = 0; i < t - 2; i++) {
            p.clockTimer();  // finish the IN-FLIGHT countdown
        }
        assertEquals(0, p.sequencePhase(), "countdown not yet complete");
        p.clockTimer();
        assertEquals(1, p.sequencePhase(),
                "the in-flight timer countdown was NOT reset by $4003");
    }

    @Test
    void timerHighWrite_loadsLength_andStartsEnvelope() {
        PulseChannel p = new PulseChannel(false);
        p.lengthCounter().setEnabled(true);
        p.writeControl(0x04); // envelope mode, V=4
        p.writeTimerHigh(0x18); // length index 3 → 2
        assertEquals(2, p.lengthCounter().value(), "$4003 loads the length counter");
        assertEquals(0, p.output(), "decay still 0 until a quarter clock");
        p.clockQuarterFrame(); // consumes the start flag set by $4003
        assertEquals(15, p.envelope().decayLevel(), "$4003 set the envelope start flag");
    }

    @Test
    void elevenBitTimer_assembledFromLowAndHighWrites() {
        PulseChannel p = new PulseChannel(true);
        p.writeTimerLow(0xAB);
        p.writeTimerHigh(0x05); // timer bits 10-8 = 5
        assertEquals(0x5AB, p.timerPeriod());
        p.writeTimerLow(0x01); // low write preserves the high bits
        assertEquals(0x501, p.timerPeriod());
    }

    @Test
    void muting_zeroLengthCounter_forcesSilence() {
        PulseChannel p = audible(8);
        step(p, 8); // phase 1 — duty-0 high step
        assertEquals(15, p.output());
        p.lengthCounter().setEnabled(false); // forces counter to 0
        assertEquals(0, p.output(), "length 0 mutes regardless of the duty bit");
    }

    @Test
    void muting_timerBelow8_forcesSilence() {
        PulseChannel p = audible(7);
        step(p, 7); // phase 1
        assertEquals(0, p.output(), "t < 8 mutes (continuous sweep rule)");
        p.writeTimerLow(0x08);
        assertEquals(15, p.output(), "t = 8 un-mutes without any other write");
    }

    @Test
    void muting_sweepTargetOverflow_forcesSilence_evenWithSweepDisabled() {
        PulseChannel p = audible(0x600);
        p.writeSweep(0x01); // DISABLED, shift=1 → target 0x900 > $7FF
        step(p, 0x600); // phase 1
        assertEquals(0, p.output(),
                "sweep target > $7FF mutes even while the sweep is disabled");
    }

    @Test
    void halfFrameClock_appliesSweepToThePeriod_andClocksLength() {
        PulseChannel p = audible(0x100);
        p.writeSweep(0x81); // enabled, P=0, add, shift=1
        int lengthBefore = p.lengthCounter().value();
        p.clockHalfFrame();
        assertEquals(0x180, p.timerPeriod(), "half-frame sweep update hits the period");
        assertEquals(lengthBefore - 1, p.lengthCounter().value(),
                "half-frame also clocks the length counter");
    }

    @Test
    void output_isEnvelopeVolume_onHighDutySteps() {
        PulseChannel p = audible(8);
        p.writeControl(0x02); // duty 0, envelope mode, V=2
        p.writeTimerHigh(0x08); // restart envelope (phase reset too)
        p.clockQuarterFrame(); // start: decay = 15
        p.clockQuarterFrame(); // divider 2 → 1
        step(p, 8); // phase 1 — duty high
        assertEquals(15, p.output(), "envelope decay level drives the amplitude");
        // 2 more quarters wrap the divider → decay 14
        p.clockQuarterFrame();
        p.clockQuarterFrame();
        assertEquals(14, p.output());
    }

    @Test
    void dutyChange_takesEffectWithoutPhaseReset() {
        PulseChannel p = audible(8);
        step(p, 8); // phase 1
        assertEquals(1, p.sequencePhase());
        p.writeControl(0xDF); // duty 3, constant 15 — $4000 does NOT reset phase
        assertEquals(1, p.sequencePhase(), "$4000 writes never reset the sequencer");
        assertEquals(DUTY[3][1] * 15, p.output());
        assertNotEquals(DUTY[0][1], DUTY[3][1], "sanity: step 1 differs across duties");
    }
}
