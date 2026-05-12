package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the standard NES controller (Step 3 of the playable-gen1 plan).
 *
 * <p>Replaces the old open-bus {@code Controller} stub with a real
 * shift-register implementation modelled on bugzmanov ch. 8. Contract:
 *
 * <ul>
 *   <li><strong>Write to $4016</strong>: bit 0 is the strobe latch.
 *       Setting strobe high resets the read index to 0; setting strobe
 *       low does NOT reset the index (only the rising edge of strobe
 *       resets).</li>
 *   <li><strong>Read from $4016 with strobe high</strong>: every read
 *       returns bit 0 of the live button state (button A) — the index
 *       never advances.</li>
 *   <li><strong>Read from $4016 with strobe low</strong>: returns the
 *       current bit indexed by the read counter, then advances the counter.
 *       After 8 reads (button RIGHT), every subsequent read returns
 *       {@code 1} until the next strobe.</li>
 *   <li><strong>Button shift order</strong>: A, B, Select, Start, Up,
 *       Down, Left, Right. (NESdev wiki standard layout.)</li>
 *   <li><strong>$4017 (controller 2)</strong>: full P2 read support — the
 *       same shift-register protocol as $4016, indexed against player 1's
 *       live state. Writes to $4017 belong to the APU frame counter and
 *       are no-ops on this component.</li>
 *   <li><strong>Shared strobe</strong>: there is a single strobe line on
 *       real hardware; the 1→0 falling edge on $4016 latches both
 *       controllers' live state and resets both read indices.</li>
 * </ul>
 */
class StandardControllerTest {

    private Controller newController() {
        return new Controller();
    }

    /** Strobe-pulse helper: write 1 then 0 to $4016, the standard "latch + start shifting" handshake. */
    private void strobeAndLatch(Controller c) {
        c.cpuBusWrite(0x4016, (byte) 0x01);
        c.cpuBusWrite(0x4016, (byte) 0x00);
    }

    // ---- button bit constants (test-side mirror of the public API) ----

    @Test
    void buttonMaskConstants_followNesdevStandardOrder() {
        // The PUBLIC API constants must match NESdev wiki order so host
        // input-mapping code reads naturally.
        assertEquals(0x01, Controller.A);
        assertEquals(0x02, Controller.B);
        assertEquals(0x04, Controller.SELECT);
        assertEquals(0x08, Controller.START);
        assertEquals(0x10, Controller.UP);
        assertEquals(0x20, Controller.DOWN);
        assertEquals(0x40, Controller.LEFT);
        assertEquals(0x80, Controller.RIGHT);
    }

    // ---- strobe semantics ----

    @Test
    void write_strobeBit0Set_resetsReadIndex() {
        Controller c = newController();
        c.setButton(Controller.A, false);
        c.setButton(Controller.B, true); // press B so non-A reads return 1
        // Strobe low, read 3 times — index advances to 3
        c.cpuBusWrite(0x4016, (byte) 0x00);
        c.cpuBusRead(0x4016, false);
        c.cpuBusRead(0x4016, false);
        c.cpuBusRead(0x4016, false);
        // Now strobe high — should reset index to 0 → next read returns A bit (0)
        c.cpuBusWrite(0x4016, (byte) 0x01);
        c.cpuBusWrite(0x4016, (byte) 0x00);
        assertEquals(0, c.cpuBusRead(0x4016, false) & 1, "after strobe pulse, first read should be A bit");
    }

    @Test
    void write_strobeBit0Clear_doesNotResetIndex() {
        // Setting strobe to 0 by itself (without a prior 1) should NOT reset
        // the index. Real hardware: only the strobe-high condition reloads
        // the latch.
        Controller c = newController();
        c.setButton(Controller.A, false);
        c.setButton(Controller.B, true);
        c.cpuBusWrite(0x4016, (byte) 0x00); // strobe stays low (was already 0 at construction)
        c.cpuBusRead(0x4016, false);  // A → 0
        c.cpuBusRead(0x4016, false);  // B → 1, index advances
        c.cpuBusWrite(0x4016, (byte) 0x00); // another low write — should NOT reset
        // Next read should be SELECT, which is unpressed → 0
        assertEquals(0, c.cpuBusRead(0x4016, false) & 1,
                "low→low write should not reset index; next read should be SELECT (unpressed)");
    }

