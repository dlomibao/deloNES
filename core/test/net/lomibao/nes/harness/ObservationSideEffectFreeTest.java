package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase B1 gate (headless-harness plan, determinism audit): after seam S3,
 * observing every frame — including {@code peek(0x4016)} / {@code peek(0x4017)}
 * — produces a bit-identical run vs not observing at all.
 */
class ObservationSideEffectFreeTest {

    /** Observe aggressively at every frame boundary. */
    private static void observe(NesHarness h) {
        h.peek(0x4016);
        h.peek(0x4017);
        h.peek(0x4016); // repeated peeks must also be harmless
        for (int a = 0; a < 0x0800; a += 0x100) {
            h.peek(a);
        }
        h.peek(0x2002);
    }

    @Test
    void perFrameObservation_producesBitIdenticalFramebuffer_nestest() {
        NesHarness plain = NesHarness.fromResource("/nestest.nes");
        NesHarness observed = NesHarness.fromResource("/nestest.nes");

        for (int f = 0; f < 60; f++) {
            observe(observed);
            plain.runFrames(1);
            observed.runFrames(1);
        }
        observe(observed);

        assertArrayEquals(plain.ppu().getVisibleScreenPixels1D(),
                observed.ppu().getVisibleScreenPixels1D(),
                "framebuffer must be bit-identical whether or not we observe");
    }

    /**
     * The joypad-specific half. NOTE: this cross-check alone does NOT gate
     * S3 — the poll ROM re-strobes $4016 every loop iteration, which
     * re-latches the shift register and erases any peek-induced advance
     * long before the frame-end assertion (verified by reverting S3: this
     * test still passes). It remains as a divergence tripwire; the tests
     * that genuinely gate S3 are the readOnly additions in ControllerTest
     * and {@link #peekMidShift_doesNotAdvanceTheShiftRegister()} below.
     */
    @Test
    void perFramePeekOf4016_doesNotDesyncJoypadShiftRegister() {
        NesHarness plain = NesHarnessTestRoms.controllerPollHarness();
        NesHarness observed = NesHarnessTestRoms.controllerPollHarness();
        InputTimeline script = InputTimeline.builder()
                .press(Button.A).atFrame(1).holdFrames(8)
                .press(Button.START).atFrame(1).holdFrames(8)
                .build();
        plain.play(script);
        observed.play(script);

        for (int f = 0; f < 12; f++) {
            observe(observed);
            plain.runFrames(1);
            observed.runFrames(1);
            for (int i = 0; i < 8; i++) {
                assertEquals(plain.peek(0x0300 + i), observed.peek(0x0300 + i),
                        "frame " + f + " button slot " + i
                        + " diverged under observation");
            }
        }
    }

    /**
     * The direct S3 gate at the bus level: strobe once, consume half the
     * shift register with normal reads, peek repeatedly mid-shift, then
     * consume the rest. With S3 the peeks are invisible and all 8 bits
     * land in order; without it each peek advances the register and the
     * tail bits skew (empirically fails on a reverted S3).
     */
    @Test
    void peekMidShift_doesNotAdvanceTheShiftRegister() {
        NesHarness h = NesHarness.fromBytes(
                NesHarnessTestRoms.controllerPollRom(), "s3-gate.nes");
        h.controller().setButton(0, Button.A, true);
        h.controller().setButton(0, Button.START, true);

        // Strobe: latch the live buttons into the shift register.
        h.nes().getCpuBus().write(0x4016, (byte) 1);
        h.nes().getCpuBus().write(0x4016, (byte) 0);

        int[] bits = new int[8];
        for (int i = 0; i < 4; i++) {
            bits[i] = h.nes().getCpuBus().read(0x4016) & 0x01;
        }
        // Mid-shift observation burst — must not advance the register.
        for (int p = 0; p < 5; p++) {
            h.peek(0x4016);
        }
        for (int i = 4; i < 8; i++) {
            bits[i] = h.nes().getCpuBus().read(0x4016) & 0x01;
        }

        // Order A, B, SELECT, START, UP, DOWN, LEFT, RIGHT.
        assertEquals(1, bits[0], "A must be bit 0");
        assertEquals(0, bits[1], "B clear");
        assertEquals(0, bits[2], "SELECT clear");
        assertEquals(1, bits[3], "START must be bit 3 — a peek-advanced register skews this");
        for (int i = 4; i < 8; i++) {
            assertEquals(0, bits[i], "directional bits clear, tail must not skew");
        }
    }
}
