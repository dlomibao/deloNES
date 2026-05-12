package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPU NMI generation
 * Phase 2.5: NMI triggering logic
 */
class PPUNMITest {
    
    private PPU ppu;
    /**
     * Per-test counter incremented every time we observe a true return from
     * {@link PPU#consumeNmi()}. Production NMI dispatch goes through the PPU
     * latch (Step 2 of the playable-gen1 plan), so we observe NMI by polling
     * the latch in the tick loop rather than mocking out {@code CPU6502.nmi()}.
     */
    private int nmiCount;

    @BeforeEach
    void setUp() {
        ppu = new PPU();
        nmiCount = 0;
    }

    /** Tick the PPU once and accumulate any NMI rising edge into {@link #nmiCount}. */
    private void tick() {
        ppu.clock();
        if (ppu.consumeNmi()) {
            nmiCount++;
        }
    }

    /** Tick {@code n} times. */
    private void tickN(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }
    
    /**
     * Helper to write to PPUCTRL register
     */
    private void writePPUCTRL(byte value) {
        ppu.cpuBusWrite(0x2000, value);
    }
    
    /**
     * Helper to enable NMI (set PPUCTRL bit 7)
     */
    private void enableNMI() {
        writePPUCTRL((byte) 0x80);
    }
    
    /**
     * Helper to disable NMI (clear PPUCTRL bit 7)
     */
    private void disableNMI() {
        writePPUCTRL((byte) 0x00);
    }
    
    @Test
    void testNMINotTriggeredWhenDisabled() {
        disableNMI();
        
        // Advance to VBlank
        tickN(241 * 341 + 1);

        assertEquals(0, nmiCount, "NMI should not trigger when disabled");
    }

    @Test
    void testNMITriggersAtScanline241WhenEnabled() {
        enableNMI();

        assertEquals(0, nmiCount, "NMI count should start at 0");

        // Advance to scanline 241, cycle 0 (one short of VBlank entry)
        tickN(241 * 341);

        assertEquals(0, nmiCount, "NMI should not trigger before cycle 1");

        tick(); // Scanline 241, cycle 1

        assertEquals(1, nmiCount, "NMI should trigger at scanline 241, cycle 1");
    }

    @Test
    void testNMITriggersOncePerFrame() {
        enableNMI();

        // Run full frame
        tickN(89342);

        assertEquals(1, nmiCount, "NMI should trigger exactly once per frame");
    }

    @Test
    void testNMITriggersEachFrameWhenEnabled() {
        enableNMI();

        tickN(89342);
        assertEquals(1, nmiCount, "NMI should trigger in first frame");

        tickN(89342);
        assertEquals(2, nmiCount, "NMI should trigger in second frame");

        tickN(89342);
        assertEquals(3, nmiCount, "NMI should trigger in third frame");
    }

    @Test
    void testDisablingNMIMidFramePreventsNextNMI() {
        enableNMI();

        // Advance to middle of frame (scanline 100)
        tickN(100 * 341);

        // Disable NMI
        disableNMI();

        // Complete frame
        while (!ppu.isFrameComplete()) {
            tick();
        }

        assertEquals(0, nmiCount, "NMI should not trigger when disabled mid-frame");
    }

    @Test
    void testEnablingNMIMidFrameTriggersNextVBlank() {
        disableNMI();

        // Advance to middle of frame (scanline 100)
        tickN(100 * 341);

        // Enable NMI
        enableNMI();

        // Continue to VBlank entry (scanline 241, cycle 1)
        tickN((241 - 100) * 341 + 1);

        assertEquals(1, nmiCount, "NMI should trigger after being enabled mid-frame");
    }

    @Test
    void testNMINotTriggeredIfVBlankFlagReadBeforeSet() {
        enableNMI();

        // Advance to scanline 240 (just before VBlank)
        tickN(240 * 341);

        // Read PPUSTATUS to clear VBlank flag
        ppu.cpuBusRead(0x2002, false);

        // Advance to scanline 241, cycle 1
        tickN(341 + 1);

        // NMI should still trigger because the flag sets at scanline 241, cycle 1
        assertEquals(1, nmiCount, "NMI should trigger even if flag was previously clear");
    }

    @Test
    void testNMINotTriggeredDuringVisibleScanlines() {
        enableNMI();

        // Run through all visible scanlines
        tickN(240 * 341);

        assertEquals(0, nmiCount, "NMI should not trigger during visible scanlines");
    }

