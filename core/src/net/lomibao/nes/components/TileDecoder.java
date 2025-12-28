package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

/**
 * Utility class for decoding NES CHR ROM tiles into pixel arrays.
 * 
 * NES tiles are stored in a specific format in CHR ROM:
 * - Each tile is 8x8 pixels with 2-bit color (0-3)
 * - Each tile occupies 16 bytes in CHR ROM
 * - Two bitplanes: LSB (bytes 0-7) and MSB (bytes 8-15)
 * 
 * CHR ROM layout (8KB):
 * - Pattern Table 0: 0x0000-0x0FFF (256 tiles, indices 0-255)
 * - Pattern Table 1: 0x1000-0x1FFF (256 tiles, indices 256-511)
 */
@Log4j2
public class TileDecoder {
    public static final int TILE_SIZE_BYTES = 16;  // 16 bytes per tile
    public static final int TILE_SIZE_PIXELS = 8;  // 8x8 pixel tile
    public static final int TILES_PER_TABLE = 256; // 256 tiles per pattern table
    public static final int PATTERN_TABLE_SIZE = 0x1000; // 4KB per table
    public static final int CHR_ROM_SIZE = 0x2000; // 8KB total (2 pattern tables)
    
    /**
     * Extracts a single 8x8 tile from CHR ROM data.
     * 
     * @param chrData raw CHR ROM byte array
     * @param tileIndex index of tile (0-511, where 0-255 is table 0, 256-511 is table 1)
     * @return byte[8][8] where each element is a 2-bit color value (0-3),
     *         or null if tile index is invalid
     */
    public static byte[][] decodeTile(byte[] chrData, int tileIndex) {
        if (chrData == null) {
            log.warn("CHR data is null");
            return null;
        }
        
        if (tileIndex < 0 || tileIndex >= TILES_PER_TABLE * 2) {
            log.warn("Invalid tile index: {}. Valid range: 0-511", tileIndex);
            return null;
        }
        
        // Calculate byte offset in CHR ROM
        int byteOffset = tileIndex * TILE_SIZE_BYTES;
        
        if (byteOffset + TILE_SIZE_BYTES > chrData.length) {
            log.warn("Tile index {} extends beyond CHR ROM size {}", tileIndex, chrData.length);
            return null;
        }
        
        byte[][] tile = new byte[TILE_SIZE_PIXELS][TILE_SIZE_PIXELS];
        
        // Extract each row of the tile
        for (int row = 0; row < TILE_SIZE_PIXELS; row++) {
            // LSB bitplane is at bytes 0-7
            byte lsb = chrData[byteOffset + row];
            // MSB bitplane is at bytes 8-15
            byte msb = chrData[byteOffset + TILE_SIZE_PIXELS + row];
            
            // Extract each column (pixel) in this row
            for (int col = 0; col < TILE_SIZE_PIXELS; col++) {
                // Pixels are stored from MSB to LSB (left to right)
                int bitPosition = 7 - col;
                int lsbBit = (lsb >> bitPosition) & 1;
                int msbBit = (msb >> bitPosition) & 1;
                
                // Combine bits: MSB is high order, LSB is low order
                int colorValue = (msbBit << 1) | lsbBit;
                tile[row][col] = (byte) colorValue;
            }
        }
        
        return tile;
    }
    
    /**
     * Decodes entire CHR ROM into a 2D pixel layout.
     * Arranges all tiles in a grid: 16 tiles per row, 32 rows total.
     * 
     * @param chrData raw CHR ROM byte array
     * @return byte[256][128] pixel grid where [y][x] contains 2-bit color (0-3)
     *         Dimensions: 256 pixels tall (32 tiles × 8 pixels), 128 pixels wide (16 tiles × 8 pixels)
     */
    public static byte[][] decodeCHRLayout(byte[] chrData) {
        if (chrData == null) {
            log.warn("CHR data is null");
            return null;
        }
        
        // Layout: 32 tile rows × 16 tile columns = 256 pixels tall × 128 pixels wide
        final int TILES_PER_ROW = 16;
        final int TILE_ROWS = 32;
        final int LAYOUT_HEIGHT = TILE_ROWS * TILE_SIZE_PIXELS;
        final int LAYOUT_WIDTH = TILES_PER_ROW * TILE_SIZE_PIXELS;
        
        byte[][] layout = new byte[LAYOUT_HEIGHT][LAYOUT_WIDTH];
        
        // Process each tile
        for (int tileRow = 0; tileRow < TILE_ROWS; tileRow++) {
            for (int tileCol = 0; tileCol < TILES_PER_ROW; tileCol++) {
                int tileIndex = tileRow * TILES_PER_ROW + tileCol;
                byte[][] tile = decodeTile(chrData, tileIndex);
                
                if (tile != null) {
                    // Place tile pixels in layout
                    int startY = tileRow * TILE_SIZE_PIXELS;
                    int startX = tileCol * TILE_SIZE_PIXELS;
                    
                    for (int row = 0; row < TILE_SIZE_PIXELS; row++) {
                        for (int col = 0; col < TILE_SIZE_PIXELS; col++) {
                            layout[startY + row][startX + col] = tile[row][col];
                        }
                    }
                }
            }
        }
        
        return layout;
    }
    
