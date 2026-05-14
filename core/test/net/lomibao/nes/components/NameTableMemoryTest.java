package net.lomibao.nes.components;

import net.lomibao.nes.components.ppu.MirroringMode;
import net.lomibao.nes.components.ppu.NameTableMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NameTableMemory
 * Tests Phase 1: Memory Infrastructure
 */
class NameTableMemoryTest {
    
    private NameTableMemory nameTableMemory;
    private PPUBus ppuBus;
    
    @BeforeEach
    void setUp() {
        nameTableMemory = new NameTableMemory();
        ppuBus = new PPUBus();
        nameTableMemory.connectPPUBus(ppuBus);
    }
    
    // Phase 1.1 Tests: Initialization
    
    @Test
    void testVRAMInitializesToCorrectSize() {
        byte[] vram = nameTableMemory.getVRAM();
        assertEquals(2048, vram.length, "VRAM should be 2048 bytes (2KB)");
    }
    
    @Test
    void testVRAMInitializesToZeros() {
        byte[] vram = nameTableMemory.getVRAM();
        for (int i = 0; i < vram.length; i++) {
            assertEquals(0, vram[i], "VRAM byte " + i + " should initialize to 0");
        }
    }
    
    @Test
    void testMirroringModeEnumExists() {
        // Verify all mirroring modes exist
        assertNotNull(MirroringMode.HORIZONTAL);
        assertNotNull(MirroringMode.VERTICAL);
        assertNotNull(MirroringMode.SINGLE_SCREEN_LO);
        assertNotNull(MirroringMode.SINGLE_SCREEN_HI);
        assertNotNull(MirroringMode.FOUR_SCREEN);
    }
    
    // Phase 1.2 Tests: Read/Write Methods
    
    @Test
    void testWriteToAddress0x2000() {
        nameTableMemory.ppuBusWrite(0x2000, (byte) 0x42);
        int value = nameTableMemory.ppuBusRead(0x2000, false);
        assertEquals(0x42, value, "Should read back same value written to 0x2000");
    }
    
    @Test
    void testWriteToAddress0x2400() {
        nameTableMemory.ppuBusWrite(0x2400, (byte) 0x84);
        int value = nameTableMemory.ppuBusRead(0x2400, false);
        assertEquals(0x84, value, "Should read back same value written to 0x2400");
    }
    
    @Test
    void testReadReturnsSameValueWritten() {
        nameTableMemory.ppuBusWrite(0x2100, (byte) 0xAB);
        int value = nameTableMemory.ppuBusRead(0x2100, false);
        assertEquals(0xAB, value, "Read should return same value as written");
    }
    
    @Test
    void testOutOfRangeAddressReturnsZero() {
        int value = nameTableMemory.ppuBusRead(0x1FFF, false); // Below range
        assertEquals(0, value, "Address below range should return 0");
        
        value = nameTableMemory.ppuBusRead(0x3F00, false); // Above range
        assertEquals(0, value, "Address above range should return 0");
    }
    
    @Test
    void testMultipleWritesReadsDoNotInterfere() {
        nameTableMemory.ppuBusWrite(0x2000, (byte) 0x11);
        nameTableMemory.ppuBusWrite(0x2100, (byte) 0x22);
        nameTableMemory.ppuBusWrite(0x2200, (byte) 0x33);
        
        assertEquals(0x11, nameTableMemory.ppuBusRead(0x2000, false));
        assertEquals(0x22, nameTableMemory.ppuBusRead(0x2100, false));
        assertEquals(0x33, nameTableMemory.ppuBusRead(0x2200, false));
    }
    
    // Phase 1.3 Tests: Horizontal Mirroring
    
    @Test
    void testHorizontalMirroring_0x2000_mirrors_to_0x2400() {
        // Without cartridge, defaults to horizontal mirroring
        nameTableMemory.ppuBusWrite(0x2000, (byte) 0x55);
        int value = nameTableMemory.ppuBusRead(0x2400, false);
        assertEquals(0x55, value, "0x2400 should mirror 0x2000 in horizontal mirroring");
    }
    
