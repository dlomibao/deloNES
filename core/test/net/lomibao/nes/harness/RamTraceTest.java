package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B3 — {@link RamTrace}: {@code h.trace(lo, hi, runnable)} collects
 * {frame, pc, addr, old, new} for every RAM write in the window, replacing
 * the DIAG_* static hack ("who writes $03B4").
 */
class RamTraceTest {

    private static NesHarness harness() {
        return NesHarnessTestRoms.controllerPollHarness();
    }

    private static void poke(NesHarness h, int addr, int value) {
        h.nes().getCpuBus().write(addr, (byte) value);
    }

    @Test
    void trace_collectsOnlyWritesInsideTheInclusiveWindow() {
        NesHarness h = harness();
        List<RamTrace.Write> ws = h.trace(0x0600, 0x060F, () -> {
            poke(h, 0x05FF, 1); // below
            poke(h, 0x0600, 2); // lo bound
            poke(h, 0x0608, 3);
            poke(h, 0x060F, 4); // hi bound
            poke(h, 0x0610, 5); // above
        });

        assertEquals(3, ws.size());
        assertEquals(0x0600, ws.get(0).addr());
        assertEquals(0x0608, ws.get(1).addr());
        assertEquals(0x060F, ws.get(2).addr());
        assertEquals(2, ws.get(0).newValue());
    }

    @Test
    void trace_recordsOldAndNewValuesAndPc() {
        NesHarness h = harness();
        poke(h, 0x0605, 0x11);
        h.cpu().setPc(0xBEEF);
        List<RamTrace.Write> ws = h.trace(0x0605, 0x0605, () -> poke(h, 0x0605, 0x22));

        assertEquals(1, ws.size());
        assertEquals(0x11, ws.get(0).oldValue());
        assertEquals(0x22, ws.get(0).newValue());
        assertEquals(0xBEEF, ws.get(0).pc());
    }

    @Test
    void trace_attributesWritesToTheFrameTheyOccurredIn() {
        NesHarness h = harness();
        h.runFrames(2); // start at frame 2
        // The poll ROM stores $0300-$0307 continuously every frame.
        List<RamTrace.Write> ws = h.trace(0x0300, 0x0307, () -> h.runFrames(2));

        assertTrue(!ws.isEmpty(), "poll ROM writes the window every frame");
        boolean sawFrame2 = false;
        boolean sawFrame3 = false;
        for (RamTrace.Write w : ws) {
            assertTrue(w.frame() == 2 || w.frame() == 3,
                    "writes only during frames 2-3, got frame " + w.frame());
            sawFrame2 |= w.frame() == 2;
            sawFrame3 |= w.frame() == 3;
        }
        assertTrue(sawFrame2 && sawFrame3, "both traced frames attributed");
    }

    @Test
    void trace_stopsCollectingAfterTheBodyReturns() {
        NesHarness h = harness();
        List<RamTrace.Write> ws = h.trace(0x0620, 0x0620, () -> poke(h, 0x0620, 1));
        poke(h, 0x0620, 2); // after the trace window closed

        assertEquals(1, ws.size(), "writes after the body are not collected");
        assertEquals(null, h.nes().getCpuBus().getWriteListener(),
                "trace watch removed — S1 fast path restored");
    }

    @Test
    void trace_outsideRam_throwsIae() {
        NesHarness h = harness();
        assertThrows(IllegalArgumentException.class,
                () -> h.trace(0x2000, 0x2007, () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> h.trace(0x0700, 0x8000, () -> { }));
    }

    @Test
    void format_reproducesWhoWritesDump() {
        NesHarness h = harness();
        poke(h, 0x03B4, 0x00);
        h.cpu().setPc(0xC123);
        List<RamTrace.Write> ws = h.trace(0x03B4, 0x03B4, () -> poke(h, 0x03B4, 0xEE));

        String dump = RamTrace.format(ws);
        assertTrue(dump.contains("$03B4"), dump);
        assertTrue(dump.contains("$C123"), dump);
        assertTrue(dump.contains("$00"), dump);
        assertTrue(dump.contains("$EE"), dump);
        assertTrue(dump.contains("frame=0"), dump);
    }
}
