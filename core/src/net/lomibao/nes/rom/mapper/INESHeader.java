package net.lomibao.nes.rom.mapper;

/**
 * Parses the 16-byte iNES / NES 2.0 header.
 *
 * <p>Bytes 0–7 are shared between the two formats (magic, PRG/CHR LSB counts,
 * flags 6/7). NES 2.0 (<a href="https://www.nesdev.org/wiki/NES_2.0">NESdev
 * wiki</a>) redefines bytes 8–15: mapper MSB/submapper, PRG/CHR size MSB
 * nibbles (with an exponent-multiplier escape), RAM/NVRAM shift counts,
 * timing, console-type detail, misc ROM count, and default expansion device.
 * Accessors here are format-aware: they branch on {@link #isNES2Format()}
 * internally so callers never need to.
 */
public class INESHeader {

    /** TV timing per NES 2.0 byte 12 (bits 0-1); iNES 1.0 maps byte 9 bit 0. */
    public enum TvTiming { NTSC, PAL, MULTI, DENDY }

    /**
     * Sanity cap for a single ROM area (PRG or CHR). The NES 2.0
     * exponent-multiplier form can encode up to 2^63 bytes; real dumps are
     * far below 64 MiB, and the web build feeds arbitrary user files to this
     * parser, so anything larger is treated as hostile/corrupt rather than
     * allocated.
     */
    public static final long MAX_ROM_AREA_BYTES = 64L * 1024 * 1024;

    private static final int PRG_BANK_SIZE = 16384;
    private static final int CHR_BANK_SIZE = 8192;

    byte[] headerBytes=new byte[16];//16 byte header
    public INESHeader(byte[] headerBytes){
        this.headerBytes=headerBytes;
    }

    public byte[] getHeaderBytes() {
        return headerBytes;
    }
    void printHeaderBytes(){

    }
    /**
     * Gets the number of 16KB units of program ROM (byte 4 LSB only).
     *
     * @deprecated duplicate of {@link #getPRGROMSize()}; both under-report
     *             NES 2.0 sizes — use {@link #getPRGROMSizeBytes()}.
     */
    @Deprecated
    public int getSizeOfPRGRom(){
        return Byte.toUnsignedInt(headerBytes[4]);
    }
    public int getFlags6(){
        return getFlagByteAsInt(6);
    }
    public int getFlags7(){
        return getFlagByteAsInt(7);
    }
    public int getFlags8(){
        return getFlagByteAsInt(8);
    }
    public int getFlags9(){
        return getFlagByteAsInt(9);
    }
    public int getFlags10(){
        return getFlagByteAsInt(10);
    }
    private int getFlagByteAsInt(int headerOffset){
        return Byte.toUnsignedInt(headerBytes[headerOffset]);
    }



    public int getPRGROMSize() {
        return headerBytes[4] & 0xFF;
    }

    public int getCHRROMSize() {
        return headerBytes[5] & 0xFF;
    }

    public boolean isHorizontalMirroring() {
        return (headerBytes[6] & 0x01) != 0;
    }

    public boolean hasBatteryBackedRAM() {
        return (headerBytes[6] & 0x02) != 0;
    }

    public boolean hasTrainer() {
        return (headerBytes[6] & 0x04) != 0;
    }

    public boolean isFourScreenVRAM() {
        return (headerBytes[6] & 0x08) != 0;
    }

    /**
     * Returns the mapper number.
     * <ul>
     *   <li>NES 2.0 ({@code (byte7 & 0x0C) == 0x08}): 12-bit number —
     *       {@code (byte6 >> 4) | (byte7 & 0xF0) | ((byte8 & 0x0F) << 8)}.
     *       Byte 8's contribution is gated on the format check: in iNES 1.0
     *       that byte is the PRG-RAM size, and reading mapper bits from it
     *       would corrupt the mapper number of any 1.0 ROM with a non-zero
     *       byte 8.</li>
     *   <li>iNES 0.7 / "DiskDude!" era headers: byte 7's high nibble may be
     *       garbage from an old tool's signature stashed in bytes 7-15. The
     *       convention is: if any of bytes 12-15 are non-zero, ignore byte 7's
     *       high nibble before computing the mapper.</li>
     *   <li>Otherwise (iNES 1.0): {@code (byte6 >> 4) | (byte7 & 0xF0)}.</li>
     * </ul>
     * See the <a href="https://www.nesdev.org/wiki/INES">NESdev wiki</a>.
     */
    public int getMapperNumber() {
        int byte7HighNibble = headerBytes[7] & 0xF0;
        // DiskDude workaround: bytes 12-15 carry a stale signature ⇒ byte 7's
        // upper nibble is unreliable. Treat as iNES 0.7 and zero it.
        // Skip for NES 2.0 headers — bytes 12-15 are meaningful fields there.
        if (hasDiskDudeArtifacts()) {
            byte7HighNibble = 0;
        }
        int mapper = ((headerBytes[6] >> 4) & 0x0F) | byte7HighNibble;
        if (isNES2Format()) {
            mapper |= (getFlags8() & 0x0F) << 8;
        }
        return mapper;
    }

