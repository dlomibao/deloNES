package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B4 — {@link OamCensus} / {@link NametableCensus} + seam S2
 * ({@code PPU.peekPpuBus}, public read-only PPU-bus read).
 *
 * <p>OAM-DMA caveat (plan, pinned watch semantics): DMA bursts write OAM
 * via {@code ppu.writeOam} directly, bypassing {@code CPUBus.write} — so
 * write watches never see them; {@code OamCensus} is the sanctioned view
 * of OAM contents.
 */
class CensusTest {

    private static NesHarness harness() {
        return NesHarnessTestRoms.controllerPollHarness();
    }

    /** Poke a 4-byte sprite entry straight into OAM. */
    private static void sprite(NesHarness h, int slot, int y, int tile, int attr, int x) {
        h.ppu().writeOam(slot * 4, (byte) y);
        h.ppu().writeOam(slot * 4 + 1, (byte) tile);
        h.ppu().writeOam(slot * 4 + 2, (byte) attr);
        h.ppu().writeOam(slot * 4 + 3, (byte) x);
    }

    @Test
    void oamCensus_countsLiveSprites_onSyntheticOam() {
        NesHarness h = harness();
        // Power-on OAM is all zeros → y=0 everywhere → 64 "live" sprites;
        // park everything off-screen first, the Temp* diag convention.
        for (int i = 0; i < 64; i++) {
            sprite(h, i, 0xFF, 0, 0, 0);
        }
        sprite(h, 0, 0x30, 0x32, 0x01, 0x78);
        sprite(h, 5, 0x50, 0x40, 0x00, 0x20);
        sprite(h, 63, 0x77, 0x10, 0x02, 0xF0);

        OamCensus census = h.oamCensus();
        assertEquals(3, census.liveSprites());
        assertEquals(0x30, census.y(0));
        assertEquals(0x32, census.tile(0));
        assertEquals(0x01, census.attr(0));
        assertEquals(0x78, census.x(0));
    }

    @Test
    void oamCensus_excludesSpritesAtOrBelow0xEF() {
        NesHarness h = harness();
        for (int i = 0; i < 64; i++) {
            sprite(h, i, 0xFF, 0, 0, 0);
        }
        sprite(h, 0, 0xEE, 1, 0, 0); // last on-screen y
        sprite(h, 1, 0xEF, 2, 0, 0); // first excluded y
        sprite(h, 2, 0xF8, 3, 0, 0); // excluded

        assertEquals(1, h.oamCensus().liveSprites(), "only y < 0xEF counts");
    }

    @Test
    void oamCensus_assertSpriteAt_passAndFail() {
        NesHarness h = harness();
        for (int i = 0; i < 64; i++) {
            sprite(h, i, 0xFF, 0, 0, 0);
        }
        sprite(h, 3, 0x40, 0x32, 0, 120);

        h.oamCensus().assertSpriteAt(0x32, 100, 140); // in range — passes

        AssertionError e = assertThrows(AssertionError.class,
                () -> h.oamCensus().assertSpriteAt(0x32, 0, 50));
        assertTrue(e.getMessage().contains("0x32") || e.getMessage().contains("$32"),
                e.getMessage());
        assertThrows(AssertionError.class,
                () -> h.oamCensus().assertSpriteAt(0x99, 0, 255), "no such tile");
    }

    @Test
    void shadowOamCensus_readsDmaSourcePageViaPeek() {
        NesHarness h = harness();
        // Populate the classic $0200 shadow page through the CPU bus.
        for (int i = 0; i < 256; i++) {
            h.nes().getCpuBus().write(0x0200 + i, (byte) 0xFF); // park all
        }
        int base = 0x0200 + 4 * 4; // sprite slot 4
        h.nes().getCpuBus().write(base, (byte) 0x30);
        h.nes().getCpuBus().write(base + 1, (byte) 0x55);
        h.nes().getCpuBus().write(base + 2, (byte) 0x00);
        h.nes().getCpuBus().write(base + 3, (byte) 0x64);

        OamCensus shadow = h.shadowOamCensus(0x02);
        assertEquals(1, shadow.liveSprites());
        assertEquals(0x55, shadow.tile(4));
        shadow.assertSpriteAt(0x55, 0x60, 0x68);
    }

    @Test
    void nametableCensus_tileAt_readsBackPpuDataWrites() {
        NesHarness h = harness();
        // Write tile id $42 to nametable 0, row 2, col 1 ($2041) via $2006/$2007.
        h.nes().getCpuBus().write(0x2006, (byte) 0x20);
        h.nes().getCpuBus().write(0x2006, (byte) 0x41);
        h.nes().getCpuBus().write(0x2007, (byte) 0x42);

        assertEquals(0x42, h.nametable().tileAt(0, 1, 2));
        assertEquals(0x00, h.nametable().tileAt(0, 0, 0), "untouched cell reads 0");
    }

    @Test
    void nametableCensus_boundsChecked() {
        NesHarness h = harness();
        assertThrows(IllegalArgumentException.class, () -> h.nametable().tileAt(4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> h.nametable().tileAt(0, 32, 0));
        assertThrows(IllegalArgumentException.class, () -> h.nametable().tileAt(0, 0, 30));
    }

    /**
     * S2 readOnly-ness gate: a full per-frame nametable + OAM census must
     * not perturb emulation — bit-identical framebuffer vs an unobserved
     * run (peekPpuBus skips the buffered-read machinery, v increments, and
     * A12 mapper notification).
     */
    @Test
    void observingCensusesEveryFrame_leavesFramebufferBitIdentical() {
        NesHarness plain = NesHarness.fromResource("/nestest.nes");
        NesHarness observed = NesHarness.fromResource("/nestest.nes");

        for (int f = 0; f < 30; f++) {
            observed.oamCensus();
            observed.shadowOamCensus(0x02);
            for (int table = 0; table < 4; table++) {
                for (int row = 0; row < 30; row++) {
                    for (int col = 0; col < 32; col++) {
                        observed.nametable().tileAt(table, col, row);
                    }
                }
            }
            plain.runFrames(1);
            observed.runFrames(1);
        }

        assertArrayEquals(plain.ppu().getVisibleScreenPixels1D(),
                observed.ppu().getVisibleScreenPixels1D(),
                "census observation must be side-effect free");
    }
}
