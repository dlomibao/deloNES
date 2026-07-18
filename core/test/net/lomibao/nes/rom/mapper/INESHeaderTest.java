package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link INESHeader}, focusing on mapper-number derivation:
 * NES 2.0 detection, the DiskDude (iNES 0.7) workaround, and clean iNES 1.0
 * headers.
 */
class INESHeaderTest {

    /** Builds a minimal 16-byte iNES 1.0 header for the given mapper number. */
    private static byte[] makeHeader(int mapperNumber) {
        byte[] h = new byte[16];
        // Magic: "NES\x1A"
        h[0] = 0x4E; // 'N'
        h[1] = 0x45; // 'E'
        h[2] = 0x53; // 'S'
        h[3] = 0x1A; // MS-DOS EOF
        h[4] = 0x01; // 1 x 16KB PRG-ROM
        h[5] = 0x01; // 1 x  8KB CHR-ROM
        h[6] = (byte) ((mapperNumber & 0x0F) << 4);
        h[7] = (byte) (mapperNumber & 0xF0);
        return h;
    }

    // ---------------------------------------------------------------------------
    // Clean iNES 1.0 vectors
    // ---------------------------------------------------------------------------

    @Test
    void mapper0_cleanHeader_decodes() {
        INESHeader header = new INESHeader(makeHeader(0));
        assertEquals(0, header.getMapperNumber());
        assertFalse(header.isNES2Format());
    }

    @Test
    void mapper66_cleanHeader_decodes() {
        // Mapper 66 = 0x42 ⇒ byte6 high nibble = 2, byte7 high nibble = 4.
        INESHeader header = new INESHeader(makeHeader(66));
        assertEquals(66, header.getMapperNumber());
    }

    // ---------------------------------------------------------------------------
    // DiskDude (iNES 0.7) workaround
    // ---------------------------------------------------------------------------

    /**
     * Headers from old dumpers occasionally stash a signature ("DiskDude!",
     * "DUDE", etc.) in bytes 7-15. Byte 7's high nibble is then garbage and must
     * be ignored. Per NESdev: if any of bytes 12-15 are non-zero, zero out
     * byte 7's high nibble before computing the mapper.
     */
    @Test
    void diskDude_zeroesByte7HighNibble_resolvesToMapper0() {
        byte[] h = makeHeader(0);
        h[7] = (byte) 0xF0;        // garbage upper nibble
        h[12] = (byte) 'D';
        h[13] = (byte) 'U';
        h[14] = (byte) 'D';
        h[15] = (byte) 'E';

        INESHeader header = new INESHeader(h);
        assertEquals(0, header.getMapperNumber(),
                "DiskDude-tagged NROM must decode to mapper 0");
    }

    /**
     * Guard: without the workaround the naïve formula would yield a bogus
     * mapper number with byte 7's high nibble bleeding into the result.
     */
    @Test
    void diskDude_naiveFormula_isBogus() {
        byte[] h = makeHeader(0);
        h[7] = (byte) 0xF0;
        h[12] = (byte) 'D';
        int naive = ((h[6] >> 4) & 0x0F) | (h[7] & 0xF0);
        assertEquals(0xF0, naive,
                "Naïve formula must produce a mapper number with byte7 high nibble set");
    }

    /**
     * Even a single non-zero byte in 12-15 should trigger the workaround.
     */
    @Test
    void diskDude_singleNonZeroByte_triggersWorkaround() {
        byte[] h = makeHeader(0);
        h[7] = (byte) 0xF0;
        h[14] = 0x01; // single non-zero byte
        INESHeader header = new INESHeader(h);
        assertEquals(0, header.getMapperNumber());
    }

    // ---------------------------------------------------------------------------
    // NES 2.0
    // ---------------------------------------------------------------------------

    /**
     * NES 2.0 signature is byte7 bits 2-3 == 0b10. {@link INESHeader#isNES2Format()}
     * must report {@code true}.
     */
    @Test
    void nes2Signature_isDetected() {
        byte[] h = makeHeader(0);
        h[7] = (byte) ((h[7] & 0xF3) | 0x08);
        INESHeader header = new INESHeader(h);
        assertTrue(header.isNES2Format(),
                "byte7 & 0x0C == 0x08 must be flagged as NES 2.0");
    }

