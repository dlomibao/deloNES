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
     * Mapper 1 (MMC1): byte6 upper nibble = 1, byte7 upper nibble = 0.
     * Expected mapper = 1 — unsupported, validation must fail.
     */
    @Test
    void mapperComputation_mapper1_fails() {
        byte[] h = makeHeader(1);
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid(), "Mapper 1 should not be supported");
        assertEquals(1, r.getDetectedMapper());
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("1"), r.getErrorMessage());
    }

    /**
     * Mapper 4 (MMC3): low nibble 4 in upper nibble of byte 6.
     * Expected mapper = 4 — unsupported.
     */
    @Test
    void mapperComputation_mapper4_fails() {
        byte[] h = makeHeader(4);
        iNESHeaderValidator.ValidationResult r = iNESHeaderValidator.validate(h);
        assertFalse(r.isValid());
        assertEquals(4, r.getDetectedMapper());
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
}
