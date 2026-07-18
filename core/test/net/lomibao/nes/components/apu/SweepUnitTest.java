package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B2 — pulse sweep unit, spec research doc §1.3 "Sweep" and
 * NESdev "APU Sweep". No automated ROM covers the sweep (research §5
 * gap): these tests ARE the spec enforcement.
 *
 * <p>Hardware rules pinned here:
 * <ul>
 *   <li>target = period ± (period &gt;&gt; shift); on negate pulse 1
 *       adds the one's complement (−change−1), pulse 2 the two's
 *       complement (−change) — the audible per-channel split;</li>
 *   <li>muting (t &lt; 8 or target &gt; $7FF) is computed continuously
 *       from the current period, even while the sweep is disabled;</li>
 *   <li>half-frame: if divider==0 &amp;&amp; enabled &amp;&amp; shift!=0
 *       &amp;&amp; not muting → period = target; then if divider==0 ||
 *       reload → divider = P, clear reload; else decrement;</li>
 *   <li>$4001/$4005 writes set the reload flag.</li>
 * </ul>
 */
class SweepUnitTest {

    private static SweepUnit pulse1Sweep() {
        return new SweepUnit(true);
    }

    private static SweepUnit pulse2Sweep() {
        return new SweepUnit(false);
    }

    // ---- target-period computation -------------------------------------

    @Test
    void target_addMode_isPeriodPlusShiftedPeriod() {
        SweepUnit s = pulse1Sweep();
        s.write(0x82); // enabled, P=0, negate clear, shift=2
        assertEquals(0x100 + (0x100 >> 2), s.targetPeriod(0x100));
    }

    @Test
    void target_negate_pulse1_usesOnesComplement() {
        SweepUnit s = pulse1Sweep();
        s.write(0x8A); // enabled, negate, shift=2
        // change = 0x100 >> 2 = 0x40; pulse 1: period - change - 1
        assertEquals(0x100 - 0x40 - 1, s.targetPeriod(0x100),
                "pulse 1 negate adds the one's complement (-change-1)");
    }

    @Test
    void target_negate_pulse2_usesTwosComplement() {
        SweepUnit s = pulse2Sweep();
        s.write(0x8A); // enabled, negate, shift=2
        assertEquals(0x100 - 0x40, s.targetPeriod(0x100),
                "pulse 2 negate adds the two's complement (-change)");
    }

    @Test
    void target_complementSplit_differsByExactlyOne() {
        SweepUnit p1 = pulse1Sweep();
        SweepUnit p2 = pulse2Sweep();
        p1.write(0x0B); // negate, shift=3 (disabled — target still computed)
        p2.write(0x0B);
        assertEquals(p2.targetPeriod(0x200) - 1, p1.targetPeriod(0x200),
                "the audible p1/p2 difference is exactly 1 in negate mode");
    }

    // ---- continuous muting ---------------------------------------------

    @Test
    void mutes_whenCurrentPeriodBelow8() {
        SweepUnit s = pulse1Sweep();
        s.write(0x00); // fully disabled
        assertTrue(s.mutes(7), "t < 8 mutes");
        assertFalse(s.mutes(8));
    }

    @Test
    void mutes_whenTargetExceeds7FF_evenWhileDisabled() {
        SweepUnit s = pulse2Sweep();
        s.write(0x01); // DISABLED (bit 7 clear), shift=1, add mode
        // period 0x600 → target 0x600 + 0x300 = 0x900 > $7FF
        assertTrue(s.mutes(0x600),
                "target > $7FF mutes continuously, even with the sweep disabled");
        assertFalse(s.mutes(0x400), "target 0x600 does not mute");
    }

    @Test
    void mutes_shiftZeroAddMode_mutesLargePeriods() {
        SweepUnit s = pulse1Sweep();
        s.write(0x00); // disabled, shift=0 → change = period, target = 2*period
        assertTrue(s.mutes(0x500), "shift 0 doubles the target: 0xA00 > $7FF mutes");
        assertFalse(s.mutes(0x3FF), "target 0x7FE stays in range");
    }

    @Test
    void negateMode_neverMutesFromTargetOverflow() {
        SweepUnit s = pulse2Sweep();
        s.write(0x0F); // negate, shift=7
        assertFalse(s.mutes(0x7FF), "negate targets can never exceed $7FF");
    }

