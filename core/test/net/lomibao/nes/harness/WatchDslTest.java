package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.CPUBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B2 — watch engine + DSL (headless-harness plan, revision 2).
 * Pins the watch semantics from the plan's API-sketch section: two trigger
 * tiers, RAM-only value tracking, mirror canonicalization, unsigned
 * normalization, AssertionError propagation, gated input, removal.
 */
class WatchDslTest {

    private static NesHarness harness() {
        return NesHarnessTestRoms.controllerPollHarness();
    }

    /** Direct CPU-bus write — exercises the S1 listener path synchronously. */
    private static void poke(NesHarness h, int addr, int value) {
        h.nes().getCpuBus().write(addr, (byte) value);
    }

    // -------------------------------------------------------------------------
    // onWrite
    // -------------------------------------------------------------------------

    @Test
    void onWrite_singleAddress_firesWithAddrValueAndPc() {
        NesHarness h = harness();
        h.cpu().setPc(0xC0DE);
        List<Watch.Write> seen = new ArrayList<>();
        h.watch(0x0080).onWrite(seen::add);

        poke(h, 0x0080, 0x42);
        poke(h, 0x0081, 0x43); // outside the watch

        assertEquals(1, seen.size());
        Watch.Write w = seen.get(0);
        assertEquals(0x0080, w.addr());
        assertEquals(0x0080, w.busAddr());
        assertEquals(0x42, w.newValue());
        assertEquals(0xC0DE, w.pc());
        assertEquals(0, w.frame());
    }

    @Test
    void onWrite_rangeWatch_firesInsideBoundsOnly() {
        NesHarness h = harness();
        List<Watch.Write> seen = new ArrayList<>();
        h.watchRange(0x0700, 0x070F).onWrite(seen::add);

        poke(h, 0x06FF, 1); // below
        poke(h, 0x0700, 2); // lo bound
        poke(h, 0x070F, 3); // hi bound
        poke(h, 0x0710, 4); // above

        assertEquals(2, seen.size());
        assertEquals(0x0700, seen.get(0).addr());
        assertEquals(0x070F, seen.get(1).addr());
    }

    @Test
    void write_normalizesBytePayloadToUnsignedInt() {
        NesHarness h = harness();
        List<Watch.Write> seen = new ArrayList<>();
        h.watch(0x0090).onWrite(seen::add);

        poke(h, 0x0090, 0xEE); // negative as a Java byte

        assertEquals(0xEE, seen.get(0).newValue(), "unsigned 0-255, so == 0xEE reads naturally");
        assertEquals("$ee", seen.get(0).newHex().toLowerCase());
    }

    @Test
    void onWrite_nonRam_hasOldValueMinusOneSentinel() {
        NesHarness h = harness();
        List<Watch.Write> seen = new ArrayList<>();
        h.watch(0x2000).onWrite(seen::add);

        poke(h, 0x2000, 0x80);

        assertEquals(1, seen.size());
        assertEquals(-1, seen.get(0).oldValue());
    }

    @Test
    void onWrite_ram_reportsOldAndNewValues() {
        NesHarness h = harness();
        poke(h, 0x00A0, 0x11);
        List<Watch.Write> seen = new ArrayList<>();
        h.watch(0x00A0).onWrite(seen::add);

        poke(h, 0x00A0, 0x22);

        assertEquals(0x11, seen.get(0).oldValue());
        assertEquals(0x22, seen.get(0).newValue());
    }

    // -------------------------------------------------------------------------
    // Value-tracking triggers (RAM only)
    // -------------------------------------------------------------------------

    @Test
    void onChange_firesOnlyWhenValueActuallyChanges() {
        NesHarness h = harness();
        AtomicInteger fires = new AtomicInteger();
        h.watch(0x00B0).onChange(w -> fires.incrementAndGet());

        poke(h, 0x00B0, 0x00); // 0 -> 0: no change
        assertEquals(0, fires.get());
        poke(h, 0x00B0, 0x05); // change
        poke(h, 0x00B0, 0x05); // same value rewritten
        poke(h, 0x00B0, 0x06); // change

        assertEquals(2, fires.get());
    }

