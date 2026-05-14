package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link MapperMMC1} (iNES Mapper 1).
 *
 * <p>MMC1 is fundamentally a serial-shift-register-driven mapper: writes
 * to {@code $8000-$FFFF} feed bit 0 of the written value into a 5-bit
 * shift register from the right. After exactly five non-reset writes,
 * the accumulated 5-bit value is committed to ONE of four internal
 * registers, selected by bits 13-14 of the WRITE address:
 *
 * <ul>
 *   <li>{@code $8000-$9FFF} → Control</li>
 *   <li>{@code $A000-$BFFF} → CHR bank 0</li>
 *   <li>{@code $C000-$DFFF} → CHR bank 1</li>
 *   <li>{@code $E000-$FFFF} → PRG bank</li>
 * </ul>
 *
 * <p>Any write whose bit 7 is set RESETS the shifter (clears the shift
 * count) AND OR's $0C into the control register — putting the chip
 * into the "16KB PRG, last bank fixed at $C000-$FFFF" mode that the
 * NES boot ROM expects.
 *
 * <p>Spec reference: <a href="https://www.nesdev.org/wiki/MMC1">NESdev
 * wiki — MMC1</a>.
 */
class MapperMMC1Test {

    // =====================================================================
    // C1 — Serial shift register
    // =====================================================================

    /**
     * Power-on default control register is the post-reset value:
     * (control | 0x0C) where control starts at 0, so 0x0C. That
     * corresponds to PRG mode 3 (16KB switchable at $8000-$BFFF, last
     * 16KB fixed at $C000-$FFFF). Verify by reading the high window of
     * a multi-bank PRG ROM — it must return offset into the LAST bank.
     */
    @Test
    void powerOn_controlRegister_isInPost_reset_mode_16kLastFixed() {
        MapperMMC1 m = new MapperMMC1(4, 1);
        // Last bank (index 3) starts at offset 3 * 16KB = 0xC000.
        assertEquals(0xC000, m.cpuMapRead(0xC000),
                "post-reset PRG mode should be 16KB-last-fixed, so $C000 → last-bank start");
        assertEquals(0xC000 + 0x3FFF, m.cpuMapRead(0xFFFF));
    }

    /**
     * Five writes of bit0=1 in sequence commit value 0b11111 (=0x1F) to
     * the PRG bank register (write address $E000-$FFFF selects PRG bank).
     * We verify the latched bank by reading the low window AFTER setting
     * the control register to PRG mode 2 (16KB-first-fixed) so the LOW
     * window reflects the latched PRG bank.
     */
    @Test
    void fiveWrites_commitValueOnFifthWrite_toDestinationRegister() {
        MapperMMC1 m = new MapperMMC1(32, 1);  // 32 banks = bank 31 valid (0x1F)
        // Default Control is 0x0C → PRG mode 3 (16KB low switchable,
        // high fixed-to-last). The low window follows the PRG bank
        // register, perfect for verifying commit-to-PRG-bank.

        // Commit value 0x1F to the PRG bank register at $E000.
        writePrgBank(m, 0x1F);

        // PRG mode 3 → low window switchable. PRG bank register low 4
        // bits are the bank (high bit may be PRG-RAM enable on SUROM,
        // ignored here). Mask to 4 bits: 0x0F.
        // So bank should resolve to 0x0F * 16KB.
        assertEquals(0x0F * 0x4000, m.cpuMapRead(0x8000));
    }

    /**
     * Fewer than 5 writes should NOT commit. After only 4 writes the
     * PRG bank register stays at its power-on value.
     */
    @Test
    void fourWrites_doNotCommit() {
        MapperMMC1 m = new MapperMMC1(32, 1);
        // Default Control mode 3: low window follows PRG bank register.
        // Pre-write low window points at bank 0 (default PRG bank reg).
        int before = m.cpuMapRead(0x8000);

        // 4 writes of bit0=1 — should NOT yet commit
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);

