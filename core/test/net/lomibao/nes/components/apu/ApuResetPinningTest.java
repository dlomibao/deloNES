package net.lomibao.nes.components.apu;

import net.lomibao.nes.components.APU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase A4 — {@code APU.reset()} pins the noise LFSR to 1 and the
 * triangle sequence phase to 0 (research §1.9). Lives in the apu
 * package so it can drift the package-visible fields first.
 */
class ApuResetPinningTest {

    @Test
    void reset_pinsNoiseLfsrToOne() {
        APU apu = new APU();
        apu.noise().lfsr = 0x2BAD;
        apu.reset();
        assertEquals(1, apu.noise().lfsr());
    }

    @Test
    void reset_pinsTrianglePhaseToZero() {
        APU apu = new APU();
        apu.triangle().sequencePhase = 13;
        apu.reset();
        assertEquals(0, apu.triangle().sequencePhase());
    }
}
