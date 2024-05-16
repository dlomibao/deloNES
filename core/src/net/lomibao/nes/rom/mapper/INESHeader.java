package net.lomibao.nes.rom.mapper;

public class INESHeader {
    byte[] headerBytes=new byte[16];//16 byte header
    public INESHeader(byte[] headerBytes){
        headerBytes=headerBytes;
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

    public int getMapperNumber() {
        return ((headerBytes[6] >> 4) & 0x0F) | (headerBytes[7] & 0xF0);
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
