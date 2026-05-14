package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link MapperUNROM512} (iNES Mapper 30,
 * UNROM-512). Spec: <a href="https://www.nesdev.org/wiki/UNROM_512">
 * NESdev wiki — UNROM 512</a>.
 *
 * <p>UNROM-512 has a single 8-bit register latched on any write to
 * {@code $8000-$FFFF}:
 * <pre>
 * 7654 3210
 * M.CC PPPP
 * | ||  ++++ low 4 bits → 16KB PRG bank index (switchable at $8000-$BFFF)
 * | ++------ bits 4-5  → 8KB CHR-RAM bank index (4 banks → 32KB total)
 * +--------- bit 7     → 1-screen mirror select (when 1-screen mode is
 *                        enabled by iNES header bit 3 of byte 6).
 * </pre>
 *
 * <p>PRG layout (UxROM-style):
 * <ul>
 *   <li>{@code $8000-$BFFF}: switchable 16KB bank from register low nibble</li>
 *   <li>{@code $C000-$FFFF}: fixed to LAST 16KB of PRG ROM</li>
 * </ul>
 *
 * <p>CHR: 32KB of CHR-RAM, bank-switched in 8KB units via register bits 4-5.
 *
 * <p>Power-on: PRG bank 0, CHR bank 0, mirror LO.
 *
 * <p>Iconic title: <i>Micro Mages</i> (Morphcat Games, 2018).
 */
class MapperUNROM512Test {

    // 128KB PRG (Micro Mages size) → 8 × 16KB banks; 256KB → 16 banks.
    private static final int PRG_BANKS_8  = 8;
    private static final int PRG_BANKS_16 = 16;
    private static final int CHR_BANKS    = 0;  // CHR-RAM cart

    private static final int PRG_16K = 16 * 1024;
    private static final int CHR_8K  =  8 * 1024;

    // =====================================================================
    // E1 — Register decode
    // =====================================================================

