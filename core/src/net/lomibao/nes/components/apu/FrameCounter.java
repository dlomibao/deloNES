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
 * <p>Phase C model (C1): a $4017 write applies its IRQ-inhibit bit (and
 * the bit-6 flag clear) immediately, but the sequencer reset, the mode
 * change and the bit-7 immediate quarter+half clock land <b>3 or 4 CPU
 * cycles later</b> by write-cycle parity (during an APU cycle → 3,
 * between APU cycles → 4 — research §1.2 quirk 1). The old sequence keeps
 * running during the delay. The IRQ flag is set on three consecutive
 * cycles (29828/29829/29830), so a mid-window $4015 read that clears it
 * sees it re-set on the next window cycle. The wrapped {@code cpuCycle}
 * counter is frame-counter-local — nothing else may key off it (plan
 * architecture note).
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
     * Cycles until a pending $4017 write's delayed sequencer reset lands
     * (0 = no write pending). Set to 3 or 4 by {@link #write4017}.
     */
    int pendingDelay;
    /** The value of the pending $4017 write (valid while pendingDelay > 0). */
    int pendingValue;

    /**
     * C2 access-cycle compensation marker ({@code syncedTo}): the number
     * of future real CPU cycles this counter has already consumed via
     * {@link #catchUpTo}. While positive, eager {@link #clock()} calls are
     * no-ops (the time was already stepped) — the contained, partial
     * Mesen-catch-up adoption pinned by the plan.
     */
    int aheadCycles;

    /**
     * Advance one CPU cycle; returns a {@link #QUARTER}/{@link #HALF} bit
     * mask of the frame clocks (0 most cycles). No-op while real time is
     * still catching up to a compensated access ({@link #catchUpTo}).
     */
    public int clock() {
        if (aheadCycles > 0) {
            aheadCycles--;
            return 0;
        }
        return step();
    }

    /**
     * C2 (seam S6 consumer): run the counter forward so its state is
     * as-of {@code targetAhead} CPU cycles past real time — the
     * compensated access cycle {@code cpuCycle + (baseClocks − 1)}. Steps
     * only the difference when a previous access already ran ahead.
     * Returns the OR of all frame-clock masks fired during catch-up; the
     * APU dispatches them to the channel units exactly like eager clocks.
     */
    public int catchUpTo(int targetAhead) {
        int events = 0;
        while (aheadCycles < targetAhead) {
            events |= step();
            aheadCycles++;
        }
        return events;
    }

    /** One effective CPU cycle of sequencer time (real or catch-up). */
    private int step() {
        // Delayed $4017 apply (C1): on the 3rd/4th cycle after the write the
        // sequencer resets, the mode bit lands, and — bit 7 set — an
        // immediate quarter+half clock fires. The sequence does not also
        // advance on the apply cycle (the timer is being reset).
        if (pendingDelay > 0 && --pendingDelay == 0) {
            mode5 = (pendingValue & 0x80) != 0;
            cpuCycle = 0;
            return mode5 ? QUARTER | HALF : 0;
        }
        cpuCycle++;
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
        }
    }

    /**
     * $4017 write (C1). The IRQ-inhibit bit applies <b>immediately</b>
     * (bit 6 set clears the frame IRQ flag; clearing bit 6 never sets it).
     * The sequencer reset, the mode change and the bit-7 immediate
     * quarter+half clock are <b>delayed 3 or 4 CPU cycles</b> by write
     * parity: {@code duringApuCycle} true (the write cycle coincided with
     * an APU-cycle tick) → 3, false → 4 (research §1.2). A second write
     * during the delay supersedes the first. The delayed events emerge
     * from {@link #clock()}'s return mask at the apply cycle.
     */
    public void write4017(int value, boolean duringApuCycle) {
        irqInhibit = (value & 0x40) != 0;
        if (irqInhibit) {
            frameIrqFlag = false;
        }
        pendingValue = value;
        pendingDelay = duringApuCycle ? 3 : 4;
    }

    /**
     * Power-on/soft reset (research §1.9): reapplies the retained $4017
     * {@code value} immediately (no write delay — nothing is running to
     * observe it), clears the IRQ flag and any pending write, and
     * positions the sequencer at {@code bootOffset} — "as if $4017 were
     * written ~9–12 cycles before the first instruction", calibrated by
     * the APU against {@code apu_reset/4017_timing}.
     */
    public void reset(int value, int bootOffset) {
        mode5 = (value & 0x80) != 0;
        irqInhibit = (value & 0x40) != 0;
        frameIrqFlag = false;
        pendingDelay = 0;
        aheadCycles = 0;
        cpuCycle = bootOffset;
    }

    /** Level-held frame interrupt flag. */
    public boolean isFrameIrqFlag() {
        return frameIrqFlag;
    }

    /**
     * Clear the flag (a $4015 read). The read has already observed 1 when
     * the flag was up (status computed before the clear); the flag is
     * simply <b>re-asserted</b> by any remaining window cycle
     * (29829/29830), which is what makes mid-window reads appear not to
     * clear. A read on the LAST set cycle (29830) clears for good —
     * blargg {@code 6-irq_flag_timing} check #5 rejects the "no-clear on
     * a set cycle" race model (C2 finding: it reported the flag's last
     * set cycle one too late).
     */
    public void clearFrameIrqFlagOnRead() {
        frameIrqFlag = false;
    }

    /** Unconditional clear (reset path). */
    public void clearFrameIrqFlag() {
        frameIrqFlag = false;
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
