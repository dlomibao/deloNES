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

    /**
     * C6 — Synthetic E2E. Drive the MMC1 5-write serial protocol via
     * {@link Cartridge#cpuBusWrite(int, byte)} to commit PRG bank = 2,
     * then assert that {@code cpuBusRead($8000)} returns the bank-2
     * marker byte. Exercises the whole stack:
     *
     * <ul>
     *   <li>Cartridge → mapper.cpuMapWrite(address, value) routing</li>
     *   <li>MMC1 serial shifter accumulating 5 LSBs</li>
     *   <li>Address-based destination decoding ($E000 → PRG bank reg)</li>
     *   <li>PRG mode 3 low-window translation through vPRGMemory</li>
     * </ul>
     *
     * <p>This is the gate the plan calls out as "write the MMC1
     * register sequence to switch PRG banks, verify cartridge.cpuBusRead
     * returns the right byte after the switch."
     */
    @Test
    void cpuBusWrite_serialProtocol_commitsPrgBankSwitch_endToEnd() {
        Cartridge cart = buildCart();
        // Default low window is bank 0 marker.
        assertEquals(0xB0, cart.cpuBusRead(0x8000));

        // Commit PRG bank = 0x02 via 5 writes to $E000, bit 0 each:
        //   value 0x02 = 0b00010  →  LSB sequence: 0, 1, 0, 0, 0
        cart.cpuBusWrite(0xE000, (byte) 0x00);
        cart.cpuBusWrite(0xE000, (byte) 0x01);
        cart.cpuBusWrite(0xE000, (byte) 0x00);
        cart.cpuBusWrite(0xE000, (byte) 0x00);
        cart.cpuBusWrite(0xE000, (byte) 0x00);

        // After the 5th write commits, PRG bank = 2. In PRG mode 3
        // (default) the low window now serves bank 2 → marker 0xB2.
        assertEquals(0xB2, cart.cpuBusRead(0x8000),
                "after committing PRG bank=2, $8000 should serve bank 2's marker");
        // The high window stays on the last bank regardless.
        assertEquals(0xB3, cart.cpuBusRead(0xC000),
                "high window still on last bank after PRG bank switch");
        // Verify last byte of bank 2 (the 0xC2 marker we stamped at 0x3FFF
        // inside bank 2) shows up at $BFFF.
        assertEquals(0xC2, cart.cpuBusRead(0xBFFF),
                "bank 2 top-of-window marker mismatched");
    }
}
