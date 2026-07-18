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
     * Single-screen mirroring, lower bank ($2000 page).
     * All four logical nametables alias to physical page 0. MMC1 selects
     * this via control register bits 0-1 = 00.
     */
    SINGLE_SCREEN_LO,

    /**
     * Single-screen mirroring, upper bank ($2400 page).
     * All four logical nametables alias to physical page 1. MMC1 selects
     * this via control register bits 0-1 = 01. Distinct from
     * {@link #SINGLE_SCREEN_LO} so games that flip between the two pages
     * (e.g. some MMC1 titles) preserve VRAM contents on each side.
     */
    SINGLE_SCREEN_HI,

    /**
     * Four-screen mirroring
     * All four nametables are independent (requires extra cartridge RAM)
     * Used by some advanced games
     */
    FOUR_SCREEN
}
