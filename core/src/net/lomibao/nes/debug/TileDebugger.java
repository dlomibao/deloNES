package net.lomibao.nes.debug;

import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.TileDecoder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Debugging utilities for inspecting CHR ROM tile data.
 * Provides methods to export and visualize tile information.
 */
@Log4j2
public class TileDebugger {
    
    /**
     * Exports CHR ROM debug information to a text file.
     * Includes ASCII visualization and detailed tile information.
     * 
     * @param chrData raw CHR ROM byte array
     * @param outputPath file path to write debug info to
     */
    public static void exportCHRDebugInfo(byte[] chrData, String outputPath) {
        if (chrData == null) {
            log.error("CHR data is null");
            return;
        }
        
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outputPath),
                    StandardCharsets.UTF_8
                )
            )) {
            
            writer.println("=== NES CHR ROM Debug Information ===");
            writer.println("Generated: " + java.time.LocalDateTime.now());
            writer.println();
            
            writer.println("CHR ROM Size: " + chrData.length + " bytes");
            writer.println("Number of Banks: " + (chrData.length / 8192));
            writer.println("Number of Tiles: " + (chrData.length / 16));
            writer.println();
            
            // Export full layout visualization
            writer.println("=== FULL CHR LAYOUT ===");
            byte[][] layout = TileDecoder.decodeCHRLayout(chrData);
            if (layout != null) {
                writer.println(TileDecoder.pixelsToDebugString(layout));
            } else {
                writer.println("Failed to decode CHR layout");
            }
            
            writer.println();
            writer.println("=== PATTERN TABLE 0 ===");
            byte[][] table0 = TileDecoder.decodePatternTable(chrData, 0);
            if (table0 != null) {
                writer.println(TileDecoder.pixelsToDebugString(table0));
            }
            
            writer.println();
            writer.println("=== PATTERN TABLE 1 ===");
            byte[][] table1 = TileDecoder.decodePatternTable(chrData, 1);
            if (table1 != null) {
                writer.println(TileDecoder.pixelsToDebugString(table1));
            }
            
            writer.println();
            writer.println("=== INDIVIDUAL TILE SAMPLES ===");
            // Export first 16 tiles as samples
            for (int i = 0; i < Math.min(16, 512); i++) {
                byte[][] tile = TileDecoder.decodeTile(chrData, i);
                if (tile != null) {
                    writer.println("Tile " + i + ":");
                    writer.println(TileDecoder.tileToDebugString(tile));
                    writer.println();
                }
            }
            
            log.info("CHR debug info exported to: {}", outputPath);
            
        } catch (IOException e) {
            log.error("Error writing CHR debug info to file: {}", outputPath, e);
        }
    }
    
    /**
     * Exports CHR ROM tile statistics and hex dump.
     * 
     * @param chrData raw CHR ROM byte array
     * @param outputPath file path to write statistics to
     */
    public static void exportCHRStatistics(byte[] chrData, String outputPath) {
        if (chrData == null) {
            log.error("CHR data is null");
            return;
        }
        
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outputPath),
                    StandardCharsets.UTF_8
                )
            )) {
            
            writer.println("=== CHR ROM Statistics ===");
            writer.println("Total Size: " + chrData.length + " bytes");
            writer.println("Total Tiles: " + (chrData.length / 16));
            writer.println();
            
            // Byte frequency analysis
            writer.println("=== Byte Frequency ===");
            int[] byteFreq = new int[256];
            for (byte b : chrData) {
                byteFreq[b & 0xFF]++;
            }
            
            for (int i = 0; i < 256; i++) {
                if (byteFreq[i] > 0) {
                    writer.printf("0x%02X: %d occurrences (%.2f%%)%n", 
                        i, byteFreq[i], (100.0 * byteFreq[i] / chrData.length));
                }
            }
            
            writer.println();
            writer.println("=== First 512 bytes (hex dump) ===");
            exportHexDump(chrData, 0, Math.min(512, chrData.length), writer);
            
            log.info("CHR statistics exported to: {}", outputPath);
            
        } catch (IOException e) {
            log.error("Error writing CHR statistics to file: {}", outputPath, e);
        }
    }
    
    /**
     * Exports a specific pattern table to a detailed text file.
     * 
     * @param chrData raw CHR ROM byte array
     * @param patternTable 0 or 1
     * @param outputPath file path to write pattern table info to
     */
    public static void exportPatternTable(byte[] chrData, int patternTable, String outputPath) {
        if (chrData == null) {
            log.error("CHR data is null");
            return;
        }
        
        if (patternTable < 0 || patternTable > 1) {
            log.error("Invalid pattern table: {}. Valid values: 0 or 1", patternTable);
            return;
        }
        
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outputPath),
                    StandardCharsets.UTF_8
                )
            )) {
            
            writer.println("=== Pattern Table " + patternTable + " ===");
            byte[][] table = TileDecoder.decodePatternTable(chrData, patternTable);
            if (table != null) {
                writer.println(TileDecoder.pixelsToDebugString(table));
                
                // Export individual tiles from this table
                writer.println();
                writer.println("=== Tiles in Pattern Table " + patternTable + " ===");
                int baseIndex = patternTable * 256;
                for (int i = 0; i < 256; i++) {
                    byte[][] tile = TileDecoder.decodeTile(chrData, baseIndex + i);
                    if (tile != null) {
                        // Check if tile is non-empty (not all zeros)
                        boolean isEmpty = true;
                        for (int y = 0; y < 8 && isEmpty; y++) {
                            for (int x = 0; x < 8 && isEmpty; x++) {
                                if (tile[y][x] != 0) {
                                    isEmpty = false;
                                }
                            }
                        }
                        
                        if (!isEmpty) {
                            writer.println("Tile " + i + ":");
                            writer.println(TileDecoder.tileToDebugString(tile));
                        }
                    }
                }
            }
            
            log.info("Pattern table {} exported to: {}", patternTable, outputPath);
            
        } catch (IOException e) {
            log.error("Error writing pattern table to file: {}", outputPath, e);
        }
    }
    
    /**
     * Gets a summary of CHR ROM content
     * 
     * @param chrData raw CHR ROM byte array
     * @return formatted summary string
     */
    public static String getCHRSummary(byte[] chrData) {
        if (chrData == null) {
            return "CHR data is null";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("CHR ROM Summary:\n");
        sb.append("  Size: ").append(chrData.length).append(" bytes\n");
        sb.append("  Banks: ").append(chrData.length / 8192).append(" (8KB each)\n");
        sb.append("  Tiles: ").append(chrData.length / 16).append(" (16 bytes each)\n");
        
        // Check if data is all zeros or all ones
        boolean allZeros = true;
        boolean allOnes = true;
        for (byte b : chrData) {
            if (b != 0) allZeros = false;
            if ((b & 0xFF) != 0xFF) allOnes = false;
        }
        
        if (allZeros) {
            sb.append("  Content: ALL ZEROS (likely CHR RAM or uninitialized)\n");
        } else if (allOnes) {
            sb.append("  Content: ALL ONES (likely uninitialized)\n");
        } else {
            sb.append("  Content: Mixed data present\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Helper method to write hex dump
     */
    private static void exportHexDump(byte[] data, int startOffset, int length, PrintWriter writer) {
        int endOffset = Math.min(startOffset + length, data.length);
        
        for (int offset = startOffset; offset < endOffset; offset += 16) {
            writer.printf("%08X: ", offset);
            
            // Hex bytes
            for (int i = 0; i < 16 && offset + i < endOffset; i++) {
                writer.printf("%02X ", data[offset + i] & 0xFF);
            }
            
            // Padding
            for (int i = (endOffset - offset); i < 16; i++) {
                writer.print("   ");
            }
            
            writer.print(" | ");
            
            // ASCII representation
            for (int i = 0; i < 16 && offset + i < endOffset; i++) {
                byte b = data[offset + i];
                char c = (b >= 32 && b < 127) ? (char) b : '.';
                writer.print(c);
            }
            
            writer.println();
        }
    }
}
