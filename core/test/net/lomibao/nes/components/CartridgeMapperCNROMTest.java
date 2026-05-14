package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Mapper 3 (CNROM) wiring through {@link Cartridge}.
 *
 * <p>Builds a synthetic 32KB-PRG / 32KB-CHR iNES image whose CHR section
 * is seeded so each of the four 8KB CHR banks has a distinctive byte at
 * offset 0 (bank N's first byte is {@code 0xA0 + N}). Drives the CHR-bank
 * latch via {@link Cartridge#cpuBusWrite(int, byte)} to addresses in the
 * {@code $8000-$FFFF} window, then verifies the PPU-side
 * {@link Cartridge#chrRead(int)} reflects the switched bank.
 *
 * <p>Covers Phase B2 of {@code docs/mapper-plan.md}.
 */
class CartridgeMapperCNROMTest {

    private static final int PRG_KB = 32;
    private static final int CHR_KB = 32;
    private static final int CHR_BYTES = CHR_KB * 1024;
    private static final int BANK_BYTES = 8 * 1024;

    /**
     * Build a 32-KB CHR seed where the first byte of each 8-KB bank is a
     * distinctive marker ({@code 0xA0 + bankIndex}). Every other byte is
     * zero so the markers are unambiguous when we read $0000 after a
     * bank switch.
     */
    private static byte[] buildChrSeed() {
        byte[] seed = new byte[CHR_BYTES];
        for (int bank = 0; bank < 4; bank++) {
            seed[bank * BANK_BYTES] = (byte) (0xA0 + bank);
            // Also stamp the bank index at offset 0x1FFF inside each bank
            // so we can sanity-check the top of the window too.
            seed[bank * BANK_BYTES + 0x1FFF] = (byte) (0xB0 + bank);
        }
        return seed;
    }

    private static Cartridge buildCNROMCart() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(
                /*mapperId=*/3, PRG_KB, CHR_KB,
                /*prgSeed=*/null,
                buildChrSeed());
        return new Cartridge(new ByteArrayInputStream(rom), "cnrom-test.nes");
    }

    @Test
    void cartridge_buildsMapperCNROM_andDefaultsToBank0() {
        Cartridge cart = buildCNROMCart();
        // Default bank is 0 → first byte of CHR bank 0 = 0xA0.
        assertEquals(0xA0, cart.chrRead(0x0000),
                "before any bank switch, PPU reads bank 0");
        assertEquals(0xB0, cart.chrRead(0x1FFF),
                "top of bank 0 should be 0xB0");
    }

    @Test
    void cartridge_switchToBank2_via8000_propagatesToPpuSide() {
        Cartridge cart = buildCNROMCart();
        cart.cpuBusWrite(0x8000, (byte) 0x02);
        assertEquals(0xA2, cart.chrRead(0x0000),
                "after CHR-bank=2, $0000 should read bank-2 marker");
        assertEquals(0xB2, cart.chrRead(0x1FFF));
    }

    @Test
    void cartridge_switchAllFourBanks_inSequence() {
        Cartridge cart = buildCNROMCart();
        for (int bank = 0; bank < 4; bank++) {
            // Latch lives anywhere in $8000-$FFFF; vary the address each
            // iteration to prove the gate is "address range" not "exact $8000".
            int latchAddr = 0x8000 + (bank * 0x2000);
            cart.cpuBusWrite(latchAddr, (byte) bank);
            assertEquals(0xA0 + bank, cart.chrRead(0x0000),
                    "bank " + bank + " mismatch at $0000");
            assertEquals(0xB0 + bank, cart.chrRead(0x1FFF),
                    "bank " + bank + " mismatch at $1FFF");
        }
    }

    @Test
    void cartridge_bankRegister_masksTo2Bits() {
        // 0xFF → bank 3; 0x06 → bank 2.
        Cartridge cart = buildCNROMCart();
        cart.cpuBusWrite(0x8000, (byte) 0xFF);
        assertEquals(0xA3, cart.chrRead(0x0000), "0xFF must mask to bank 3");
        cart.cpuBusWrite(0xABCD, (byte) 0x06);
        assertEquals(0xA2, cart.chrRead(0x0000), "0x06 must mask to bank 2");
    }

    @Test
    void cartridge_bankSwitch_doesNotCorruptPrg() {
        Cartridge cart = buildCNROMCart();
        // 32KB-PRG, seed is null ⇒ all-zero PRG. cpuBusWrite into the PRG
        // window must not land in vPRGMemory on CNROM.
        cart.cpuBusWrite(0x8000, (byte) 0xFF);
        cart.cpuBusWrite(0xFFFF, (byte) 0xFF);
        assertEquals(0, cart.cpuBusRead(0x8000),
                "CNROM PRG is ROM — writes must not stick");
        assertEquals(0, cart.cpuBusRead(0xFFFF));
    }
}
