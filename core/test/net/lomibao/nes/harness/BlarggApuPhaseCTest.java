package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase C blargg gate set (docs/apu-plan.md Phase C): cycle-exact frame
 * counter + IRQ delivery. One test method per ROM, driven through
 * {@link BlarggRomRunner}. ROMs are committed under
 * {@code test-roms/blargg/} (D7) — CI gates on them unconditionally.
 *
 * <p>The C0 spike ran exactly these ROMs against the Phase B APU and
 * recorded the failure deltas in the plan doc (the C0 findings note) —
 * they calibrate the C2 access-cycle compensation.
 */
class BlarggApuPhaseCTest {

    private static void assertPasses(String rom) {
        BlarggResult r = BlarggRomRunner.run(rom);
        assertEquals(0, r.code(), rom + " → " + r.message());
    }

    // ---- apu_test/rom_singles (blargg 2011) — ±1-cycle $4015 timing ----

    @Test
    void apuTest_4_jitter() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/4-jitter.nes");
    }

    @Test
    void apuTest_5_len_timing() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/5-len_timing.nes");
    }

    @Test
    void apuTest_6_irq_flag_timing() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/6-irq_flag_timing.nes");
    }

    // ---- apu_reset (frame-counter boot offset + $4017 retention) ----

    @Test
    void apuReset_4017_timing() {
        assertPasses("test-roms/blargg/apu_reset/4017_timing.nes");
    }

    @Test
    void apuReset_4017_written() {
        assertPasses("test-roms/blargg/apu_reset/4017_written.nes");
    }
}
