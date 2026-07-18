package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase A1 — PPUBus routes pattern-table writes through the cartridge.
 * Complements {@link PPUBusTest} (which loaded a CHR-ROM cart from
 * nestest.nes and so couldn't exercise the CHR-RAM write path).
 */
class PPUBusChrWriteTest {

    private static Cartridge buildChrRamCart() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(
                0, /*prgKB=*/16, /*chrKB=*/0, null, null);
        return new Cartridge(new ByteArrayInputStream(rom), "chr-ram-bus-test.nes");
    }

    @Test
    void busWrite_toPatternTable_reachesCartridgeCHRRAM() {
        Cartridge cart = buildChrRamCart();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        bus.write(0x0500, (byte) 0x42);

        assertEquals(0x42, cart.chrRead(0x0500),
                "PPUBus.write must route $0000-$1FFF to cartridge.chrWrite");
    }

    @Test
    void busWrite_thenBusRead_roundTripsThroughCHRRAM() {
        Cartridge cart = buildChrRamCart();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        bus.write(0x0123, (byte) 0xDE);
        bus.write(0x1ABC, (byte) 0xAD);

        assertEquals(0xDE, bus.read(0x0123));
        assertEquals(0xAD, bus.read(0x1ABC));
    }

    @Test
    void busWrite_withNoCartridgeConnected_doesNotThrow() {
        PPUBus bus = new PPUBus();
        assertDoesNotThrow(() -> bus.write(0x0500, (byte) 0xFF));
    }
}
