package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPU tile fetching logic (Phase 3)
 * Tests nametable, attribute, and pattern table fetch sequences
 */
class PPUTileFetchTest {
    
    private PPU ppu;
    private MockPPUBus mockBus;
    private PPUBus ppuBus;
    
    @BeforeEach
    void setUp() {
        ppu = new PPU();
        mockBus = new MockPPUBus();
        ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        ppu.reset();
        // Note: Tests will enable rendering as needed
    }
    
    @Test
    void testNametableFetchCycle1() {
        // Set up nametable with tile ID 0x42 at position 0
        mockBus.write(0x2000, (byte) 0x42);
        
        // Enable rendering and advance to cycle 1
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        advanceToCycle(0, 1);
        
        // After cycle 1, nametable should be fetched
        advanceCycles(1);
        
        // Verify fetch happened (will be loaded into shifters at cycle 8)
        assertEquals(0x42, ppu.getBgNextTileId(), "Nametable byte should be fetched at cycle 1");
    }
    
    @Test
    void testAttributeFetchCycle3() {
        // Set up attribute table with value 0x03 at position 0
        mockBus.write(0x23C0, (byte) 0xFF);  // All quadrants use palette 3
        
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        advanceToCycle(0, 3);
        
        // After cycle 3, attribute should be fetched
        advanceCycles(1);
        
        // Verify attribute fetch (bottom-right quadrant)
        assertEquals(0x03, ppu.getBgNextTileAttr(), "Attribute byte should be fetched at cycle 3");
    }
    
    @Test
    void testShifterLoadingAtCycle8() {
        // Set up a complete tile fetch sequence
        mockBus.write(0x2000, (byte) 0x01);  // Tile ID 1
        mockBus.write(0x23C0, (byte) 0x00);  // Attribute 0
        mockBus.write(0x0010, (byte) 0xF0);  // Pattern low (tile 1, row 0)
        mockBus.write(0x0018, (byte) 0x0F);  // Pattern high (tile 1, row 0)
        
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        // Advance through full 8-cycle fetch sequence, then load at cycle 9
        // Cycle 2: fetch nametable, Cycle 4: fetch attr, Cycle 6: fetch pattern low
        // Cycle 8: fetch pattern high, Cycle 9: LOAD shifters
        advanceToCycle(0, 9);
        
        // After cycle 9 completes, shifters should be loaded with fetched data
        int shiftLow = ppu.getBgShiftPatternLow();
        int shiftHigh = ppu.getBgShiftPatternHigh();
        
        // Lower 8 bits should contain the pattern data
        assertEquals(0xF0, shiftLow & 0xFF, "Shifter low should be loaded with pattern low");
        assertEquals(0x0F, shiftHigh & 0xFF, "Shifter high should be loaded with pattern high");
    }
    
    @Test
    void testShiftRegisterShiftingDuringVisibleCycles() {
        mockBus.write(0x2000, (byte) 0x00);
        mockBus.write(0x0000, (byte) 0x80);
        mockBus.write(0x0008, (byte) 0x00);
        
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        // Advance to cycle 9 where shifters are loaded with 0x80
        advanceToCycle(0, 9);
        int initialShift = ppu.getBgShiftPatternLow();
        
        // Advance to cycle 10 (no load, just shift)
        advanceCycles(1);
        int afterShift = ppu.getBgShiftPatternLow();
        
        // Verify shift occurred (left shift by 1)
        assertEquals((initialShift << 1) & 0xFFFF, afterShift, "Shift register should shift left by 1");
    }
    
    @Test
    void testMultipleTileFetches() {
        // Set up multiple tiles in nametable
        mockBus.write(0x2000, (byte) 0x10);  // First tile
        mockBus.write(0x2001, (byte) 0x20);  // Second tile
        mockBus.write(0x2002, (byte) 0x30);  // Third tile
        
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        // Advance through first tile fetch
        advanceToCycle(0, 1);
        advanceCycles(1);
        assertEquals(0x10, ppu.getBgNextTileId(), "First tile should be fetched");
        
        // Advance through second tile fetch (8 cycles later)
        advanceCycles(8);
        assertEquals(0x20, ppu.getBgNextTileId(), "Second tile should be fetched");
        
        // Advance through third tile fetch
        advanceCycles(8);
        assertEquals(0x30, ppu.getBgNextTileId(), "Third tile should be fetched");
    }
    
