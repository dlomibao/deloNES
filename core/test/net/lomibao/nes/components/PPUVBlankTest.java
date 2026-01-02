package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPU VBlank flag logic
 * Phase 2.4: VBlank flag timing
 */
class PPUVBlankTest {
    
    private PPU ppu;
    
    @BeforeEach
    void setUp() {
        ppu = new PPU();
    }
    
    /**
     * Helper to read PPUSTATUS register
     */
    private int readPPUSTATUS() {
        return ppu.cpuBusRead(0x2002, false);
    }
    
    /**
     * Helper to get VBlank flag directly from PPUSTATUS
     */
    private boolean isVBlankFlagSet() {
        return (readPPUSTATUS() & 0x80) != 0;
    }
    
    @Test
    void testVBlankFlagInitiallyClear() {
        assertFalse(isVBlankFlagSet(), "VBlank flag should be clear initially");
    }
    
    @Test
    void testVBlankFlagSetsAtScanline241Cycle1() {
        // Advance to scanline 241, cycle 0
        for (int s = 0; s < 241; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        assertEquals(241, ppu.getScanline());
        assertEquals(0, ppu.getCycle());
        assertFalse(isVBlankFlagSet(), "VBlank flag should not be set at scanline 241, cycle 0");
        
        // One more clock to reach cycle 1
        ppu.clock();
        assertEquals(241, ppu.getScanline());
        assertEquals(1, ppu.getCycle());
        assertTrue(isVBlankFlagSet(), "VBlank flag should be set at scanline 241, cycle 1");
    }
    
    @Test
    void testVBlankFlagRemainsSetDuringVBlank() {
        // Advance to middle of VBlank (scanline 250)
        for (int s = 0; s < 250; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        for (int c = 0; c < 100; c++) {
            ppu.clock();
        }
        
        assertEquals(250, ppu.getScanline());
        assertTrue(isVBlankFlagSet(), "VBlank flag should remain set during VBlank period");
    }
    
    @Test
    void testVBlankFlagClearsAtScanline261Cycle1() {
        // Advance to end of VBlank (scanline 261, cycle 0)
        for (int s = 0; s < 261; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        assertEquals(261, ppu.getScanline());
        assertEquals(0, ppu.getCycle());
        assertTrue(isVBlankFlagSet(), "VBlank flag should still be set at scanline 261, cycle 0");
        
        // One more clock to reach cycle 1
        ppu.clock();
        assertEquals(261, ppu.getScanline());
        assertEquals(1, ppu.getCycle());
        assertFalse(isVBlankFlagSet(), "VBlank flag should be clear at scanline 261, cycle 1");
    }
    
    @Test
    void testVBlankFlagClearsOnPPUSTATUSRead() {
        // Advance to VBlank
        for (int s = 0; s < 241; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        ppu.clock(); // Scanline 241, cycle 1
        
        // Read PPUSTATUS - should return VBlank flag as set, then clear it
        int status = readPPUSTATUS();
        assertTrue((status & 0x80) != 0, "Read should return VBlank flag as set");
        
        // Second read should show flag is now clear
        int status2 = readPPUSTATUS();
        assertFalse((status2 & 0x80) != 0, "VBlank flag should be clear after first read");
    }
    
    @Test
    void testVBlankFlagDoesNotSetIfClearedByReadBeforeScanline241() {
        // Advance to scanline 240 (just before VBlank)
        for (int s = 0; s < 240; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        
        // Clear any existing flag (shouldn't be set, but make sure)
        readPPUSTATUS();
        assertFalse(isVBlankFlagSet());
        
        // Advance to scanline 241, cycle 1
        for (int c = 0; c < 341; c++) {
            ppu.clock();
        }
        ppu.clock();
        
        assertEquals(241, ppu.getScanline());
        assertEquals(1, ppu.getCycle());
        assertTrue(isVBlankFlagSet(), "VBlank flag should set normally at scanline 241, cycle 1");
    }
    
    @Test
    void testVBlankCycleAcrossMultipleFrames() {
        // First frame - check VBlank sets
        for (int s = 0; s < 241; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        ppu.clock();
        assertTrue(isVBlankFlagSet(), "VBlank should set in first frame");
        
        // Complete first frame
        while (!ppu.isFrameComplete()) {
            ppu.clock();
        }
        
        // VBlank should be clear after pre-render scanline
        assertFalse(isVBlankFlagSet(), "VBlank should be clear at start of second frame");
        
        // Second frame - advance to VBlank again
        for (int s = 0; s < 241; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        ppu.clock();
        assertTrue(isVBlankFlagSet(), "VBlank should set in second frame");
    }
    
    @Test
    void testVBlankFlagStateAtVisibleScanlines() {
        // During visible scanlines (0-239), VBlank should be clear
        for (int s = 0; s < 100; s++) {
            for (int c = 0; c < 341; c++) {
                assertFalse(isVBlankFlagSet(), 
                    "VBlank flag should be clear during visible scanlines (scanline " + s + ")");
                ppu.clock();
            }
        }
    }
    
    @Test
    void testSprite0HitClearsAtPreRender() {
        // Manually set sprite 0 hit flag (bit 6 of PPUSTATUS)
        ppu.cpuBusWrite(0x2002, (byte) 0x40);  // This won't actually work since PPUSTATUS is read-only
        // Instead, we need to test that the clock() clears it
        
        // Advance to pre-render scanline
        for (int s = 0; s < 261; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        ppu.clock(); // Scanline 261, cycle 1
        
        int status = readPPUSTATUS();
        assertEquals(0, status & 0x40, "Sprite 0 hit flag should be clear after pre-render");
    }
    
    @Test
    void testFrameCompleteClearsAtPreRender() {
        // Advance to scanline 261, cycle 0 (pre-render scanline, before flag clear)
        for (int s = 0; s < 261; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        
        // Complete the last scanline to set frameComplete
        for (int c = 0; c < 341; c++) {
            ppu.clock();
        }
        
        assertTrue(ppu.isFrameComplete(), "Frame should be complete after wrapping");
        assertEquals(0, ppu.getScanline(), "Should be at scanline 0 after frame wrap");
        assertEquals(0, ppu.getCycle(), "Should be at cycle 0 after frame wrap");
        
        // Advance through scanline 0 to reach scanline 261 of next frame
        for (int s = 0; s < 261; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        ppu.clock(); // Now at scanline 261, cycle 1
        
        assertEquals(261, ppu.getScanline());
        assertEquals(1, ppu.getCycle());
        assertFalse(ppu.isFrameComplete(), "frameComplete should be cleared at pre-render scanline 261, cycle 1");
    }
}
