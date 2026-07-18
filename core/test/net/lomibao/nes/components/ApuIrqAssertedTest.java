package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C3 — {@link APU#irqAsserted()} level semantics (seam S4,
 * docs/apu-plan.md Phase C): the line follows the frame-IRQ and DMC-IRQ
 * flags, is held until software clears them, and the two flags are
 * independent.
 */
class ApuIrqAssertedTest {

    private static APU apuWithFrameFlagSet() {
        APU apu = new APU(); // power-up $4017 = $00 → IRQ enabled (D8)
        for (int i = 0; i < 29828; i++) {
            apu.clock();
        }
        assertTrue(apu.frameCounter().isFrameIrqFlag());
        return apu;
    }

    @Test
    void notAssertedAtPowerUp_assertedOnceFlagSets_levelHeld() {
        APU apu = new APU();
        assertFalse(apu.irqAsserted(), "no IRQ before the flag window");
        for (int i = 0; i < 29828; i++) {
            apu.clock();
        }
        assertTrue(apu.irqAsserted(), "asserted when the frame flag sets");
        for (int i = 0; i < 500; i++) {
            apu.clock();
        }
        assertTrue(apu.irqAsserted(), "level-held until software clears the flag");
    }

    @Test
    void read4015_clearsFrameFlag_deasserts() {
        APU apu = apuWithFrameFlagSet();
        for (int i = 0; i < 5; i++) {
            apu.clock(); // move past the window so the clear sticks
        }
        apu.cpuBusRead(0x4015, false);
        assertFalse(apu.irqAsserted(), "$4015 read clears the frame flag → line drops");
    }

    @Test
    void write4017_bit6_deasserts() {
        APU apu = apuWithFrameFlagSet();
        for (int i = 0; i < 5; i++) {
            apu.clock();
        }
        apu.cpuBusWrite(0x4017, (byte) 0x40);
        assertFalse(apu.irqAsserted(), "$4017 bit 6 clears the frame flag → line drops");
    }

    @Test
    void dmcFlag_assertsIndependently_clearedBy4015Write_notRead() {
        APU apu = new APU();
        apu.dmcIrqFlag = true; // Phase D sets this for real
        assertTrue(apu.irqAsserted(), "DMC IRQ flag asserts the line on its own");
        apu.cpuBusRead(0x4015, false);
        assertTrue(apu.irqAsserted(), "$4015 READ never clears the DMC flag");
        apu.cpuBusWrite(0x4015, (byte) 0x00);
        assertFalse(apu.irqAsserted(), "$4015 write clears the DMC flag");
    }

    @Test
    void bothFlags_lineStaysUpUntilBothCleared() {
        APU apu = apuWithFrameFlagSet();
        for (int i = 0; i < 5; i++) {
            apu.clock();
        }
        apu.dmcIrqFlag = true;
        apu.cpuBusRead(0x4015, false); // clears frame flag only
        assertTrue(apu.irqAsserted(), "DMC flag still holds the line");
        apu.cpuBusWrite(0x4015, (byte) 0x00); // clears DMC flag
        assertFalse(apu.irqAsserted());
    }
}
