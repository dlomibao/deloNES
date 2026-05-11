package net.lomibao.nes;

import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the NMI rising-edge polling and frame-rendered listener
 * introduced in Step 2 of the playable-gen1 plan.
 *
 * <p>Contract:
 * <ul>
 *   <li>Each {@code tick()} polls {@code ppu.consumeNmi()} after advancing
 *       the bus. On a rising edge, {@code cpu.nmi()} is invoked AND any
 *       registered frame-rendered listener fires once.</li>
 *   <li>If PPUCTRL bit 7 is clear, no NMI rising edge occurs and the listener
 *       does not fire.</li>
 *   <li>The listener fires once per VBlank, not per tick.</li>
 * </ul>
 */
class NesSystemNmiHookTest {

    private NesSystem newBareSystem() {
        return NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(new PPU())
                .build();
    }

    /** Mock CPU that just counts how many times nmi() was invoked. */
    static class NmiCountingCpu extends CPU6502 {
        int nmiCount = 0;
        @Override
        public void nmi() {
            nmiCount++;
        }
    }

    private NesSystem withNmiCountingCpu(NmiCountingCpu cpu) {
        return NesSystem.builder()
                .cpu(cpu)
                .ram(new Ram())
                .ppu(new PPU())
                .build();
    }

    /** Writes PPUCTRL via the bus to enable NMI in the PPU. */
    private void enableNmi(NesSystem sys) {
        sys.getPpu().cpuBusWrite(0x2000, (byte) 0x80);
    }

    @Test
    void nmi_isInvokedOnCpu_whenPpuEntersVBlank() {
        NmiCountingCpu cpu = new NmiCountingCpu();
        NesSystem sys = withNmiCountingCpu(cpu);
        enableNmi(sys);
        sys.runFrame();
        assertEquals(1, cpu.nmiCount,
                "NesSystem must invoke cpu.nmi() exactly once per frame on VBlank entry");
    }

    @Test
    void nmi_isNotInvoked_whenPpuCtrlBit7Clear() {
        NmiCountingCpu cpu = new NmiCountingCpu();
        NesSystem sys = withNmiCountingCpu(cpu);
        // PPUCTRL bit 7 = 0 → NMI suppressed
        sys.getPpu().cpuBusWrite(0x2000, (byte) 0x00);
        sys.runFrame();
        assertEquals(0, cpu.nmiCount,
                "PPUCTRL bit 7 clear should suppress the NMI rising edge");
    }

    @Test
    void frameRenderedListener_firesOnce_perFrame() {
        NesSystem sys = newBareSystem();
        enableNmi(sys);
        int[] callCount = {0};
        sys.setFrameRenderedListener(s -> callCount[0]++);
        sys.runFrame();
        assertEquals(1, callCount[0],
                "frame-rendered listener should fire exactly once per VBlank entry");
    }

    @Test
    void frameRenderedListener_doesNotFire_whenNmiSuppressed() {
        NesSystem sys = newBareSystem();
        // PPUCTRL bit 7 clear → no NMI rising edge → listener should not fire
        sys.getPpu().cpuBusWrite(0x2000, (byte) 0x00);
        int[] callCount = {0};
        sys.setFrameRenderedListener(s -> callCount[0]++);
        sys.runFrame();
        assertEquals(0, callCount[0],
                "no NMI rising edge → no listener call");
    }

    @Test
    void frameRenderedListener_firesEachFrame_acrossMultipleFrames() {
        NesSystem sys = newBareSystem();
        enableNmi(sys);
        int[] callCount = {0};
        sys.setFrameRenderedListener(s -> callCount[0]++);
        sys.runFrame();
        sys.runFrame();
        sys.runFrame();
        assertEquals(3, callCount[0],
                "listener should fire once per frame across multiple frames");
    }

    @Test
    void frameRenderedListener_canBeReplaced() {
        NesSystem sys = newBareSystem();
        enableNmi(sys);
        int[] firstCount = {0};
        int[] secondCount = {0};
        sys.setFrameRenderedListener(s -> firstCount[0]++);
        sys.runFrame();
        sys.setFrameRenderedListener(s -> secondCount[0]++);
        sys.runFrame();
        assertEquals(1, firstCount[0], "first listener should have seen exactly one frame");
        assertEquals(1, secondCount[0], "second listener should have seen exactly one frame");
    }

    @Test
    void frameRenderedListener_canBeNull_unregistered() {
        NesSystem sys = newBareSystem();
        enableNmi(sys);
        sys.setFrameRenderedListener(s -> { throw new RuntimeException("should be unregistered"); });
        sys.setFrameRenderedListener(null);
        // If the listener wasn't unregistered, this would throw.
        sys.runFrame();
    }
}
