package net.lomibao.nes.rom.mapper;

/**
 * iNES Mapper 2 — UxROM.
 *
 * <p>PRG layout:
 * <ul>
 *   <li><b>$8000-$BFFF</b>: switchable 16KB bank, selected by the bank
 *       register.</li>
 *   <li><b>$C000-$FFFF</b>: <em>fixed</em> 16KB bank, always pointing
 *       at the last bank in the PRG ROM
 *       ({@code (numberOfPRGBanks - 1) * 16KB}).</li>
 * </ul>
 *
 * <p>Bank register: any write to {@code $8000-$FFFF} updates a single
 * latch. Only the low 4 bits of the written value matter; the upper
 * bits are masked off. Real UxROM hardware has a bus conflict on
 * writes — games that ship UxROM typically avoid writing values to PRG
 * locations that would create a mismatch. This emulator does not model
 * the bus conflict; it accepts the written value cleanly.
 *
 * <p>CHR: 8KB fixed. If the cart reports {@code nCHRBanks > 0} it's
 * CHR-ROM (writes are ignored); if {@code nCHRBanks == 0} it's CHR-RAM
 * (writes pass through). Mirrors {@link Mapper000}'s CHR semantics.
 *
 * <p>Mirroring: not switchable by the mapper — derived from the iNES
 * header bit, surfaced via {@link Mirror#HARDWARE}.
 *
 * <p>References:
 * <ul>
 *   <li><a href="https://www.nesdev.org/wiki/UxROM">NESdev wiki — UxROM (iNES Mapper 2)</a></li>
 * </ul>
 */
public class MapperUxROM implements Mapper {

    /** Size of one PRG bank in bytes (16KB). */
    private static final int PRG_BANK_BYTES = 0x4000;

    /** Mask applied to the bank-select value before latching (low 4 bits). */
    private static final int BANK_REGISTER_MASK = 0x0F;

    private final int nPRGBanks;
    private final int nCHRBanks;

    /** Offset (in bytes) of the last 16KB bank — always the fixed high window. */
    private final int lastBankOffset;

    /** Currently-selected switchable-bank index (low 4 bits of last write). */
    private int prgBankSelect;

    public MapperUxROM(int prgBanks, int chrBanks) {
        this.nPRGBanks = prgBanks;
        this.nCHRBanks = chrBanks;
        this.lastBankOffset = (prgBanks - 1) * PRG_BANK_BYTES;
        this.prgBankSelect = 0;
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xBFFF) {
            // Switchable low window. Wrap to the cart's actual bank count —
            // hardware ignores unwired upper address lines, so a 4-bit
            // register on a smaller cart wraps rather than indexing past
            // the end of PRG ROM.
            return (prgBankSelect % nPRGBanks) * PRG_BANK_BYTES + (address & 0x3FFF);
        }
        if (address >= 0xC000 && address <= 0xFFFF) {
            // Fixed-to-last-bank high window.
            return lastBankOffset + (address & 0x3FFF);
        }
        return UNMAPPED;
    }

    /**
     * Default 1-arg Mapper.cpuMapWrite. UxROM doesn't have a written
     * value here, so it can't latch the bank — returns UNMAPPED for
     * in-range writes (the value-aware overload below does the latching).
     * Out-of-range writes are also UNMAPPED.
     */
    @Override
    public int cpuMapWrite(int address) {
        // No value available, so the bank register cannot be latched here;
        // Cartridge routes writes through the 2-arg overload. UxROM never
        // writes to PRG memory, so UNMAPPED is correct on every path.
        return UNMAPPED;
    }

    /**
     * Value-aware register latch. Cartridge routes byte writes here so
     * UxROM can capture the bank index. Returns {@link #UNMAPPED} —
     * UxROM never writes to PRG memory; the register is internal to
     * the mapper.
     */
    @Override
    public int cpuMapWrite(int address, int value) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            prgBankSelect = value & BANK_REGISTER_MASK;
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
            // CHR-RAM cart (nCHRBanks == 0): writes pass through.
            // CHR-ROM cart: writes ignored.
            if (nCHRBanks == 0) {
                return address;
            }
        }
        return UNMAPPED;
    }

    @Override
    public void reset() {
        // Real UxROM doesn't define a reset state for its bank register,
        // but the convention (and how most games initialise) is bank 0.
        prgBankSelect = 0;
    }

    @Override
    public boolean reqState() {
        return false;
    }

    @Override
    public void irqClear() {
        // UxROM has no IRQ source.
    }

    @Override
    public void scanLine() {
        // UxROM has no scanline counter.
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
