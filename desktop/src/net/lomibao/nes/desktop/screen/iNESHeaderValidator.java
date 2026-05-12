package net.lomibao.nes.desktop.screen;

/**
 * Validates the first 16 bytes of an iNES ROM header before loading.
 *
 * <p>Performs two checks:
 * <ol>
 *   <li>Magic bytes — bytes 0–3 must equal {@code 0x4E 0x45 0x53 0x1A} ("NES\x1A").</li>
 *   <li>Mapper number — derived from bytes 6 and 7 using the standard iNES 1.0 formula;
 *       only mapper 0 (NROM) is currently supported.</li>
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
     * @param headerBytes the first 16 bytes of the ROM file
     * @return a {@link ValidationResult} describing pass/fail
     * @throws IllegalArgumentException if {@code headerBytes} is null or fewer than 16 bytes
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

        // Compute mapper number from iNES 1.0 spec:
        //   low nibble  = upper nibble of byte 6
        //   high nibble = upper nibble of byte 7
        int mapperNumber = ((headerBytes[6] >> 4) & 0x0F) | (headerBytes[7] & 0xF0);

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
