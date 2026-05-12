package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for PPU palette RAM and register functionality
 */
public class PPUTest {
    private PPU ppu;
    private CPUBus bus;
    
    @BeforeEach
    void setUp() {
        ppu = new PPU();
        bus = CPUBus.builder()
                .ppu(ppu)
                .build()
                .connect();
        ppu.reset();
    }
    
    @Test
    void testPPUInitialization() {
        assertNotNull(ppu);
        // All palette entries should be zero initially
        for (int i = 0; i < 32; i++) {
            assertEquals(0, ppu.getPaletteColor(i), "Palette index " + i + " should be 0");
        }
    }
    
    @Test
    void testPPUADDR_TwoWriteProtocol() {
        // Write high byte to PPUADDR (0x2006)
        bus.write(0x2006, (byte) 0x3F);
        
        // Write low byte to PPUADDR (0x2006)
        bus.write(0x2006, (byte) 0x10);
        
        // Write data to PPUDATA (0x2007) - should go to address 0x3F10
        bus.write(0x2007, (byte) 0x25);
        
        // 0x3F10 mirrors to 0x3F00 (background color mirror)
        assertEquals(0x25, ppu.getPaletteColor(0x00), "Palette at 0x00 should be 0x25 (mirrored from 0x10)");
    }
    
    @Test
    void testPaletteWrite() {
        // Set PPUADDR to 0x3F00 (start of palette RAM)
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        
        // Write palette data
        bus.write(0x2007, (byte) 0x0F); // Background color
        bus.write(0x2007, (byte) 0x30); // Color 1
        bus.write(0x2007, (byte) 0x16); // Color 2
        bus.write(0x2007, (byte) 0x27); // Color 3
        
        // Verify palette data was written
        assertEquals(0x0F, ppu.getPaletteColor(0));
        assertEquals(0x30, ppu.getPaletteColor(1));
        assertEquals(0x16, ppu.getPaletteColor(2));
        assertEquals(0x27, ppu.getPaletteColor(3));
    }
    
    @Test
    void testPaletteRead() {
        // Set PPUADDR to 0x3F00
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        
        // Write some data
        bus.write(0x2007, (byte) 0x12);
        bus.write(0x2007, (byte) 0x34);
        
        // Reset address to read it back
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        
        // Read palette data (palette reads are immediate, no buffering)
        int color1 = bus.read(0x2007);
        int color2 = bus.read(0x2007);
        
        assertEquals(0x12, color1);
        assertEquals(0x34, color2);
    }
    
    @Test
    void testPaletteMirroring_Every32Bytes() {
        // Write to 0x3F00
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        bus.write(0x2007, (byte) 0xAB);
        
        // Read from 0x3F20 (should mirror to 0x3F00)
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x20);
        int value = bus.read(0x2007);
        