    /**
     * For NES 2.0 headers we must NOT apply the iNES 0.7 DiskDude workaround,
     * since bytes 12-15 carry meaningful NES 2.0 fields (PRG/CHR RAM sizes,
     * timing). Verify byte 7's high nibble is preserved even with bytes 12-15
     * non-zero, so callers see the iNES-1.0-compatible mapper bits as-is.
     * (Full NES 2.0 mapper resolution would additionally consult byte 8; at
     * minimum the iNES-1.0 bits must not be zeroed.)
     */
    @Test
    void nes2Header_doesNotApplyDiskDudeWorkaround() {
        byte[] h = makeHeader(0);
        // NES 2.0 signature (bits 2-3 = 0b10) + high nibble 0xF.
        h[7] = (byte) (((h[7] & 0xF3) | 0x08) | 0xF0);
        h[12] = 0x01;
        h[13] = 0x02;
        INESHeader header = new INESHeader(h);
        assertTrue(header.isNES2Format());
        assertEquals(0xF0, header.getMapperNumber() & 0xF0,
                "NES 2.0 must keep byte 7's high nibble even when bytes 12-15 are non-zero");
    }

    // ---------------------------------------------------------------------------
    // Flag accessors — straight bit reads. Cover both states.
    // ---------------------------------------------------------------------------

    @Test
    void prgAndChrRomSizes_readByte4AndByte5() {
        byte[] h = makeHeader(0);
        h[4] = 0x08; // 8 x 16KB
        h[5] = 0x02; // 2 x 8KB
        INESHeader header = new INESHeader(h);
        assertEquals(8, header.getPRGROMSize());
        assertEquals(8, header.getSizeOfPRGRom());
        assertEquals(2, header.getCHRROMSize());
    }

    @Test
    void flags6_mirroringAndBatteryAndTrainerAndFourScreen() {
        byte[] h = makeHeader(0);
        h[6] = (byte) 0x0F; // all four low bits set
        INESHeader header = new INESHeader(h);
        assertTrue(header.isHorizontalMirroring());
        assertTrue(header.hasBatteryBackedRAM());
        assertTrue(header.hasTrainer());
        assertTrue(header.isFourScreenVRAM());
        assertEquals(0x0F, header.getFlags6());
    }

    @Test
    void flags6_allClear_reportsFalse() {
        byte[] h = makeHeader(0); // makeHeader clears flags
        INESHeader header = new INESHeader(h);
        assertFalse(header.isHorizontalMirroring());
        assertFalse(header.hasBatteryBackedRAM());
        assertFalse(header.hasTrainer());
        assertFalse(header.isFourScreenVRAM());
    }

    @Test
    void flags7_vsUnisystemAndPlayChoice10() {
        byte[] h = makeHeader(0);
        h[7] = 0x03; // bits 0 and 1
        INESHeader header = new INESHeader(h);
        assertTrue(header.isVSUnisystem());
        assertTrue(header.isPlayChoice10());
        assertEquals(0x03, header.getFlags7());
    }

    @Test
    void flags8_through10_readPRGRAMAndPALAndPRGRAMPresent() {
        byte[] h = makeHeader(0);
        h[8] = 0x04;
        h[9] = 0x01;  // PAL bit
        h[10] = 0x10; // PRG-RAM present bit
        INESHeader header = new INESHeader(h);
        assertEquals(4, header.getPRGRAMSize());
        assertEquals(4, header.getFlags8());
        assertTrue(header.isPAL());
        assertEquals(1, header.getFlags9());
        assertTrue(header.hasPRGRAMPresent());
        assertEquals(0x10, header.getFlags10());
    }

