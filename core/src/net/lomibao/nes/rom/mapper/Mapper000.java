package net.lomibao.nes.rom.mapper;

/**
 * mapper 0 of ines format roms are straight passthrough
 * **/
public class Mapper000 implements Mapper{
    @Override
    public Integer cpuMapRead(int address) {
        return address;
    }

    @Override
    public Integer cpuMapWrite(int address) {
        return address;
    }

    @Override
    public Integer ppuMapRead(int address) {
        return address;
    }

    @Override
    public Integer ppuMapWrite(int address) {
        return address;
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
        return 0;
    }

    @Override
    public int numberOfCHRBanks() {
        return 0;
    }

    @Override
    public Mirror mirror() {
        return null;
    }
}
