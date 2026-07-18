package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.Controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Live-input recorder (headless-harness plan, Phase D2 — timeline tier).
 *
 * <p>Call {@link #sampleFrame(Controller)} once per frame, at the D2 frame
 * boundary — <em>immediately before</em> that frame's
 * {@code NesSystem.runFrame()} call (decision D9). Each sample reads both
 * controllers' live state via seam S4 ({@link Controller#isPressed}) and
 * emits {@link InputTimeline.Edge}s for every change since the previous
 * sample (initial state: all released). Because this is exactly the
 * boundary {@link InputTimelinePlayer} applies edges at, a recorded run
 * replays bit-identically, and recording a scripted run reproduces the
 * script's edge list verbatim — scripted and recorded timelines are the
 * same artifact.
 *
 * <p>Edges within a frame are emitted in (player, button-ordinal) order —
 * the same total order {@link InputTimelineBuilder} sorts into — so
 * recorded and built timelines compare equal edge-for-edge.
 *
 * <p>TeaVM-safe (timeline tier): a future web host can record with this
 * same class; no IO, no reflection, no wall clock.
 */
public final class InputRecorder {

    private static final int NUM_PLAYERS = 2;
    private static final Button[] BUTTONS = Button.values();

    /** State as of the previous sample — [player][buttonOrdinal]. */
    private final boolean[][] previous = new boolean[NUM_PLAYERS][BUTTONS.length];
    private final List<InputTimeline.Edge> edges = new ArrayList<InputTimeline.Edge>();
    /** Frames sampled so far; the next sample records at this frame index. */
    private int frames;

    /**
     * Sample both controllers' live state for the current frame boundary
     * (frame index = number of prior samples, matching the harness's
     * 0-based {@code frame()}), then advance the frame counter.
     */
    public void sampleFrame(Controller controller) {
        if (controller == null) {
            throw new IllegalArgumentException("controller must not be null");
        }
        for (int p = 0; p < NUM_PLAYERS; p++) {
            for (int b = 0; b < BUTTONS.length; b++) {
                boolean pressed = controller.isPressed(p, BUTTONS[b]);
                if (pressed != previous[p][b]) {
                    previous[p][b] = pressed;
                    edges.add(new InputTimeline.Edge(frames, p, BUTTONS[b], pressed));
                }
            }
        }
        frames++;
    }

    /** Number of frames sampled so far — the recording's length. */
    public int frames() {
        return frames;
    }

    /**
     * The recording so far as an immutable timeline (edges already in
     * builder order). Snapshot — later samples do not mutate it.
     */
    public InputTimeline toTimeline() {
        return new InputTimeline(new ArrayList<InputTimeline.Edge>(edges));
    }

    /**
     * Wrap the recording as a {@link Movie} whose length is the sampled
     * frame count. The sha-256 is computed by the caller (fixtures:
     * {@code TestRoms.sha256Hex}; desktop host: {@code MessageDigest}) —
     * the timeline tier stays IO- and crypto-free.
     *
     * @throws IllegalStateException if nothing has been sampled yet
     */
    public Movie toMovie(String romName, String romSha256) {
        if (frames == 0) {
            throw new IllegalStateException(
                    "nothing recorded yet — sampleFrame() at least once before toMovie()");
        }
        return new Movie(romName, romSha256, frames, toTimeline());
    }
}
