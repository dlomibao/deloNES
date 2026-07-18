package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B — APU-level wiring: register decode reaches the channel
 * units, the frame counter's quarter/half clocks drive envelopes /
 * linear counter / sweeps, and the channel timers run at their native
 * rates from the per-CPU-cycle {@link APU#clock()} (research §1.1:
 * pulse/noise at APU rate, triangle at CPU rate).
 */
class ApuChannelWiringTest {

    @Test
    void pulseRegisters_decodeIntoChannelUnits() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4000, (byte) 0xBF); // duty 2, halt, constant 15
        apu.cpuBusWrite(0x4001, (byte) 0x91); // sweep enabled P=1 shift=1
        apu.cpuBusWrite(0x4002, (byte) 0xAB);
        apu.cpuBusWrite(0x4003, (byte) 0x05); // timer hi 5
        assertEquals(0x5AB, apu.pulse1().timerPeriod());
        assertTrue(apu.pulse1().sweep().isEnabled());
        assertTrue(apu.pulse1().envelope().isConstantVolume());
        assertTrue(apu.pulse1().envelope().isStartFlag(), "$4003 starts the envelope");

        apu.cpuBusWrite(0x4006, (byte) 0x34);
        apu.cpuBusWrite(0x4007, (byte) 0x02);
        assertEquals(0x234, apu.pulse2().timerPeriod());
    }

    @Test
    void triangleAndNoiseRegisters_decodeIntoChannelUnits() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4008, (byte) 0x85); // control set, R=5
        apu.cpuBusWrite(0x400A, (byte) 0xCD);
        apu.cpuBusWrite(0x400B, (byte) 0x02);
        assertEquals(0x2CD, apu.triangle().timerPeriod());
        assertTrue(apu.triangle().isControlFlag());
        assertTrue(apu.triangle().isLinearReloadFlag(), "$400B arms the linear reload");

        apu.cpuBusWrite(0x400C, (byte) 0x15); // constant 5
        apu.cpuBusWrite(0x400E, (byte) 0x83); // mode 1, index 3
        apu.cpuBusWrite(0x400F, (byte) 0x08);
        assertTrue(apu.noise().isModeFlag());
        assertEquals(16, apu.noise().timerPeriod(), "index 3 = 32 CPU = 16 APU cycles");
        assertTrue(apu.noise().envelope().isStartFlag(), "$400F starts the envelope");
    }

    @Test
    void quarterFrameClocks_driveEnvelopesAndLinearCounter() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x0F);
        apu.cpuBusWrite(0x4000, (byte) 0x00); // p1 envelope mode V=0
        apu.cpuBusWrite(0x4003, (byte) 0x08); // start envelope
        apu.cpuBusWrite(0x4008, (byte) 0x07); // triangle: control clear, R=7
        apu.cpuBusWrite(0x400B, (byte) 0x08); // linear reload flag
        for (int i = 0; i < 7457; i++) {
            apu.clock(); // first quarter-frame at 7457 (mode 0)
        }
        assertEquals(15, apu.pulse1().envelope().decayLevel(),
                "quarter clock consumed the envelope start flag");
        assertEquals(7, apu.triangle().linearCounter(),
                "quarter clock reloaded the linear counter");
    }

    @Test
    void halfFrameClocks_driveSweeps() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4002, (byte) 0x00);
        apu.cpuBusWrite(0x4003, (byte) 0x01); // period 0x100
        apu.cpuBusWrite(0x4001, (byte) 0x81); // sweep enabled, add, shift 1
        for (int i = 0; i < 14913; i++) {
            apu.clock(); // first half-frame at 14913 (mode 0)
        }
        assertEquals(0x180, apu.pulse1().timerPeriod(),
                "half-frame clock ran the sweep against the live period");
    }

    @Test
    void channelTimers_runAtNativeRates() {
        APU apu = new APU();
        // Triangle audible: length + linear + timer t=3 (>= 2).
        apu.cpuBusWrite(0x4015, (byte) 0x0F);
        apu.cpuBusWrite(0x4008, (byte) 0x87); // control set, R=7
        apu.cpuBusWrite(0x400A, (byte) 0x03);
        apu.cpuBusWrite(0x400B, (byte) 0x08);
        apu.pulse1().lengthCounter().setEnabled(true);
        apu.cpuBusWrite(0x4002, (byte) 0x08); // pulse t=8
        apu.cpuBusWrite(0x4003, (byte) 0x08);
        // Force the linear counter live without waiting for a quarter clock:
        apu.triangle().clockQuarterFrame();
        int triPhase0 = apu.triangle().sequencePhase();
        int p1Phase0 = apu.pulse1().sequencePhase();
        for (int i = 0; i < 8; i++) {
            apu.clock();
        }
        // EXACT phase math (review round 1: the old != assertions were
        // vacuous — deleting the APU-rate gate passed the whole suite).
        // Timers fire when counter==0 then reload to period, so with the
        // power-up-empty counter the first clock fires immediately.
        // Triangle clocks every CPU cycle: t=3 fires at CPU clocks 1 and 5
        // → exactly 2 steps after 8 CPU clocks. If it were (wrongly)
        // demoted to APU rate it would fire only once → phase 1.
        assertEquals((triPhase0 + 2) & 0x1F, apu.triangle().sequencePhase(),
                "triangle timer must advance at CPU rate: exactly 2 steps in 8 CPU cycles");
        for (int i = 0; i < 26; i++) {
            apu.clock(); // 34 CPU total = 17 APU cycles
        }
        // Pulse clocks at APU rate (every 2nd CPU cycle): t=8 fires at APU
        // clocks 1 and 10 → exactly 2 steps after 17 APU cycles. At CPU
        // rate it would have fired at 1, 10, 19, 28 → phase +4.
        assertEquals((p1Phase0 + 2) & 0x07, apu.pulse1().sequencePhase(),
                "pulse timer must advance at APU rate: exactly 2 steps in 34 CPU cycles");
    }
}