    @Test
    void powerOn_state_pointsAtBank0_andLastBankFixed() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        // $8000 → switchable bank 0, byte 0.
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        // $C000 → last bank (index 7) byte 0 = 7 * 16KB = 0x1C000.
        assertEquals(7 * PRG_16K, m.cpuMapRead(0xC000));
        // Mirror power-on is ONESCREEN_LO.
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    @Test
    void cpuMapWrite_latchesPRGBank_lowFourBits() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        // Bank 3 select.
        m.cpuMapWrite(0x8000, 0x03);
        // $8000 → bank 3 byte 0 = 3 * 16KB = 0xC000.
        assertEquals(3 * PRG_16K, m.cpuMapRead(0x8000));
        // $C000 stays on last bank (7).
        assertEquals(7 * PRG_16K, m.cpuMapRead(0xC000));
    }

    @Test
    void cpuMapWrite_latchesCHRBank_bits4and5() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        // bits 4-5 = 0b11 → CHR bank 3. Low nibble unused (PRG bank 0).
        m.cpuMapWrite(0x8000, 0x30);
        // $0000 in CHR bank 3 → 3 * 8KB = 0x6000.
        assertEquals(3 * CHR_8K, m.ppuMapRead(0x0000));
        assertEquals(3 * CHR_8K + 0x1FFF, m.ppuMapRead(0x1FFF));
    }

    @Test
    void cpuMapWrite_latchesMirrorBit_M_selectsLOorHI() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        // Bit 7 set → ONESCREEN_HI.
        m.cpuMapWrite(0x8000, 0x80);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, m.mirror());
        // Bit 7 clear → ONESCREEN_LO.
        m.cpuMapWrite(0x8000, 0x00);
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    @Test
    void cpuMapWrite_outsideRegisterWindow_isNoOp() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        // Writes to <$8000 must NOT change any state.
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000, 0x03));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x7FFF, 0xFF));
        // Bank still 0, mirror still LO, CHR bank still 0.
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(0x0000, m.ppuMapRead(0x0000));
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    @Test
    void cpuMapWrite_multipleWrites_lastOneWins() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        m.cpuMapWrite(0x8000, 0x12);   // CHR bank 1, PRG bank 2
        m.cpuMapWrite(0xC000, 0xA5);   // 0b10100101 → M=1, CC=10 (bank 2), PPPP=0101 (bank 5)
        assertEquals(5 * PRG_16K, m.cpuMapRead(0x8000));
        assertEquals(2 * CHR_8K, m.ppuMapRead(0x0000));
        assertEquals(Mapper.Mirror.ONESCREEN_HI, m.mirror());
    }

    @Test
    void cpuMapWrite_inRange_returnsUNMAPPED_isRegisterHit() {
        // Writes to $8000-$FFFF go to the bank register, not PRG memory.
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000, 0x01));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xC000, 0x02));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xFFFF, 0xFF));
    }

    @Test
    void cpuMapWrite_singleArgOverload_isUNMAPPED() {
        // Interface compatibility: addr-only variant cannot drive a
        // register latch; should return UNMAPPED for any address.
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xFFFF));
    }

    // =====================================================================
    // E2 — PRG layout (16KB switchable at $8000-$BFFF, 16KB fixed-last at
    //                  $C000-$FFFF)
    // =====================================================================

    @Test
    void cpuMapRead_lowWindow_followsSwitchableBank() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        m.cpuMapWrite(0x8000, 0x05);  // bank 5
        // $8000 → bank 5 byte 0 = 5 * 16KB = 0x14000.
        assertEquals(5 * PRG_16K, m.cpuMapRead(0x8000));
        // $BFFF → bank 5 last byte = 0x14000 + 0x3FFF = 0x17FFF.
        assertEquals(5 * PRG_16K + 0x3FFF, m.cpuMapRead(0xBFFF));
    }

    @Test
    void cpuMapRead_highWindow_alwaysReturnsLastBank() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        int lastBank = PRG_BANKS_8 - 1;  // 7
        int lastBase = lastBank * PRG_16K;
        // Power-on: $C000 → last-bank byte 0.
        assertEquals(lastBase, m.cpuMapRead(0xC000));
        assertEquals(lastBase + 0x3FFF, m.cpuMapRead(0xFFFF));
        // Change low bank — high window stays on last bank.
        m.cpuMapWrite(0x8000, 0x03);
        assertEquals(lastBase, m.cpuMapRead(0xC000));
        assertEquals(lastBase + 0x3FFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapRead_boundary_BFFF_C000() {
        // Verify the $BFFF/$C000 boundary: the byte just below uses the
        // switchable bank; the byte just above uses the last bank.
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_16, CHR_BANKS);
        m.cpuMapWrite(0x8000, 0x04);  // bank 4 (low nibble)
        int lastBase = (PRG_BANKS_16 - 1) * PRG_16K;
        assertEquals(4 * PRG_16K + 0x3FFF, m.cpuMapRead(0xBFFF));
        assertEquals(lastBase,             m.cpuMapRead(0xC000));
    }

    @Test
    void cpuMapRead_outOfRange_returnsUNMAPPED() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x0000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x4020));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x7FFF));
    }

    // ---- Bookkeeping / lifecycle stubs (no IRQ source on UNROM-512) ----

    @Test
    void numberOfPRGBanks_andCHRBanks_reflectConstructor() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, 0);
        assertEquals(PRG_BANKS_8, m.numberOfPRGBanks());
        assertEquals(0, m.numberOfCHRBanks());
    }

    @Test
    void irqLifecycleStubs_areNoOps() {
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        m.scanLine();
        m.irqClear();
        assertFalse(m.reqState(), "UNROM-512 has no IRQ source");
    }

    @Test
    void ppuMapWrite_e1Stub_returnsUNMAPPED() {
        // E1 ships a stub ppuMapWrite that always returns UNMAPPED.
        // The 32KB CHR-RAM bank-aware write path is added in E3.
        MapperUNROM512 m = new MapperUNROM512(PRG_BANKS_8, CHR_BANKS);
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x0000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x1FFF));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x2000));
    }
}
