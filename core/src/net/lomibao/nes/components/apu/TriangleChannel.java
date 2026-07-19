package net.lomibao.nes.components.apu;

/**
 * Triangle channel, complete — docs/apu-plan.md Phase B4; spec research
 * doc §1.4 and NESdev "APU Triangle".
 *
 * <p>32-step sequence 15,14,…,1,0,0,1,…,14,15 — output 0-15, no volume
 * control. The timer clocks every <em>CPU</em> cycle (the one fast-clock
 * channel; f = CPU / (32·(t+1))). The sequencer steps only while the
 * linear counter AND the length counter are both non-zero; when gated
 * the phase freezes at its current value — the output holds, no click.
 * Ultrasonic guard: stepping is skipped while t &lt; 2 (~28 kHz+ would
 * alias into garbage; hardware averages it away). Nothing consumes the
 * output until the Phase E mixer.
 *
 * <p>Linear counter quarter-frame dance: reload flag set → counter = R,
 * else decrement if &gt; 0; then if the control flag (bit 7 of $4008,
 * doubling as length halt) is clear, clear the reload flag. Writing
 * $400B sets the reload flag.
 *
 * <p>TeaVM hot path: int-only, no allocation, static final table.
 * Fields package-visible for unit tests in this package.
 */
public final class TriangleChannel {

    /** The 32-step output sequence (research §1.4). */
    static final int[] SEQUENCE = {
            15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    };

    private final LengthCounter lengthCounter = new LengthCounter();

    /** Position in the 32-step output sequence. */
    int sequencePhase;
    /** 11-bit timer reload value t ($400A/$400B). */
    int timerPeriod;
    /** In-flight countdown — NOT reset by timer-register writes. */
    int timerCounter;
    /** Linear counter (the triangle's fine-grained duration gate). */
    int linearCounter;
    /** $4008 bits 6-0 — linear-counter reload value R. */
    int linearReloadValue;
    /** $4008 bit 7 — control flag (doubles as length-counter halt). */
    boolean controlFlag;
    /** Set by $400B; consumed/cleared by the quarter-frame dance. */
    boolean linearReloadFlag;

    public LengthCounter lengthCounter() {
        return lengthCounter;
    }

    /** $4008 — control/halt flag (bit 7) + linear reload value (6-0). */
    public void writeLinear(int value) {
        controlFlag = (value & 0x80) != 0;
        lengthCounter.setHalt(controlFlag);
        linearReloadValue = value & 0x7F;
    }

    /** $400A — timer low 8 bits (high bits preserved). */
    public void writeTimerLow(int value) {
        timerPeriod = (timerPeriod & 0x700) | (value & 0xFF);
    }

    /**
     * $400B — timer bits 10-8 + length index. Side effects: length load
     * (if enabled) and the linear-counter reload flag is set. The
     * sequence phase and in-flight countdown are NOT touched.
     */
    public void writeTimerHigh(int value) {
        timerPeriod = (timerPeriod & 0x0FF) | ((value & 0x07) << 8);
        lengthCounter.load(value >> 3);
        linearReloadFlag = true;
    }

    /**
     * CPU-rate timer clock. Steps the sequencer every t+1 CPU cycles
     * while both duration counters are non-zero and t ≥ 2 (ultrasonic
     * guard); when gated the phase freezes — no click.
     */
    public void clockTimer() {
        if (timerCounter == 0) {
            timerCounter = timerPeriod;
            if (linearCounter > 0 && lengthCounter.isActive() && timerPeriod >= 2) {
                sequencePhase = (sequencePhase + 1) & 0x1F;
            }
        } else {
            timerCounter--;
        }
    }

    /** Quarter-frame clock — the linear-counter reload/decrement dance. */
    public void clockQuarterFrame() {
        if (linearReloadFlag) {
            linearCounter = linearReloadValue;
        } else if (linearCounter > 0) {
            linearCounter--;
        }
        if (!controlFlag) {
            linearReloadFlag = false;
        }
    }

    /** Half-frame clock — length counter. */
    public void clockHalfFrame() {
        lengthCounter.clockHalfFrame();
    }

    /**
     * Current output 0-15 (Phase E mixer input) — always the raw
     * sequence value; a gated sequencer holds its last value.
     */
    public int output() {
        return SEQUENCE[sequencePhase];
    }

    /** Reset pins the sequence phase to 0 (A4). */
    public void resetPhase() {
        sequencePhase = 0;
    }

    /** Current 32-step sequence position (test seam). */
    public int sequencePhase() {
        return sequencePhase;
    }

    /** Current 11-bit timer reload value (test seam). */
    public int timerPeriod() {
        return timerPeriod;
    }

    /** Current linear-counter value (test seam). */
    public int linearCounter() {
        return linearCounter;
    }

    public boolean isLinearReloadFlag() {
        return linearReloadFlag;
    }

    public boolean isControlFlag() {
        return controlFlag;
    }
}
