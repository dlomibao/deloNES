package net.lomibao.nes.components;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

public abstract class CPUBusComponent {
    private Bus bus;

    public void write(int address, byte value) {
        bus.write(address,value);
    }

        /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    public int read(int address,boolean readOnly){
        return bus.read(address);
    }
    public int read(int address){
        return read(address,false);
    }

    public void connectBus(Bus bus){
        this.bus=bus;

    }
    public Bus getBus(){
        return bus;
    }

    public int getStartAddress(){
        throw new NotImplementedException();
    }

    public int getEndAddress(){
        throw new NotImplementedException();
    }
    public boolean inRange(int address){
        return address>=getStartAddress() && address<getEndAddress();
    }
}
