package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B5 — noise channel, spec research doc §1.5. No automated ROM
 * covers the LFSR (research §5 gap): these tests ARE the spec
 * enforcement.
 *
 * <p>Hardware rules pinned here: 15-bit LFSR with feedback = bit0 XOR
 * (bit6 in mode 1, else bit1) shifted in at bit 14; the 93/31-step
 * mode-1 loops vs the 32767-step mode-0 loop; the NTSC period table
 * (CPU cycles) driven at APU-cycle rate; silencing on LFSR bit0 or
 * length 0; and the $400F side effects (length load, envelope start).
 */
class NoiseChannelTest {

    @Test
    void lfsr_mode0_firstStepFromSeed1_shiftsFeedbackIntoBit14() {
        NoiseChannel n = new NoiseChannel();
        n.writeMode(0x00); // mode 0, period index 0
        // seed 1: feedback = bit0 ^ bit1 = 1 ^ 0 = 1 → lfsr = 0x4000
        n.clockLfsr();
        assertEquals(0x4000, n.lfsr(), "feedback lands in bit 14 after the shift");
        n.clockLfsr(); // feedback = 0^0 = 0 → 0x2000
        assertEquals(0x2000, n.lfsr());
    }

    @Test
    void lfsr_mode0_loopLengthIs32767() {
        NoiseChannel n = new NoiseChannel();
        n.writeMode(0x00);
        int steps = 0;
        do {
            n.clockLfsr();
            steps++;
        } while (n.lfsr() != 1 && steps <= 40000);
        assertEquals(32767, steps, "mode-0 LFSR is a maximal 15-bit sequence");
    }

    @Test
    void lfsr_mode1_loopLengthIs93Or31() {
        NoiseChannel n = new NoiseChannel();
        n.writeMode(0x80); // mode flag → feedback taps bit 6
        int steps = 0;
        do {
            n.clockLfsr();
            steps++;
        } while (n.lfsr() != 1 && steps <= 200);
        assertTrue(steps == 93 || steps == 31,
                "mode-1 metallic loops are 93 or 31 steps, was " + steps);
    }

    @Test
    void lfsr_modesDiverge_afterABit1DiffersFromBit6State() {
        NoiseChannel m0 = new NoiseChannel();
        NoiseChannel m1 = new NoiseChannel();
        m0.writeMode(0x00);
        m1.writeMode(0x80);
        // Walk both from seed 1; sequences must diverge (different taps).
        boolean diverged = false;
        for (int i = 0; i < 20 && !diverged; i++) {
            m0.clockLfsr();
            m1.clockLfsr();
            diverged = m0.lfsr() != m1.lfsr();
        }
        assertTrue(diverged, "bit-6 vs bit-1 taps must produce different sequences");
    }

    @Test
    void periodTable_timerClocksLfsrEveryTableCpuCycles() {
        // Index 2 → 16 CPU cycles = 8 APU cycles per LFSR clock.
        NoiseChannel n = new NoiseChannel();
        n.writeMode(0x02);
        n.clockTimer(); // power-up counter 0 → first LFSR clock, arms period
        int after1 = n.lfsr();
        assertNotEquals(1, after1, "first timer fire clocks the LFSR");
        for (int i = 0; i < 7; i++) {
            n.clockTimer();
        }
        assertEquals(after1, n.lfsr(), "no LFSR clock before 8 APU cycles");
        n.clockTimer(); // 8th APU cycle since the last fire
        assertNotEquals(after1, n.lfsr(),
                "LFSR clocks every table[i]/2 APU cycles (16 CPU cycles at index 2)");
    }

    @Test
    void periodTable_matchesResearchTable() {
        int[] cpuCycles = {4, 8, 16, 32, 64, 96, 128, 160,
                202, 254, 380, 508, 762, 1016, 2034, 4068};
        NoiseChannel n = new NoiseChannel();
        for (int i = 0; i < 16; i++) {
            n.writeMode(0x80 | i);
            assertEquals(cpuCycles[i] / 2, n.timerPeriod(),
                    "APU-cycle period for index " + i);
        }
    }

    @Test
    void output_silencedWhileLfsrBit0Set_orLengthZero() {
        NoiseChannel n = new NoiseChannel();
        n.lengthCounter().setEnabled(true);
        n.writeControl(0x1A); // constant volume 10
        n.writeLength(0x08);  // length index 1 → 254
        assertEquals(1, n.lfsr() & 1, "seed 1 has bit0 set");
        assertEquals(0, n.output(), "LFSR bit0 == 1 silences the channel");
        n.clockLfsr(); // → 0x4000, bit0 clear
        assertEquals(10, n.output(), "bit0 clear → envelope volume out");
        n.lengthCounter().setEnabled(false); // force length 0
        assertEquals(0, n.output(), "length 0 silences regardless of the LFSR");
    }

    @Test
    void writeLength_loadsLength_andStartsEnvelope() {
        NoiseChannel n = new NoiseChannel();
        n.lengthCounter().setEnabled(true);
        n.writeControl(0x05); // envelope mode, V=5
        n.writeLength(0x18);  // index 3 → 2
        assertEquals(2, n.lengthCounter().value(), "$400F loads the length counter");
        assertTrue(n.envelope().isStartFlag(), "$400F sets the envelope start flag");
        n.clockQuarterFrame();
        assertEquals(15, n.envelope().decayLevel());
    }

    @Test
    void output_followsEnvelopeDecay() {
        NoiseChannel n = new NoiseChannel();
        n.lengthCounter().setEnabled(true);
        n.writeControl(0x00); // envelope mode, V=0 (decay every quarter)
        n.writeLength(0x08);
        n.clockLfsr(); // bit0 clear → audible
        n.clockQuarterFrame(); // start: decay 15
        assertEquals(15, n.output());
        n.clockQuarterFrame(); // decay 14
        assertEquals(14, n.output());
    }

    @Test
    void writeControl_decodesHaltConstantVolumeAndV() {
        NoiseChannel n = new NoiseChannel();
        n.writeControl(0x3C); // halt/loop + constant volume, V=12
        assertTrue(n.lengthCounter().isHalt(), "$400C bit 5 is the length halt");
        assertTrue(n.envelope().isLoop(), "bit 5 doubles as the envelope loop");
        assertTrue(n.envelope().isConstantVolume());
        assertEquals(12, n.envelope().output());
    }

    /**
     * Directed tap vector (review round 1): from lfsr=2, mode 0 feedback is
     * bit0(0) XOR bit1(1) = 1 -> (2>>1) | 0x4000 = 0x4001. A wrong tap at
     * bit 2 would produce 1 instead — this uniquely pins the bit-1 tap,
     * which the loop-length test alone does not (several degree-15
     * trinomials are primitive and also yield 32767-step loops).
     */
    @org.junit.jupiter.api.Test
    void mode0Tap_isBit1_directedVector() {
        NoiseChannel n = new NoiseChannel();
        n.lfsr = 2;
        n.clockLfsr();
        org.junit.jupiter.api.Assertions.assertEquals(0x4001, n.lfsr,
                "mode-0 feedback must tap bit 1 (got a different polynomial)");
    }
}