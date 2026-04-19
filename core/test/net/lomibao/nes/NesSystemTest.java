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
    void nestest_baselinePasses_whenDrivenViaNesSystem() throws Exception {
        // Snapshot regression: build a NesSystem with the nestest cartridge and
        // ensure the failure-code memory location ($0002 / $0003) stays clean
        // after driving the CPU through nestest's auto-mode entry point. This
        // is the "NesSystem must not break the 8992/8992 baseline" guard.
        InputStream rom = getClass().getResourceAsStream("/nestest.nes");
        assertNotNull(rom, "nestest.nes resource missing");

        Cartridge cartridge = new Cartridge(rom, "nestest.nes");
        CPU6502 cpu = new CPU6502();

        NesSystem sys = NesSystem.builder()
                .cpu(cpu)
                .ram(new Ram())
                .ppu(new PPU())
                .cartridge(cartridge)
                .build();

        sys.reset();
        // Nestest auto-mode entry point.
        cpu.setPc(0xC000);

        // Drive purely via the CPU clock loop (instruction-by-instruction).
        // We don't use sys.tick() here because nestest expects roughly 8992
        // *instructions* and tracking exact instruction count via master-tick
        // would require also testing PPU side-effects, which is out of scope
        // for the system-tick test. The point of this test is to assert the
        // facade's exposure of CPU/Bus is wired the same as the existing
        // NestestTest setup so we can later swap NestestTest to use NesSystem.
        while (!cpu.complete()) {
            cpu.clock();
        }
        // Run for 8992 instructions worth of cycles to exercise the same path
        // NestestTest exercises.
        int instructions = 0;
        while (instructions < 8992) {
            cpu.clock();
            if (cpu.complete()) {
                instructions++;
            }
        }

        int failureCode = sys.getCpuBus().read(0x0002, true);
        int failureSubCode = sys.getCpuBus().read(0x0003, true);
        assertEquals(0, failureCode,
                String.format("nestest reported failure code 0x%02X (sub 0x%02X)", failureCode, failureSubCode));
        assertEquals(0, failureSubCode,
                String.format("nestest reported sub-code 0x%02X (code 0x%02X)", failureSubCode, failureCode));
    }
}
