package net.lomibao.nes.desktop.audio;

/**
 * Phase 0 APU POC (derisk code — docs/apu-plan.md, "Phase 0 / 0-D").
 * Synthesizes a fixed-frequency square wave at NES-realistic volume and
 * meters out per-video-frame sample counts from a fractional accumulator
 * (44100 / 60.0988 = 733.77 → a ~77/23 weighted mix of 734- and 733-sample frames), exactly the pacing scheme the
 * real APU output path (Phase E) will use.
 *
 * <p>May be absorbed or deleted by Phase E. Keep it host-side only —
 * nothing in {@code core/src} may reference it.
 */
public final class SquareToneGenerator {

    /** NTSC NES frame rate (research doc §3). */
    public static final double NES_FRAME_RATE = 60.0988;

    private final double samplesPerFrame;
    private final double phaseInc;
    private final float amplitude;

    /** Fractional part of frames-worth-of-samples not yet emitted. */
    private double frameAccumulator;
    /** Oscillator phase in periods, [0, 1). */
    private double phase;

    public SquareToneGenerator(float sampleRate, double toneHz, float amplitude) {
        this.samplesPerFrame = sampleRate / NES_FRAME_RATE;
        this.phaseInc = toneHz / sampleRate;
        this.amplitude = amplitude;
    }

    /**
     * Number of samples to synthesize for the next video frame. Alternates
     * (e.g. 733/734 at 44.1 kHz) so no long-term drift accumulates against
     * the NES frame rate — never a hardcoded 735 (plan D12).
     */
    public int nextFrameSampleCount() {
        frameAccumulator += samplesPerFrame;
        int n = (int) frameAccumulator;
        frameAccumulator -= n;
        return n;
    }

    /** Fill {@code buf[0..n)} with the next {@code n} samples. Phase is continuous across calls. */
    public void fill(float[] buf, int n) {
        for (int i = 0; i < n; i++) {
            buf[i] = phase < 0.5 ? amplitude : -amplitude;
            phase += phaseInc;
            if (phase >= 1.0) {
                phase -= 1.0;
            }
        }
    }
}
