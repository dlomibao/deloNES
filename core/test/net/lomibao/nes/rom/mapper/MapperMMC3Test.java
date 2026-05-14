package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link MapperMMC3} (iNES Mapper 4).
 *
 * <p>MMC3 is the most prolific NES mapper: 8 internal 6-bit bank registers
 * (R0-R7) written via a two-step protocol — first a write to $8000 (even
 * address) selects WHICH R register the next $8001 (odd address) write
 * targets, plus the PRG/CHR layout-mode bits; then a write to $8001 stores
 * a byte into the selected R register. Additional even/odd register pairs
 * at $A000/$A001, $C000/$C001, $E000/$E001 control mirroring, PRG-RAM
 * protect, and the scanline IRQ counter.
 *
 * <p>Tests are organised by Phase D sub-stage in {@code docs/mapper-plan.md}.
 *
 * <p>Spec reference:
 * <a href="https://www.nesdev.org/wiki/MMC3">NESdev wiki — MMC3</a>.
 *
 * <p>Iconic title: <i>Super Mario Bros. 3</i> (Nintendo, 1990).
 */
class MapperMMC3Test {

    // =====================================================================
    // D1 — Bank registers R0..R7 + even/odd discrimination
    // =====================================================================

    /**
     * Write to $8000 (even) latches bank-select state: low 3 bits = R
     * index, bit 6 = PRG mode, bit 7 = CHR A12 invert. A subsequent
     * write to $8001 (odd) stores its value into the previously selected
     * R register.
     *
     * Verify the basic R6/R7 (PRG) latch path: select R6, write a bank
     * index, then read the PRG window that R6 maps. Default PRG mode 0
     * routes R6 to $8000-$9FFF.
     */
    @Test
    void d1_bankSelect_thenBankData_writesToR6_appearsAt_8000() {
        // 8 PRG banks (16KB each) = 16 8KB banks. We pick PRG bank 5
        // for R6 (8KB at $8000) — verify the read returns offset 5*8KB.
        MapperMMC3 m = new MapperMMC3(8, 1);
        // Bank select: R index = 6, PRG mode 0, CHR invert 0.
        m.cpuMapWrite(0x8000, 0x06);
        // Bank data: R6 = 5.
        m.cpuMapWrite(0x8001, 0x05);
        // PRG mode 0: $8000-$9FFF = R6 = bank 5 → offset 5*0x2000 = 0xA000.
        assertEquals(5 * 0x2000, m.cpuMapRead(0x8000));
        assertEquals(5 * 0x2000 + 0x1FFF, m.cpuMapRead(0x9FFF));
    }

    /**
     * R7 is always at $A000-$BFFF regardless of PRG mode. Verify it
     * latches and maps independently of R6.
     */
    @Test
    void d1_writesToR7_appearAt_A000() {
        MapperMMC3 m = new MapperMMC3(8, 1);  // 16 8KB banks
        m.cpuMapWrite(0x8000, 0x07);   // select R7
        m.cpuMapWrite(0x8001, 0x03);   // R7 = bank 3
        // R7 is always at $A000.
        assertEquals(3 * 0x2000, m.cpuMapRead(0xA000));
        assertEquals(3 * 0x2000 + 0x1FFF, m.cpuMapRead(0xBFFF));
    }

    /**
     * R0 is a 2KB CHR bank (low bit ignored). With CHR-invert=0 it lives
     * at $0000-$07FF in CHR space.
     */
    @Test
    void d1_writesToR0_storeCHR_2kbBank() {
        MapperMMC3 m = new MapperMMC3(2, 8);  // 8 CHR banks (8KB) = 64 1KB banks
        m.cpuMapWrite(0x8000, 0x00);   // select R0
        m.cpuMapWrite(0x8001, 0x04);   // R0 = 4 → low bit ignored, 2KB bank = (4 & ~1) * 1KB = offset 0x1000
        // CHR-invert=0: R0 = $0000-$07FF, 2KB bank. Bank 4 (with bit 0
        // forced 0) starts at offset 4 * 0x400 = 0x1000.
        assertEquals(0x1000, m.ppuMapRead(0x0000));
        assertEquals(0x1000 + 0x07FF, m.ppuMapRead(0x07FF));
    }

