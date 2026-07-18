package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.apu.FrameCounter;
import net.lomibao.nes.components.apu.NoiseChannel;
import net.lomibao.nes.components.apu.PulseChannel;
import net.lomibao.nes.components.apu.TriangleChannel;

/**
 * Audio Processing Unit (Ricoh 2A03 APU side) — rewritten in place from
 * the register-echo stub per docs/apu-plan.md Phase A.
 *
 * <p>Registers $4000-$401F on the CPU bus (reads: only $4015 is
 * readable; $4016/$4017 reads are routed to the controller by
 * {@link CPUBus} and never reach here). {@link #clock()} is called once
 * per CPU cycle from the {@code phase == 0} branch of
 * {@link CPUBus#clock()}, first in the branch (seam S2) — the APU never
 * stops, even during DMA stalls.
 *
 * <p>Power-up state (D8, research §1.9): $4017 = $00 — 4-step mode with
 * the frame IRQ <em>enabled</em> — modeled faithfully from day one. The
 * frame IRQ flag is set/read/cleared correctly in Phase A but not yet
 * delivered to the CPU (delivery is Phase C, seam S4).
 *
 * <p>TeaVM hot path: {@code clock()} is int-only — no {@code long}, no
 * allocation, no boxing, no String ops.
 */
@Log4j2
public class APU extends CPUBusComponent {
    // https://www.nesdev.org/wiki/APU#Registers

    public static final int START_ADDRESS = 0x4000;
    public static final int END_ADDRESS = 0x4020; // exclusive

    private final FrameCounter frameCounter = new FrameCounter();
    private final PulseChannel pulse1 = new PulseChannel(true);
    private final PulseChannel pulse2 = new PulseChannel(false);
    private final TriangleChannel triangle = new TriangleChannel();
    private final NoiseChannel noise = new NoiseChannel();

    /**
     * Last value written to $4017 — reapplied on {@link #reset()}
     * (research §1.9: "the last-written $4017 value is retained").
     * Power-up value is $00 (D8).
     */
    private int last4017;

    /** $4015 bit 4 — DMC enable. Restart/stop rules land in Phase D. */
    private boolean dmcEnabled;

    /**
     * DMC interrupt flag ($4015 bit 7). Never set until Phase D — but the
     * clear paths ($4015 write; NOT $4015 read) exist now (A3).
     * Package-visible so same-package tests can pin the clear semantics.
     */
    boolean dmcIrqFlag;

    @Override
    public int getCPUBusStartAddress() {
        return START_ADDRESS;
    }

    @Override
    public int getCPUBusEndAddress() {
        return END_ADDRESS;
    }

    /**
     * CPU-cycle parity: pulse/noise timers clock every 2nd CPU cycle
     * (1 APU cycle — research §1.1); the triangle timer every CPU cycle.
     */
    private boolean oddCpuCycle;

    /**
     * Advance one CPU cycle (seam S2). Dispatches the frame counter's
     * quarter/half-frame clocks to the channel units and runs the
     * channel timers at their native rates.
     */
    public void clock() {
        int events = frameCounter.clock();
        if (events != 0) {
            dispatchFrameClocks(events);
        }
        // Triangle timer runs at CPU rate — the one fast-clock channel.
        triangle.clockTimer();
        // Pulse (and noise) timers run at APU rate (every 2nd CPU cycle).
        oddCpuCycle = !oddCpuCycle;
        if (!oddCpuCycle) {
            pulse1.clockTimer();
            pulse2.clockTimer();
            noise.clockTimer();
        }
    }

    /** Route quarter/half-frame clocks to the channels (research §1.2). */
    private void dispatchFrameClocks(int events) {
        if ((events & FrameCounter.QUARTER) != 0) {
            // Envelopes + triangle linear counter.
            pulse1.clockQuarterFrame();
            pulse2.clockQuarterFrame();
            triangle.clockQuarterFrame();
            noise.clockQuarterFrame();
        }
        if ((events & FrameCounter.HALF) != 0) {
            // Sweeps + length counters.
            pulse1.clockHalfFrame();
            pulse2.clockHalfFrame();
            triangle.clockHalfFrame();
            noise.clockHalfFrame();
        }
    }

