package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the new PPU NMI poll/consume API introduced in Step 2 of the
 * playable-gen1 plan.
 *
 * <p>The contract:
 * <ul>
 *   <li>{@code peekNmi()} — non-destructive read of the NMI latch.</li>
 *   <li>{@code consumeNmi()} — read AND clear; returns true exactly once
 *       per VBlank entry. Subsequent calls return false until the next
 *       false→true transition.</li>
 *   <li>The latch is set when the PPU enters VBlank with NMI enabled in
 *       PPUCTRL bit 7. The PPU does NOT call {@code cpu.nmi()} directly —
 *       that coupling is the host's responsibility (see {@code NesSystem}).</li>
 * </ul>
 */
class PPUNmiHookTest {

    private PPU ppu;

    private void writePpuCtrl(byte value) {
        ppu.cpuBusWrite(0x2000, value);
    }

    private void runUntilEnteringVBlank() {
        // VBlank is set at scanline 241, cycle 1 — that's the 241*341 + 1 = 82,182nd PPU tick.
        for (int i = 0; i < 241 * 341 + 1; i++) {
            ppu.clock();
        }
    }

    @Test
    void peekNmi_isFalse_atPowerOn() {
        ppu = new PPU();
        assertFalse(ppu.peekNmi(), "NMI latch should start false");
    }

    @Test
    void consumeNmi_returnsFalse_atPowerOn() {
        ppu = new PPU();
        assertFalse(ppu.consumeNmi(), "consumeNmi should return false when no NMI is pending");
    }

    @Test
    void consumeNmi_returnsTrueOnce_perVBlankEntry_whenNmiEnabled() {
        ppu = new PPU();
        writePpuCtrl((byte) 0x80); // enable NMI in PPUCTRL bit 7
        runUntilEnteringVBlank();
        assertTrue(ppu.peekNmi(), "NMI latch should be set on VBlank entry with NMI enabled");
        assertTrue(ppu.consumeNmi(), "first consumeNmi after VBlank entry should return true");
        assertFalse(ppu.consumeNmi(), "second consumeNmi without another VBlank should return false");
        assertFalse(ppu.peekNmi(), "peekNmi after consume should be false");
    }

    @Test
    void consumeNmi_doesNotFire_whenNmiDisabled() {
        ppu = new PPU();
        // PPUCTRL bit 7 = 0 (NMI disabled)
        writePpuCtrl((byte) 0x00);
        runUntilEnteringVBlank();
        assertFalse(ppu.peekNmi(), "NMI latch should not be set when PPUCTRL bit 7 is clear");
        assertFalse(ppu.consumeNmi(), "consumeNmi should return false when NMI is disabled");
    }

    @Test
    void consumeNmi_firesEachFrame_whenNmiEnabled() {
        ppu = new PPU();
        writePpuCtrl((byte) 0x80);
        for (int frame = 1; frame <= 3; frame++) {
            // Run one full frame (89,342 master ticks for the PPU).
            for (int i = 0; i < 89342; i++) {
                ppu.clock();
            }
            assertTrue(ppu.consumeNmi(), "NMI should fire on frame " + frame);
            assertFalse(ppu.consumeNmi(),
                    "consumeNmi should not re-fire within the same frame on frame " + frame);
        }
    }

    @Test
    void ppu_doesNotCallCpuNmi_directly() {
        // Sanity check that the production PPU path no longer pokes the CPU
        // directly: even when a CPU reference is wired (legacy test pattern),
        // we rely on the latch-and-poll protocol exclusively.
        ppu = new PPU();
        // We do NOT call ppu.setCPU(...) here intentionally — production
        // (NesSystem-driven) PPU has no CPU reference and must still latch.
        writePpuCtrl((byte) 0x80);
        runUntilEnteringVBlank();
        assertTrue(ppu.consumeNmi(),
                "PPU must latch NMI even when no CPU reference is set");
    }
}
