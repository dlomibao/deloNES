package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A2 — {@link NesHarness} facade: frame counter semantics (D2), input
 * application at the frame boundary, runUntil cap, skip-if-absent (D8).
 */
class NesHarnessTest {

    /** See {@link NesHarnessTestRoms#controllerPollRom()} for the program listing. */
    private static NesHarness pollHarness() {
        return NesHarnessTestRoms.controllerPollHarness();
    }

    @Test
    void frameCounterCountsCompletedRunFrames() {
        NesHarness h = pollHarness();
        assertEquals(0, h.frame(), "starts at 0 before any frame runs");
        h.runFrames(3);
        assertEquals(3, h.frame());
        h.runToFrame(10);
        assertEquals(10, h.frame());
    }

    @Test
    void runToFramePastFrameThrows() {
        NesHarness h = pollHarness();
        h.runFrames(5);
        assertThrows(IllegalArgumentException.class, () -> h.runToFrame(3));
    }

    @Test
    void timelinePressIsVisibleToStrobingRom() {
        NesHarness h = pollHarness();
        h.play(InputTimeline.builder()
                .press(Button.A).atFrame(2).holdFrames(2)      // frames 2-3
                .press(Button.START).atFrame(2).holdFrames(2)
                .build());
        h.runToFrame(2);
        assertEquals(0, h.peek(0x0300), "A not yet pressed during frames 0-1");
        h.runToFrame(4); // frames 2 and 3 ran with the buttons held
        assertEquals(1, h.peek(0x0300), "A bit latched during frame 3");
        assertEquals(1, h.peek(0x0303), "START bit latched during frame 3");
        assertEquals(0, h.peek(0x0301), "B never pressed");
        h.runToFrame(6); // released before frame 4's first tick
        assertEquals(0, h.peek(0x0300), "A released after the hold window");
        assertEquals(0, h.peek(0x0303));
    }

    @Test
    void withoutTimelineNoButtonsAreSeen() {
        NesHarness h = pollHarness();
        h.runFrames(4);
        for (int i = 0; i < 8; i++) {
            assertEquals(0, h.peek(0x0300 + i), "button " + i + " must be idle");
        }
    }

    @Test
    void runUntilReturnsWhenConditionMet() {
        NesHarness h = pollHarness();
        h.play(InputTimeline.builder()
                .press(Button.A).atFrame(2).holdFrames(3)
                .build());
        h.runUntil(() -> h.peek(0x0300) == 1, 600);
        assertEquals(1, h.peek(0x0300));
        assertTrue(h.frame() <= 5, "condition met within the press window, was frame " + h.frame());
    }

    @Test
    void runUntilCapThrowsAssertionError() {
        NesHarness h = pollHarness();
        AssertionError e = assertThrows(AssertionError.class,
                () -> h.runUntil(() -> false, 5));
        assertTrue(e.getMessage().contains("5"), "message names the cap: " + e.getMessage());
        assertEquals(5, h.frame(), "cap frames were actually run");
    }

    @Test
    void atFrameHookFiresOnceAtItsBoundary() {
        NesHarness h = pollHarness();
        AtomicInteger fired = new AtomicInteger();
        AtomicInteger frameWhenFired = new AtomicInteger(-1);
        h.atFrame(3, hh -> {
            fired.incrementAndGet();
            frameWhenFired.set(hh.frame());
        });
        h.runToFrame(6);
        assertEquals(1, fired.get(), "one-shot hook fires exactly once");
        assertEquals(3, frameWhenFired.get(),
                "hook runs at the frame-3 boundary, before frame 3's first tick");
    }

    @Test
    void fromResourceLoadsNestest() {
        NesHarness h = NesHarness.fromResource("/nestest.nes");
        assertNotNull(h.cpu());
        assertNotNull(h.ppu());
        assertNotNull(h.controller());
        h.runFrames(2);
        assertEquals(2, h.frame());
    }

    @Test
    void fromRealRomSkipsCleanlyWhenAbsent() {
        assertThrows(TestAbortedException.class,
                () -> NesHarness.fromRealRom("definitely-not-present-9c41d0.nes"));
    }
}
