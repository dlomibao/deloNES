package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E1 — {@link ApuMixer}: §1.8 table values, nonlinear mix
 * composition, box-average downsampler cadence (fractional accumulator,
 * no drift, ~77/23 per-frame alternation), IIR stability, and stream
 * reset semantics.
 */
class ApuMixerTest {

    /** NTSC CPU cycles per frame alternate 29780/29781 (29780.5 avg). */
    private static final int FRAME_CYCLES_EVEN = 29780;
    private static final int FRAME_CYCLES_ODD = 29781;

    // ---------------------------------------------------------------
    // §1.8 tables
    // ---------------------------------------------------------------

    @Test
    void pulseTable_matchesFormula_andZeroEntry() {
        assertEquals(31, ApuMixer.PULSE_TABLE.length);
        assertEquals(0f, ApuMixer.PULSE_TABLE[0], "pulse_table[0] must be exactly 0");
        for (int n : new int[] {1, 15, 30}) {
            double expected = 95.52 / (8128.0 / n + 100.0);
            assertEquals(expected, ApuMixer.PULSE_TABLE[n], 1e-7,
                    "pulse_table[" + n + "]");
        }
        // Monotonic increasing — a swapped constant would break this.
        for (int n = 1; n < 31; n++) {
            assertTrue(ApuMixer.PULSE_TABLE[n] > ApuMixer.PULSE_TABLE[n - 1]);
        }
    }

    @Test
    void tndTable_matchesFormula_andZeroEntry() {
        assertEquals(203, ApuMixer.TND_TABLE.length);
        assertEquals(0f, ApuMixer.TND_TABLE[0], "tnd_table[0] must be exactly 0");
        for (int n : new int[] {1, 100, 202}) {
            double expected = 163.67 / (24329.0 / n + 100.0);
            assertEquals(expected, ApuMixer.TND_TABLE[n], 1e-7,
                    "tnd_table[" + n + "]");
        }
        for (int n = 1; n < 203; n++) {
            assertTrue(ApuMixer.TND_TABLE[n] > ApuMixer.TND_TABLE[n - 1]);
        }
    }

    @Test
    void mixLevel_composesTablesPerFormula() {
        // out = pulse_table[p1+p2] + tnd_table[3*tri + 2*noise + dmc]
        assertEquals(0f, ApuMixer.mixLevel(0, 0, 0, 0, 0));
        assertEquals(ApuMixer.PULSE_TABLE[30], ApuMixer.mixLevel(15, 15, 0, 0, 0));
        assertEquals(ApuMixer.TND_TABLE[3 * 15 + 2 * 15 + 127],
                ApuMixer.mixLevel(0, 0, 15, 15, 127));
        assertEquals(ApuMixer.PULSE_TABLE[12] + ApuMixer.TND_TABLE[3 * 7 + 2 * 3 + 40],
                ApuMixer.mixLevel(5, 7, 7, 3, 40));
    }

    // ---------------------------------------------------------------
    // Box-average downsampler
    // ---------------------------------------------------------------

    /** Feed {@code cycles} CPU cycles of fixed levels, draining as we go; returns emitted count. */
    private static int run(ApuMixer mixer, ApuSampleBuffer ring, int cycles,
                           int p1, int p2, int tri, int noise, int dmc) {
        float[] scratch = new float[512];
        int emitted = 0;
        for (int c = 0; c < cycles; c++) {
            mixer.acceptCpuCycle(p1, p2, tri, noise, dmc);
            if (ring.available() >= 256) {
                emitted += ring.read(scratch, scratch.length);
            }
        }
        emitted += ring.read(scratch, scratch.length);
        while (ring.available() > 0) {
            emitted += ring.read(scratch, scratch.length);
        }
        return emitted;
    }

    @Test
    void oneSecondOfCycles_yieldsSampleRateSamples_noDrift() {
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        int emitted = run(mixer, ring, 1_789_773, 4, 4, 8, 2, 30);
        assertTrue(Math.abs(emitted - 44100) <= 1,
                "one CPU-second at 44.1kHz must emit 44100 +/-1 samples, got " + emitted);
    }

    @Test
    void perFrameCounts_alternate733and734_atRoughly77to23() {
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        float[] scratch = new float[2048];
        int frames = 1000;
        int total = 0;
        int count734 = 0;
        long cycleTotal = 0;
        for (int f = 0; f < frames; f++) {
            int frameCycles = (f & 1) == 0 ? FRAME_CYCLES_EVEN : FRAME_CYCLES_ODD;
            cycleTotal += frameCycles;
            for (int c = 0; c < frameCycles; c++) {
                mixer.acceptCpuCycle(8, 8, 8, 8, 64);
            }
            int n = 0;
            int got;
            while ((got = ring.read(scratch, scratch.length)) > 0) {
                n += got;
            }
            assertTrue(n == 733 || n == 734,
                    "frame " + f + " emitted " + n + " samples — must be 733 or 734 (never 735)");
            if (n == 734) {
                count734++;
            }
            total += n;
        }
        // 29780.5 / (1789773/44100) = 733.74 → ~74% of frames carry 734.
        double frac734 = count734 / (double) frames;
        assertTrue(frac734 > 0.68 && frac734 < 0.80,
                "734-sample frames should be ~74% of frames (the ~77/23 weighted mix), got " + frac734);
        // No cumulative drift against the cycle count.
        int expectedTotal = (int) (cycleTotal * 44100.0 / ApuMixer.CPU_HZ);
        assertTrue(Math.abs(total - expectedTotal) <= 1,
                "cumulative samples " + total + " drifted from cycle-derived " + expectedTotal);
        assertEquals(0, ring.dropped(), "drained every frame — nothing may drop");
    }

