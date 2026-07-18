package net.lomibao.nes.components;

import net.lomibao.nes.components.apu.FrameCounter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * APU bus integration — seams S2 (clock hook) and S3 (reset hook) of
 * docs/apu-plan.md Phase A1, plus register-decode behavior at the APU
 * surface. Grows with A2-A4.
 */
class APUTest {

    // ---------------------------------------------------------------------
    // Seam S2 — one apu.clock() per CPU cycle from the phase==0 branch
    // ---------------------------------------------------------------------

    @Test
    void s2_busClocksApu_oncePerThreeMasterTicks() {
        APU apu = new APU();
        CPUBus bus = CPUBus.builder().apu(apu).build().connect();
        for (int i = 0; i < 9; i++) {
            bus.clock();
        }
        assertEquals(3, apu.frameCounter().cycle(),
                "3 CPU cycles after 9 master ticks — APU clocks on phase 0 only");
    }

    @Test
    void s2_apuKeepsClockingWhileDmaStallsCpu() {
        // The APU never stops: during an OAM-DMA burst the CPU turn goes
        // to the DMA state machine, but apu.clock() runs first regardless.
        APU apu = new APU();
        PPU ppu = new PPU();
        DmaController dma = new DmaController();
        Ram ram = new Ram();
        CPUBus bus = CPUBus.builder()
                .apu(apu).ppu(ppu).dma(dma).ram(ram)
                .build().connect();
        bus.write(0x4014, (byte) 0x02); // start OAM DMA — CPU suspended
        assertTrue(dma.isActive());
        int before = apu.frameCounter().cycle();
        for (int i = 0; i < 30; i++) {
            bus.clock();
        }
        assertEquals(before + 10, apu.frameCounter().cycle(),
                "APU must keep clocking through the DMA stall");
    }

    // ---------------------------------------------------------------------
    // Seam S3 — CPUBus.reset() resets the APU
    // ---------------------------------------------------------------------

    @Test
    void s3_busReset_resetsApu_retaining4017() {
        APU apu = new APU();
        CPUBus bus = CPUBus.builder()
                .cpu(new CPU6502()).ram(new Ram()).apu(apu)
                .build().connect();
        bus.write(0x4017, (byte) 0xC0);
        for (int i = 0; i < 300; i++) {
            bus.clock();
        }
        bus.reset();
        FrameCounter fc = apu.frameCounter();
        assertEquals(0, fc.cycle(), "sequencer repositioned at reset");
        assertTrue(fc.isMode5(), "last $4017 (bit 7) retained across reset");
        assertTrue(fc.isIrqInhibit(), "last $4017 (bit 6) retained across reset");
        assertEquals(0xC0, apu.getLast4017());
    }

    // ---------------------------------------------------------------------
    // Register decode ($4017 → frame counter; power-up state)
    // ---------------------------------------------------------------------

    @Test
    void powerUp_4017IsZero_fourStepIrqEnabled() {
        // D8: faithful $4017 = $00 power-up — 4-step mode, IRQ enabled.
        APU apu = new APU();
        assertEquals(0x00, apu.getLast4017());
        assertFalse(apu.frameCounter().isMode5());
        assertFalse(apu.frameCounter().isIrqInhibit());
    }

    @Test
    void write4017_decodesIntoFrameCounter() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4017, (byte) 0x80);
        assertTrue(apu.frameCounter().isMode5());
        assertFalse(apu.frameCounter().isIrqInhibit());
        apu.cpuBusWrite(0x4017, (byte) 0x40);
        assertFalse(apu.frameCounter().isMode5());
        assertTrue(apu.frameCounter().isIrqInhibit());
    }

    @Test
    void writeOnlyRegisters_readAsZero_notEchoed() {
        // The stub's byte-array echo is gone: APU registers are
        // write-only ($4015 becomes readable in A3).
        APU apu = new APU();
        apu.cpuBusWrite(0x4000, (byte) 0xAB);
        apu.cpuBusWrite(0x4008, (byte) 0xCD);
        assertEquals(0, apu.cpuBusRead(0x4000, false));
        assertEquals(0, apu.cpuBusRead(0x4008, false));
        assertEquals(0, apu.cpuBusRead(0x4017, false));
    }
}
