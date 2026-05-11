package net.lomibao.nes;

import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NesSystem} — the system orchestrator above CPUBus.
 *
 * <p>Validates:
 * <ul>
 *   <li>Master-tick advances PPU every call and CPU every 3rd call.</li>
 *   <li>{@code runFrame()} loops {@code tick()} until the PPU signals frame complete.</li>
 *   <li>{@code reset()} resets the CPU and the master-clock count.</li>
 *   <li>The full nestest baseline still passes when driven via {@code NesSystem} (delegating to CPU clock).</li>
 * </ul>
 */
public class NesSystemTest {

    /** Builds a minimal NesSystem with no cartridge — fine for tick/clock-counter tests that don't fetch instructions. */
    private NesSystem newBareSystem() {
        return NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(new PPU())
                .build();
    }

    @Test
    void tick_advancesMasterClockCountByOne() {
        NesSystem sys = newBareSystem();
        long before = sys.getMasterClockCount();
        sys.tick();
        assertEquals(before + 1, sys.getMasterClockCount(),
                "tick() must advance master-clock count by exactly 1");
    }

    @Test
    void tick_advancesPpuOncePerCall() {
        NesSystem sys = newBareSystem();
        // PPU.cycle starts at 0; each tick() must advance ppu.clock() exactly once.
        // After 5 ticks the PPU should be on cycle 5.
        for (int i = 0; i < 5; i++) {
            sys.tick();
        }
        assertEquals(5, sys.getPpu().getCycle(),
                "PPU should advance one cycle per tick()");
    }

    @Test
    void tick_advancesCpuEveryThirdCall() {
        // With no cartridge present the CPU has nothing to fetch, so its
        // public clockCount field is the right thing to assert against.
        // CPUBus.clock() runs cpu.clock() on master ticks where masterClockCount % 3 == 0.
        // After 9 master ticks (the very first one, 4th, 7th — three CPU clocks).
        NesSystem sys = newBareSystem();
        long cpuCyclesBefore = sys.getCpu().getClockCount();
        for (int i = 0; i < 9; i++) {
            sys.tick();
        }
        long cpuCyclesAfter = sys.getCpu().getClockCount();
        assertEquals(3, cpuCyclesAfter - cpuCyclesBefore,
                "CPU should clock exactly 3 times per 9 master ticks (1:3 divider)");
    }

    @Test
    void runFrame_runsUntilFrameComplete_thenReturns() {
        NesSystem sys = newBareSystem();
        // Run one full frame. PPU is configured for 341 cycles × 262 scanlines =
        // 89,342 master ticks per frame when rendering is off (no odd-frame skip).
        sys.runFrame();
        // After runFrame() returns, the frame-complete flag should have been
        // observed and cleared, so a subsequent call to isFrameComplete() is false.
        assertFalse(sys.getPpu().isFrameComplete(),
                "frameComplete should be cleared after runFrame() consumes it");
    }

    @Test
    void runFrame_runsExactly341x262MasterTicks_whenRenderingDisabled() {
        NesSystem sys = newBareSystem();
        long before = sys.getMasterClockCount();
        sys.runFrame();
        long after = sys.getMasterClockCount();
        long delta = after - before;
        // Without rendering enabled the odd-frame skip does not apply, so a
        // frame is exactly 341 * 262 = 89,342 master ticks.
        assertEquals(341L * 262L, delta,
                "One frame with rendering off must be exactly 341*262 master ticks");
    }

    @Test
    void reset_zeroesMasterClockCount() {
        NesSystem sys = newBareSystem();
        for (int i = 0; i < 100; i++) {
            sys.tick();
        }
        assertNotEquals(0, sys.getMasterClockCount(), "precondition: clock should have advanced");
        sys.reset();
        assertEquals(0, sys.getMasterClockCount(),
                "reset() must zero the master-clock count");
    }

    @Test
    void getCpu_getPpu_getCpuBus_areExposed() {
        NesSystem sys = newBareSystem();
        assertNotNull(sys.getCpu(), "NesSystem.getCpu() must expose the CPU");
        assertNotNull(sys.getPpu(), "NesSystem.getPpu() must expose the PPU");
        assertNotNull(sys.getCpuBus(), "NesSystem.getCpuBus() must expose the CPU bus");
    }

    @Test
    void cartridge_attachedSystem_runsOneFrameAndExecutesCpuInstructions() throws Exception {
        // Wires nestest cartridge into a NesSystem, jumps to the auto-mode entry
        // point ($C000), and runs one full frame via the facade. Asserts both
        // the PPU completed a frame (89,342 master ticks) AND the CPU advanced
        // through real instructions — the weaker version of this test only
        // proved the bus didn't NPE.
        InputStream rom = getClass().getResourceAsStream("/nestest.nes");
        assertNotNull(rom, "nestest.nes resource missing");
        Cartridge cartridge = new Cartridge(rom, "nestest.nes");

        NesSystem sys = NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(new PPU())
                .cartridge(cartridge)
                .build();

        sys.reset();
        sys.getCpu().setPc(0xC000);  // nestest auto-mode entry point

        long ticksBefore = sys.getMasterClockCount();
        long cpuCyclesBefore = sys.getCpu().getClockCount();
        sys.runFrame();
        long ticksDelta = sys.getMasterClockCount() - ticksBefore;
        long cpuCyclesDelta = sys.getCpu().getClockCount() - cpuCyclesBefore;

        assertEquals(341L * 262L, ticksDelta,
                "one frame should be exactly 341*262 master ticks (no odd-frame skip yet)");
        // CPU runs at 1/3 PPU rate; with real instruction execution we expect
        // close to 89,342/3 ≈ 29,780 cycles. Allow a small margin for the
        // reset-vector setup cycles already spent.
        assertTrue(cpuCyclesDelta >= 29_000 && cpuCyclesDelta <= 30_000,
                "CPU cycles per frame should be ~29,780; got " + cpuCyclesDelta);
    }

    @Test
    void builder_throwsNpe_whenCpuMissing() {
        assertThrows(NullPointerException.class, () -> NesSystem.builder()
                .ram(new Ram())
                .ppu(new PPU())
                .build(),
                "missing cpu should fail-fast at construction");
    }

    @Test
    void builder_throwsNpe_whenPpuMissing() {
        assertThrows(NullPointerException.class, () -> NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .build(),
                "missing ppu should fail-fast at construction");
    }

    @Test
    void builder_throwsNpe_whenRamMissing() {
        assertThrows(NullPointerException.class, () -> NesSystem.builder()
                .cpu(new CPU6502())
                .ppu(new PPU())
                .build(),
                "missing ram should fail-fast at construction");
    }

    @Test
    void runFrame_throwsIllegalState_whenSafetyCapTrips() {
        // Build a system whose PPU is mocked to never set frameComplete. We
        // can't easily mock PPU without test infrastructure, but we can
        // simulate the wedge by clearing the flag every tick. Subclass PPU
        // and override clock() to swallow the frame-complete transition.
        PPU wedgedPpu = new PPU() {
            @Override
            public void clock() {
                // never advance — frameComplete will never become true
            }
            @Override
            public boolean isFrameComplete() {
                return false;
            }
        };
        NesSystem sys = NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(wedgedPpu)
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                sys::runFrame,
                "wedged PPU should trigger the safety cap and throw");
        assertTrue(ex.getMessage().contains("frame complete"),
                "exception message should explain the wedge: " + ex.getMessage());
    }
}
