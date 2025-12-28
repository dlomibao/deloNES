package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TileDecoderTest {
    
    private byte[] mockCHRData;
    
    @BeforeEach
    void setUp() {
        // Create mock CHR ROM with 8KB of data
        mockCHRData = new byte[0x2000];
        
        // Fill with some test data
        for (int i = 0; i < mockCHRData.length; i++) {
            mockCHRData[i] = (byte) (i & 0xFF);
        }
    }
    
    @Test
    void testDecodeTile_ValidIndex() {
        // Tile 0 should decode successfully
        byte[][] tile = TileDecoder.decodeTile(mockCHRData, 0);
        
        assertNotNull(tile, "Tile should not be null for valid index");
        assertEquals(8, tile.length, "Tile should have 8 rows");
        assertEquals(8, tile[0].length, "Each row should have 8 columns");
    }
    
    @Test
    void testDecodeTile_PixelColorRange() {
        // All pixels should be 0-3 (2-bit colors)
        byte[][] tile = TileDecoder.decodeTile(mockCHRData, 0);
        
        assertNotNull(tile);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                byte color = tile[y][x];
                assertTrue(color >= 0 && color <= 3, 
                    "Pixel color should be 0-3, got: " + color);
            }
        }
    }
    
    @Test
    void testDecodeTile_InvalidIndexNegative() {
        byte[][] tile = TileDecoder.decodeTile(mockCHRData, -1);
        assertNull(tile, "Should return null for negative tile index");
    }
    
    @Test
    void testDecodeTile_InvalidIndexTooLarge() {
        byte[][] tile = TileDecoder.decodeTile(mockCHRData, 512);
        assertNull(tile, "Should return null for tile index >= 512");
    }
    
    @Test
    void testDecodeTile_NullData() {
        byte[][] tile = TileDecoder.decodeTile(null, 0);
        assertNull(tile, "Should return null for null CHR data");
    }
    
    @Test
    void testDecodeTile_AllZeros() {
        byte[] zeroCHR = new byte[0x2000];
        byte[][] tile = TileDecoder.decodeTile(zeroCHR, 0);
        
        assertNotNull(tile);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(0, tile[y][x], "All pixels should be 0");
            }
        }
    }
    
    @Test
    void testDecodeTile_AllOnes() {
        byte[] oneCHR = new byte[0x2000];
        for (int i = 0; i < oneCHR.length; i++) {
            oneCHR[i] = (byte) 0xFF;
        }
        
        byte[][] tile = TileDecoder.decodeTile(oneCHR, 0);
        assertNotNull(tile);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(3, tile[y][x], "All pixels should be 3 (both bits set)");
            }
        }
    }
    
    @Test
    void testDecodeTile_MixedBitplanes() {
        // Create CHR with specific pattern
        byte[] testCHR = new byte[0x2000];
        // LSB plane (bytes 0-7): 0xFF (all 1s)
        // MSB plane (bytes 8-15): 0x00 (all 0s)
        for (int i = 0; i < 8; i++) {
            testCHR[i] = (byte) 0xFF;
            testCHR[8 + i] = (byte) 0x00;
        }
        
        byte[][] tile = TileDecoder.decodeTile(testCHR, 0);
        assertNotNull(tile);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(1, tile[y][x], "Pixels should be 1 (LSB only)");
            }
        }
    }
    
    @Test
    void testDecodeCHRLayout_Dimensions() {
        byte[][] layout = TileDecoder.decodeCHRLayout(mockCHRData);
        
        assertNotNull(layout, "Layout should not be null");
        assertEquals(256, layout.length, "Layout should be 256 pixels tall");
        assertEquals(128, layout[0].length, "Layout should be 128 pixels wide");
    }
    
    @Test
    void testDecodeCHRLayout_PixelColorRange() {
        byte[][] layout = TileDecoder.decodeCHRLayout(mockCHRData);
        
        assertNotNull(layout);
        for (int y = 0; y < layout.length; y++) {
            for (int x = 0; x < layout[y].length; x++) {
                byte color = layout[y][x];
                assertTrue(color >= 0 && color <= 3, 
                    "Pixel color should be 0-3, got: " + color);
            }
        }
    }
    
    @Test
    void testDecodeCHRLayout_NullData() {
        byte[][] layout = TileDecoder.decodeCHRLayout(null);
        assertNull(layout, "Should return null for null CHR data");
    }
    
    @Test
    void testDecodePatternTable_Dimensions() {
        byte[][] table0 = TileDecoder.decodePatternTable(mockCHRData, 0);
        byte[][] table1 = TileDecoder.decodePatternTable(mockCHRData, 1);
        
        assertNotNull(table0);
        assertNotNull(table1);
        assertEquals(128, table0.length, "Table should be 128 pixels tall");
        assertEquals(128, table0[0].length, "Table should be 128 pixels wide");
        assertEquals(128, table1.length, "Table should be 128 pixels tall");
        assertEquals(128, table1[0].length, "Table should be 128 pixels wide");
    }
    
    @Test
    void testDecodePatternTable_PixelColorRange() {
        byte[][] table = TileDecoder.decodePatternTable(mockCHRData, 0);
        
        assertNotNull(table);
        for (int y = 0; y < table.length; y++) {
            for (int x = 0; x < table[y].length; x++) {
                byte color = table[y][x];
                assertTrue(color >= 0 && color <= 3,
                    "Pixel color should be 0-3, got: " + color);
            }
        }
    }
    
    @Test
    void testDecodePatternTable_InvalidTable() {
        byte[][] table = TileDecoder.decodePatternTable(mockCHRData, 2);
        assertNull(table, "Should return null for invalid pattern table");
    }
    
    @Test
    void testDecodePatternTable_NegativeTable() {
        byte[][] table = TileDecoder.decodePatternTable(mockCHRData, -1);
        assertNull(table, "Should return null for negative pattern table");
    }
    
    @Test
    void testPixelsToDebugString_NotNull() {
        byte[][] pixels = new byte[8][8];
        String debug = TileDecoder.pixelsToDebugString(pixels);
        
        assertNotNull(debug, "Debug string should not be null");
        assertFalse(debug.isEmpty(), "Debug string should not be empty");
    }
    
    @Test
    void testPixelsToDebugString_NullInput() {
        String debug = TileDecoder.pixelsToDebugString(null);
        assertNotNull(debug);
        assertTrue(debug.contains("null"), "Should handle null input gracefully");
    }
    
    @Test
    void testPixelsToDebugString_EmptyArray() {
        String debug = TileDecoder.pixelsToDebugString(new byte[0][]);
        assertNotNull(debug);
        assertTrue(debug.contains("empty"), "Should handle empty array gracefully");
    }
    
    @Test
    void testTileToDebugString_ValidTile() {
        byte[][] tile = new byte[8][8];
        tile[0][0] = 0;
        tile[0][1] = 1;
        tile[0][2] = 2;
        tile[0][3] = 3;
        
        String debug = TileDecoder.tileToDebugString(tile);
        assertNotNull(debug);
        assertFalse(debug.isEmpty());
    }
    
    @Test
    void testTileToDebugString_InvalidTile() {
        String debug = TileDecoder.tileToDebugString(null);
        assertNotNull(debug);
        assertTrue(debug.contains("invalid"), "Should handle invalid tile");
    }
    
    @Test
    void testTileToDebugString_WrongDimensions() {
        byte[][] tile = new byte[7][8]; // Wrong height
        String debug = TileDecoder.tileToDebugString(tile);
        assertNotNull(debug);
        assertTrue(debug.contains("invalid"), "Should handle wrong dimensions");
    }
    
    @Test
    void testGetTile() {
        byte[][] tile = TileDecoder.getTile(mockCHRData, 0);
        
        assertNotNull(tile);
        assertEquals(8, tile.length);
        assertEquals(8, tile[0].length);
    }
    
    @Test
    void testBitplaneExtraction_FirstRow() {
        // Create specific test data for first tile
        byte[] testCHR = new byte[0x2000];
        // Set first row LSB to 0b10101010
        testCHR[0] = (byte) 0xAA;
        // Set first row MSB to 0b01010101
        testCHR[8] = (byte) 0x55;
        
        byte[][] tile = TileDecoder.decodeTile(testCHR, 0);
        assertNotNull(tile);
        
        // Expected: LSB=1,0,1,0,1,0,1,0 and MSB=0,1,0,1,0,1,0,1
        // Combined (MSB<<1|LSB): 1,2,1,2,1,2,1,2
        int[] expected = {1, 2, 1, 2, 1, 2, 1, 2};
        for (int x = 0; x < 8; x++) {
            assertEquals(expected[x], tile[0][x], 
                "Pixel " + x + " should match bitplane extraction");
        }
    }
    
    @Test
    void testTileIndexToByteOffset() {
        // Tile 0 should start at byte 0
        byte[][] tile0 = TileDecoder.decodeTile(mockCHRData, 0);
        assertNotNull(tile0);
        
        // Tile 1 should start at byte 16 (1 * 16)
        byte[][] tile1 = TileDecoder.decodeTile(mockCHRData, 1);
        assertNotNull(tile1);
        
        // Tile 256 should start at byte 0x1000 (256 * 16)
        byte[][] tile256 = TileDecoder.decodeTile(mockCHRData, 256);
        assertNotNull(tile256);
    }
    
    @Test
    void testMultipleDecodesConsistent() {
        // Decoding the same tile twice should produce identical results
        byte[][] tile1a = TileDecoder.decodeTile(mockCHRData, 5);
        byte[][] tile1b = TileDecoder.decodeTile(mockCHRData, 5);
        
        assertNotNull(tile1a);
        assertNotNull(tile1b);
        
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(tile1a[y][x], tile1b[y][x], 
                    "Decoding should be consistent");
            }
        }
    }
    
    @Test
    void testCHRLayoutTileArrangement() {
        // Verify that the layout arranges tiles correctly
        byte[][] layout = TileDecoder.decodeCHRLayout(mockCHRData);
        
        // Get individual tiles and verify they match layout
        byte[][] tile0 = TileDecoder.decodeTile(mockCHRData, 0);
        
        // First tile should be at layout[0:8][0:8]
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(tile0[y][x], layout[y][x], 
                    "Tile 0 should be in layout corner");
            }
        }
    }
}