    @Test
    void sampleRate48k_perFrameCountsAlternate798and799() {
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        mixer.setSampleRate(48000);
        assertEquals(48000, mixer.getSampleRate());
        float[] scratch = new float[2048];
        for (int f = 0; f < 200; f++) {
            int frameCycles = (f & 1) == 0 ? FRAME_CYCLES_EVEN : FRAME_CYCLES_ODD;
            for (int c = 0; c < frameCycles; c++) {
                mixer.acceptCpuCycle(0, 0, 15, 0, 0);
            }
            int n = 0;
            int got;
            while ((got = ring.read(scratch, scratch.length)) > 0) {
                n += got;
            }
            assertTrue(n == 798 || n == 799,
                    "48kHz frame emitted " + n + " — must be 798 or 799");
        }
    }

    @Test
    void constantInput_boxAverageReproducesTheConstant() {
        // A constant level must survive the fractional-boundary weighting
        // exactly — any mis-normalized 77/23 split or missing carry term
        // shows up as a wrong raw average.
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        float level = ApuMixer.mixLevel(15, 15, 0, 0, 0);
        for (int c = 0; c < 10_000; c++) {
            mixer.acceptCpuCycle(15, 15, 0, 0, 0);
        }
        assertEquals(level, mixer.lastRaw, 1e-6f,
                "box average of a constant must be the constant");
    }

    @Test
    void silence_producesExactlyZeroSamples() {
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        float[] scratch = new float[512];
        for (int c = 0; c < 100_000; c++) {
            mixer.acceptCpuCycle(0, 0, 0, 0, 0);
            int n = ring.read(scratch, scratch.length);
            for (int i = 0; i < n; i++) {
                assertEquals(0f, scratch[i], "all-channels-off must be exact digital silence");
            }
        }
    }

    // ---------------------------------------------------------------
    // IIR filters
    // ---------------------------------------------------------------

    @Test
    void highPass_blocksDc_stepInputDecaysTowardZero() {
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        float[] scratch = new float[512];
        // Step to a loud constant level and hold for ~1s of CPU time.
        float first = Float.NaN;
        float last = 0f;
        boolean sawFirst = false;
        for (int c = 0; c < 1_789_773; c++) {
            mixer.acceptCpuCycle(15, 15, 15, 15, 127);
            int n = ring.read(scratch, scratch.length);
            for (int i = 0; i < n; i++) {
                if (!sawFirst) {
                    first = scratch[i];
                    sawFirst = true;
                }
                last = scratch[i];
            }
        }
        assertTrue(sawFirst);
        assertTrue(Math.abs(last) < 0.01f,
                "90Hz HP must bleed off DC within a second; final sample " + last);
        assertTrue(Math.abs(last) < Math.abs(first),
                "step response must decay (first=" + first + " last=" + last + ")");
    }

    @Test
    void worstCaseAlternatingInput_staysBoundedAndFinite() {
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        float[] scratch = new float[512];
        for (int c = 0; c < 500_000; c++) {
            if ((c & 1) == 0) {
                mixer.acceptCpuCycle(15, 15, 15, 15, 127);
            } else {
                mixer.acceptCpuCycle(0, 0, 0, 0, 0);
            }
            int n = ring.read(scratch, scratch.length);
            for (int i = 0; i < n; i++) {
                float s = scratch[i];
                assertTrue(!Float.isNaN(s) && !Float.isInfinite(s), "IIR blew up: " + s);
                assertTrue(Math.abs(s) <= 2f, "sample out of bounds: " + s);
            }
        }
    }

    // ---------------------------------------------------------------
    // Configuration / reset
    // ---------------------------------------------------------------

    @Test
    void resetStream_clearsDownsamplerAndFilterState() {
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        ApuMixer mixer = new ApuMixer(ring);
        for (int c = 0; c < 5000; c++) {
            mixer.acceptCpuCycle(15, 15, 15, 15, 127);
        }
        ring.clear();
        mixer.resetStream();
        assertEquals(0f, mixer.lastRaw);
        // Post-reset silence must be exact zero — leaked IIR state would
        // decay audibly instead.
        float[] scratch = new float[512];
        for (int c = 0; c < 10_000; c++) {
            mixer.acceptCpuCycle(0, 0, 0, 0, 0);
            int n = ring.read(scratch, scratch.length);
            for (int i = 0; i < n; i++) {
                assertEquals(0f, scratch[i], "stream state leaked across resetStream()");
            }
        }
    }

    @Test
    void invalidSampleRate_rejected() {
        ApuMixer mixer = new ApuMixer(new ApuSampleBuffer(16));
        assertThrows(IllegalArgumentException.class, () -> mixer.setSampleRate(0));
        assertThrows(IllegalArgumentException.class, () -> mixer.setSampleRate(-44100));
    }

    @Test
    void defaultSampleRate_is44100() {
        ApuMixer mixer = new ApuMixer(new ApuSampleBuffer(16));
        assertEquals(44100, mixer.getSampleRate());
        assertEquals(ApuMixer.DEFAULT_SAMPLE_RATE, mixer.getSampleRate());
    }
}
