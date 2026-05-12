package net.lomibao.nes.components;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import lombok.extern.log4j.Log4j2;

import java.util.Optional;

@Data
@Builder
@Log4j2
public class CPUBus {
    @ToString.Exclude
    CPU6502 cpu;
    Ram ram;
    PPU ppu;
    APU apu;
    Cartridge cartridge;
    Controller controller;
    DmaController dma;
    FullAddressRam testRam;
    @Builder.Default
    long masterClockCount = 0;// the master clock is the total number of clocks for the system. aligned with
                              // the PPU since it is the fastest

    public CPUBus connect() {
        Optional.ofNullable(testRam).ifPresent(testRam -> testRam.connectCpuBus(this));
        Optional.ofNullable(cpu).ifPresent(cpu -> cpu.connectCpuBus(this));
        Optional.ofNullable(ram).ifPresent(ram -> ram.connectCpuBus(this));
        Optional.ofNullable(ppu).ifPresent(ppu -> ppu.connectCpuBus(this));
        Optional.ofNullable(apu).ifPresent(apu -> apu.connectCpuBus(this));
        Optional.ofNullable(cartridge).ifPresent(cartridge -> cartridge.connectCpuBus(this));
        Optional.ofNullable(controller).ifPresent(controller -> controller.connectCpuBus(this));
        Optional.ofNullable(dma).ifPresent(dma -> dma.connectCpuBus(this));
        return this;
    }

    public void write(int address, byte value) {
        final int addr = address & 0xFFFF;// mask to 16b
        if (Optional.ofNullable(testRam).map(r -> r.inCPUBusRange(addr)).orElse(false)) {// test ram gets top priority
                                                                                         // if set and covers full
                                                                                         // address range
            testRam.cpuBusWrite(addr, value);
        } else if (Optional.ofNullable(ram).map(r -> r.inCPUBusRange(addr)).orElse(false)) {
            ram.cpuBusWrite(addr, value);
        } else if (Optional.ofNullable(ppu).map(ppu -> ppu.inCPUBusRange(addr)).orElse(false)) {
            ppu.cpuBusWrite(addr, value);
        } else if (Optional.ofNullable(controller).map(c -> c.inCPUBusRange(addr)).orElse(false)) {
            // Controller handles $4016 writes (strobe). $4017 writes are a
            // no-op in the controller; the APU frame-counter write is handled
            // below so that both consumers see the write.
            controller.cpuBusWrite(addr, value);
            // Fall through to APU for any address the APU also owns (e.g.
            // $4017 frame counter). The controller no-ops $4017 writes so
            // routing both is safe.
            if (Optional.ofNullable(apu).map(a -> a.inCPUBusRange(addr)).orElse(false)) {
                apu.cpuBusWrite(addr, value);
            }
        } else if (Optional.ofNullable(dma).map(d -> d.inCPUBusRange(addr)).orElse(false)) {
            // DMA ($4014 OAM DMA trigger) must be checked before APU because the
            // APU's address window ($4000-$401F) overlaps $4014. Without this
            // ordering, sprite DMA writes are silently routed to the APU
            // register file instead of triggering the OAM transfer.
            dma.cpuBusWrite(addr, value);
        } else if (Optional.ofNullable(apu).map(a -> a.inCPUBusRange(addr)).orElse(false)) {
            apu.cpuBusWrite(addr, value);
        } else {
            log.error("no device found in range of address {}", address);
        }
    }

    /**
     * if read only is true, only reads current state. reads on 6502 can under
     * normal operation have sideeffects
     * 
     * @param address
     * @param readOnly
     * @return
     */
    public int read(int address, boolean readOnly) {
        final int addr = address & 0xFFFF;// mask to 16b

        if (Optional.ofNullable(testRam).map(r -> r.inCPUBusRange(addr)).orElse(false)) {// test ram gets top priority
                                                                                         // if set and covers full
                                                                                         // address range
            return testRam.cpuBusRead(addr, readOnly);
        } else if (Optional.ofNullable(ram).map(r -> r.inCPUBusRange(addr)).orElse(false)) {
            return ram.cpuBusRead(addr, readOnly);
        } else if (Optional.ofNullable(ppu).map(p -> p.inCPUBusRange(addr)).orElse(false)) {
            return ppu.cpuBusRead(addr, readOnly);
        } else if (Optional.ofNullable(controller).map(c -> c.inCPUBusRange(addr)).orElse(false)) {
            // Controller ($4016-$4017) must be checked before APU: the APU
            // address range includes $4016/$4017 (APU covers $4000-$401F), but
            // on real NES hardware $4016/$4017 reads are routed to the
            // controller port, not the APU. APU writes to $4017 (frame counter)
            // continue to work because writes go through the write() path which
            // already routes $4017 writes to the controller (no-op there) and
            // the APU frame counter is written via cpuBusWrite in the write()
            // path below.
            return controller.cpuBusRead(addr, readOnly);
        } else if (Optional.ofNullable(dma).map(d -> d.inCPUBusRange(addr)).orElse(false)) {
            // DMA ($4014) before APU: APU's window overlaps but $4014 is the
            // OAM DMA trigger (write-only; read returns 0). $4015 falls through
            // to APU below for status reads. Keeps read ordering symmetric with
            // write().
            return dma.cpuBusRead(addr, readOnly);
        } else if (Optional.ofNullable(apu).map(a -> a.inCPUBusRange(addr)).orElse(false)) {
            return apu.cpuBusRead(addr, readOnly);
        } else if (Optional.ofNullable(cartridge).map(c -> c.inCPUBusRange(addr)).orElse(false)) {
            return cartridge.cpuBusRead(addr, readOnly);
        }
        log.error("no device found in range of address {}", address);
        return 0;
    }

    public int read(int address) {
        return read(address, false);
    }

    public void reset() {
        cpu.reset();
        masterClockCount = 0;
    }

    public void clock() {

        Optional.ofNullable(ppu).ifPresent(ppu -> ppu.clock());
        if (masterClockCount % 3 == 0) {// ppu is 3x faster than the cpu
            // DMA preempts the CPU when active: instead of cpu.clock() this
            // CPU-turn goes to the DMA state machine. CPU is suspended for
            // the duration of the DMA burst (513 or 514 CPU cycles).
            if (dma != null && dma.isActive()) {
                dma.tickDmaCycle(this, ppu, masterClockCount);
            } else {
                Optional.ofNullable(cpu).ifPresent(cpu -> cpu.clock());
            }
        }
        masterClockCount++;
    }

}
