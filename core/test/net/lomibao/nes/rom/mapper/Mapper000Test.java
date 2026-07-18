package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link Mapper000} (NROM). Verifies the
 * fixed/mirrored PRG layouts, CHR pass-through with CHR-ROM vs CHR-RAM,
 * out-of-range UNMAPPED sentinel behaviour, and that the
 * IRQ/scanline/reset stubs are callable as no-ops. This is the canonical
 * baseline against which Phase B mappers will be compared.
 */
class Mapper000Test {

    // ---- PRG mapping (16KB single-bank mirrored, 32KB dual-bank) ----

    @Test
    void cpuMapRead_16KB_mirrorsLowerHalfIntoUpperHalf() {
        Mapper000 m = new Mapper000(1, 1);
        // $8000 → offset 0
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        // $BFFF → offset $3FFF (top of 16KB)
        assertEquals(0x3FFF, m.cpuMapRead(0xBFFF));
        // $C000 → mirrors back to offset 0 (16KB mirror)
        assertEquals(0x0000, m.cpuMapRead(0xC000));
        // $FFFF → mirrors to $3FFF
        assertEquals(0x3FFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapRead_32KB_flatMappingAcrossFullPRGRange() {
        Mapper000 m = new Mapper000(2, 1);
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(0x3FFF, m.cpuMapRead(0xBFFF));
        assertEquals(0x4000, m.cpuMapRead(0xC000));
        assertEquals(0x7FFF, m.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuMapRead_belowPrgRange_returnsUNMAPPED() {
        Mapper000 m = new Mapper000(1, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x7FFF));
        assertEquals(Mapper.UNMAPPED, m.cpuMapRead(0x0000));
    }

    @Test
    void cpuMapWrite_inRange_returnsUNMAPPED() {
        // Seam S1 defensive fix (docs/apu-plan.md): NROM has no registers
        // and its PRG is ROM — cpuMapWrite must never hand back a
        // vPRGMemory offset, or stray stores corrupt PRG-ROM.
        Mapper000 m = new Mapper000(2, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xC000));
    }

    @Test
    void cpuMapWrite_outOfRange_returnsUNMAPPED() {
        Mapper000 m = new Mapper000(1, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000));
    }

    @Test
    void cpuMapWrite_16KB_alsoUNMAPPED() {
        // S1: UNMAPPED regardless of bank count — PRG is ROM either way.
        Mapper000 m = new Mapper000(1, 1);
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xC000));
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0xFFFF));
    }

    @Test
    void cpuMapWrite_2argDefault_delegatesTo1argForm() {
        // Mapper interface ships a default 2-arg cpuMapWrite that
        // delegates to the address-only form. NROM has no register
        // latching, so the 2-arg form should produce identical mapped
        // addresses to the 1-arg form regardless of the value byte.
        Mapper000 m = new Mapper000(2, 1);
        assertEquals(m.cpuMapWrite(0xC000), m.cpuMapWrite(0xC000, 0x55));
        assertEquals(m.cpuMapWrite(0xFFFF), m.cpuMapWrite(0xFFFF, 0xAA));
        // Out-of-range stays UNMAPPED on the 2-arg form too.
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000, 0x01));
    }

    @Test
    void getChrRamSize_default_is8KB() {
        // Mapper interface ships a default getChrRamSize() returning 8KB.
        // Mapper000 inherits this; UNROM-512 overrides to 32KB. Exercising
        // the default here keeps JaCoCo above ≥0.90 on the interface.
        Mapper000 m = new Mapper000(1, 0);
        assertEquals(8192, m.getChrRamSize());
    }

    // ---- CHR mapping (passthrough) ----

    @Test
    void ppuMapRead_inCHRRange_passesThrough() {
        Mapper000 m = new Mapper000(1, 1);
        assertEquals(0x0000, m.ppuMapRead(0x0000));
        assertEquals(0x1FFF, m.ppuMapRead(0x1FFF));
    }

    @Test
    void ppuMapRead_outOfCHRRange_returnsUNMAPPED() {
        Mapper000 m = new Mapper000(1, 1);
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x2000));
        assertEquals(Mapper.UNMAPPED, m.ppuMapRead(0x3000));
    }

    @Test
    void ppuMapWrite_chrROMcart_returnsUNMAPPED() {
        // CHR-ROM cart (nCHRBanks > 0) ignores PPU writes to pattern memory.
        Mapper000 m = new Mapper000(1, 1);
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x0100));
        assertEquals(Mapper.UNMAPPED, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_chrRAMcart_passesThrough() {
        // CHR-RAM cart (nCHRBanks == 0) lets writes through.
        Mapper000 m = new Mapper000(1, 0);
        assertEquals(0x0100, m.ppuMapWrite(0x0100));
        assertEquals(0x1FFF, m.ppuMapWrite(0x1FFF));
    }

    @Test
    void ppuMapWrite_outOfCHRRange_returnsUNMAPPED_regardlessOfCHRRAM() {
        Mapper000 chrRam = new Mapper000(1, 0);
        Mapper000 chrRom = new Mapper000(1, 1);
        assertEquals(Mapper.UNMAPPED, chrRam.ppuMapWrite(0x2000));
        assertEquals(Mapper.UNMAPPED, chrRom.ppuMapWrite(0x2000));
    }

    // ---- Bank counts and configuration ----

    @Test
    void numberOfPRGBanks_reflectsConstructorArg() {
        assertEquals(1, new Mapper000(1, 1).numberOfPRGBanks());
        assertEquals(2, new Mapper000(2, 1).numberOfPRGBanks());
    }

    @Test
    void numberOfCHRBanks_reflectsConstructorArg() {
        assertEquals(1, new Mapper000(1, 1).numberOfCHRBanks());
        assertEquals(0, new Mapper000(1, 0).numberOfCHRBanks());
    }

    @Test
    void mirror_isAlwaysHARDWARE_forNROM() {
        assertEquals(Mapper.Mirror.HARDWARE, new Mapper000(1, 1).mirror());
        assertEquals(Mapper.Mirror.HARDWARE, new Mapper000(2, 0).mirror());
    }

    // ---- IRQ + lifecycle stubs (no-ops, just exercise for coverage) ----

    @Test
    void resetAndStubs_doNotThrow() {
        Mapper000 m = new Mapper000(1, 1);
        m.reset();
        m.scanLine();
        m.irqClear();
        assertFalse(m.reqState(), "NROM has no IRQ source");
    }

    @Test
    void cpuMapWriteWithValue_defaultIsNoOp_forMapper000() {
        // Mapper000 inherits the default {@code cpuMapWrite(int, byte)}
        // from the {@link Mapper} interface. It must be safely callable
        // and have no observable side-effect — NROM has no registers.
        Mapper000 m = new Mapper000(1, 1);
        assertDoesNotThrow(() -> m.cpuMapWrite(0x8000, (byte) 0xFF));
        assertDoesNotThrow(() -> m.cpuMapWrite(0x0000, (byte) 0x00));
        // CHR / PRG mapping still works the same.
        assertEquals(0x0000, m.cpuMapRead(0x8000));
        assertEquals(0x0000, m.ppuMapRead(0x0000));
    }

    // ---- Enum coverage: the Mirror enum's synthetic values()/valueOf() ----

    @Test
    void mirrorEnum_hasFiveEntries_andRoundTripsByName() {
        Mapper.Mirror[] values = Mapper.Mirror.values();
        assertEquals(5, values.length);
        assertEquals(Mapper.Mirror.HARDWARE, Mapper.Mirror.valueOf("HARDWARE"));
        assertEquals(Mapper.Mirror.HORIZONTAL, Mapper.Mirror.valueOf("HORIZONTAL"));
        assertEquals(Mapper.Mirror.VERTICAL, Mapper.Mirror.valueOf("VERTICAL"));
        assertEquals(Mapper.Mirror.ONESCREEN_LO, Mapper.Mirror.valueOf("ONESCREEN_LO"));
        assertEquals(Mapper.Mirror.ONESCREEN_HI, Mapper.Mirror.valueOf("ONESCREEN_HI"));
    }

    // ---- Mapper interface default method coverage ----

    @Test
    void cpuMapWrite_valueOverload_defaultDelegatesToNoValueOverload() {
        // The Mapper interface added a value-carrying cpuMapWrite
        // overload in Phase B3 for register-write mappers. The default
        // impl delegates to the no-value version; exercise it here so
        // Mapper000 (which doesn't override) covers the default path.
        Mapper000 m = new Mapper000(1, 1);
        // In-range write: same mapped offset as cpuMapWrite(address).
        assertEquals(m.cpuMapWrite(0xC000), m.cpuMapWrite(0xC000, 0x42));
        // Out-of-range write: UNMAPPED.
        assertEquals(Mapper.UNMAPPED, m.cpuMapWrite(0x6000, 0x42));
    }
}