    @Test
    void getHeaderBytes_returnsBackingArray_andPrintHeaderBytesIsNoOp() {
        byte[] h = makeHeader(0);
        INESHeader header = new INESHeader(h);
        assertSame(h, header.getHeaderBytes());
        // printHeaderBytes is intentionally a no-op; call it to register
        // coverage and assert nothing throws.
        header.printHeaderBytes();
    }

    // ---------------------------------------------------------------------------
    // NES 2.0 — detection matrix, 12-bit mapper, submapper
    // ---------------------------------------------------------------------------

    /** Stamps the NES 2.0 signature (byte7 bits 2-3 = 0b10) onto a header. */
    private static byte[] makeNes2Header(int mapperNumber) {
        byte[] h = makeHeader(mapperNumber);
        h[7] = (byte) ((h[7] & 0xF3) | 0x08);
        return h;
    }

    /** Only byte7 bits 2-3 == 0b10 means NES 2.0 — 0b00, 0b01, 0b11 do not. */
    @Test
    void nes2Detection_matrix() {
        for (int bits = 0; bits <= 3; bits++) {
            byte[] h = makeHeader(0);
            h[7] = (byte) (bits << 2);
            assertEquals(bits == 2, new INESHeader(h).isNES2Format(),
                    "byte7 bits 2-3 = " + bits);
        }
    }

    /**
     * NES 2.0 mapper is 12-bit: byte 8's low nibble contributes bits 8-11.
     * Mapper 0x105 = 261 from byte6 nibble 5, byte7 nibble 0, byte8 low nibble 1.
     */
    @Test
    void nes2MapperNumber_includesByte8LowNibble() {
        byte[] h = makeNes2Header(5);
        h[8] = 0x01;
        assertEquals(261, new INESHeader(h).getMapperNumber());
    }

    /**
     * Guard for the most dangerous line of the NES 2.0 change: iNES 1.0 uses
     * byte 8 for PRG-RAM size — its bits must NEVER leak into the mapper
     * number of a 1.0 header.
     */
    @Test
    void ines10_nonZeroByte8_doesNotCorruptMapperNumber() {
        byte[] h = makeHeader(0);
        h[8] = 0x04; // 4 x 8KB PRG-RAM — NOT mapper bits
        assertEquals(0, new INESHeader(h).getMapperNumber(),
                "iNES 1.0 byte 8 (PRG-RAM size) must not contribute mapper bits");
    }

    /** Submapper is byte 8's high nibble; values >= 8 must not sign-extend. */
    @Test
    void nes2Submapper_highValues_noSignExtension() {
        byte[] h = makeNes2Header(0);
        h[8] = (byte) 0xF0; // submapper 15, mapper MSB nibble 0
        INESHeader header = new INESHeader(h);
        assertEquals(15, header.getSubmapper());
        assertEquals(0, header.getMapperNumber());
    }

    @Test
    void ines10_submapperIsAlwaysZero() {
        byte[] h = makeHeader(0);
        h[8] = (byte) 0xF0;
        assertEquals(0, new INESHeader(h).getSubmapper());
    }

    // ---------------------------------------------------------------------------
    // NES 2.0 — ROM sizes (unit form, exponent form, caps)
    // ---------------------------------------------------------------------------

    @Test
    void ines10_romSizeBytes_fromLsbTimesBankSize() {
        byte[] h = makeHeader(0);
        h[4] = 0x02; // 2 x 16KB
        h[5] = 0x01; // 1 x 8KB
        INESHeader header = new INESHeader(h);
        assertEquals(32768, header.getPRGROMSizeBytes());
        assertEquals(8192, header.getCHRROMSizeBytes());
    }

    @Test
    void nes2_unitForm_includesByte9MsbNibbles() {
        byte[] h = makeNes2Header(0);
        h[4] = 0x00;
        h[5] = 0x02;
        h[9] = 0x01; // PRG MSB nibble = 1 (units 0x100 = 256), CHR MSB nibble = 0
        INESHeader header = new INESHeader(h);
        assertEquals(256 * 16384, header.getPRGROMSizeBytes());
        assertEquals(2 * 8192, header.getCHRROMSizeBytes());
    }

