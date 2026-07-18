package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for {@link Cartridge} + {@code MapperUxROM} (iNES
 * Mapper 2). Builds a synthetic 64KB PRG / 8KB CHR cartridge whose four
 * 16KB PRG banks each carry a distinct marker byte at their first
 * offset, drives bank-switch writes through the CPU-bus path, and
 * asserts that:
 *
 * <ul>
 *   <li>The Cartridge instantiates the right mapper for mapper id 2.</li>
 *   <li>{@code cpuBusRead($8000)} follows the bank latched by the most
 *       recent write into {@code $8000-$FFFF}.</li>
 *   <li>{@code cpuBusRead($C000)} <em>always</em> returns the last bank's
 *       first byte, regardless of register state.</li>
 *   <li>Bank-register writes do <em>not</em> corrupt PRG memory — the
 *       register is internal to the mapper, not a memory-mapped byte.</li>
 * </ul>
 */
class CartridgeMapperUxROMTest {

    /** 64KB PRG = 4 banks × 16KB. Final bank index = 3 (fixed at $C000). */
    private static final int PRG_KB = 64;
    private static final int CHR_KB = 8;
    private static final int BANK_SIZE = 16 * 1024;

    /**
     * Build a synthetic UxROM-flavored iNES ROM where each 16KB PRG
     * bank has a distinctive byte 0xB0+bankIndex at its first offset
     * (and zeros elsewhere, since we only care about the marker).
     */
    private static byte[] buildMarkedRom() {
        // Use a 16KB-bank-sized prgSeed pattern. MapperTestSupport
        // tiles the seed across PRG bytes; if the seed length equals
        // a 16KB bank, byte 0 of bank N is seed[0] — but we need
        // DIFFERENT markers per bank. So construct the full PRG bytes
        // explicitly and feed them as the seed (length = full PRG).
        int prgBytes = PRG_KB * 1024;
        byte[] prg = new byte[prgBytes];
        for (int b = 0; b < PRG_KB / 16; b++) {
            prg[b * BANK_SIZE] = (byte) (0xB0 + b);
            // Tag the LAST byte of each bank too, to verify the BANK_SIZE
            // mapping window (bank N covers offsets [N*16KB, N*16KB + 0x3FFF]).
            prg[b * BANK_SIZE + 0x3FFF] = (byte) (0xC0 + b);
        }
        return MapperTestSupport.buildSyntheticROM(
                2, PRG_KB, CHR_KB, prg, new byte[] { 0x55 });
    }

    private static Cartridge buildCart() {
        return new Cartridge(new ByteArrayInputStream(buildMarkedRom()),
                "uxrom-synth.nes");
    }

    @Test
    void cartridgeBoots_withMapper2_andDefaultsToBank0AtLowWindow() {
        Cartridge cart = buildCart();
        // After power-on the bank register is 0 → $8000 maps to bank 0.
        assertEquals(0xB0, cart.cpuBusRead(0x8000),
                "default low-window mapping should be bank 0");
    }

    @Test
    void cartridgeBoots_withMapper2_highWindowFixedAtLastBank() {
        Cartridge cart = buildCart();
        // 4 PRG banks → last bank index = 3 → marker 0xB3.
        assertEquals(0xB3, cart.cpuBusRead(0xC000),
                "high window must always serve last-bank's first byte");
    }

    @Test
    void cpuBusWrite_8000_switchesLowWindow_throughAllBanks() {
        Cartridge cart = buildCart();
        for (int b = 0; b < 4; b++) {
            cart.cpuBusWrite(0x8000, (byte) b);
            assertEquals(0xB0 + b, cart.cpuBusRead(0x8000),
                    "after latch=" + b + ", $8000 should serve bank " + b);
        }
    }

    @Test
    void cpuBusWrite_lastByteOfBank_resolvesThroughMappedOffset() {
        // Switch to bank 2; $BFFF should read the marker we stamped at
        // the very end of that 16KB bank.
        Cartridge cart = buildCart();
        cart.cpuBusWrite(0x8000, (byte) 0x02);
        assertEquals(0xC2, cart.cpuBusRead(0xBFFF),
                "bank 2 top-of-window marker mismatched");
    }

    @Test
    void cpuBusRead_C000_alwaysReturnsLastBank_regardlessOfBankSelect() {
        Cartridge cart = buildCart();
        for (int b = 0; b < 4; b++) {
            cart.cpuBusWrite(0x8000, (byte) b);
            assertEquals(0xB3, cart.cpuBusRead(0xC000),
                    "high window must remain on last bank after latch=" + b);
        }
    }

    @Test
    void cpuBusWrite_anywhereInRegisterRange_latchesBank() {
        // UxROM mirrors its register across all of $8000-$FFFF.
        Cartridge cart = buildCart();

        cart.cpuBusWrite(0x9000, (byte) 0x01);
        assertEquals(0xB1, cart.cpuBusRead(0x8000));

        cart.cpuBusWrite(0xABCD, (byte) 0x02);
        assertEquals(0xB2, cart.cpuBusRead(0x8000));

        cart.cpuBusWrite(0xFFFF, (byte) 0x00);
        assertEquals(0xB0, cart.cpuBusRead(0x8000));
    }

    @Test
    void cpuBusWrite_registerLatch_doesNotCorruptPRGMemory() {
        // Bank 0's marker is 0xB0. A write to $8000 should latch the
        // bank register but MUST NOT scribble the value into PRG memory.
        Cartridge cart = buildCart();
        cart.cpuBusWrite(0x8000, (byte) 0xFF);
        // After latch=0xFF (low 4 = 0xF; only 4 banks → masked further by impl;
        // here we just check $8000 in bank 0 is still 0xB0). Switch back:
        cart.cpuBusWrite(0x8000, (byte) 0x00);
        assertEquals(0xB0, cart.cpuBusRead(0x8000),
                "register writes must not overwrite the PRG marker byte");
    }

    @Test
    void cpuBusRead_belowPrgRange_returnsZero() {
        // Cartridge returns 0 for UNMAPPED addresses.
        Cartridge cart = buildCart();
        assertEquals(0, cart.cpuBusRead(0x4020));
    }

    @Test
    void mirrorModeReflectsHeader_notSwitchableByMapper() {
        // MapperTestSupport builds carts with flags6 bit 0 = 0 —
        // horizontal mirroring per the iNES spec. UxROM.mirror()
        // returns HARDWARE → Cartridge uses the header. The contract
        // under test is: UxROM does not switch mirroring at runtime;
        // whatever the header said is what surfaces.
        Cartridge cart = buildCart();
        assertEquals(net.lomibao.nes.rom.mapper.Mapper.Mirror.HORIZONTAL,
                cart.getMirrorMode(),
                "synthetic ROM with bit0=0 resolves to HORIZONTAL");
    }
}