    @Test
    void whenBecomes_isEdgeTriggered_notLevel() {
        NesHarness h = harness();
        AtomicInteger fires = new AtomicInteger();
        h.watch(0x00B1).whenBecomes(0x01).then(ctx -> fires.incrementAndGet());

        poke(h, 0x00B1, 0x01); // 0 -> 1: edge
        poke(h, 0x00B1, 0x01); // 1 -> 1: level, no fire
        poke(h, 0x00B1, 0x00); // 1 -> 0
        poke(h, 0x00B1, 0x01); // 0 -> 1: edge again

        assertEquals(2, fires.get());
    }

    @Test
    void crossesAbove_firesOnUpwardThresholdCrossingOnly() {
        NesHarness h = harness();
        AtomicInteger fires = new AtomicInteger();
        h.watch(0x00B2).crossesAbove(5).then(ctx -> fires.incrementAndGet());

        poke(h, 0x00B2, 3);  // 0 -> 3: still below
        poke(h, 0x00B2, 6);  // 3 -> 6: crosses above
        poke(h, 0x00B2, 7);  // 6 -> 7: already above, no fire
        poke(h, 0x00B2, 5);  // back to threshold (not above)
        poke(h, 0x00B2, 9);  // 5 -> 9: crosses above again

        assertEquals(2, fires.get());
    }

    @Test
    void crossesBelow_firesOnDownwardThresholdCrossingOnly() {
        NesHarness h = harness();
        AtomicInteger fires = new AtomicInteger();
        h.watch(0x00B3).crossesBelow(5).then(ctx -> fires.incrementAndGet());

        poke(h, 0x00B3, 9);  // 0 -> 9: upward, no fire (starts at 0 which is < 5, but upward)
        poke(h, 0x00B3, 4);  // 9 -> 4: crosses below
        poke(h, 0x00B3, 3);  // already below
        poke(h, 0x00B3, 5);  // back at threshold
        poke(h, 0x00B3, 0);  // 5 -> 0: crosses below again

        assertEquals(2, fires.get());
    }

    @Test
    void valueTrackingOutsideRam_throwsIaeAtRegistration() {
        NesHarness h = harness();
        assertThrows(IllegalArgumentException.class, () -> h.watch(0x2001).onChange(w -> { }));
        assertThrows(IllegalArgumentException.class, () -> h.watch(0x8000).whenBecomes(1));
        assertThrows(IllegalArgumentException.class, () -> h.watch(0x4014).crossesAbove(1));
        assertThrows(IllegalArgumentException.class, () -> h.watch(0x4016).crossesBelow(1));
    }

    @Test
    void valueTrackingRangePartiallyOutsideRam_throwsIae() {
        NesHarness h = harness();
        assertThrows(IllegalArgumentException.class,
                () -> h.watchRange(0x1FF0, 0x2005).onChange(w -> { }));
    }

    // -------------------------------------------------------------------------
    // Mirror canonicalization
    // -------------------------------------------------------------------------

    @Test
    void ramMirrorWrite_firesCanonicalWatch() {
        NesHarness h = harness();
        poke(h, 0x0080, 0x33);
        List<Watch.Write> seen = new ArrayList<>();
        h.watch(0x0080).onWrite(seen::add);

        poke(h, 0x0880, 0x44); // $0880 mirrors $0080

        assertEquals(1, seen.size());
        assertEquals(0x0080, seen.get(0).addr(), "addr() is canonical");
        assertEquals(0x0880, seen.get(0).busAddr(), "busAddr() is the CPU-visible one");
        assertEquals(0x33, seen.get(0).oldValue(), "old value read through the mirror");
    }

    @Test
    void mirrorRegisteredWatch_firesForCanonicalWrite() {
        NesHarness h = harness();
        AtomicInteger fires = new AtomicInteger();
        h.watch(0x1880).onWrite(w -> fires.incrementAndGet()); // canonicalizes to $0080

        poke(h, 0x0080, 0x01);

        assertEquals(1, fires.get(), "watch registered via a mirror matches the canonical cell");
    }

