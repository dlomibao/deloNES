package net.lomibao.nes.components.apu;

/**
 * Envelope generator, shared by the pulse and noise channels
 * (docs/apu-plan.md Phase B1; spec research doc §1.3 "Envelope" and
 * NESdev "APU Envelope").
 *
 * <p>Quarter-frame clocked. If the start flag is set: clear it, decay =
 * 15, divider = V. Otherwise clock the divider; on wrap (0 → V) clock
 * the decay level — decrement if &gt; 0, else reload to 15 iff the loop
 * flag is set. Output is V in constant-volume mode, else the decay
 * level; the decay machinery runs regardless of the mode bit.
 *
 * <p>The loop flag is the same register bit as the length-counter halt
 * (bit 5 of $4000/$4004/$400C) — the owning channel sets both from one
 * write. TeaVM hot path: int-only, no allocation. Fields
 * package-visible for unit tests in this package.
 */
public final class Envelope {

    /** Set by a $4003/$4007/$400F write; consumed by the next quarter clock. */
    boolean startFlag;
    /** Divider counter, counts V+1 quarter-frames per decay clock. */
    int divider;
    /** Decay level 15 → 0 (the envelope-mode output). */
    int decayLevel;
    /** Register bit 5 — envelope loop (doubles as length-counter halt). */
    boolean loop;
    /** Register bit 4 — constant-volume mode. */
    boolean constantVolume;
    /** Register bits 3-0 — volume (constant mode) / divider period V. */
    int volumePeriod;

    /**
     * Decode the channel's $4000/$4004/$400C envelope bits (5-0). Never
     * restarts the envelope and never touches the live decay state.
     */
    public void writeControl(int value) {
        loop = (value & 0x20) != 0;
        constantVolume = (value & 0x10) != 0;
        volumePeriod = value & 0x0F;
    }

    /** $4003/$4007/$400F side effect — restart on the next quarter clock. */
    public void start() {
        startFlag = true;
    }

    /** Quarter-frame clock (research §1.3). */
    public void clockQuarterFrame() {
        if (startFlag) {
            startFlag = false;
            decayLevel = 15;
            divider = volumePeriod;
        } else if (divider == 0) {
            divider = volumePeriod;
            if (decayLevel > 0) {
                decayLevel--;
            } else if (loop) {
                decayLevel = 15;
            }
        } else {
            divider--;
        }
    }

    /** Current volume 0-15: V if constant-volume, else the decay level. */
    public int output() {
        return constantVolume ? volumePeriod : decayLevel;
    }

    public boolean isStartFlag() {
        return startFlag;
    }

    public int decayLevel() {
        return decayLevel;
    }

    public int divider() {
        return divider;
    }

    public boolean isLoop() {
        return loop;
    }

    public boolean isConstantVolume() {
        return constantVolume;
    }
}
