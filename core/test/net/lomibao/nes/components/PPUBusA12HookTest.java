package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.Mapper;
import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase A3 — PPU A12 rising-edge notification reaches the cartridge's
 * mapper. MMC3 (Phase D) wires its scanline-IRQ counter to this hook;
 * UNROM-512 (Phase E) doesn't care; everything else gets the default
 * no-op {@link Mapper#tickPpuA12(int, int)}.
 *
 * <p>A12 = bit 12 of the PPU bus address. A rising edge happens when
 * the previous bus address had bit 12 clear and the new one has it set.
 */
class PPUBusA12HookTest {

    /**
     * Cartridge subclass with an embedded counter-mapper, so tests can
     * observe A12 events without spinning up a real mapper implementation
     * yet. Phase D will replace this with MMC3's tick path.
     */
    static class CountingMirrorCartridge extends Cartridge {
        int risingEdges = 0;
        int totalNotifications = 0;
        int lastAddress = -1;
        int lastPrevAddress = -1;

        CountingMirrorCartridge() {
            super(new ByteArrayInputStream(
                            MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null)),
                    "a12-hook-test.nes");
        }

        @Override
        public void notifyPpuA12(int address, int previousAddress) {
            totalNotifications++;
            lastAddress = address;
            lastPrevAddress = previousAddress;
            boolean prevHigh = (previousAddress & 0x1000) != 0;
            boolean nowHigh  = (address & 0x1000) != 0;
            if (!prevHigh && nowHigh) {
                risingEdges++;
            }
        }
    }

    @Test
    void readWithA12Low_thenHigh_countsOneRisingEdge() {
        CountingMirrorCartridge cart = new CountingMirrorCartridge();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        bus.read(0x0000); // A12 low
        bus.read(0x1000); // A12 high  → rising edge
        assertEquals(1, cart.risingEdges);
    }

    @Test
    void readWithA12High_thenLow_doesNotCountRisingEdge() {
        CountingMirrorCartridge cart = new CountingMirrorCartridge();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        bus.read(0x1000); // baseline first
        cart.risingEdges = 0;
        bus.read(0x0000); // falling edge → not counted
        assertEquals(0, cart.risingEdges);
    }

    @Test
    void readWithA12StableHigh_doesNotCountRisingEdge() {
        CountingMirrorCartridge cart = new CountingMirrorCartridge();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        bus.read(0x1000);
        cart.risingEdges = 0;
        bus.read(0x1234); // A12 still high
        bus.read(0x1FFF); // still high
        assertEquals(0, cart.risingEdges);
    }

    @Test
    void multipleTransitions_countOnlyRisingEdges() {
        CountingMirrorCartridge cart = new CountingMirrorCartridge();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        // Simulate a scanline: low (BG fetches) → high (sprite fetches) →
        // low (next scanline BG fetches) → high again. Two rising edges.
        bus.read(0x0000);
        bus.read(0x0008);
        bus.read(0x1000); // rising 1
        bus.read(0x1008);
        bus.read(0x0000); // falling
        bus.read(0x1000); // rising 2
        assertEquals(2, cart.risingEdges);
    }

    @Test
    void writes_alsoDriveA12Hook() {
        // $2006 register writes change the PPU address even though it's
        // a write path. MMC3 sees them as A12 transitions.
        CountingMirrorCartridge cart = new CountingMirrorCartridge();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        bus.read(0x0000);              // A12 low baseline
        bus.write(0x1000, (byte) 0x42); // A12 high → rising
        assertEquals(1, cart.risingEdges);
    }

    @Test
    void mapper000_defaultTickPpuA12_isNoOpAndDoesNotThrow() {
        // Regression guard: Mapper000 inherits the interface default and
        // must accept calls without state corruption.
        net.lomibao.nes.rom.mapper.Mapper000 m =
                new net.lomibao.nes.rom.mapper.Mapper000(1, 1);
        assertDoesNotThrow(() -> m.tickPpuA12(0x1000, 0x0000));
        assertDoesNotThrow(() -> m.tickPpuA12(0x0000, 0x1FFF));
    }

    @Test
    void cartridgeWithoutOverride_routesNotificationToMapper() {
        // Default Cartridge.notifyPpuA12 must delegate to mapper.tickPpuA12.
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        Cartridge cart = new Cartridge(new ByteArrayInputStream(rom), "delegate.nes");
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);
        // Just ensures the bus->cart->mapper path doesn't crash for the
        // default no-op mapper.
        assertDoesNotThrow(() -> {
            bus.read(0x0000);
            bus.read(0x1000);
        });
    }

    @Test
    void notification_passesCurrentAndPreviousAddress() {
        CountingMirrorCartridge cart = new CountingMirrorCartridge();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        bus.read(0x0123);
        bus.read(0x1ABC);
        assertEquals(0x1ABC, cart.lastAddress);
        assertEquals(0x0123, cart.lastPrevAddress);
    }
}
