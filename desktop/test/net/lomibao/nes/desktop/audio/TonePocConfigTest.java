package net.lomibao.nes.desktop.audio;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 0 POC-D — flag parsing for the {@code desktop:audioPoc} launcher. */
class TonePocConfigTest {

    @Test
    void defaultsMatchThePlan() {
        TonePocLauncher.Config c = TonePocLauncher.Config.parse(new Properties());
        assertEquals(-1, c.bufferSize, "unset = keep LibGDX default (512)");
        assertEquals(-1, c.bufferCount, "unset = keep LibGDX default (9)");
        assertFalse(c.stereo, "mono is the default (plan D15)");
        assertFalse(c.underrunTest);
        assertEquals(75, c.runSeconds, "60+s soak with margin");
    }

    @Test
    void variantRunPropertiesParse() {
        Properties p = new Properties();
        p.setProperty("delones.audioPoc.bufferSize", "1024");
        p.setProperty("delones.audioPoc.bufferCount", "4");
        p.setProperty("delones.audioPoc.stereo", "true");
        p.setProperty("delones.audioPoc.underrunTest", "true");
        p.setProperty("delones.audioPoc.seconds", "120");
        TonePocLauncher.Config c = TonePocLauncher.Config.parse(p);
        assertEquals(1024, c.bufferSize);
        assertEquals(4, c.bufferCount);
        assertTrue(c.stereo);
        assertTrue(c.underrunTest);
        assertEquals(120, c.runSeconds);
    }

    @Test
    void malformedNumbersFallBackToDefaults() {
        Properties p = new Properties();
        p.setProperty("delones.audioPoc.bufferSize", "not-a-number");
        TonePocLauncher.Config c = TonePocLauncher.Config.parse(p);
        assertEquals(-1, c.bufferSize);
    }

    @Test
    void percentileReturnsP99OfWriteTimes() {
        long[] samples = new long[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = i + 1; // 1..100
        }
        // Nearest-rank: p99 of 100 samples is the 99th smallest.
        assertEquals(99, TonePocLauncher.percentileNanos(samples, 100, 0.99));
        assertEquals(50, TonePocLauncher.percentileNanos(samples, 100, 0.50));
        assertEquals(100, TonePocLauncher.percentileNanos(samples, 100, 1.0),
                "p=1.0 is the max");
        assertEquals(0, TonePocLauncher.percentileNanos(samples, 0, 0.99),
                "no samples = 0");
    }
}