    /**
     * R0's low bit is ignored — writing 5 or 4 produces identical mapping.
     */
    @Test
    void d1_r0_lowBitIgnored_2kbBank() {
        MapperMMC3 m4 = new MapperMMC3(2, 8);
        m4.cpuMapWrite(0x8000, 0x00);
        m4.cpuMapWrite(0x8001, 0x04);
        int with4 = m4.ppuMapRead(0x0000);

        MapperMMC3 m5 = new MapperMMC3(2, 8);
        m5.cpuMapWrite(0x8000, 0x00);
        m5.cpuMapWrite(0x8001, 0x05);
        int with5 = m5.ppuMapRead(0x0000);
        assertEquals(with4, with5, "R0 low bit must be ignored (2KB bank)");
    }

    /**
     * R2 is a 1KB CHR bank — full 6 bits used. With CHR-invert=0 it
     * lives at $1000-$13FF.
     */
    @Test
    void d1_writesToR2_storeCHR_1kbBank() {
        MapperMMC3 m = new MapperMMC3(2, 8);  // 64 1KB CHR banks
        m.cpuMapWrite(0x8000, 0x02);   // select R2
        m.cpuMapWrite(0x8001, 0x07);   // R2 = 7
        // CHR-invert=0: R2 → $1000. Bank 7 * 1KB = 0x1C00.
        assertEquals(0x1C00, m.ppuMapRead(0x1000));
        assertEquals(0x1C00 + 0x03FF, m.ppuMapRead(0x13FF));
    }

    /**
     * Even/odd discrimination: writes to an even address (bit 0 = 0)
     * select the bank-select state; writes to an odd address (bit 0 = 1)
     * store data into the selected R register. The address only matters
     * via its $8000-$9FFF range and bit 0 — multiple even addresses
     * within $8000-$9FFE all act as bank-select, multiple odd addresses
     * within $8001-$9FFF all act as bank-data.
     */
    @Test
    void d1_evenOdd_discriminatedByBit0_notFullAddress() {
        MapperMMC3 m = new MapperMMC3(8, 1);  // 16 PRG 8KB banks
        // Use $8002 (even) instead of $8000.
        m.cpuMapWrite(0x8002, 0x06);   // select R6
        // Use $9FFF (odd) instead of $8001.
        m.cpuMapWrite(0x9FFF, 0x09);   // R6 = 9
        assertEquals(9 * 0x2000, m.cpuMapRead(0x8000));
    }

    /**
     * Writing to $8000 multiple times before a $8001 only keeps the
     * LAST bank-select state. Each $8001 write targets the most recent
     * R index.
     */
    @Test
    void d1_lastBankSelectWins_evenWritesAreOverwritten() {
        MapperMMC3 m = new MapperMMC3(8, 1);  // 16 PRG 8KB banks
        m.cpuMapWrite(0x8000, 0x06);   // select R6
        m.cpuMapWrite(0x8000, 0x07);   // override: select R7 instead
        m.cpuMapWrite(0x8001, 0x05);   // R7 = 5
        // R7 is at $A000.
        assertEquals(5 * 0x2000, m.cpuMapRead(0xA000));
    }

    /**
     * Bank-data writes update only the currently-selected R register.
     * Verify by setting R6 to one value, then changing the selection
     * to R7 and writing a different value — R6 stays unchanged.
     */
    @Test
    void d1_bankData_onlyUpdatesSelectedR() {
        MapperMMC3 m = new MapperMMC3(8, 1);  // 16 PRG 8KB banks
        m.cpuMapWrite(0x8000, 0x06);
        m.cpuMapWrite(0x8001, 0x02);   // R6 = 2

        m.cpuMapWrite(0x8000, 0x07);
        m.cpuMapWrite(0x8001, 0x09);   // R7 = 9 (R6 unaffected)

        // R6 is still 2 at $8000 (PRG mode 0).
        assertEquals(2 * 0x2000, m.cpuMapRead(0x8000));
        // R7 is 9 at $A000.
        assertEquals(9 * 0x2000, m.cpuMapRead(0xA000));
    }