    /** Exponent form: 2^E * (M*2+1). E=15, M=1 ⇒ 32768 * 3 = 98304 bytes. */
    @Test
    void nes2_exponentForm_decodes() {
        byte[] h = makeNes2Header(0);
        h[9] = 0x0F;              // PRG MSB nibble 0xF ⇒ exponent form
        h[4] = (byte) ((15 << 2) | 1); // E=15, M=1
        assertEquals(98304, new INESHeader(h).getPRGROMSizeBytes());
    }

    @Test
    void nes2_exponentForm_overCap_throwsNamingSize() {
        byte[] h = makeNes2Header(0);
        h[9] = 0x0F;
        h[4] = (byte) 0xFC; // E=63, M=0 ⇒ 2^63
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new INESHeader(h).getPRGROMSizeBytes());
        assertTrue(e.getMessage().contains("cap"), e.getMessage());
    }

    /**
     * Unit form maxes out below the cap by construction (0xEFF units:
     * ~63 MB PRG / ~31 MB CHR) — the largest encodable values must decode,
     * not throw.
     */
    @Test
    void nes2_unitForm_maxSizes_decodeUnderCap() {
        byte[] h = makeNes2Header(0);
        h[4] = (byte) 0xFF;
        h[9] = 0x0E; // PRG: 0xEFF units * 16KB ≈ 60MB
        assertEquals(0xEFF * 16384, new INESHeader(h).getPRGROMSizeBytes());
        byte[] h2 = makeNes2Header(0);
        h2[5] = (byte) 0xFF;
        h2[9] = (byte) 0xE0; // CHR: 0xEFF units * 8KB ≈ 30MB
        assertEquals(0xEFF * 8192, new INESHeader(h2).getCHRROMSizeBytes());
    }

    /**
     * Sub-bank exponent sizes (e.g. 8KB PRG: E=13) are rejected — the mapper
     * layer is bank-count based. Documented limitation (DECISIONS.md D2).
     */
    @Test
    void nes2_exponentForm_subBankSize_throws() {
        byte[] h = makeNes2Header(0);
        h[9] = 0x0F;
        h[4] = (byte) (13 << 2); // E=13, M=0 ⇒ 8192 bytes < 16KB PRG bank
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new INESHeader(h).getPRGROMSizeBytes());
        assertTrue(e.getMessage().contains("8192"), e.getMessage());
    }

    // ---------------------------------------------------------------------------
    // NES 2.0 — RAM shifts, timing, console type, misc fields
    // ---------------------------------------------------------------------------

    /** Shift 0 means NO RAM — not 64 << 0 = 64 bytes. */
    @Test
    void nes2_ramShiftZero_meansNone() {
        byte[] h = makeNes2Header(0);
        INESHeader header = new INESHeader(h);
        assertEquals(0, header.getPRGRAMSizeBytes());
        assertEquals(0, header.getCHRRAMSizeBytes());
    }

    @Test
    void nes2_ramShifts_decode() {
        byte[] h = makeNes2Header(0);
        h[10] = (byte) 0x97; // PRG-RAM shift 7 = 8KB, PRG-NVRAM shift 9 = 32KB
        h[11] = (byte) 0x59; // CHR-RAM shift 9 = 32KB, CHR-NVRAM shift 5 = 2KB
        INESHeader header = new INESHeader(h);
        assertEquals(8192, header.getPRGRAMSizeBytes());
        assertEquals(32768, header.getPRGNVRAMSizeBytes());
        assertEquals(32768, header.getCHRRAMSizeBytes());
        assertEquals(2048, header.getCHRNVRAMSizeBytes());
    }

    @Test
    void ines10_ramSizeBytesAccessors_returnZero() {
        byte[] h = makeHeader(0);
        h[10] = (byte) 0x97;
        h[11] = (byte) 0x59;
        INESHeader header = new INESHeader(h);
        assertEquals(0, header.getPRGRAMSizeBytes());
        assertEquals(0, header.getCHRRAMSizeBytes());
    }

    @Test
    void timing_bothFormats() {
        // iNES 1.0: byte 9 bit 0
        byte[] h10 = makeHeader(0);
        assertEquals(INESHeader.TvTiming.NTSC, new INESHeader(h10).getTimingMode());
        h10[9] = 0x01;
        assertEquals(INESHeader.TvTiming.PAL, new INESHeader(h10).getTimingMode());

        // NES 2.0: byte 12 bits 0-1
        INESHeader.TvTiming[] expected = {
                INESHeader.TvTiming.NTSC, INESHeader.TvTiming.PAL,
                INESHeader.TvTiming.MULTI, INESHeader.TvTiming.DENDY};
        for (int t = 0; t <= 3; t++) {
            byte[] h = makeNes2Header(0);
            h[12] = (byte) t;
            assertEquals(expected[t], new INESHeader(h).getTimingMode(), "timing " + t);
        }
    }

    @Test
    void consoleType_readsByte7LowBits() {
        for (int type = 0; type <= 3; type++) {
            byte[] h = makeHeader(0);
            h[7] = (byte) type;
            assertEquals(type, new INESHeader(h).getConsoleType());
        }
    }

    /**
     * DiskDude-tagged 1.0 headers: all of byte 7 is signature garbage, so
     * the console-type bits get the same leniency as the mapper high
     * nibble — a dirty old NROM dump must not be rejected as "VS System".
     */
    @Test
    void consoleType_diskDudeHeader_lenientlyReportsNes() {
        byte[] h = makeHeader(0);
        h[7] = (byte) 0xF1;  // garbage incl. VS bit
        h[12] = (byte) 'D';
        h[13] = (byte) 'U';
        h[14] = (byte) 'D';
        h[15] = (byte) 'E';
        assertEquals(0, new INESHeader(h).getConsoleType(),
                "DiskDude garbage in byte 7 must not read as a console type");
    }

    /**
     * Legacy accessors must not read NES 2.0 bytes with iNES 1.0 semantics:
     * byte 8 is mapper/submapper, byte 9 is size MSBs, byte 10 is RAM shifts.
     */
    @Test
    void nes2_legacyAccessors_areFormatAware() {
        byte[] h = makeNes2Header(0);
        h[8] = 0x04;  // would be "4 x 8KB PRG-RAM" in iNES 1.0 — here submapper 0/mapper MSB 4
        h[9] = 0x00;
        h[10] = 0x10; // would be "PRG-RAM present" in iNES 1.0 — here NVRAM shift 1
        h[12] = 0x00; // NTSC
        INESHeader header = new INESHeader(h);
        assertEquals(0, header.getPRGRAMSize(), "legacy PRG-RAM units must be 0 under NES 2.0");
        assertFalse(header.isPAL(), "PAL must come from byte 12 under NES 2.0");
        assertFalse(header.hasPRGRAMPresent(),
                "presence must derive from the byte-10 PRG-RAM shift, which is 0");

        byte[] hPal = makeNes2Header(0);
        hPal[9] = 0x01; // size MSB nibble, NOT the 1.0 PAL bit
        hPal[12] = 0x01; // PAL
        INESHeader palHeader = new INESHeader(hPal);
        assertTrue(palHeader.isPAL());
        byte[] hRam = makeNes2Header(0);
        hRam[10] = 0x07; // PRG-RAM shift 7 = 8KB
        assertTrue(new INESHeader(hRam).hasPRGRAMPresent());
    }

    @Test
    void nes2_miscRomCountAndExpansionDevice() {
        byte[] h = makeNes2Header(0);
        h[14] = 0x02;
        h[15] = 0x01; // standard controllers
        INESHeader header = new INESHeader(h);
        assertEquals(2, header.getMiscRomCount());
        assertEquals(1, header.getDefaultExpansionDevice());

        byte[] h10 = makeHeader(0);
        h10[14] = 0x02;
        h10[15] = 0x01;
        INESHeader legacy = new INESHeader(h10);
        assertEquals(0, legacy.getMiscRomCount());
        assertEquals(0, legacy.getDefaultExpansionDevice());
    }
}
