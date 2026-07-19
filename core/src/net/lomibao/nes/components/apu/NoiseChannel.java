package net.lomibao.nes.components.apu;

/**
 * Noise channel, complete — docs/apu-plan.md Phase B5; spec research
 * doc §1.5 and NESdev "APU Noise".
 *
 * <p>15-bit LFSR, seeded to 1. Each timer clock: feedback = bit0 XOR
 * (bit6 if the mode flag is set, else bit1); shift right; feedback into
 * bit 14. Mode 1 gives the 93/31-step metallic loops; mode 0 the
 * maximal 32767-step sequence. The timer runs at APU-cycle rate with
 * the NTSC period table (stored in APU cycles = CPU table / 2). Output
 * is the envelope volume, silenced while LFSR bit0 == 1 or the length
 * counter is 0. Nothing consumes the output until the Phase E mixer.
 *
 * <p>$400F side effects: length load (if enabled) + envelope start.
 *
 * <p>TeaVM hot path: int-only, no allocation, static final table.
 * Fields package-visible for unit tests in this package.
 */
public final class NoiseChannel {

    /**
     * NTSC period table in APU cycles (research §1.5 table is CPU
     * cycles — 4, 8, 16, … 4068 — and the timer clocks every 2nd CPU
     * cycle, so these are the halved values).
     */
    static final int[] PERIOD_TABLE_APU = {
            2, 4, 8, 16, 32, 48, 64, 80,
            101, 127, 190, 254, 381, 508, 1017, 2034,
    };

    private final LengthCounter lengthCounter = new LengthCounter();
    private final Envelope envelope = new Envelope();

    /** 15-bit linear-feedback shift register; effectively starts at 1. */
    int lfsr = 1;
    /** $400E bit 7 — mode flag: feedback taps bit 6 instead of bit 1. */
    boolean modeFlag;
    /** LFSR clock interval in APU cycles (from {@link #PERIOD_TABLE_APU}). */
    int timerPeriod = PERIOD_TABLE_APU[0];
    /** In-flight countdown. */
    int timerCounter;

    public LengthCounter lengthCounter() {
        return lengthCounter;
    }

    public Envelope envelope() {
        return envelope;
    }

    /** $400C — length halt / envelope loop + volume bits. */
    public void writeControl(int value) {
        lengthCounter.setHalt((value & 0x20) != 0);
        envelope.writeControl(value);
    }

    /** $400E — mode flag (bit 7) + period-table index (bits 3-0). */
    public void writeMode(int value) {
        modeFlag = (value & 0x80) != 0;
        timerPeriod = PERIOD_TABLE_APU[value & 0x0F];
    }

    /** $400F — length load (bits 7-3, if enabled) + envelope start. */
    public void writeLength(int value) {
        lengthCounter.load(value >> 3);
        envelope.start();
    }

    /** APU-cycle clock: steps the LFSR every {@code timerPeriod} APU cycles. */
    public void clockTimer() {
        if (timerCounter == 0) {
            timerCounter = timerPeriod - 1;
            clockLfsr();
        } else {
            timerCounter--;
        }
    }

    /** One LFSR step (research §1.5). */
    void clockLfsr() {
        int tap = modeFlag ? (lfsr >> 6) : (lfsr >> 1);
        int feedback = (lfsr ^ tap) & 1;
        lfsr = (lfsr >> 1) | (feedback << 14);
    }

    /** Quarter-frame clock — envelope. */
    public void clockQuarterFrame() {
        envelope.clockQuarterFrame();
    }

    /** Half-frame clock — length counter. */
    public void clockHalfFrame() {
        lengthCounter.clockHalfFrame();
    }

    /**
     * Current output 0-15 (Phase E mixer input): envelope volume,
     * silenced while LFSR bit0 == 1 or the length counter is 0.
     */
    public int output() {
        if ((lfsr & 1) != 0 || !lengthCounter.isActive()) {
            return 0;
        }
        return envelope.output();
    }

    /** Reset pins the LFSR back to 1 (A4). */
    public void resetLfsr() {
        lfsr = 1;
    }

    /** Current LFSR value (test seam). */
    public int lfsr() {
        return lfsr;
    }

    /** Current LFSR interval in APU cycles (test seam). */
    public int timerPeriod() {
        return timerPeriod;
    }

    public boolean isModeFlag() {
        return modeFlag;
    }
}
