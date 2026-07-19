package net.lomibao.nes.components.apu;

/**
 * Nonlinear mixer + box-average downsampler + output filters (Phase E1;
 * decisions D5/D14/D12/D15).
 *
 * <p><b>Mix</b> — the nonlinear lookup tables everyone ships (research
 * §1.8), built once at class load:
 * <pre>
 * pulse_table[n] = 95.52  / (8128.0/n  + 100)   n = 0..30,  [0] = 0
 * tnd_table[n]   = 163.67 / (24329.0/n + 100)   n = 0..202, [0] = 0
 * out = pulse_table[p1 + p2] + tnd_table[3*tri + 2*noise + dmc]
 * </pre>
 * The tables are correctness (channel balance, DMC cancellation), not a
 * quality rung — same cost as a linear mix (D5).
 *
 * <p><b>Downsample</b> — box average with a fractional window boundary:
 * every CPU cycle's mixed level is accumulated; one output sample is
 * emitted per {@code cpuHz / sampleRate} ≈ 40.58 cycles (at 44.1 kHz).
 * The boundary cycle is split <em>fractionally</em> between the closing
 * and opening windows (the "~77/23" weighted-mix lesson from POC-D:
 * 44100/60.0988 = 733.77 → per-frame counts come out as a ~77/23 mix of
 * 734/733, never a hardcoded 735). Per-sample math is float/double —
 * fine on TeaVM (JS numbers are native doubles; {@code long} is the
 * poison, not floating point). No allocation anywhere on the path.
 *
 * <p><b>Filter</b> — first-order IIRs on the emitted sample stream:
 * ~90 Hz high-pass (doubles as the DC blocker — no thump on
 * pause/resume) and ~14 kHz low-pass, both present on the real console
 * (research §1.8; the hardware's second ~440 Hz HP is out of v1 scope —
 * the plan's E1 text names exactly these two).
 *
 * <p>Sample rate is a host-supplied parameter (D12): 44100 by default
 * (desktop, headless tests), {@code ctx.sampleRate} on web — set before
 * the first frame via {@link #setSampleRate(int)}. Mono only (D15).
 *
 * <p>All downsampler/IIR state is reset on {@code APU.reset()} / ROM
 * load and is a pure function of the tick stream — included in the E4
 * audio hash, never part of the movie format (audio is
 * observation-only).
 *
 * <p>The channel→mixer boundary stays delta-friendly: channels expose
 * plain output levels each cycle and only this class consumes them, so
 * the Phase F blip_buf port is a mixer-only swap (D5/F1).
 */
public final class ApuMixer {

    /** NTSC CPU clock (research §3). */
    static final double CPU_HZ = 1789773.0;

    /** Default output rate when the host never calls {@link #setSampleRate(int)} (D12). */
    public static final int DEFAULT_SAMPLE_RATE = 44100;

    /** §1.8 lookup tables — 31 / 203 entries, index 0 == 0. */
    static final float[] PULSE_TABLE = new float[31];
    static final float[] TND_TABLE = new float[203];

    static {
        // [0] stays 0.0f (the formula divides by n).
        for (int n = 1; n < PULSE_TABLE.length; n++) {
            PULSE_TABLE[n] = (float) (95.52 / (8128.0 / n + 100.0));
        }
        for (int n = 1; n < TND_TABLE.length; n++) {
            TND_TABLE[n] = (float) (163.67 / (24329.0 / n + 100.0));
        }
    }

    /** The destination ring — core-owned, drained by the hosts (D16). */
    private final ApuSampleBuffer out;

    private int sampleRate;
    /** CPU cycles per output sample (~40.58 at 44.1 kHz). */
    private double cyclesPerSample;

    // -- box-average window state --
    /** Weighted sum of mixed levels inside the current window. */
    private double windowSum;
    /** Cycles (fractional) left before the current window closes. */
    private double windowRemaining;

