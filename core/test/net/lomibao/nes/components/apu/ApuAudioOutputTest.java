package net.lomibao.nes.components.apu;

import net.lomibao.nes.components.APU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E4 — APU-level audio output pins (the "440 Hz square from
 * registers" test from the audio research §6, and post-HP silence).
 *
 * <p>Drives a bare {@link APU} (no bus needed — the C2 access
 * compensation no-ops without a CPU) one CPU cycle at a time and
 * observes the emitted sample stream through the core ring.
 */
class ApuAudioOutputTest {

    private static final int CPU_CYCLES_PER_SECOND = 1_789_773;

    /** Clock {@code cycles} CPU cycles, draining every emitted sample into a dense array. */
    private static float[] runAndDrain(APU apu, int cycles) {
        float[] scratch = new float[2048];
        float[] out = new float[(int) (cycles / 40.0) + 16];
        int n = 0;
        for (int c = 0; c < cycles; c++) {
            apu.clock();
            if (apu.sampleBuffer().available() >= 1024) {
                int got = apu.sampleBuffer().read(scratch, scratch.length);
                System.arraycopy(scratch, 0, out, n, got);
                n += got;
            }
        }
        int got;
        while ((got = apu.sampleBuffer().read(scratch, scratch.length)) > 0) {
            System.arraycopy(scratch, 0, out, n, got);
            n += got;
        }
        float[] dense = new float[n];
        System.arraycopy(out, 0, dense, 0, n);
        return dense;
    }

    /** Count sign transitions with +/-eps hysteresis (a square's fundamental x2 per second). */
    private static int zeroCrossings(float[] samples, float eps) {
        int crossings = 0;
        int sign = 0;
        for (float s : samples) {
            if (s > eps) {
                if (sign < 0) {
                    crossings++;
                }
                sign = 1;
            } else if (s < -eps) {
                if (sign > 0) {
                    crossings++;
                }
                sign = -1;
            }
        }
        return crossings;
    }

    @Test
    void scriptedPulse1Registers_yieldExpectedFundamental() {
        // Timer t = 253 -> f = CPU / (16 * (t+1)) = 1789773 / 4064 = 440.4 Hz.
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x01);          // enable pulse 1
        apu.cpuBusWrite(0x4000, (byte) 0xBF);          // duty 50%, halt, constant vol 15
        apu.cpuBusWrite(0x4001, (byte) 0x00);          // sweep off
        apu.cpuBusWrite(0x4002, (byte) 0xFD);          // timer low = 253
        apu.cpuBusWrite(0x4003, (byte) 0x00);          // timer high 0 + length load

        float[] samples = runAndDrain(apu, CPU_CYCLES_PER_SECOND);
        assertTrue(Math.abs(samples.length - 44100) <= 1,
                "one CPU-second must emit 44100 +/-1 samples, got " + samples.length);

        // Expect ~2 * 440.4 = 880.8 crossings over the second (+/-3%).
        int crossings = zeroCrossings(samples, 0.005f);
        // Window tightened to ~±1% (review round 1): wide enough for edge
        // truncation, tight enough that a t-vs-t+1 period error (0.39%,
        // ~877 vs 881) plus table jitter cannot slip through unnoticed
        // when combined with the exact PulseChannelTest period pins.
        assertTrue(crossings >= 872 && crossings <= 890,
                "zero-crossing count " + crossings
                + " outside the 440.4 Hz fundamental window [872, 890]");

        // Non-vacuity: the tone is actually loud, not numerical dust.
        float peak = 0f;
        for (float s : samples) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertTrue(peak > 0.02f, "pulse tone peak too small: " + peak);
    }

    @Test
    void allChannelsDisabled_isSilentPostHighPass() {
        // Power-up: every channel disabled. The triangle parks on a
        // constant sequence step (a pure DC term through the nonlinear
        // mixer), so "silence" is asserted POST-HP (plan E4): the 90 Hz
        // high-pass bleeds the DC off and the stream settles to zero.
        APU apu = new APU();
        float[] samples = runAndDrain(apu, CPU_CYCLES_PER_SECOND);
        assertTrue(samples.length > 40_000);
        // Skip 0.1 s of HP settle (tau ~1.8 ms — generous margin).
        for (int i = 4410; i < samples.length; i++) {
            assertTrue(Math.abs(samples[i]) < 1e-3f,
                    "disabled channels must be silent post-HP; sample[" + i + "]=" + samples[i]);
        }
    }

    @Test
    void reset_dropsBufferedSamplesAndStreamState() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x01);
        apu.cpuBusWrite(0x4000, (byte) 0xBF);
        apu.cpuBusWrite(0x4002, (byte) 0xFD);
        apu.cpuBusWrite(0x4003, (byte) 0x00);
        for (int c = 0; c < 100_000; c++) {
            apu.clock();
        }
        assertTrue(apu.sampleBuffer().available() > 0, "tone must have buffered samples");
        apu.reset();
        assertTrue(apu.sampleBuffer().available() == 0,
                "reset() must clear the ring (D18 / determinism section)");
        assertTrue(apu.sampleBuffer().dropped() == 0);
    }
}
