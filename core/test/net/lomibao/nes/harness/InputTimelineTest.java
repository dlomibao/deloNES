package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A1 — fluent builder, edge compilation, overlap merge, immutability
 * (docs/headless-harness-plan.md, D2/D3 semantics).
 */
class InputTimelineTest {

    private static void assertEdge(InputTimeline.Edge e,
                                   int frame, int player, Button button, boolean pressed) {
        assertEquals(frame, e.frame(), "frame");
        assertEquals(player, e.player(), "player");
        assertEquals(button, e.button(), "button");
        assertEquals(pressed, e.pressed(), "pressed");
    }

    @Test
    void pressHoldCompilesToDownAndUpEdges() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.START).atFrame(1650).holdFrames(20)
                .build();
        List<InputTimeline.Edge> edges = t.edges();
        assertEquals(2, edges.size());
        assertEdge(edges.get(0), 1650, 0, Button.START, true);
        assertEdge(edges.get(1), 1670, 0, Button.START, false); // released before frame N+k
    }

    @Test
    void edgesSortedByFrameRegardlessOfInsertionOrder() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(500).holdFrames(2)
                .press(Button.B).atFrame(10).holdFrames(2)
                .build();
        List<InputTimeline.Edge> edges = t.edges();
        assertEquals(4, edges.size());
        for (int i = 1; i < edges.size(); i++) {
            assertTrue(edges.get(i - 1).frame() <= edges.get(i).frame(),
                    "edges must be sorted by frame");
        }
        assertEdge(edges.get(0), 10, 0, Button.B, true);
        assertEdge(edges.get(3), 502, 0, Button.A, false);
    }

    @Test
    void holdRangeIsInclusiveBothEnds() {
        InputTimeline t = InputTimeline.builder()
                .hold(Button.RIGHT).fromFrame(1950).toFrame(2100)
                .build();
        List<InputTimeline.Edge> edges = t.edges();
        assertEquals(2, edges.size());
        assertEdge(edges.get(0), 1950, 0, Button.RIGHT, true);
        assertEdge(edges.get(1), 2101, 0, Button.RIGHT, false); // held through 2100
    }

    @Test
    void overlappingPressesOfSameButtonMerge() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(10).holdFrames(10)  // 10..19
                .press(Button.A).atFrame(15).holdFrames(10)  // 15..24
                .build();
        List<InputTimeline.Edge> edges = t.edges();
        assertEquals(2, edges.size(), "overlap must merge into one down/up pair");
        assertEdge(edges.get(0), 10, 0, Button.A, true);
        assertEdge(edges.get(1), 25, 0, Button.A, false);
    }

    @Test
    void adjacentPressesOfSameButtonMerge() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(10).holdFrames(5)   // 10..14
                .press(Button.A).atFrame(15).holdFrames(5)   // 15..19
                .build();
        List<InputTimeline.Edge> edges = t.edges();
        assertEquals(2, edges.size(), "adjacent intervals must merge (no 1-frame release)");
        assertEdge(edges.get(0), 10, 0, Button.A, true);
        assertEdge(edges.get(1), 20, 0, Button.A, false);
    }

    @Test
    void distinctButtonsDoNotMerge() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(10).holdFrames(10)
                .press(Button.B).atFrame(15).holdFrames(10)
                .build();
        assertEquals(4, t.edges().size());
    }

    @Test
    void playerRoutingStickyUntilChanged() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(10).holdFrames(2)          // player 0 default
                .player(1)
                .press(Button.B).atFrame(100).holdFrames(2)
                .press(Button.SELECT).atFrame(200).holdFrames(2)    // still player 1
                .build();
        List<InputTimeline.Edge> edges = t.edges();
        assertEquals(0, edges.get(0).player());
        assertEquals(1, edges.get(2).player());
        assertEquals(1, edges.get(4).player());
        // same button on different players must NOT merge
        InputTimeline t2 = InputTimeline.builder()
                .press(Button.A).atFrame(10).holdFrames(10)
                .player(1).press(Button.A).atFrame(15).holdFrames(10)
                .build();
        assertEquals(4, t2.edges().size());
    }

    @Test
    void atSecondsUsesNtscRounding() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.START).atSeconds(31.6).holdFrames(20)
                .build();
        assertEquals(1899, t.edges().get(0).frame()); // TimeBase.framesAt(31.6)
        assertEquals(1919, t.edges().get(1).frame());
    }

    @Test
    void edgesListIsUnmodifiable() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(1).holdFrames(1)
                .build();
        List<InputTimeline.Edge> edges = t.edges();
        assertThrows(UnsupportedOperationException.class, () -> edges.remove(0));
        assertThrows(UnsupportedOperationException.class,
                () -> edges.add(edges.get(0)));
    }

    @Test
    void builderReuseAfterBuildDoesNotMutateEarlierTimeline() {
        InputTimelineBuilder b = InputTimeline.builder()
                .press(Button.A).atFrame(1).holdFrames(1);
        InputTimeline first = b.build();
        b.press(Button.B).atFrame(2).holdFrames(1);
        InputTimeline second = b.build();
        assertEquals(2, first.edges().size(), "earlier build must be immutable");
        assertEquals(4, second.edges().size());
        assertFalse(first.edges().equals(second.edges()));
    }

    @Test
    void validationRejectsBadArguments() {
        InputTimelineBuilder b = InputTimeline.builder();
        assertThrows(IllegalArgumentException.class,
                () -> b.press(Button.A).atFrame(-1));
        assertThrows(IllegalArgumentException.class,
                () -> b.press(Button.A).atFrame(0).holdFrames(0));
        assertThrows(IllegalArgumentException.class,
                () -> b.press(Button.A).atSeconds(-1.0));
        assertThrows(IllegalArgumentException.class,
                () -> b.hold(Button.A).fromFrame(10).toFrame(9));
        assertThrows(IllegalArgumentException.class, () -> b.player(2));
        assertThrows(IllegalArgumentException.class, () -> b.player(-1));
    }

    @Test
    void emptyTimelineBuilds() {
        assertTrue(InputTimeline.builder().build().edges().isEmpty());
    }
}
