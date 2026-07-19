package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A2 — length counter unit tests (docs/apu-plan.md; spec research
 * §1.3/§1.7 and NESdev "APU Length Counter").
 */
class LengthCounterTest {

    /** The canonical NESdev table, spelled out independently of the impl. */
    private static final int[] EXPECTED_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6,
            160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22,
            192, 24, 72, 26, 16, 28, 32, 30,
    };

    private static LengthCounter enabled() {
        LengthCounter lc = new LengthCounter();
        lc.setEnabled(true);
        return lc;
    }

    @Test
    void load_all32TableEntries() {
        for (int i = 0; i < 32; i++) {
            LengthCounter lc = enabled();
            lc.load(i);
            assertEquals(EXPECTED_TABLE[i], lc.value(), "table entry " + i);
        }
    }

    @Test
    void load_blockedWhileDisabled() {
        LengthCounter lc = new LengthCounter(); // disabled at power-up
        lc.load(1); // would be 254
        assertEquals(0, lc.value(), "reload must be blocked while disabled");
        assertFalse(lc.isActive());
    }

    @Test
    void disable_forcesCounterToZero() {
        LengthCounter lc = enabled();
        lc.load(1);
        assertEquals(254, lc.value());
        lc.setEnabled(false);
        assertEquals(0, lc.value(), "$4015 disable forces the counter to 0");
    }

    @Test
    void reEnable_doesNotRestoreCounter() {
        LengthCounter lc = enabled();
        lc.load(1);
        lc.setEnabled(false);
        lc.setEnabled(true);
        assertEquals(0, lc.value(), "re-enabling never restores a cleared counter");
    }

    @Test
    void clockHalfFrame_decrementsToZeroAndStops() {
        LengthCounter lc = enabled();
        lc.load(3); // 2
        lc.clockHalfFrame();
        assertEquals(1, lc.value());
        assertTrue(lc.isActive());
        lc.clockHalfFrame();
        assertEquals(0, lc.value());
        assertFalse(lc.isActive());
        lc.clockHalfFrame();
        assertEquals(0, lc.value(), "counter sticks at 0");
    }

    @Test
    void halt_gatesClocking_resumeOnUnhalt() {
        LengthCounter lc = enabled();
        lc.load(0); // 10
        lc.setHalt(true);
        for (int i = 0; i < 5; i++) {
            lc.clockHalfFrame();
        }
        assertEquals(10, lc.value(), "halted counter never decrements");
        lc.setHalt(false);
        lc.clockHalfFrame();
        assertEquals(9, lc.value(), "clocking resumes when halt clears");
    }

    @Test
    void loadWhileRunning_replacesValue() {
        LengthCounter lc = enabled();
        lc.load(0); // 10
        lc.clockHalfFrame();
        assertEquals(9, lc.value());
        lc.load(2); // 20
        assertEquals(20, lc.value(), "a new load replaces the in-flight count");
    }

    @Test
    void indexIsMaskedTo5Bits() {
        LengthCounter lc = enabled();
        lc.load(32); // == index 0
        assertEquals(EXPECTED_TABLE[0], lc.value());
    }
}
