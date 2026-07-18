package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;

/**
 * PPU Bus connects the PPU to various memory components:
 * - Pattern Tables (CHR ROM/RAM): 0x0000-0x1FFF
 * - Nametables: 0x2000-0x3EFF
 * - Palette RAM is NOT on this bus (internal to PPU)
 */
@Log4j2
public class PPUBus {
    private PPU ppu;
    private Cartridge cartridge;
    private List<PPUBusComponent> components = new ArrayList<>();

    /**
     * Last PPU bus address emitted; used by the Phase A3 A12 hook to
     * detect rising edges on bit 12 across consecutive accesses.
     * Stored as 14-bit (masked to 0x3FFF on every update).
     */
    private int previousPpuAddress = 0;

    /**
     * Connects the cartridge to the PPU bus for CHR ROM/RAM access
     */
    public void connectCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    /**
     * Connects the PPU (for internal reference, though PPU accesses bus, not vice versa)
     */
    public void connectPPU(PPU ppu) {
        this.ppu = ppu;
    }
    
    /**
     * Connects one or more PPU bus components to the bus
     * Components are checked in order, first matching component handles the address
     * @param components components to connect
     * @return this bus for method chaining
     */
    public PPUBus connect(PPUBusComponent... components) {
        for (PPUBusComponent component : components) {
            this.components.add(component);
            component.connectPPUBus(this);
        }
        return this;
    }

    /**
     * Writes a byte to the PPU bus
     * Routes to appropriate component based on address range
     * @param address PPU address space (0x0000-0x3FFF)
     * @param value byte to write
     */
    public void write(int address, byte value) {
        int addr = address & 0x3FFF; // 14-bit address space

        // Phase A3: notify cart of A12 transition before routing.
        // Writes drive A12 too ($2006 latch loads, $2007 increments etc.)
        if (cartridge != null) {
            cartridge.notifyPpuA12(addr, previousPpuAddress);
        }
        previousPpuAddress = addr;

        // Try registered components first
        for (PPUBusComponent component : components) {
            if (component.inPPUusRange(addr)) {
                component.ppuBusWrite(addr, value);
                return;
            }
        }
        
        // Fall back to legacy handling
        if (addr < 0x2000) {
            // Pattern Tables (0x0000-0x1FFF): CHR ROM/RAM.
            // CHR-ROM carts: mapper returns UNMAPPED, the write no-ops.
            // CHR-RAM carts (and future bank-switched CHR-RAM mappers like
            // UNROM-512): mapper returns the mapped offset and the byte
            // lands in vCHRMemory. Phase A1 of docs/mapper-plan.md.
            if (cartridge != null) {
                cartridge.chrWrite(addr, value);
            }
        } else if (addr < 0x3F00) {
            // Nametables (0x2000-0x3EFF)
            log.warn("Write to nametable at address 0x{} with no component registered", Integer.toHexString(addr));
        } else {
            // 0x3F00-0x3FFF: Palette RAM
            // This should NOT reach the bus - it's internal to PPU
            log.error("Write to palette address 0x{} should not reach PPU bus", Integer.toHexString(addr));
        }
    }

    /**
     * Reads a byte from the PPU bus
     * Routes to appropriate component based on address range
     * @param address PPU address space (0x0000-0x3FFF)
     * @return byte value at address
     */
    public int read(int address) {
        int addr = address & 0x3FFF; // 14-bit address space

        // Phase A3: notify cart of A12 transition before reading.
        if (cartridge != null) {
            cartridge.notifyPpuA12(addr, previousPpuAddress);
        }
        previousPpuAddress = addr;

        // Try registered components first
        for (PPUBusComponent component : components) {
            if (component.inPPUusRange(addr)) {
                return component.ppuBusRead(addr, false);
            }
        }
        
        // Fall back to legacy handling
        if (addr < 0x2000) {
            // Pattern Tables (0x0000-0x1FFF): CHR ROM/RAM
            if (cartridge != null) {
                return cartridge.chrRead(addr);
            }
            return 0;
        } else if (addr < 0x3F00) {
            // Nametables (0x2000-0x3EFF)
            log.warn("Read from nametable at address 0x{} with no component registered", Integer.toHexString(addr));
            return 0;
        } else {
            // 0x3F00-0x3FFF: Palette RAM
            // This should NOT reach the bus - it's internal to PPU
            log.error("Read from palette address 0x{} should not reach PPU bus", Integer.toHexString(addr));
            return 0;
        }
    }
}

