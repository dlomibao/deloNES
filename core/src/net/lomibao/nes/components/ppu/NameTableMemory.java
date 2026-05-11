package net.lomibao.nes.components.ppu;

import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.PPUBus;
import net.lomibao.nes.components.PPUBusComponent;

/**
 * Nametable memory component for NES PPU
 * Manages 2KB of VRAM for background tile layout
 * Handles mirroring based on cartridge configuration
 */
@Log4j2
public class NameTableMemory implements PPUBusComponent {
    private PPUBus ppuBus;
    private Cartridge cartridge;
    /**
     * Optional manual override for the mirroring mode. When non-null,
     * takes precedence over the cartridge-derived mode. Useful for tests
     * that exercise scrolling without standing up a full cartridge, and
     * for future mappers (MMC1+) that can switch mirroring at runtime.
     */
    private MirroringMode mirroringOverride;

    // 2KB VRAM (2 physical nametables, mirrored to appear as 4)
    private byte[] vram = new byte[2048];
    
    public static final int PPU_BUS_START_ADDRESS = 0x2000;
    public static final int PPU_BUS_END_ADDRESS = 0x3F00;
    
    /**
     * Creates nametable memory and initializes VRAM to zeros
     */
    public NameTableMemory() {
        // VRAM initializes to zeros by default
    }
    
    /**
     * Sets the cartridge reference for mirroring mode detection
     * @param cartridge the cartridge to get mirroring mode from
     */
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }
    
    /**
     * Gets the current mirroring mode from the cartridge
     * @return the mirroring mode, defaults to HORIZONTAL if no cartridge
     */
    private MirroringMode getMirroringMode() {
        if (mirroringOverride != null) {
            return mirroringOverride;
        }
        if (cartridge == null) {
            return MirroringMode.HORIZONTAL;
        }
        // For now, use horizontal mirroring based on header flag
        // TODO: Advanced mappers can change mirroring dynamically
        return cartridge.isHorizontalMirroring()
            ? MirroringMode.HORIZONTAL
            : MirroringMode.VERTICAL;
    }

    /**
     * Manually set the mirroring mode, bypassing cartridge-derived mode.
     * Used by tests and (in the future) by mappers like MMC1 that switch
     * mirroring at runtime via control-register writes. Pass {@code null}
     * to revert to cartridge-derived mode.
     */
    public void setMirroringOverride(MirroringMode mode) {
        this.mirroringOverride = mode;
    }
    
    /**
     * Mirrors a nametable address to physical VRAM address
     * Handles horizontal, vertical, single-screen, and four-screen mirroring
     * 
     * @param address PPU address in range 0x2000-0x3EFF
     * @return physical VRAM address (0-2047)
     */
    private int mirrorAddress(int address) {
        // Normalize to 0x2000-0x2FFF range (nametables mirror every 0x1000 bytes)
        int addr = ((address - 0x2000) & 0x0FFF);
        
        // Determine which nametable (0-3) and offset within nametable
        int nametable = (addr >> 10) & 0x03; // Which of 4 nametables (bits 10-11)
        int offset = addr & 0x3FF;            // Offset within nametable (bits 0-9)
        
        MirroringMode mode = getMirroringMode();
        
        int physicalTable;
        switch (mode) {
            case HORIZONTAL:
                // Horizontal mirroring: 0&1 mirror, 2&3 mirror
                // Tables 0,1 → physical table 0
                // Tables 2,3 → physical table 1
                physicalTable = (nametable >> 1) & 0x01;
                break;
                
            case VERTICAL:
                // Vertical mirroring: 0&2 mirror, 1&3 mirror
                // Tables 0,2 → physical table 0
                // Tables 1,3 → physical table 1
                physicalTable = nametable & 0x01;
                break;
                
            case SINGLE_SCREEN:
                // Single-screen: all map to table 0
                physicalTable = 0;
                break;
                
            case FOUR_SCREEN:
                // Four-screen: would need 4KB VRAM (not yet supported)
                // Fall back to horizontal mirroring
                log.warn("Four-screen VRAM not yet supported, using horizontal mirroring");
                physicalTable = (nametable >> 1) & 0x01;
                break;
                
            default:
                physicalTable = 0;
        }
        
        // Calculate final VRAM address
        return (physicalTable << 10) | offset;
    }
    
    /**
     * Reads a byte from nametable memory
     * @param address PPU address (0x2000-0x3EFF)
     * @param readOnly if true, doesn't trigger side effects (unused for nametables)
     * @return byte value at address
     */
    @Override
    public int ppuBusRead(int address, boolean readOnly) {
        if (address < PPU_BUS_START_ADDRESS || address >= PPU_BUS_END_ADDRESS) {
            return 0;
        }
        
        int vramAddr = mirrorAddress(address);
        return Byte.toUnsignedInt(vram[vramAddr]);
    }
    
    /**
     * Writes a byte to nametable memory
     * @param address PPU address (0x2000-0x3EFF)
     * @param value byte value to write
     */
    @Override
    public void ppuBusWrite(int address, byte value) {
        if (address < PPU_BUS_START_ADDRESS || address >= PPU_BUS_END_ADDRESS) {
            return;
        }
        
        int vramAddr = mirrorAddress(address);
        vram[vramAddr] = value;
    }

    @Override
    public void connectPPUBus(PPUBus ppuBus) {
        this.ppuBus = ppuBus;
    }

    @Override
    public PPUBus getPPUBus() {
        return ppuBus;
    }

    @Override
    public int getPPUBusStartAddress() {
        return PPU_BUS_START_ADDRESS;
    }

    @Override
    public int getPPUBusEndAddress() {
        return PPU_BUS_END_ADDRESS;
    }
    
    /**
     * Gets the raw VRAM array (for testing)
     * @return the 2KB VRAM array
     */
    public byte[] getVRAM() {
        return vram;
    }
}
