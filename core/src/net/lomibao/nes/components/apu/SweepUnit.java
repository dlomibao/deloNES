package net.lomibao.nes.components.apu;

/**
 * Pulse sweep unit ($4001/$4005) — docs/apu-plan.md Phase B2; spec
 * research doc §1.3 "Sweep" and NESdev "APU Sweep".
 *
 * <p>Target period = period ± (period &gt;&gt; shift). In negate mode
 * pulse 1 adds the one's complement (−change−1) and pulse 2 the two's
 * complement (−change) — an audible per-channel difference. The target
 * is recomputed continuously from the channel's current period, so the
 * two muting conditions (current period &lt; 8, target &gt; $7FF) apply
 * even while the sweep is disabled.
 *
 * <p>Half-frame clock: if divider==0 &amp;&amp; enabled &amp;&amp;
 * shift!=0 &amp;&amp; not muting → the channel period becomes the
 * target; then if divider==0 || reload → divider = P, clear reload;
 * else decrement. The owning {@link PulseChannel} passes its 11-bit
 * period in and stores the (possibly updated) result.
 *
 * <p>TeaVM hot path: int-only, no allocation. Fields package-visible
 * for unit tests in this package.
 */
public final class SweepUnit {

    /** True for pulse 1 — one's-complement negate (research §1.3). */
    private final boolean onesComplement;

    /** Register bit 7 — sweep enabled (gates period updates only, not muting). */
    boolean enabled;
    /** Register bits 6-4 — divider period P (updates every P+1 half-frames). */
    int dividerPeriod;
    /** Register bit 3 — negate (subtract) mode. */
    boolean negate;
    /** Register bits 2-0 — barrel-shift amount; 0 blocks period updates. */
    int shift;
    /** Set by every register write; restarts the divider at the next half clock. */
    boolean reload;
    /** Divider counter. */
    int divider;

    public SweepUnit(boolean onesComplement) {
        this.onesComplement = onesComplement;
    }

    /** $4001/$4005 write: decode fields and set the reload flag. */
    public void write(int value) {
        enabled = (value & 0x80) != 0;
        dividerPeriod = (value >> 4) & 0x07;
        negate = (value & 0x08) != 0;
        shift = value & 0x07;
        reload = true;
    }

    /**
     * Target period for the given current channel period — recomputed
     * continuously (muting applies even when disabled).
     */
    public int targetPeriod(int currentPeriod) {
        int change = currentPeriod >> shift;
        if (negate) {
            return onesComplement ? currentPeriod - change - 1
                    : currentPeriod - change;
        }
        return currentPeriod + change;
    }

    /**
     * True when the sweep forces the channel silent: current period
     * &lt; 8, or target period &gt; $7FF. Independent of {@link #enabled}.
     */
    public boolean mutes(int currentPeriod) {
        return currentPeriod < 8 || targetPeriod(currentPeriod) > 0x7FF;
    }

    /**
     * Half-frame clock. Returns the channel's new period (unchanged
     * unless an update fires).
     */
    public int clockHalfFrame(int currentPeriod) {
        int newPeriod = currentPeriod;
        if (divider == 0 && enabled && shift != 0 && !mutes(currentPeriod)) {
            newPeriod = targetPeriod(currentPeriod);
        }
        if (divider == 0 || reload) {
            divider = dividerPeriod;
            reload = false;
        } else {
            divider--;
        }
        return newPeriod;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int dividerPeriod() {
        return dividerPeriod;
    }

    public boolean isNegate() {
        return negate;
    }

    public int shift() {
        return shift;
    }

    public boolean isReload() {
        return reload;
    }

    public int divider() {
        return divider;
    }

    public boolean isOnesComplement() {
        return onesComplement;
    }
}
