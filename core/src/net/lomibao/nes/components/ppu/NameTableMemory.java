package net.lomibao.nes.components.ppu;

import net.lomibao.nes.components.PPUBus;
import net.lomibao.nes.components.PPUBusComponent;

public class NameTableMemory implements PPUBusComponent {
    PPUBus ppuBus;
    public static final int PPU_BUS_START_ADDRESS=0x2000;
    public static final int PPU_BUS_END_ADDRESS=0x3F00;

    @Override
    public void connectPPUBus(PPUBus ppuBus) {
        this.ppuBus=ppuBus;
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
}
