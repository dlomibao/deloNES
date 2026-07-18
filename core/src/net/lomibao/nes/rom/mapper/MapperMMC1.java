package net.lomibao.nes.rom.mapper;

/**
 * iNES Mapper 1 — MMC1 (Nintendo's first major mapper chip).
 *
 * <p>MMC1 uses a <b>serial protocol</b> to write its four internal 5-bit
 * configuration registers: each CPU write to {@code $8000-$FFFF} shifts
 * bit 0 of the value into a 5-bit shift register from the right. After
 * five non-reset writes, the accumulated 5-bit value is committed to one
 * of four destination registers, selected by bits 13-14 of the WRITE
 * address:
 *
 * <ul>
 *   <li>{@code $8000-$9FFF} → Control register</li>
 *   <li>{@code $A000-$BFFF} → CHR bank 0 register</li>
 *   <li>{@code $C000-$DFFF} → CHR bank 1 register</li>
 *   <li>{@code $E000-$FFFF} → PRG bank register</li>
 * </ul>
 *
 * <p>Any write with <b>bit 7 set</b> resets the shift register (clears the
 * shift count) AND OR's {@code 0x0C} into the Control register, putting
 * the chip into "16KB PRG, last bank fixed at $C000-$FFFF" mode — the
 * standard boot configuration the NES reset vector expects.
 *
 * <p><b>Control register layout (5 bits):</b>
 * <ul>
 *   <li>bits 0-1: mirroring (00=ONESCREEN_LO, 01=ONESCREEN_HI,
 *       10=VERTICAL, 11=HORIZONTAL)</li>
 *   <li>bits 2-3: PRG mode (00/01=32KB switchable, 10=16KB switchable
 *       at $C000-$FFFF with $8000-$BFFF fixed to FIRST bank,
 *       11=16KB switchable at $8000-$BFFF with $C000-$FFFF fixed to
 *       LAST bank)</li>
 *   <li>bit 4: CHR mode (0=8KB, 1=4KB)</li>
 * </ul>
 *
 * <p><b>CHR bank registers</b> are 5-bit 4KB-bank indexes. In 8KB CHR
 * mode (Control bit 4 = 0), CHR bank 0's low bit is ignored and the
 * bank is treated as an 8KB index covering both halves of the CHR
 * window; CHR bank 1 is unused. Some carts (SNROM/SUROM) repurpose the
 * high CHR-bank bits for PRG-RAM enable and PRG bank A18 — those use
 * cases are <em>ignored</em> by this implementation; we store all 5
 * bits but only use them as CHR bank indexes.
 *
 * <p><b>PRG bank register</b> is a 5-bit 16KB-bank index. Per the spec,
 * bit 4 may be PRG-RAM enable — for compatibility with all licensed
 * Mapper-1 titles up to 256KB, we mask the bank to 4 bits when
 * computing the PRG offset.
 *
 * <p><b>Power-on state:</b> per the NESdev wiki the Control register
 * starts in the bit-7-reset state (control |= 0x0C), so PRG mode 3 with
 * the last bank fixed at $C000-$FFFF. Other registers are technically
 * indeterminate but treating them as 0 is safe and matches every other
 * emulator's convention.
 *
 * <p>Iconic title: <i>The Legend of Zelda</i> (Nintendo, 1987). Spec:
 * <a href="https://www.nesdev.org/wiki/MMC1">NESdev wiki — MMC1</a>.
 */
public class MapperMMC1 implements Mapper {

    private static final int PRG_16K = 0x4000;
    private static final int PRG_32K = 0x8000;
    private static final int CHR_4K  = 0x1000;
    private static final int CHR_8K  = 0x2000;

    /** Mask applied to PRG bank register before computing offset (4-bit). */
    private static final int PRG_BANK_MASK = 0x0F;
    /** Mask applied to CHR bank register (5-bit). */
    private static final int CHR_BANK_MASK = 0x1F;
    /** Mask applied to a 5-bit shift result. */
    private static final int SHIFT_FIVE_BIT_MASK = 0x1F;
    /** Bit forced into Control by a bit-7-set write (PRG mode 3). */
    private static final int CONTROL_RESET_OR = 0x0C;

    private final int nPRGBanks;
    private final int nCHRBanks;

    // ---- Internal registers ------------------------------------------

    /** Five-bit shift register. */
    private int shiftRegister;
    /** Count of writes accumulated into the shift register (0..4). */
    private int shiftCount;

    /** Control register (5-bit). */
    private int control;
    /** CHR bank 0 register (5-bit). */
    private int chrBank0;
    /** CHR bank 1 register (5-bit). */
    private int chrBank1;
    /** PRG bank register (5-bit). */
    private int prgBank;

