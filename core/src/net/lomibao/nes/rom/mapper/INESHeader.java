package net.lomibao.nes.rom.mapper;

public class INESHeader {
    byte[] headerBytes=new byte[16];//16 byte header
    public INESHeader(byte[] headerBytes){
        this.headerBytes=headerBytes;
    }

    public byte[] getHeaderBytes() {
        return headerBytes;
    }
    void printHeaderBytes(){

    }
    /**gets number of 16KB units of program rom**/
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
     * Returns the mapper number per the iNES spec
     * (<a href="https://www.nesdev.org/wiki/INES">NESdev wiki</a>):
     * <ul>
     *   <li>For NES 2.0 headers ({@code (byte7 & 0x0C) == 0x08}) the mapper number
     *       also includes bits from byte 8. {@link #isNES2Format()} should be
     *       checked first; this method falls back to the iNES 1.0 layout, which
     *       is incomplete for NES 2.0.</li>
     *   <li>For iNES 0.7 / "DiskDude!" era headers, byte 7's high nibble may be
     *       garbage from an old tool's signature stashed in bytes 7-15. The
     *       convention is: if any of bytes 12-15 are non-zero, ignore byte 7's
     *       high nibble before computing the mapper.</li>
     * </ul>
     * Formula: {@code mapper = (byte6 >> 4) | (byte7 & 0xF0)}.
     */
    public int getMapperNumber() {
        int byte7HighNibble = headerBytes[7] & 0xF0;
        // DiskDude workaround: bytes 12-15 carry a stale signature ⇒ byte 7's
        // upper nibble is unreliable. Treat as iNES 0.7 and zero it.
        // Skip for NES 2.0 headers — those are handled separately.
        if (!isNES2Format()
                && (headerBytes[12] != 0 || headerBytes[13] != 0
                    || headerBytes[14] != 0 || headerBytes[15] != 0)) {
            byte7HighNibble = 0;
        }
        return ((headerBytes[6] >> 4) & 0x0F) | byte7HighNibble;
    }

    public boolean isVSUnisystem() {
        return (headerBytes[7] & 0x01) != 0;
    }

    public boolean isPlayChoice10() {
        return (headerBytes[7] & 0x02) != 0;
    }

    public boolean isNES2Format() {
        return ((headerBytes[7] & 0x0C) >> 2) == 2;
    }

    public int getPRGRAMSize() {
        return headerBytes[8] & 0xFF;
    }

    public boolean isPAL() {
        return (headerBytes[9] & 0x01) != 0;
    }

    public boolean hasPRGRAMPresent() {
        return (headerBytes[10] & 0x10) != 0;
    }
}
