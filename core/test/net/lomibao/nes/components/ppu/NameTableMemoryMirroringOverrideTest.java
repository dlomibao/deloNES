package net.lomibao.nes.components.ppu;

import net.lomibao.nes.components.PPUBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NameTableMemory#setMirroringOverride(MirroringMode)}
 * (introduced in Step 7). Pinpoints the override semantics so future
 * MMC1+ runtime mirroring work has a clear contract to extend.
 */
class NameTableMemoryMirroringOverrideTest {

    private NameTableMemory nt;

    @BeforeEach
    void setUp() {
        nt = new NameTableMemory();
        // Wire to a bus so writes/reads route through the mirroring logic.
        PPUBus bus = new PPUBus();
        bus.connect(nt);
    }

    @Test
    void override_takesPrecedenceOverDefault_horizontal() {
        // Default (no cartridge, no override) = HORIZONTAL.
        // Override to VERTICAL: $2400 should now be independent from $2000.
        nt.setMirroringOverride(MirroringMode.VERTICAL);
        nt.ppuBusWrite(0x2000, (byte) 0xAA);
        nt.ppuBusWrite(0x2400, (byte) 0xBB);
        assertEquals(0xAA, nt.ppuBusRead(0x2000, false), "$2000 keeps its value");
        assertEquals(0xBB, nt.ppuBusRead(0x2400, false),
                "vertical mirroring → $2400 is independent of $2000");
    }

    @Test
    void overrideHorizontal_makes2000_and_2400_alias() {
        nt.setMirroringOverride(MirroringMode.HORIZONTAL);
        nt.ppuBusWrite(0x2000, (byte) 0x77);
        // Horizontal mirroring: $2000 + $2400 share top-half VRAM page.
        assertEquals(0x77, nt.ppuBusRead(0x2400, false),
                "horizontal mirroring → $2400 reads from same physical page as $2000");
    }

    @Test
    void overrideNull_revertsToCartridgeDerivedMode() {
        // Set then unset.
        nt.setMirroringOverride(MirroringMode.VERTICAL);
        nt.ppuBusWrite(0x2000, (byte) 0x11);
        nt.ppuBusWrite(0x2400, (byte) 0x22);
        // Sanity: vertical → independent.
        assertEquals(0x22, nt.ppuBusRead(0x2400, false));
        // Unset override → falls back to default (no cartridge → HORIZONTAL).
        nt.setMirroringOverride(null);
        // Now $2400 should mirror $2000.
        nt.ppuBusWrite(0x2000, (byte) 0x33);
        assertEquals(0x33, nt.ppuBusRead(0x2400, false),
                "after override cleared, default horizontal mirroring restored");
    }
}
