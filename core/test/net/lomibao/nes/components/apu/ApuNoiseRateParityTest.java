package net.lomibao.nes.components.apu;

import net.lomibao.nes.components.APU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Noise timer rate parity at the APU level (review round 1: noise was
 * absent from the wiring parity test entirely). Lives in the apu package
 * for LFSR field access. Period index 0 = 2 APU cycles per LFSR clock;
 * with the power-up-empty counter the fires land on APU clocks 1 and 3,
 * so 8 CPU clocks (= 4 APU clocks) advance the LFSR exactly twice:
 * seed 1 -> 0x4000 -> 0x2000. At (wrong) CPU rate the LFSR would clock
 * four times instead.
 */
class ApuNoiseRateParityTest {

    @Test
    void noiseLfsr_advancesExactlyTwice_in8CpuClocks() {
        APU apu = new APU();
        apu.cpuBusWrite(0x4015, (byte) 0x08);  // enable noise
        apu.cpuBusWrite(0x400E, (byte) 0x00);  // mode 0, period index 0
        assertEquals(1, apu.noise().lfsr, "power-up LFSR seed");
        for (int i = 0; i < 8; i++) {
            apu.clock();
        }
        assertEquals(0x2000, apu.noise().lfsr,
                "noise timer must run at APU rate: exactly 2 LFSR clocks in 8 CPU clocks");
    }
}
