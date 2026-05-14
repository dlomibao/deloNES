package net.lomibao.nes.rom.mapper;

/**
 * iNES Mapper 7 — AxROM (Rare's "Rare Single-Screen" board family:
 * ANROM / AOROM / AMROM). See NESdev wiki
 * <a href="https://www.nesdev.org/wiki/AxROM">Mapper 7</a>.
 *
 * <p><b>Single bank register</b> mapped anywhere in $8000-$FFFF:
 * <ul>
 *   <li>bits 0-2 select a 32KB PRG bank — 32KB step, NOT 16KB. The
 *       whole CPU PRG window is one switchable bank.</li>
 *   <li>bit 4 selects single-screen mirroring (0 = ONESCREEN_LO / NT A,
 *       1 = ONESCREEN_HI / NT B).</li>
 *   <li>all other bits are ignored.</li>
 * </ul>
 *
 * <p><b>PRG:</b> up to 256KB (8 x 32KB banks). 32KB switchable at
 * $8000-$FFFF. No fixed bank.
 *
 * <p><b>CHR:</b> typically 8KB CHR-RAM (no banking). Rare CHR-ROM
 * variants behave like Mapper 0 in the CHR window.
 *
 * <p><b>Mirroring:</b> always single-screen at runtime; controlled by
 * the latched register bit. The iNES header's mirror bit and 4-screen
 * bit are <em>ignored</em> — the mapper takes full control.
 *
 * <p><b>Power-on:</b> PRG bank 0, ONESCREEN_LO.
 *
 * <p>Iconic title: <i>Battletoads</i> (Rare, 1991).
 */
public class MapperAxROM implements Mapper {

    private static final int PRG_BANK_SELECT_MASK = 0x07;  // bits 0-2
    private static final int MIRROR_BIT          = 0x10;  // bit 4
    private static final int PRG_BANK_BYTES      = 32 * 1024;
    private static final int PRG_WINDOW_MASK     = 0x7FFF; // 32KB - 1

    private final int nPRGBanks;
    private final int nCHRBanks;

    /** Selected 32KB PRG bank, bits 0-2 of the last register write. */
    private int prgBank;
    /** True ⇔ bit 4 of the last register write was set (ONESCREEN_HI). */
    private boolean mirrorHigh;

    public MapperAxROM(int prgBanks, int chrBanks) {
        this.nPRGBanks = prgBanks;
        this.nCHRBanks = chrBanks;
        reset();
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            return (prgBank * PRG_BANK_BYTES) + (address & PRG_WINDOW_MASK);
        }
        return UNMAPPED;
    }

    @Override
    public int cpuMapWrite(int address) {
        // The no-value overload is preserved for interface
        // compatibility; AxROM register writes need the byte value
        // and arrive through the value-carrying overload below. Any
        // direct call here is a no-op write (UNMAPPED).
        return UNMAPPED;
    }

    @Override
    public int cpuMapWrite(int address, int value) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            prgBank    = value & PRG_BANK_SELECT_MASK;
            mirrorHigh = (value & MIRROR_BIT) != 0;
        }
        // Register hits and out-of-range writes both return UNMAPPED:
        // there is no PRG-RAM landing zone on AxROM, so the Cartridge
        // must not write to vPRGMemory on either path.
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
            // CHR-RAM cart (nCHRBanks == 0) lets writes through; CHR-ROM
            // cart (nCHRBanks > 0) treats the CHR window as read-only.
            if (nCHRBanks == 0) {
                return address;
            }
        }
        return UNMAPPED;
    }

    @Override
    public void reset() {
        prgBank = 0;
        mirrorHigh = false;
    }

    @Override
    public boolean reqState() {
        // AxROM has no IRQ source.
        return false;
    }

    @Override
    public void irqClear() {
        // no-op
    }

    @Override
    public void scanLine() {
        // no-op
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
        return mirrorHigh ? Mirror.ONESCREEN_HI : Mirror.ONESCREEN_LO;
    }
}
