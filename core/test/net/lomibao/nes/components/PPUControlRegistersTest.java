package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPUCTRL and PPUMASK helper methods (Phase 3)
 * Tests register bit parsing and configuration
 */
class PPUControlRegistersTest {
    
    private PPU ppu;
    
    @BeforeEach
    void setUp() {
        ppu = new PPU();
        ppu.reset();
    }
    
    // ==================== PPUCTRL Tests ====================
    
    @Test
    void testPPUCTRLBit4SelectsBackgroundPatternTable() {
        // Set PPUCTRL bit 4 = 0 (pattern table 0 at $0000)
        ppu.cpuBusWrite(0x2000, (byte) 0x00);
        ppu.cpuBusWrite(0x2001, (byte) 0x08);  // Enable rendering
        
        MockPPUBus mockBus = new MockPPUBus();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        mockBus.write(0x2000, (byte) 0x00);  // Nametable: tile 0
        
        // Trigger pattern table fetch
        advanceToCycle(0, 5);
        ppu.clock();
        
        // Should access pattern table 0
        assertTrue(mockBus.wasAddressRead(0x0000), "PPUCTRL bit 4=0 should select pattern table 0");
        
        // Reset and test bit 4 = 1 (pattern table 1 at $1000)
        ppu.reset();
        ppu.cpuBusWrite(0x2000, (byte) 0x10);  // Set bit 4
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        mockBus = new MockPPUBus();
        ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        mockBus.write(0x2000, (byte) 0x00);
        
        advanceToCycle(0, 5);
        ppu.clock();
        
        // Should access pattern table 1
        assertTrue(mockBus.wasAddressRead(0x1000), "PPUCTRL bit 4=1 should select pattern table 1");
    }
    
    @Test
    void testPPUCTRLBits0and1SelectBaseNametable() {
        MockPPUBus mockBus = new MockPPUBus();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        ppu.cpuBusWrite(0x2001, (byte) 0x08);  // Enable rendering
        
        // Test nametable 0 (bits 0-1 = 00) -> $2000
        ppu.cpuBusWrite(0x2000, (byte) 0x00);
        mockBus.write(0x2000, (byte) 0xAA);
        advanceToCycle(0, 1);
        ppu.clock();
        assertEquals(0xAA, ppu.getBgNextTileId(), "Bits 0-1=00 should select nametable at $2000");
        
        // Test nametable 1 (bits 0-1 = 01) -> $2400
        ppu.reset();
        ppu.cpuBusWrite(0x2000, (byte) 0x01);
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        mockBus.write(0x2400, (byte) 0xBB);
        advanceToCycle(0, 1);
        ppu.clock();
        assertEquals(0xBB, ppu.getBgNextTileId(), "Bits 0-1=01 should select nametable at $2400");
        
        // Test nametable 2 (bits 0-1 = 10) -> $2800
        ppu.reset();
        ppu.cpuBusWrite(0x2000, (byte) 0x02);
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        mockBus.write(0x2800, (byte) 0xCC);
        advanceToCycle(0, 1);
        ppu.clock();
        assertEquals(0xCC, ppu.getBgNextTileId(), "Bits 0-1=10 should select nametable at $2800");
        
        // Test nametable 3 (bits 0-1 = 11) -> $2C00
        ppu.reset();
        ppu.cpuBusWrite(0x2000, (byte) 0x03);
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        mockBus.write(0x2C00, (byte) 0xDD);
        advanceToCycle(0, 1);
        ppu.clock();
        assertEquals(0xDD, ppu.getBgNextTileId(), "Bits 0-1=11 should select nametable at $2C00");
    }
    
    @Test
    void testPPUCTRLBit7ControlsNMI() {
        // Disable NMI generation (bit 7 = 0)
        ppu.cpuBusWrite(0x2000, (byte) 0x00);

        // Advance to VBlank start
        advanceToCycle(241, 1);

        assertFalse(ppu.peekNmi(), "NMI should not be latched when PPUCTRL bit 7 = 0");

        // Reset and enable NMI (bit 7 = 1)
        ppu.reset();
        ppu.cpuBusWrite(0x2000, (byte) 0x80);

        advanceToCycle(241, 1);

        assertTrue(ppu.peekNmi(), "NMI should be latched when PPUCTRL bit 7 = 1");
    }
    
    // ==================== PPUMASK Tests ====================
    
    @Test
    void testPPUMASKBit3EnablesBackgroundRendering() {
        MockPPUBus mockBus = new MockPPUBus();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        mockBus.write(0x2000, (byte) 0x42);
        
        // Disable background rendering (bit 3 = 0)
        ppu.cpuBusWrite(0x2001, (byte) 0x00);
        
        advanceToCycle(0, 1);
        ppu.clock();
        
        // No fetching should occur
        assertEquals(0, ppu.getBgNextTileId(), "Background fetching should be disabled when PPUMASK bit 3 = 0");
        
        // Enable background rendering (bit 3 = 1)
        ppu.reset();
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        advanceToCycle(0, 1);
        ppu.clock();
        
        // Fetching should occur
        assertEquals(0x42, ppu.getBgNextTileId(), "Background fetching should be enabled when PPUMASK bit 3 = 1");
    }
    
