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
    // Hot-path optimisation: `phase` cycles 0,1,2 in lockstep with
    // `masterClockCount % 3`. The previous code did `masterClockCount % 3 == 0`
    // every master tick, which on TeaVM lowers to Long_rem + Long_eq —
    // ~3.6% of total CPU time in the profile (no native 64-bit ints in JS).
    // Keeping masterClockCount as a long for API compat, just dispatching on
    // the int phase counter.
    @Builder.Default
    int phase = 0;

    /**
     * Seam S1 (headless-harness plan, Phase B1): single nullable bus-write
     * observer. Null in production — the hot-path cost is then exactly one
     * null-check branch per write (nestest 8992/8992 parity gate). Set via
     * the Lombok-generated {@code setWriteListener}; only the headless
     * harness installs one.
     */
    @ToString.Exclude
    BusWriteListener writeListener;

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
        // Seam S1: dispatch to the (rarely installed) write listener BEFORE
        // routing, so the old-value snapshot below reads pre-write state.
        // The readOnly RAM snapshot is taken ONLY when a listener is
        // installed; old values exist only for CPU RAM ($0000-$1FFF) — every
        // other address reports -1 (no well-defined bus-level old value).
        if (writeListener != null) {
            int old = (ram != null && addr < 0x2000)
                    ? ram.cpuBusRead(addr, true) : -1;   // readOnly snapshot
            writeListener.onWrite(addr, old, value, cpu != null ? cpu.getPc() : -1);
        }
        // Inlined address-range checks. Was `component.inCPUBusRange(addr)`,
        // which made 3 virtual calls per check (the wrapper + two getter
        // methods). At ~21 virtual calls per bus write × thousands of writes
        // per frame, it showed up at ~5.6% of total CPU on the web build.
        // Ranges below are NES hardware constants — match each component's
        // own getCPUBusStartAddress/getCPUBusEndAddress.
        //
        // Note testRam (FullAddressRam, $0000-$FFFF) is checked first ONLY
        // when present. Most callers don't pass one (test fixtures only).
        if (testRam != null) {
            testRam.cpuBusWrite(addr, value);
            return;
        }
        if (ram != null && addr < 0x2000) {                      // $0000-$1FFF
            ram.cpuBusWrite(addr, value);
        } else if (ppu != null && addr < 0x4000) {               // $2000-$3FFF
            ppu.cpuBusWrite(addr, value);
        } else if (addr < 0x4020) {                              // $4000-$401F
            // Controller window is $4016-$4017 inside the APU window.
            // DMA's $4014 is also inside. Original ordering preserved:
            // controller first (so $4016 strobe writes hit it), DMA before
            // APU (so $4014 sprite-DMA isn't swallowed), APU last.
            if (controller != null && addr >= 0x4016 && addr <= 0x4017) {
                controller.cpuBusWrite(addr, value);
                if (apu != null) {
                    apu.cpuBusWrite(addr, value); // $4017 also = APU frame counter
                }
            } else if (dma != null && addr == 0x4014) {
                dma.cpuBusWrite(addr, value);
            } else if (apu != null) {
                apu.cpuBusWrite(addr, value);
            } else {
                log.error("no device found in range of address {}", address);
            }
        } else if (cartridge != null && addr < 0x8000) {         // $4020-$7FFF
            // Seam S1 (docs/apu-plan.md): route $4020-$7FFF writes to the
            // cartridge — $6000-$7FFF lands in its PRG-RAM (blargg $6000
            // result protocol), $4020-$5FFF is silently dropped by the
            // cartridge (genuinely unmapped). Routing is deliberately
            // scoped: $8000-$FFFF writes keep the historical drop-with-log
            // below — full write routing to mapper registers is a future
            // mapper-branch item that owns its determinism/movie impact.
            cartridge.cpuBusWrite(addr, value);
        } else {
            // $8000-$FFFF (or no cartridge) — CPU writes here just log as
            // a "no device" miss (NROM PRG is read-only). Format is now
            // regex-free in the html stub so the cost is small.
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
        // Address-range checks inlined as integer comparisons — was 3
        // virtual calls per check, ~5.6% of CPU on the web build. Ordering
        // and address constants match write() and each component's own
        // getCPUBusStartAddress/getCPUBusEndAddress contract.
        if (testRam != null) {
            return testRam.cpuBusRead(addr, readOnly);
        }
        if (ram != null && addr < 0x2000) {                      // $0000-$1FFF
            return ram.cpuBusRead(addr, readOnly);
        }
        if (ppu != null && addr < 0x4000) {                      // $2000-$3FFF
            return ppu.cpuBusRead(addr, readOnly);
        }
        if (addr < 0x4020) {                                     // $4000-$401F
            // Controller ($4016-$4017) before APU on the read path: on
            // real hardware those addresses route to the controller port,
            // not the APU. DMA ($4014) is write-only; reads return 0
            // via apu fallthrough is fine — keep dma read for ordering
            // symmetry with write().
            if (controller != null && addr >= 0x4016 && addr <= 0x4017) {
                return controller.cpuBusRead(addr, readOnly);
            }
            if (dma != null && addr == 0x4014) {
                return dma.cpuBusRead(addr, readOnly);
            }
            if (apu != null) {
                return apu.cpuBusRead(addr, readOnly);
            }
        } else if (cartridge != null) {                          // $4020-$FFFF
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
        // Seam S3 (docs/apu-plan.md): reset acts as $4015 = $00 on the
        // APU while the last $4017 value is retained (apu_reset ROMs).
        if (apu != null) apu.reset();
        masterClockCount = 0;
        phase = 0;
    }

    public void clock() {
        // Called ~89k times per emulated frame. Plain null-guards instead of
        // Optional.ifPresent(lambda); the lambdas captured each call were the
        // dominant allocation source on the TeaVM web build.
        if (ppu != null) ppu.clock();
        // ppu is 3x faster than the cpu — phase counter avoids
        // (masterClockCount % 3) Long_rem on TeaVM.
        if (phase == 0) {
            // Seam S2 (docs/apu-plan.md): the APU clocks once per CPU
            // cycle, FIRST in the branch, before the DMA/CPU turn
            // consumer — the APU never stops, even during DMA stalls.
            // This ordering is part of the movie-determinism contract
            // and does not change in later phases.
            if (apu != null) apu.clock();
            // DMA preempts the CPU when active: instead of cpu.clock() this
            // CPU-turn goes to the DMA state machine. CPU is suspended for
            // the duration of the DMA burst (513 or 514 CPU cycles).
            if (dma != null && dma.isActive()) {
                dma.tickDmaCycle(this, ppu, masterClockCount);
            } else if (cpu != null) {
                cpu.clock();
            }
        }
        masterClockCount++;
        phase++;
        if (phase == 3) phase = 0;
    }

}
