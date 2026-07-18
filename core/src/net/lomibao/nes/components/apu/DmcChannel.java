package net.lomibao.nes.components.apu;

/**
 * Delta modulation channel (DMC), functional unit — docs/apu-plan.md
 * Phase D1; spec research doc §1.6 and NESdev "APU DMC".
 *
 * <p>Three cooperating pieces:
 * <ul>
 *   <li><b>Timer</b> — clocks at APU-cycle rate (every 2nd CPU cycle)
 *       with the NTSC rate table stored halved to APU cycles (the
 *       {@link NoiseChannel} convention).</li>
 *   <li><b>Output unit</b> — 7-bit delta counter 0-127; per timer clock,
 *       when not silenced, shift-register bit 1 → +2 (clamped ≤ 125
 *       before the add), bit 0 → −2 (clamped ≥ 2); every 8 clocks the
 *       shift register refills from the 1-byte sample buffer or the
 *       silence flag is set. $4011 loads the counter directly.</li>
 *   <li><b>Memory reader</b> — sample address $C000 + A×64, length
 *       L×16 + 1 bytes, address wrap $FFFF → $8000. When the buffer is
 *       empty and bytes remain, {@link #needsSampleByte()} goes high;
 *       the APU performs the actual {@code cpuBus.read()} and hands the
 *       byte to {@link #acceptSampleByte(int)} (stall-free in D1; via
 *       the D2 stall machine after). On the LAST byte fetched: loop flag
 *       → reload address/length; else IRQ-enable → the accept call
 *       returns {@code true} and the APU raises the DMC IRQ flag (the
 *       flag itself lives in {@code APU} beside the $4015 semantics that
 *       clear it).</li>
 * </ul>
 *
 * <p>Reset (research §1.9): the APU applies {@code $4011 &= 1} via
 * {@link #resetOutputLevel()} and acts as $4015 = $00 (reader stopped).
 *
 * <p>TeaVM hot path: int-only, no allocation, static final table.
 * Fields package-visible for unit tests in this package.
 */
public final class DmcChannel {

    /**
     * NTSC rate table in APU cycles (research §1.6 lists CPU cycles per
     * output bit — 428, 380, … 54, all even — and the DMC timer clocks
     * every 2nd CPU cycle, so these are the halved values).
     */
    static final int[] RATE_TABLE_APU = {
            214, 190, 170, 160, 143, 127, 113, 107,
            95, 80, 71, 64, 53, 42, 36, 27,
    };

    // -- $4010 control --
    /** Bit 7 — raise the DMC IRQ when the last sample byte is fetched. */
    boolean irqEnabled;
    /** Bit 6 — on the last byte, reload address/length instead of stopping. */
    boolean loopFlag;
    /** Output-bit interval in APU cycles (from {@link #RATE_TABLE_APU}). */
    int timerPeriod = RATE_TABLE_APU[0];
    /** In-flight countdown. */
    int timerCounter;

    // -- output unit --
    /** 7-bit delta counter 0-127 — the channel's output level. */
    int outputLevel;
    /** Current sample byte being shifted out, LSB first. */
    int shiftRegister;
    /** Bits left in the current output cycle (8 → 1). */
    int bitsRemaining = 8;
    /** Set when a refill found the buffer empty — freezes the level. */
    boolean silence = true;

    // -- 1-byte sample buffer --
    boolean bufferFilled;
    int sampleBuffer;

    // -- memory reader --
    /** Programmed start address ($4012): $C000 + A×64. */
    int sampleAddress = 0xC000;
    /** Programmed length ($4013): L×16 + 1 bytes. */
    int sampleLength = 1;
    /** Next fetch address (wraps $FFFF → $8000). */
    int currentAddress = 0xC000;
    /** Bytes left in the current sample; 0 = reader stopped. */
    int bytesRemaining;

    /** $4010 — IRQ enable (bit 7), loop (bit 6), rate index (bits 3-0). */
    public void writeControl(int value) {
        irqEnabled = (value & 0x80) != 0;
        loopFlag = (value & 0x40) != 0;
        timerPeriod = RATE_TABLE_APU[value & 0x0F];
    }

    /** $4011 — direct load of the 7-bit delta counter (PCM-by-CPU). */
    public void writeDirectLoad(int value) {
        outputLevel = value & 0x7F;
    }

