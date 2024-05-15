package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

//Picture Processing Unit
@Log4j2
public class PPU  extends CPUBusComponent {
    public int START_ADDRESS=0x2000;
    public int REGISTER_SIZE=8;
    public int END_ADDRESS=0x4000;//exclusive

    public byte[] registers;
    public PPU(){
        registers=new byte[REGISTER_SIZE];
    }

    @Override
    public int getStartAddress() {
        return START_ADDRESS;
    }
    @Override
    public int getEndAddress(){
        return  END_ADDRESS;
    }

    @Override
    public void write(int address,byte value){
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
    public int read(int address,boolean readOnly){
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
        return address%REGISTER_SIZE;
    }
}
