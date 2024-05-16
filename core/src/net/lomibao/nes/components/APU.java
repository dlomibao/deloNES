package net.lomibao.nes.components;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.log4j.Log4j2;

//Audio Processing Unit

@Data
@EqualsAndHashCode(callSuper = false)
@Log4j2
public class APU  extends CPUBusComponent {
    //https://www.nesdev.org/wiki/APU#Registers


    public int START_ADDRESS=0x4000;
    public int REGISTER_SIZE=32;
    public int END_ADDRESS=0x4020;//exclusive

    public byte[] registers;
    public APU(){
        registers=new byte[REGISTER_SIZE];
    }

    @Override
    public int getCPUBusStartAddress() {
        return START_ADDRESS;
    }
    @Override
    public int getCPUBusEndAddress(){
        return END_ADDRESS;
    }

    @Override
    public void cpuBusWrite(int address, byte value){
        int index=getIndex(address);
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
        int index=getIndex(address);
        if(index==-1){
            return 0;
        }
        return Byte.toUnsignedInt(registers[index]);
    }

    private int getIndex(int address){
        if(address<START_ADDRESS && address>=END_ADDRESS){
            log.error("attempting to read memory out of range {}. valid range [{},{}]",address,START_ADDRESS,END_ADDRESS);
            return -1;
        }
        return (address-START_ADDRESS)%REGISTER_SIZE;
    }
}
