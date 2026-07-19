package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase D blargg gate set (docs/apu-plan.md Phase D): the functional
 * DMC ROMs, driven through {@link BlarggRomRunner}. D1's stall-free
 * DMC alone must pass both; the relocated
 * {@code apu_reset/works_immediately} gate lives in
 * {@link BlarggApuPhase1Test} (re-enabled in this phase per the Phase A
 * adjudication note).
 */
class BlarggApuPhaseDTest {

    private static void assertPasses(String rom) {
        BlarggResult r = BlarggRomRunner.run(rom);
        assertEquals(0, r.code(), rom + " → " + r.message());
    }

    @Test
    void apuTest_7_dmc_basics() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/7-dmc_basics.nes");
    }

    @Test
    void apuTest_8_dmc_rates() {
        assertPasses("test-roms/blargg/apu_test/rom_singles/8-dmc_rates.nes");
    }
}