    /**
     * Out-of-range reads return UNMAPPED.
     */
    @Test
    void d1_outOfRangeReads_returnUNMAPPED() {
        MapperMMC3 m = new MapperMMC3(8, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x0000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x7FFF));
    }

    @Test
    void d1_ppuOutOfRangeReads_returnUNMAPPED() {
        MapperMMC3 m = new MapperMMC3(8, 1);
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x2000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(-1));
    }

    /**
     * R1 (the OTHER 2KB CHR bank, slots 2-3 in CHR-invert=0 layout).
     */
    @Test
    void d1_writesToR1_storeCHR_2kbBank_atSlots2_3() {
        MapperMMC3 m = new MapperMMC3(2, 8);
        m.cpuMapWrite(0x8000, 0x01);   // select R1
        m.cpuMapWrite(0x8001, 0x06);   // R1 = 6 → 2KB bank at offset 6*1KB = 0x1800
        assertEquals(0x1800, m.ppuMapRead(0x0800));
        assertEquals(0x1800 + 0x07FF, m.ppuMapRead(0x0FFF));
    }

    /**
     * R3/R4/R5 (1KB CHR banks, slots 5/6/7).
     */
    @Test
    void d1_writesToR3_R4_R5_storeCHR_1kbBanks() {
        MapperMMC3 m = new MapperMMC3(2, 8);
        m.cpuMapWrite(0x8000, 0x03); m.cpuMapWrite(0x8001, 0x08);  // R3 = 8
        m.cpuMapWrite(0x8000, 0x04); m.cpuMapWrite(0x8001, 0x09);  // R4 = 9
        m.cpuMapWrite(0x8000, 0x05); m.cpuMapWrite(0x8001, 0x0A);  // R5 = 10
        assertEquals(0x2000, m.ppuMapRead(0x1400));   // R3 = bank 8 * 1KB = 0x2000
        assertEquals(0x2400, m.ppuMapRead(0x1800));   // R4 = 9 * 1KB
        assertEquals(0x2800, m.ppuMapRead(0x1C00));   // R5 = 10 * 1KB
    }

    /**
     * 1-arg cpuMapWrite (legacy interface) is always a no-op — returns
     * UNMAPPED for any address since it can't latch without a value.
     */
    @Test
    void d1_cpuMapWrite_1arg_legacy_returnsUNMAPPED() {
        MapperMMC3 m = new MapperMMC3(2, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x8000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xFFFF));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x0000));
    }

    /**
     * Writes below $8000 must not affect register state.
     */
    @Test
    void d1_cpuMapWrite_belowPrgRange_returnsUNMAPPED_andNoStateChange() {
        MapperMMC3 m = new MapperMMC3(8, 1);
        m.cpuMapWrite(0x8000, 0x06);
        m.cpuMapWrite(0x8001, 0x03);
        int before = m.cpuMapRead(0x8000);

        m.cpuMapWrite(0x6000, 0x77);
        m.cpuMapWrite(0x7FFF, 0xAA);

        assertEquals(before, m.cpuMapRead(0x8000));
    }

    /**
     * Bank-count metadata reflects the constructor arguments.
     */
    @Test
    void d1_numberOfBanks_reflectConstructor() {
        MapperMMC3 m = new MapperMMC3(8, 4);
        assertEquals(8, m.numberOfPRGBanks());
        assertEquals(4, m.numberOfCHRBanks());
    }

    /**
     * Lifecycle stubs are callable; with the IRQ counter not yet wired
     * (D6) {@link MapperMMC3#reqState()} always returns false.
     */
    @Test
    void d1_lifecycleStubs_callable_withoutIRQ() {
        MapperMMC3 m = new MapperMMC3(2, 1);
        m.scanLine();
        m.irqClear();
        assertFalse(m.reqState());
    }

    /**
     * Default mirror() reflects the $A000 register (which starts at 0).
     * Bit 0 = 0 → VERTICAL (the post-reset default; D4 wires $A000).
     */
    @Test
    void d1_default_mirror_isVertical() {
        MapperMMC3 m = new MapperMMC3(2, 1);
        assertEquals(Mapper.Mirror.VERTICAL, m.mirror());
    }

    /**
     * Reset clears the bank registers and select index.
     */
    @Test
    void d1_reset_clearsState() {
        MapperMMC3 m = new MapperMMC3(8, 1);
        m.cpuMapWrite(0x8000, 0x06);
        m.cpuMapWrite(0x8001, 0x05);
        // After reset, R6 = 0 so $8000 maps to bank 0.
        m.reset();
        assertEquals(0x0000, m.cpuMapRead(0x8000));
    }

    // =====================================================================
    // D2 — PRG layout modes
    // =====================================================================

    /**
     * PRG mode 0 ($8000 bit 6 = 0):
     *   $8000-$9FFF = R6
     *   $A000-$BFFF = R7
     *   $C000-$DFFF = 2nd-to-last 8KB bank
     *   $E000-$FFFF = last 8KB bank (fixed always)
     */
    @Test
    void d2_prgMode0_layout() {
        // 8 PRG-16K banks = 16 PRG-8K banks (last index = 15, second-to-last = 14).
        MapperMMC3 m = new MapperMMC3(8, 1);
        m.cpuMapWrite(0x8000, 0x06);   // select R6, mode 0
        m.cpuMapWrite(0x8001, 0x03);   // R6 = 3
        m.cpuMapWrite(0x8000, 0x07);
        m.cpuMapWrite(0x8001, 0x05);   // R7 = 5

        assertEquals(3 * 0x2000, m.cpuMapRead(0x8000));        // R6
        assertEquals(5 * 0x2000, m.cpuMapRead(0xA000));        // R7
        assertEquals(14 * 0x2000, m.cpuMapRead(0xC000));       // 2nd-to-last
        assertEquals(15 * 0x2000, m.cpuMapRead(0xE000));       // last
        assertEquals(15 * 0x2000 + 0x1FFF, m.cpuMapRead(0xFFFF));
    }

    /**
     * PRG mode 1 ($8000 bit 6 = 1):
     *   $8000-$9FFF = 2nd-to-last
     *   $A000-$BFFF = R7
     *   $C000-$DFFF = R6
     *   $E000-$FFFF = last
     */
    @Test
    void d2_prgMode1_swapsR6AndSecondToLast() {
        MapperMMC3 m = new MapperMMC3(8, 1);
        // Mode 0: set R6 = 3, R7 = 5.
        m.cpuMapWrite(0x8000, 0x06);
        m.cpuMapWrite(0x8001, 0x03);
        m.cpuMapWrite(0x8000, 0x07);
        m.cpuMapWrite(0x8001, 0x05);

        // Flip to mode 1 (bit 6 = 1); keep R-index pointing at R7 (low 3 = 7).
        m.cpuMapWrite(0x8000, 0x47);

        assertEquals(14 * 0x2000, m.cpuMapRead(0x8000), "2nd-to-last at $8000");
        assertEquals(5 * 0x2000, m.cpuMapRead(0xA000), "R7 at $A000");
        assertEquals(3 * 0x2000, m.cpuMapRead(0xC000), "R6 at $C000");
        assertEquals(15 * 0x2000, m.cpuMapRead(0xE000), "last at $E000");
    }

    /**
     * Last 8KB bank is FIXED at $E000-$FFFF in BOTH modes.
     */
    @Test
    void d2_lastBank_alwaysFixedAt_E000() {
        MapperMMC3 m = new MapperMMC3(8, 1);
        m.cpuMapWrite(0x8000, 0x06);
        m.cpuMapWrite(0x8001, 0x00);
        assertEquals(15 * 0x2000, m.cpuMapRead(0xE000));
        // Mode 1.
        m.cpuMapWrite(0x8000, 0x46);
        assertEquals(15 * 0x2000, m.cpuMapRead(0xE000));
    }

    /**
     * R6 walks all PRG 8KB banks.
     */
    @Test
    void d2_r6_acrossAllPrgBanks() {
        MapperMMC3 m = new MapperMMC3(4, 1);  // 8 PRG 8KB banks
        for (int b = 0; b < 8; b++) {
            m.cpuMapWrite(0x8000, 0x06);
            m.cpuMapWrite(0x8001, b);
            assertEquals(b * 0x2000, m.cpuMapRead(0x8000),
                    "PRG bank " + b + " base mismatch");
        }
    }

    // =====================================================================
    // D3 — CHR layout + A12 invert
    // =====================================================================

    /**
     * CHR-invert = 0: 2KB,2KB,1KB,1KB,1KB,1KB at $0000-$1FFF.
     * R0 → $0000-$07FF, R1 → $0800-$0FFF, R2 → $1000-$13FF,
     * R3 → $1400-$17FF, R4 → $1800-$1BFF, R5 → $1C00-$1FFF.
     */
    @Test
    void d3_chrInvert0_layout() {
        MapperMMC3 m = new MapperMMC3(2, 8);  // 64 1KB CHR banks
        m.cpuMapWrite(0x8000, 0x00); m.cpuMapWrite(0x8001, 0x02);  // R0 = 2
        m.cpuMapWrite(0x8000, 0x01); m.cpuMapWrite(0x8001, 0x06);  // R1 = 6
        m.cpuMapWrite(0x8000, 0x02); m.cpuMapWrite(0x8001, 0x10);  // R2 = 16
        m.cpuMapWrite(0x8000, 0x03); m.cpuMapWrite(0x8001, 0x11);  // R3 = 17
        m.cpuMapWrite(0x8000, 0x04); m.cpuMapWrite(0x8001, 0x12);  // R4 = 18
        m.cpuMapWrite(0x8000, 0x05); m.cpuMapWrite(0x8001, 0x13);  // R5 = 19

        assertEquals(0x0800, m.ppuMapRead(0x0000));            // R0 = 2 → 2*1KB
        assertEquals(0x0800 + 0x07FF, m.ppuMapRead(0x07FF));
        assertEquals(0x1800, m.ppuMapRead(0x0800));            // R1 = 6
        assertEquals(0x1800 + 0x07FF, m.ppuMapRead(0x0FFF));
        assertEquals(0x4000, m.ppuMapRead(0x1000));            // R2 = 16
        assertEquals(0x4000 + 0x03FF, m.ppuMapRead(0x13FF));
        assertEquals(0x4400, m.ppuMapRead(0x1400));            // R3 = 17
        assertEquals(0x4800, m.ppuMapRead(0x1800));            // R4 = 18
        assertEquals(0x4C00, m.ppuMapRead(0x1C00));            // R5 = 19
        assertEquals(0x4C00 + 0x03FF, m.ppuMapRead(0x1FFF));
    }

    /**
     * CHR-invert = 1: 1KB,1KB,1KB,1KB,2KB,2KB at $0000-$1FFF.
     * R2-R5 → low half ($0000-$0FFF), R0/R1 → high half ($1000-$1FFF).
     */
    @Test
    void d3_chrInvert1_layout() {
        MapperMMC3 m = new MapperMMC3(2, 8);
        m.cpuMapWrite(0x8000, 0x00); m.cpuMapWrite(0x8001, 0x02);  // R0 = 2
        m.cpuMapWrite(0x8000, 0x01); m.cpuMapWrite(0x8001, 0x06);  // R1 = 6
        m.cpuMapWrite(0x8000, 0x02); m.cpuMapWrite(0x8001, 0x10);  // R2 = 16
        m.cpuMapWrite(0x8000, 0x03); m.cpuMapWrite(0x8001, 0x11);  // R3 = 17
        m.cpuMapWrite(0x8000, 0x04); m.cpuMapWrite(0x8001, 0x12);  // R4 = 18
        m.cpuMapWrite(0x8000, 0x05); m.cpuMapWrite(0x8001, 0x13);  // R5 = 19
        // Flip CHR-invert (bit 7) — value bit 7 = 1.
        m.cpuMapWrite(0x8000, 0x85);

        assertEquals(0x4000, m.ppuMapRead(0x0000));   // R2 = 16
        assertEquals(0x4400, m.ppuMapRead(0x0400));   // R3 = 17
        assertEquals(0x4800, m.ppuMapRead(0x0800));   // R4 = 18
        assertEquals(0x4C00, m.ppuMapRead(0x0C00));   // R5 = 19
        assertEquals(0x0800, m.ppuMapRead(0x1000));   // R0 = 2
        assertEquals(0x1800, m.ppuMapRead(0x1800));   // R1 = 6
    }

    /**
     * Flipping CHR-invert mid-stream swaps the halves but preserves
     * bank-register contents.
     */
    @Test
    void d3_chrInvertFlip_preservesBankRegisterValues() {
        MapperMMC3 m = new MapperMMC3(2, 8);
        m.cpuMapWrite(0x8000, 0x02); m.cpuMapWrite(0x8001, 0x10);   // R2 = 16
        m.cpuMapWrite(0x8000, 0x00); m.cpuMapWrite(0x8001, 0x02);   // R0 = 2

        // invert=0: R2 at $1000, R0 at $0000.
        assertEquals(0x4000, m.ppuMapRead(0x1000));
        assertEquals(0x0800, m.ppuMapRead(0x0000));

        // Flip CHR-invert.
        m.cpuMapWrite(0x8000, 0x80);
        // After flip: R2 at $0000, R0 at $1000.
        assertEquals(0x4000, m.ppuMapRead(0x0000));
        assertEquals(0x0800, m.ppuMapRead(0x1000));
    }

    /**
     * R1 low bit also ignored (independent 2KB bank).
     */
    @Test
    void d3_r1_lowBitIgnored() {
        MapperMMC3 a = new MapperMMC3(2, 8);
        a.cpuMapWrite(0x8000, 0x01); a.cpuMapWrite(0x8001, 0x04);
        int with4 = a.ppuMapRead(0x0800);

        MapperMMC3 b = new MapperMMC3(2, 8);
        b.cpuMapWrite(0x8000, 0x01); b.cpuMapWrite(0x8001, 0x05);
        int with5 = b.ppuMapRead(0x0800);
        assertEquals(with4, with5, "R1 low bit must be ignored (2KB bank)");
    }

    // =====================================================================
    // D4 — Mirroring register ($A000)
    // =====================================================================

    /**
     * $A000 (even) bit 0 = 0 → VERTICAL mirroring.
     */
    @Test
    void d4_a000_bit0_zero_vertical() {
        MapperMMC3 m = new MapperMMC3(2, 1);
        m.cpuMapWrite(0xA000, 0x00);
        assertEquals(Mapper.Mirror.VERTICAL, m.mirror());
    }

    /**
     * $A000 (even) bit 0 = 1 → HORIZONTAL mirroring.
     */
    @Test
    void d4_a000_bit0_one_horizontal() {
        MapperMMC3 m = new MapperMMC3(2, 1);
        m.cpuMapWrite(0xA000, 0x01);
        assertEquals(Mapper.Mirror.HORIZONTAL, m.mirror());
    }

    /**
     * Writes to $A001 (odd, PRG-RAM protect) MUST NOT alter mirroring.
     */
    @Test
    void d4_a001_doesNotAffectMirroring() {
        MapperMMC3 m = new MapperMMC3(2, 1);
        m.cpuMapWrite(0xA000, 0x01);    // HORIZONTAL
        m.cpuMapWrite(0xA001, 0x00);    // PRG-RAM protect (bit 0 = 0 would look like vertical)
        assertEquals(Mapper.Mirror.HORIZONTAL, m.mirror(),
                "$A001 (PRG-RAM protect) must not alter mirror state");
    }

    /**
     * Mirroring is toggleable at runtime — writes can flip back and forth.
     */
    @Test
    void d4_mirroring_toggleableAtRuntime() {
        MapperMMC3 m = new MapperMMC3(2, 1);
        m.cpuMapWrite(0xA000, 0x01);
        assertEquals(Mapper.Mirror.HORIZONTAL, m.mirror());
        m.cpuMapWrite(0xA000, 0x00);
        assertEquals(Mapper.Mirror.VERTICAL, m.mirror());
    }
}
