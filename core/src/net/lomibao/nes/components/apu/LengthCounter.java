package net.lomibao.nes.components.apu;

/**
 * Channel length counter (docs/apu-plan.md Phase A2; spec research §1.3,
 * §1.7 and NESdev "APU Length Counter").
 *
 * <p>One per pulse/triangle/noise channel. Loaded from the 32-entry
 * table by the channel's $4003/$4007/$400B/$400F write (bits 7-3),
 * <em>blocked while the channel is disabled</em>; disabling via $4015
 * forces the counter to 0; clocked on half-frame ticks unless halted.
 *
 * <p>TeaVM hot path: int-only, no allocation. Fields package-visible
 * for unit tests in this package.
 */
public final class LengthCounter {

    /** NESdev length table, indexed by register bits 7-3. */
    static final int[] LENGTH_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6,
            160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22,
            192, 24, 72, 26, 16, 28, 32, 30,
    };

    int counter;
    /** Length-counter halt (doubles as envelope-loop / triangle control). */
    boolean halt;
    /** $4015 channel-enable bit; false blocks reloads and zeroes the counter. */
    boolean enabled;

    /**
     * Load from the table (index = register bits 7-3, pre-shifted by the
     * caller to 0-31). Reloads are blocked while the channel is disabled
     * (§1.7).
     */
    public void load(int index) {
        if (enabled) {
            counter = LENGTH_TABLE[index & 0x1F];
        }
    }

    /** Half-frame clock: decrement unless halted or already 0. */
    public void clockHalfFrame() {
        if (!halt && counter > 0) {
            counter--;
        }
    }

    /** $4015 enable bit. Clearing forces the counter to 0 immediately. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            counter = 0;
        }
    }

    /** Halt flag (channel register bit 5, or bit 7 for triangle). */
    public void setHalt(boolean halt) {
        this.halt = halt;
    }

    /** True while the counter is non-zero — the $4015 status bit. */
    public boolean isActive() {
        return counter > 0;
    }

    /** Current counter value (test seam). */
    public int value() {
        return counter;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHalt() {
        return halt;
    }
}