    @Test
    void testNoFetchingWhenRenderingDisabled() {
        // Disable rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x00);
        
        mockBus.write(0x2000, (byte) 0xFF);
        
        // Advance through fetch cycle
        advanceToCycle(0, 1);
        advanceCycles(1);
        
        // Tile ID should not be fetched when rendering is disabled
        assertEquals(0, ppu.getBgNextTileId(), "No fetching should occur when rendering disabled");
    }
    
    @Test
    void testFetchingOnPreRenderScanline() {
        // Start fresh and fill nametable
        ppu.reset();
        // Fill enough memory to cover pre-render scanline fetches (scanline 261 / 8 = 32)
        // This means addresses up to baseAddr + (32 * 32) + 32 = baseAddr + 1056
        for (int i = 0; i < 2048; i++) {
            mockBus.write(0x2000 + i, (byte) 0xAB);
        }
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        // Manually advance to pre-render scanline (261), cycle 2
        // Total cycles per frame: 341 * 262 = 89,342
        // Advance to start of scanline 261 (341 * 261 = 89,101 cycles)
        int targetCycles = 341 * 261 + 2;
        for (int i = 0; i < targetCycles; i++) {
            ppu.clock();
        }
        
        // Fetching should occur on pre-render scanline
        assertEquals(0xAB, ppu.getBgNextTileId(), "Fetching should occur on pre-render scanline");
    }
    
    @Test
    void testNoFetchingOnVBlankScanlines() {
        // Enable rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        mockBus.write(0x2000, (byte) 0xFF);
        
        // Advance to VBlank scanline (241)
        advanceToCycle(241, 1);
        
        int beforeTileId = ppu.getBgNextTileId();
        advanceCycles(10);
        int afterTileId = ppu.getBgNextTileId();
        
        // No fetching should occur during VBlank
        assertEquals(beforeTileId, afterTileId, "No fetching should occur during VBlank");
    }
    
    @Test
    void testFetchCycleRanges() {
        // Enable rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        // Fill the entire nametable so all fetches will succeed
        for (int i = 0; i < 1024; i++) {
            mockBus.write(0x2000 + i, (byte) 0x99);
        }
        
        // Test that fetching occurs in cycles 1-256 (cycle 2 fetches nametable)
        advanceToCycle(0, 2);
        int tileIdAfterCycle2 = ppu.getBgNextTileId();
        
        assertEquals(0x99, tileIdAfterCycle2, "Fetching should occur in cycle range 1-256");
        
        // Test cycles 321-336 on SAME scanline (next scanline prefetch)
        // Fill nametable with a different value
        for (int i = 0; i < 1024; i++) {
            mockBus.write(0x2000 + i, (byte) 0x88);
        }
        while (ppu.getCycle() < 322) {
            ppu.clock();
        }
        int tileIdAfterCycle322 = ppu.getBgNextTileId();
        
        assertEquals(0x88, tileIdAfterCycle322, "Fetching should occur in cycle range 321-336");
    }
    
    // Helper methods
    
    private void advanceCycles(int count) {
        for (int i = 0; i < count; i++) {
            ppu.clock();
        }
    }
    
    private void advanceToCycle(int targetScanline, int targetCycle) {
        int currentScanline = ppu.getScanline();
        int currentCycle = ppu.getCycle();
        
        while (currentScanline < targetScanline || (currentScanline == targetScanline && currentCycle < targetCycle)) {
            ppu.clock();
            currentCycle = ppu.getCycle();
            currentScanline = ppu.getScanline();
        }
    }
    
    /**
     * Mock PPU bus component for testing tile fetches
     */
    private static class MockPPUBus implements PPUBusComponent {
        private byte[] memory = new byte[0x4000];  // 16KB address space
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
