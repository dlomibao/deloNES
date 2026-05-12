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
}
