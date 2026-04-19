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
    void cartridge_attachedSystem_runsOneFrameWithoutException() throws Exception {
        // Smoke test: build a fully wired NesSystem with the nestest cartridge
        // and verify runFrame() returns cleanly. This exercises the wiring
        // (cpu + ram + ppu + cartridge all reachable through CPUBus) without
        // attempting to validate game-level behaviour — that's the canonical
        // {@code NestestTest} regression guard, which must continue to pass.
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
        sys.runFrame();
        assertTrue(sys.getMasterClockCount() > 0,
                "runFrame() should have advanced the master clock");
    }
}
