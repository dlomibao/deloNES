package net.lomibao.nes.desktop.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 POC-D (derisk) — headless assertions on the 440 Hz square
 * synthesis math that {@code TonePocLauncher} feeds to the audio device.
 * The audible half of the POC is manual; this pins the sample math.
 */
class SquareToneGeneratorTest {

    @Test
    void frameSampleCountsAlternate733And734At44100() {
        SquareToneGenerator gen = new SquareToneGenerator(44100f, 440.0, 0.15f);
        long total = 0;
        for (int i = 0; i < 601; i++) {
            int n = gen.nextFrameSampleCount();
            assertTrue(n == 733 || n == 734, "frame " + i + " produced " + n);
            total += n;
        }
        // 601 frames at 60.0988 fps: 601 * 44100 / 60.0988 = 441_024.6...
        double expected = 601 * 44100.0 / SquareToneGenerator.NES_FRAME_RATE;
        assertTrue(Math.abs(total - expected) <= 1.0,
                "total " + total + " vs expected " + expected);
    }

    @Test
    void frameSampleCountsAlternate798And799At48000() {
        SquareToneGenerator gen = new SquareToneGenerator(48000f, 440.0, 0.15f);
        for (int i = 0; i < 601; i++) {
            int n = gen.nextFrameSampleCount();
            assertTrue(n == 798 || n == 799, "frame " + i + " produced " + n);
        }
    }

    @Test
    void oneSecondOfSamplesHas880ZeroCrossingsFor440Hz() {
        SquareToneGenerator gen = new SquareToneGenerator(44100f, 440.0, 0.15f);
        float[] buf = new float[44100];
        gen.fill(buf, buf.length);
        int crossings = 0;
        for (int i = 1; i < buf.length; i++) {
            if ((buf[i - 1] > 0) != (buf[i] > 0)) {
                crossings++;
            }
        }
        // A 440 Hz square has 2 * 440 = 880 sign transitions per second
        // (+/-1 for period truncation at the buffer edges).
        assertTrue(crossings >= 879 && crossings <= 881,
                "zero crossings = " + crossings);
    }

    @Test
    void samplesAreExactlyPlusOrMinusAmplitude() {
        SquareToneGenerator gen = new SquareToneGenerator(44100f, 440.0, 0.15f);
        float[] buf = new float[4096];
        gen.fill(buf, buf.length);
        for (float s : buf) {
            assertEquals(0.15f, Math.abs(s), 1e-6f);
        }
    }

    @Test
    void phaseIsContinuousAcrossFillCalls() {
        // Filling in two chunks must produce the same waveform as one call —
        // a phase reset between render() calls would buzz at frame rate.
        SquareToneGenerator one = new SquareToneGenerator(44100f, 440.0, 0.15f);
        SquareToneGenerator two = new SquareToneGenerator(44100f, 440.0, 0.15f);
        float[] whole = new float[1466];
        one.fill(whole, whole.length);
        float[] a = new float[733];
        float[] b = new float[733];
        two.fill(a, a.length);
        two.fill(b, b.length);
        for (int i = 0; i < 733; i++) {
            assertEquals(whole[i], a[i], 0f, "first chunk sample " + i);
            assertEquals(whole[733 + i], b[i], 0f, "second chunk sample " + i);
        }
    }
}
