package net.lomibao.nes.components.apu;

/**
 * Triangle channel — Phase A2 length-counter-only shell
 * (docs/apu-plan.md). Timer/linear counter/sequencer arrive in Phase B;
 * the 32-step sequence phase exists now because reset pins it to 0
 * (Phase A4, research §1.9).
 */
public final class TriangleChannel {

    private final LengthCounter lengthCounter = new LengthCounter();
    /** Position in the 32-step output sequence (Phase B consumer). */
    int sequencePhase;

    public LengthCounter lengthCounter() {
        return lengthCounter;
    }

    /** CPU-rate timer clock — sequencer lands in Phase B4. */
    public void clockTimer() {
        // B4: timer countdown + 32-step sequencer.
    }

    /** Quarter-frame clock — linear counter lands in Phase B4. */
    public void clockQuarterFrame() {
        // B4: linear counter reload/decrement dance.
    }

    /** Half-frame clock — length counter. */
    public void clockHalfFrame() {
        lengthCounter.clockHalfFrame();
    }

    /** Reset pins the sequence phase to 0 (A4). */
    public void resetPhase() {
        sequencePhase = 0;
    }

    /** Current 32-step sequence position (test seam). */
    public int sequencePhase() {
        return sequencePhase;
    }
}
