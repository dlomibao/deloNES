package net.lomibao.nes.desktop.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link iNESHeaderValidator}.
 */
class iNESHeaderValidatorTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Builds a minimal 16-byte iNES 1.0 header for the given mapper number. */
    private static byte[] makeHeader(int mapperNumber) {
        byte[] h = new byte[16];
        // Magic: "NES\x1A"
        h[0] = 0x4E; // 'N'
        h[1] = 0x45; // 'E'
        h[2] = 0x53; // 'S'
        h[3] = 0x1A; // MS-DOS EOF
        // PRG/CHR sizes — 1 bank each
        h[4] = 0x01;
        h[5] = 0x01;
        // Byte 6: low nibble of mapper in upper nibble
        h[6] = (byte) ((mapperNumber & 0x0F) << 4);
        // Byte 7: high nibble of mapper in upper nibble
        h[7] = (byte) (mapperNumber & 0xF0);
        return h;
    }

    // ---------------------------------------------------------------------------
    // Magic byte checks
    // ---------------------------------------------------------------------------

    @Test
    void validMapper0Header_passes() {
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(makeHeader(0));
        assertTrue(r.isValid(), "Mapper 0 with valid magic should pass");
        assertNull(r.getErrorMessage());
        assertEquals(0, r.getDetectedMapper());
    }

    @Test
    void badMagicFirstByte_fails() {
        byte[] h = makeHeader(0);
        h[0] = 0x00; // corrupt first magic byte
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid(), "Bad magic should fail");
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("magic"), r.getErrorMessage());
        assertEquals(-1, r.getDetectedMapper(), "Mapper should be -1 when magic is bad");
    }

    @Test
    void badMagicThirdByte_fails() {
        byte[] h = makeHeader(0);
        h[2] = 0x54; // 'T' instead of 'S'
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid());
    }

    @Test
    void tooShortHeader_fails() {
        byte[] h = new byte[8]; // only 8 bytes
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid());
        assertEquals(-1, r.getDetectedMapper());
    }

    @Test
    void nullHeader_fails() {
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(null);
        assertFalse(r.isValid());
        assertEquals(-1, r.getDetectedMapper());
    }

    // ---------------------------------------------------------------------------
    // Mapper number computation vectors
    // ---------------------------------------------------------------------------

    /**
     * Mapper 0 (NROM): byte6 upper nibble = 0, byte7 upper nibble = 0.
     * Expected mapper = 0.
     */
    @Test
    void mapperComputation_mapper0() {
        byte[] h = makeHeader(0);
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertTrue(r.isValid());
        assertEquals(0, r.getDetectedMapper());
    }

    /**
     * Every mapper Cartridge can construct must validate. Single source of
     * truth: {@link net.lomibao.nes.components.Cartridge#SUPPORTED_MAPPERS}.
     */
    @Test
    void allSupportedMappers_pass() {
        for (int mapperNumber : net.lomibao.nes.components.Cartridge.SUPPORTED_MAPPERS) {
            iNESHeaderValidator.ValidationResult r =
                    iNESHeaderValidator.validate(makeHeader(mapperNumber));
            assertTrue(r.isValid(), "Mapper " + mapperNumber + " should be supported: "
                    + r.getErrorMessage());
            assertEquals(mapperNumber, r.getDetectedMapper());
        }
    }

    /**
     * Mapper 5 (MMC5) is not implemented — validation must fail and the
     * message must name the mapper and list the supported set.
     */
    @Test
    void mapperComputation_mapper5_fails() {
        byte[] h = makeHeader(5);
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid(), "Mapper 5 should not be supported");
        assertEquals(5, r.getDetectedMapper());
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("5"), r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("30"),
                "Error should list the supported mappers, got: " + r.getErrorMessage());
    }

    /**
     * Mapper 66 (GxROM): high nibble 4 (0x40) in byte 7.
     * byte6 upper nibble = 2 (low 4 bits of 66 = 0x42 → nibble is 2 … wait:
     * 66 decimal = 0x42 → low nibble = 2, high nibble = 4.
     * byte6 bits[7:4] = low 4 bits of mapper = 0x2 → 0x20
     * byte7 bits[7:4] = high 4 bits of mapper = 0x4 → 0x40
     */
    @Test
    void mapperComputation_mapper66_fails() {
        byte[] h = makeHeader(66);
        // Verify computation: should decode to 66
        int mapperNumber = ((h[6] >> 4) & 0x0F) | (h[7] & 0xF0);
        assertEquals(66, mapperNumber, "Mapper number encoding check");

        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid());
        assertEquals(66, r.getDetectedMapper());
    }

    // ---------------------------------------------------------------------------
    // Edge case: exactly 16 bytes
    // ---------------------------------------------------------------------------

    @Test
    void exactly16Bytes_validMapper0_passes() {
        byte[] h = makeHeader(0);
        assertEquals(16, h.length);
        assertTrue(iNESHeaderValidator.validate(h).isValid());
    }

    // ---------------------------------------------------------------------------
    // DiskDude workaround + NES 2.0 detection (B2)
    // ---------------------------------------------------------------------------

    /**
     * "DiskDude!" path: legitimate NROM dump whose bytes 7-15 hold a stale tool
     * signature. Byte 7's high nibble is garbage (0xF0 here). Bytes 12-15 are
     * non-zero ("DUDE"), so the validator must zero out byte 7's high nibble
     * and resolve mapper = 0 (NROM) instead of a bogus high mapper number.
     */
    @Test
    void diskDude_zeroesByte7HighNibble_andAcceptsMapper0() {
        byte[] h = makeHeader(0);
        // Simulate a DiskDude-tagged header: byte 7 garbage + non-zero bytes 12-15.
        h[7] = (byte) 0xF0;       // high nibble garbage
        h[12] = (byte) 'D';
        h[13] = (byte) 'U';
        h[14] = (byte) 'D';
        h[15] = (byte) 'E';

        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertTrue(r.isValid(),
                "DiskDude-tagged NROM should validate: " + r.getErrorMessage());
        assertEquals(0, r.getDetectedMapper(),
                "Mapper must resolve to 0 (NROM), not include byte 7's high nibble");
    }

    /**
     * Without the DiskDude workaround, the naïve formula would treat byte 7's
     * high nibble as the mapper's high nibble: 0xF0 ⇒ bogus mapper 0xF0. This
     * guards against the fix being silently reverted.
     */
    @Test
    void diskDude_naiveFormula_isBogus() {
        byte[] h = makeHeader(0);
        h[7] = (byte) 0xF0;
        h[12] = (byte) 'D';

        int naive = ((h[6] >> 4) & 0x0F) | (h[7] & 0xF0);
        assertEquals(0xF0, naive,
                "Sanity check: naïve formula leaks byte 7's high nibble into the mapper");
    }

    /**
     * NES 2.0 headers (byte7 & 0x0C == 0x08) are accepted for every supported
     * mapper. This mirrors real modern dumps — e.g. the Micro Mages NROM demo
     * build ships bytes 4-7 = {@code 02 01 00 08}.
     */
    @Test
    void nes2Format_supportedMappers_pass() {
        for (int mapperNumber : net.lomibao.nes.components.Cartridge.SUPPORTED_MAPPERS) {
            byte[] h = makeHeader(mapperNumber);
            h[7] = (byte) ((h[7] & 0xF3) | 0x08);
            iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
            assertTrue(r.isValid(), "NES 2.0 mapper " + mapperNumber + " should pass: "
                    + r.getErrorMessage());
            assertEquals(mapperNumber, r.getDetectedMapper());
        }
    }

    /**
     * NES 2.0 12-bit mapper: byte 8's low nibble contributes bits 8-11, so an
     * unsupported high mapper number must be reported with its full value.
     * Mapper 0x105 = 261: byte6/7 nibbles encode 0x05, byte 8 low nibble = 1.
     */
    @Test
    void nes2Format_12BitMapper_reportedAndRejected() {
        byte[] h = makeHeader(5);
        h[7] = (byte) ((h[7] & 0xF3) | 0x08);
        h[8] = 0x01; // mapper bits 8-11
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid());
        assertEquals(261, r.getDetectedMapper(),
                "12-bit NES 2.0 mapper number must include byte 8's low nibble");
    }

    /**
     * VS System console type (byte 7 bit 0) is rejected — different PPU and
     * IO hardware. Applies to both header formats.
     */
    @Test
    void vsSystemConsoleType_rejected() {
        byte[] h = makeHeader(0);
        h[7] = (byte) (h[7] | 0x01);
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid(), "VS System ROM should be rejected");
        assertTrue(r.getErrorMessage().contains("console"),
                "Error should mention console type, got: " + r.getErrorMessage());
    }

    /**
     * NES 2.0 exponent-form size over the 64 MiB cap fails validation
     * gracefully (a clear message, not an exception escaping validate()).
     */
    @Test
    void nes2Format_overCapExponentSize_failsGracefully() {
        byte[] h = makeHeader(0);
        h[7] = (byte) ((h[7] & 0xF3) | 0x08);
        h[9] = 0x0F;         // PRG MSB nibble 0xF ⇒ exponent form
        h[4] = (byte) 0xFC;  // E = 63, M = 0 ⇒ 2^63 bytes
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid(), "2^63-byte PRG should be rejected");
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("cap"),
                "Error should mention the size cap, got: " + r.getErrorMessage());
    }

    /**
     * Clean iNES 1.0 regression: a normal NROM header with bytes 12-15 all zero
     * still resolves to mapper 0 and validates successfully.
     */
    @Test
    void cleanINes10_nromStillValid_regression() {
        byte[] h = makeHeader(0);
        // Bytes 12-15 are already zero from makeHeader; assert explicitly to make
        // the precondition visible.
        assertEquals(0, h[12]);
        assertEquals(0, h[13]);
        assertEquals(0, h[14]);
        assertEquals(0, h[15]);

        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertTrue(r.isValid());
        assertEquals(0, r.getDetectedMapper());
    }

    /**
     * Clean iNES 1.0 path for a non-zero mapper: bytes 12-15 zero ⇒ the high
     * nibble of byte 7 is honoured. Mapper 66 (high nibble 4) must still decode
     * to 66 even after the DiskDude check is added.
     */
    @Test
    void cleanINes10_mapper66_stillDecodes() {
        byte[] h = makeHeader(66);
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid(), "Mapper 66 is unsupported");
        assertEquals(66, r.getDetectedMapper(),
                "Mapper number must still decode to 66 when bytes 12-15 are zero");
    }
}
