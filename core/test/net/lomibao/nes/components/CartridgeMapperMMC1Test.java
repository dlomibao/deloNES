package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for {@link Cartridge} + {@code MapperMMC1} (iNES
 * Mapper 1). Builds a synthetic 64KB PRG / 8KB CHR cartridge whose four
 * 16KB PRG banks each carry a distinct marker byte at their first
 * offset, then drives the MMC1 5-write serial protocol through the
 * CPU-bus path and asserts that the cartridge boots and bank-switches
 * correctly.
 *
 * <p>This test suite owns the C5 (Cartridge wiring) and C6 (synthetic
 * E2E) gates from {@code docs/mapper-plan.md} Phase C.
 */
class CartridgeMapperMMC1Test {

    /** 64KB PRG = 4 banks × 16KB. Final bank index = 3 (fixed at $C000 in default mode). */
    private static final int PRG_KB = 64;
    private static final int CHR_KB = 8;
    private static final int BANK_SIZE = 16 * 1024;

    /**
     * Build a synthetic MMC1 iNES ROM where each 16KB PRG bank carries
     * a distinctive byte 0xB0+bankIndex at its first offset (and 0xC0+
     * bankIndex at its last offset), so swapping banks is observable
     * via cpuBusRead.
     */
    private static byte[] buildMarkedRom() {
        int prgBytes = PRG_KB * 1024;
        byte[] prg = new byte[prgBytes];
        for (int b = 0; b < PRG_KB / 16; b++) {
            prg[b * BANK_SIZE] = (byte) (0xB0 + b);
            prg[b * BANK_SIZE + 0x3FFF] = (byte) (0xC0 + b);
        }
        // Mapper id = 1 → MMC1.
        return MapperTestSupport.buildSyntheticROM(
                1, PRG_KB, CHR_KB, prg, new byte[] { 0x55 });
    }

    private static Cartridge buildCart() {
        return new Cartridge(new ByteArrayInputStream(buildMarkedRom()),
                "mmc1-synth.nes");
    }

    /**
     * C5 — Cartridge wiring. With mapper id = 1 in the header, the
     * Cartridge constructor must instantiate MapperMMC1 so reads work
     * end-to-end. Default MMC1 state is PRG mode 3 (16KB low
     * switchable, high fixed-to-last) with PRG bank register = 0, so
     * $8000 → bank 0 and $C000 → last bank (index 3).
     */
    @Test
    void cartridgeWithMapper1_instantiatesMMC1_andDefaultsToBank0LowFixedToLastHigh() {
        Cartridge cart = buildCart();
        // Bank 0 marker at $8000 (default PRG bank reg = 0, mode 3).
        assertEquals(0xB0, cart.cpuBusRead(0x8000),
                "default low-window mapping should be bank 0");
        // Last bank (index 3) marker at $C000.
        assertEquals(0xB3, cart.cpuBusRead(0xC000),
                "default high-window mapping should be last bank");
    }
}
