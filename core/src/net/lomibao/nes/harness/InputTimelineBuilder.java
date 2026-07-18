package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Fluent builder for {@link InputTimeline} (headless-harness plan, Phase A1).
 *
 * <pre>
 * InputTimeline timeline = InputTimeline.builder()          // player 0 default
 *         .press(Button.START).atFrame(1650).holdFrames(20)
 *         .press(Button.START).atSeconds(31.6).holdFrames(20) // NTSC sugar, D3
 *         .player(1).press(Button.B).atFrame(100).holdFrames(2)
 *         .hold(Button.RIGHT).fromFrame(1950).toFrame(2100)   // inclusive range
 *         .build();                                           // immutable, sorted
 * </pre>
 *
 * <p>Semantics (D2): {@code press(b).atFrame(N).holdFrames(k)} holds the
 * button during frames {@code N .. N+k-1} — down when frame N's first tick
 * runs, released before frame {@code N+k}'s first tick.
 * {@code hold(b).fromFrame(f).toFrame(t)} holds during frames
 * {@code f .. t} inclusive (release edge at {@code t+1}).
 *
 * <p>Overlapping or adjacent intervals for the same {@code (player, button)}
 * merge into one press — a script never produces a spurious mid-hold
 * release. {@link #build()} may be called repeatedly; each call snapshots
 * the intervals added so far into a fresh immutable timeline.
 *
 * <p>TeaVM-safe (timeline tier): plain collections only.
 */
public final class InputTimelineBuilder {

    /** One requested hold: {@code [start, endExclusive)} for (player, button). */
    private static final class Interval {
        final int player;
        final Button button;
        final int start;
        final int endExclusive;

        Interval(int player, Button button, int start, int endExclusive) {
            this.player = player;
            this.button = button;
            this.start = start;
            this.endExclusive = endExclusive;
        }
    }

    private final List<Interval> intervals = new ArrayList<Interval>();
    private int currentPlayer = 0;

    InputTimelineBuilder() {
        // via InputTimeline.builder()
    }

    /**
     * Route subsequent {@link #press(Button)}/{@link #hold(Button)} calls to
     * {@code player} (0 or 1). Sticky until changed; the builder starts at 0.
     */
    public InputTimelineBuilder player(int player) {
        if (player != 0 && player != 1) {
            throw new IllegalArgumentException("player must be 0 or 1, got: " + player);
        }
        this.currentPlayer = player;
        return this;
    }

    /** Begin a press step: continue with {@code .atFrame(n)}/{@code .atSeconds(s)} then {@code .holdFrames(k)}. */
    public PressStep press(Button button) {
        requireButton(button);
        return new PressStep(button);
    }

    /** Begin an explicit-range hold: continue with {@code .fromFrame(f).toFrame(t)}. */
    public HoldStep hold(Button button) {
        requireButton(button);
        return new HoldStep(button);
    }

    /** Compile the intervals added so far into an immutable, sorted timeline. */
    public InputTimeline build() {
        // Merge overlapping/adjacent intervals per (player, button), then
        // emit a down edge at each merged start and an up edge at each end.
        List<InputTimeline.Edge> edges = new ArrayList<InputTimeline.Edge>();
        for (int player = 0; player < 2; player++) {
            for (Button button : Button.values()) {
                List<Interval> group = new ArrayList<Interval>();
                for (Interval iv : intervals) {
                    if (iv.player == player && iv.button == button) {
                        group.add(iv);
                    }
                }
                if (group.isEmpty()) {
                    continue;
                }
                Collections.sort(group, new Comparator<Interval>() {
                    @Override
                    public int compare(Interval a, Interval b) {
                        return Integer.compare(a.start, b.start);
                    }
                });
                int start = group.get(0).start;
                int end = group.get(0).endExclusive;
                for (int i = 1; i < group.size(); i++) {
                    Interval iv = group.get(i);
                    if (iv.start <= end) {
                        // overlapping or adjacent — extend the merged hold
                        if (iv.endExclusive > end) {
                            end = iv.endExclusive;
                        }
                    } else {
                        edges.add(new InputTimeline.Edge(start, player, button, true));
                        edges.add(new InputTimeline.Edge(end, player, button, false));
                        start = iv.start;
                        end = iv.endExclusive;
                    }
                }
                edges.add(new InputTimeline.Edge(start, player, button, true));
                edges.add(new InputTimeline.Edge(end, player, button, false));
            }
        }
        Collections.sort(edges, new Comparator<InputTimeline.Edge>() {
            @Override
            public int compare(InputTimeline.Edge a, InputTimeline.Edge b) {
                if (a.frame() != b.frame()) {
                    return Integer.compare(a.frame(), b.frame());
                }
                if (a.player() != b.player()) {
                    return Integer.compare(a.player(), b.player());
                }
                if (a.button() != b.button()) {
                    return Integer.compare(a.button().ordinal(), b.button().ordinal());
                }
                // releases before presses on the same frame (never happens
                // for the same player+button after merging, but keep the
                // order total and deterministic)
                return Boolean.compare(a.pressed(), b.pressed());
            }
        });
        return new InputTimeline(edges);
    }

    private static void requireButton(Button button) {
        if (button == null) {
            throw new IllegalArgumentException("button must not be null");
        }
    }

    private static int requireFrame(int frame) {
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be >= 0, got: " + frame);
        }
        return frame;
    }

    // -------------------------------------------------------------------------
    // Fluent steps
    // -------------------------------------------------------------------------

    /** Step after {@code press(button)}: choose the start frame. */
    public final class PressStep {
        private final Button button;
        private final int player = currentPlayer;

        private PressStep(Button button) {
            this.button = button;
        }

        /** Press starting at frame {@code frame} (D2: down before that frame's first tick). */
        public PressAt atFrame(int frame) {
            return new PressAt(player, button, requireFrame(frame));
        }

        /** Press starting at {@code TimeBase.framesAt(seconds)} (NTSC sugar, D3). */
        public PressAt atSeconds(double seconds) {
            return new PressAt(player, button, TimeBase.framesAt(seconds));
        }
    }

    /** Step after {@code atFrame/atSeconds}: choose the hold length. */
    public final class PressAt {
        private final int player;
        private final Button button;
        private final int startFrame;

        private PressAt(int player, Button button, int startFrame) {
            this.player = player;
            this.button = button;
            this.startFrame = startFrame;
        }

        /**
         * Hold for {@code frames} frames: down during
         * {@code startFrame .. startFrame+frames-1}, released before frame
         * {@code startFrame+frames}.
         */
        public InputTimelineBuilder holdFrames(int frames) {
            if (frames < 1) {
                throw new IllegalArgumentException("holdFrames must be >= 1, got: " + frames);
            }
            intervals.add(new Interval(player, button, startFrame, startFrame + frames));
            return InputTimelineBuilder.this;
        }
    }

    /** Step after {@code hold(button)}: choose the start frame. */
    public final class HoldStep {
        private final Button button;
        private final int player = currentPlayer;

        private HoldStep(Button button) {
            this.button = button;
        }

        /** Hold starting at frame {@code frame} (inclusive). */
        public HoldFrom fromFrame(int frame) {
            return new HoldFrom(player, button, requireFrame(frame));
        }
    }

    /** Step after {@code fromFrame}: choose the (inclusive) end frame. */
    public final class HoldFrom {
        private final int player;
        private final Button button;
        private final int fromFrame;

        private HoldFrom(int player, Button button, int fromFrame) {
            this.player = player;
            this.button = button;
            this.fromFrame = fromFrame;
        }

        /**
         * Hold through frame {@code frame} inclusive (release edge at
         * {@code frame + 1}). Must be {@code >= fromFrame}.
         */
        public InputTimelineBuilder toFrame(int frame) {
            if (frame < fromFrame) {
                throw new IllegalArgumentException(
                        "toFrame (" + frame + ") must be >= fromFrame (" + fromFrame + ")");
            }
            intervals.add(new Interval(player, button, fromFrame, frame + 1));
            return InputTimelineBuilder.this;
        }
    }
}
