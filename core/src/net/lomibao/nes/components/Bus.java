package net.lomibao.nes.components;

import lombok.Builder;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.util.Optional;

@Data
@Builder
@Log4j2
public class Bus{
    CPU6502 cpu;
    Ram ram;
    PPU ppu;
    APU apu;
    Cartridge cartridge;

    public Bus connect(){

        Optional.ofNullable(cpu).ifPresent(cpu-> cpu.connectBus(this));
        Optional.ofNullable(ram).ifPresent(ram-> ram.connectBus(this));
        Optional.ofNullable(ppu).ifPresent(ppu-> ppu.connectBus(this));
        Optional.ofNullable(apu).ifPresent(apu-> apu.connectBus(this));
        Optional.ofNullable(cartridge).ifPresent(cartridge -> cartridge.connectBus(this));
        return this;
    }

    public void write(int address,byte value){
        address=address&0xFFFF;//mask to 16b
        throw new NotImplementedException();
    }

    /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    public byte read(int address,boolean readOnly){
        final int addr=address&0xFFFF;//mask to 16b
        if(Optional.ofNullable(ram).map(r->r.inRange(addr)).orElse(false)){
            return ram.read(address,readOnly);
        }else if(Optional.ofNullable(ppu).map(p->p.inRange(addr)).orElse(false)){
            return ppu.read(address,readOnly);
        }else if(Optional.ofNullable(apu).map(a->a.inRange(addr)).orElse(false)){
            return apu.read(address,readOnly);
        }else if(Optional.ofNullable(cartridge).map(c->c.inRange(addr)).orElse(false)){
            return cartridge.read(address,readOnly);
        }
        log.error("no device found in range of address {}",address);
        return 0;
    }
    public byte read(int address){
        return read(address,false);
    }

    //private void map(short address)
}