    // ---- read-while-strobe-high ----

    @Test
    void read_returnsButtonA_whileStrobeHigh() {
        Controller c = newController();
        c.setButton(Controller.A, true);
        c.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        // Multiple reads should all return A bit (which is 1 — A is pressed)
        for (int i = 0; i < 5; i++) {
            assertEquals(1, c.cpuBusRead(0x4016, false) & 1,
                    "read " + i + " while strobe high should return A bit (pressed)");
        }
    }

    @Test
    void read_whileStrobeHigh_doesNotAdvanceIndex() {
        Controller c = newController();
        // Press only B — A is unpressed.
        c.setButton(Controller.B, true);
        c.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        c.cpuBusRead(0x4016, false);
        c.cpuBusRead(0x4016, false);
        c.cpuBusRead(0x4016, false);
        // Strobe low — first read should still be A (unpressed → 0).
        // If the index advanced under strobe-high, this would return B bit instead.
        c.cpuBusWrite(0x4016, (byte) 0x00);
        assertEquals(0, c.cpuBusRead(0x4016, false) & 1,
                "after dropping strobe, first read should be A — index must not have advanced");
    }

    // ---- read-while-strobe-low (the main shift-register behaviour) ----

    @Test
    void read_returnsButtonsInOrder_AThenBThenSelectThenStartThenUDLR() {
        // Press all 8 buttons. Pulse strobe. Read 8 times — every bit 1.
        Controller c = newController();
        c.setButton(Controller.A | Controller.B | Controller.SELECT | Controller.START
                  | Controller.UP | Controller.DOWN | Controller.LEFT | Controller.RIGHT, true);
        strobeAndLatch(c);
        for (int i = 0; i < 8; i++) {
            assertEquals(1, c.cpuBusRead(0x4016, false) & 1,
                    "all-buttons-pressed: read " + i + " should be 1");
        }
    }

