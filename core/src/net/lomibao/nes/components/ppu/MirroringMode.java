package net.lomibao.nes.components.ppu;

/**
 * Nametable mirroring modes for NES PPU
 * Determines how the four nametable addresses map to physical VRAM
 */
public enum MirroringMode {
    /**
     * Horizontal mirroring: vertical arrangement
     * Nametables 0 and 1 mirror each other (top two)
     * Nametables 2 and 3 mirror each other (bottom two)
     * Common in games with vertical scrolling
     */
    HORIZONTAL,
    
    /**
     * Vertical mirroring: horizontal arrangement
     * Nametables 0 and 2 mirror each other (left two)
     * Nametables 1 and 3 mirror each other (right two)
     * Common in games with horizontal scrolling
     */
    VERTICAL,
    
    /**
     * Single-screen mirroring
     * All four nametables map to the same physical memory
     * Used by some simple games
     */
    SINGLE_SCREEN,
    
    /**
     * Four-screen mirroring
     * All four nametables are independent (requires extra cartridge RAM)
     * Used by some advanced games
     */
    FOUR_SCREEN
}
