package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.Controller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D2 — {@link InputRecorder} (headless-harness plan).
 *
 * <p>The recorder samples both controllers' live state (seam S4,
 * {@code Controller.isPressed}) once per frame boundary — the same
 * D2 boundary {@link InputTimelinePlayer} applies edges at — and emits
 * the identical edge representation, so a recorded scripted run
 * round-trips to the very timeline that drove it.
 */
class InputRecorderTest {

    /**
     * Drive {@code controller} with {@code timeline} for {@code frames}
     * frames exactly as the harness does (player applies edges for frame N
     * at the boundary, then the recorder samples that same boundary).
     */
    private static InputRecorder recordScriptedRun(InputTimeline timeline,
                                                   Controller controller, int frames) {
        InputTimelinePlayer player = new InputTimelinePlayer(timeline);
        InputRecorder recorder = new InputRecorder();
        for (int f = 0; f < frames; f++) {
            player.applyUpTo(f, controller);
            recorder.sampleFrame(controller);
            // (the emulated frame would tick here)
        }
        return recorder;
    }

    @Test
    void recordedEdges_equalAppliedEdges_forScriptedRun() {
        InputTimeline script = InputTimeline.builder()
                .press(Button.START).atFrame(10).holdFrames(5)
                .press(Button.A).atFrame(12).holdFrames(2)
                .hold(Button.RIGHT).fromFrame(20).toFrame(29)
                .build();
        InputRecorder recorder = recordScriptedRun(script, new Controller(), 40);
        assertEquals(script.edges(), recorder.toTimeline().edges(),
                "recorded timeline must be edge-identical to the script that drove it");
        assertEquals(40, recorder.frames());
    }

    @Test
    void recordedEdges_twoPlayer_routedAndOrderedLikeTheBuilder() {
        InputTimeline script = InputTimeline.builder()
                .press(Button.B).atFrame(3).holdFrames(4)
                .player(1).press(Button.SELECT).atFrame(3).holdFrames(1)
                .player(1).press(Button.UP).atFrame(5).holdFrames(2)
                .build();
        InputRecorder recorder = recordScriptedRun(script, new Controller(), 10);
        assertEquals(script.edges(), recorder.toTimeline().edges());
    }

    @Test
    void noInput_recordsNoEdges_butCountsFrames() {
        InputRecorder recorder = new InputRecorder();
        Controller controller = new Controller();
        for (int f = 0; f < 25; f++) {
            recorder.sampleFrame(controller);
        }
        assertEquals(0, recorder.toTimeline().edges().size());
        assertEquals(25, recorder.frames());
        assertEquals(-1, recorder.toTimeline().lastFrame());
    }

    @Test
    void edgesEmittedOnlyOnChange_heldButtonIsOneEdgePair() {
        Controller controller = new Controller();
        InputRecorder recorder = new InputRecorder();
        recorder.sampleFrame(controller);                 // frame 0: idle
        controller.setButton(0, Button.DOWN, true);
        recorder.sampleFrame(controller);                 // frame 1: press edge
        recorder.sampleFrame(controller);                 // frame 2: held — no edge
        recorder.sampleFrame(controller);                 // frame 3: held — no edge
        controller.setButton(0, Button.DOWN, false);
        recorder.sampleFrame(controller);                 // frame 4: release edge

        assertEquals(2, recorder.toTimeline().edges().size());
        InputTimeline.Edge press = recorder.toTimeline().edges().get(0);
        InputTimeline.Edge release = recorder.toTimeline().edges().get(1);
        assertEquals(1, press.frame());
        assertTrue(press.pressed());
        assertEquals(4, release.frame());
        assertEquals(Button.DOWN, release.button());
    }

    @Test
    void pressAlreadyDownAtFrameZero_isAFrameZeroEdge() {
        // D2: scripted and recorded timelines agree from frame 0 — a button
        // down before the first sampled boundary is an edge AT frame 0.
        Controller controller = new Controller();
        controller.setButton(0, Button.A, true);
        InputRecorder recorder = new InputRecorder();
        recorder.sampleFrame(controller);
        assertEquals(1, recorder.toTimeline().edges().size());
        assertEquals(0, recorder.toTimeline().edges().get(0).frame());
    }

    @Test
    void toMovie_wrapsRecordingWithIdentityPins_andReplaysIdentically() {
        InputTimeline script = InputTimeline.builder()
                .press(Button.START).atFrame(2).holdFrames(3)
                .build();
        InputRecorder recorder = recordScriptedRun(script, new Controller(), 8);
        Movie movie = recorder.toMovie("game.nes",
                "00000000000000000000000000000000000000000000000000000000000000ff");
        assertEquals(8, movie.frames(), "movie length = sampled frame count");
        assertEquals(script.edges(), movie.timeline().edges());
        // Serialize → parse round-trips the recording bit-identically.
        assertEquals(movie.timeline().edges(),
                MovieFormat.parse(MovieFormat.serialize(movie)).timeline().edges());
    }

    @Test
    void toMovie_beforeAnySample_throws() {
        assertThrows(IllegalStateException.class, () -> new InputRecorder()
                .toMovie("game.nes",
                        "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void sampleFrame_nullController_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputRecorder().sampleFrame(null));
    }
}
