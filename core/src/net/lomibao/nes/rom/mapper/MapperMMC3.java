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

    /** R0..R7, each a 6-bit bank index. */
    private final int[] bankReg = new int[8];

    /** Bank-select state from the last $8000-$9FFE even-address write. */
    private int bankSelectIndex;

    public MapperMMC3(int prgBanks, int chrBanks) {
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
        // D1: PRG mode 0 only. R6 at $8000-$9FFF, R7 at $A000-$BFFF.
        // $C000-$FFFF is implemented in D2 alongside the mode bit.
        int bank;
        int windowBase;
        if (address < 0xA000) {              // $8000-$9FFF
            bank = bankReg[6];
            windowBase = 0x8000;
        } else if (address < 0xC000) {       // $A000-$BFFF
            bank = bankReg[7];
            windowBase = 0xA000;
        } else {
            // D2 will refine; for D1 fall back to a deterministic mapping.
            return UNMAPPED;
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
        // D1: CHR-invert=0 layout only.
        // Slots 0,1 use R0 (2KB); slots 2,3 use R1 (2KB);
        // slots 4-7 use R2/R3/R4/R5 (1KB each).
        int slot = (address >> 10) & 0x07;
        int bank;
        if (slot < 2) {
            int twoKbBank = bankReg[0] & 0x3E;
            bank = twoKbBank + (slot & 0x01);
        } else if (slot < 4) {
            int twoKbBank = bankReg[1] & 0x3E;
            bank = twoKbBank + (slot & 0x01);
        } else {
            bank = bankReg[slot - 2] & 0x3F;   // slot 4→R2, 5→R3, 6→R4, 7→R5
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
