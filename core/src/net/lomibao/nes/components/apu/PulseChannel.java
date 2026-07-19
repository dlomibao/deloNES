package net.lomibao.nes.components.apu;

/**
 * Pulse channel, complete — docs/apu-plan.md Phase B3; spec research
 * doc §1.3 and NESdev "APU Pulse".
 *
 * <p>8-step duty sequencer driven by an 11-bit timer at APU-cycle rate
 * (the sequencer advances every t+1 APU cycles; f = CPU / (16·(t+1))).
 * Output 0-15: the envelope volume on high duty steps, 0 when muted —
 * duty bit low, length counter 0, or the sweep's continuous muting
 * conditions (t &lt; 8, target &gt; $7FF, which apply even with the
 * sweep disabled). Nothing consumes the output until the Phase E mixer.
 *
 * <p>$4003/$4007 side effects: length load (if enabled), duty phase
 * reset (the famous click), envelope start — the in-flight timer
 * countdown is NOT reset.
 *
 * <p>TeaVM hot path: int-only, no allocation, static final tables.
 * Fields package-visible for unit tests in this package.
 */
public final class PulseChannel {

    /** Duty sequences in playback order (research §1.3). */
    static final int[][] DUTY_TABLE = {
            {0, 1, 0, 0, 0, 0, 0, 0}, // 12.5%
            {0, 1, 1, 0, 0, 0, 0, 0}, // 25%
            {0, 1, 1, 1, 1, 0, 0, 0}, // 50%
            {1, 0, 0, 1, 1, 1, 1, 1}, // 25% negated
    };

    private final LengthCounter lengthCounter = new LengthCounter();
    private final Envelope envelope = new Envelope();
    private final SweepUnit sweep;
    /** True for pulse 1 (one's-complement sweep negate — research §1.3). */
    private final boolean onesComplementSweep;

    /** $4000/$4004 bits 7-6 — duty cycle select. */
    int duty;
    /** Position in the 8-step duty sequence; reset by $4003/$4007. */
    int sequencePhase;
    /** 11-bit timer reload value t ($4002/$4003 low/high). */
    int timerPeriod;
    /** In-flight countdown — NOT reset by timer-register writes. */
    int timerCounter;

    public PulseChannel(boolean onesComplementSweep) {
        this.onesComplementSweep = onesComplementSweep;
        this.sweep = new SweepUnit(onesComplementSweep);
    }

    /** $4000/$4004 — duty, length halt / envelope loop, volume bits. */
    public void writeControl(int value) {
        duty = (value >> 6) & 0x03;
        lengthCounter.setHalt((value & 0x20) != 0);
        envelope.writeControl(value);
    }

    /** $4001/$4005 — sweep register. */
    public void writeSweep(int value) {
        sweep.write(value);
    }

    /** $4002/$4006 — timer low 8 bits (high bits preserved). */
    public void writeTimerLow(int value) {
        timerPeriod = (timerPeriod & 0x700) | (value & 0xFF);
    }

    /**
     * $4003/$4007 — timer bits 10-8 + length index. Side effects
     * (research §1.3): length load, duty phase reset, envelope start.
     * The in-flight timer countdown is NOT reset.
     */
    public void writeTimerHigh(int value) {
        timerPeriod = (timerPeriod & 0x0FF) | ((value & 0x07) << 8);
        lengthCounter.load(value >> 3);
        sequencePhase = 0;
        envelope.start();
    }

    /** APU-cycle clock: sequencer advances every t+1 APU cycles. */
    public void clockTimer() {
        if (timerCounter == 0) {
            timerCounter = timerPeriod;
            sequencePhase = (sequencePhase + 1) & 0x07;
        } else {
            timerCounter--;
        }
    }

    /** Quarter-frame clock — envelope. */
    public void clockQuarterFrame() {
        envelope.clockQuarterFrame();
    }

    /** Half-frame clock — sweep (may rewrite the period) + length. */
    public void clockHalfFrame() {
        timerPeriod = sweep.clockHalfFrame(timerPeriod);
        lengthCounter.clockHalfFrame();
    }

    /**
     * Current output 0-15 (Phase E mixer input). 0 when the duty bit is
     * low, the length counter is 0, or the sweep mutes (t &lt; 8 /
     * target &gt; $7FF — continuous, even when the sweep is disabled).
     */
    public int output() {
        if (DUTY_TABLE[duty][sequencePhase] == 0
                || !lengthCounter.isActive()
                || sweep.mutes(timerPeriod)) {
            return 0;
        }
        return envelope.output();
    }

    public LengthCounter lengthCounter() {
        return lengthCounter;
    }

    public Envelope envelope() {
        return envelope;
    }

    public SweepUnit sweep() {
        return sweep;
    }

    public boolean isOnesComplementSweep() {
        return onesComplementSweep;
    }

    /** Current duty-sequence step (test seam). */
    public int sequencePhase() {
        return sequencePhase;
    }

    /** Current 11-bit timer reload value (test seam). */
    public int timerPeriod() {
        return timerPeriod;
    }
}