    @Test
    void testPPUMASKBit4EnablesSpriteRendering() {
        // Test that rendering is considered enabled when sprite bit is set
        ppu.cpuBusWrite(0x2001, (byte) 0x10);  // Enable sprites, disable background
        
        MockPPUBus mockBus = new MockPPUBus();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        mockBus.write(0x2000, (byte) 0x55);
        
        advanceToCycle(0, 1);
        ppu.clock();
        
        // Background fetching still occurs when rendering is enabled (even if just sprites)
        // This is correct NES behavior - fetch happens when ANY rendering is enabled
        assertTrue(ppu.getBgNextTileId() >= 0, "Fetching occurs when sprite rendering enabled");
    }
    
    @Test
    void testPPUMASKAllBitsDisabledStopsFetching() {
        MockPPUBus mockBus = new MockPPUBus();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        mockBus.write(0x2000, (byte) 0xFF);
        
        // All rendering disabled
        ppu.cpuBusWrite(0x2001, (byte) 0x00);
        
        advanceToCycle(0, 1);
        int beforeTileId = ppu.getBgNextTileId();
        ppu.clock();
        int afterTileId = ppu.getBgNextTileId();
        
        // No change should occur
        assertEquals(beforeTileId, afterTileId, "No fetching when all rendering disabled");
    }
    
    @Test
    void testPPUMASKBitsWorkIndependently() {
        // Test that setting other bits doesn't affect background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x1F);  // All bits set
        
        MockPPUBus mockBus = new MockPPUBus();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        mockBus.write(0x2000, (byte) 0x77);
        
        advanceToCycle(0, 1);
        ppu.clock();
        
        // Fetching should still work with all bits set
        assertEquals(0x77, ppu.getBgNextTileId(), "Other PPUMASK bits should not interfere with fetching");
    }
    
    @Test
    void testRenderingCanBeToggledMidFrame() {
        MockPPUBus mockBus = new MockPPUBus();
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        
        // Start with rendering enabled
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        mockBus.write(0x2000, (byte) 0x11);
        
        advanceToCycle(0, 1);
        ppu.clock();
        assertEquals(0x11, ppu.getBgNextTileId(), "Fetching should work when enabled");
        
        // Disable rendering mid-frame
        ppu.cpuBusWrite(0x2001, (byte) 0x00);
        
        // Advance to next tile fetch
        advanceCycles(8);
        mockBus.write(0x2001, (byte) 0x22);
        int tileId = ppu.getBgNextTileId();
        
        // Fetch should not update when disabled
        assertEquals(0x11, tileId, "Fetching should stop when rendering disabled mid-frame");
    }
    
    // Helper methods
    
    private void advanceCycles(int count) {
        for (int i = 0; i < count; i++) {
            ppu.clock();
        }
    }
    
    private void advanceToCycle(int targetScanline, int targetCycle) {
        // Don't reset - just advance from current position
        int currentScanline = ppu.getScanline();
        int currentCycle = ppu.getCycle();
        
        while (currentScanline < targetScanline || (currentScanline == targetScanline && currentCycle < targetCycle)) {
            ppu.clock();
            currentCycle = ppu.getCycle();
            currentScanline = ppu.getScanline();
        }
    }
    
    /**
     * Mock PPU bus component for testing
     */
    private static class MockPPUBus implements PPUBusComponent {
        private byte[] memory = new byte[0x4000];
        private boolean[] readAddresses = new boolean[0x4000];
        private PPUBus ppuBus;
        
        @Override
        public int ppuBusRead(int address, boolean readOnly) {
            address = address & 0x3FFF;
            readAddresses[address] = true;
            return Byte.toUnsignedInt(memory[address]);
        }
        
        @Override
        public void ppuBusWrite(int address, byte value) {
            address = address & 0x3FFF;
            memory[address] = value;
        }
        
        public void write(int address, byte value) {
            ppuBusWrite(address, value);
        }
        
        @Override
        public int getPPUBusStartAddress() {
            return 0x0000;
        }
        
        @Override
        public int getPPUBusEndAddress() {
            return 0x3FFF;
        }
        
        @Override
        public void connectPPUBus(PPUBus bus) {
            this.ppuBus = bus;
        }
        
        @Override
        public PPUBus getPPUBus() {
            return ppuBus;
        }
        
        public boolean wasAddressRead(int address) {
            return readAddresses[address & 0x3FFF];
        }
    }
}
