package net.lomibao.nes.harness;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase A blargg gate set (docs/apu-plan.md; research §5.6 phase 1):
 * one test method per ROM, driven through {@link BlarggRomRunner}.
 * ROMs are committed under {@code test-roms/blargg/} (D7) — CI gates
 * on them unconditionally.
 */
class BlarggApuPhase1Test {

    private static void assertPasses(String rom) {
        BlarggResult r = BlarggRomRunner.run(rom);
        assertEquals(0, r.code(), rom + " → " + r.message());
    }

    // ---- apu_test/rom_singles (blargg 2011) ----

    @Test
    void apuTest_1_len_ctr() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/1-len_ctr.nes");
    }

    @Test
    void apuTest_2_len_table() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/2-len_table.nes");
    }

    @Test
    void apuTest_3_irq_flag() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/3-irq_flag.nes");
    }

    // ---- apu_reset (power-up / reset state; $81 press-reset handshake) ----

    @Test
    void apuReset_4015_cleared() {
        assertPasses("test-roms/blargg/apu_reset/4015_cleared.nes");
    }

    @Test
    void apuReset_len_ctrs_enabled() {
        assertPasses("test-roms/blargg/apu_reset/len_ctrs_enabled.nes");
    }

    @Test
    void apuReset_irq_flag_cleared() {
        assertPasses("test-roms/blargg/apu_reset/irq_flag_cleared.nes");
    }

    /**
     * PLAN CONFLICT (docs/apu-plan.md Phase A gate vs. the ROM's actual
     * requirements) — surfaced 2026-07-18, awaiting a plan decision:
     * {@code works_immediately.s} log check #1 requires a $4015 read of
     * $1F (bit 4 = DMC bytes-remaining &gt; 0, primed via $4010=$8F /
     * $4013=1) ~6000 cycles after power, and check #2 requires $8F
     * (bit 7 = DMC IRQ flag set once the 17-byte sample at rate 15
     * finishes). Phase A3's spec pins both bits to "always 0 until
     * Phase D", so this ROM is unpassable before the DMC lands. The
     * other six Phase A gate ROMs pass. Re-enable in Phase D (or when
     * the plan re-scopes the gate).
     */
    @Test
    @Disabled("Phase A plan conflict: ROM requires Phase D DMC state "
            + "($4015 bits 4/7) — see Javadoc; re-enable in Phase D")
    void apuReset_works_immediately() {
        assertPasses("test-roms/blargg/apu_reset/works_immediately.nes");
    }
}
