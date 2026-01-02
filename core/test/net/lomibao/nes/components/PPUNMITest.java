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
    private MockCPU mockCPU;
    
    /**
     * Mock CPU for testing NMI signals
     */
    static class MockCPU extends CPU6502 {
        private int nmiCount = 0;
        private int lastNMIScanline = -1;
        private int lastNMICycle = -1;
        
        public MockCPU() {
            super();
        }
        
        @Override
        public void nmi() {
            nmiCount++;
            // Store PPU state when NMI triggered (would need PPU reference in real scenario)
        }
        
        public int getNMICount() {
            return nmiCount;
        }
        
        public void resetNMICount() {
            nmiCount = 0;
        }
        
        public void recordNMITiming(int scanline, int cycle) {
            lastNMIScanline = scanline;
            lastNMICycle = cycle;
        }
        
        public int getLastNMIScanline() {
            return lastNMIScanline;
        }
        
        public int getLastNMICycle() {
            return lastNMICycle;
        }
    }
    
    @BeforeEach
    void setUp() {
        ppu = new PPU();
        mockCPU = new MockCPU();
        ppu.setCPU(mockCPU);
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
        for (int s = 0; s < 241; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        ppu.clock(); // Scanline 241, cycle 1
        
        assertEquals(0, mockCPU.getNMICount(), "NMI should not trigger when disabled");
    }
    
    @Test
    void testNMITriggersAtScanline241WhenEnabled() {
        enableNMI();
        
        assertEquals(0, mockCPU.getNMICount(), "NMI count should start at 0");
        
        // Advance to scanline 241, cycle 1
        for (int s = 0; s < 241; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        
        assertEquals(0, mockCPU.getNMICount(), "NMI should not trigger before cycle 1");
        
        ppu.clock(); // Scanline 241, cycle 1
        
        assertEquals(1, mockCPU.getNMICount(), "NMI should trigger at scanline 241, cycle 1");
    }
    
    @Test
    void testNMITriggersOncePerFrame() {
        enableNMI();
        
        // Run full frame
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        
        assertEquals(1, mockCPU.getNMICount(), "NMI should trigger exactly once per frame");
    }
    
    @Test
    void testNMITriggersEachFrameWhenEnabled() {
        enableNMI();
        
        // Frame 1
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertEquals(1, mockCPU.getNMICount(), "NMI should trigger in first frame");
        
        // Frame 2
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertEquals(2, mockCPU.getNMICount(), "NMI should trigger in second frame");
        
        // Frame 3
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertEquals(3, mockCPU.getNMICount(), "NMI should trigger in third frame");
    }
    
    @Test
    void testDisablingNMIMidFramePreventsNextNMI() {
        enableNMI();
        
        // Advance to middle of frame (scanline 100)
        for (int s = 0; s < 100; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        
        // Disable NMI
        disableNMI();
        
        // Complete frame
        while (!ppu.isFrameComplete()) {
            ppu.clock();
        }
        
        assertEquals(0, mockCPU.getNMICount(), "NMI should not trigger when disabled mid-frame");
    }
    
    @Test
    void testEnablingNMIMidFrameTriggersNextVBlank() {
        disableNMI();
        
        // Advance to middle of frame (scanline 100)
        for (int s = 0; s < 100; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        
        // Enable NMI
        enableNMI();
        
        // Continue to VBlank
        for (int s = 100; s < 241; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        ppu.clock(); // Scanline 241, cycle 1
        
        assertEquals(1, mockCPU.getNMICount(), "NMI should trigger after being enabled mid-frame");
    }
    
    @Test
    void testNMINotTriggeredIfVBlankFlagReadBeforeSet() {
        enableNMI();
        
        // Advance to scanline 240 (just before VBlank)
        for (int s = 0; s < 240; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        
        // Read PPUSTATUS to clear VBlank flag
        ppu.cpuBusRead(0x2002, false);
        
        // Advance to scanline 241, cycle 1
        for (int c = 0; c < 341; c++) {
            ppu.clock();
        }
        ppu.clock();
        
        // NMI should still trigger because the flag sets at scanline 241, cycle 1
        assertEquals(1, mockCPU.getNMICount(), "NMI should trigger even if flag was previously clear");
    }
    
    @Test
    void testNMINotTriggeredDuringVisibleScanlines() {
        enableNMI();
        
        // Run through all visible scanlines
        for (int s = 0; s < 240; s++) {
            for (int c = 0; c < 341; c++) {
                ppu.clock();
            }
        }
        
        assertEquals(0, mockCPU.getNMICount(), "NMI should not trigger during visible scanlines");
    }
    
    @Test
    void testNMIWithoutCPUDoesNotCrash() {
        // Create PPU without CPU reference
        PPU ppuNoCPU = new PPU();
        // Don't set CPU reference
        
        // Enable NMI
        ppuNoCPU.cpuBusWrite(0x2000, (byte) 0x80);
        
        // Advance to VBlank - should not crash
        assertDoesNotThrow(() -> {
            for (int s = 0; s < 241; s++) {
                for (int c = 0; c < 341; c++) {
                    ppuNoCPU.clock();
                }
            }
            ppuNoCPU.clock(); // Scanline 241, cycle 1
        }, "PPU should not crash when triggering NMI without CPU reference");
    }
    
    @Test
    void testTogglingNMIEnableAcrossFrames() {
        // Frame 1: NMI enabled
        enableNMI();
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertEquals(1, mockCPU.getNMICount());
        
        // Frame 2: NMI disabled
        disableNMI();
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertEquals(1, mockCPU.getNMICount(), "Count should not increase when disabled");
        
        // Frame 3: NMI enabled again
        enableNMI();
        for (int i = 0; i < 89342; i++) {
            ppu.clock();
        }
        assertEquals(2, mockCPU.getNMICount(), "NMI should trigger again after re-enabling");
    }
    
    @Test
    void testPPUCTRLBit7ControlsNMI() {
        // Test each bit pattern with bit 7 clear
        for (int i = 0; i < 128; i++) {
            ppu.reset();
            mockCPU.resetNMICount();
            ppu.setCPU(mockCPU);
            
            writePPUCTRL((byte) i); // Bit 7 = 0
            
            // Advance to VBlank
            for (int s = 0; s < 241; s++) {
                for (int c = 0; c < 341; c++) {
                    ppu.clock();
                }
            }
            ppu.clock();
            
            assertEquals(0, mockCPU.getNMICount(), 
                "NMI should not trigger with PPUCTRL=0x" + Integer.toHexString(i));
        }
        
        // Test each bit pattern with bit 7 set
        for (int i = 128; i < 256; i++) {
            ppu.reset();
            mockCPU.resetNMICount();
            ppu.setCPU(mockCPU);
            
            writePPUCTRL((byte) i); // Bit 7 = 1
            
            // Advance to VBlank
            for (int s = 0; s < 241; s++) {
                for (int c = 0; c < 341; c++) {
                    ppu.clock();
                }
            }
            ppu.clock();
            
            assertEquals(1, mockCPU.getNMICount(), 
                "NMI should trigger with PPUCTRL=0x" + Integer.toHexString(i));
        }
    }
}
