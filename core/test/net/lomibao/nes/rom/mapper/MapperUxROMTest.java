package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link MapperUxROM} (iNES Mapper 2, UxROM).
 *
 * <p>UxROM spec (NESdev wiki Mapper 2):
 * <ul>
 *   <li>Writes to $8000-$FFFF select a 16KB PRG bank (low 4 bits).</li>
 *   <li>$8000-$BFFF is the switchable window; $C000-$FFFF is FIXED to
 *       the last 16KB bank.</li>
 *   <li>CHR is 8KB fixed (CHR-ROM if {@code nCHRBanks > 0}, CHR-RAM
 *       otherwise).</li>
 *   <li>Mirroring comes from the iNES header — mapper reports
 *       {@link Mapper.Mirror#HARDWARE}.</li>
 * </ul>
 */
class MapperUxROMTest {

    // ---- PRG mapping: switchable low window vs fixed high window ----

    @Test
    void cpuMapRead_lowWindow_bank0_byDefault() {
        // After power-on / reset, bank register is 0; $8000 → offset 0.
        MapperUxROM m = new MapperUxROM(4, 1);
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(0x3FFF, m.cpuMapRead(0xBFFF));
    }

    @Test
    void cpuMapRead_lowWindow_switchesToBank3_afterRegisterWrite() {
        MapperUxROM m = new MapperUxROM(4, 1);
        // Latch bank 3 by writing to anywhere in $8000-$FFFF.
        m.cpuMapWrite(0x8000, (byte) 0x03);
        // bank 3 starts at offset 3 * 16KB = 0xC000 in PRG ROM.
        assertEquals(0xC000, m.cpuMapRead(0x8000));
        assertEquals(0xFFFF, m.cpuMapRead(0xBFFF));
    }

    @Test
    void cpuMapRead_highWindow_alwaysPointsAtLastBank_regardlessOfRegister() {
        MapperUxROM m = new MapperUxROM(4, 1);
        // 4 PRG banks → last bank index 3 → starts at offset 0xC000.
        // $C000 should map to offset 0xC000 (start of last bank).
        assertEquals(0xC000, m.cpuMapRead(0xC000));
        assertEquals(0xFFFF, m.cpuMapRead(0xFFFF));

        // Switching the register MUST NOT change the high window.
        m.cpuMapWrite(0x8000, (byte) 0x00);
        assertEquals(0xC000, m.cpuMapRead(0xC000));
        assertEquals(0xFFFF, m.cpuMapRead(0xFFFF));

        m.cpuMapWrite(0x8000, (byte) 0x02);
        assertEquals(0xC000, m.cpuMapRead(0xC000));
        assertEquals(0xFFFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapWrite_to_9000_ABCD_FFFF_allLatchTheBank() {
        // Mapper 2's register is mirrored across the whole $8000-$FFFF
        // range; writes anywhere in there switch the bank.
        MapperUxROM a = new MapperUxROM(8, 1);
        a.cpuMapWrite(0x9000, (byte) 0x05);
        assertEquals(0x05 * 0x4000, a.cpuMapRead(0x8000));

        MapperUxROM b = new MapperUxROM(8, 1);
        b.cpuMapWrite(0xABCD, (byte) 0x06);
        assertEquals(0x06 * 0x4000, b.cpuMapRead(0x8000));

        MapperUxROM c = new MapperUxROM(8, 1);
        c.cpuMapWrite(0xFFFF, (byte) 0x02);
        assertEquals(0x02 * 0x4000, c.cpuMapRead(0x8000));
    }

    @Test
    void cpuMapRead_belowPrgRange_returnsUNMAPPED() {
        MapperUxROM m = new MapperUxROM(4, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x0000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x7FFF));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x4020));
    }

    @Test
    void cpuMapWrite_belowPrgRange_returnsUNMAPPED_andDoesNotLatch() {
        MapperUxROM m = new MapperUxROM(4, 1);
        // A write below $8000 must be UNMAPPED and must not change bank.
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000, (byte) 0x03));
        assertEquals(0x0000, m.cpuMapRead(0x8000),
                "bank register should remain at 0 after an out-of-range write");
    }

    @Test
    void cpuMapWrite_inRange_returnsUNMAPPED_writesAreRegisterOnlyNotPRG() {
        // Writes in $8000-$FFFF latch the bank register; they are NOT
        // passed through to PRG memory. UxROM PRG is ROM, not RAM.
        MapperUxROM m = new MapperUxROM(4, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000, (byte) 0x01));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xC000, (byte) 0x02));
    }

    // ---- Bank index masking: only low 4 bits matter ----

    @Test
    void cpuMapWrite_bankIndexMaskedToLow4Bits() {
        // Writing 0xFF should latch bank 0xF (15) — high bits ignored.
        // Use a 16-bank ROM so bank 15 is the last bank (valid).
        MapperUxROM m = new MapperUxROM(16, 1);
        m.cpuMapWrite(0x8000, (byte) 0xFF);
        // bank 15 starts at offset 15 * 16KB = 0x3C000.
        assertEquals(0x3C000, m.cpuMapRead(0x8000));
        assertEquals(0x3C000 + 0x3FFF, m.cpuMapRead(0xBFFF));
    }

    @Test
    void cpuMapWrite_highBitsBeyondLow4_doNotAffectBank() {
        MapperUxROM m = new MapperUxROM(8, 1);
        // 0xF0 → low 4 bits = 0 → bank 0.
        m.cpuMapWrite(0x8000, (byte) 0xF0);
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        // 0x80 | 0x03 → low 4 bits = 3 → bank 3.
        m.cpuMapWrite(0x8000, (byte) 0x83);
        assertEquals(0x03 * 0x4000, m.cpuMapRead(0x8000));
    }

    // ---- CHR mapping (8KB fixed; ROM passthrough or RAM passthrough) ----

    @Test
    void ppuMapRead_inCHRRange_passesThrough() {
        MapperUxROM m = new MapperUxROM(4, 1);
        assertEquals(0x0000, m.ppuMapRead(0x0000));
        assertEquals(0x0123, m.ppuMapRead(0x0123));
        assertEquals(0x1FFF, m.ppuMapRead(0x1FFF));
    }

    @Test
    void ppuMapRead_outOfCHRRange_returnsUNMAPPED() {
        MapperUxROM m = new MapperUxROM(4, 1);
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x2000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x3FFF));
    }

    @Test
    void ppuMapWrite_chrROMcart_returnsUNMAPPED() {
        MapperUxROM m = new MapperUxROM(4, 1);
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x0100));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_chrRAMcart_passesThroughInRange() {
        MapperUxROM m = new MapperUxROM(4, 0);
        assertEquals(0x0000, m.ppuMapWrite(0x0000));
        assertEquals(0x0100, m.ppuMapWrite(0x0100));
        assertEquals(0x1FFF, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_outOfCHRRange_returnsUNMAPPED_regardlessOfCHRRAM() {
        MapperUxROM rom = new MapperUxROM(4, 1);
        MapperUxROM ram = new MapperUxROM(4, 0);
        assertEquals(Mapper.UNMAPPED, rom.ppuMapWrite(0x2000));
        assertEquals(Mapper.UNMAPPED, ram.ppuMapWrite(0x2000));
    }

    // ---- ROM-size variants: bank-N-fixed at $C000 ----

    @Test
    void cpuMapRead_128kROM_bank7IsLastFixed_banks0to6Switchable() {
        // 128KB = 8 PRG banks. Last bank index = 7, offset = 7 * 16KB.
        MapperUxROM m = new MapperUxROM(8, 1);
        final int LAST_BASE = 7 * 0x4000;

        // High window always points at last bank.
        assertEquals(LAST_BASE, m.cpuMapRead(0xC000));
        assertEquals(LAST_BASE + 0x3FFF, m.cpuMapRead(0xFFFF));

        // Low window switchable across 0..6.
        for (int b = 0; b <= 6; b++) {
            m.cpuMapWrite(0x8000, (byte) b);
            assertEquals(b * 0x4000, m.cpuMapRead(0x8000),
                    "bank " + b + " base mismatch");
            assertEquals(b * 0x4000 + 0x3FFF, m.cpuMapRead(0xBFFF),
                    "bank " + b + " top mismatch");
        }
    }

    @Test
    void cpuMapRead_256kROM_bank15IsLastFixed() {
        // 256KB = 16 PRG banks. Last bank index = 15, offset = 15 * 16KB.
        MapperUxROM m = new MapperUxROM(16, 1);
        final int LAST_BASE = 15 * 0x4000;
        assertEquals(LAST_BASE, m.cpuMapRead(0xC000));
        assertEquals(LAST_BASE + 0x3FFF, m.cpuMapRead(0xFFFF));

        // Switching the register doesn't change the high window.
        m.cpuMapWrite(0x8000, (byte) 0x07);
        assertEquals(LAST_BASE, m.cpuMapRead(0xC000));
        // But the low window now follows bank 7.
        assertEquals(7 * 0x4000, m.cpuMapRead(0x8000));
    }

    // ---- Mirror + bank counts + lifecycle stubs ----

    @Test
    void mirror_isHARDWARE_forUxROM() {
        // UxROM mirroring is fixed by the iNES header bit; the mapper
        // tells Cartridge "fall back to the header" via HARDWARE.
        assertEquals(Mapper.Mirror.HARDWARE, new MapperUxROM(4, 1).mirror());
        assertEquals(Mapper.Mirror.HARDWARE, new MapperUxROM(8, 0).mirror());
    }

    @Test
    void numberOfPRGBanks_and_CHRBanks_reflectConstructor() {
        MapperUxROM m = new MapperUxROM(8, 0);
        assertEquals(8, m.numberOfPRGBanks());
        assertEquals(0, m.numberOfCHRBanks());
    }

    @Test
    void resetClearsBankRegister_lowWindowMapsToBank0_afterReset() {
        MapperUxROM m = new MapperUxROM(8, 1);
        m.cpuMapWrite(0x8000, (byte) 0x05);
        assertEquals(0x05 * 0x4000, m.cpuMapRead(0x8000));
        m.reset();
        assertEquals(0x0000, m.cpuMapRead(0x8000),
                "reset should bring the low window back to bank 0");
        // High window still on last bank after reset.
        assertEquals(7 * 0x4000, m.cpuMapRead(0xC000));
    }

    @Test
    void irqAndScanlineStubs_areCallableNoops() {
        MapperUxROM m = new MapperUxROM(4, 1);
        m.scanLine();
        m.irqClear();
        assertFalse(m.reqState(), "UxROM has no IRQ source");
    }

    @Test
    void cpuMapWrite_1argLegacy_alwaysReturnsUNMAPPED_andDoesNotLatch() {
        // The value-less 1-arg form can't latch the bank; it's kept for
        // interface compatibility but is effectively a no-op for UxROM.
        // Cartridge.cpuBusWrite uses the 2-arg overload.
        MapperUxROM m = new MapperUxROM(4, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xFFFF));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000));
        // Bank register should remain at 0 (no latch happened).
        assertEquals(0x0000, m.cpuMapRead(0x8000));
    }
}
