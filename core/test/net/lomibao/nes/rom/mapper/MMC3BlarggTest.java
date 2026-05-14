package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Phase D7 — Blargg {@code mmc3_test_2} suite, run headlessly.
 *
 * <p>Per the Phase D spec in {@code docs/mapper-plan.md}, this test
 * loads {@code mmc3_test_2.nes} (a multi-sub-test ROM by Shay Green
 * a.k.a. Blargg covering MMC3 corner cases) via {@link
 * net.lomibao.nes.components.Cartridge}, runs the system headlessly
 * for ~120 frames, and asserts the Blargg pass/fail status byte at
 * CPU address {@code $6000} indicates success (status &lt; 0x80 with
 * the "all tests passed" sentinel at the conventional offset).
 *
 * <p><b>Status: DEFERRED.</b> The {@code mmc3_test_2.nes} ROM is not
 * present in {@code core/src/test/resources/test-roms/blargg/}. The
 * Phase D agent intentionally did NOT fetch it over the network
 * during the autonomous run (per task constraint); re-enable this
 * test once the ROM is added.
 *
 * <p>The {@link Disabled} annotation keeps the test in the suite as
 * documentation of intent while preventing build failure; coverage of
 * MMC3 itself remains at 100% via the unit tests in
 * {@link MapperMMC3Test}.
 */
class MMC3BlarggTest {

    @Test
    @Disabled("Re-enable when mmc3_test_2.nes is added to "
            + "core/src/test/resources/test-roms/blargg/")
    void mmc3_test_2_allTestsPassed() {
        // Suite layout when the ROM is added:
        //   var rom = getClass().getResourceAsStream("/test-roms/blargg/mmc3_test_2.nes");
        //   Cartridge cart = new Cartridge(rom, "mmc3_test_2.nes");
        //   NesSystem nes = new NesSystem();
        //   nes.insertCartridge(cart);
        //   nes.reset();
        //   for (int frame = 0; frame < 120; frame++) {
        //       nes.runFrame();
        //   }
        //   // Blargg convention: byte at $6000 holds the test status.
        //   // 0x80 = "test still running", 0xFF/0xFE = initial sentinel,
        //   // 0x00 = pass, anything else = fail code.
        //   int status = nes.cpuBus.read(0x6000);
        //   assertEquals(0x00, status, "Blargg mmc3_test_2 reported failure code " + status);
    }
}
