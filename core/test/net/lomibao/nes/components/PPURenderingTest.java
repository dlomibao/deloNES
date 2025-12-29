package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPU background pixel rendering (Phase 4)
 */
class PPURenderingTest {
    private PPU ppu;
    private MockPPUBus mockBus;
    
    @BeforeEach
    void setUp() {
        ppu = new PPU();
        mockBus = new MockPPUBus();
        
        // Create and connect PPU bus
        PPUBus ppuBus = new PPUBus();
        ppuBus.connect(mockBus);
        ppu.connectPPUBus(ppuBus);
        
        ppu.reset();
    }
    
    /**
     * Helper to advance PPU to a specific scanline and cycle
     */
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
     * Helper to set up a simple tile pattern in memory
     */
    private void setupSimpleTile() {
        // Set up nametable - fill with tile index 1
        for (int i = 0; i < 1024; i++) {
            mockBus.write(0x2000 + i, (byte) 0x01);
        }
        
        // Set up attribute table - use palette 0
        for (int i = 0; i < 64; i++) {
            mockBus.write(0x23C0 + i, (byte) 0x00);
        }
        
        // Set up pattern table tile 1 - simple vertical stripe pattern
        // Pattern low byte (each byte = 1 row of 8 pixels, bit pattern)
        mockBus.write(0x0010, (byte) 0b01010101);
        mockBus.write(0x0011, (byte) 0b01010101);
        mockBus.write(0x0012, (byte) 0b01010101);
        mockBus.write(0x0013, (byte) 0b01010101);
        mockBus.write(0x0014, (byte) 0b01010101);
        mockBus.write(0x0015, (byte) 0b01010101);
        mockBus.write(0x0016, (byte) 0b01010101);
        mockBus.write(0x0017, (byte) 0b01010101);
        
        // Pattern high byte
        mockBus.write(0x0018, (byte) 0b00000000);
        mockBus.write(0x0019, (byte) 0b00000000);
        mockBus.write(0x001A, (byte) 0b00000000);
        mockBus.write(0x001B, (byte) 0b00000000);
        mockBus.write(0x001C, (byte) 0b00000000);
        mockBus.write(0x001D, (byte) 0b00000000);
        mockBus.write(0x001E, (byte) 0b00000000);
        mockBus.write(0x001F, (byte) 0b00000000);
        
        // Set up palette - palette 0, color 1 = white-ish
        ppu.cpuBusWrite(0x2006, (byte) 0x3F);
        ppu.cpuBusWrite(0x2006, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x0F);  // Background color
        ppu.cpuBusWrite(0x2007, (byte) 0x30);  // Palette 0, color 1
        ppu.cpuBusWrite(0x2007, (byte) 0x20);  // Palette 0, color 2
        ppu.cpuBusWrite(0x2007, (byte) 0x10);  // Palette 0, color 3
    }
    
    @Test
    void testPixelOutputToFramebuffer() {
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        setupSimpleTile();
        
        // Advance to scanline 0, cycle 10 (should have rendered some pixels)
        advanceToCycle(0, 10);
        
        // Check that screen buffer has been written to
        int[][] screen = ppu.getScreen();
        
        // At least one pixel should be non-zero
        boolean foundNonZero = false;
        for (int x = 0; x < 9; x++) {
            if (screen[0][x] != 0) {
                foundNonZero = true;
                break;
            }
        }
        
        assertTrue(foundNonZero, "Screen buffer should have non-zero pixels");
    }
    
    @Test
    void testBackgroundDisabledRendersBackdrop() {
        // Disable background rendering (PPUMASK bit 3 = 0)
        ppu.cpuBusWrite(0x2001, (byte) 0x00);
        
        setupSimpleTile();
        
        // Advance to scanline 0, cycle 10
        advanceToCycle(0, 10);
        
        int[][] screen = ppu.getScreen();
        
        // All pixels should be backdrop color (palette index 0)
        // The backdrop color is whatever is in palette RAM at index 0
        int expectedColor = screen[0][0];
        
        for (int x = 0; x < 9; x++) {
            assertEquals(expectedColor, screen[0][x], 
                "All pixels should be backdrop color when background disabled");
        }
    }
    
    @Test
    void testFullScanlineRendering() {
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        setupSimpleTile();
        
        // Render a full scanline (256 pixels)
        advanceToCycle(0, 257);
        
        int[][] screen = ppu.getScreen();
        
        // Check that all visible pixels have been rendered
        int nonZeroCount = 0;
        for (int x = 0; x < 256; x++) {
            if (screen[0][x] != 0) {
                nonZeroCount++;
            }
        }
        
        assertTrue(nonZeroCount > 200, 
            "Most pixels should be rendered after full scanline (found " + nonZeroCount + "/256)");
    }
    
