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
    public void cpuBusWrite(int address, byte value){
        int index=getIndex(address);
        byteArray[index]=value;
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
        return byteArray[index];
    }
    private int getIndex(int address){
        if(address<ADDRESS_RANGE_START || address>ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE){
            log.error("attempting to read memory out of range {}. valid range [{},{}]",address,ADDRESS_RANGE_START,ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE);
            return -1;
        }
        return (address-ADDRESS_RANGE_START)%MEMORY_SIZE;
    }
    @Override
    public int cpuBusRead(int address){
        return cpuBusRead(address,false);
    }

    @Override
    public int getCPUBusStartAddress(){
        return ADDRESS_RANGE_START;
    }
    @Override
    public int getCPUBusEndAddress(){
        return (ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE);
    }

    public void writeRange(int address,byte[] bytes){
        for(int i=0;i<bytes.length;i++){
            cpuBusWrite(address+i,bytes[i]);
        }
    }
}
