package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link MapperCNROM} (iNES Mapper 3).
 *
 * <p>CNROM keeps a fixed PRG window (16KB mirrored or 32KB flat — same as
 * NROM/Mapper000) and a single CHR-bank register latched on any CPU write
 * to {@code $8000-$FFFF}. Only the low two bits of the register width are
 * canonical (4 × 8KB = 32KB CHR-ROM max); higher bits are masked away.
 *
 * <p>The bank-latch value reaches the mapper via the new overload
 * {@link Mapper#cpuMapWrite(int, int)} (default delegates on the interface).
 * Bus conflicts on the real hardware are ignored here per Phase B2 of
 * docs/mapper-plan.md.
 */
class MapperCNROMTest {

    // ---- CHR bank switching ---------------------------------------------------

    @Test
    void ppuMapRead_bank0_byDefault_returnsRawAddressOffset() {
        // Before any cpuMapWrite the CHR bank is 0; PPU reads should land
        // in the first 8KB CHR bank.
        MapperCNROM m = new MapperCNROM(1, 4);
        assertEquals(0x0000, m.ppuMapRead(0x0000));
        assertEquals(0x0500, m.ppuMapRead(0x0500));
        assertEquals(0x1FFF, m.ppuMapRead(0x1FFF));
    }

    @Test
    void ppuMapRead_afterSwitchToBank3_offsetsAreShiftedByThreeBanks() {
        MapperCNROM m = new MapperCNROM(1, 4);
        m.cpuMapWrite(0x8000, (byte) 0x03);
        assertEquals(0x6000, m.ppuMapRead(0x0000));
        assertEquals(0x7FFF, m.ppuMapRead(0x1FFF));
    }

    @Test
    void ppuMapRead_at1FFF_inEachOfFourBanks_returnsDistinctMappedOffsets() {
        MapperCNROM m = new MapperCNROM(1, 4);
        int[] expected = { 0x1FFF, 0x3FFF, 0x5FFF, 0x7FFF };
        for (int b = 0; b < 4; b++) {
            m.cpuMapWrite(0x8000, (byte) b);
            assertEquals(expected[b], m.ppuMapRead(0x1FFF),
                    "bank " + b + " should map $1FFF to "
                            + Integer.toHexString(expected[b]));
        }
    }

    // ---- Bank register: written via cpuMapWrite + 2-bit mask ------------------

    @Test
    void cpuMapWriteValue_masksTo2Bits_evenForOverlongVariants() {
        // 0xFF → masked to bank 3; 0x06 → masked to bank 2.
        MapperCNROM m = new MapperCNROM(1, 4);
        m.cpuMapWrite(0x8000, (byte) 0xFF);
        assertEquals(0x6000, m.ppuMapRead(0x0000),
                "0xFF should be masked to bank 3 (low 2 bits)");
        m.cpuMapWrite(0x8000, (byte) 0x06);
        assertEquals(0x4000, m.ppuMapRead(0x0000),
                "0x06 should be masked to bank 2 (low 2 bits)");
    }

    @Test
    void cpuMapWrite_intOnly_returnsUNMAPPED_doesNotRouteToPRG() {
        // The mapper signals "do not write to PRG memory" by returning UNMAPPED.
        // CNROM has no PRG-RAM and the register lives inside the mapper itself.
        MapperCNROM m = new MapperCNROM(2, 4);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000),
                "CNROM cpuMapWrite(int) must NOT route into vPRGMemory");
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xFFFF));
    }

    @Test
    void cpuMapWrite_anyAddressInPrgWindow_isAcceptedForLatch() {
        MapperCNROM m = new MapperCNROM(1, 4);
        m.cpuMapWrite(0x9000, (byte) 0x01);
        assertEquals(0x2000, m.ppuMapRead(0x0000), "$9000 should latch");
        m.cpuMapWrite(0xABCD, (byte) 0x02);
        assertEquals(0x4000, m.ppuMapRead(0x0000), "$ABCD should latch");
        m.cpuMapWrite(0xFFFF, (byte) 0x03);
        assertEquals(0x6000, m.ppuMapRead(0x0000), "$FFFF should latch");
    }

    @Test
    void cpuMapWrite_outsidePrgRange_doesNotLatch() {
        MapperCNROM m = new MapperCNROM(1, 4);
        m.cpuMapWrite(0x8000, (byte) 0x02);
        // Out-of-range writes must NOT clobber the latch.
        m.cpuMapWrite(0x7FFF, (byte) 0x00);
        m.cpuMapWrite(0x0000, (byte) 0x00);
        assertEquals(0x4000, m.ppuMapRead(0x0000),
                "Out-of-range writes must leave bank=2 untouched");
        // Out-of-range int-only overload is UNMAPPED too.
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x7FFF));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x0000));
    }

    // ---- PRG mapping (always fixed; same shape as Mapper000) ------------------

    @Test
    void cpuMapRead_16KB_mirrorsLowerHalfIntoUpperHalf() {
        MapperCNROM m = new MapperCNROM(1, 1);
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(0x3FFF, m.cpuMapRead(0xBFFF));
        assertEquals(0x0000, m.cpuMapRead(0xC000));
        assertEquals(0x3FFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapRead_32KB_flatMappingAcrossFullPRGRange() {
        MapperCNROM m = new MapperCNROM(2, 1);
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(0x3FFF, m.cpuMapRead(0xBFFF));
        assertEquals(0x4000, m.cpuMapRead(0xC000));
        assertEquals(0x7FFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapRead_belowPrgRange_returnsUNMAPPED() {
        MapperCNROM m = new MapperCNROM(1, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x7FFF));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x0000));
    }

    @Test
    void cpuMapRead_unaffectedByBankSwitch() {
        // CNROM has NO PRG banking. The CHR latch must not perturb PRG reads.
        MapperCNROM m = new MapperCNROM(1, 4);
        int before = m.cpuMapRead(0xC000);
        m.cpuMapWrite(0x8000, (byte) 0x03);
        int after = m.cpuMapRead(0xC000);
        assertEquals(before, after);
    }

    // ---- PPU writes always rejected (CHR-ROM only on canonical CNROM) ---------

    @Test
    void ppuMapWrite_inRange_returnsUNMAPPED_alwaysCHRROM() {
        MapperCNROM m = new MapperCNROM(1, 4);
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x0000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_outOfRange_returnsUNMAPPED() {
        MapperCNROM m = new MapperCNROM(1, 4);
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x2000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x3FFF));
    }

    @Test
    void ppuMapRead_outOfRange_returnsUNMAPPED() {
        MapperCNROM m = new MapperCNROM(1, 4);
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x2000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x4000));
    }

    // ---- Mirror, bank counts, IRQ stubs --------------------------------------

    @Test
    void mirror_isAlwaysHARDWARE_forCNROM() {
        // CNROM has no mirroring control; defer to iNES header.
        assertEquals(Mapper.Mirror.HARDWARE, new MapperCNROM(1, 1).mirror());
        assertEquals(Mapper.Mirror.HARDWARE, new MapperCNROM(2, 4).mirror());
    }

    @Test
    void numberOfPRGBanks_andCHRBanks_reflectConstructorArgs() {
        MapperCNROM m = new MapperCNROM(2, 4);
        assertEquals(2, m.numberOfPRGBanks());
        assertEquals(4, m.numberOfCHRBanks());
    }

    @Test
    void resetAndStubs_doNotThrow_andClearBankToZero() {
        MapperCNROM m = new MapperCNROM(1, 4);
        m.cpuMapWrite(0x8000, (byte) 0x03);
        m.reset();
        m.scanLine();
        m.irqClear();
        assertFalse(m.reqState(), "CNROM has no IRQ source");
        assertEquals(0x0000, m.ppuMapRead(0x0000),
                "reset() must zero the CHR bank latch");
    }
}