    @Test
    void testHorizontalMirroring_0x2800_mirrors_to_0x2C00() {
        nameTableMemory.ppuBusWrite(0x2800, (byte) 0x66);
        int value = nameTableMemory.ppuBusRead(0x2C00, false);
        assertEquals(0x66, value, "0x2C00 should mirror 0x2800 in horizontal mirroring");
    }
    
    @Test
    void testHorizontalMirroring_0x2000_and_0x2800_different() {
        nameTableMemory.ppuBusWrite(0x2000, (byte) 0x77);
        nameTableMemory.ppuBusWrite(0x2800, (byte) 0x88);
        
        assertEquals(0x77, nameTableMemory.ppuBusRead(0x2000, false));
        assertEquals(0x88, nameTableMemory.ppuBusRead(0x2800, false));
        assertNotEquals(
            nameTableMemory.ppuBusRead(0x2000, false),
            nameTableMemory.ppuBusRead(0x2800, false),
            "0x2000 and 0x2800 should be different in horizontal mirroring"
        );
    }
    
    @Test
    void testAttributeTableRead() {
        // Attribute table is at 0x23C0-0x23FF
        nameTableMemory.ppuBusWrite(0x23C0, (byte) 0xAA);
        int value = nameTableMemory.ppuBusRead(0x23C0, false);
        assertEquals(0xAA, value, "Should read attribute table byte");
    }
    
    @Test
    void testAttributeTableMirroring() {
        // Attribute table in first nametable
        nameTableMemory.ppuBusWrite(0x23C0, (byte) 0xBB);
        // Should mirror to second nametable's attribute table
        int value = nameTableMemory.ppuBusRead(0x27C0, false);
        assertEquals(0xBB, value, "Attribute tables should mirror with horizontal mirroring");
    }
    
    // Phase 1.4 Tests: Integration with PPUBus (tested in PPUBusTest)
    
    @Test
    void testAddressRangeCorrect() {
        assertEquals(0x2000, nameTableMemory.getPPUBusStartAddress());
        assertEquals(0x3F00, nameTableMemory.getPPUBusEndAddress());
    }
    
    @Test
    void testInRange() {
        assertTrue(nameTableMemory.inPPUusRange(0x2000), "0x2000 should be in range");
        assertTrue(nameTableMemory.inPPUusRange(0x2FFF), "0x2FFF should be in range");
        assertFalse(nameTableMemory.inPPUusRange(0x1FFF), "0x1FFF should be out of range");
        assertFalse(nameTableMemory.inPPUusRange(0x3F00), "0x3F00 should be out of range");
    }
    
    // Phase 1.5 Tests: End-to-End (tested in PPUIntegrationTest)
    
    @Test
    void testNametablePersistence() {
        // Write multiple values
        for (int i = 0; i < 256; i++) {
            nameTableMemory.ppuBusWrite(0x2000 + i, (byte) i);
        }
        
        // Verify all values persist
        for (int i = 0; i < 256; i++) {
            assertEquals(i, nameTableMemory.ppuBusRead(0x2000 + i, false),
                "Value at index " + i + " should persist");
        }
    }
    
    @Test
    void testFullNametableWrite() {
        // Write entire nametable (960 bytes of tile indices)
        for (int i = 0; i < 960; i++) {
            nameTableMemory.ppuBusWrite(0x2000 + i, (byte) (i & 0xFF));
        }
        
        // Verify all values
        for (int i = 0; i < 960; i++) {
            int expected = i & 0xFF;
            int actual = nameTableMemory.ppuBusRead(0x2000 + i, false);
            assertEquals(expected, actual, "Nametable byte " + i + " should match");
        }
    }
    
    @Test
    void testAttributeTableWrite() {
        // Write entire attribute table (64 bytes)
        for (int i = 0; i < 64; i++) {
            nameTableMemory.ppuBusWrite(0x23C0 + i, (byte) (i * 4));
        }
        
        // Verify all values
        for (int i = 0; i < 64; i++) {
            int expected = (i * 4) & 0xFF;
            int actual = nameTableMemory.ppuBusRead(0x23C0 + i, false);
            assertEquals(expected, actual, "Attribute byte " + i + " should match");
        }
    }
}
