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

    // ---------------------------------------------------------------------
    // A2 — length-counter decode + half-frame cadence
    // ---------------------------------------------------------------------

    @Test
    void a2_haltFlags_decodeFromChannelRegisters() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4000, (byte) 0x20);
        apu.cpuBusWrite(0x4004, (byte) 0x20);
        apu.cpuBusWrite(0x4008, (byte) 0x80); // triangle: bit 7
        apu.cpuBusWrite(0x400C, (byte) 0x20);
        assertTrue(apu.pulse1().lengthCounter().isHalt());
        assertTrue(apu.pulse2().lengthCounter().isHalt());
        assertTrue(apu.triangle().lengthCounter().isHalt());
        assertTrue(apu.noise().lengthCounter().isHalt());
        apu.cpuBusWrite(0x4008, (byte) 0x7F); // bit 7 clear despite bit 5 set
        assertFalse(apu.triangle().lengthCounter().isHalt(),
                "triangle halt is bit 7, not bit 5");
    }

    @Test
    void a2_lengthLoads_decodeBits7to3_perChannel() {
        APU apu = new APU();
        apu.pulse1().lengthCounter().setEnabled(true);
        apu.pulse2().lengthCounter().setEnabled(true);
        apu.triangle().lengthCounter().setEnabled(true);
        apu.noise().lengthCounter().setEnabled(true);
        apu.cpuBusWrite(0x4003, (byte) 0x08); // index 1 → 254
        apu.cpuBusWrite(0x4007, (byte) 0x10); // index 2 → 20
        apu.cpuBusWrite(0x400B, (byte) 0x18); // index 3 → 2
        apu.cpuBusWrite(0x400F, (byte) 0x00); // index 0 → 10
        assertEquals(254, apu.pulse1().lengthCounter().value());
        assertEquals(20, apu.pulse2().lengthCounter().value());
        assertEquals(2, apu.triangle().lengthCounter().value());
        assertEquals(10, apu.noise().lengthCounter().value());
    }

    @Test
    void a2_disabledChannel_blocksLoadThroughRegisterWrite() {
        APU apu = new APU(); // power-up: all channels disabled
        apu.cpuBusWrite(0x4003, (byte) 0x08);
        assertEquals(0, apu.pulse1().lengthCounter().value(),
                "load must be blocked while the channel is disabled");
    }

    @Test
    void a2_halfFrameCadence_clocksAllFourLengthCounters() {
        APU apu = new APU();
        apu.pulse1().lengthCounter().setEnabled(true);
        apu.pulse2().lengthCounter().setEnabled(true);
        apu.triangle().lengthCounter().setEnabled(true);
        apu.noise().lengthCounter().setEnabled(true);
        apu.cpuBusWrite(0x4003, (byte) 0x00); // 10
        apu.cpuBusWrite(0x4007, (byte) 0x00);
        apu.cpuBusWrite(0x400B, (byte) 0x00);
        apu.cpuBusWrite(0x400F, (byte) 0x00);
        // First half-frame clock lands at CPU cycle 14913 (mode 0).
        for (int i = 0; i < 14912; i++) {
            apu.clock();
        }
        assertEquals(10, apu.pulse1().lengthCounter().value(), "no half clock before 14913");
        apu.clock(); // 14913
        assertEquals(9, apu.pulse1().lengthCounter().value());
        assertEquals(9, apu.pulse2().lengthCounter().value());
        assertEquals(9, apu.triangle().lengthCounter().value());
        assertEquals(9, apu.noise().lengthCounter().value());
        // Second half clock of the period at 29829.
        for (int i = 14913; i < 29829; i++) {
            apu.clock();
        }
        assertEquals(8, apu.noise().lengthCounter().value(), "second half clock at 29829");
    }

    @Test
    void a2_immediate4017Clock_alsoClocksLengthCounters() {
        APU apu = new APU();
        apu.pulse1().lengthCounter().setEnabled(true);
        apu.cpuBusWrite(0x4003, (byte) 0x00); // 10
        apu.cpuBusWrite(0x4017, (byte) 0x80); // bit 7 → immediate quarter+half
        assertEquals(9, apu.pulse1().lengthCounter().value(),
                "$4017 bit-7 write force-clocks the length counters");
    }

    // ---------------------------------------------------------------------
    // A3 — $4015 semantics (write `---D NT21`, read `IF-D NT21`)
    // ---------------------------------------------------------------------

    /** Clock the APU to CPU cycle 29828 — the frame IRQ flag set point. */
    private static APU apuWithFrameIrqSet() {
        APU apu = new APU();
        for (int i = 0; i < 29828; i++) {
            apu.clock();
        }
        assertTrue(apu.frameCounter().isFrameIrqFlag());
        return apu;
    }

    @Test
    void a3_write4015_enablesChannels_loadsThenWork() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x0F);
        apu.cpuBusWrite(0x4003, (byte) 0x08); // 254
        apu.cpuBusWrite(0x4007, (byte) 0x08);
        apu.cpuBusWrite(0x400B, (byte) 0x08);
        apu.cpuBusWrite(0x400F, (byte) 0x08);
        assertEquals(0x0F, apu.cpuBusRead(0x4015, true) & 0x0F,
                "all four NT21 bits set while lengths are non-zero");
        assertFalse(apu.isDmcEnabled());
    }

    @Test
    void a3_write4015_clearBit_forcesLengthToZero() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x0F);
        apu.cpuBusWrite(0x4003, (byte) 0x08);
        apu.cpuBusWrite(0x400F, (byte) 0x08);
        apu.cpuBusWrite(0x4015, (byte) 0x08); // disable pulse1, keep noise
        assertEquals(0, apu.pulse1().lengthCounter().value(),
                "clear-length-on-disable");
        assertEquals(0x08, apu.cpuBusRead(0x4015, true) & 0x0F,
                "only the still-enabled noise bit remains");
    }

    @Test
    void a3_read4015_bitMapping_NT21() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x05); // pulse1 + triangle
        apu.cpuBusWrite(0x4003, (byte) 0x08);
        apu.cpuBusWrite(0x400B, (byte) 0x08);
        assertEquals(0x05, apu.cpuBusRead(0x4015, true),
                "bit0=pulse1, bit2=triangle; bits 4/5/6/7 clear");
    }

    @Test
    void a3_read4015_clearsFrameIrqFlag() {
        APU apu = apuWithFrameIrqSet();
        apu.clock(); // move off the set cycle (29829 also sets; go past)
        apu.clock();
        apu.clock(); // now at a plain cycle (post-wrap)
        assertEquals(0x40, apu.cpuBusRead(0x4015, false) & 0x40, "bit 6 reads 1");
        assertEquals(0, apu.cpuBusRead(0x4015, false) & 0x40,
                "read must clear the frame IRQ flag");
    }

    @Test
    void a3_read4015_sameCycleAsSet_returns1WithoutClearing() {
        APU apu = apuWithFrameIrqSet(); // current cycle 29828 = a set cycle
        assertEquals(0x40, apu.cpuBusRead(0x4015, false) & 0x40,
                "same-cycle read returns 1");
        assertTrue(apu.frameCounter().isFrameIrqFlag(),
                "…but must not clear the flag (§1.7 race)");
    }

    @Test
    void a3_readOnlyPeek_neverClearsFrameIrqFlag() {
        APU apu = apuWithFrameIrqSet();
        for (int i = 0; i < 5; i++) {
            apu.clock();
        }
        assertEquals(0x40, apu.cpuBusRead(0x4015, true) & 0x40);
        assertTrue(apu.frameCounter().isFrameIrqFlag(),
                "readOnly observation must be side-effect free");
    }

    @Test
    void a3_write4017_doesNotClearFrameFlag_withoutBit6() {
        APU apu = apuWithFrameIrqSet();
        apu.cpuBusWrite(0x4017, (byte) 0x00);
        assertTrue(apu.frameCounter().isFrameIrqFlag(),
                "$4017 write without bit 6 must not clear the frame flag");
    }

    @Test
    void a3_write4015_doesNotClearFrameFlag_butClearsDmcFlag() {
        APU apu = apuWithFrameIrqSet();
        apu.dmcIrqFlag = true; // Phase D sets this for real
        assertEquals(0xC0, apu.cpuBusRead(0x4015, true) & 0xC0,
                "bit7=DMC IRQ, bit6=frame IRQ");
        apu.cpuBusWrite(0x4015, (byte) 0x1F);
        assertFalse(apu.dmcIrqFlag, "$4015 write clears the DMC IRQ flag");
        assertTrue(apu.frameCounter().isFrameIrqFlag(),
                "$4015 write never clears the frame IRQ flag");
    }

    @Test
    void a3_read4015_doesNotClearDmcFlag() {
        APU apu = new APU();
        apu.dmcIrqFlag = true;
        apu.cpuBusRead(0x4015, false);
        assertTrue(apu.dmcIrqFlag, "$4015 read clears frame flag only — not DMC");
    }

    @Test
    void a3_bits4And5_readZero_untilPhaseD() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x10); // DMC enable bit stored…
        assertTrue(apu.isDmcEnabled());
        assertEquals(0, apu.cpuBusRead(0x4015, true) & 0x30,
                "bit4 (DMC bytes remaining) and bit5 (open bus) read 0");
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
