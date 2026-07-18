package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase A1 — CHR-RAM write support on the cartridge. Verifies the
 * symmetry between writing through the mapper and reading back via
 * {@link Cartridge#chrRead(int)} for Mapper000 CHR-RAM carts, and
 * the no-op semantics for CHR-ROM carts.
 */
class CartridgeChrWriteTest {

    private static Cartridge buildChrRamCart() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(
                0, /*prgKB=*/16, /*chrKB=*/0, null, null);
        return new Cartridge(new ByteArrayInputStream(rom), "chr-ram-test.nes");
    }

    private static Cartridge buildChrRomCart(byte[] chrSeed) {
        byte[] rom = MapperTestSupport.buildSyntheticROM(
                0, /*prgKB=*/16, /*chrKB=*/8, null, chrSeed);
        return new Cartridge(new ByteArrayInputStream(rom), "chr-rom-test.nes");
    }

    @Test
    void chrRam_writeThenRead_returnsWrittenByte() {
        Cartridge cart = buildChrRamCart();
        cart.chrWrite(0x0500, (byte) 0x42);
        assertEquals(0x42, cart.chrRead(0x0500));
    }

    @Test
    void chrRam_writeMultipleAddresses_allRoundTrip() {
        Cartridge cart = buildChrRamCart();
        cart.chrWrite(0x0000, (byte) 0x11);
        cart.chrWrite(0x0FFF, (byte) 0x22);
        cart.chrWrite(0x1FFF, (byte) 0x33);
        assertEquals(0x11, cart.chrRead(0x0000));
        assertEquals(0x22, cart.chrRead(0x0FFF));
        assertEquals(0x33, cart.chrRead(0x1FFF));
    }

    @Test
    void chrRom_writeIsNoOp_originalByteRemains() {
        // Seed CHR with 0xAB across the whole bank.
        Cartridge cart = buildChrRomCart(new byte[] { (byte) 0xAB });
        cart.chrWrite(0x0500, (byte) 0xFF);
        assertEquals(0xAB, cart.chrRead(0x0500), "CHR-ROM must ignore writes");
    }

    @Test
    void chrRom_writeAcrossEntireRange_isNoOp() {
        Cartridge cart = buildChrRomCart(new byte[] { 0x42 });
        for (int addr = 0; addr <= 0x1FFF; addr += 0x100) {
            cart.chrWrite(addr, (byte) 0xFF);
        }
        for (int addr = 0; addr <= 0x1FFF; addr += 0x100) {
            assertEquals(0x42, cart.chrRead(addr));
        }
    }

    @Test
    void chrWrite_outsidePatternTableRange_doesNotCorruptCHR() {
        Cartridge cart = buildChrRamCart();
        cart.chrWrite(0x0100, (byte) 0x77);
        // Write past the pattern table window — must NOT touch vCHRMemory.
        cart.chrWrite(0x2000, (byte) 0xFF);
        cart.chrWrite(0x3000, (byte) 0xFF);
        assertEquals(0x77, cart.chrRead(0x0100), "Earlier CHR-RAM byte must survive");
    }

    @Test
    void chrWrite_invalidNegativeAddress_doesNotThrow() {
        Cartridge cart = buildChrRamCart();
        // Defensive guard; mapper returns UNMAPPED for negative addresses.
        assertDoesNotThrow(() -> cart.chrWrite(-1, (byte) 0xFF));
    }
}