    @Test
    void testMultipleScanlines() {
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        setupSimpleTile();
        
        // Render first 10 scanlines
        advanceToCycle(9, 257);
        
        int[][] screen = ppu.getScreen();
        
        // Check that multiple scanlines have been rendered
        int renderedScanlines = 0;
        for (int y = 0; y < 10; y++) {
            boolean hasPixels = false;
            for (int x = 0; x < 256; x++) {
                if (screen[y][x] != 0) {
                    hasPixels = true;
                    break;
                }
            }
            if (hasPixels) {
                renderedScanlines++;
            }
        }
        
        assertTrue(renderedScanlines >= 9, 
            "Should have rendered at least 9 scanlines (found " + renderedScanlines + ")");
    }
    
    @Test
    void testColorPaletteMapping() {
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        // Set up palette with distinct colors
        ppu.cpuBusWrite(0x2006, (byte) 0x3F);
        ppu.cpuBusWrite(0x2006, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x0F);  // Background color (white)
        ppu.cpuBusWrite(0x2007, (byte) 0x01);  // Palette 0, color 1 (dark blue)
        ppu.cpuBusWrite(0x2007, (byte) 0x02);  // Palette 0, color 2 (purple)
        ppu.cpuBusWrite(0x2007, (byte) 0x03);  // Palette 0, color 3 (blue)
        
        setupSimpleTile();
        
        // Render a few pixels
        advanceToCycle(0, 10);
        
        int[][] screen = ppu.getScreen();
        
        // Colors should be from NES palette, not zero
        boolean foundPaletteColor = false;
        for (int x = 0; x < 9; x++) {
            int color = screen[0][x];
            // NES colors are 0xAARRGGBB format, alpha should be 0xFF
            if ((color & 0xFF000000) == 0xFF000000) {
                foundPaletteColor = true;
                break;
            }
        }
        
        assertTrue(foundPaletteColor, "Should find colors from NES palette");
    }
    
    @Test
    void testPatternPixelExtraction() {
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        // Fill nametable with different tile indices
        for (int i = 0; i < 1024; i++) {
            mockBus.write(0x2000 + i, (byte) 0x05);
        }
        
        // Set up pattern table tile 5 with all pixels = color 1
        for (int i = 0; i < 8; i++) {
            mockBus.write(0x0050 + i, (byte) 0xFF);  // Low byte all 1s
            mockBus.write(0x0058 + i, (byte) 0x00);  // High byte all 0s
        }
        
        // Set up palette
        ppu.cpuBusWrite(0x2006, (byte) 0x3F);
        ppu.cpuBusWrite(0x2006, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x0F);
        ppu.cpuBusWrite(0x2007, (byte) 0x30);  // Color 1 should be visible
        
        // Render some pixels
        advanceToCycle(0, 20);
        
        int[][] screen = ppu.getScreen();
        
        // Should have rendered some non-background pixels
        int uniqueColors = 0;
        int firstColor = screen[0][0];
        for (int x = 1; x < 19; x++) {
            if (screen[0][x] != firstColor) {
                uniqueColors++;
            }
        }
        
        assertTrue(uniqueColors > 0 || firstColor != 0, 
            "Should have extracted pattern data from tiles");
    }
    
    @Test
    void testNoRenderingDuringVBlank() {
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        setupSimpleTile();
        
        // Advance to VBlank scanline (241)
        advanceToCycle(241, 1);
        
        int[][] screen = ppu.getScreen();
        
        // Capture current scanline 241 state
        int[] scanline241Before = new int[256];
        System.arraycopy(screen[241], 0, scanline241Before, 0, 256);
        
        // Clock through some VBlank cycles
        for (int i = 0; i < 100; i++) {
            ppu.clock();
        }
        
        // Scanline 241 should not change during VBlank
        boolean unchanged = true;
        for (int x = 0; x < 256; x++) {
            if (screen[241][x] != scanline241Before[x]) {
                unchanged = false;
                break;
            }
        }
        
        assertTrue(unchanged, "No rendering should occur during VBlank scanlines");
    }
    
    @Test
    void testPreRenderScanlineNoOutput() {
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        
        setupSimpleTile();
        
        // Advance to pre-render scanline (261)
        advanceToCycle(261, 1);
        
        int[][] screen = ppu.getScreen();
        
        // Capture pre-render scanline state
        int[] scanline261Before = new int[256];
        System.arraycopy(screen[261], 0, scanline261Before, 0, 256);
        
        // Clock through pre-render scanline
        for (int i = 0; i < 256; i++) {
            ppu.clock();
        }
        
        // Pre-render scanline should not have visible output
        // (it fetches tiles but doesn't render them)
        boolean unchanged = true;
        for (int x = 0; x < 256; x++) {
            if (screen[261][x] != scanline261Before[x]) {
                unchanged = false;
                break;
            }
        }
        
        assertTrue(unchanged, "Pre-render scanline should not output pixels to screen");
    }
    
    /**
     * Mock PPU bus component for testing
     */
    private static class MockPPUBus implements PPUBusComponent {
        private byte[] memory = new byte[0x4000];  // 16KB address space
        private PPUBus ppuBus;
        
        @Override
        public int ppuBusRead(int address, boolean readOnly) {
            address = address & 0x3FFF;
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
    }
}