    @Test
    void read_returnsExactBitForEachButton_whenOnlySomePressed() {
        // Press B and START only. Expected sequence: 0,1,0,1,0,0,0,0
        Controller c = newController();
        c.setButton(Controller.B, true);
        c.setButton(Controller.START, true);
        strobeAndLatch(c);
        int[] expected = {0, 1, 0, 1, 0, 0, 0, 0};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], c.cpuBusRead(0x4016, false) & 1,
                    "read " + i + " (button " + i + ") mismatch");
        }
    }

    @Test
    void read_returnsOneAfterEighthRead_untilNextStrobe() {
        // After all 8 buttons have been shifted out, every subsequent read
        // returns 1 — this is the bugzmanov ch. 8 detail olcNES gets wrong.
        // Some games depend on it.
        Controller c = newController();
        // No buttons pressed.
        strobeAndLatch(c);
        // Drain 8 reads → all 0.
        for (int i = 0; i < 8; i++) {
            assertEquals(0, c.cpuBusRead(0x4016, false) & 1, "read " + i + " should be 0");
        }
        // Now reads 9-16 should all be 1.
        for (int i = 8; i < 16; i++) {
            assertEquals(1, c.cpuBusRead(0x4016, false) & 1,
                    "read " + i + " (post-shift) should always be 1");
        }
    }

    @Test
    void read_returnsOneAfterEighthRead_resetsAfterNextStrobe() {
        Controller c = newController();
        c.setButton(Controller.A, true);
        strobeAndLatch(c);
        // Drain 10 reads — last 2 are post-shift 1s.
        for (int i = 0; i < 10; i++) {
            c.cpuBusRead(0x4016, false);
        }
        // Re-strobe — next read should be A (1 = pressed), not 1-after-eighth.
        strobeAndLatch(c);
        assertEquals(1, c.cpuBusRead(0x4016, false) & 1,
                "after fresh strobe, first read should be A bit again");
    }

    // ---- setButton ----

    @Test
    void setButton_pressedTrue_setsBitInLiveState() {
        Controller c = newController();
        c.setButton(Controller.SELECT, true);
        c.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        // Read 3 times to advance past index 0,1 conceptually — but strobe
        // is high so all return A. Drop strobe and read 3 times.
        c.cpuBusWrite(0x4016, (byte) 0x00);
        c.cpuBusRead(0x4016, false); // A → 0
        c.cpuBusRead(0x4016, false); // B → 0
        assertEquals(1, c.cpuBusRead(0x4016, false) & 1,
                "SELECT (button 2) should be pressed");
    }

    @Test
    void setButton_pressedFalse_clearsBitInLiveState() {
        Controller c = newController();
        c.setButton(Controller.UP, true);
        c.setButton(Controller.UP, false);
        strobeAndLatch(c);
        for (int i = 0; i < 4; i++) c.cpuBusRead(0x4016, false); // skip A,B,SEL,START
        assertEquals(0, c.cpuBusRead(0x4016, false) & 1,
                "UP should be released after setButton(UP, false)");
    }

    @Test
    void setButton_multipleBitsAtOnce_packedMaskWorks() {
        Controller c = newController();
        // Press A and START in one call using a combined mask.
        c.setButton(Controller.A | Controller.START, true);
        strobeAndLatch(c);
        assertEquals(1, c.cpuBusRead(0x4016, false) & 1, "A");
        assertEquals(0, c.cpuBusRead(0x4016, false) & 1, "B");
        assertEquals(0, c.cpuBusRead(0x4016, false) & 1, "SELECT");
        assertEquals(1, c.cpuBusRead(0x4016, false) & 1, "START");
    }

    // ---- $4017 (controller 2 / player 2) ----

    @Test
    void controller2_4017_returnsPlayer2ButtonsInOrder() {
        // P2 follows the same shift-register protocol as P1. Press all 8 P2
        // buttons; after strobing $4016 (the shared strobe), 8 sequential
        // reads from $4017 should yield 1,1,1,1,1,1,1,1.
        Controller c = newController();
        for (Button b : Button.values()) {
            c.setButton(1, b, true);
        }
        strobeAndLatch(c);
        for (int i = 0; i < 8; i++) {
            assertEquals(1, c.cpuBusRead(0x4017, false) & 1,
                    "P2 all-pressed: $4017 read " + i + " should be 1");
        }
    }

    @Test
    void controller2_4017_returnsExactBitForEachButton_whenOnlySomePressed() {
        // Press P2's B and START only — expected serial output 0,1,0,1,0,0,0,0.
        // This is the same pattern used for P1 in the corresponding $4016 test,
        // so a single regression here pinpoints player-indexing bugs.
        Controller c = newController();
        c.setButton(1, Button.B, true);
        c.setButton(1, Button.START, true);
        strobeAndLatch(c);
        int[] expected = {0, 1, 0, 1, 0, 0, 0, 0};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], c.cpuBusRead(0x4017, false) & 1,
                    "P2 $4017 read " + i + " (" + Button.values()[i] + ") mismatch");
        }
    }

    @Test
    void controller2_4017_returnsOneAfterEighthRead_untilNextStrobe() {
        // After P2's 8 buttons have shifted out, every subsequent $4017 read
        // returns 1 — same open-bus pull-up behaviour as P1.
        Controller c = newController();
        // No P2 buttons pressed.
        strobeAndLatch(c);
        for (int i = 0; i < 8; i++) {
            assertEquals(0, c.cpuBusRead(0x4017, false) & 1,
                    "P2 read " + i + " should be 0 (no buttons pressed)");
        }
        for (int i = 8; i < 16; i++) {
            assertEquals(1, c.cpuBusRead(0x4017, false) & 1,
                    "P2 read " + i + " (post-shift) should always be 1");
        }
    }

    @Test
    void controller2_4017_writeIsNoOp() {
        Controller c = newController();
        // Writes to $4017 belong to the APU frame counter on real hardware;
        // the controller component must ignore them rather than crash.
        assertDoesNotThrow(() -> c.cpuBusWrite(0x4017, (byte) 0xFF));
    }

    // ---- cross-talk between controllers ----

    @Test
    void crossTalk_player1ButtonsDoNotAppearOnPlayer2Reads() {
        // Press every P1 button; P2 has none pressed. After strobe, P2's
        // 8 reads on $4017 must all be 0.
        Controller c = newController();
        c.setButton(Controller.A | Controller.B | Controller.SELECT | Controller.START
                  | Controller.UP | Controller.DOWN | Controller.LEFT | Controller.RIGHT, true);
        strobeAndLatch(c);
        for (int i = 0; i < 8; i++) {
            assertEquals(0, c.cpuBusRead(0x4017, false) & 1,
                    "P1 buttons must not bleed into P2 stream at $4017 (read " + i + ")");
        }
    }

    @Test
    void crossTalk_player2ButtonsDoNotAppearOnPlayer1Reads() {
        // Press every P2 button; P1 has none pressed. P1's 8 reads on $4016
        // must all be 0.
        Controller c = newController();
        for (Button b : Button.values()) {
            c.setButton(1, b, true);
        }
        strobeAndLatch(c);
        for (int i = 0; i < 8; i++) {
            assertEquals(0, c.cpuBusRead(0x4016, false) & 1,
                    "P2 buttons must not bleed into P1 stream at $4016 (read " + i + ")");
        }
    }

    // ---- shared strobe semantics ----

    @Test
    void sharedStrobe_writingToDollar4016_resetsBothPlayersShiftRegisters() {
        // Real hardware has a single strobe line driven from $4016 bit 0.
        // The 1→0 falling edge re-latches BOTH controllers' live state and
        // resets BOTH read indices.
        Controller c = newController();
        // P1 presses A only; P2 presses B only.
        c.setButton(0, Button.A, true);
        c.setButton(1, Button.B, true);
        strobeAndLatch(c);
        // Drain 3 P1 reads and 3 P2 reads so both indices are advanced.
        for (int i = 0; i < 3; i++) {
            c.cpuBusRead(0x4016, false);
            c.cpuBusRead(0x4017, false);
        }
        // A fresh strobe pulse on $4016 must reset BOTH players' indices.
        strobeAndLatch(c);
        // P1's first post-strobe read should be the A bit (pressed → 1).
        assertEquals(1, c.cpuBusRead(0x4016, false) & 1,
                "after shared strobe, P1's first read should be A (pressed)");
        // P2's first post-strobe read should be A (unpressed → 0). If the
        // P2 index hadn't reset, this would be the 4th bit (START, also 0)
        // or 5th (UP, 0) — so to make the assertion unambiguous, also
        // check the 2nd P2 read is B (pressed → 1), which is only true if
        // we genuinely restarted at bit 0.
        assertEquals(0, c.cpuBusRead(0x4017, false) & 1,
                "after shared strobe, P2's first read should be A (unpressed)");
        assertEquals(1, c.cpuBusRead(0x4017, false) & 1,
                "after shared strobe, P2's second read should be B (pressed)");
    }

    // ---- bus-wiring sanity ----

    @Test
    void cpuBusRange_covers4016And4017() {
        Controller c = newController();
        assertEquals(0x4016, c.getCPUBusStartAddress());
        assertEquals(0x4018, c.getCPUBusEndAddress(), "exclusive end");
        assertTrue(c.inCPUBusRange(0x4016));
        assertTrue(c.inCPUBusRange(0x4017));
        assertFalse(c.inCPUBusRange(0x4015));
        assertFalse(c.inCPUBusRange(0x4018));
    }
}
