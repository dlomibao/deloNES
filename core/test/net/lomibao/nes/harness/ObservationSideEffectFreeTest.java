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
     * The joypad-specific half: a ROM that strobes and shifts $4016 into
     * $0300-$0307 every iteration. Without S3, a boundary peek of $4016
     * advances the shift register mid-loop and the stored button bits skew.
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
}
