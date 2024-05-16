package net.lomibao.nes.components;

import lombok.Builder;
import lombok.Data;
import lombok.extern.log4j.Log4j2;

import java.util.Optional;

@Data
@Builder
@Log4j2
public class CPUBus {
    CPU6502 cpu;
    Ram ram;
    PPU ppu;
    APU apu;
    Cartridge cartridge;
    FullAddressRam testRam;
    @Builder.Default
    long masterClockCount=0;//the master clock is the total number of clocks for the system. aligned with the PPU since it is the fastest


    public CPUBus connect(){
        Optional.ofNullable(testRam).ifPresent(testRam->testRam.connectCpuBus(this));
        Optional.ofNullable(cpu).ifPresent(cpu-> cpu.connectCpuBus(this));
        Optional.ofNullable(ram).ifPresent(ram-> ram.connectCpuBus(this));
        Optional.ofNullable(ppu).ifPresent(ppu-> ppu.connectCpuBus(this));
        Optional.ofNullable(apu).ifPresent(apu-> apu.connectCpuBus(this));
        Optional.ofNullable(cartridge).ifPresent(cartridge -> cartridge.connectCpuBus(this));
        return this;
    }

    public void write(int address,byte value){
        final int addr=address&0xFFFF;//mask to 16b
        if(Optional.ofNullable(testRam).map(r->r.inCPUBusRange(addr)).orElse(false)){//test ram gets top priority if set and covers full address range
            testRam.cpuBusWrite(addr,value);
        }else if(Optional.ofNullable(ram).map(r->r.inCPUBusRange(addr)).orElse(false)){
            ram.cpuBusWrite(addr,value);
        }else if(Optional.ofNullable(ppu).map(ppu->ppu.inCPUBusRange(addr)).orElse(false)){
            ppu.cpuBusWrite(addr,value);
        }

        log.error("no device found in range of address {}",address);

    }

    /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    public int read(int address,boolean readOnly){
        final int addr=address&0xFFFF;//mask to 16b

        if(Optional.ofNullable(testRam).map(r->r.inCPUBusRange(addr)).orElse(false)){//test ram gets top priority if set and covers full address range
            return testRam.cpuBusRead(addr,readOnly);
        }else if(Optional.ofNullable(ram).map(r->r.inCPUBusRange(addr)).orElse(false)){
            return ram.cpuBusRead(addr,readOnly);
        }else if(Optional.ofNullable(ppu).map(p->p.inCPUBusRange(addr)).orElse(false)){
            return ppu.cpuBusRead(addr,readOnly);
        }else if(Optional.ofNullable(apu).map(a->a.inCPUBusRange(addr)).orElse(false)){
            return apu.cpuBusRead(addr,readOnly);
        }else if(Optional.ofNullable(cartridge).map(c->c.inCPUBusRange(addr)).orElse(false)){
            return cartridge.cpuBusRead(addr,readOnly);
        }
        log.error("no device found in range of address {}",address);
        return 0;
    }
    public int read(int address){
        return read(address,false);
    }

    public void reset(){
        cpu.reset();
        masterClockCount=0;
    }
    public void clock(){

        Optional.ofNullable(ppu).ifPresent(ppu->ppu.clock());
        if(masterClockCount%3==0){//ppu is 3x faster than the cpu
            Optional.ofNullable(cpu).ifPresent(cpu->cpu.clock());
        }
        masterClockCount++;
    }

}
