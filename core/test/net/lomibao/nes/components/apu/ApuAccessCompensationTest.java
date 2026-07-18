package net.lomibao.nes.components.apu;

import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.CPUBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C2 — access-cycle compensation (seam S6, docs/apu-plan.md):
 * $4015/$4017 accesses made during an atomically-executed instruction
 * are serviced as-of {@code cpuCycle + (baseClocks − 1)}; the eager
 * per-cycle clocks that follow are no-ops until real time catches up.
 * Evolved from the C0 measurement spike (findings note in the plan doc).
 */
class ApuAccessCompensationTest {

    /** CPU stub pinning the S6 getter to a chosen in-flight base-clock value. */
    private static final class FixedBaseCpu extends CPU6502 {
        int base;

        @Override
        public int getInFlightBaseClocks() {
            return base;
        }
    }

    private final FixedBaseCpu cpu = new FixedBaseCpu();
    private final APU apu = new APU();
    private final CPUBus bus = CPUBus.builder().cpu(cpu).apu(apu).build().connect();

    private void clockApu(int n) {
        for (int i = 0; i < n; i++) {
            apu.clock();
        }
    }

    // ------------------------------------------------------------------
    // Compensated $4015 reads land on the correct side of the flag edge
    // ------------------------------------------------------------------

    @Test
    void read4015_2cycleInstruction_effectiveCycleBeforeEdge_readsClear() {
        apu.frameCounter().cpuCycle = 29826;
        cpu.base = 2; // effective = real + 1 → 29827, one short of the edge
        int status = bus.read(0x4015);
        assertEquals(0, status & 0x40, "effective cycle 29827 is before the flag edge");
        assertEquals(29827, apu.frameCounter().cycle());
    }

    @Test
    void read4015_3cycleInstruction_effectiveCycleOnEdge_readsSet_windowReAsserts() {
        apu.frameCounter().cpuCycle = 29826;
        cpu.base = 3; // effective = real + 2 → 29828, the flag-set cycle
        int status = bus.read(0x4015);
        assertEquals(0x40, status & 0x40, "effective cycle 29828 reads the flag set");
        assertFalse(apu.frameCounter().isFrameIrqFlag(), "the read clears the register");
        clockApu(3); // real time catches up (2 no-ops) and steps 29829
        assertTrue(apu.frameCounter().isFrameIrqFlag(),
                "the remaining window cycles re-assert the flag after the read");
    }

    @Test
    void read4015_4cycleInstruction_landsOnEdgeFromFurtherBack() {
        apu.frameCounter().cpuCycle = 29825;
        cpu.base = 4; // effective = real + 3 → 29828
        int status = bus.read(0x4015);
        assertEquals(0x40, status & 0x40, "4-cycle load compensates 3 cycles onto the edge");
    }

    // ------------------------------------------------------------------
    // Eager clocks after a compensated access are no-ops (no double-clock)
    // ------------------------------------------------------------------

    @Test
    void eagerClocksAfterCompensatedAccess_dontAdvanceUntilRealTimeCatchesUp() {
        apu.frameCounter().cpuCycle = 100;
        cpu.base = 4; // ahead 3
        bus.read(0x4015);
        assertEquals(103, apu.frameCounter().cycle(), "catch-up ran the counter to the access cycle");
        clockApu(3);
        assertEquals(103, apu.frameCounter().cycle(),
                "the 3 eager clocks that follow are no-ops (already consumed)");
        apu.clock();
        assertEquals(104, apu.frameCounter().cycle(), "real time caught up; clocking resumes");
    }

    @Test
    void frameClockCrossedDuringCatchUp_dispatchedToChannels_exactlyOnce() {
        // Load a noise length, position just before the 14913 half clock,
        // then read $4015 with a 4-cycle instruction: the half clock fires
        // during catch-up (decrement once) and must not fire again when
        // the eager clocks replay the same cycles.
        apu.noise().lengthCounter().setEnabled(true);
        apu.cpuBusWrite(0x400F, (byte) 0x00); // length index 0 → 10
        assertEquals(10, apu.noise().lengthCounter().value());
        apu.frameCounter().cpuCycle = 14911;
        cpu.base = 4; // effective 14914 — past the 14913 half clock
        bus.read(0x4015);
        assertEquals(9, apu.noise().lengthCounter().value(),
                "half clock at 14913 fired during catch-up");
        clockApu(6);
        assertEquals(9, apu.noise().lengthCounter().value(),
                "eager clocks past 14913 must not double-clock the length counter");
    }

    // ------------------------------------------------------------------
    // Compensated $4015/$4017 writes
    // ------------------------------------------------------------------

    @Test
    void write4015_servicedAtEffectiveCycle() {
        apu.frameCounter().cpuCycle = 14911;
        cpu.base = 4; // effective 14914
        bus.write(0x4015, (byte) 0x0F);
        assertEquals(14914, apu.frameCounter().cycle(),
                "$4015 write catch-up runs the frame counter to the store's final cycle");
    }

    @Test
    void write4017_parityIsTakenAtTheEffectiveCycle() {
        // Real parity after 2 APU clocks: an APU-cycle tick just happened
        // (duringApuCycle = true → delay 3). An odd compensation shift
        // (base 4 → ahead 3) flips it to 4; an even shift (base 3 →
        // ahead 2) keeps 3. Observed as the real-clock count until the
        // delayed sequencer reset applies: ahead no-ops + effective delay.
        clockApu(2);
        cpu.base = 4;
        bus.write(0x4017, (byte) 0x00);
        int untilReset = clocksUntilSequencerReset();
        assertEquals(3 + 4, untilReset,
                "odd ahead (3) flips parity: 3 catch-up no-ops + 4-cycle delay");

        clockApu(1); // realign to even parity (post-reset cycle count is odd)
        cpu.base = 3;
        bus.write(0x4017, (byte) 0x00);
        assertEquals(2 + 3, clocksUntilSequencerReset(),
                "even ahead (2) keeps parity: 2 catch-up no-ops + 3-cycle delay");
    }

    @Test
    void noInFlightInstruction_accessIsNotCompensated() {
        apu.frameCounter().cpuCycle = 200;
        cpu.base = 0; // idle CPU (DMA / reset paths / harness pokes)
        bus.read(0x4015);
        assertEquals(200, apu.frameCounter().cycle(), "no compensation without an instruction");
        assertFalse(apu.frameCounter().isFrameIrqFlag());
    }

    /** Real APU clocks until the frame counter's cycle() returns to 0. */
    private int clocksUntilSequencerReset() {
        for (int i = 1; i <= 16; i++) {
            apu.clock();
            if (apu.frameCounter().cycle() == 0) {
                return i;
            }
        }
        throw new AssertionError("delayed $4017 reset never applied within 16 clocks");
    }
}