    public MapperMMC1(int prgBanks, int chrBanks) {
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
        int prgMode = (control >> 2) & 0x03;
        // Wrap the 4-bit bank register to the cart's actual bank count —
        // hardware ignores unwired upper address lines, so a register value
        // beyond the ROM size wraps rather than indexing past the end.
        int bank = (prgBank & PRG_BANK_MASK) % nPRGBanks;
        switch (prgMode) {
            case 0: case 1: {
                // 32KB switchable: low bit of bank is ignored. Wrap at 32KB
                // granularity — a 16KB-level wrap on an odd (malformed) bank
                // count could pair the last bank with one past the end.
                int base = ((bank >> 1) % Math.max(1, nPRGBanks / 2)) * PRG_32K;
                return base + (address - 0x8000);  // offset within 32KB pair
            }
            case 2: {
                // 16KB switchable at $C000-$FFFF, $8000-$BFFF fixed to FIRST bank.
                if (address <= 0xBFFF) {
                    return (address - 0x8000);  // first bank
                }
                return bank * PRG_16K + (address - 0xC000);
            }
            case 3:
            default: {
                // 16KB switchable at $8000-$BFFF, $C000-$FFFF fixed to LAST bank.
                if (address <= 0xBFFF) {
                    return bank * PRG_16K + (address - 0x8000);
                }
                int lastBank = (nPRGBanks - 1);
                return lastBank * PRG_16K + (address - 0xC000);
            }
        }
    }

    @Override
    public int cpuMapWrite(int address) {
        // Address-only form: cannot drive the serial protocol without
        // the byte value. Cartridge.cpuBusWrite uses the 2-arg overload.
        return UNMAPPED;
    }

    @Override
    public int cpuMapWrite(int address, int value) {
        if (address < 0x8000 || address > 0xFFFF) {
            return UNMAPPED;
        }

        // Bit 7 of value → reset shifter + OR $0C into control.
        if ((value & 0x80) != 0) {
            shiftRegister = 0;
            shiftCount = 0;
            control |= CONTROL_RESET_OR;
            return UNMAPPED;
        }

        // Otherwise: shift bit 0 into the register (LSB-first).
        shiftRegister = (shiftRegister >>> 1) | ((value & 0x01) << 4);
        shiftCount++;

        if (shiftCount == 5) {
            // Commit the accumulated 5-bit value to one of four
            // destination registers based on bits 13-14 of the WRITE address.
            int destination = (address >> 13) & 0x03;
            int latched = shiftRegister & SHIFT_FIVE_BIT_MASK;
            switch (destination) {
                case 0:  // $8000-$9FFF
                    control = latched;
                    break;
                case 1:  // $A000-$BFFF
                    chrBank0 = latched & CHR_BANK_MASK;
                    break;
                case 2:  // $C000-$DFFF
                    chrBank1 = latched & CHR_BANK_MASK;
                    break;
                case 3:  // $E000-$FFFF
                    prgBank = latched;
                    break;
            }
            // Reset the shifter for the next sequence.
            shiftRegister = 0;
            shiftCount = 0;
        }
        return UNMAPPED;
    }

    // ---- PPU side ----------------------------------------------------

    @Override
    public int ppuMapRead(int address) {
        return chrTranslate(address);
    }

    /**
     * Shared CHR address translation for reads and CHR-RAM writes. Bank
     * indexes wrap to the cart's actual 4KB CHR bank count (CHR-RAM carts:
     * 8KB = 2 banks) — SNROM-class boards latch PRG-RAM control bits into
     * chrBank0's upper bits, and hardware wraps those out via unwired
     * address lines rather than blanking the graphics.
     */
    private int chrTranslate(int address) {
        if (address < 0x0000 || address > 0x1FFF) {
            return UNMAPPED;
        }
        int chr4kCount = Math.max(1, (nCHRBanks == 0 ? 1 : nCHRBanks) * 2);
        boolean fourKbMode = (control & 0x10) != 0;
        if (fourKbMode) {
            // Two independent 4KB banks.
            if (address < 0x1000) {
                return ((chrBank0 & CHR_BANK_MASK) % chr4kCount) * CHR_4K + address;
            }
            return ((chrBank1 & CHR_BANK_MASK) % chr4kCount) * CHR_4K + (address - 0x1000);
        }
        // 8KB mode: CHR bank 0 holds the 8KB bank, low bit ignored.
        int bank8 = ((chrBank0 & CHR_BANK_MASK) >> 1) % Math.max(1, chr4kCount / 2);
        return bank8 * CHR_8K + address;
    }

    @Override
    public int ppuMapWrite(int address) {
        // CHR-RAM cart (nCHRBanks == 0): writes land at the same
        // bank-translated offset as reads — with a nonzero CHR bank
        // latched, an untranslated write would land in bank 0 while
        // reads came from the banked offset. CHR-ROM is read-only.
        if (nCHRBanks == 0) {
            return chrTranslate(address);
        }
        return UNMAPPED;
    }

    // ---- Lifecycle / metadata ----------------------------------------

    @Override
    public void reset() {
        shiftRegister = 0;
        shiftCount = 0;
        // Power-on: control starts in bit-7-reset state (|= 0x0C).
        control = CONTROL_RESET_OR;
        chrBank0 = 0;
        chrBank1 = 0;
        prgBank = 0;
    }

    @Override
    public boolean reqState() {
        // MMC1 has no IRQ source.
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
        switch (control & 0x03) {
            case 0: return Mirror.ONESCREEN_LO;
            case 1: return Mirror.ONESCREEN_HI;
            case 2: return Mirror.VERTICAL;
            case 3:
            default: return Mirror.HORIZONTAL;
        }
    }
}
