package net.lomibao.nes.rom.mapper;

/**
 * iNES Mapper 3 — CNROM. Phase B2 of {@code docs/mapper-plan.md}.
 *
 * <p>CNROM is one of the simplest mappers: PRG-ROM is fixed (16KB
 * mirrored into the CPU window or 32KB flat — identical to NROM), and a
 * single CHR-bank register selects which 8KB of CHR-ROM is visible to the
 * PPU at {@code $0000-$1FFF}. The register is latched on <em>any</em>
 * CPU write to {@code $8000-$FFFF}; only the low two bits are used in
 * the canonical spec (4 × 8KB = 32KB CHR-ROM max). Some unofficial
 * variants widen the register, but masking to two bits is safe for all
 * licensed CNROM titles.
 *
 * <p><b>Bus conflicts:</b> real CNROM hardware AND's the written value
 * with the byte already at the same PRG address (the cart's data lines
 * are shared). This implementation ignores bus conflicts — no licensed
 * CNROM title depends on them, and the simplification keeps the mapper
 * small. Adding conflict emulation later is a one-line tweak inside
 * {@link #cpuMapWrite(int, byte)}.
 *
 * <p>CHR is <b>always ROM</b> on canonical CNROM ({@code nCHRBanks > 0}
 * in every licensed cart), so {@link #ppuMapWrite(int)} unconditionally
 * returns {@link Mapper#UNMAPPED}. Mirroring is fixed by the iNES header
 * and {@link #mirror()} returns {@link Mirror#HARDWARE} so
 * {@code Cartridge.getMirrorMode()} falls back to the header bit.
 */
public class MapperCNROM implements Mapper {

    private static final int CHR_BANK_BYTES = 0x2000; // 8 KB
    /** Mask for the canonical 2-bit CNROM CHR-bank register. */
    private static final int CHR_BANK_MASK = 0x03;

    private final int nPRGBanks;
    private final int nCHRBanks;

    /** Currently-selected 8 KB CHR bank index (0..3 after masking). */
    private int chrBank;

    public MapperCNROM(int prgBanks, int chrBanks) {
        this.nPRGBanks = prgBanks;
        this.nCHRBanks = chrBanks;
        this.chrBank = 0;
    }

    // ---- CPU side -------------------------------------------------------------

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            // Same fixed PRG layout as NROM: 16KB carts mirror $C000-$FFFF
            // back onto $8000-$BFFF; 32KB carts get a flat $8000-$FFFF map.
            return (address - 0x8000) & (nPRGBanks > 1 ? 0x7FFF : 0x3FFF);
        }
        return UNMAPPED;
    }

    @Override
    public int cpuMapWrite(int address) {
        // CNROM has no PRG-RAM. Writes in the register window are absorbed
        // by the value-aware overload below; nothing lands in vPRGMemory.
        return UNMAPPED;
    }

    @Override
    public void cpuMapWrite(int address, byte value) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            chrBank = value & CHR_BANK_MASK;
        }
        // Bus-conflict handling intentionally omitted — see class javadoc.
    }

    // ---- PPU side -------------------------------------------------------------

    @Override
    public int ppuMapRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            return chrBank * CHR_BANK_BYTES + address;
        }
        return UNMAPPED;
    }

    @Override
    public int ppuMapWrite(int address) {
        // CHR is ROM on canonical CNROM — writes never land.
        return UNMAPPED;
    }

    // ---- Lifecycle ------------------------------------------------------------

    @Override
    public void reset() {
        chrBank = 0;
    }

    @Override
    public boolean reqState() {
        return false;
    }

    @Override
    public void irqClear() {
        // no IRQ source
    }

    @Override
    public void scanLine() {
        // no scanline-based behavior
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
