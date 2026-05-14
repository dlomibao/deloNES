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

    /**
     * Value-carrying CPU write hook. Mappers with bank-select registers
     * mapped into the PRG window (AxROM, UxROM, CNROM, MMC1, MMC3, ...)
     * need the byte value, not just the address, to update internal
     * state. The default implementation delegates to the no-value
     * overload so existing mappers (e.g. {@link Mapper000}) remain
     * untouched and the new overload is opt-in per mapper.
     *
     * <p>Return semantics match {@link #cpuMapWrite(int)}: an offset
     * &gt;= 0 means "write the byte to PRG memory at this offset",
     * {@link #UNMAPPED} means "the write hit a register or fell
     * outside our range; don't touch PRG memory".
     *
     * @param address CPU bus address ($0000-$FFFF)
     * @param value   unsigned byte value being written (0-255)
     * @return PRG-memory offset to write, or {@link #UNMAPPED}
     */
    default int cpuMapWrite(int address, int value) {
        return cpuMapWrite(address);
    }

    int ppuMapRead(int address);
    int ppuMapWrite(int address);


    void reset();
    boolean reqState();
    void irqClear();

    void scanLine();

    /**
     * PPU A12 line transition notification. Called on every PPU bus
     * address change; default is no-op. MMC3's scanline IRQ counter
     * clocks on A12 rising edges (low→high) and uses this hook.
     *
     * <p>A rising edge is detected by callers as
     * {@code (previousAddress & 0x1000) == 0 && (address & 0x1000) != 0}.
     * Mappers can apply their own filters (e.g. MMC3's 4-clock low
     * filter) on top.
     *
     * @param address          current PPU bus address (14-bit)
     * @param previousAddress  prior PPU bus address (14-bit)
     */
    default void tickPpuA12(int address, int previousAddress) {
        // no-op by default; mappers that need scanline IRQs override
    }

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
