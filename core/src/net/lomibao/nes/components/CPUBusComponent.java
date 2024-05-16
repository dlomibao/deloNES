package net.lomibao.nes.components;

public abstract class CPUBusComponent {
    private CPUBus cpuBus;

    public void cpuBusWrite(int address, byte value) {
        cpuBus.write(address,value);
    }

        /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    public int cpuBusRead(int address, boolean readOnly){
        return cpuBus.read(address);
    }
    public int cpuBusRead(int address){
        return cpuBusRead(address,false);
    }

    public void connectCpuBus(CPUBus cpuBus){
        this.cpuBus = cpuBus;

    }
    public CPUBus getBus(){
        return cpuBus;
    }

    abstract public int getCPUBusStartAddress();

    abstract public int getCPUBusEndAddress();
    public boolean inCPUBusRange(int address){
        return address>= getCPUBusStartAddress() && address< getCPUBusEndAddress();
    }
}
