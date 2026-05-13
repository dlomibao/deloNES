package net.lomibao.nes.rom.mapper;

/**
 * mapper 0 of ines format roms are straight passthrough
 **/
public class Mapper000 implements Mapper {
    private final int nPRGBanks;
    private final int nCHRBanks;

    public Mapper000(int prgBanks, int chrBanks) {
        this.nPRGBanks = prgBanks;
        this.nCHRBanks = chrBanks;
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            // If PRG Banks > 1, 32KB mapping, else 16KB mirrored
            return (address - 0x8000) & (nPRGBanks > 1 ? 0x7FFF : 0x3FFF);
        }
        return UNMAPPED;
    }

    @Override
    public int cpuMapWrite(int address) {
        // Mapper 000 usually doesn't have PRG RAM/Registers in the $8000 range
        if (address >= 0x8000 && address <= 0xFFFF) {
            return (address - 0x8000) & (nPRGBanks > 1 ? 0x7FFF : 0x3FFF);
        }
        return UNMAPPED;
    }

    @Override
    public int ppuMapRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            return address;
        }
        return UNMAPPED;
    }

    @Override
    public int ppuMapWrite(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            // Usually read-only unless it's CHR RAM (which nCHRBanks == 0 indicates)
            if (nCHRBanks == 0) {
                return address;
            }
        }
        return UNMAPPED;
    }

    @Override
    public void reset() {

    }

    @Override
    public boolean reqState() {
        return false;
    }

    @Override
    public void irqClear() {

    }

    @Override
    public void scanLine() {

    }

    @Override
    public int numberOfPRGBanks() {
        return nPRGBanks;
    }

    @Override
    public int numberOfCHRBanks() {
        return nCHRBanks;
    }

    @Override
    public Mirror mirror() {
        return Mirror.HARDWARE;
    }
}
