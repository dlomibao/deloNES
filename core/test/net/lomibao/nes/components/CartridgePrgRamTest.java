package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Seam S1 of docs/apu-plan.md (Phase A0): cartridge PRG-RAM at
 * $6000-$7FFF, CPU-bus write routing scoped to $4020-$7FFF, and the
 * defensive Mapper000 {@code cpuMapWrite → UNMAPPED} fix.
 *
 * <p>Covers all four parts of the S1 spec:
 * <ol>
 *   <li>dedicated zero-filled 8KB PRG-RAM storage in {@link Cartridge}</li>
 *   <li>$6000-$7FFF read AND write branches ahead of the mapper</li>
 *   <li>bus write routing to the cartridge for $4020-$7FFF ONLY
 *       ($8000-$FFFF writes stay dropped at the bus)</li>
 *   <li>Mapper000 writes never reach {@code vPRGMemory} (RED test —
 *       the unfixed mapper let $8000+ stores corrupt NROM PRG-ROM)</li>
 * </ol>
 */
class CartridgePrgRamTest {

    /** Sentinel byte placed at PRG offset 0 ($8000/$C000) of the synthetic ROM. */
    private static final int ROM_SENTINEL = 0x42;

    private Cartridge cart;
    private CPUBus bus;

    /** Minimal NROM-128 image: 16KB PRG (sentinel at offset 0), 8KB CHR. */
    private static byte[] nromImage() {
        byte[] rom = new byte[16 + 16 * 1024 + 8 * 1024];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1; // 1 × 16KB PRG
        rom[5] = 1; // 1 × 8KB CHR
        rom[16] = (byte) ROM_SENTINEL; // PRG offset 0 → CPU $8000 and $C000
        return rom;
    }

    @BeforeEach
    void setUp() {
        cart = new Cartridge(new ByteArrayInputStream(nromImage()), "prg-ram-test.nes");
        bus = CPUBus.builder()
                .ram(new Ram())
                .cartridge(cart)
                .build()
                .connect();
    }

    // ---------------------------------------------------------------------
    // Part 1 — storage
    // ---------------------------------------------------------------------

    @Test
    void prgRam_zeroFilledAtBoot() {
        assertEquals(0, bus.read(0x6000, true), "$6000 must boot as 0");
        assertEquals(0, bus.read(0x7000, true), "$7000 must boot as 0");
        assertEquals(0, bus.read(0x7FFF, true), "$7FFF must boot as 0");
    }

    // ---------------------------------------------------------------------
    // Part 2 + 3 — cartridge branches and scoped bus routing
    // ---------------------------------------------------------------------

    @Test
    void busWriteThenBusRead_roundTripsThroughPrgRam() {
        bus.write(0x6000, (byte) 0xDE);
        bus.write(0x7FFF, (byte) 0x61);
        assertEquals(0xDE, bus.read(0x6000), "bus $6000 write→read round-trip");
        assertEquals(0x61, bus.read(0x7FFF), "bus $7FFF write→read round-trip");
    }

    @Test
    void cartridgeWrite_visibleThroughBusRead() {
        // Direction 1 of the read fix: a direct cartridge write is seen by
        // the bus read path (which already routed $4020-$FFFF to the cart).
        cart.cpuBusWrite(0x6123, (byte) 0xB0);
        assertEquals(0xB0, bus.read(0x6123, true));
    }

    @Test
    void busWrite_visibleThroughCartridgeRead() {
        // Direction 2: a bus write reaches the cartridge's PRG-RAM branch.
        bus.write(0x7ABC, (byte) 0x5A);
        assertEquals(0x5A, cart.cpuBusRead(0x7ABC, true));
    }

    @Test
    void prgRamWindow_doesNotShadowRom() {
        // Filling PRG-RAM never leaks into the ROM window.
        bus.write(0x6000, (byte) 0xFF);
        assertEquals(ROM_SENTINEL, bus.read(0x8000, true),
                "PRG-ROM at $8000 must be untouched by PRG-RAM writes");
        assertEquals(ROM_SENTINEL, bus.read(0xC000, true),
                "NROM-128 mirror at $C000 must be untouched");
    }

    // ---------------------------------------------------------------------
    // Part 4 — Mapper000 cpuMapWrite → UNMAPPED (RED: the unfixed mapper
    // returned $8000+ offsets and let this write corrupt vPRGMemory)
    // ---------------------------------------------------------------------

    @Test
    void cartridgeWriteTo8000_doesNotAlterPrgRom() {
        cart.cpuBusWrite(0x8000, (byte) 0x99);
        assertEquals(ROM_SENTINEL, cart.cpuBusRead(0x8000, true),
                "Mapper000 cpuMapWrite must be UNMAPPED — NROM PRG is ROM");
        assertEquals(ROM_SENTINEL, cart.cpuBusRead(0xC000, true),
                "NROM-128 mirror must also be untouched");
    }

    @Test
    void busWriteTo8000Plus_stillDroppedAtBus() {
        // Routing stays scoped to $4020-$7FFF: $8000-$FFFF bus writes keep
        // today's drop-at-the-bus behavior and corrupt nothing.
        bus.write(0x8000, (byte) 0x99);
        bus.write(0xFFFF, (byte) 0x99);
        assertEquals(ROM_SENTINEL, bus.read(0x8000, true), "ROM unchanged");
        assertEquals(0, bus.read(0x6000, true), "PRG-RAM unchanged");
    }

    @Test
    void writeTo4020To5FFF_reachesCartridgeAndIsDroppedHarmlessly() {
        bus.write(0x4020, (byte) 0x77);
        bus.write(0x5FFF, (byte) 0x77);
        // Genuinely unmapped: reads return 0, nothing was corrupted.
        assertEquals(0, bus.read(0x4020, true));
        assertEquals(0, bus.read(0x5FFF, true));
        assertEquals(ROM_SENTINEL, bus.read(0x8000, true), "ROM unchanged");
        assertEquals(0, bus.read(0x6000, true), "PRG-RAM unchanged");
    }
}
