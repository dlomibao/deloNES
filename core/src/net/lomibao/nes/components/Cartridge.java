package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class Cartridge extends CPUBusComponent {
    //The CPU only goes to the cartridge for addresses 0x4020 to 0xFFFF, and the PPU only goes to the cartridge for addresses 0x0000 to 0x3EFF
}
