package net.lomibao.nes.components.apu;

/**
 * Pulse channel — Phase A2 length-counter-only shell (docs/apu-plan.md).
 * Duty/timer/envelope/sweep arrive in Phase B; the constructor flag
 * records which sweep-negate complement this channel uses (pulse 1 =
 * one's complement, pulse 2 = two's complement — research §1.3).
 */
public final class PulseChannel {

    private final LengthCounter lengthCounter = new LengthCounter();
    /** True for pulse 1 (one's-complement sweep negate) — consumed in Phase B. */
    private final boolean onesComplementSweep;

    public PulseChannel(boolean onesComplementSweep) {
        this.onesComplementSweep = onesComplementSweep;
    }

    public LengthCounter lengthCounter() {
        return lengthCounter;
    }

    public boolean isOnesComplementSweep() {
        return onesComplementSweep;
    }
}
