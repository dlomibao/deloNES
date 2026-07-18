package net.lomibao.nes.components.ppu;

import net.lomibao.nes.components.PPUBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase A2 — SINGLE_SCREEN_LO and SINGLE_SCREEN_HI both alias all four
 * nametables together, but to different physical VRAM pages. MMC1 uses
 * this distinction to flip between the two pages via its control reg.
 */
class NameTableMemorySingleScreenTest {

    private NameTableMemory nt;

    @BeforeEach
    void setUp() {
        nt = new NameTableMemory();
        PPUBus bus = new PPUBus();
        bus.connect(nt);
    }

    @Test
    void singleScreenLo_allFourNametablesAliasToPage0() {
        nt.setMirroringOverride(MirroringMode.SINGLE_SCREEN_LO);
        nt.ppuBusWrite(0x2000, (byte) 0xAA);
        // All four logical nametable bases must read 0xAA.
        assertEquals(0xAA, nt.ppuBusRead(0x2000, false));
        assertEquals(0xAA, nt.ppuBusRead(0x2400, false));
        assertEquals(0xAA, nt.ppuBusRead(0x2800, false));
        assertEquals(0xAA, nt.ppuBusRead(0x2C00, false));
    }

    @Test
    void singleScreenHi_allFourNametablesAliasToPage1() {
        nt.setMirroringOverride(MirroringMode.SINGLE_SCREEN_HI);
        nt.ppuBusWrite(0x2000, (byte) 0xBB);
        assertEquals(0xBB, nt.ppuBusRead(0x2000, false));
        assertEquals(0xBB, nt.ppuBusRead(0x2400, false));
        assertEquals(0xBB, nt.ppuBusRead(0x2800, false));
        assertEquals(0xBB, nt.ppuBusRead(0x2C00, false));
    }

    @Test
    void singleScreenLo_and_HI_useDistinctPhysicalPages() {
        // Write 0x11 with LO, then flip to HI and write 0x22.
        // Flipping back to LO must still see 0x11.
        nt.setMirroringOverride(MirroringMode.SINGLE_SCREEN_LO);
        nt.ppuBusWrite(0x2000, (byte) 0x11);
        nt.setMirroringOverride(MirroringMode.SINGLE_SCREEN_HI);
        nt.ppuBusWrite(0x2000, (byte) 0x22);
        assertEquals(0x22, nt.ppuBusRead(0x2000, false), "HI page reads 0x22");
        nt.setMirroringOverride(MirroringMode.SINGLE_SCREEN_LO);
        assertEquals(0x11, nt.ppuBusRead(0x2000, false), "LO page retained 0x11");
    }

    @Test
    void mirroringModeEnum_hasBothLOAndHIVariants() {
        // Compile-time check: forces a fail if anyone removes either.
        MirroringMode lo = MirroringMode.SINGLE_SCREEN_LO;
        MirroringMode hi = MirroringMode.SINGLE_SCREEN_HI;
        assertNotEquals(lo, hi);
    }
}
