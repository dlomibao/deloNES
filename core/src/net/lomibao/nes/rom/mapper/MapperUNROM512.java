package net.lomibao.nes.rom.mapper;

/**
 * iNES Mapper 30 — UNROM-512 (RetroUSB / Membler Industries homebrew
 * board). See NESdev wiki
 * <a href="https://www.nesdev.org/wiki/UNROM_512">Mapper 30</a>.
 *
 * <p><b>Single 8-bit register</b> latched on any CPU write to
 * {@code $8000-$FFFF}:
 * <pre>
 * 7654 3210
 * M.CC PPPP
 * | ||  ++++ low 4 bits → 16KB PRG bank index (switchable at $8000-$BFFF)
 * | ++------ bits 4-5  → 8KB CHR-RAM bank index (4 banks → 32KB total)
 * +--------- bit 7     → 1-screen mirror select (when 1-screen mode is
 *                        enabled by the iNES header).
 * </pre>
 *
 * <p><b>PRG layout</b> (UxROM-style):
 * <ul>
 *   <li>{@code $8000-$BFFF}: switchable 16KB bank from register low nibble</li>
 *   <li>{@code $C000-$FFFF}: FIXED to the last 16KB of PRG ROM</li>
 * </ul>
 *
 * <p><b>CHR layout</b>: 32KB of CHR-RAM (NOT CHR-ROM); bank-switched in
 * 8KB units via register bits 4-5. The Cartridge allocates the 32KB
 * vCHRMemory by consulting {@link #getChrRamSize()}.
 *
 * <p><b>Mirroring</b>: per spec, iNES header byte 6 bit 3 (4-screen
 * flag) is repurposed: when SET, mapper provides 1-screen-with-select
 * (bit 7 of register picks LO/HI); when CLEAR, mirroring is fixed from
 * the header's horizontal/vertical bit. For deloNES we always honour
 * the latched M bit (returning {@link Mirror#ONESCREEN_LO} or
 * {@link Mirror#ONESCREEN_HI}), which matches the common homebrew
 * convention (Micro Mages, etc.). A cart that wants fixed mirroring
 * should set its bit-7 to a known value and leave it alone — but the
 * full "fixed vs select" distinction is outside the current scope.
 *
 * <p><b>Power-on</b>: PRG bank 0, CHR bank 0, mirror LO. Spec says the
 * register is technically uninitialized; treating it as 0 is safe and
 * matches every other emulator.
 *
 * <p>Iconic title: <i>Micro Mages</i> (Morphcat Games, 2018).
 */
public class MapperUNROM512 implements Mapper {

    private static final int PRG_BANK_MASK = 0x0F;  // low 4 bits → PRG bank
    private static final int CHR_BANK_MASK = 0x03;  // 2 bits → CHR bank (after >>4)
    private static final int CHR_BANK_SHIFT = 4;
    private static final int MIRROR_BIT    = 0x80;  // bit 7

    private static final int PRG_16K       = 16 * 1024;
    private static final int CHR_8K        =  8 * 1024;
    private static final int CHR_RAM_BYTES = 32 * 1024;  // 4 × 8KB banks
    private static final int PRG_LOW_MASK  = 0x3FFF;     // 16KB window mask

    private final int nPRGBanks;
    private final int nCHRBanks;

    /** Switchable PRG bank index (4 bits). */
    private int prgBank;
    /** Switchable CHR-RAM bank index (2 bits). */
    private int chrBank;
    /** True if bit 7 of last register write was set (ONESCREEN_HI). */
    private boolean mirrorHigh;

    public MapperUNROM512(int prgBanks, int chrBanks) {
        this.nPRGBanks = prgBanks;
        this.nCHRBanks = chrBanks;
        reset();
    }

    // ---- CPU side ----------------------------------------------------

    @Override
    public int cpuMapRead(int address) {
        if (address < 0x8000 || address > 0xFFFF) {
            return UNMAPPED;
        }
        if (address <= 0xBFFF) {
            // $8000-$BFFF: switchable 16KB bank.
            return prgBank * PRG_16K + (address & PRG_LOW_MASK);
        }
        // $C000-$FFFF: fixed to LAST 16KB bank.
        int lastBank = nPRGBanks - 1;
        return lastBank * PRG_16K + (address & PRG_LOW_MASK);
    }

    @Override
    public int cpuMapWrite(int address) {
        // Address-only form: cannot drive the register latch without
        // the byte value. Cartridge.cpuBusWrite calls the 2-arg overload.
        return UNMAPPED;
    }

    @Override
    public int cpuMapWrite(int address, int value) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            prgBank    = value & PRG_BANK_MASK;
            chrBank    = (value >> CHR_BANK_SHIFT) & CHR_BANK_MASK;
            mirrorHigh = (value & MIRROR_BIT) != 0;
        }
        // Register hits and out-of-range writes both return UNMAPPED:
        // there is no PRG-RAM landing zone on UNROM-512.
        return UNMAPPED;
    }

    // ---- PPU side ----------------------------------------------------

    @Override
    public int ppuMapRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            return chrBank * CHR_8K + address;
        }
        return UNMAPPED;
    }

    @Override
    public int ppuMapWrite(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            // 32KB CHR-RAM: writes always land at the bank-translated
            // offset, mirroring ppuMapRead. Cartridge allocates 32KB
            // by consulting getChrRamSize() at construction time.
            return chrBank * CHR_8K + address;
        }
        return UNMAPPED;
    }

    // ---- Lifecycle / metadata ----------------------------------------

    @Override
    public void reset() {
        prgBank = 0;
        chrBank = 0;
        mirrorHigh = false;
    }

    @Override
    public boolean reqState() {
        // UNROM-512 has no IRQ source.
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

    /**
     * UNROM-512 needs 32KB of CHR-RAM (4 × 8KB banks). Overrides the
     * 8KB default from {@link Mapper#getChrRamSize()}. The Cartridge
     * constructor consults this at allocation time when the iNES
     * header reports {@code nCHRBanks == 0} (CHR-RAM cart).
     *
     * @return 32768 (32KB)
     */
    @Override
    public int getChrRamSize() {
        return CHR_RAM_BYTES;
    }
}
