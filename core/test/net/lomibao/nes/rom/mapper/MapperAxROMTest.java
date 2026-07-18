package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link MapperAxROM} (iNES mapper 7, AxROM).
 *
 * <p>AxROM has a single bank register written to anywhere in
 * $8000-$FFFF:
 * <ul>
 *   <li>bits 0-2 select a 32KB PRG bank (note: 32KB step, not 16KB —
 *       the entire CPU PRG window is one switchable bank)</li>
 *   <li>bit 4 selects single-screen mirroring
 *       (0 = ONESCREEN_LO / NT A, 1 = ONESCREEN_HI / NT B)</li>
 *   <li>other bits are ignored</li>
 * </ul>
 * Power-on state: PRG bank 0, ONESCREEN_LO. CHR is typically 8KB
 * CHR-RAM (passthrough writes). Mirroring is ALWAYS single-screen at
 * runtime; the iNES header's mirror/4-screen bits are overridden by
 * the mapper.
 *
 * <p>Because the Mapper interface's original {@code cpuMapWrite(int)}
 * has no value parameter, this phase extends the interface with a
 * value-carrying overload {@code cpuMapWrite(int address, int value)}.
 * Tests drive register writes through that overload directly.
 */
class MapperAxROMTest {

    // 128KB / 32KB-per-bank = 4 banks; 256KB → 8 banks.
    private static final int PRG_BANKS_4 = 8;   // 128KB → 4 x 32KB banks (numbered 0-3)
    private static final int PRG_BANKS_8 = 16;  // 256KB → 8 x 32KB banks (numbered 0-7)

    // ---- PRG mapping (32KB switchable) ----

    @Test
    void cpuMapRead_powerOn_pointsAtBank0() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        // First byte of bank 0 → offset 0.
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        // Last byte of the 32KB window → offset $7FFF (still bank 0 on power-on).
        assertEquals(0x7FFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapWrite_bank1_thenCpuMapRead_returnsFirstByteOfBank1() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        // Writing bank-select=1 anywhere in $8000-$FFFF selects bank 1.
        m.cpuMapWrite(0x8000, 0x01);
        // bank 1 → 32KB offset 0x8000; reading $8000 returns first byte of bank 1.
        assertEquals(0x8000, m.cpuMapRead(0x8000));
    }

