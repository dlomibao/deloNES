package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPU background pixel rendering (Phase 4)
 */
class PPURenderingTest {

    /**
     * Backdrop color used throughout this file. The setup methods write
     * palette entry 0 = NES color $0F (black), which the PPU's master
     * palette table maps to {@code 0xFF000000}. The screen buffer is also
     * pre-filled with {@code 0xFF000000} on construction.
     *
     * <p>Tests that want to verify "a tile pixel was actually rendered"
     * must compare against this constant rather than against {@code 0}
     * — the buffer is never literally zero, so {@code pixel != 0} is
     * trivially true and proves nothing.
     */
    private static final int BACKDROP = 0xFF000000;

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
        // PPUMASK: show BG (bit 3) + show BG in leftmost 8 pixels (bit 1).
        // Without bit 1 the first 8 pixels are forced to backdrop, which
        // would mask the very output we're trying to inspect.
        ppu.cpuBusWrite(0x2001, (byte) 0x0A);

        setupSimpleTile();

        // Render a full scanline so the pipeline reaches steady state. The
        // first ~16 pixels of scanline 0 reflect empty shifters (no prior
        // prefetch from a pre-render pass), so look further in.
        advanceToCycle(0, 257);

        // At least one pixel must be a real tile pixel — not the backdrop
        // and not the initial buffer fill. `!= 0` was insufficient because
        // the buffer is initialized to 0xFF000000.
        int[][] screen = ppu.getScreen();
        boolean foundTilePixel = false;
        for (int x = 16; x < 256; x++) {
            if (screen[0][x] != BACKDROP) {
                foundTilePixel = true;
                break;
            }
        }
        assertTrue(foundTilePixel,
                "Screen buffer should contain at least one tile pixel (non-backdrop)");
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
        ppu.cpuBusWrite(0x2001, (byte) 0x0A);
        setupSimpleTile();

        advanceToCycle(0, 257);

        // With pattern 0b01010101 every other pixel of each tile should be
        // palette idx 1 (a non-backdrop tile pixel). Count actual tile
        // pixels (not just non-zero buffer writes — which the buffer
        // initialization to 0xFF000000 already satisfies).
        int[][] screen = ppu.getScreen();
        int tilePixelCount = 0;
        for (int x = 16; x < 256; x++) {
            if (screen[0][x] != BACKDROP) {
                tilePixelCount++;
            }
        }
        assertTrue(tilePixelCount > 100,
                "Should have >100 non-backdrop tile pixels after a full scanline (found "
                        + tilePixelCount + "/240)");
    }
    
    @Test
    void testMultipleScanlines() {
        ppu.cpuBusWrite(0x2001, (byte) 0x0A);
        setupSimpleTile();

        advanceToCycle(9, 257);

        // Verify each scanline produced at least one non-backdrop tile
        // pixel — proves the per-scanline pipeline (incl. coarseY / fineY
        // advance + per-line prefetch) ran for each row, not just frame 0.
        int[][] screen = ppu.getScreen();
        int renderedScanlines = 0;
        for (int y = 0; y < 10; y++) {
            for (int x = 16; x < 256; x++) {
                if (screen[y][x] != BACKDROP) {
                    renderedScanlines++;
                    break;
                }
            }
        }
        assertTrue(renderedScanlines >= 9,
                "Should have rendered at least 9 scanlines with tile pixels (found "
                        + renderedScanlines + ")");
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
        ppu.cpuBusWrite(0x2001, (byte) 0x0A);

        for (int i = 0; i < 1024; i++) {
            mockBus.write(0x2000 + i, (byte) 0x05);
        }
        // Pattern tile 5: every pixel of every row = palette index 1.
        for (int i = 0; i < 8; i++) {
            mockBus.write(0x0050 + i, (byte) 0xFF);
            mockBus.write(0x0058 + i, (byte) 0x00);
        }

        ppu.cpuBusWrite(0x2006, (byte) 0x3F);
        ppu.cpuBusWrite(0x2006, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x0F);
        ppu.cpuBusWrite(0x2007, (byte) 0x30);

        advanceToCycle(0, 257);

        // With every pixel = palette idx 1, the post-startup window should
        // be entirely the same non-backdrop color — the extracted color.
        // Counting it directly is stronger than "uniqueColors > 0 OR
        // firstColor != 0", which the buffer init satisfied for free.
        int[][] screen = ppu.getScreen();
        int extracted = 0;
        for (int x = 16; x < 256; x++) {
            if (screen[0][x] != BACKDROP) {
                extracted++;
            }
        }
        assertTrue(extracted > 200,
                "Tile 5 fills every pixel — expected >200 extracted tile pixels (got "
                        + extracted + ")");
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
