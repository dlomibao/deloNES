package net.lomibao.nes;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.CPUBus;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.FullAddressRam;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;

/**
 * System orchestrator that sits above {@link CPUBus} and exposes a single
 * {@code tick()} / {@code runFrame()} entry point for hosts (LibGDX render
 * loop, headless tests, debug launchers).
 *
 * <p>This is Step 1 of the playable-gen1 plan
 * (see {@code docs/impl_plan_playable_gen1.md}). It deliberately starts as a
 * thin facade over the existing {@link CPUBus#clock()} master-tick (which
 * already drives PPU every call and CPU every 3rd call). Later steps will
 * extend this class with NMI hook polling (Step 2), DMA arbitration
 * (Step 4), frame pacing (Step 8), and a frame-rendered listener.
 *
 * <p>Wiring is done via Lombok {@code @Builder}; supply at minimum a CPU,
 * RAM, and PPU. A cartridge is optional for tests that synthesise programs
 * directly into RAM. The constructor connects every supplied component to
 * the bus.
 */
@Log4j2
@Getter
public class NesSystem {
    private final CPU6502 cpu;
    private final PPU ppu;
    private final CPUBus cpuBus;

    @Builder
    private NesSystem(CPU6502 cpu,
                      Ram ram,
                      PPU ppu,
                      APU apu,
                      Cartridge cartridge,
                      Controller controller,
                      FullAddressRam testRam) {
        this.cpu = cpu;
        this.ppu = ppu;
        this.cpuBus = CPUBus.builder()
                .cpu(cpu)
                .ram(ram)
                .ppu(ppu)
                .apu(apu)
                .cartridge(cartridge)
                .controller(controller)
                .testRam(testRam)
                .build()
                .connect();
    }

    /**
     * Advance the system by exactly one master tick. Delegates to
     * {@link CPUBus#clock()} which advances the PPU every call and the CPU
     * every third call (the NES PPU runs at 3× CPU speed).
     */
    public void tick() {
        cpuBus.clock();
    }

    /**
     * Run master ticks until the PPU signals frame complete, then consume
     * (clear) the flag and return. After this call, {@code ppu.isFrameComplete()}
     * is false and the PPU is positioned at the start of the next frame.
     *
     * <p>A safety cap of 3× the nominal frame length (341*262*3 = 268,026
     * ticks) prevents an infinite loop if rendering state is wedged. If the
     * cap trips, this method logs an error and returns.
     */
    public void runFrame() {
        long maxTicks = 341L * 262L * 3L; // 3× safety margin
        long start = cpuBus.getMasterClockCount();
        while (!ppu.isFrameComplete()) {
            tick();
            if (cpuBus.getMasterClockCount() - start > maxTicks) {
                log.error("runFrame() safety cap tripped after {} ticks without frame-complete",
                        cpuBus.getMasterClockCount() - start);
                return;
            }
        }
        // Consume the flag so a subsequent call has a fresh frame to wait on.
        ppu.clearFrameComplete();
    }

    /**
     * Reset the system to power-on state. Resets the CPU and zeroes the
     * master-clock count via {@link CPUBus#reset()}.
     */
    public void reset() {
        cpuBus.reset();
    }

    /**
     * Convenience for callers that want the master-clock count without
     * reaching through {@link #getCpuBus()}.
     */
    public long getMasterClockCount() {
        return cpuBus.getMasterClockCount();
    }
}