    /**
     * True when this is an iNES 1.0 header whose bytes 12-15 are non-zero —
     * the "DiskDude!" signature convention: an old dumper stashed text in
     * bytes 7-15, making all of byte 7 (not just the mapper high nibble)
     * unreliable. Never true for NES 2.0, where those bytes are real fields.
     */
    private boolean hasDiskDudeArtifacts() {
        return !isNES2Format()
                && (headerBytes[12] != 0 || headerBytes[13] != 0
                    || headerBytes[14] != 0 || headerBytes[15] != 0);
    }

    /**
     * NES 2.0 submapper (byte 8 high nibble, 0-15). iNES 1.0 has no
     * submapper concept — returns 0.
     */
    public int getSubmapper() {
        return isNES2Format() ? getFlags8() >> 4 : 0;
    }

    /**
     * @deprecated raw byte-7 bit read with no DiskDude leniency; use
     *             {@link #getConsoleType()}.
     */
    @Deprecated
    public boolean isVSUnisystem() {
        return (headerBytes[7] & 0x01) != 0;
    }

    /**
     * @deprecated raw byte-7 bit read with no DiskDude leniency; use
     *             {@link #getConsoleType()}.
     */
    @Deprecated
    public boolean isPlayChoice10() {
        return (headerBytes[7] & 0x02) != 0;
    }

    /**
     * Console type from byte 7 bits 0-1 (both formats): 0 = NES/Famicom,
     * 1 = VS System, 2 = PlayChoice-10, 3 = extended (NES 2.0). An iNES 1.0
     * header with both the VS and PC10 bits set reads as 3 — malformed input
     * either way; every non-zero value is rejected identically upstream, so
     * no special handling.
     *
     * <p>DiskDude-tagged 1.0 headers (see {@link #hasDiskDudeArtifacts()})
     * report 0: byte 7 is signature garbage on those dumps, and trusting its
     * console bits would reject old NROM dumps that played fine before —
     * the same leniency the mapper-number path applies to the high nibble.
     */
    public int getConsoleType() {
        if (hasDiskDudeArtifacts()) {
            return 0;
        }
        return headerBytes[7] & 0x03;
    }

    public boolean isNES2Format() {
        return ((headerBytes[7] & 0x0C) >> 2) == 2;
    }

    /**
     * Legacy iNES 1.0 PRG-RAM size (byte 8, in 8 KB units). In NES 2.0
     * byte 8 holds mapper MSB/submapper instead, so this returns 0 there —
     * use {@link #getPRGRAMSizeBytes()} for the format-aware value.
     */
    public int getPRGRAMSize() {
        return isNES2Format() ? 0 : headerBytes[8] & 0xFF;
    }

    /**
     * Format-aware PAL flag. iNES 1.0: byte 9 bit 0. NES 2.0: byte 9 is the
     * ROM-size MSB nibbles, so timing comes from byte 12 instead.
     */
    public boolean isPAL() {
        if (isNES2Format()) {
            return getTimingMode() == TvTiming.PAL;
        }
        return (headerBytes[9] & 0x01) != 0;
    }

    /**
     * Format-aware PRG-RAM presence. iNES 1.0: byte 10 bit 4 (rarely set by
     * real files). NES 2.0: byte 10 is the PRG-RAM/NVRAM shift counts, so
     * presence means a non-zero decoded size.
     */
    public boolean hasPRGRAMPresent() {
        if (isNES2Format()) {
            return getPRGRAMSizeBytes() > 0;
        }
        return (headerBytes[10] & 0x10) != 0;
    }

    /** PRG-ROM size in bytes, format-aware. See {@link #decodeRomAreaSize}. */
    public int getPRGROMSizeBytes() {
        return decodeRomAreaSize(getPRGROMSize(), getFlags9() & 0x0F, PRG_BANK_SIZE, "PRG-ROM");
    }

    /** CHR-ROM size in bytes, format-aware; 0 means a CHR-RAM cart. */
    public int getCHRROMSizeBytes() {
        return decodeRomAreaSize(getCHRROMSize(), getFlags9() >> 4, CHR_BANK_SIZE, "CHR-ROM");
    }