    @Test
    void testNMIWithoutCPUDoesNotCrash() {
        // Sanity check: PPU has no CPU reference at all post-Step-2 — the
        // latch-and-poll API stands on its own. This test is preserved
        // for the test-name continuity but the failure mode it once guarded
        // (NPE in PPU.clock when cpu==null) is now structurally impossible.
        PPU ppuNoCPU = new PPU();

        // Enable NMI
        ppuNoCPU.cpuBusWrite(0x2000, (byte) 0x80);

        // Advance to VBlank - should not crash
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 241 * 341 + 1; i++) {
                ppuNoCPU.clock();
            }
        }, "PPU should not crash on VBlank entry without CPU reference");
        assertTrue(ppuNoCPU.consumeNmi(),
                "NMI latch should be set even with no CPU wired");
    }

    @Test
    void testTogglingNMIEnableAcrossFrames() {
        // Frame 1: NMI enabled
        enableNMI();
        tickN(89342);
        assertEquals(1, nmiCount);

        // Frame 2: NMI disabled
        disableNMI();
        tickN(89342);
        assertEquals(1, nmiCount, "Count should not increase when disabled");

        // Frame 3: NMI enabled again
        enableNMI();
        tickN(89342);
        assertEquals(2, nmiCount, "NMI should trigger again after re-enabling");
    }

    // ---------------------------------------------------------------
    // Rising-edge NMI inside VBlank (B5).
    //
    // On real hardware, writing $2000 with bit 7 set while the
    // PPUSTATUS VBlank flag (bit 7) is already set asserts NMI — even
    // if VBlank entry already fired one for this frame. Battletoads
    // and other titles toggle $2000 bit 7 mid-VBlank to fire a second
    // NMI. See https://www.nesdev.org/wiki/PPU_registers#PPUCTRL
    // ---------------------------------------------------------------

    @Test
    void testSettingNMIEnableDuringVBlankWithPrevClearLatchesNMI() {
        // Start with NMI disabled so VBlank entry does NOT latch NMI.
        disableNMI();
        // Advance to scanline 241, cycle 1 — VBlank entry. No latch
        // expected because bit 7 of PPUCTRL is clear.
        tickN(241 * 341 + 1);
        assertEquals(0, nmiCount, "VBlank entry with NMI disabled must not latch");
        assertTrue((ppu.cpuBusRead(0x2002, true) & 0x80) != 0,
                "precondition: PPUSTATUS VBlank flag should be set");

        // Now perform the 0→1 rising edge on PPUCTRL bit 7 while still
        // inside VBlank. Hardware should latch a fresh NMI.
        enableNMI();
        assertTrue(ppu.consumeNmi(),
                "Setting PPUCTRL bit 7 (0→1) during VBlank must latch NMI");
    }

    @Test
    void testSettingNMIEnableDuringVBlankWhenAlreadySetDoesNotReLatch() {
        // NMI enabled before VBlank entry — once-per-frame entry latches.
        enableNMI();
        tickN(241 * 341 + 1);
        assertEquals(1, nmiCount, "VBlank entry with NMI enabled should latch");
        assertTrue((ppu.cpuBusRead(0x2002, true) & 0x80) != 0,
                "precondition: PPUSTATUS VBlank flag should still be set");

        // Writing the same enable bit again is NOT an edge; no new NMI.
        writePPUCTRL((byte) 0x80);
        assertFalse(ppu.consumeNmi(),
                "No rising edge on PPUCTRL bit 7 must not latch NMI");
    }

    @Test
    void testSettingNMIEnableOutsideVBlankDoesNotLatch() {
        // We are at scanline 0, cycle 0 — outside VBlank, PPUSTATUS bit 7 = 0.
        disableNMI();
        // Sanity: VBlank flag clear.
        assertEquals(0, ppu.cpuBusRead(0x2002, true) & 0x80,
                "precondition: PPUSTATUS VBlank flag should be clear");

        // Enable NMI while outside VBlank — no latch.
        enableNMI();
        assertFalse(ppu.consumeNmi(),
                "Enabling NMI outside VBlank must not latch NMI");
    }

    @Test
    void testRisingEdgeProducesSecondNMIInSameFrame() {
        // Battletoads pattern: NMI handler returns, sets bit 7 high again
        // mid-VBlank to immediately re-fire NMI. We model the simpler form:
        // entry latches one NMI; clearing then re-setting bit 7 (still in
        // VBlank, flag still set since we haven't read $2002) latches a
        // second NMI within the same VBlank window.
        //
        // Note: the {@link #tick()} helper auto-consumes the latch into
        // {@code nmiCount}, so we observe the entry NMI via the counter,
        // then directly observe the second NMI by polling consumeNmi.
        enableNMI();
        tickN(241 * 341 + 1);     // VBlank entry — first NMI latched & consumed by tick().
        assertEquals(1, nmiCount, "first NMI of frame (VBlank entry)");
        assertTrue((ppu.cpuBusRead(0x2002, true) & 0x80) != 0,
                "precondition: PPUSTATUS VBlank flag should still be set");

        // Clear bit 7, then set it again — rising edge mid-VBlank.
        disableNMI();
        // Note: we deliberately do NOT read $2002 (non-readOnly) here; the
        // VBlank flag stays set, which is the precondition for the edge to
        // fire.
        enableNMI();
        assertTrue(ppu.consumeNmi(),
                "Second NMI must latch on mid-VBlank rising edge");
    }

    @Test
    void testPPUCTRLBit7ControlsNMI() {
        // Test each bit pattern with bit 7 clear
        for (int i = 0; i < 128; i++) {
            ppu.reset();
            nmiCount = 0;

            writePPUCTRL((byte) i); // Bit 7 = 0

            // Advance to VBlank
            tickN(241 * 341 + 1);

            assertEquals(0, nmiCount,
                "NMI should not trigger with PPUCTRL=0x" + Integer.toHexString(i));
        }

        // Test each bit pattern with bit 7 set
        for (int i = 128; i < 256; i++) {
            ppu.reset();
            nmiCount = 0;

            writePPUCTRL((byte) i); // Bit 7 = 1

            // Advance to VBlank
            tickN(241 * 341 + 1);

            assertEquals(1, nmiCount,
                "NMI should trigger with PPUCTRL=0x" + Integer.toHexString(i));
        }
    }
}
