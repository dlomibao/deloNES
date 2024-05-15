package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

/**test ram that covers the full address space of cpu**/
@Log4j2
public class FullAddressRam extends CPUBusComponent {
    public int MEMORY_SIZE=0xFFFF;
    public int ADDRESS_RANGE_SIZE= MEMORY_SIZE;
    public short ADDRESS_RANGE_START=0;

    public byte[] byteArray;

    public FullAddressRam(){
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
    @Override
    public int read(int address,boolean readOnly){
        int index=getIndex(address);
        if(index==-1){
            return 0;
        }
        return Byte.toUnsignedInt(byteArray[index]);
    }
    private int getIndex(int address){
        if(address<ADDRESS_RANGE_START || address>ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE){
            log.error("attempting to read memory out of range {}. valid range [{},{}]",address,ADDRESS_RANGE_START,ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE);
            return -1;
        }
        return (address-ADDRESS_RANGE_START)%MEMORY_SIZE;
    }
    @Override
    public int read(int address){
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

    public void writeRange(int address,byte[] bytes){
        for(int i=0;i<bytes.length;i++){
            write(address+i,bytes[i]);
        }
    }
    public byte[] getByteArray(){
        return byteArray;
    }
    public String getHexRange(int startAddress,int size,int lineSize){
        StringBuilder sb=new StringBuilder();
        for(int i=startAddress;i<startAddress+size;i++){
            sb.append(String.format("%02X ",byteArray[i]));
            if(i%lineSize==lineSize-1){
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