        assertEquals(0xAB, value, "0x3F20 should mirror to 0x3F00");
    }
    
    @Test
    void testPaletteMirroring_BackgroundColors() {
        // Test special mirroring: 0x3F10 -> 0x3F00
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        bus.write(0x2007, (byte) 0x11);
        
        // Read from 0x3F10 (should return same value as 0x3F00)
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x10);
        int value = bus.read(0x2007);
        
        assertEquals(0x11, value, "0x3F10 should mirror to 0x3F00");
    }
    
    @Test
    void testPaletteMirroring_0x3F14() {
        // Test special mirroring: 0x3F14 -> 0x3F04
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x04);
        bus.write(0x2007, (byte) 0x22);
        
        // Read from 0x3F14
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x14);
        int value = bus.read(0x2007);
        
        assertEquals(0x22, value, "0x3F14 should mirror to 0x3F04");
    }
    
    @Test
    void testPaletteMirroring_0x3F18() {
        // Test special mirroring: 0x3F18 -> 0x3F08
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x08);
        bus.write(0x2007, (byte) 0x33);
        
        // Read from 0x3F18
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x18);
        int value = bus.read(0x2007);
        
        assertEquals(0x33, value, "0x3F18 should mirror to 0x3F08");
    }
    
    @Test
    void testPaletteMirroring_0x3F1C() {
        // Test special mirroring: 0x3F1C -> 0x3F0C
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x0C);
        bus.write(0x2007, (byte) 0x44);
        
        // Read from 0x3F1C
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x1C);
        int value = bus.read(0x2007);
        
        assertEquals(0x44, value, "0x3F1C should mirror to 0x3F0C");
    }
    
    @Test
    void testAddressIncrement_Horizontal() {
        // PPUCTRL bit 2 = 0 means increment by 1 (horizontal)
        bus.write(0x2000, (byte) 0x00); // PPUCTRL = 0 (increment by 1)
        
        // Set address to 0x3F00
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        
        // Write multiple values
        bus.write(0x2007, (byte) 0x10);
        bus.write(0x2007, (byte) 0x20);
        bus.write(0x2007, (byte) 0x30);
        
        // Verify sequential writes
        assertEquals(0x10, ppu.getPaletteColor(0));
        assertEquals(0x20, ppu.getPaletteColor(1));
        assertEquals(0x30, ppu.getPaletteColor(2));
    }
    
    @Test
    void testAddressIncrement_Vertical() {
        // PPUCTRL bit 2 = 1 means increment by 32 (vertical)
        bus.write(0x2000, (byte) 0x04); // PPUCTRL bit 2 = 1 (increment by 32)
        
        // Set address to 0x3F00
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        
        bus.write(0x2007, (byte) 0xAA);
        
        // Address should now be 0x3F20, which mirrors to 0x3F00
        bus.write(0x2007, (byte) 0xBB);
        
        // Both should have written to the same location due to mirroring
        assertEquals(0xBB, ppu.getPaletteColor(0), "Second write should overwrite first due to mirroring");
    }
    
    @Test
    void testPPUSTATUS_ClearsVBlank() {
        // Set vblank flag (bit 7) in PPUSTATUS
        bus.write(0x2002, (byte) 0x80); // This write should be ignored
        
        // Manually set the status register for testing
        ppu.setRegisterForTest(2, (byte) 0x80);
        
        // Read PPUSTATUS
        int status = bus.read(0x2002);
        
        // Should return 0x80
        assertEquals(0x80, status);
        
        // Read again - vblank should be cleared
        status = bus.read(0x2002);
        assertEquals(0x00, status, "Second read should have vblank cleared");
    }
    
    @Test
    void testPPUSTATUS_ResetsAddressLatch() {
        // Write high byte to PPUADDR
        bus.write(0x2006, (byte) 0x3F);
        
        // Read PPUSTATUS (should reset the latch)
        bus.read(0x2002);
        
        // Write to PPUADDR again - should be treated as high byte again
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x05);
        
        // Write data
        bus.write(0x2007, (byte) 0x99);
        
        // Verify it went to the right place
        assertEquals(0x99, ppu.getPaletteColor(5));
    }
    
    @Test
    void testPPURegisterMirroring() {
        // Test that PPU registers mirror every 8 bytes
        
        // Write to 0x2000 (PPUCTRL)
        bus.write(0x2000, (byte) 0xAB);
        
        // Read from 0x2008 (should mirror to 0x2000)
        int value1 = ppu.getRegisterForTest(0);

        // Write to 0x2008
        bus.write(0x2008, (byte) 0xCD);

        // Check that 0x2000 was updated
        int value2 = ppu.getRegisterForTest(0);
        
        assertEquals(0xAB, value1 & 0xFF);
        assertEquals(0xCD, value2 & 0xFF);
    }
    
    @Test
    void testWriteFullPalette() {
        // Write complete palette (32 bytes)
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        
        for (int i = 0; i < 32; i++) {
            bus.write(0x2007, (byte) (i + 0x10));
        }
        
        // Verify entries. Due to mirroring, indices 0x10-0x1F write to 0x00-0x0F
        // with special mirroring for 0x10, 0x14, 0x18, 0x1C to 0x00, 0x04, 0x08, 0x0C
        
        // Check background colors that get overwritten by mirroring
        assertEquals(0x20, ppu.getPaletteColor(0x00), "0x00 overwritten by 0x10 write");
        assertEquals(0x24, ppu.getPaletteColor(0x04), "0x04 overwritten by 0x14 write");
        assertEquals(0x28, ppu.getPaletteColor(0x08), "0x08 overwritten by 0x18 write");
        assertEquals(0x2C, ppu.getPaletteColor(0x0C), "0x0C overwritten by 0x1C write");
        
        // Check non-mirrored entries (indices that don't have special mirroring)
        assertEquals(0x11, ppu.getPaletteColor(0x01));
        assertEquals(0x12, ppu.getPaletteColor(0x02));
        assertEquals(0x13, ppu.getPaletteColor(0x03));
        assertEquals(0x15, ppu.getPaletteColor(0x05));
        assertEquals(0x1E, ppu.getPaletteColor(0x0E));
        assertEquals(0x1F, ppu.getPaletteColor(0x0F));
    }
    
    @Test
    void testPaletteIndexBounds() {
        // Test that out-of-bounds palette access returns 0
        assertEquals(0, ppu.getPaletteColor(-1));
        assertEquals(0, ppu.getPaletteColor(32));
        assertEquals(0, ppu.getPaletteColor(100));
    }
    
    @Test
    void testReset() {
        // Write some data
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        bus.write(0x2007, (byte) 0xFF);
        bus.write(0x2000, (byte) 0xAA);
        
        // Reset PPU
        ppu.reset();
        
        // Verify registers are cleared
        for (int i = 0; i < 8; i++) {
            assertEquals(0, ppu.getRegisterForTest(i) & 0xFF, "Register " + i + " should be 0 after reset");
        }
        
        // Verify we can write to palette after reset
        bus.write(0x2006, (byte) 0x3F);
        bus.write(0x2006, (byte) 0x00);
        bus.write(0x2007, (byte) 0x12);
        
        assertEquals(0x12, ppu.getPaletteColor(0));
    }
}
