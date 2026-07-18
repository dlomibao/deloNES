package net.lomibao.nes.harness;

/**
 * NTSC time↔frame conversion for the input timeline (headless-harness plan,
 * decision D3).
 *
 * <p>{@code frames = Math.round(seconds × 60.0988)} — the published NESdev
 * NTSC frame rate (60.0988139 Hz), rounded half-up. This is <em>sugar</em>
 * for "about N seconds in", not a wall-clock contract: the emulator's PPU
 * currently lacks the odd-frame cycle skip, so the emulated rate is not the
 * hardware rate anyway; spurious extra precision was deliberately rejected.
 *
 * <p>TeaVM-safe (timeline tier): plain arithmetic only.
 */
public final class TimeBase {

    /** NTSC vertical rate, NESdev wiki 60.0988139 Hz (4 significant decimals). */
    public static final double NTSC_FPS = 60.0988;

    private TimeBase() {
        // static helpers only
    }

    /**
     * Frame index for a point {@code seconds} into an NTSC run:
     * {@code Math.round(seconds × 60.0988)}.
     *
     * @throws IllegalArgumentException if {@code seconds} is negative or NaN
     */
    public static int framesAt(double seconds) {
        if (!(seconds >= 0)) { // also rejects NaN
            throw new IllegalArgumentException("seconds must be >= 0, got: " + seconds);
        }
        return (int) Math.round(seconds * NTSC_FPS);
    }
}
