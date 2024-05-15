package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class Ram  extends CPUBusComponent {
    public short MEMORY_SIZE=2*1024;
    public short ADDRESS_RANGE_SIZE= (short) (4*MEMORY_SIZE);//4 mirrors
    public short ADDRESS_RANGE_START=0;

    public byte[] byteArray;

    public Ram(){
        byteArray=new byte[MEMORY_SIZE];
    }
    @Override
    public void write(int address,byte value){
        int index=getIndex(address);
        byteArray[index]=value;
    }

    /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    public byte read(short address,boolean readOnly){
        int index=getIndex(address);
        if(index==-1){
            return 0;
        }
        return byteArray[index];
    }
    private int getIndex(int address){
        if(address<ADDRESS_RANGE_START || address>ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE){
            log.error("attempting to read memory out of range {}. valid range [{},{}]",address,ADDRESS_RANGE_START,ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE);
            return -1;
        }
        return address%MEMORY_SIZE;
    }
    public byte read(short address){
        return read(address,false);
    }

    @Override
    public int getStartAddress(){
        return ADDRESS_RANGE_START;
    }
    @Override
    public int getEndAddress(){
        return (ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE);
    }
}
