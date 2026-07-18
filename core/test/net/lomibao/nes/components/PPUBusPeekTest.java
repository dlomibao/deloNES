package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 contract at the {@link PPUBus#peek(int)} layer: peeks must not clock
 * the mapper's A12 line. Phase B review found the census bit-identical
 * gate structurally unable to catch this (it runs on NROM, where A12
 * notification is inert) — this test uses a synthetic MMC3 cart with an
 * armed IRQ counter, where a peek that leaked through
 * {@code notifyPpuA12} would fire the scanline IRQ.
 */
class PPUBusPeekTest {

    /** Synthetic MMC3 (mapper 4) cart: 2x16KB PRG + 1x8KB CHR. */
    private static Cartridge mmc3Cart() {
        byte[] rom = new byte[16 + 2 * 16384 + 8192];
        rom[0] = 'N'; rom[1] = 'E'; rom[2] = 'S'; rom[3] = 0x1A;
        rom[4] = 2;
        rom[5] = 1;
        rom[6] = 0x40; // mapper 4 low nibble
        return new Cartridge(new ByteArrayInputStream(rom), "mmc3-peek.nes");
    }

    @Test
    void peek_doesNotClockMapperA12_irqStaysSilent() {
        Cartridge cart = mmc3Cart();
        PPUBus bus = new PPUBus();
        bus.connectCartridge(cart);

        // Arm the MMC3 IRQ counter: latch 1, reload, enable. Two filtered
        // A12 rising edges from here would assert the IRQ line.
        cart.cpuBusWrite(0xC000, (byte) 0x01);
        cart.cpuBusWrite(0xC001, (byte) 0x00);
        cart.cpuBusWrite(0xE001, (byte) 0x00);

        // Drive peeks across the A12 boundary with the 4-low-tick spacing
        // MMC3's edge filter requires — far more edges than the counter needs.
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 5; j++) {
                bus.peek(0x0000);
            }
            bus.peek(0x1000);
        }
        assertFalse(cart.mapperIrqPending(),
                "peeks must not clock A12 — an armed MMC3 counter fired from observation");

        // Sanity: the same access pattern through the REAL read path does
        // clock the counter, proving this test can detect the leak.
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 5; j++) {
                bus.read(0x0000);
            }
            bus.read(0x1000);
        }
        assertTrue(cart.mapperIrqPending(),
                "real reads must clock A12 — if this fails the test rig is wrong");
    }
}
