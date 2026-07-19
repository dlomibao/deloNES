package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D1 — {@link DmcChannel} functional unit (docs/apu-plan.md;
 * spec research doc §1.6 and NESdev "APU DMC"): rate table, 7-bit delta
 * counter with ±2 clamp, shift register + 8-clock refill cadence,
 * memory-reader address/length arithmetic with the $FFFF→$8000 wrap,
 * loop-vs-IRQ last-byte paths, and the $4015 restart/stop rules.
 *
 * <p>The channel itself never touches the CPU bus — the memory reader
 * surfaces {@code needsSampleByte()} and the APU performs the fetch
 * (stall-free in D1; via the D2 stall machine after). Bus wiring and
 * IRQ-flag delivery are covered in {@code ApuDmcTest}.
 */
class DmcChannelTest {

    private final DmcChannel dmc = new DmcChannel();

    // ------------------------------------------------------------------
    // Rate table + $4010 decode
    // ------------------------------------------------------------------

    @Test
    void rateTable_isNtscCpuTableHalvedToApuCycles() {
        int[] cpu = {428, 380, 340, 320, 286, 254, 226, 214,
                190, 160, 142, 128, 106, 84, 72, 54};
        assertEquals(cpu.length, DmcChannel.RATE_TABLE_APU.length);
        for (int i = 0; i < cpu.length; i++) {
            assertEquals(cpu[i] / 2, DmcChannel.RATE_TABLE_APU[i],
                    "rate index " + i + " (timer clocks at APU-cycle rate)");
        }
    }

    @Test
    void writeControl_decodesIrqLoopAndRate() {
        dmc.writeControl(0xCF); // IRQ on, loop on, rate 15
        assertTrue(dmc.isIrqEnabled());
        assertTrue(dmc.isLoopFlag());
        assertEquals(27, dmc.timerPeriod());
        dmc.writeControl(0x00);
        assertFalse(dmc.isIrqEnabled());
        assertFalse(dmc.isLoopFlag());
        assertEquals(214, dmc.timerPeriod());
    }

    // ------------------------------------------------------------------
    // Output unit — delta counter clamp + $4011 direct load
    // ------------------------------------------------------------------

    @Test
    void directLoad_sets7BitOutputLevel() {
        dmc.writeDirectLoad(0xFF); // bit 7 ignored
        assertEquals(0x7F, dmc.output());
        dmc.writeDirectLoad(0x40);
        assertEquals(0x40, dmc.output());
    }

    @Test
    void deltaCounter_clampsAtUpperBound() {
        dmc.silence = false;
        dmc.shiftRegister = 0xFF; // all 1-bits → +2 per clock
        dmc.outputLevel = 124;
        dmc.clockOutput();
        assertEquals(126, dmc.output(), "124 ≤ 125 → +2");
        dmc.clockOutput();
        assertEquals(126, dmc.output(), "126 > 125 → clamp, no change");
    }

    @Test
    void deltaCounter_clampsAtLowerBound() {
        dmc.silence = false;
        dmc.shiftRegister = 0x00; // all 0-bits → −2 per clock
        dmc.outputLevel = 3;
        dmc.clockOutput();
        assertEquals(1, dmc.output(), "3 ≥ 2 → −2");
        dmc.clockOutput();
        assertEquals(1, dmc.output(), "1 < 2 → clamp, no change");
    }

    @Test
    void silenceFlag_freezesDeltaCounter() {
        dmc.silence = true;
        dmc.shiftRegister = 0xFF;
        dmc.outputLevel = 60;
        dmc.clockOutput();
        assertEquals(60, dmc.output(), "silenced output cycle never moves the level");
    }

    // ------------------------------------------------------------------
    // Shift register + 8-clock refill cadence
    // ------------------------------------------------------------------

    @Test
    void refill_after8Clocks_loadsShiftRegisterFromBuffer() {
        dmc.bufferFilled = true;
        dmc.sampleBuffer = 0xA5;
        dmc.bitsRemaining = 8;
        for (int i = 0; i < 8; i++) {
            dmc.clockOutput();
        }
        assertEquals(0xA5, dmc.shiftRegister, "buffer → shift register at the 8-clock boundary");
        assertFalse(dmc.bufferFilled, "buffer emptied by the refill");
        assertFalse(dmc.silence, "playback un-silences when a byte is available");
        assertEquals(8, dmc.bitsRemaining, "bit counter restarts at 8");
    }

    @Test
    void refill_withEmptyBuffer_setsSilence() {
        dmc.bufferFilled = false;
        dmc.silence = false;
        dmc.bitsRemaining = 1;
        dmc.clockOutput();
        assertTrue(dmc.silence, "empty buffer at the refill boundary silences the output");
    }

    @Test
    void timer_clocksOutputEveryPeriodApuCycles() {
        dmc.writeControl(0x0F); // rate 15 → period 27 APU cycles
        dmc.timerCounter = 0;
        dmc.bitsRemaining = 8;
        dmc.clockTimer(); // fires immediately, reloads counter
        assertEquals(7, dmc.bitsRemaining);
        for (int i = 0; i < 26; i++) {
            dmc.clockTimer();
        }
        assertEquals(7, dmc.bitsRemaining, "26 further APU cycles — still counting down");
        dmc.clockTimer();
        assertEquals(6, dmc.bitsRemaining, "27th APU cycle fires the next output clock");
    }

