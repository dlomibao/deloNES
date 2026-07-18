package net.lomibao.nes.components.apu;

/**
 * APU frame counter / sequencer ($4017) — NTSC, Phase A skeleton of
 * docs/apu-plan.md (spec: research doc §1.2).
 *
 * <p>Clocked once per CPU cycle by {@link net.lomibao.nes.components.APU}.
 * Event positions use the doubled (CPU-cycle) form of the NESdev table so
 * every half-APU-cycle event lands on an integer:
 *
 * <pre>
 * mode 0 (4-step): quarter 7457; quarter+half 14913; quarter 22371;
 *                  IRQ flag 29828/29829/29830 (29829 also quarter+half);
 *                  wrap 29830 → 0
 * mode 1 (5-step): quarter 7457; quarter+half 14913; quarter 22371;
 *                  (29829 dead step); quarter+half 37281; wrap 37282 → 0
 * </pre>
 *
 * <p>Phase A model: the $4017 sequencer-reset happens immediately on the
 * write (the 3/4-CPU-cycle write delay) and the IRQ flag's exact 3-cycle
 * window semantics beyond the table positions are Phase C. The wrapped
 * {@code cpuCycle} counter is frame-counter-local — nothing else may key
 * off it (plan architecture note).
 *
 * <p>TeaVM hot path: int-only, no {@code long}, no allocation, no
 * {@code %}. Fields are package-visible for unit tests in this package.
 */
public final class FrameCounter {

    /** Bit in the {@link #clock()} return mask: quarter-frame clock. */
    public static final int QUARTER = 1;
    /** Bit in the {@link #clock()} return mask: half-frame clock. */
    public static final int HALF = 2;

    /** CPU cycles since the last sequencer reset/wrap (frame-counter-local). */
    int cpuCycle;
    /** $4017 bit 7 — true = 5-step mode (never sets the IRQ flag). */
    boolean mode5;
    /** $4017 bit 6 — true = frame IRQ inhibited. */
    boolean irqInhibit;
    /** Level-held frame interrupt flag (cleared by software, not by clocking). */
    boolean frameIrqFlag;
    /**
     * True while the current CPU cycle is one of the flag-set cycles —
     * the $4015 same-cycle read race reads 1 without clearing (§1.7).
     */
    boolean irqSetThisCycle;

    /**
     * Advance one CPU cycle; returns a {@link #QUARTER}/{@link #HALF} bit
     * mask of the frame clocks (0 most cycles).
     */
    public int clock() {
        cpuCycle++;
        irqSetThisCycle = false;
        int events = 0;
        if (mode5) {
            if (cpuCycle == 7457 || cpuCycle == 22371) {
                events = QUARTER;
            } else if (cpuCycle == 14913 || cpuCycle == 37281) {
                events = QUARTER | HALF;
            } else if (cpuCycle == 37282) {
                cpuCycle = 0;
            }
        } else {
            if (cpuCycle == 7457 || cpuCycle == 22371) {
                events = QUARTER;
            } else if (cpuCycle == 14913) {
                events = QUARTER | HALF;
            } else if (cpuCycle == 29828) {
                setIrqFlag();
            } else if (cpuCycle == 29829) {
                events = QUARTER | HALF;
                setIrqFlag();
            } else if (cpuCycle == 29830) {
                setIrqFlag();
                cpuCycle = 0;
            }
        }
        return events;
    }

    private void setIrqFlag() {
        if (!irqInhibit) {
            frameIrqFlag = true;
            irqSetThisCycle = true;
        }
    }

    /**
     * $4017 write. Sets mode/inhibit, clears the IRQ flag when bit 6 is
     * set (clearing bit 6 never sets it), and resets the sequencer
     * immediately (Phase A — the 3/4-cycle delay is Phase C). Returns the
     * immediate quarter+half clock mask fired when bit 7 is set, 0
     * otherwise — the caller dispatches it like a {@link #clock()} result.
     */
    public int write4017(int value) {
        mode5 = (value & 0x80) != 0;
        irqInhibit = (value & 0x40) != 0;
        if (irqInhibit) {
            frameIrqFlag = false;
        }
        cpuCycle = 0;
        irqSetThisCycle = false;
        return mode5 ? QUARTER | HALF : 0;
    }

    /** Power-on/reset of the sequencer position (flag handling is the APU's). */
    public void resetSequencer() {
        cpuCycle = 0;
        irqSetThisCycle = false;
    }

    /** Level-held frame interrupt flag. */
    public boolean isFrameIrqFlag() {
        return frameIrqFlag;
    }

    /**
     * Clear the flag (a $4015 read). No-op on a flag-set cycle — the
     * same-cycle race returns 1 without clearing (§1.7).
     */
    public void clearFrameIrqFlagOnRead() {
        if (!irqSetThisCycle) {
            frameIrqFlag = false;
        }
    }

    /** Unconditional clear (reset path). */
    public void clearFrameIrqFlag() {
        frameIrqFlag = false;
        irqSetThisCycle = false;
    }

    /** Current frame-counter-local CPU cycle (test/diagnostic seam). */
    public int cycle() {
        return cpuCycle;
    }

    /** True in 5-step mode ($4017 bit 7). */
    public boolean isMode5() {
        return mode5;
    }

    /** True while $4017 bit 6 inhibits the frame IRQ. */
    public boolean isIrqInhibit() {
        return irqInhibit;
    }
}
