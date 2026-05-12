package net.lomibao.nes.desktop.screen;

/**
 * Validates the first 16 bytes of an iNES ROM header before loading.
 *
 * <p>Performs three checks:
 * <ol>
 *   <li>Magic bytes — bytes 0–3 must equal {@code 0x4E 0x45 0x53 0x1A} ("NES\x1A").</li>
 *   <li>NES 2.0 detection — if {@code (byte7 & 0x0C) == 0x08} the file is NES 2.0,
 *       which we do not yet support and reject with a specific error.</li>
 *   <li>Mapper number — derived from bytes 6 and 7 using the standard iNES 1.0 formula,
 *       with the DiskDude workaround applied (see {@link #validate(byte[])}); only
 *       mapper 0 (NROM) is currently supported.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   iNESHeaderValidator.ValidationResult r =
 *       iNESHeaderValidator.validate(first16Bytes);
 *   if (!r.isValid()) { showError(r.getErrorMessage()); }
 * </pre>
 */
public class iNESHeaderValidator {

    /** Expected iNES magic: "NES" followed by MS-DOS EOF byte 0x1A. */
    private static final byte[] INES_MAGIC = {0x4E, 0x45, 0x53, 0x1A};

    private iNESHeaderValidator() {}

    /**
     * Validates {@code headerBytes} (must be at least 16 bytes).
     *
     * <p>Mapper-number derivation follows the iNES spec on the
     * <a href="https://www.nesdev.org/wiki/INES">NESdev wiki</a>:
     * <ul>
     *   <li>If {@code (byte7 & 0x0C) == 0x08} the file is NES 2.0 — bail out with a
     *       specific error. (The mapper number in NES 2.0 also draws bits from byte 8,
     *       so the iNES 1.0 formula below is not safe to apply to such files.)</li>
     *   <li>If any of bytes 12–15 are non-zero, the header was likely written by an
     *       old tool that stashed a signature like {@code "DiskDude!"} in bytes 7–15.
     *       In that case byte 7's high nibble is garbage; the
     *       <a href="https://www.nesdev.org/wiki/INES#Flags_7">NESdev convention</a> is
     *       to zero it out before computing the mapper. Without this workaround, valid
     *       NROM dumps are misread as "mapper {@code 0xF...}" and rejected.</li>
     *   <li>Otherwise apply the iNES 1.0 formula:
     *       {@code mapper = (byte6 >> 4) | (byte7 & 0xF0)}.</li>
     * </ul>
     *
     * @param headerBytes the first 16 bytes of the ROM file
     * @return a {@link ValidationResult} describing pass/fail
     */
    public static ValidationResult validate(byte[] headerBytes) {
        if (headerBytes == null || headerBytes.length < 16) {
            return ValidationResult.failure(-1, "Header too short: need at least 16 bytes");
        }

        // Check magic bytes
        for (int i = 0; i < INES_MAGIC.length; i++) {
            if (headerBytes[i] != INES_MAGIC[i]) {
                return ValidationResult.failure(-1,
                        "Not a valid iNES file: bad magic bytes at offset " + i
                        + " (expected 0x" + Integer.toHexString(INES_MAGIC[i] & 0xFF).toUpperCase()
                        + ", got 0x" + Integer.toHexString(headerBytes[i] & 0xFF).toUpperCase() + ")");
            }
        }

        // NES 2.0 detection: per NESdev wiki, bits 2-3 of byte 7 equal 0b10 ⇒ NES 2.0.
        // Mapper bits also come from byte 8 in that format, so the iNES 1.0 formula
        // would silently misidentify the mapper. Reject explicitly.
        if ((headerBytes[7] & 0x0C) == 0x08) {
            return ValidationResult.failure(-1,
                    "NES 2.0 format not yet supported");
        }

        // DiskDude workaround: many old dumpers (iNES 0.7 era) stored a signature
        // such as "DiskDude!" in bytes 7-15. If bytes 12-15 are non-zero, byte 7's
        // high nibble is unreliable garbage and must be zeroed before computing the
        // mapper. See https://www.nesdev.org/wiki/INES#Flags_7
        int byte7HighNibble = headerBytes[7] & 0xF0;
        if (headerBytes[12] != 0 || headerBytes[13] != 0
                || headerBytes[14] != 0 || headerBytes[15] != 0) {
            byte7HighNibble = 0;
        }

        // iNES 1.0 mapper number:
        //   low nibble  = upper nibble of byte 6
        //   high nibble = upper nibble of byte 7 (possibly zeroed above)
        int mapperNumber = ((headerBytes[6] >> 4) & 0x0F) | byte7HighNibble;

        if (mapperNumber != 0) {
            return ValidationResult.failure(mapperNumber,
                    "Unsupported mapper " + mapperNumber + " — only NROM (mapper 0) is supported");
        }

        return ValidationResult.success(mapperNumber);
    }

    /**
     * Holds the outcome of a header validation.
     */
    public static final class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final int detectedMapper;

        private ValidationResult(boolean valid, String errorMessage, int detectedMapper) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.detectedMapper = detectedMapper;
        }

        static ValidationResult success(int mapperNumber) {
            return new ValidationResult(true, null, mapperNumber);
        }

        static ValidationResult failure(int mapperNumber, String message) {
            return new ValidationResult(false, message, mapperNumber);
        }

        /** @return {@code true} if the header passed all checks */
        public boolean isValid() {
            return valid;
        }

        /**
         * @return human-readable error description, or {@code null} when {@link #isValid()} is
         *         {@code true}
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * @return mapper number parsed from the header, or {@code -1} if the header was
         *         too short or had bad magic (mapper could not be determined)
         */
        public int getDetectedMapper() {
            return detectedMapper;
        }
    }
}