    // ------------------------------------------------------------------
    // Memory reader — address/length arithmetic, wrap, loop/IRQ paths
    // ------------------------------------------------------------------

    @Test
    void sampleAddress_isC000PlusATimes64() {
        dmc.writeSampleAddress(0x02);
        dmc.setEnabled(true); // bytes==0 → restart latches the address
        assertEquals(0xC080, dmc.currentAddress());
        dmc.setEnabled(false);
        dmc.writeSampleAddress(0xFF);
        dmc.setEnabled(true);
        assertEquals(0xC000 + 0xFF * 64, dmc.currentAddress());
    }

    @Test
    void sampleLength_isLTimes16Plus1() {
        dmc.writeSampleLength(0x02);
        dmc.setEnabled(true);
        assertEquals(33, dmc.bytesRemaining());
        dmc.setEnabled(false);
        dmc.writeSampleLength(0x00);
        dmc.setEnabled(true);
        assertEquals(1, dmc.bytesRemaining(), "L=0 still means a 1-byte sample");
    }

    @Test
    void readerAddress_wrapsFFFFto8000() {
        dmc.writeSampleLength(0x01); // 17 bytes — plenty
        dmc.setEnabled(true);
        dmc.currentAddress = 0xFFFF;
        dmc.acceptSampleByte(0x12);
        assertEquals(0x8000, dmc.currentAddress(), "reader wraps $FFFF → $8000, not $0000");
    }

    @Test
    void lastByte_withLoopFlag_reloadsAddressAndLength_noIrq() {
        dmc.writeControl(0xC0); // IRQ enabled AND loop — loop wins
        dmc.writeSampleAddress(0x04);
        dmc.writeSampleLength(0x00); // 1 byte
        dmc.setEnabled(true);
        assertTrue(dmc.needsSampleByte());
        boolean irq = dmc.acceptSampleByte(0x55);
        assertFalse(irq, "loop path never raises the IRQ");
        assertEquals(1, dmc.bytesRemaining(), "length reloaded");
        assertEquals(0xC100, dmc.currentAddress(), "address reloaded");
    }

    @Test
    void lastByteFetch_withIrqEnabled_raisesIrq_beforeByteDrains() {
        dmc.writeControl(0x80); // IRQ enabled, no loop
        dmc.writeSampleLength(0x00); // 1 byte
        dmc.setEnabled(true);
        boolean irq = dmc.acceptSampleByte(0x55);
        assertTrue(irq, "IRQ fires when the LAST BYTE IS FETCHED — not when it finishes playing");
        assertEquals(0, dmc.bytesRemaining());
        assertTrue(dmc.bufferFilled, "the fetched byte is still buffered, unplayed");
    }

    @Test
    void lastByte_withIrqDisabled_raisesNothing() {
        dmc.writeControl(0x00);
        dmc.writeSampleLength(0x00);
        dmc.setEnabled(true);
        assertFalse(dmc.acceptSampleByte(0x55));
        assertEquals(0, dmc.bytesRemaining());
    }

    // ------------------------------------------------------------------
    // $4015 restart/stop rules
    // ------------------------------------------------------------------

    @Test
    void enable_withBytesRemaining_doesNotRestart() {
        dmc.writeSampleAddress(0x01);
        dmc.writeSampleLength(0x01); // 17 bytes
        dmc.setEnabled(true);
        dmc.acceptSampleByte(0x00); // 16 left, address advanced
        int addr = dmc.currentAddress();
        dmc.setEnabled(true); // re-enable mid-sample
        assertEquals(16, dmc.bytesRemaining(), "no restart while bytes remain");
        assertEquals(addr, dmc.currentAddress());
    }

    @Test
    void disable_zeroesBytesRemaining_butBufferedByteStillPlays() {
        dmc.writeSampleLength(0x01);
        dmc.setEnabled(true);
        dmc.acceptSampleByte(0x0F);
        dmc.setEnabled(false);
        assertEquals(0, dmc.bytesRemaining(), "$4015 bit-4 clear → bytes := 0");
        assertTrue(dmc.bufferFilled, "the already-buffered byte is retained (plays out)");
        assertFalse(dmc.needsSampleByte(), "no further fetches while disabled");
    }

    @Test
    void needsSampleByte_onlyWhenBufferEmptyAndBytesRemain() {
        assertFalse(dmc.needsSampleByte(), "power-up: no bytes remaining");
        dmc.writeSampleLength(0x00);
        dmc.setEnabled(true);
        assertTrue(dmc.needsSampleByte(), "enabled + empty buffer → fetch wanted");
        dmc.acceptSampleByte(0x01);
        assertFalse(dmc.needsSampleByte(), "buffer full → no fetch");
    }

    // ------------------------------------------------------------------
    // Reset — $4011 &= 1 (research §1.9)
    // ------------------------------------------------------------------

    @Test
    void resetOutputLevel_appliesAnd1() {
        dmc.writeDirectLoad(0x55);
        dmc.resetOutputLevel();
        assertEquals(1, dmc.output(), "reset applies $4011 &= 1 — odd level → 1");
        dmc.writeDirectLoad(0x54);
        dmc.resetOutputLevel();
        assertEquals(0, dmc.output(), "even level → 0");
    }
}
