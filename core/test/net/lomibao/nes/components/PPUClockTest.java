package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPU clock cycle counting and frame timing
 * Phase 2.1 and 2.2: Clock counter state and basic clock cycle counting
 */
class PPUClockTest {
    
    private PPU ppu;
    
    @BeforeEach
    void setUp() {
        ppu = new PPU();
    }
    
    // Phase 2.1 Tests: Clock Counter State
    
    @Test
    void testCountersInitializeToZero() {
        assertEquals(0, ppu.getScanline(), "Scanline should initialize to 0");
        assertEquals(0, ppu.getCycle(), "Cycle should initialize to 0");
    }
    
    @Test
    void testFrameCompleteInitializesToFalse() {
        assertFalse(ppu.isFrameComplete(), "frameComplete should initialize to false");
    }
    
    @Test
    void testResetClearsCounters() {
        // Advance the PPU
        for (int i = 0; i < 1000; i++) {
            ppu.clock();
        }
        
        // Reset
        ppu.reset();
        
        assertEquals(0, ppu.getScanline(), "Scanline should reset to 0");
        assertEquals(0, ppu.getCycle(), "Cycle should reset to 0");
        assertFalse(ppu.isFrameComplete(), "frameComplete should reset to false");
    }
    
    // Phase 2.2 Tests: Basic Clock Cycle Counting
    
    @Test
    void testCycleIncrementsOnClock() {
        assertEquals(0, ppu.getCycle());
        
        ppu.clock();
        assertEquals(1, ppu.getCycle());
        
        ppu.clock();
        assertEquals(2, ppu.getCycle());
    }
    
    @Test
    void testCycleWrapsAt341() {
        // Set to cycle 340 (last cycle of scanline)
        for (int i = 0; i < 340; i++) {
            ppu.clock();
        }
        assertEquals(340, ppu.getCycle());
        assertEquals(0, ppu.getScanline());
        
        // Next clock should wrap to 0 and increment scanline
        ppu.clock();
        assertEquals(0, ppu.getCycle());
        assertEquals(1, ppu.getScanline());
    }
    
    @Test
    void testScanlineIncrementsWhenCycleWraps() {
        int initialScanline = ppu.getScanline();
        
        // Complete one full scanline (341 cycles)
        for (int i = 0; i < 341; i++) {
            ppu.clock();
        }
        
        assertEquals(initialScanline + 1, ppu.getScanline());
        assertEquals(0, ppu.getCycle());
    }
    
    @Test
    void testScanlineWrapsAt262() {
        // Advance to scanline 261, cycle 340 (last cycle of frame)
        for (int scanline = 0; scanline < 262; scanline++) {
            for (int cycle = 0; cycle < 341; cycle++) {
                ppu.clock();
            }
        }
        
        // Should wrap to scanline 0
        assertEquals(0, ppu.getScanline());
        assertEquals(0, ppu.getCycle());
    }
    
    @Test
    void testFrameCompleteSetWhenFrameWraps() {
        assertFalse(ppu.isFrameComplete(), "frameComplete should start false");
        
        // Complete one full frame (341 * 262 = 89,342 cycles)
        for (int scanline = 0; scanline < 262; scanline++) {
            for (int cycle = 0; cycle < 341; cycle++) {
                ppu.clock();
            }
        }
        
        assertTrue(ppu.isFrameComplete(), "frameComplete should be set after frame wraps");
        assertEquals(0, ppu.getScanline(), "Should wrap to scanline 0");
        assertEquals(0, ppu.getCycle(), "Should wrap to cycle 0");
    }
    
    @Test
    void testFullFrameCycleCount() {
        int clockCount = 0;
        
        // Clock until frame completes
        while (!ppu.isFrameComplete()) {
            ppu.clock();
            clockCount++;
        }
        
        // Full frame is 341 cycles * 262 scanlines = 89,342 cycles
        assertEquals(89342, clockCount, "Full frame should be exactly 89,342 cycles");
    }
    
    @Test
    void testMultipleFrames() {
        // First frame
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertTrue(ppu.isFrameComplete());
        ppu.clearFrameComplete();
        
        // Second frame
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertTrue(ppu.isFrameComplete());
        
        assertEquals(0, ppu.getScanline());
        assertEquals(0, ppu.getCycle());
    }
    
    @Test
    void testOddFrameToggle() {
        assertFalse(ppu.isOddFrame(), "Should start with even frame");
        
        // Complete first frame
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertTrue(ppu.isOddFrame(), "Should be odd frame after first frame");
        
        // Complete second frame
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertFalse(ppu.isOddFrame(), "Should be even frame after second frame");
        
        // Complete third frame
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertTrue(ppu.isOddFrame(), "Should be odd frame after third frame");
    }
    
    @Test
    void testClearFrameComplete() {
        // Complete a frame
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertTrue(ppu.isFrameComplete());
        
        // Clear the flag
        ppu.clearFrameComplete();
        assertFalse(ppu.isFrameComplete());
    }
    
    @Test
    void testSpecificScanlineCyclePositions() {
        // Test scanline 0, cycle 0
        assertEquals(0, ppu.getScanline());
        assertEquals(0, ppu.getCycle());
        
        // Advance to scanline 0, cycle 100
        for (int i = 0; i < 100; i++) {
            ppu.clock();
        }
        assertEquals(0, ppu.getScanline());
        assertEquals(100, ppu.getCycle());
        
        // Advance to scanline 10, cycle 50
        for (int i = 0; i < (10 * 341 + 50 - 100); i++) {
            ppu.clock();
        }
        assertEquals(10, ppu.getScanline());
        assertEquals(50, ppu.getCycle());
    }
}
