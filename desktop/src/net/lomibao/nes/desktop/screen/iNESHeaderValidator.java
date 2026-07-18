package net.lomibao.nes.desktop.screen;

import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.rom.mapper.INESHeader;

/**
 * Validates the first 16 bytes of an iNES / NES 2.0 ROM header before loading.
 *
 * <p>Pre-flight triage for the ROM-select screen: a fast, friendly error
 * before {@code Cartridge} is constructed. {@code Cartridge} enforces the
 * same gates (console type, supported mapper, sizes) as the backstop — the
 * web drag-drop path bypasses this class entirely.
 *
 * <p>Checks, in order:
 * <ol>
 *   <li>Magic bytes — bytes 0–3 must equal {@code 0x4E 0x45 0x53 0x1A} ("NES\x1A").</li>
 *   <li>Console type — byte 7 bits 0-1 must be 0 (NES/Famicom); VS System,
 *       PlayChoice-10 and extended consoles use different hardware.</li>
 *   <li>ROM-area sizes — decodable and under the sanity cap (NES 2.0
 *       exponent form can encode absurd sizes; see
 *       {@link INESHeader#MAX_ROM_AREA_BYTES}).</li>
 *   <li>Mapper number — derived by {@link INESHeader#getMapperNumber()}
 *       (which owns the DiskDude workaround and the NES 2.0 12-bit
 *       extension) and checked against {@link Cartridge#SUPPORTED_MAPPERS}.</li>
 * </ol>
 *
 * <p>Both iNES 1.0 and NES 2.0 headers are accepted.
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

        // All field decoding is delegated to INESHeader — the single owner of
        // the DiskDude workaround and the NES 2.0 12-bit mapper / size rules.
        INESHeader header = new INESHeader(headerBytes);

        if (header.getConsoleType() != 0) {
            return ValidationResult.failure(-1,
                    "Unsupported console type (VS System / PlayChoice-10 / extended)"
                    + " — only NES/Famicom ROMs run");
        }

        try {
            header.getPRGROMSizeBytes();
            header.getCHRROMSizeBytes();
        } catch (IllegalArgumentException e) {
            return ValidationResult.failure(-1, e.getMessage());
        }

        int mapperNumber = header.getMapperNumber();
        if (!Cartridge.isMapperSupported(mapperNumber)) {
            return ValidationResult.failure(mapperNumber,
                    "Unsupported mapper " + mapperNumber
                    + " — supported mappers: " + supportedMapperList());
        }

        return ValidationResult.success(mapperNumber);
    }

    private static String supportedMapperList() {
        return Cartridge.SUPPORTED_MAPPERS.stream().sorted()
                .map(String::valueOf)
                .reduce((a, b) -> a + ", " + b).orElse("");
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
         *         too short, had bad magic, an unsupported console type, or an
         *         undecodable size (mapper could not be determined / is irrelevant)
         */
        public int getDetectedMapper() {
            return detectedMapper;
        }
    }
}