    @Override
    public void cpuBusWrite(int address, byte value) {
        int v = Byte.toUnsignedInt(value);
        switch (address) {
            // -- pulse 1 ($4000-$4003, B3) --
            case 0x4000:
                pulse1.writeControl(v);
                break;
            case 0x4001:
                pulse1.writeSweep(v);
                break;
            case 0x4002:
                pulse1.writeTimerLow(v);
                break;
            case 0x4003:
                pulse1.writeTimerHigh(v);
                break;
            // -- pulse 2 ($4004-$4007, B3) --
            case 0x4004:
                pulse2.writeControl(v);
                break;
            case 0x4005:
                pulse2.writeSweep(v);
                break;
            case 0x4006:
                pulse2.writeTimerLow(v);
                break;
            case 0x4007:
                pulse2.writeTimerHigh(v);
                break;
            // -- triangle ($4008-$400B, B4) --
            case 0x4008:
                // Bit 7 is the triangle control flag AND length halt.
                triangle.writeLinear(v);
                break;
            case 0x400A:
                triangle.writeTimerLow(v);
                break;
            case 0x400B:
                triangle.writeTimerHigh(v);
                break;
            // -- noise ($400C-$400F, B5) --
            case 0x400C:
                noise.writeControl(v);
                break;
            case 0x400E:
                noise.writeMode(v);
                break;
            case 0x400F:
                noise.writeLength(v);
                break;
            // -- $4015 control (A3): enables + DMC-IRQ-flag clear --
            case 0x4015:
                pulse1.lengthCounter().setEnabled((v & 0x01) != 0);
                pulse2.lengthCounter().setEnabled((v & 0x02) != 0);
                triangle.lengthCounter().setEnabled((v & 0x04) != 0);
                noise.lengthCounter().setEnabled((v & 0x08) != 0);
                dmcEnabled = (v & 0x10) != 0;
                // Any $4015 write clears the DMC IRQ flag — never the
                // frame IRQ flag (§1.7). DMC restart/stop rules: Phase D.
                dmcIrqFlag = false;
                break;
            case 0x4017:
                last4017 = v;
                int immediate = frameCounter.write4017(v);
                if (immediate != 0) {
                    dispatchFrameClocks(immediate);
                }
                break;
            default:
                // Remaining registers decode into channel/unit state as
                // the channels land (A2/A3/B/D). Never stored in a raw
                // byte array — the stub's echo behavior is gone.
                break;
        }
    }

    /**
     * If read only is true, only reads current state — reads on the 6502
     * can under normal operation have side effects ($4015 read clears the
     * frame IRQ flag, Phase A3).
     */
    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        if (address < START_ADDRESS || address >= END_ADDRESS) {
            log.error("attempting to read memory out of range {}. valid range [{},{}]",
                    address, START_ADDRESS, END_ADDRESS);
            return 0;
        }
        // $4015 status: IF-D NT21 (§1.7). Bit 5 reads 0 — open bus is a
        // ratified non-goal (D10). Bits 4/7 stay 0 until the DMC lands
        // (Phase D); the flag/enable plumbing already exists.
        if (address == 0x4015) {
            int status = 0;
            if (pulse1.lengthCounter().isActive()) {
                status |= 0x01;
            }
            if (pulse2.lengthCounter().isActive()) {
                status |= 0x02;
            }
            if (triangle.lengthCounter().isActive()) {
                status |= 0x04;
            }
            if (noise.lengthCounter().isActive()) {
                status |= 0x08;
            }
            // Bit 4: DMC bytes remaining > 0 — always 0 until Phase D.
            if (frameCounter.isFrameIrqFlag()) {
                status |= 0x40;
            }
            if (dmcIrqFlag) {
                status |= 0x80;
            }
            // Reading clears the frame IRQ flag (never the DMC flag) —
            // except on the very cycle the flag is being set, which reads
            // 1 without clearing (§1.7 race). readOnly peeks are
            // side-effect free (harness observation contract).
            if (!readOnly) {
                frameCounter.clearFrameIrqFlagOnRead();
            }
            return status;
        }
        // Every other APU register is write-only and reads as 0.
        return 0;
    }

    /**
     * Power-on/soft reset (seam S3; Phase A4, research §1.9): acts as
     * $4015 = $00 (all channels off, lengths forced to 0, DMC IRQ flag
     * cleared), retains and reapplies the last $4017 value, pins the
     * noise LFSR to 1 and the triangle sequence phase to 0. The frame
     * IRQ flag is cleared ({@code apu_reset/irq_flag_cleared}).
     */
    public void reset() {
        // Reset APU-cycle parity too: post-reset put/get parity becomes
        // test-visible in Phase C (jitter), and leaving it floating made
        // parity depend on when reset occurred (Phase B review finding).
        oddCpuCycle = false;
        // Acts as $4015 = $00.
        pulse1.lengthCounter().setEnabled(false);
        pulse2.lengthCounter().setEnabled(false);
        triangle.lengthCounter().setEnabled(false);
        noise.lengthCounter().setEnabled(false);
        dmcEnabled = false;
        dmcIrqFlag = false;
        frameCounter.clearFrameIrqFlag();
        // Retained $4017 reapplied; the bit-7 immediate clock mask is
        // dropped at reset (nothing is running to clock).
        frameCounter.write4017(last4017);
        noise.resetLfsr();
        triangle.resetPhase();
    }

    /** Narrow test/diagnostic seam onto the frame counter. */
    public FrameCounter frameCounter() {
        return frameCounter;
    }

    public PulseChannel pulse1() {
        return pulse1;
    }

    public PulseChannel pulse2() {
        return pulse2;
    }

    public TriangleChannel triangle() {
        return triangle;
    }

    public NoiseChannel noise() {
        return noise;
    }

    /** Last value written to $4017 (retained across reset — A4). */
    public int getLast4017() {
        return last4017;
    }

    /** $4015 bit 4 as last written (DMC behavior lands in Phase D). */
    public boolean isDmcEnabled() {
        return dmcEnabled;
    }
}
