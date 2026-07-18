package net.lomibao.nes.rom.mapper;

/**
 * Test helper: build a minimal, valid iNES-format ROM image in memory
 * so mapper unit tests don't have to ship physical .nes files for every
 * edge case. Used by Phase B-E mapper tests in docs/mapper-plan.md.
 */
public final class MapperTestSupport {
    private MapperTestSupport() {}

    private static final byte[] INES_MAGIC = { 0x4E, 0x45, 0x53, 0x1A };

    public static final int PRG_BANK_BYTES = 16 * 1024;
    public static final int CHR_BANK_BYTES = 8 * 1024;

    /**
     * Build an iNES 1.0-format byte[] suitable for feeding directly to
     * {@code new Cartridge(bytes)}. Header layout:
     * <ul>
     *   <li>Bytes 0-3: {@code N E S 0x1A} magic.</li>
     *   <li>Byte 4: PRG-ROM size in 16KB banks.</li>
     *   <li>Byte 5: CHR-ROM size in 8KB banks (0 ⇒ CHR-RAM cartridge).</li>
     *   <li>Byte 6 high nibble: mapper# low 4 bits. Low nibble: mirroring/
     *       battery/trainer/4-screen — all zero here.</li>
     *   <li>Byte 7 high nibble: mapper# high 4 bits. Low nibble: zero
     *       (NES 1.0, no Vs.Unisystem, no PlayChoice).</li>
     *   <li>Bytes 8-15: zero.</li>
     * </ul>
     *
     * @param mapperId 0-255
     * @param prgKB    positive multiple of 16
     * @param chrKB    non-negative multiple of 8 (0 ⇒ no CHR section)
     * @param prgSeed  bytes used to fill PRG section, repeating; null ⇒ all zero
     * @param chrSeed  bytes used to fill CHR section, repeating; null or chrKB==0 ⇒ all zero
     * @return iNES image (length 16 + prgKB*1024 + chrKB*1024)
     */
    public static byte[] buildSyntheticROM(
            int mapperId, int prgKB, int chrKB, byte[] prgSeed, byte[] chrSeed) {
        if (mapperId < 0 || mapperId > 0xFF) {
            throw new IllegalArgumentException("mapperId must be 0-255: " + mapperId);
        }
        if (prgKB <= 0 || prgKB % 16 != 0) {
            throw new IllegalArgumentException("prgKB must be a positive multiple of 16: " + prgKB);
        }
        if (chrKB < 0 || chrKB % 8 != 0) {
            throw new IllegalArgumentException("chrKB must be a non-negative multiple of 8: " + chrKB);
        }

        int prgBytes = prgKB * 1024;
        int chrBytes = chrKB * 1024;
        byte[] rom = new byte[16 + prgBytes + chrBytes];

        System.arraycopy(INES_MAGIC, 0, rom, 0, 4);
        rom[4] = (byte) (prgKB / 16);
        rom[5] = (byte) (chrKB / 8);
        rom[6] = (byte) ((mapperId & 0x0F) << 4);
        rom[7] = (byte) (mapperId & 0xF0);

        fillSection(rom, 16, prgBytes, prgSeed);
        if (chrBytes > 0) {
            fillSection(rom, 16 + prgBytes, chrBytes, chrSeed);
        }
        return rom;
    }

    private static void fillSection(byte[] rom, int offset, int length, byte[] seed) {
        if (seed == null || seed.length == 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            rom[offset + i] = seed[i % seed.length];
        }
    }
}
