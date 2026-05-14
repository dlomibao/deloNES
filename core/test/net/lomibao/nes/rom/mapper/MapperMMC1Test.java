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
