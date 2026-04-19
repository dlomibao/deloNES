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