    /**
     * Decodes one ROM-area size to bytes.
     *
     * <p>iNES 1.0: {@code lsb * bankSize}. NES 2.0: 12-bit unit count
     * {@code (msbNibble << 8) | lsb}, unless {@code msbNibble == 0xF} which
     * selects the exponent-multiplier form {@code 2^E * (M*2+1)} with
     * {@code E = lsb >> 2}, {@code M = lsb & 3}. E is rejected before
     * shifting when it alone would exceed the cap ({@code 2^26} = 64 MiB),
     * so the long math can never overflow. Exponent-form results must be a
     * multiple of the bank size — the mapper layer is bank-count based, so
     * legitimate sub-bank sizes (e.g. 8 KB PRG test ROMs) are rejected with
     * a descriptive error rather than mis-mapped (documented limitation).
     *
     * @throws IllegalArgumentException naming the offending size when over
     *         {@link #MAX_ROM_AREA_BYTES} or not bank-aligned
     */
    private int decodeRomAreaSize(int lsb, int msbNibble, int bankSize, String label) {
        long sizeBytes;
        if (!isNES2Format()) {
            sizeBytes = (long) lsb * bankSize;
        } else if (msbNibble == 0x0F) {
            int exponent = lsb >> 2;
            int multiplier = (lsb & 0x03) * 2 + 1;
            if (exponent > 26) {
                throw new IllegalArgumentException(label + " exponent-form size 2^" + exponent
                        + " exceeds the " + (MAX_ROM_AREA_BYTES / (1024 * 1024)) + " MiB cap");
            }
            sizeBytes = (1L << exponent) * multiplier;
            if (sizeBytes % bankSize != 0) {
                throw new IllegalArgumentException(label + " exponent-form size " + sizeBytes
                        + " bytes is not a multiple of the " + bankSize + "-byte bank size");
            }
        } else {
            sizeBytes = (long) ((msbNibble << 8) | lsb) * bankSize;
        }
        if (sizeBytes > MAX_ROM_AREA_BYTES) {
            throw new IllegalArgumentException(label + " size " + sizeBytes
                    + " bytes exceeds the " + (MAX_ROM_AREA_BYTES / (1024 * 1024)) + " MiB cap");
        }
        return (int) sizeBytes;
    }

    /** NES 2.0 PRG-RAM size in bytes (byte 10 low nibble shift); 0 for iNES 1.0. */
    public int getPRGRAMSizeBytes() {
        return isNES2Format() ? decodeRamShift(getFlags10() & 0x0F) : 0;
    }

    /** NES 2.0 PRG-NVRAM (battery) size in bytes (byte 10 high nibble); 0 for iNES 1.0. */
    public int getPRGNVRAMSizeBytes() {
        return isNES2Format() ? decodeRamShift(getFlags10() >> 4) : 0;
    }

    /** NES 2.0 CHR-RAM size in bytes (byte 11 low nibble shift); 0 for iNES 1.0. */
    public int getCHRRAMSizeBytes() {
        return isNES2Format() ? decodeRamShift(getFlagByteAsInt(11) & 0x0F) : 0;
    }

    /** NES 2.0 CHR-NVRAM size in bytes (byte 11 high nibble); 0 for iNES 1.0. */
    public int getCHRNVRAMSizeBytes() {
        return isNES2Format() ? decodeRamShift(getFlagByteAsInt(11) >> 4) : 0;
    }

    /** NES 2.0 RAM shift decode: 0 means none, else {@code 64 << shift} bytes. */
    private static int decodeRamShift(int shift) {
        return shift == 0 ? 0 : 64 << shift;
    }

    /**
     * TV timing. NES 2.0: byte 12 bits 0-1 (NTSC/PAL/multi-region/Dendy).
     * iNES 1.0: byte 9 bit 0 mapped to NTSC/PAL.
     */
    public TvTiming getTimingMode() {
        if (isNES2Format()) {
            switch (getFlagByteAsInt(12) & 0x03) {
                case 1:  return TvTiming.PAL;
                case 2:  return TvTiming.MULTI;
                case 3:  return TvTiming.DENDY;
                default: return TvTiming.NTSC;
            }
        }
        return (headerBytes[9] & 0x01) != 0 ? TvTiming.PAL : TvTiming.NTSC;
    }

    /** NES 2.0 misc-ROM count (byte 14 bits 0-1); 0 for iNES 1.0. */
    public int getMiscRomCount() {
        return isNES2Format() ? getFlagByteAsInt(14) & 0x03 : 0;
    }

    /** NES 2.0 default expansion device (byte 15 bits 0-5); 0 for iNES 1.0. */
    public int getDefaultExpansionDevice() {
        return isNES2Format() ? getFlagByteAsInt(15) & 0x3F : 0;
    }
}
