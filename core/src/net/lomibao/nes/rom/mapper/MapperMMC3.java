package net.lomibao.nes.rom.mapper;

/**
 * iNES Mapper 4 — MMC3 (Nintendo's most prolific mapper chip).
 *
 * <p>MMC3 has 8 internal 6-bit bank registers (R0-R7) written via a
 * two-step protocol against the {@code $8000-$9FFE} pair (even = bank
 * select, odd = bank data). Additional even/odd pairs at {@code $A000},
 * {@code $C000}, and {@code $E000} control mirroring, the IRQ latch /
 * reload, and IRQ enable/disable respectively (registered in later
 * sub-stages).
 *
 * <p><b>Bank registers:</b>
 * <ul>
 *   <li>R0, R1 — 2KB CHR banks (low bit ignored)</li>
 *   <li>R2, R3, R4, R5 — 1KB CHR banks</li>
 *   <li>R6, R7 — 8KB PRG banks</li>
 * </ul>
 *
 * <p>This file is built up across sub-stages D1..D6; see
 * {@code docs/mapper-plan.md} for the spec and
 * {@link MapperMMC3Test} for the unit tests.
 *
 * <p>Iconic title: <i>Super Mario Bros. 3</i> (Nintendo, 1990). Spec:
 * <a href="https://www.nesdev.org/wiki/MMC3">NESdev wiki — MMC3</a>.
 */
public class MapperMMC3 implements Mapper {

    private static final int PRG_8K  = 0x2000;
    private static final int CHR_1K  = 0x0400;

    private final int nPRGBanks;     // 16KB units
    private final int nCHRBanks;     // 8KB units
    /** Total 8KB PRG banks (= nPRGBanks * 2). */
    private final int prg8kCount;

    /** R0..R7, each a 6-bit bank index. */
    private final int[] bankReg = new int[8];

    /** Bank-select state from the last $8000-$9FFE even-address write. */
    private int bankSelectIndex;
    /** PRG mode bit (bit 6 of $8000). false = mode 0, true = mode 1. */
    private boolean prgMode;
    /** CHR A12-invert bit (bit 7 of $8000). */
    private boolean chrInvert;

    public MapperMMC3(int prgBanks, int chrBanks) {
        this.nPRGBanks = prgBanks;
        this.nCHRBanks = chrBanks;
        this.prg8kCount = prgBanks * 2;
        reset();
    }

    // ---- CPU side ----------------------------------------------------

    @Override
    public int cpuMapRead(int address) {
        if (address < 0x8000 || address > 0xFFFF) {
            return UNMAPPED;
        }
        final int lastBank = prg8kCount - 1;
        final int penult = prg8kCount - 2;
        int bank;
        int windowBase;
        if (address < 0xA000) {              // $8000-$9FFF
            bank = prgMode ? penult : bankReg[6];
            windowBase = 0x8000;
        } else if (address < 0xC000) {       // $A000-$BFFF
            bank = bankReg[7];
            windowBase = 0xA000;
        } else if (address < 0xE000) {       // $C000-$DFFF
            bank = prgMode ? bankReg[6] : penult;
            windowBase = 0xC000;
        } else {                             // $E000-$FFFF — always last
            bank = lastBank;
            windowBase = 0xE000;
        }
        return bank * PRG_8K + (address - windowBase);
    }

    @Override
    public int cpuMapWrite(int address) {
        // Address-only legacy form: cannot mutate registers without value.
        return UNMAPPED;
    }

    @Override
    public int cpuMapWrite(int address, int value) {
        if (address < 0x8000 || address > 0xFFFF) {
            return UNMAPPED;
        }
        boolean even = (address & 0x0001) == 0;
        int region = (address >> 13) & 0x03;
        if (region == 0) {                   // $8000-$9FFF: bank-select / bank-data
            if (even) {
                bankSelectIndex = value & 0x07;
                prgMode = (value & 0x40) != 0;
                chrInvert = (value & 0x80) != 0;
            } else {
                bankReg[bankSelectIndex] = value & 0x3F;
            }
        }
        // Other regions ($A000+) wired up in later sub-stages.
        return UNMAPPED;
    }

    // ---- PPU side ----------------------------------------------------

    @Override
    public int ppuMapRead(int address) {
        if (address < 0x0000 || address > 0x1FFF) {
            return UNMAPPED;
        }
        // The 8KB CHR space splits into eight 1KB slots by address bits
        // 10-12. CHR-invert swaps the two 4KB halves:
        //   invert=0 layout: [R0 R0 R1 R1 R2 R3 R4 R5]
        //   invert=1 layout: [R2 R3 R4 R5 R0 R0 R1 R1]
        int slot = (address >> 10) & 0x07;
        boolean lowHalf = slot < 4;
        int bank;
        if (!chrInvert) {
            if (lowHalf) {
                // slots 0,1 → R0 (2KB); slots 2,3 → R1 (2KB).
                int r = (slot < 2) ? 0 : 1;
                int twoKbBank = bankReg[r] & 0x3E;
                bank = twoKbBank + (slot & 0x01);
            } else {
                // slot 4→R2, 5→R3, 6→R4, 7→R5 (1KB each).
                bank = bankReg[slot - 2] & 0x3F;
            }
        } else {
            if (lowHalf) {
                // slot 0→R2, 1→R3, 2→R4, 3→R5 (1KB each).
                bank = bankReg[slot + 2] & 0x3F;
            } else {
                // slots 4,5 → R0 (2KB); slots 6,7 → R1 (2KB).
                int r = (slot < 6) ? 0 : 1;
                int twoKbBank = bankReg[r] & 0x3E;
                bank = twoKbBank + (slot & 0x01);
            }
        }
        return bank * CHR_1K + (address & 0x03FF);
    }

    @Override
    public int ppuMapWrite(int address) {
        return UNMAPPED;
    }

    // ---- Lifecycle / metadata ----------------------------------------

    @Override
    public void reset() {
        for (int i = 0; i < 8; i++) {
            bankReg[i] = 0;
        }
        bankSelectIndex = 0;
        prgMode = false;
        chrInvert = false;
    }

    @Override
    public boolean reqState() {
        return false;
    }

    @Override
    public void irqClear() {
        // no-op until D6
    }

    @Override
    public void scanLine() {
        // MMC3 uses A12-driven IRQ (D6), not scanLine-driven.
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
        // D4 will compute from $A000 register.
        return Mirror.HARDWARE;
    }
}
