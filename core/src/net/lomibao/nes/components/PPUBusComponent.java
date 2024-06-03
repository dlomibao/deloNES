package net.lomibao.nes.components;

public interface PPUBusComponent {




    public default void ppuBusWrite(int address, byte value) {
        getPPUBus().write(address,value);
    }

    /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    public default int ppuBusRead(int address, boolean readOnly){
        return getPPUBus().read(address);
    }
    public default int ppuBusRead(int address){
        return ppuBusRead(address,false);
    }

    public void connectPPUBus(PPUBus ppuBus);

    public PPUBus getPPUBus();


    abstract public int getPPUBusStartAddress();

    abstract public int getPPUBusEndAddress();
    public default boolean inPPUusRange(int address){
        return address>= getPPUBusStartAddress() && address< getPPUBusEndAddress();
    }

}


