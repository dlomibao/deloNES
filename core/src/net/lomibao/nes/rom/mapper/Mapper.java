package net.lomibao.nes.rom.mapper;

public interface Mapper {
    /**
     * Sentinel returned by the {@code *Map*} methods when the supplied
     * address falls outside the mapper's range. Callers must check for
     * {@code >= 0} before using the result as an array index.
     *
     * <p>This used to be a {@code null} returned from a boxed
     * {@link Integer}, but every per-CPU-cycle map call boxed the result;
     * tens of thousands of Integer allocations per emulated frame were
     * material in the TeaVM web-build profile. Returning a primitive
     * {@code int} with a sentinel removes the allocation entirely.
     */
    int UNMAPPED = -1;

    int cpuMapRead(int address);
    int cpuMapWrite(int address);

    int ppuMapRead(int address);
    int ppuMapWrite(int address);


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
