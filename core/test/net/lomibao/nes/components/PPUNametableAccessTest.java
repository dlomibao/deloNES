package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PPU nametable access through CPU
 * Tests Phase 1.5: End-to-End CPU Access via PPUADDR/PPUDATA
 */
class PPUNametableAccessTest {
    
    private PPU ppu;
    private PPUBus ppuBus;
    private Cartridge cartridge;
    
    @BeforeEach
    void setUp() throws Exception {
        ppu = new PPU();
        ppuBus = new PPUBus();
        
        // Connect PPU to bus
        ppu.connectPPUBus(ppuBus);
        ppuBus.connectPPU(ppu);
        
        // Load test ROM
        InputStream romStream = getClass().getResourceAsStream("/nestest.nes");
        if (romStream != null) {
            cartridge = new Cartridge(romStream, "nestest.nes");
            ppu.setCartridge(cartridge);
            ppuBus.connectCartridge(cartridge);
        }
    }
    
    /**
     * Helper method to write to PPU via PPUADDR/PPUDATA (simulating CPU writes)
     */
    private void ppuWrite(int address, byte value) {
        // Write high byte of address to PPUADDR (0x2006)
        ppu.cpuBusWrite(0x2006, (byte) ((address >> 8) & 0xFF));
        // Write low byte of address to PPUADDR
        ppu.cpuBusWrite(0x2006, (byte) (address & 0xFF));
        // Write data to PPUDATA (0x2007)
        ppu.cpuBusWrite(0x2007, value);
    }
    
    /**
     * Helper method to read from PPU via PPUADDR/PPUDATA (simulating CPU reads)
     */
    private int ppuRead(int address) {
        // Write high byte of address to PPUADDR (0x2006)
        ppu.cpuBusWrite(0x2006, (byte) ((address >> 8) & 0xFF));
        // Write low byte of address to PPUADDR
        ppu.cpuBusWrite(0x2006, (byte) (address & 0xFF));
        // Read from PPUDATA (0x2007)
        // Note: First read is buffered, need dummy read for non-palette addresses
        ppu.cpuBusRead(0x2007, false); // Dummy read
        
        // Set address again for actual read
        ppu.cpuBusWrite(0x2006, (byte) ((address >> 8) & 0xFF));
        ppu.cpuBusWrite(0x2006, (byte) (address & 0xFF));
        ppu.cpuBusRead(0x2007, false); // Dummy read (fills buffer)
        return ppu.cpuBusRead(0x2007, false); // Actual read
    }
    
    @Test
    void testWriteTileIndexViaPPUDATA() {
        // Write a tile index to nametable
        ppuWrite(0x2000, (byte) 0x42);
        
        // Read it back
        int value = ppuRead(0x2000);
        assertEquals(0x42, value, "Should read back tile index written via PPUDATA");
    }
    
    @Test
    void testWriteEntireNametable() {
        // Write 960 bytes of tile indices (32x30 grid)
        for (int i = 0; i < 960; i++) {
            ppuWrite(0x2000 + i, (byte) (i & 0xFF));
        }
        
        // Verify all values
        for (int i = 0; i < 960; i++) {
            int expected = i & 0xFF;
            int actual = ppuRead(0x2000 + i);
            assertEquals(expected, actual, 
                "Nametable tile " + i + " should match (expected: 0x" + 
                Integer.toHexString(expected) + ", got: 0x" + Integer.toHexString(actual) + ")");
        }
    }
    
    @Test
    void testWriteAttributeTable() {
        // Write attribute table (64 bytes at 0x23C0-0x23FF)
        for (int i = 0; i < 64; i++) {
            ppuWrite(0x23C0 + i, (byte) (i * 4));
        }
        
        // Verify all values
        for (int i = 0; i < 64; i++) {
            int expected = (i * 4) & 0xFF;
            int actual = ppuRead(0x23C0 + i);
            assertEquals(expected, actual, "Attribute byte " + i + " should match");
        }
    }
    
    @Test
    void testAddressAutoIncrement() {
        // Set PPUCTRL to increment by 1 (default)
        ppu.cpuBusWrite(0x2000, (byte) 0x00); // PPUCTRL: increment by 1
        
        // Set address to 0x2000
        ppu.cpuBusWrite(0x2006, (byte) 0x20);
        ppu.cpuBusWrite(0x2006, (byte) 0x00);
        
        // Write 5 sequential values (address should auto-increment)
        ppu.cpuBusWrite(0x2007, (byte) 0x11);
        ppu.cpuBusWrite(0x2007, (byte) 0x22);
        ppu.cpuBusWrite(0x2007, (byte) 0x33);
        ppu.cpuBusWrite(0x2007, (byte) 0x44);
        ppu.cpuBusWrite(0x2007, (byte) 0x55);
        
        // Read back and verify
        assertEquals(0x11, ppuRead(0x2000));
        assertEquals(0x22, ppuRead(0x2001));
        assertEquals(0x33, ppuRead(0x2002));
        assertEquals(0x44, ppuRead(0x2003));
        assertEquals(0x55, ppuRead(0x2004));
    }
    
    @Test
    void testMultipleReadWriteCycles() {
        // Perform multiple write/read cycles
        for (int cycle = 0; cycle < 10; cycle++) {
            int address = 0x2000 + (cycle * 10);
            byte value = (byte) (cycle * 16);
            
            ppuWrite(address, value);
            int readValue = ppuRead(address);
            
            assertEquals(Byte.toUnsignedInt(value), readValue, 
                "Cycle " + cycle + " should write and read correctly");
        }
    }
    
    @Test
    void testWriteToDifferentNametables() {
        // Write to all four nametable addresses
        ppuWrite(0x2000, (byte) 0x11); // Nametable 0
        ppuWrite(0x2400, (byte) 0x22); // Nametable 1
        ppuWrite(0x2800, (byte) 0x33); // Nametable 2
        ppuWrite(0x2C00, (byte) 0x44); // Nametable 3
        
        // Read back
        int val0 = ppuRead(0x2000);
        int val1 = ppuRead(0x2400);
        int val2 = ppuRead(0x2800);
        int val3 = ppuRead(0x2C00);
        
        // nestest.nes uses vertical mirroring, so 0x2000 == 0x2800 and 0x2400 == 0x2C00
        assertEquals(val0, val2, "Nametables 0 and 2 should mirror (vertical)");
        assertEquals(val1, val3, "Nametables 1 and 3 should mirror (vertical)");
    }
    
    @Test
    void testNametableDataPersistence() {
        // Write pattern to nametable 0
        for (int i = 0; i < 100; i++) {
            ppuWrite(0x2000 + i, (byte) i);
        }
        
        // Write different data to nametable 1 (non-overlapping with vertical mirroring)
        for (int i = 0; i < 100; i++) {
            ppuWrite(0x2400 + i, (byte) (200 - i));
        }
        
        // Verify first pattern still intact at nametable 0
        for (int i = 0; i < 100; i++) {
            int value = ppuRead(0x2000 + i);
            assertEquals(i, value, "Original data at 0x" + 
                Integer.toHexString(0x2000 + i) + " should persist");
        }
        
        // Verify second pattern at nametable 1
        for (int i = 0; i < 100; i++) {
            int value = ppuRead(0x2400 + i);
            assertEquals((200 - i) & 0xFF, value, "Second pattern data should persist");
        }
    }
}
