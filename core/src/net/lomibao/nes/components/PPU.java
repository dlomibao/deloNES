package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

//Picture Processing Unit
@Log4j2
public class PPU  extends CPUBusComponent implements PPUBusComponent{
    public int CPUBUS_START_ADDRESS =0x2000;
    public int REGISTER_SIZE=8;
    public int CPUBUS_END_ADDRESS =0x4000;//exclusive

    public byte[] registers;
    public PPUBus ppuBus;
    private Cartridge cartridge;  // Reference for CHR ROM access


    public PPU(){
        registers=new byte[REGISTER_SIZE];
    }

    /**
     * Sets the cartridge reference for CHR ROM access
     * @param cartridge the cartridge with CHR ROM data
     */
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public int getCPUBusStartAddress() {
        return CPUBUS_START_ADDRESS;
    }
    @Override
    public int getCPUBusEndAddress(){
        return CPUBUS_END_ADDRESS;
    }

    @Override
    public void cpuBusWrite(int address, byte value){
        int index= getCPUBusIndex(address);
        registers[index]=value;
    }

    /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    @Override
    public int cpuBusRead(int address, boolean readOnly){
        int index= getCPUBusIndex(address);
        if(index==-1){
            return 0;
        }
        return Byte.toUnsignedInt(registers[index]);
    }

    private int getCPUBusIndex(int address){
        if(address< CPUBUS_START_ADDRESS && address>= CPUBUS_END_ADDRESS){
            log.error("attempting to read memory out of range {}. valid range [{},{}]",address, CPUBUS_START_ADDRESS, CPUBUS_END_ADDRESS);
            return -1;
        }
        return address%REGISTER_SIZE;
    }

    public void clock() {
        //todo complete
    }

    @Override
    public void connectPPUBus(PPUBus ppuBus) {
        this.ppuBus=ppuBus;
    }

    @Override
    public PPUBus getPPUBus() {
        return ppuBus;
    }

    @Override
    public int getPPUBusStartAddress() {
        return 0;
    }

    @Override
    public int getPPUBusEndAddress() {
        return 0;
    }

    /**
     * Gets the full CHR layout decoded for debugging purposes
     * @return byte[256][128] grid where [y][x] contains 2-bit color (0-3)
     */
    public byte[][] getCHRLayout() {
        if (cartridge == null) {
            log.warn("Cartridge not set, cannot decode CHR layout");
            return null;
        }
        byte[] chrData = cartridge.getCHRROM();
        return TileDecoder.decodeCHRLayout(chrData);
    }

    /**
     * Gets a specific pattern table decoded for debugging
     * @param table 0 or 1
     * @return byte[128][128] grid where [y][x] contains 2-bit color (0-3)
     */
    public byte[][] getPatternTable(int table) {
        if (cartridge == null) {
            log.warn("Cartridge not set, cannot decode pattern table");
            return null;
        }
        byte[] chrData = cartridge.getCHRROM();
        return TileDecoder.decodePatternTable(chrData, table);
    }

    /**
     * Gets a specific tile decoded
     * @param tileIndex 0-511
     * @return byte[8][8] grid where [y][x] contains 2-bit color (0-3)
     */
    public byte[][] getTile(int tileIndex) {
        if (cartridge == null) {
            log.warn("Cartridge not set, cannot decode tile");
            return null;
        }
        byte[] chrData = cartridge.getCHRROM();
        return TileDecoder.getTile(chrData, tileIndex);
    }

    /**
     * Gets debug string representation of CHR layout
     * @return ASCII visualization of CHR data
     */
    public String getCHRLayoutDebugString() {
        byte[][] layout = getCHRLayout();
        if (layout == null) {
            return "CHR layout unavailable";
        }
        return TileDecoder.pixelsToDebugString(layout);
    }

}
