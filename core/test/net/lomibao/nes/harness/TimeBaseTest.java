package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase A1 — D3: {@code frames = Math.round(seconds × 60.0988)}.
 */
class TimeBaseTest {

    @Test
    void zeroSecondsIsFrameZero() {
        assertEquals(0, TimeBase.framesAt(0.0));
    }

    @Test
    void oneSecondRoundsToSixty() {
        // 1.0 × 60.0988 = 60.0988 → 60
        assertEquals(60, TimeBase.framesAt(1.0));
    }

    @Test
    void tenSecondsIsNot600() {
        // 10 × 60.0988 = 600.988 → 601: the NTSC rate is NOT 60.0 fps.
        assertEquals(601, TimeBase.framesAt(10.0));
    }

    @Test
    void apiSketchExample() {
        // 31.6 × 60.0988 = 1899.122… → 1899 (the .atSeconds(31.6) sketch).
        assertEquals(1899, TimeBase.framesAt(31.6));
    }

    @Test
    void halfSecondRoundsDown() {
        // 0.5 × 60.0988 = 30.0494 → 30 (not the naive 60fps "30 exactly",
        // but close — the boundary shows Math.round, not floor/ceil).
        assertEquals(30, TimeBase.framesAt(0.5));
    }

    @Test
    void roundHalfUpBoundary() {
        // 5.5 × 60.0988 = 330.5434 → 331. Pins rounding (any half-mode)
        // versus truncation, which would give 330; the exact half-up tie
        // behavior of Math.round is not distinguishable at this value.
        assertEquals(331, TimeBase.framesAt(5.5));
    }

    @Test
    void negativeSecondsThrows() {
        assertThrows(IllegalArgumentException.class, () -> TimeBase.framesAt(-0.1));
    }
}