    @Test
    void ppuRegisterMirror_canonicalizesTo2000Plus7() {
        NesHarness h = harness();
        List<Watch.Write> seen = new ArrayList<>();
        h.watch(0x2000).onWrite(seen::add);

        poke(h, 0x2008, 0x90); // $2008 mirrors $2000

        assertEquals(1, seen.size());
        assertEquals(0x2000, seen.get(0).addr());
        assertEquals(0x2008, seen.get(0).busAddr());
        assertEquals(-1, seen.get(0).oldValue());
    }

    // -------------------------------------------------------------------------
    // Failure propagation, gated input, removal
    // -------------------------------------------------------------------------

    @Test
    void callbackAssertionError_propagatesOutOfRunFrames() {
        NesHarness h = harness();
        // The poll ROM stores to $0300-$0307 every loop iteration.
        h.watch(0x0300).onWrite(w -> {
            throw new AssertionError("corruption at " + w.addrHex()
                    + " frame " + w.frame() + " pc " + w.pcHex());
        });

        AssertionError e = assertThrows(AssertionError.class, () -> h.runFrames(2));
        assertTrue(e.getMessage().contains("$0300"), e.getMessage());
    }

    @Test
    void gatedInput_landsAtNextFrameBoundary() {
        NesHarness h = harness();
        // Press A frames 2-9 via timeline; when the ROM stores A=1 to $0300,
        // gate a START press. The watch fires mid-frame 2; the press must be
        // live during frame 3 (next boundary), held for 2 frames (3-4).
        h.play(InputTimeline.builder()
                .press(Button.A).atFrame(2).holdFrames(8)
                .build());
        h.watch(0x0300).whenBecomes(1).then(ctx -> ctx.press(Button.START, 2));

        h.runToFrame(2);
        assertEquals(0, h.peek(0x0303), "START idle before the gate fires");
        h.runToFrame(4); // frames 2 (gate fires) and 3 (press live) completed
        assertEquals(1, h.peek(0x0303), "gated START visible during frame 3");
        h.runToFrame(7); // released before frame 5's first tick
        assertEquals(0, h.peek(0x0303), "gated press released after holdFrames");
        assertEquals(1, h.peek(0x0300), "timeline press still held");
    }

    @Test
    void removedWatch_stopsFiring_andListenerDetachesWhenLastRemoved() {
        NesHarness h = harness();
        AtomicInteger fires = new AtomicInteger();
        Watch w = h.watch(0x00C0).onWrite(x -> fires.incrementAndGet());

        poke(h, 0x00C0, 1);
        assertEquals(1, fires.get());

        w.remove();
        poke(h, 0x00C0, 2);
        assertEquals(1, fires.get(), "removed watch no longer fires");

        CPUBus bus = h.nes().getCpuBus();
        assertEquals(null, bus.getWriteListener(),
                "S1 fast path restored once the last watch is gone");
    }

    @Test
    void multipleWatchesOnSameAddress_allFire() {
        NesHarness h = harness();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        h.watch(0x00D0).onWrite(x -> a.incrementAndGet());
        h.watchRange(0x00C0, 0x00DF).onWrite(x -> b.incrementAndGet());

        poke(h, 0x00D0, 1);

        assertEquals(1, a.get());
        assertEquals(1, b.get());
    }

    @Test
    void watchFrameAttribution_matchesHarnessFrame() {
        NesHarness h = harness();
        List<Integer> frames = new ArrayList<>();
        Watch w = h.watch(0x0300).onWrite(x -> {
            if (frames.isEmpty() || frames.get(frames.size() - 1) != x.frame()) {
                frames.add(x.frame());
            }
        });
        h.runFrames(3);
        w.remove();
        assertTrue(frames.contains(0) && frames.contains(1) && frames.contains(2),
                "writes attributed to frames 0,1,2 — got " + frames);
    }

    /**
     * Round-2 review: a RAM-to-PPU range with a coincidentally equal
     * canonical span must still be rejected — regions canonicalize with
     * different offsets, so the bounds check would silently drop
     * addresses inside the request (e.g. $2006/$2007 for $1000-$3005).
     */
    @org.junit.jupiter.api.Test
    void watchRange_crossRegion_equalSpan_throws() {
        NesHarness h = harness();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> h.watchRange(0x1000, 0x3005).onWrite(w -> { }));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> h.watchRange(0x0800, 0x2801).onWrite(w -> { }));
    }
}