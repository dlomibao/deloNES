package net.lomibao.nes.components.apu;

import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.CPUBus;
import net.lomibao.nes.components.Cartridge;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D1 — APU↔DMC wiring (docs/apu-plan.md): $4010-$4013 register
 * decode, $4015 bit-4 (bytes remaining) / bit-7 (DMC IRQ) status,
 * write-clears-DMC-IRQ, $4010 bit-7-clear clears the flag, the fetch
 * through {@code cpuBus.read()} (arriving on the last cycle of the D2
 * stall — {@code clockApu} consumes stalls the way the bus's CPU-turn
 * slot does), DMC IRQ delivery on last-byte fetch, and reset's
 * {@code $4011 &= 1}. Stall/arbitration behavior proper is pinned in
 * {@code ApuDmcStallTest}.
 */
class ApuDmcTest {

    /** Synthetic 16KB NROM whose PRG byte at offset i is (i &amp; 0xFF). */
    private static Cartridge patternCartridge() {
        byte[] prg = new byte[16 * 1024];
        for (int i = 0; i < prg.length; i++) {
            prg[i] = (byte) i;
        }
        prg[0x3FFC] = 0x00; // reset vector → $8000 (unused; CPU never clocks)
        prg[0x3FFD] = (byte) 0x80;
        byte[] rom = new byte[16 + prg.length + 8 * 1024];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;
        rom[5] = 1;
        System.arraycopy(prg, 0, rom, 16, prg.length);
        return new Cartridge(new ByteArrayInputStream(rom), "dmc-pattern.nes");
    }

    private final APU apu = new APU();
    private final CPUBus bus = CPUBus.builder()
            .cpu(new CPU6502())
            .apu(apu)
            .cartridge(patternCartridge())
            .build()
            .connect();

    /**
     * Clock the APU n CPU cycles, consuming any DMC stall the way the
     * bus's CPU-turn slot does (seam S5) so fetches actually land.
     */
    private void clockApu(int n) {
        for (int i = 0; i < n; i++) {
            apu.clock();
            if (apu.dmcStallPending()) {
                apu.tickDmcStall();
            }
        }
    }

    // ------------------------------------------------------------------
    // Register decode
    // ------------------------------------------------------------------

    @Test
    void registers4010to4013_decodeIntoDmcState() {
        bus.write(0x4010, (byte) 0x4E); // loop, rate 14
        bus.write(0x4011, (byte) 0x3C);
        bus.write(0x4012, (byte) 0x05);
        bus.write(0x4013, (byte) 0x03);
        DmcChannel dmc = apu.dmc();
        assertFalse(dmc.isIrqEnabled());
        assertTrue(dmc.isLoopFlag());
        assertEquals(36, dmc.timerPeriod());
        assertEquals(0x3C, dmc.output());
        assertEquals(0xC000 + 5 * 64, dmc.sampleAddress);
        assertEquals(3 * 16 + 1, dmc.sampleLength);
    }

    // ------------------------------------------------------------------
    // $4015 bit 4 + the stall-free enable fetch
    // ------------------------------------------------------------------

    @Test
    void enable_fetchesFirstByteThroughBus_onTheLastStartStallCycle() {
        bus.write(0x4012, (byte) 0x02); // $C080 → PRG offset $80
        bus.write(0x4013, (byte) 0x01); // 17 bytes
        bus.write(0x4015, (byte) 0x10);
        DmcChannel dmc = apu.dmc();
        assertTrue(apu.dmcStallPending(), "D2: the start fetch rides a 3-cycle stall");
        assertFalse(dmc.bufferFilled, "no magic arrival — the fetch waits for the stall");
        clockApu(3); // the CPU-turn slots consume the stall; fetch on the last
        assertTrue(dmc.bufferFilled);
        assertEquals(0x80, dmc.sampleBuffer, "byte read via cpuBus.read() at $C080");
        assertEquals(0xC081, dmc.currentAddress());
        assertEquals(16, dmc.bytesRemaining(), "fetch consumed one of the 17 bytes");
    }

    @Test
    void status_bit4_tracksBytesRemaining() {
        bus.write(0x4013, (byte) 0x01); // 17 bytes
        bus.write(0x4015, (byte) 0x10);
        assertEquals(0x10, bus.read(0x4015) & 0x10, "bytes remaining > 0 → bit 4 set");
        bus.write(0x4015, (byte) 0x00); // stop → bytes := 0
        assertEquals(0, bus.read(0x4015) & 0x10, "disabled → bytes := 0 → bit 4 clear");
    }

