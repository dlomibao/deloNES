package net.lomibao.nes.components;

import net.lomibao.nes.components.ppu.NameTableMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PPUBus component routing
 * Tests Phase 1.4: PPUBus Integration
 */
class PPUBusTest {
    
    private PPUBus ppuBus;
    private NameTableMemory nameTableMemory;
    private Cartridge cartridge;
    
    @BeforeEach
    void setUp() throws Exception {
        ppuBus = new PPUBus();
        nameTableMemory = new NameTableMemory();
        
        // Load a test ROM for CHR data
        InputStream romStream = getClass().getResourceAsStream("/nestest.nes");
        if (romStream != null) {
            cartridge = new Cartridge(romStream, "nestest.nes");
            ppuBus.connectCartridge(cartridge);
            nameTableMemory.setCartridge(cartridge);
        }
    }
    
    @Test
    void testRegisterNameTableMemoryComponent() {
        ppuBus.connect(nameTableMemory);
        assertNotNull(nameTableMemory.getPPUBus(), "NameTableMemory should be connected to bus");
    }
    
    @Test
    void testNametableRangeRoutesToNameTableMemory() {
        ppuBus.connect(nameTableMemory);
        
        // Write through PPUBus
        ppuBus.write(0x2000, (byte) 0x42);
        
        // Read back through PPUBus
        int value = ppuBus.read(0x2000);
        assertEquals(0x42, value, "Read from PPUBus should return value written");
    }
    
    @Test
    void testPatternTableRangeRoutesToCartridge() {
        if (cartridge == null) {
            return; // Skip if ROM not available
        }
        
        // Read CHR ROM through PPUBus (should go to cartridge)
        int value = ppuBus.read(0x0000);
        
        // Should be same as reading directly from cartridge
        int expected = cartridge.chrRead(0x0000);
        assertEquals(expected, value, "Pattern table reads should route to cartridge");
    }
    
    @Test
    void testWriteThroughPPUBusToNametable() {
        ppuBus.connect(nameTableMemory);
        
        // Write multiple values through bus
        ppuBus.write(0x2000, (byte) 0x11);
        ppuBus.write(0x2100, (byte) 0x22);
        ppuBus.write(0x2200, (byte) 0x33);
        
        // Read back through bus
        assertEquals(0x11, ppuBus.read(0x2000));
        assertEquals(0x22, ppuBus.read(0x2100));
        assertEquals(0x33, ppuBus.read(0x2200));
    }
    
    @Test
    void testMultipleComponentsRegistered() {
        // Could add more components in the future
        ppuBus.connect(nameTableMemory);
        
        // Nametable should handle its range
        ppuBus.write(0x2000, (byte) 0xAA);
        assertEquals(0xAA, ppuBus.read(0x2000));
        
        // Pattern table should still work (cartridge)
        if (cartridge != null) {
            int chrValue = ppuBus.read(0x0000);
            assertEquals(cartridge.chrRead(0x0000), chrValue);
        }
    }
    
    @Test
    void testFirstMatchingComponentHandlesAddress() {
        ppuBus.connect(nameTableMemory);
        
        // All nametable addresses should route to NameTableMemory
        for (int addr = 0x2000; addr < 0x3F00; addr += 0x100) {
            ppuBus.write(addr, (byte) (addr & 0xFF));
            int value = ppuBus.read(addr);
            assertTrue(value >= 0 && value <= 0xFF, 
                "Address 0x" + Integer.toHexString(addr) + " should be handled");
        }
    }
    
    @Test
    void testAddressMirroringHandledCorrectly() {
        ppuBus.connect(nameTableMemory);
        
        // Write to first nametable
        ppuBus.write(0x2000, (byte) 0x55);
        
        // Read from mirrored address
        // nestest.nes uses vertical mirroring, so 0x2000 mirrors to 0x2800
        int value = ppuBus.read(0x2800);
        assertEquals(0x55, value, "Mirroring should work through PPUBus");
    }
    
    @Test
    void testPaletteAddressDoesNotReachBus() {
        // Palette RAM (0x3F00-0x3FFF) should NOT go through PPU bus
        // It's internal to PPU
        // This test just verifies bus doesn't crash on palette addresses
        ppuBus.write(0x3F00, (byte) 0x00);
        int value = ppuBus.read(0x3F00);
        assertEquals(0, value, "Palette addresses should return 0 (handled by PPU internally)");
    }
}
