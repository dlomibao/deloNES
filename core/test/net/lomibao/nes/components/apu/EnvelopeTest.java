package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B1 — envelope generator (shared pulse/noise), spec research doc
 * §1.3 "Envelope". No automated ROM covers the envelope (research §5
 * gap): these tests ARE the spec enforcement.
 *
 * <p>Hardware rules pinned here:
 * <ul>
 *   <li>quarter-frame with start flag set: clear start, decay = 15,
 *       divider = V (no decay clock on that quarter);</li>
 *   <li>otherwise clock the divider; on divider wrap (0 → V) clock the
 *       decay level: decrement if &gt; 0, else reload to 15 iff loop;</li>
 *   <li>output = V when constant-volume, else decay level — and the
 *       decay machinery keeps running even in constant-volume mode.</li>
 * </ul>
 */
class EnvelopeTest {

    /** Feed n quarter-frame clocks. */
    private static void quarters(Envelope e, int n) {
        for (int i = 0; i < n; i++) {
            e.clockQuarterFrame();
        }
    }

    @Test
    void startFlag_reloadsDecayAndDivider_andClears() {
        Envelope e = new Envelope();
        e.writeControl(0x05); // V=5, no loop, no constant volume
        e.start();
        assertTrue(e.isStartFlag());
        e.clockQuarterFrame();
        assertFalse(e.isStartFlag(), "start flag cleared by the quarter clock");
        assertEquals(15, e.decayLevel(), "decay reloaded to 15");
        assertEquals(5, e.divider(), "divider reloaded to V");
        assertEquals(15, e.output(), "output is the decay level (not constant volume)");
    }

    @Test
    void startQuarter_doesNotAlsoClockDecay() {
        Envelope e = new Envelope();
        e.writeControl(0x00); // V=0 — divider wraps every quarter
        e.start();
        e.clockQuarterFrame(); // start handling only
        assertEquals(15, e.decayLevel(), "the start quarter must not decrement decay");
        e.clockQuarterFrame(); // divider 0 → wrap → decay clock
        assertEquals(14, e.decayLevel());
    }

    @Test
    void dividerWrap_periodIsVPlusOneQuarters() {
        Envelope e = new Envelope();
        e.writeControl(0x02); // V=2 → decay clocks every 3 quarter-frames
        e.start();
        e.clockQuarterFrame(); // consume start: decay=15, divider=2
        quarters(e, 2); // divider 2→1→0, no wrap yet
        assertEquals(15, e.decayLevel(), "no decay clock until the divider wraps");
        e.clockQuarterFrame(); // divider 0 → reload to 2, decay 15→14
        assertEquals(14, e.decayLevel());
        assertEquals(2, e.divider(), "divider reloads to V on wrap");
        quarters(e, 3); // one full period
        assertEquals(13, e.decayLevel());
    }

    @Test
    void noLoop_decayStopsAtZero() {
        Envelope e = new Envelope();
        e.writeControl(0x00); // V=0, loop clear
        e.start();
        e.clockQuarterFrame(); // start
        quarters(e, 15); // decay 15 → 0
        assertEquals(0, e.decayLevel());
        quarters(e, 5);
        assertEquals(0, e.decayLevel(), "without loop, decay holds at 0");
        assertEquals(0, e.output());
    }

    @Test
    void loop_reloadsDecayTo15AfterZero() {
        Envelope e = new Envelope();
        e.writeControl(0x20); // V=0, loop (bit 5) set
        e.start();
        e.clockQuarterFrame(); // start
        quarters(e, 15); // decay 15 → 0
        assertEquals(0, e.decayLevel());
        e.clockQuarterFrame(); // wrap with decay 0 + loop → reload 15
        assertEquals(15, e.decayLevel(), "loop flag reloads decay to 15");
    }

    @Test
    void constantVolume_outputsV_whileDecayKeepsRunning() {
        Envelope e = new Envelope();
        e.writeControl(0x1A); // constant volume (bit 4), V=10
        e.start();
        e.clockQuarterFrame(); // start: decay=15
        assertEquals(10, e.output(), "constant-volume mode outputs V");
        quarters(e, 33); // 3 full V=10 periods → decay 15→12
        assertEquals(12, e.decayLevel(),
                "decay keeps clocking underneath constant-volume mode");
        assertEquals(10, e.output(), "output still V");
    }

    @Test
    void clearingConstantVolume_exposesTheLiveDecayLevel() {
        Envelope e = new Envelope();
        e.writeControl(0x10); // constant volume, V=0
        e.start();
        e.clockQuarterFrame(); // start
        quarters(e, 4); // decay 15→11 underneath
        e.writeControl(0x00); // drop to envelope mode, V=0
        assertEquals(11, e.output(),
                "switching off constant volume reveals the decay that kept running");
    }

    @Test
    void writeControl_doesNotRestartTheEnvelope() {
        Envelope e = new Envelope();
        e.writeControl(0x03); // V=3
        e.start();
        e.clockQuarterFrame(); // start: decay=15, divider=3
        quarters(e, 4); // decay → 14
        assertEquals(14, e.decayLevel());
        e.writeControl(0x0F); // change V mid-flight — no start
        assertFalse(e.isStartFlag(), "control writes never set the start flag");
        assertEquals(14, e.decayLevel(), "control writes never touch the decay level");
        assertEquals(14, e.output(), "envelope mode output unchanged by the write");
    }

    @Test
    void outputBeforeAnyStart_isDecayZero() {
        Envelope e = new Envelope();
        e.writeControl(0x07); // envelope mode
        assertEquals(0, e.output(), "power-up decay level is 0 in envelope mode");
        e.writeControl(0x17); // constant volume
        assertEquals(7, e.output(), "constant-volume output works without a start");
    }
}
