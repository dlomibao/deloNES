package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

//Picture Processing Unit
@Log4j2
public class PPU  extends CPUBusComponent {
    public int CPUBUS_START_ADDRESS =0x2000;
    public int REGISTER_SIZE=8;
    public int CPUBUS_END_ADDRESS =0x4000;//exclusive

    public byte[] registers;
    public PPU(){
        registers=new byte[REGISTER_SIZE];
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
}
