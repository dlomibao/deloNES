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
     * Value-aware variant of {@link #cpuMapWrite(int)}. Mappers whose
     * {@code $8000-$FFFF} writes are <em>register latches</em> (UxROM,
     * CNROM, MMC1, MMC3, AxROM, UNROM-512) override this to capture the
     * byte being written; mappers whose PRG window is plain RAM
     * (NROM with battery, etc.) can rely on the default which delegates
     * to the address-only form.
     *
     * <p>Cartridge.cpuBusWrite invokes this overload so the mapper sees
     * both the address and the value in one shot.
     *
     * @param address CPU address (typically $8000-$FFFF for register writes)
     * @param value   byte being written, as an unsigned 0-255 int
     * @return mapped PRG offset, or {@link #UNMAPPED} if the write is a
     *         register-only side effect with no PRG-memory destination
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
