package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A0 — tests of {@link BlarggRomRunner} itself, against synthetic
 * ROMs that walk the $6000 protocol (docs/apu-plan.md, runner spec):
 * magic gating, timeout, and the $81 press-reset handshake.
 */
class BlarggRomRunnerTest {

    // =====================================================================
    // Synthetic protocol ROMs (hand-assembled 6502, NROM-128 via
    // NesHarnessTestRoms.nromWithProgram; program origin $8000)
    // =====================================================================

    /**
     * Writes {@code status} to $6000 BEFORE the magic appears (bait for a
     * gating-ignorant runner), burns ~11 frames in a delay loop, writes
     * the message "OK" to $6004, then the magic, then {@code status}
     * again as the real final result.
     */
    private static NesHarness statusAfterMagicHarness(int status) {
        int[] prog = {
                0xA9, status,           // 8000 LDA #status
                0x8D, 0x00, 0x60,       // 8002 STA $6000  (pre-magic bait)
                0xA2, 0x00,             // 8005 LDX #$00
                0xA0, 0x00,             // 8007 LDY #$00   (outer)
                0x88,                   // 8009 DEY        (inner)
                0xD0, 0xFD,             // 800A BNE $8009
                0xCA,                   // 800C DEX
                0xD0, 0xF8,             // 800D BNE $8007  (~330k cycles ≈ 11 frames)
                0xA9, 'O',              // 800F LDA #'O'
                0x8D, 0x04, 0x60,       // 8011 STA $6004
                0xA9, 'K',              // 8014 LDA #'K'
                0x8D, 0x05, 0x60,       // 8016 STA $6005
                0xA9, 0x00,             // 8019 LDA #$00
                0x8D, 0x06, 0x60,       // 801B STA $6006  (terminator)
                0xA9, 0xDE,             // 801E LDA #$DE
                0x8D, 0x01, 0x60,       // 8020 STA $6001
                0xA9, 0xB0,             // 8023 LDA #$B0
                0x8D, 0x02, 0x60,       // 8025 STA $6002
                0xA9, 0x61,             // 8028 LDA #$61
                0x8D, 0x03, 0x60,       // 802A STA $6003  (protocol now valid)
                0xA9, status,           // 802D LDA #status
                0x8D, 0x00, 0x60,       // 802F STA $6000  (real final result)
                0x4C, 0x32, 0x80,       // 8032 JMP $8032
        };
        return harness(prog, "status-after-magic.nes");
    }

    /** Writes the magic and a permanent $80 "running" status, then spins. */
    private static NesHarness foreverRunningHarness() {
        int[] prog = {
                0xA9, 0xDE, 0x8D, 0x01, 0x60,   // magic
                0xA9, 0xB0, 0x8D, 0x02, 0x60,
                0xA9, 0x61, 0x8D, 0x03, 0x60,
                0xA9, 0x80, 0x8D, 0x00, 0x60,   // status $80 = running
                0x4C, 0x14, 0x80,               // 8014 JMP $8014
        };
        return harness(prog, "forever-running.nes");
    }

    /** Never writes the magic (or anything) — just spins. */
    private static NesHarness noMagicHarness() {
        return harness(new int[] {0x4C, 0x00, 0x80}, "no-magic.nes");
    }

    /**
     * First boot: writes magic + status $81 (press reset) and marks $6100
     * (cartridge PRG-RAM survives {@code reset()}). After the reset the
     * mark routes execution to the pass path: message "R", status 0. Only
     * a runner that actually performs the handshake can see code 0.
     */
    private static NesHarness resetHandshakeHarness() {
        int[] prog = {
                0xAD, 0x00, 0x61,       // 8000 LDA $6100
                0xC9, 0xA5,             // 8003 CMP #$A5
                0xF0, 0x1C,             // 8005 BEQ $8023  (after-reset path)
                0xA9, 0xA5,             // 8007 LDA #$A5
                0x8D, 0x00, 0x61,       // 8009 STA $6100  (mark first boot)
                0xA9, 0xDE,             // 800C
                0x8D, 0x01, 0x60,       // 800E STA $6001
                0xA9, 0xB0,             // 8011
                0x8D, 0x02, 0x60,       // 8013 STA $6002
                0xA9, 0x61,             // 8016
                0x8D, 0x03, 0x60,       // 8018 STA $6003
                0xA9, 0x81,             // 801B LDA #$81
                0x8D, 0x00, 0x60,       // 801D STA $6000  (press reset)
                0x4C, 0x20, 0x80,       // 8020 JMP $8020
                0xA9, 'R',              // 8023 LDA #'R'   (after-reset path)
                0x8D, 0x04, 0x60,       // 8025 STA $6004
                0xA9, 0x00,             // 8028 LDA #$00
                0x8D, 0x05, 0x60,       // 802A STA $6005  (terminator)
                0x8D, 0x00, 0x60,       // 802D STA $6000  (status 0 = pass)
                0x4C, 0x30, 0x80,       // 8030 JMP $8030
        };
        return harness(prog, "reset-handshake.nes");
    }

    private static NesHarness harness(int[] prog, String name) {
        return NesHarness.fromBytes(NesHarnessTestRoms.nromWithProgram(prog), name);
    }

    // =====================================================================
    // Tests
    // =====================================================================

    @Test
    void resultCode_andMessage_readAfterFinalStatus() {
        BlarggResult r = BlarggRomRunner.run(statusAfterMagicHarness(0x00), 60);
        assertEquals(0, r.code());
        assertEquals("OK", r.message());
    }

    @Test
    void failureCode_isReturnedVerbatim() {
        BlarggResult r = BlarggRomRunner.run(statusAfterMagicHarness(0x03), 60);
        assertEquals(3, r.code());
        assertEquals("OK", r.message());
    }

    @Test
    void magicGating_ignoresStatusUntilMagicAppears() {
        // The ROM writes a "final-looking" $00 to $6000 many frames before
        // the magic. A gating-ignorant runner would return immediately with
        // an empty message; the message is only in place once the magic is.
        BlarggResult r = BlarggRomRunner.run(statusAfterMagicHarness(0x00), 60);
        assertEquals("OK", r.message(),
                "runner must not trust $6000 before the $DE $B0 $61 magic");
    }

    @Test
    void timeout_whileRunning_reportsLastStatus() {
        AssertionError e = assertThrows(AssertionError.class,
                () -> BlarggRomRunner.run(foreverRunningHarness(), 20));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(e.getMessage().contains("last status=$80"), e.getMessage());
    }

    @Test
    void timeout_withoutMagic_saysMagicNeverAppeared() {
        AssertionError e = assertThrows(AssertionError.class,
                () -> BlarggRomRunner.run(noMagicHarness(), 10));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(e.getMessage().contains("magic"), e.getMessage());
    }

    @Test
    void pressResetHandshake_resetsAndCompletes() {
        BlarggResult r = BlarggRomRunner.run(resetHandshakeHarness(), 120);
        assertEquals(0, r.code(),
                "code 0 is only reachable via the post-reset path — the"
                + " runner must have performed the $81 reset handshake");
        assertEquals("R", r.message());
    }
}