    @Test
    void cpuMapWrite_bank2_lastAddress_returnsLastByteOfBank2() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        m.cpuMapWrite(0x8000, 0x02);
        // bank 2 → 32KB offset 0x10000; last byte = 0x10000 + 0x7FFF = 0x17FFF.
        assertEquals(0x17FFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapWrite_32KBStep_isExactMultipleOf32K() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_8, 0);
        for (int bank = 0; bank < 8; bank++) {
            m.cpuMapWrite(0xC000, bank);
            assertEquals(bank * 0x8000, m.cpuMapRead(0x8000),
                    "bank " + bank + " should map $8000 → " + Integer.toHexString(bank * 0x8000));
        }
    }

    @Test
    void cpuMapRead_outOfRange_returnsUNMAPPED() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x7FFF));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x0000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x4020));
    }

    @Test
    void cpuMapWrite_outOfRange_isNoOp() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        // Writes to addresses outside $8000-$FFFF must NOT touch the
        // register and must return UNMAPPED.
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000, 0x03));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x7FFF, 0x11));
        // Bank should still be 0 (power-on), mirror still LO.
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    // ---- Mirroring (bit 4 of the register) ----

    @Test
    void mirror_powerOn_isONESCREEN_LO() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    @Test
    void mirror_bit4Set_switchesToONESCREEN_HI() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        m.cpuMapWrite(0x9000, 0x10);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, m.mirror());
    }

    @Test
    void mirror_bit4Cleared_returnsToONESCREEN_LO() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        m.cpuMapWrite(0x8000, 0x10);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, m.mirror());
        m.cpuMapWrite(0x8000, 0x00);
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    @Test
    void bankAndMirror_areIndependent_value0x13_selectsBank3AndHI() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        // 0x13 = bits 0-2 = 011 (bank 3), bit 4 = 1 (ONESCREEN_HI)
        m.cpuMapWrite(0x8000, 0x13);
        assertEquals(0x18000, m.cpuMapRead(0x8000), "bank 3 → 3 * 32KB = 0x18000");
        assertEquals(Mapper.Mirror.ONESCREEN_HI, m.mirror());
    }

    @Test
    void writeRegister_unrelatedBitsAreIgnored() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        // 0x08 = bit 3 set; bit 3 is reserved/ignored. Should be a no-op
        // on both bank and mirror.
        m.cpuMapWrite(0x8000, 0x08);
        assertEquals(0x0000, m.cpuMapRead(0x8000), "bit 3 must not affect bank select");
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());

        // 0xE0 = bits 5/6/7 set; high bits are reserved/ignored.
        m.cpuMapWrite(0x8000, 0xE0);
        assertEquals(0x0000, m.cpuMapRead(0x8000), "high bits must not affect bank select");
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    // ---- CHR mapping (typically 8KB CHR-RAM) ----

    @Test
    void ppuMapRead_inCHRRange_passesThrough() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        assertEquals(0x0000, m.ppuMapRead(0x0000));
        assertEquals(0x1FFF, m.ppuMapRead(0x1FFF));
    }

    @Test
    void ppuMapRead_outOfCHRRange_returnsUNMAPPED() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x2000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x3000));
    }

    @Test
    void ppuMapWrite_chrRAMcart_passesThrough() {
        // AxROM typically ships with CHR-RAM (nCHRBanks == 0).
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        assertEquals(0x0000, m.ppuMapWrite(0x0000));
        assertEquals(0x0100, m.ppuMapWrite(0x0100));
        assertEquals(0x1FFF, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_chrROMcart_returnsUNMAPPED() {
        // Rare AxROM variants ship with CHR-ROM; writes should be ignored.
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 1);
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x0000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_outOfCHRRange_returnsUNMAPPED() {
        MapperAxROM chrRam = new MapperAxROM(PRG_BANKS_4, 0);
        MapperAxROM chrRom = new MapperAxROM(PRG_BANKS_4, 1);
        assertEquals(Mapper.UNMAPPED, chrRam.ppuMapWrite(0x2000));
        assertEquals(Mapper.UNMAPPED, chrRom.ppuMapWrite(0x2000));
    }

    // ---- Bank counts and reset ----

    @Test
    void numberOfPRGBanks_andCHRBanks_reflectConstructor() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_8, 0);
        assertEquals(PRG_BANKS_8, m.numberOfPRGBanks());
        assertEquals(0, m.numberOfCHRBanks());
    }

    @Test
    void reset_returnsToPowerOnState_bank0AndONESCREEN_LO() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        // Mutate state...
        m.cpuMapWrite(0x8000, 0x13);
        assertEquals(0x18000, m.cpuMapRead(0x8000));
        assertEquals(Mapper.Mirror.ONESCREEN_HI, m.mirror());
        // ...then reset.
        m.reset();
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    // ---- IRQ + scanline stubs (no IRQ source on AxROM) ----

    @Test
    void irqLifecycleStubs_areNoOps() {
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        m.scanLine();
        m.irqClear();
        assertFalse(m.reqState(), "AxROM has no IRQ source");
    }

    // ---- cpuMapWrite sentinel: register write returns UNMAPPED ----

    @Test
    void cpuMapWrite_inRange_returnsUNMAPPED_becauseItIsARegisterHit() {
        // Writes to $8000-$FFFF on AxROM hit the bank register; they
        // are NOT writes to PRG memory. The mapper must return UNMAPPED
        // so Cartridge.cpuBusWrite doesn't try to index vPRGMemory.
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000, 0x01));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xC000, 0x02));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xFFFF, 0x10));
    }

    // ---- iNES header mirror/4-screen bits ARE OVERRIDDEN ----

    @Test
    void mirror_alwaysSingleScreen_neverHARDWARE_orHORIZONTAL_orVERTICAL() {
        // Hardware-level guarantee: AxROM has full mirroring control,
        // so the mapper's mirror() return value must always be one of
        // ONESCREEN_LO / ONESCREEN_HI. It must NEVER fall back to
        // HARDWARE (which would defer to the iNES header).
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        // Sweep every register value 0x00 - 0xFF and confirm mirror()
        // is one of the two single-screen modes — never HARDWARE,
        // HORIZONTAL, or VERTICAL.
        for (int v = 0; v <= 0xFF; v++) {
            m.cpuMapWrite(0x8000, v);
            Mapper.Mirror mode = m.mirror();
            assertTrue(
                    mode == Mapper.Mirror.ONESCREEN_LO || mode == Mapper.Mirror.ONESCREEN_HI,
                    "value " + Integer.toHexString(v) + " yielded mirror=" + mode);
        }
    }

    // ---- Existing single-arg cpuMapWrite still works (interface compat) ----

    @Test
    void cpuMapWrite_singleArgOverload_defaultsToUNMAPPED() {
        // The interface's pre-existing single-arg cpuMapWrite is called
        // by older Mapper consumers. For AxROM, register writes go
        // through the value-carrying overload; the single-arg variant
        // should still return UNMAPPED for any address (no PRG-write
        // landing zone on AxROM).
        MapperAxROM m = new MapperAxROM(PRG_BANKS_4, 0);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000));
    }
}
