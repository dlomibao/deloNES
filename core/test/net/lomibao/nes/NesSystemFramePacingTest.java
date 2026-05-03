package net.lomibao.nes;

import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NesSystem#advance(double)} — the time-driven frame
 * pacing helper introduced in Step 8 of the playable-gen1 plan.
 *
 * <p>Contract:
 * <ul>
 *   <li>Advances an internal accumulator by {@code deltaSeconds}.</li>
 *   <li>While the accumulator covers a full NTSC frame period
 *       ({@link NesSystem#NTSC_FRAME_SECONDS}), runs one frame and
 *       subtracts the period.</li>
 *   <li>Caps at {@link NesSystem#MAX_FRAMES_PER_ADVANCE} to prevent the
 *       "death spiral" where a slow host swamps the emulator with
 *       catch-up frames.</li>
 *   <li>Returns the number of frames actually run on this call.</li>
 *   <li>Carries fractional accumulation across calls — the average
 *       frame rate over a long run matches NTSC.</li>
 * </ul>
 */
class NesSystemFramePacingTest {

    private NesSystem sys;

    @BeforeEach
    void setUp() {
        sys = NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(new PPU())
                .build();
    }

    @Test
    void ntscFrameSeconds_isReasonableConstant() {
        // 60.0988 Hz → ~16.6389 ms per frame
        assertTrue(NesSystem.NTSC_FRAME_SECONDS > 0.0166 && NesSystem.NTSC_FRAME_SECONDS < 0.0167,
                "NTSC frame period should be ~16.6 ms; got " + NesSystem.NTSC_FRAME_SECONDS);
    }

    @Test
    void advance_runsZeroFrames_whenDeltaIsZero() {
        int frames = sys.advance(0.0);
        assertEquals(0, frames, "no time elapsed → no frames run");
    }

    @Test
    void advance_runsZeroFrames_whenDeltaIsLessThanFramePeriod() {
        int frames = sys.advance(NesSystem.NTSC_FRAME_SECONDS / 2);
        assertEquals(0, frames, "half a frame's worth of time → 0 frames");
    }

    @Test
    void advance_runsOneFrame_whenDeltaJustExceedsFramePeriod() {
        // Slight overshoot to dodge floating-point cliffs.
        int frames = sys.advance(NesSystem.NTSC_FRAME_SECONDS * 1.001);
        assertEquals(1, frames, "one frame's worth → 1 frame");
    }

    @Test
    void advance_runsTwoFrames_whenDeltaCoversTwoFramePeriods() {
        int frames = sys.advance(NesSystem.NTSC_FRAME_SECONDS * 2.001);
        assertEquals(2, frames, "two frames' worth → 2 frames");
    }

    @Test
    void advance_capsAt_MAX_FRAMES_PER_ADVANCE_evenForHugeDelta() {
        // Simulate a 1-second hiccup. Without a cap this would try to
        // run 60 frames in one tick and stall the host.
        int frames = sys.advance(1.0);
        assertEquals(NesSystem.MAX_FRAMES_PER_ADVANCE, frames,
                "huge delta must be capped to MAX_FRAMES_PER_ADVANCE");
    }

    @Test
    void advance_carriesPartialAccumulation_acrossCalls() {
        // Two half-frame deltas in succession should run one frame total.
        int firstCall = sys.advance(NesSystem.NTSC_FRAME_SECONDS * 0.5);
        int secondCall = sys.advance(NesSystem.NTSC_FRAME_SECONDS * 0.6);
        assertEquals(0, firstCall, "first half-frame delta runs 0 frames");
        assertEquals(1, secondCall, "second half-frame delta crosses the period boundary → 1 frame");
    }

    @Test
    void advance_overManyCalls_runsFrameRateMatchingNtsc() {
        // Simulate 60 ms intervals (~3.6 frames each) for 10 calls = 600 ms total.
        // Expected frames: 600 ms / 16.6389 ms ≈ 36 frames.
        int totalFrames = 0;
        for (int i = 0; i < 10; i++) {
            totalFrames += sys.advance(0.06);
        }
        // Allow ±2 slack for floating-point + cap interactions.
        assertTrue(totalFrames >= 34 && totalFrames <= 38,
                "10 × 60ms ≈ 36 frames; got " + totalFrames);
    }

    @Test
    void advance_returnValue_matchesPpuFrameAdvances() {
        // Independent oracle: count rising-edge frame-rendered listener calls.
        int[] listenerCount = {0};
        sys.setFrameRenderedListener(s -> listenerCount[0]++);
        // Enable NMI so the rising-edge listener actually fires.
        sys.getPpu().cpuBusWrite(0x2000, (byte) 0x80);

        int totalFromAdvance = 0;
        for (int i = 0; i < 5; i++) {
            totalFromAdvance += sys.advance(NesSystem.NTSC_FRAME_SECONDS * 1.001);
        }
        assertEquals(totalFromAdvance, listenerCount[0],
                "advance() return value should equal listener fire count");
    }

    // ---- determinism ----

    @Test
    void runFrame_repeatedNTimes_producesIdenticalMasterClockProgression() {
        // Replay determinism: two NesSystems started identically and run
        // the same number of frames must end at the same master-clock
        // count. This is the foundation for replay/record features later.
        NesSystem sysA = NesSystem.builder()
                .cpu(new CPU6502()).ram(new Ram()).ppu(new PPU()).build();
        NesSystem sysB = NesSystem.builder()
                .cpu(new CPU6502()).ram(new Ram()).ppu(new PPU()).build();

        for (int i = 0; i < 5; i++) {
            sysA.runFrame();
            sysB.runFrame();
        }
        assertEquals(sysA.getMasterClockCount(), sysB.getMasterClockCount(),
                "two identical NesSystems running N frames must end at the same master-clock count");
    }
}