        assertEquals(before, m.cpuMapRead(0x8000),
                "only 4 writes — PRG bank register must not commit yet");
    }

    /**
     * After a successful 5-write commit, the shifter resets and the
     * NEXT 5 writes drive a new commit. Demonstrates that the shifter
     * empties on commit.
     */
    @Test
    void afterCommit_shifter_resets_andNextSequenceCommitsAgain() {
        MapperMMC1 m = new MapperMMC1(32, 1);
        // Default Control is mode 3 (low switchable). Commit PRG bank = 0x03.
        writePrgBank(m, 0x03);
        assertEquals(0x03 * 0x4000, m.cpuMapRead(0x8000));

        // Now do another 5-write sequence committing 0x05.
        writePrgBank(m, 0x05);
        assertEquals(0x05 * 0x4000, m.cpuMapRead(0x8000));
    }

    /**
     * Bit 7 set on any written value RESETS the shifter mid-sequence
     * AND OR's $0C into the control register. Demonstrates: do 3 partial
     * writes, then a bit-7-set value, then attempt 4 more bit0=1 writes
     * — the next commit needs 5 writes from a fresh shifter, so 4 won't
     * commit.
     */
    @Test
    void writeWithBit7Set_resetsShifter_partialBitsLost() {
        MapperMMC1 m = new MapperMMC1(32, 1);
        writeControl(m, 0x08);  // PRG mode 2

        // 3 partial writes of bit0=1 (would build up to 0b111 if all 5 landed)
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);

        // Bit-7-set write resets the shifter (and OR $0C into control).
        m.cpuMapWrite(0xE000, 0x80);

        // 4 writes of bit0=1 after reset — still 1 short of commit.
        int bankBefore = m.cpuMapRead(0x8000);
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);
        m.cpuMapWrite(0xE000, 0x01);
        assertEquals(bankBefore, m.cpuMapRead(0x8000),
                "shifter must have been reset by bit-7-set write");
    }

    /**
     * Bit-7-set OR's $0C into the control register. Verify by:
     * 1) Setting control to PRG mode 0 (32KB switchable; bits 2-3 = 00).
     * 2) Issuing a bit-7-set write.
     * 3) Confirming the control reg is now mode 3 (16KB last-fixed) —
     *    i.e. high window points at the LAST bank.
     */
    @Test
    void writeWithBit7Set_orsZeroCintoControlRegister() {
        MapperMMC1 m = new MapperMMC1(4, 1);
        // Set Control to PRG mode 0 (32KB switchable): bits 2-3 = 00 → 0x00.
        writeControl(m, 0x00);

        // PRG mode 0 → 32KB switchable: $C000 should NOT necessarily map
        // to the last bank. With PRG bank reg at 0, it maps to bank 0
        // (offset 0x4000 inside the 32KB pair).
        assertEquals(0x4000, m.cpuMapRead(0xC000),
                "after PRG mode 0 with bank reg 0, $C000 → upper 16KB of bank 0 pair");

        // Now bit-7-set write — should put control back to "last-fixed" mode.
        m.cpuMapWrite(0xE000, 0x80);

        // $C000 must now map to the LAST bank (index 3 of 4 banks).
        assertEquals(0xC000, m.cpuMapRead(0xC000),
                "after bit-7-set, control |= 0x0C → PRG mode 3 → high window fixed to last bank");
    }

    /**
     * Destination register is selected by bits 13-14 of the WRITE
     * address. Verify each of the four destination ranges by writing
     * the same 5-bit value to each and observing the effect on the
     * appropriate output (Control mirror, CHR bank, PRG bank).
     */
    @Test
    void destinationRegister_selectedByAddressBits13_14() {
        // $8000-$9FFF → Control
        MapperMMC1 a = new MapperMMC1(4, 1);
        // Commit Control = 0b00010 = 0x02 → mirroring = VERTICAL (bits 0-1 = 10).
        commitFiveBitValue(a, 0x9FFF, 0x02);
        assertEquals(Mapper.Mirror.VERTICAL, a.mirror());

        // $A000-$BFFF → CHR bank 0
        MapperMMC1 b = new MapperMMC1(4, 2);
        // Set CHR mode to 4KB so CHR bank 0 register is meaningful.
        writeControl(b, 0x10);  // bit 4 set
        // Commit CHR bank 0 = 0x01 → 4KB bank index 1 at $0000.
        commitFiveBitValue(b, 0xA000, 0x01);
        // CHR bank 1 at $0000 in 4KB mode → offset 1 * 4KB = 0x1000.
        assertEquals(0x1000, b.ppuMapRead(0x0000));

        // $C000-$DFFF → CHR bank 1
        MapperMMC1 c = new MapperMMC1(4, 2);
        writeControl(c, 0x10);
        commitFiveBitValue(c, 0xC000, 0x01);
        // In 4KB CHR mode, CHR bank 1 register controls $1000-$1FFF.
        assertEquals(0x1000, c.ppuMapRead(0x1000));

        // $E000-$FFFF → PRG bank
        MapperMMC1 d = new MapperMMC1(4, 1);
        // Default Control is mode 3 (low switchable, high fixed-to-last).
        commitFiveBitValue(d, 0xE000, 0x02);
        assertEquals(0x02 * 0x4000, d.cpuMapRead(0x8000));
    }

    // =====================================================================
    // C2 — Control register
    // =====================================================================

    /**
     * Bits 0-1 of the Control register select mirroring:
     * 00=ONESCREEN_LO, 01=ONESCREEN_HI, 10=VERTICAL, 11=HORIZONTAL.
     * Walk all four values; verify {@link MapperMMC1#mirror()}.
     */
    @Test
    void control_bits0_1_selectMirroringAcrossAllFourModes() {
        // Each test starts from a fresh mapper. Control bits 2-3 / 4 are
        // zero in each value so they don't affect mirror().
        MapperMMC1 lo = new MapperMMC1(4, 1);
        writeControl(lo, 0b00000);
        assertEquals(Mapper.Mirror.ONESCREEN_LO, lo.mirror());

        MapperMMC1 hi = new MapperMMC1(4, 1);
        writeControl(hi, 0b00001);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, hi.mirror());

        MapperMMC1 vert = new MapperMMC1(4, 1);
        writeControl(vert, 0b00010);
        assertEquals(Mapper.Mirror.VERTICAL, vert.mirror());

        MapperMMC1 horz = new MapperMMC1(4, 1);
        writeControl(horz, 0b00011);
        assertEquals(Mapper.Mirror.HORIZONTAL, horz.mirror());
    }

    /**
     * Control bits 2-3 select PRG mode. Verify by observing the PRG
     * window mapping ($8000 / $C000) across all four modes.
     *
     * <p>Mode encoding:
     * <ul>
     *   <li>00 / 01: 32KB switchable at $8000-$FFFF (low bit of PRG-bank reg ignored)</li>
     *   <li>10:      16KB switchable at $C000-$FFFF; $8000-$BFFF FIXED to FIRST bank</li>
     *   <li>11:      16KB switchable at $8000-$BFFF; $C000-$FFFF FIXED to LAST bank</li>
     * </ul>
     */
    @Test
    void control_bits2_3_setPrgMode() {
        // Mode 0 (32KB switchable) with PRG bank 0:
        // $8000 → bank-pair 0 offset 0, $C000 → bank-pair 0 offset 0x4000.
        MapperMMC1 m0 = new MapperMMC1(8, 1);
        writeControl(m0, 0b00000);  // mirror=00, prg-mode=00, chr-mode=0
        assertEquals(0x0000, m0.cpuMapRead(0x8000));
        assertEquals(0x4000, m0.cpuMapRead(0xC000));

        // Mode 1 (32KB switchable) — same behavior as 0.
        MapperMMC1 m1 = new MapperMMC1(8, 1);
        writeControl(m1, 0b00100);  // prg-mode=01
        assertEquals(0x0000, m1.cpuMapRead(0x8000));
        assertEquals(0x4000, m1.cpuMapRead(0xC000));

        // Mode 2 (16KB high-switchable, low fixed to first):
        // $8000 → 0 (first bank), $C000 → prgBank * 16KB (=0 here too).
        MapperMMC1 m2 = new MapperMMC1(8, 1);
        writeControl(m2, 0b01000);  // prg-mode=10
        assertEquals(0x0000, m2.cpuMapRead(0x8000));
        assertEquals(0x0000, m2.cpuMapRead(0xC000));

        // Mode 3 (16KB low-switchable, high fixed to last):
        // $8000 → prgBank * 16KB (= 0), $C000 → last bank (index 7) * 16KB.
        MapperMMC1 m3 = new MapperMMC1(8, 1);
        writeControl(m3, 0b01100);  // prg-mode=11
        assertEquals(0x0000, m3.cpuMapRead(0x8000));
        assertEquals(7 * 0x4000, m3.cpuMapRead(0xC000));
    }

    /**
     * Control bit 4 selects CHR mode (0=8KB, 1=4KB). At a high level
     * we just verify that flipping the bit changes how the CHR bank
     * registers are interpreted; concrete bank arithmetic is C3.
     */
    @Test
    void control_bit4_selectsChrMode_8kVs4k() {
        // Put a known CHR bank 0 = 0x02 into the register first.
        // In 8KB mode the low bit is masked → 8KB bank index = 2 >> 1 = 1.
        // In 4KB mode the bank is used as-is → 4KB bank index = 2.
        MapperMMC1 cart = new MapperMMC1(2, 4);
        // Set Control = mode 3 (default-ish) + CHR mode 0 (8KB). chrBank0 = 2.
        writeControl(cart, 0b01100);    // mirror=00 (LO), prg=11, chr=0
        commitFiveBitValue(cart, 0xA000, 0x02);

        // 8KB mode at $0000: 8KB bank 1 * 0x2000 = 0x2000.
        assertEquals(0x2000, cart.ppuMapRead(0x0000));

        // Flip to CHR 4KB mode. Control = same as above with bit 4 set.
        writeControl(cart, 0b11100);    // prg=11, chr=1
        // 4KB bank 2 at $0000 → 2 * 0x1000 = 0x2000.
        assertEquals(0x2000, cart.ppuMapRead(0x0000));
        // Top half at $1000 uses CHR bank 1 register (still default 0) → 0.
        assertEquals(0x0000, cart.ppuMapRead(0x1000));
    }

    /**
     * Control register can be REWRITTEN with subsequent 5-write commits;
     * the registers don't latch permanently. Verify by switching modes
     * back to back and observing both reflect.
     */
    @Test
    void control_canBeRewritten_byNewFiveWriteSequence() {
        MapperMMC1 m = new MapperMMC1(4, 1);
        // First commit: mirror = HORIZONTAL (bits 0-1 = 11).
        writeControl(m, 0b00011);
        assertEquals(Mapper.Mirror.HORIZONTAL, m.mirror());

        // Second commit: mirror = VERTICAL (bits 0-1 = 10).
        writeControl(m, 0b00010);
        assertEquals(Mapper.Mirror.VERTICAL, m.mirror());

        // Third commit: mirror = ONESCREEN_LO (bits 0-1 = 00).
        writeControl(m, 0b00000);
        assertEquals(Mapper.Mirror.ONESCREEN_LO, m.mirror());
    }

    // =====================================================================
    // C3 — CHR bank registers
    // =====================================================================

    /**
     * 4KB CHR mode: CHR bank 0 register controls $0000-$0FFF, CHR
     * bank 1 controls $1000-$1FFF independently.
     */
    @Test
    void chr4kMode_chrBank0_controls_lowHalfOnly() {
        MapperMMC1 m = new MapperMMC1(2, 8);  // 8 CHR banks of 4KB
        writeControl(m, 0b10000);  // chr-mode = 1 (4KB)
        commitFiveBitValue(m, 0xA000, 0x03);  // CHR bank 0 = 3

        // $0000 → bank 3 * 4KB = 0x3000.
        assertEquals(0x3000, m.ppuMapRead(0x0000));
        assertEquals(0x3000 + 0x0FFF, m.ppuMapRead(0x0FFF));
        // $1000 uses CHR bank 1 (still 0) → 0x0000.
        assertEquals(0x0000, m.ppuMapRead(0x1000));
    }

    @Test
    void chr4kMode_chrBank1_controls_highHalfOnly() {
        MapperMMC1 m = new MapperMMC1(2, 8);
        writeControl(m, 0b10000);  // chr-mode = 1
        commitFiveBitValue(m, 0xC000, 0x05);  // CHR bank 1 = 5

        // $0000 → bank 0 (default for CHR bank 0).
        assertEquals(0x0000, m.ppuMapRead(0x0000));
        // $1000 → bank 5 * 4KB.
        assertEquals(0x5000, m.ppuMapRead(0x1000));
        assertEquals(0x5000 + 0x0FFF, m.ppuMapRead(0x1FFF));
    }

    @Test
    void chr4kMode_bothBanks_setIndependently() {
        MapperMMC1 m = new MapperMMC1(2, 8);
        writeControl(m, 0b10000);  // chr-mode = 1
        commitFiveBitValue(m, 0xA000, 0x02);
        commitFiveBitValue(m, 0xC000, 0x07);

        assertEquals(0x2000, m.ppuMapRead(0x0000));
        assertEquals(0x7000, m.ppuMapRead(0x1000));
    }

    /**
     * 8KB CHR mode: CHR bank 0 holds the 8KB bank index; low bit is
     * ignored; CHR bank 1 register has no effect.
     */
    @Test
    void chr8kMode_chrBank0_lowBitIgnored_eightKbBank() {
        MapperMMC1 m = new MapperMMC1(2, 4);  // 4 CHR banks of 8KB = 8 banks of 4KB
        writeControl(m, 0b00000);  // chr-mode = 0 (8KB)
        // CHR bank 0 = 0x02 → 8KB bank (2 >> 1) = 1 → offset 0x2000.
        commitFiveBitValue(m, 0xA000, 0x02);
        assertEquals(0x2000, m.ppuMapRead(0x0000));
        assertEquals(0x2000 + 0x1FFF, m.ppuMapRead(0x1FFF));

        // CHR bank 0 = 0x03 — low bit ignored → same 8KB bank (3 >> 1 = 1).
        commitFiveBitValue(m, 0xA000, 0x03);
        assertEquals(0x2000, m.ppuMapRead(0x0000));
    }

    @Test
    void chr8kMode_chrBank1_isIgnored() {
        MapperMMC1 m = new MapperMMC1(2, 4);
        writeControl(m, 0b00000);  // chr-mode = 0 (8KB)
        // Set CHR bank 0 to a known value; CHR bank 1 to something else.
        commitFiveBitValue(m, 0xA000, 0x04);  // 8KB bank (4>>1) = 2
        commitFiveBitValue(m, 0xC000, 0x1F);  // chr bank 1 = max — should be ignored

        // $0000 → 8KB bank 2 → offset 0x4000.
        assertEquals(0x4000, m.ppuMapRead(0x0000));
        // $1000 also uses 8KB CHR bank 0 (same 8KB window) → 0x4000 + 0x1000.
        assertEquals(0x5000, m.ppuMapRead(0x1000));
    }

    @Test
    void chr8kMode_switching_acrossAllAvailableBanks() {
        MapperMMC1 m = new MapperMMC1(2, 8);  // 8 banks of 8KB
        writeControl(m, 0b00000);  // chr-mode = 0

        // For each 8KB bank in [0..7], write CHR bank 0 = (bank << 1)
        // (low bit forced to 0 to keep impl bit-shift unambiguous).
        for (int bank = 0; bank < 8; bank++) {
            commitFiveBitValue(m, 0xA000, bank << 1);
            assertEquals(bank * 0x2000, m.ppuMapRead(0x0000),
                    "8KB bank " + bank + " base mismatch");
            assertEquals(bank * 0x2000 + 0x1FFF, m.ppuMapRead(0x1FFF),
                    "8KB bank " + bank + " top mismatch");
        }
    }

    @Test
    void chr4kMode_outOfRange_returnsUNMAPPED() {
        MapperMMC1 m = new MapperMMC1(2, 8);
        writeControl(m, 0b10000);
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x2000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x3FFF));
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(-1));
    }

    @Test
    void ppuMapWrite_chrRomCart_returnsUNMAPPED() {
        MapperMMC1 m = new MapperMMC1(2, 8);
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x0000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_chrRamCart_passesThroughInRange_andUnmappedOutside() {
        MapperMMC1 m = new MapperMMC1(2, 0);  // nCHRBanks = 0 → CHR-RAM
        assertEquals(0x0000, m.ppuMapWrite(0x0000));
        assertEquals(0x1FFF, m.ppuMapWrite(0x1FFF));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x2000));
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Issue five writes to {@code address}, each carrying the next bit
     * of {@code value5} (LSB first). After the 5th, the mapper commits.
     */
    private static void commitFiveBitValue(MapperMMC1 m, int address, int value5) {
        for (int i = 0; i < 5; i++) {
            int bit = (value5 >> i) & 1;
            m.cpuMapWrite(address, bit);
        }
    }

    /** Commit a 5-bit value to the Control register ($8000-$9FFF). */
    private static void writeControl(MapperMMC1 m, int value5) {
        commitFiveBitValue(m, 0x8000, value5);
    }

    /** Commit a 5-bit value to the PRG bank register ($E000-$FFFF). */
    private static void writePrgBank(MapperMMC1 m, int value5) {
        commitFiveBitValue(m, 0xE000, value5);
    }
}
