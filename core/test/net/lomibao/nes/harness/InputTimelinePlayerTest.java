package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.Controller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase A1 — {@link InputTimelinePlayer#applyUpTo(int, Controller)} applies
 * timeline edges to a live {@link Controller}, idempotently per frame (D2).
 *
 * <p>Controller state is observed through the hardware protocol itself
 * (strobe + 8 sequential reads of $4016/$4017) — there is no live-state
 * getter until seam S4 lands in Phase D.
 */
class InputTimelinePlayerTest {

    /** Strobe-latch then shift out all 8 buttons of {@code player} as a bitmask (bit i = Button ordinal i). */
    private static int latchAndReadMask(Controller c, int player) {
        c.cpuBusWrite(0x4016, (byte) 1);
        c.cpuBusWrite(0x4016, (byte) 0);
        int addr = player == 0 ? 0x4016 : 0x4017;
        int mask = 0;
        for (int i = 0; i < 8; i++) {
            mask |= (c.cpuBusRead(addr, false) & 0x01) << i;
        }
        return mask;
    }

    private static final int A_BIT = 1 << Button.A.ordinal();
    private static final int START_BIT = 1 << Button.START.ordinal();

    @Test
    void appliesPressAndReleaseAtTheirFrames() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(5).holdFrames(3) // down 5..7, up edge at 8
                .build();
        Controller c = new Controller();
        InputTimelinePlayer p = new InputTimelinePlayer(t);

        p.applyUpTo(4, c);
        assertEquals(0, latchAndReadMask(c, 0), "not yet pressed at frame 4");

        p.applyUpTo(5, c);
        assertEquals(A_BIT, latchAndReadMask(c, 0), "pressed from frame 5");

        p.applyUpTo(7, c);
        assertEquals(A_BIT, latchAndReadMask(c, 0), "still held at frame 7");

        p.applyUpTo(8, c);
        assertEquals(0, latchAndReadMask(c, 0), "released before frame 8's first tick");
    }

    @Test
    void applyIsIdempotentPerFrame() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.START).atFrame(2).holdFrames(2)
                .build();
        Controller c = new Controller();
        InputTimelinePlayer p = new InputTimelinePlayer(t);

        p.applyUpTo(2, c);
        assertEquals(START_BIT, latchAndReadMask(c, 0));

        // Simulate external interference, then re-apply the SAME frame:
        // an idempotent player must not re-fire the already-consumed edge.
        c.setButton(0, Button.START, false);
        p.applyUpTo(2, c);
        assertEquals(0, latchAndReadMask(c, 0), "edge for frame 2 must not re-apply");
    }

    @Test
    void earlierFrameAfterLaterIsNoOp() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(1).holdFrames(1) // up edge at 2
                .build();
        Controller c = new Controller();
        InputTimelinePlayer p = new InputTimelinePlayer(t);
        p.applyUpTo(10, c); // consumes both edges
        assertEquals(0, latchAndReadMask(c, 0));
        p.applyUpTo(1, c); // going backwards applies nothing
        assertEquals(0, latchAndReadMask(c, 0));
    }

    @Test
    void routesPlayerTwoEdgesToPlayerTwo() {
        InputTimeline t = InputTimeline.builder()
                .player(1).press(Button.A).atFrame(3).holdFrames(2)
                .build();
        Controller c = new Controller();
        InputTimelinePlayer p = new InputTimelinePlayer(t);
        p.applyUpTo(3, c);
        assertEquals(0, latchAndReadMask(c, 0), "player 0 untouched");
        assertEquals(A_BIT, latchAndReadMask(c, 1), "player 1 pressed");
    }

    @Test
    void resetReplaysFromFrameZero() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(0).holdFrames(1)
                .build();
        Controller c = new Controller();
        InputTimelinePlayer p = new InputTimelinePlayer(t);
        p.applyUpTo(5, c); // press + release consumed
        assertEquals(0, latchAndReadMask(c, 0));
        p.reset();
        p.applyUpTo(0, c);
        assertEquals(A_BIT, latchAndReadMask(c, 0), "after reset, frame-0 press re-applies");
    }
}