    @Test
    void playback_drainsBytes_viaTimerRefills() {
        bus.write(0x4010, (byte) 0x0F); // rate 15 → 54 CPU cycles per bit
        bus.write(0x4013, (byte) 0x01); // 17 bytes
        bus.write(0x4015, (byte) 0x10);
        // One byte = 8 bits × 54 CPU cycles = 432 CPU cycles. Run two
        // byte-periods plus slack: refill fetches must drain the reader.
        clockApu(2 * 432 + 8);
        assertTrue(apu.dmc().bytesRemaining() <= 14,
                "refill fetches drain bytes as playback proceeds, got "
                        + apu.dmc().bytesRemaining());
    }

    // ------------------------------------------------------------------
    // DMC IRQ — set on last-byte fetch, cleared per $4015/$4010 rules
    // ------------------------------------------------------------------

    @Test
    void dmcIrq_onLastByteFetch_isLevelHeld_andReadDoesNotClear() {
        bus.write(0x4010, (byte) 0x80); // IRQ enabled, no loop
        bus.write(0x4013, (byte) 0x00); // 1-byte sample
        bus.write(0x4015, (byte) 0x10); // enable → the start-stall fetch IS the last byte
        clockApu(3); // consume the 3-cycle start stall — fetch on the last cycle
        assertTrue(apu.irqAsserted(), "DMC IRQ fires on the last-byte FETCH");
        assertEquals(0x80, bus.read(0x4015) & 0x80, "bit 7 reads set");
        assertEquals(0x80, bus.read(0x4015) & 0x80, "$4015 read does NOT clear the DMC flag");
        bus.write(0x4015, (byte) 0x00);
        assertFalse(apu.irqAsserted(), "$4015 write clears the DMC IRQ flag");
    }

    @Test
    void write4010_bit7Clear_clearsDmcIrqFlag() {
        bus.write(0x4010, (byte) 0x80);
        bus.write(0x4013, (byte) 0x00);
        bus.write(0x4015, (byte) 0x10);
        clockApu(3); // consume the start stall — last-byte fetch raises the IRQ
        assertTrue(apu.irqAsserted());
        bus.write(0x4010, (byte) 0x00); // IRQ-enable cleared → flag cleared
        assertFalse(apu.irqAsserted(), "$4010 bit-7 clear also clears the DMC IRQ flag");
    }

    @Test
    void loopedSample_neverRaisesIrq_andKeepsFetching() {
        bus.write(0x4010, (byte) 0xC0); // IRQ enabled AND loop — loop wins
        bus.write(0x4013, (byte) 0x00); // 1-byte sample, reloads forever
        bus.write(0x4015, (byte) 0x10);
        clockApu(3 * 8 * 214 * 2); // several byte periods at rate 0
        assertFalse(apu.irqAsserted(), "loop path never raises the IRQ");
        assertEquals(0x10, bus.read(0x4015) & 0x10, "looping sample always has bytes remaining");
    }

    // ------------------------------------------------------------------
    // Timer cadence — APU-cycle rate (every 2nd CPU cycle)
    // ------------------------------------------------------------------

    @Test
    void dmcTimer_clocksAtApuCycleRate() {
        DmcChannel dmc = apu.dmc();
        dmc.writeControl(0x0F); // period 27 APU cycles
        dmc.timerCounter = 5;
        clockApu(2);
        assertEquals(4, dmc.timerCounter, "one timer step per 2 CPU cycles");
        clockApu(1);
        assertEquals(4, dmc.timerCounter, "odd CPU cycle — no step");
    }

    // ------------------------------------------------------------------
    // Reset — acts as $4015=$00 for the DMC and applies $4011 &= 1
    // ------------------------------------------------------------------

    @Test
    void reset_applies4011And1_andStopsReader() {
        bus.write(0x4011, (byte) 0x55);
        bus.write(0x4013, (byte) 0x01);
        bus.write(0x4015, (byte) 0x10);
        apu.reset();
        assertEquals(1, apu.dmc().output(), "reset applies $4011 &= 1");
        assertEquals(0, apu.dmc().bytesRemaining(), "reset acts as $4015 = $00 — reader stopped");
        assertEquals(0, bus.read(0x4015) & 0x90, "bits 4/7 clear after reset");
    }
}
