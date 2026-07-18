package net.lomibao.nes.harness;

import net.lomibao.nes.components.Controller;

import java.util.List;

/**
 * Applies an {@link InputTimeline}'s edges to a live {@link Controller} at
 * frame boundaries (headless-harness plan, Phase A1; semantics D2).
 *
 * <p>Call {@link #applyUpTo(int, Controller)} with the current frame number
 * <em>before</em> that frame's first master tick: every not-yet-applied edge
 * whose frame is {@code <= frame} is applied via
 * {@link Controller#setButton(int, net.lomibao.nes.components.Button, boolean)}.
 * A monotonic cursor makes the call idempotent per frame — re-invoking with
 * the same (or an earlier) frame applies nothing. {@link #reset()} rewinds
 * to frame 0 for replay from a fresh boot.
 *
 * <p>TeaVM-safe (timeline tier).
 */
public final class InputTimelinePlayer {

    private final List<InputTimeline.Edge> edges;
    private int cursor;

    public InputTimelinePlayer(InputTimeline timeline) {
        if (timeline == null) {
            throw new IllegalArgumentException("timeline must not be null");
        }
        this.edges = timeline.edges();
    }

    /**
     * Apply all pending edges with {@code edge.frame() <= frame} to
     * {@code controller}. Idempotent: each edge is applied exactly once per
     * player lifetime (until {@link #reset()}).
     */
    public void applyUpTo(int frame, Controller controller) {
        while (cursor < edges.size() && edges.get(cursor).frame() <= frame) {
            InputTimeline.Edge e = edges.get(cursor);
            controller.setButton(e.player(), e.button(), e.pressed());
            cursor++;
        }
    }

    /** True once every edge has been applied. */
    public boolean finished() {
        return cursor >= edges.size();
    }

    /** Rewind to the start of the timeline (for replay from a fresh boot). */
    public void reset() {
        cursor = 0;
    }
}
