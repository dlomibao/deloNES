package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;

import java.util.Collections;
import java.util.List;

/**
 * Immutable, frame-indexed controller-input script — the timeline tier's
 * central artifact (headless-harness plan, Phase A1).
 *
 * <p>A timeline is a sorted list of {@link Edge}s: {@code (frame, player,
 * button, pressed)} state changes. Semantics are pinned by decision D2: an
 * edge at frame {@code N} means the button state change is already in effect
 * when frame {@code N}'s first master tick runs — a game strobing $4016
 * during frame {@code N} latches it. Build instances with the fluent
 * {@link #builder()} DSL; apply them with {@link InputTimelinePlayer}. The
 * Phase-D recorder emits the same edge representation, so scripted and
 * recorded timelines are the same artifact.
 *
 * <p>TeaVM-safe (timeline tier): plain collections only — no reflection,
 * regex, or {@code String.format}.
 */
public final class InputTimeline {

    /**
     * One button state change: at frame {@link #frame()}, {@link #player()}'s
     * {@link #button()} becomes {@link #pressed()} (in effect before that
     * frame's first tick — D2).
     */
    public static final class Edge {
        private final int frame;
        private final int player;
        private final Button button;
        private final boolean pressed;

        Edge(int frame, int player, Button button, boolean pressed) {
            this.frame = frame;
            this.player = player;
            this.button = button;
            this.pressed = pressed;
        }

        /** Frame index (0-based, D2 semantics) at which this change applies. */
        public int frame() {
            return frame;
        }

        /** Player index: 0 (player 1) or 1 (player 2). */
        public int player() {
            return player;
        }

        /** The button changing state. */
        public Button button() {
            return button;
        }

        /** {@code true} = pressed from this frame on; {@code false} = released. */
        public boolean pressed() {
            return pressed;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Edge)) {
                return false;
            }
            Edge other = (Edge) o;
            return frame == other.frame
                    && player == other.player
                    && button == other.button
                    && pressed == other.pressed;
        }

        @Override
        public int hashCode() {
            int h = frame;
            h = 31 * h + player;
            h = 31 * h + button.ordinal();
            h = 31 * h + (pressed ? 1 : 0);
            return h;
        }

        @Override
        public String toString() {
            return "Edge{frame=" + frame + ", player=" + player
                    + ", button=" + button + ", pressed=" + pressed + "}";
        }
    }

    private final List<Edge> edges;

    /** Package-private: built by {@link InputTimelineBuilder} with an already-sorted list. */
    InputTimeline(List<Edge> sortedEdges) {
        this.edges = Collections.unmodifiableList(sortedEdges);
    }

    /** Start a fluent builder ({@code player 0} routing by default). */
    public static InputTimelineBuilder builder() {
        return new InputTimelineBuilder();
    }

    /**
     * All edges, sorted by frame (ties: player, then button order, releases
     * before presses). Unmodifiable.
     */
    public List<Edge> edges() {
        return edges;
    }

    /** Frame of the last edge, or -1 for an empty timeline. */
    public int lastFrame() {
        return edges.isEmpty() ? -1 : edges.get(edges.size() - 1).frame();
    }
}
