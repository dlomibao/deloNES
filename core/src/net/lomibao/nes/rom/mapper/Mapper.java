package net.lomibao.nes.rom.mapper;

public interface Mapper {
    /*
    mapper interface returns null if failed to map, otherwise will return the rom address
    * */
    Integer cpuMapRead(int address);
    Integer cpuMapWrite(int address);

    Integer ppuMapRead(int address);
    Integer ppuMapWrite(int address);


    void reset();
    boolean reqState();
    void irqClear();

    void scanLine();

    int numberOfPRGBanks();
    int numberOfCHRBanks();

    Mirror mirror();
    enum Mirror
    {
        HARDWARE,
        HORIZONTAL,
        VERTICAL,
        ONESCREEN_LO,
        ONESCREEN_HI,
    };
}