    /** $4012 — sample address = $C000 + A×64. */
    public void writeSampleAddress(int value) {
        sampleAddress = 0xC000 + ((value & 0xFF) << 6);
    }

    /** $4013 — sample length = L×16 + 1 bytes. */
    public void writeSampleLength(int value) {
        sampleLength = ((value & 0xFF) << 4) + 1;
    }

    /**
     * $4015 bit-4 semantics (research §1.7): set with bytes-remaining
     * == 0 → restart the sample (latch address/length); set mid-sample →
     * no effect; clear → bytes := 0 (the buffered byte still plays out).
     */
    public void setEnabled(boolean enabled) {
        if (!enabled) {
            bytesRemaining = 0;
        } else if (bytesRemaining == 0) {
            currentAddress = sampleAddress;
            bytesRemaining = sampleLength;
        }
    }

    /** True when the memory reader wants a fetch (buffer empty, bytes left). */
    public boolean needsSampleByte() {
        return !bufferFilled && bytesRemaining > 0;
    }

    /**
     * Deliver a fetched sample byte (the APU read it off the CPU bus).
     * Advances/wraps the reader address and consumes one byte; on the
     * last byte, the loop flag reloads address/length, otherwise the
     * return value tells the APU to raise the DMC IRQ flag (IRQ fires on
     * the last-byte FETCH, not when the byte finishes playing).
     *
     * @return true when the DMC IRQ should be raised
     */
    public boolean acceptSampleByte(int value) {
        sampleBuffer = value & 0xFF;
        bufferFilled = true;
        currentAddress = currentAddress == 0xFFFF ? 0x8000 : currentAddress + 1;
        bytesRemaining--;
        if (bytesRemaining == 0) {
            if (loopFlag) {
                currentAddress = sampleAddress;
                bytesRemaining = sampleLength;
            } else if (irqEnabled) {
                return true;
            }
        }
        return false;
    }

    /**
     * APU-cycle clock: fires an output-unit step every {@code timerPeriod}.
     *
     * @return true when an output-unit step fired this cycle — the only
     *         clock-driven event that can raise {@link #needsSampleByte()}
     *         (a refill draining the buffer), so the APU polls the reader
     *         only on these cycles (D9 hot-path economy)
     */
    public boolean clockTimer() {
        if (timerCounter == 0) {
            timerCounter = timerPeriod - 1;
            clockOutput();
            return true;
        }
        timerCounter--;
        return false;
    }

    /** One output-unit step (research §1.6). */
    void clockOutput() {
        if (!silence) {
            if ((shiftRegister & 1) != 0) {
                if (outputLevel <= 125) {
                    outputLevel += 2;
                }
            } else if (outputLevel >= 2) {
                outputLevel -= 2;
            }
        }
        shiftRegister >>= 1;
        bitsRemaining--;
        if (bitsRemaining == 0) {
            bitsRemaining = 8;
            if (bufferFilled) {
                silence = false;
                shiftRegister = sampleBuffer;
                bufferFilled = false;
            } else {
                silence = true;
            }
        }
    }

    /**
     * Current output 0-127 (Phase E mixer input). Unlike the tone
     * channels the delta counter holds its level while silenced.
     */
    public int output() {
        return outputLevel;
    }

    /** Reset applies {@code $4011 &= 1} (research §1.9; Phase A4 seam). */
    public void resetOutputLevel() {
        outputLevel &= 1;
    }

    /** Bytes left in the current sample ($4015 bit 4 = this > 0). */
    public int bytesRemaining() {
        return bytesRemaining;
    }

    /** Next fetch address (test/diagnostic seam; the APU reads through it). */
    public int currentAddress() {
        return currentAddress;
    }

    /** Current output-bit interval in APU cycles (test seam). */
    public int timerPeriod() {
        return timerPeriod;
    }

    /** True while the 1-byte sample buffer holds an unplayed byte (test seam). */
    public boolean isBufferFilled() {
        return bufferFilled;
    }

    /** True while the output unit is running on an empty buffer (test seam). */
    public boolean isSilenced() {
        return silence;
    }

    public boolean isIrqEnabled() {
        return irqEnabled;
    }

    public boolean isLoopFlag() {
        return loopFlag;
    }
}