    // ---- half-frame clock ----------------------------------------------

    @Test
    void halfFrame_updatesPeriodOnlyWhenDividerZeroEnabledShiftNonzeroNotMuting() {
        SweepUnit s = pulse1Sweep();
        s.write(0x81); // enabled, P=0, add, shift=1
        // write sets reload; first half-frame: divider==0 (power-up) →
        // update fires immediately, then divider = P = 0.
        assertEquals(0x100 + 0x80, s.clockHalfFrame(0x100),
                "divider==0 && enabled && shift!=0 && !muting → period = target");
    }

    @Test
    void halfFrame_dividerPeriod_isPPlusOneHalfFrames() {
        SweepUnit s = pulse1Sweep();
        s.write(0x91); // enabled, P=1, add, shift=1
        int period = 0x100;
        period = s.clockHalfFrame(period); // divider was 0 → update, divider = 1
        assertEquals(0x180, period);
        period = s.clockHalfFrame(period); // divider 1 → 0, no update
        assertEquals(0x180, period, "no update while the divider counts down");
        period = s.clockHalfFrame(period); // divider 0 → update again
        assertEquals(0x180 + (0x180 >> 1), period,
                "updates land every P+1 half-frames");
    }

    @Test
    void halfFrame_disabled_neverUpdatesPeriod_butDividerStillCounts() {
        SweepUnit s = pulse1Sweep();
        s.write(0x11); // disabled, P=1, shift=1
        int period = 0x100;
        for (int i = 0; i < 6; i++) {
            period = s.clockHalfFrame(period);
        }
        assertEquals(0x100, period, "a disabled sweep never adjusts the period");
    }

    @Test
    void halfFrame_shiftZero_neverUpdatesPeriod() {
        SweepUnit s = pulse1Sweep();
        s.write(0x80); // enabled, P=0, shift=0
        int period = 0x100;
        for (int i = 0; i < 4; i++) {
            period = s.clockHalfFrame(period);
        }
        assertEquals(0x100, period, "shift 0 blocks the period update");
    }

    @Test
    void halfFrame_muting_blocksUpdate_butDividerStillRuns() {
        SweepUnit s = pulse2Sweep();
        s.write(0x81); // enabled, P=0, add, shift=1
        // period 0x600 → target 0x900 > $7FF → muted, update blocked
        assertEquals(0x600, s.clockHalfFrame(0x600),
                "a muting target blocks the period update");
        assertEquals(0x600, s.clockHalfFrame(0x600), "still blocked");
    }

    @Test
    void write_setsReloadFlag_andReloadRestartsDividerWithoutUpdate() {
        SweepUnit s = pulse1Sweep();
        s.write(0xA1); // enabled, P=2, add, shift=1
        assertTrue(s.isReload(), "$4001/$4005 write sets the reload flag");
        int period = s.clockHalfFrame(0x100); // divider 0 → update; reload consumed
        assertEquals(0x180, period);
        assertFalse(s.isReload(), "reload flag cleared by the half-frame clock");
        period = s.clockHalfFrame(period); // divider 2 → 1
        s.write(0xA1); // re-write mid-count: reload flag set again
        period = s.clockHalfFrame(period);
        assertEquals(0x180, period,
                "reload with divider!=0 restarts the divider WITHOUT an update");
        assertEquals(2, s.divider(), "divider back at P after the reload");
        assertFalse(s.isReload());
    }

    @Test
    void halfFrame_negateUpdate_shrinksPeriodPerComplement() {
        SweepUnit p1 = pulse1Sweep();
        SweepUnit p2 = pulse2Sweep();
        p1.write(0x89); // enabled, P=0, negate, shift=1
        p2.write(0x89);
        assertEquals(0x100 - 0x80 - 1, p1.clockHalfFrame(0x100));
        assertEquals(0x100 - 0x80, p2.clockHalfFrame(0x100));
    }

    @Test
    void write_decodesAllFields() {
        SweepUnit s = pulse1Sweep();
        s.write(0xDB); // 1101 1011: enabled, P=5, negate, shift=3
        assertTrue(s.isEnabled());
        assertEquals(5, s.dividerPeriod());
        assertTrue(s.isNegate());
        assertEquals(3, s.shift());
    }
}