    /**
     * Decodes a single pattern table (256 tiles).
     * 
     * @param chrData raw CHR ROM byte array
     * @param patternTable 0 or 1 (pattern table index)
     * @return byte[128][128] pixel grid where [y][x] contains 2-bit color (0-3)
     *         Dimensions: 128 pixels × 128 pixels (16 tiles × 16 tiles)
     */
    public static byte[][] decodePatternTable(byte[] chrData, int patternTable) {
        if (chrData == null) {
            log.warn("CHR data is null");
            return null;
        }
        
        if (patternTable < 0 || patternTable > 1) {
            log.warn("Invalid pattern table: {}. Valid values: 0 or 1", patternTable);
            return null;
        }
        
        final int TILES_PER_SIDE = 16;
        final int TABLE_PIXELS = TILES_PER_SIDE * TILE_SIZE_PIXELS;
        
        byte[][] table = new byte[TABLE_PIXELS][TABLE_PIXELS];
        
        int baseTableIndex = patternTable * TILES_PER_TABLE;
        
        // Process each tile in the pattern table
        for (int tileRow = 0; tileRow < TILES_PER_SIDE; tileRow++) {
            for (int tileCol = 0; tileCol < TILES_PER_SIDE; tileCol++) {
                int tileIndex = baseTableIndex + tileRow * TILES_PER_SIDE + tileCol;
                byte[][] tile = decodeTile(chrData, tileIndex);
                
                if (tile != null) {
                    int startY = tileRow * TILE_SIZE_PIXELS;
                    int startX = tileCol * TILE_SIZE_PIXELS;
                    
                    for (int row = 0; row < TILE_SIZE_PIXELS; row++) {
                        for (int col = 0; col < TILE_SIZE_PIXELS; col++) {
                            table[startY + row][startX + col] = tile[row][col];
                        }
                    }
                }
            }
        }
        
        return table;
    }
    
    /**
     * Converts a 2D pixel array to a debug string representation.
     * Uses ASCII characters to visualize the 2-bit color values.
     * 
     * @param pixels byte[y][x] array with 2-bit color values (0-3)
     * @return multi-line string with ASCII representation
     */
    public static String pixelsToDebugString(byte[][] pixels) {
        if (pixels == null || pixels.length == 0) {
            return "null or empty pixel array";
        }
        
        // ASCII characters for 2-bit colors: 0=space, 1=light, 2=medium, 3=dark
        char[] colorChars = {' ', '░', '▒', '█'};
        
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < pixels.length; y++) {
            for (int x = 0; x < pixels[y].length; x++) {
                byte colorValue = pixels[y][x];
                if (colorValue < 0 || colorValue > 3) {
                    sb.append('?');
                } else {
                    sb.append(colorChars[colorValue]);
                }
            }
            sb.append('\n');
        }
        
        return sb.toString();
    }
    
    /**
     * Converts a single tile to a debug string representation.
     * 
     * @param tile byte[8][8] tile pixels with 2-bit colors
     * @return multi-line string showing the tile
     */
    public static String tileToDebugString(byte[][] tile) {
        if (tile == null || tile.length != TILE_SIZE_PIXELS || tile[0].length != TILE_SIZE_PIXELS) {
            return "invalid tile";
        }
        
        char[] colorChars = {' ', '░', '▒', '█'};
        StringBuilder sb = new StringBuilder();
        
        for (int y = 0; y < TILE_SIZE_PIXELS; y++) {
            for (int x = 0; x < TILE_SIZE_PIXELS; x++) {
                byte colorValue = tile[y][x];
                if (colorValue < 0 || colorValue > 3) {
                    sb.append('?');
                } else {
                    sb.append(colorChars[colorValue]);
                }
            }
            sb.append('\n');
        }
        
        return sb.toString();
    }
    
    /**
     * Gets a specific tile from the full CHR layout (0-511).
     * 
     * @param chrData raw CHR ROM byte array
     * @param tileIndex 0-511
     * @return byte[8][8] or null if invalid
     */
    public static byte[][] getTile(byte[] chrData, int tileIndex) {
        return decodeTile(chrData, tileIndex);
    }
}
