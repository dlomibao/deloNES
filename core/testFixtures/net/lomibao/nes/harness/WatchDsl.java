package net.lomibao.nes.harness;

import java.util.function.Consumer;

/**
 * Registration builder returned by {@link NesHarness#watch(int)} /
 * {@link NesHarness#watchRange(int, int)} (headless-harness plan, Phase B2).
 *
 * <p>Two trigger tiers, explicit in the API (D4): the terminal methods here
 * register <em>write watches</em> — exact, fired mid-frame from the S1 bus
 * seam. Frame-boundary <em>polls</em> are the other tier
 * ({@code h.runUntil(...)}, {@code h.peek(...)}).
 *
 * <p>Value-tracking triggers — {@link #onChange}, {@link #whenBecomes},
 * {@link #crossesAbove}, {@link #crossesBelow} — need a bus-level old
 * value, which exists only for CPU RAM ($0000-$1FFF); registering one
 * outside that range throws {@link IllegalArgumentException}.
 * {@link #onWrite} works anywhere (non-RAM writes report
 * {@code oldValue() == -1}).
 */
public final class WatchDsl {

    private final NesHarness harness;
    /** Canonicalized inclusive bounds. */
    private final int lo;
    private final int hi;

    WatchDsl(NesHarness harness, int loAddr, int hiAddr) {
        this.harness = harness;
        int lo = Watch.canonical(loAddr);
        int hi = Watch.canonical(hiAddr);
        if (lo > hi) {
            throw new IllegalArgumentException(String.format(
                    "watch range $%04X-$%04X canonicalizes to $%04X-$%04X — "
                    + "ranges must not span mirror regions",
                    loAddr, hiAddr, lo, hi));
        }
        this.lo = lo;
        this.hi = hi;
    }

    // -------------------------------------------------------------------------
    // Terminal registrations
    // -------------------------------------------------------------------------

    /** Fire on every write in range (any address; non-RAM has oldValue -1). */
    public Watch onWrite(Consumer<Watch.Write> callback) {
        requireCallback(callback);
        return harness.registerWatch(new Watch(
                harness, lo, hi, Watch.Kind.WRITE, 0, callback, null));
    }

    /** Fire when a write actually changes the RAM value. RAM range only. */
    public Watch onChange(Consumer<Watch.Write> callback) {
        requireCallback(callback);
        requireRam("onChange");
        return harness.registerWatch(new Watch(
                harness, lo, hi, Watch.Kind.CHANGE, 0, callback, null));
    }

    /**
     * Edge trigger: fires when the value becomes {@code value} (and was not
     * already {@code value}) — not on level. RAM range only.
     */
    public ThenStage whenBecomes(int value) {
        requireRam("whenBecomes");
        return new ThenStage(Watch.Kind.BECOMES, value & 0xFF);
    }

    /**
     * Edge trigger: fires when the value transitions from {@code <= threshold}
     * to {@code > threshold}. RAM range only.
     */
    public ThenStage crossesAbove(int threshold) {
        requireRam("crossesAbove");
        return new ThenStage(Watch.Kind.CROSS_ABOVE, threshold);
    }

    /**
     * Edge trigger: fires when the value transitions from {@code >= threshold}
     * to {@code < threshold}. RAM range only.
     */
    public ThenStage crossesBelow(int threshold) {
        requireRam("crossesBelow");
        return new ThenStage(Watch.Kind.CROSS_BELOW, threshold);
    }

    // -------------------------------------------------------------------------

    /** Second stage of a value-edge trigger: attach the action. */
    public final class ThenStage {
        private final Watch.Kind kind;
        private final int value;

        private ThenStage(Watch.Kind kind, int value) {
            this.kind = kind;
            this.value = value;
        }

        /**
         * Register the trigger. The action runs mid-frame at the tripping
         * write; any {@link Watch.Context#press} it issues lands at the
         * NEXT frame boundary (D2).
         */
        public Watch then(Consumer<Watch.Context> action) {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            return harness.registerWatch(new Watch(
                    harness, lo, hi, kind, value, null, action));
        }
    }

    // -------------------------------------------------------------------------

    private void requireRam(String trigger) {
        // Canonical RAM is $0000-$07FF; the S1 old-value snapshot covers
        // all of $0000-$1FFF, which canonicalizes into that window.
        if (hi > 0x07FF) {
            throw new IllegalArgumentException(String.format(
                    "%s requires a CPU-RAM address range ($0000-$1FFF); got "
                    + "canonical $%04X-$%04X — old values are undefined "
                    + "outside RAM (use onWrite instead)",
                    trigger, lo, hi));
        }
    }

    private static void requireCallback(Consumer<Watch.Write> callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
    }
}
