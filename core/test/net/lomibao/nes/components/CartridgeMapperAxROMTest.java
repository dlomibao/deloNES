package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.Mapper;
import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link Cartridge} + {@link
 * net.lomibao.nes.rom.mapper.MapperAxROM} (iNES mapper 7).
 *
 * <p>Builds a synthetic 128KB AxROM image (4 x 32KB banks) via
 * {@link MapperTestSupport#buildSyntheticROM}, with a per-bank
 * signature byte at the start of each 32KB bank so a single
 * cpuBusRead($8000) is enough to identify the active bank.
 *
 * <p>Drives bank switches through the public CPU bus interface
 * ({@code cartridge.cpuBusWrite}) — same path the live emulator uses
 * — and asserts that the mapper register update is observable both
 * via {@code cpuBusRead} (PRG window) and {@code getMirrorMode()}
 * (nametable mirroring).
 */
class CartridgeMapperAxROMTest {

    /**
     * Builds a 128KB AxROM ROM where the byte at PRG-offset
     * {@code bank * 0x8000} is set to {@code 0xA0 | bank}. Every other
     * byte in PRG is a marker pattern ({@code 0xEE}) so a stray read
     * (or wrong bank) is immediately visible.
     *
     * <p>iNES header reports PRG = 128KB (8 x 16KB banks → 4 x 32KB
     * banks for AxROM), CHR = 0 (CHR-RAM, the AxROM default).
     */
    private static byte[] buildAxROMImage() {
        int prgKB = 128;                       // 8 x 16KB = 128KB total
        int prgBytes = prgKB * 1024;
        // seed: 0xEE everywhere
        byte[] seed = new byte[prgBytes];
        for (int i = 0; i < prgBytes; i++) {
            seed[i] = (byte) 0xEE;
        }
        // Stamp a per-bank signature at the start of each 32KB bank.
        for (int bank = 0; bank < 4; bank++) {
            seed[bank * 0x8000] = (byte) (0xA0 | bank);
        }
        return MapperTestSupport.buildSyntheticROM(7, prgKB, 0, seed, null);
    }

    private static Cartridge buildCartridge() {
        byte[] rom = buildAxROMImage();
        return new Cartridge(new ByteArrayInputStream(rom), "synthetic-axrom.nes");
    }

    @Test
    void axrom_powerOn_readsBank0Signature() {
        Cartridge cart = buildCartridge();
        assertEquals(0xA0, cart.cpuBusRead(0x8000),
                "power-on bank is 0, so $8000 should read bank 0's signature");
    }

    @Test
    void axrom_writeBank1_swapsToBank1() {
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x01);
        assertEquals(0xA1, cart.cpuBusRead(0x8000),
                "after writing 0x01, $8000 should read bank 1's signature");
    }

    @Test
    void axrom_writeBank2_swapsToBank2() {
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0xC000, (byte) 0x02);  // register write can land anywhere $8000-$FFFF
        assertEquals(0xA2, cart.cpuBusRead(0x8000));
    }

    @Test
    void axrom_writeBank3_swapsToBank3() {
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0xFFFF, (byte) 0x03);  // any address in the PRG window selects the register
        assertEquals(0xA3, cart.cpuBusRead(0x8000));
    }

    @Test
    void axrom_powerOn_mirrorIsONESCREEN_LO() {
        Cartridge cart = buildCartridge();
        assertEquals(Mapper.Mirror.ONESCREEN_LO, cart.getMirrorMode(),
                "AxROM defaults to ONESCREEN_LO on power-on");
    }

    @Test
    void axrom_writeBit4_switchesMirrorToONESCREEN_HI() {
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x10);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, cart.getMirrorMode());
    }

    @Test
    void axrom_clearBit4_switchesMirrorBackToONESCREEN_LO() {
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x10);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, cart.getMirrorMode());
        cart.cpuBusWrite(0x8000, (byte) 0x00);
        assertEquals(Mapper.Mirror.ONESCREEN_LO, cart.getMirrorMode());
    }

    @Test
    void axrom_bankAndMirror_independentInOneWrite() {
        // 0x13 = bank 3 + ONESCREEN_HI in one register write
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x13);
        assertEquals(0xA3, cart.cpuBusRead(0x8000));
        assertEquals(Mapper.Mirror.ONESCREEN_HI, cart.getMirrorMode());
    }

    @Test
    void axrom_cpuWrite_doesNotCorruptPRGMemory() {
        // The CPU bus write that selects bank 1 must NOT also land in
        // the PRG ROM array (AxROM is mask-ROM; PRG is read-only).
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x01);
        // After switching to bank 1, the bank-1 signature byte is
        // still 0xA1 (i.e. the write of 0x01 didn't overwrite PRG).
        assertEquals(0xA1, cart.cpuBusRead(0x8000));
        // And bank 0's signature is still 0xA0 when we switch back.
        cart.cpuBusWrite(0x8000, (byte) 0x00);
        assertEquals(0xA0, cart.cpuBusRead(0x8000));
    }
}
