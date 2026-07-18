package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.apu.DmcChannel;
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

    /**
     * Frame-counter boot/reset offset (C1, research §1.9): the sequencer
     * behaves "as if $4017 were written ~9-12 CPU cycles before the first
     * instruction". Our reset path burns the CPU's 7 reset cycles WHILE
     * the APU clocks, and apu.clock() runs before cpu.clock() within the
     * dispatch turn, so the sequencer has stepped 8 times when the first
     * opcode executes: effective offset = this constant + 8. (Review
     * round 1 corrected the earlier "+7" accounting.) 8 sits below
     * §1.9's raw 9-12 range; reconciled because that range includes the
     * 3/4-cycle $4017 write delay the hardware "write" implies (9-12
     * minus 3/4 = 5-9 sequencer-visible), and the constant is ultimately
     * CALIBRATED against {@code apu_reset/4017_timing} (Phase C gate),
     * which is the authoritative pin — the range is an error bar, not a
     * spec.
     */
    static final int FRAME_COUNTER_BOOT_OFFSET = 0;

    private final FrameCounter frameCounter = new FrameCounter();
    private final PulseChannel pulse1 = new PulseChannel(true);
    private final PulseChannel pulse2 = new PulseChannel(false);
    private final TriangleChannel triangle = new TriangleChannel();
    private final NoiseChannel noise = new NoiseChannel();
    private final DmcChannel dmc = new DmcChannel();

    /**
     * Last value written to $4017 — reapplied on {@link #reset()}
     * (research §1.9: "the last-written $4017 value is retained").
     * Power-up value is $00 (D8).
     */
    private int last4017;

    /** $4015 bit 4 as last written — DMC enable (rules in {@link DmcChannel#setEnabled}). */
    private boolean dmcEnabled;

    /**
     * DMC interrupt flag ($4015 bit 7) — set on the last-byte fetch (D1),
     * cleared by any $4015 write or a $4010 write with bit 7 clear (never
     * by a $4015 read). Lives here beside the $4015 semantics; the
     * channel signals set-events via {@code acceptSampleByte}'s return.
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

    /** D2 stall lengths (D4): flat 4 for reload fetches, 3 for $4015 starts. */
    static final int RELOAD_STALL_CYCLES = 4;
    static final int START_STALL_CYCLES = 3;

    /**
     * D2 (seam S5): remaining DMC DMA stall cycles. While &gt; 0 the
     * CPU-turn slot in {@link CPUBus#clock()} is consumed here instead of
     * {@code dma}/{@code cpu} (DMC wins; an OAM burst pauses). The fetch
     * itself happens on the LAST stall cycle. Per the D4 parity argument
     * the reload stall length MUST stay even (4): {@code DmaController}
     * alternates get/put on master-clock parity and consecutive CPU turns
     * alternate that parity, so only an even-length stall preserves a
     * paused burst's alternation phase; the odd 3-cycle start stall can
     * only be armed by a CPU write to $4015 — impossible mid-burst while
     * the CPU is halted. Changing either length silently corrupts OAM —
     * the D2 alternation test is the tripwire.
     */
    private int dmcStallRemaining;

    /** Power-up (D8): $4017 = $00 applied at the C1 boot offset. */
    public APU() {
        frameCounter.reset(0x00, FRAME_COUNTER_BOOT_OFFSET);
    }

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
        // Pulse, noise and DMC timers run at APU rate (every 2nd CPU cycle).
        oddCpuCycle = !oddCpuCycle;
        if (!oddCpuCycle) {
            pulse1.clockTimer();
            pulse2.clockTimer();
            noise.clockTimer();
            dmc.clockTimer();
            // D2: a refill that drained the buffer arms the 4-cycle
            // reload stall; the CPU-turn slot of this same bus tick
            // (apu.clock() runs first — seam S2) is stall cycle 1, and
            // the fetch lands on the last stall cycle via tickDmcStall().
            if (dmcStallRemaining == 0 && dmc.needsSampleByte()) {
                dmcStallRemaining = RELOAD_STALL_CYCLES;
            }
        }
    }

    /**
     * Seam S5 (D2): true while a DMC DMA stall is consuming CPU-turn
     * slots — checked in {@link CPUBus#clock()} BEFORE {@code
     * dma.isActive()} (D4 arbitration: DMC wins, OAM pauses).
     */
    public boolean dmcStallPending() {
        return dmcStallRemaining > 0;
    }

    /**
     * Consume one CPU-turn slot as a DMC stall cycle (seam S5). On the
     * last cycle the sample byte is fetched through {@code cpuBus.read()}
     * — unless the reader was stopped mid-stall ($4015 bit-4 clear while
     * the stall was already committed), in which case the committed
     * cycles are still consumed but the fetch is aborted.
     */
    public void tickDmcStall() {
        dmcStallRemaining--;
        if (dmcStallRemaining == 0 && dmc.needsSampleByte()) {
            fetchDmcByte();
        }
    }

    /**
     * Perform the DMC memory-reader fetch through the CPU bus — invoked
     * from the last cycle of a D2 stall — and raise the DMC IRQ flag
     * when the channel reports a last-byte fetch.
     */
    private void fetchDmcByte() {
        CPUBus bus = getBus();
        int value = bus != null ? bus.read(dmc.currentAddress(), false) : 0;
        if (dmc.acceptSampleByte(value)) {
            dmcIrqFlag = true;
        }
    }

    /**
     * C2 access-cycle compensation (seam S6): a $4015/$4017 access made
     * during an atomically-executed instruction is serviced as-of
     * {@code cpuCycle + (instructionBaseClocks − 1)} — reads/writes land
     * on the final cycle of read/store instructions on a real 6502, while
     * our CPU issues them at instruction start. Runs the frame counter
     * forward to the access cycle (dispatching any frame clocks fired on
     * the way) and returns the number of cycles run ahead, which also
     * shifts the $4017 write-parity. RMW instructions to APU space are
     * pinned to the same rule (documented approximation). Returns 0 when
     * no instruction is in flight (DMA, reset paths, harness pokes).
     */
    private int catchUpForAccess() {
        CPUBus bus = getBus();
        if (bus == null) {
            return 0;
        }
        CPU6502 cpu = bus.getCpu();
        if (cpu == null) {
            return 0;
        }
        int base = cpu.getInFlightBaseClocks();
        int ahead = base > 0 ? base - 1 : 0;
        int events = frameCounter.catchUpTo(ahead);
        if (events != 0) {
            dispatchFrameClocks(events);
        }
        return ahead;
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
            // -- DMC ($4010-$4013, D1) --
            case 0x4010:
                dmc.writeControl(v);
                // Clearing the IRQ-enable bit also clears the DMC IRQ
                // flag (NESdev "APU DMC" $4010 semantics).
                if ((v & 0x80) == 0) {
                    dmcIrqFlag = false;
                }
                break;
            case 0x4011:
                dmc.writeDirectLoad(v);
                break;
            case 0x4012:
                dmc.writeSampleAddress(v);
                break;
            case 0x4013:
                dmc.writeSampleLength(v);
                break;
            // -- $4015 control (A3): enables + DMC-IRQ-flag clear --
            case 0x4015:
                // C2: the enable change lands on the store's final cycle —
                // half-frame clocks up to that cycle fire first.
                catchUpForAccess();
                pulse1.lengthCounter().setEnabled((v & 0x01) != 0);
                pulse2.lengthCounter().setEnabled((v & 0x02) != 0);
                triangle.lengthCounter().setEnabled((v & 0x04) != 0);
                noise.lengthCounter().setEnabled((v & 0x08) != 0);
                dmcEnabled = (v & 0x10) != 0;
                // Any $4015 write clears the DMC IRQ flag — never the
                // frame IRQ flag (§1.7).
                dmcIrqFlag = false;
                // D1 restart/stop rules: bit-4 set with bytes==0 restarts
                // the sample; clear zeroes bytes (buffered byte plays out).
                dmc.setEnabled(dmcEnabled);
                // D2: a $4015-triggered start with an empty buffer arms
                // the 3-cycle start stall (never while one is committed);
                // the fetch lands on the last stall cycle. The odd length
                // is safe per D4: this path requires a CPU write, which
                // cannot occur while the CPU is halted inside an OAM burst.
                if (dmcStallRemaining == 0 && dmc.needsSampleByte()) {
                    dmcStallRemaining = START_STALL_CYCLES;
                }
                break;
            case 0x4017:
                last4017 = v;
                // C2: the write is effective on the store's final cycle.
                int ahead = catchUpForAccess();
                // C1 write parity AT THE EFFECTIVE CYCLE: this CPU cycle
                // contained an APU-cycle tick iff oddCpuCycle is false
                // after clock()'s toggle (the pulse/noise timers just
                // clocked) → 3-cycle delay; otherwise 4. An odd
                // compensation shift flips the parity. The delayed
                // reset/mode/bit-7 clock emerge from frameCounter.clock()
                // at the apply cycle.
                boolean duringApuCycle = !oddCpuCycle;
                if ((ahead & 1) != 0) {
                    duringApuCycle = !duringApuCycle;
                }
                frameCounter.write4017(v, duringApuCycle);
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
            // C2: a real $4015 read observes state as-of the load's final
            // cycle. readOnly peeks are side-effect free (harness
            // observation contract) — never compensated.
            if (!readOnly) {
                catchUpForAccess();
            }
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
            // Bit 4: DMC bytes remaining > 0 (D1).
            if (dmc.bytesRemaining() > 0) {
                status |= 0x10;
            }
            if (frameCounter.isFrameIrqFlag()) {
                status |= 0x40;
            }
            if (dmcIrqFlag) {
                status |= 0x80;
            }
            // Reading ALWAYS clears the frame IRQ flag (never the DMC
            // flag); if the read lands inside the 3-cycle set window
            // (29828-29830) the remaining window cycles re-assert it, so
            // only a read on 29830 clears it for good. (Corrected in C2
            // from the research's "same-cycle read returns 1 without
            // clearing" model, which fails blargg 6-irq_flag_timing #5.)
            // readOnly peeks are side-effect free (harness contract).
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
        // DMC reset (D1, research §1.9): reader stopped (bytes := 0) and
        // $4011 &= 1 applied to the delta counter. Any committed stall is
        // dropped with the rest of the in-flight state (D2).
        dmc.setEnabled(false);
        dmc.resetOutputLevel();
        dmcStallRemaining = 0;
        // Retained $4017 reapplied immediately at the boot offset (C1);
        // the frame IRQ flag and any pending delayed write are dropped
        // (apu_reset/irq_flag_cleared, 4017_timing).
        frameCounter.reset(last4017, FRAME_COUNTER_BOOT_OFFSET);
        noise.resetLfsr();
        triangle.resetPhase();
    }

    /**
     * Seam S4 (C3): the APU's level-held IRQ line — asserted while the
     * frame-IRQ or DMC-IRQ flag is up, dropped only when software clears
     * them ($4015 read / $4017 bit 6 for the frame flag; $4015 write for
     * the DMC flag). Polled from {@code NesSystem.tick()}; never cleared
     * by the CPU taking the interrupt.
     */
    public boolean irqAsserted() {
        return frameCounter.isFrameIrqFlag() || dmcIrqFlag;
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

    public DmcChannel dmc() {
        return dmc;
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
