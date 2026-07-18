package net.lomibao.nes.components.apu;

/**
 * Noise channel — Phase A2 length-counter-only shell (docs/apu-plan.md).
 * Timer/envelope/LFSR clocking arrive in Phase B; the 15-bit LFSR field
 * exists now because power-up and reset pin it to 1 (Phase A4, research
 * §1.9).
 */
public final class NoiseChannel {

    private final LengthCounter lengthCounter = new LengthCounter();
    /** 15-bit linear-feedback shift register; effectively starts at 1. */
    int lfsr = 1;

    public LengthCounter lengthCounter() {
        return lengthCounter;
    }

    /** APU-rate timer clock — LFSR stepping lands in Phase B5. */
    public void clockTimer() {
        // B5: timer countdown + LFSR feedback.
    }

    /** Quarter-frame clock — envelope lands in Phase B5. */
    public void clockQuarterFrame() {
        // B5: envelope.
    }

    /** Half-frame clock — length counter. */
    public void clockHalfFrame() {
        lengthCounter.clockHalfFrame();
    }

    /** Reset pins the LFSR back to 1 (A4). */
    public void resetLfsr() {
        lfsr = 1;
    }

    /** Current LFSR value (test seam; Phase B clocks it). */
    public int lfsr() {
        return lfsr;
    }
}