    // -- IIR state --
    /** HP coefficient a in y[n] = a*(y[n-1] + x[n] - x[n-1]). */
    private double hpCoeff;
    /** LP coefficient b in y[n] = y[n-1] + b*(x[n] - y[n-1]). */
    private double lpCoeff;
    private double hpPrevIn;
    private double hpOut;
    private double lpOut;

    /** Last pre-filter (raw box-average) sample — package-visible test seam. */
    float lastRaw;

    public ApuMixer(ApuSampleBuffer out) {
        this.out = out;
        configure(DEFAULT_SAMPLE_RATE);
    }

    /**
     * Host-supplied output rate (D12) — call before the first frame.
     * Recomputes the decimation ratio and filter coefficients and resets
     * all stream state (the rate change invalidates it).
     */
    public void setSampleRate(int rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("sample rate must be > 0: " + rate);
        }
        configure(rate);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    private void configure(int rate) {
        this.sampleRate = rate;
        this.cyclesPerSample = CPU_HZ / rate;
        double dt = 1.0 / rate;
        // First-order RC coefficients: RC = 1 / (2*pi*fc).
        double hpRc = 1.0 / (2.0 * Math.PI * 90.0);
        double lpRc = 1.0 / (2.0 * Math.PI * 14000.0);
        this.hpCoeff = hpRc / (hpRc + dt);
        this.lpCoeff = dt / (lpRc + dt);
        resetStream();
    }

    /**
     * Reset downsampler + IIR state (fractional window, filter memory).
     * Called from {@code APU.reset()} and on ROM load / host resume
     * (D18); pure function of the tick stream thereafter.
     */
    public void resetStream() {
        windowSum = 0.0;
        windowRemaining = cyclesPerSample;
        hpPrevIn = 0.0;
        hpOut = 0.0;
        lpOut = 0.0;
        lastRaw = 0f;
    }

    /**
     * §1.8 nonlinear mix of one cycle's channel levels
     * (pulse/tri/noise 0-15, dmc 0-127) → 0.0-~1.0.
     */
    static float mixLevel(int p1, int p2, int tri, int noise, int dmc) {
        return PULSE_TABLE[p1 + p2] + TND_TABLE[3 * tri + 2 * noise + dmc];
    }

    /**
     * Consume one CPU cycle's channel output levels (called once per CPU
     * cycle from {@code APU.clock()}). Accumulates into the box-average
     * window; emits into the ring when the window closes, splitting the
     * boundary cycle's weight fractionally between the two windows.
     */
    public void acceptCpuCycle(int p1, int p2, int tri, int noise, int dmc) {
        double level = PULSE_TABLE[p1 + p2] + TND_TABLE[3 * tri + 2 * noise + dmc];
        if (windowRemaining >= 1.0) {
            // Whole cycle inside the current window.
            windowSum += level;
            windowRemaining -= 1.0;
            if (windowRemaining == 0.0) {
                // Exact boundary (integer ratio) — close with no carry.
                emit(windowSum);
                windowSum = 0.0;
                windowRemaining = cyclesPerSample;
            }
        } else {
            // Boundary cycle: fraction f closes this window, (1-f) opens the next.
            double f = windowRemaining;
            emit(windowSum + level * f);
            double carry = 1.0 - f;
            windowSum = level * carry;
            windowRemaining = cyclesPerSample - carry;
        }
    }

    /** Close a window: normalize, filter (HP then LP), write to the ring. */
    private void emit(double sum) {
        double raw = sum / cyclesPerSample;
        lastRaw = (float) raw;
        // ~90 Hz high-pass (DC blocker): y[n] = a*(y[n-1] + x[n] - x[n-1]).
        hpOut = hpCoeff * (hpOut + raw - hpPrevIn);
        hpPrevIn = raw;
        // ~14 kHz low-pass: y[n] = y[n-1] + b*(x[n] - y[n-1]).
        lpOut += lpCoeff * (hpOut - lpOut);
        out.write((float) lpOut);
    }
}
