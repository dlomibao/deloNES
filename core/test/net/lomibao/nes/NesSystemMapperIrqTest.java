package net.lomibao.nes;

import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.rom.RomLoader;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapper IRQ delivery: {@code NesSystem.tick()} must poll the cartridge's
 * mapper IRQ line (MMC3 scanline counter) and drive {@link CPU6502#irq()}.
 * Review finding, PR #33 round 1 — the IRQ counter existed but nothing
 * delivered it to the CPU, so MMC3 raster-split titles would hang.
 */
class NesSystemMapperIrqTest {

    private static final String OPCODE_CSV_RESOURCE = "/opcodes/opcodes.csv";

    /** Minimal MMC3 (mapper 4) iNES 1.0 ROM: 2×16KB PRG + 1×8KB CHR. */
    private static byte[] buildMmc3Rom() {
        int prgSize = 2 * 16384;
        byte[] rom = new byte[16 + prgSize + 8192];
        rom[0] = 'N'; rom[1] = 'E'; rom[2] = 'S'; rom[3] = 0x1A;
        rom[4] = 2;
        rom[5] = 1;
        rom[6] = 0x40;  // mapper 4 low nibble in byte 6 bits 4-7
        // Reset vector → $C000; IRQ vector ($FFFE) → $C100.
        rom[16 + prgSize - 4] = 0x00;
        rom[16 + prgSize - 3] = (byte) 0xC0;
        rom[16 + prgSize - 2] = 0x00;
        rom[16 + prgSize - 1] = (byte) 0xC1;
        return rom;
    }

    private static InputStream opcodeCsv() {
        InputStream in = CPU6502.class.getResourceAsStream(OPCODE_CSV_RESOURCE);
        assertNotNull(in, "opcodes.csv classpath resource missing");
        return in;
    }

    /** Drives enough filtered A12 rising edges to clock the IRQ counter to zero. */
    private static void clockIrqUntilPending(Cartridge cart, int edges) {
        for (int i = 0; i < edges; i++) {
            // 4+ low ticks satisfy the MMC3 A12 filter, then one rising edge.
            for (int lo = 0; lo < 5; lo++) {
                cart.notifyPpuA12(0x0000, 0x0000);
            }
            cart.notifyPpuA12(0x1000, 0x0000);
        }
    }

    @Test
    void mapperIrq_deliveredToCpu_whenInterruptsEnabled() {
        RomLoader.Loaded loaded = RomLoader.loadFromBytes(buildMmc3Rom(), "mmc3-irq.nes", opcodeCsv());
        Cartridge cart = loaded.cartridge;

        // Program the MMC3 IRQ: latch 1, reload, enable.
        cart.cpuBusWrite(0xC000, (byte) 0x01);  // latch = 1
        cart.cpuBusWrite(0xC001, (byte) 0x00);  // reload on next edge
        cart.cpuBusWrite(0xE001, (byte) 0x00);  // enable

        // Edge 1 reloads counter=1; edge 2 decrements to 0 → IRQ asserts.
        clockIrqUntilPending(cart, 2);
        assertTrue(cart.mapperIrqPending(), "MMC3 counter should assert the IRQ line");

        // CPU reset sets the I flag — clear it so the IRQ can be taken.
        loaded.cpu.setStatus((byte) 0x00);
        loaded.nes.tick();

        assertFalse(cart.mapperIrqPending(), "delivered IRQ must clear the line");
        assertEquals(0xC100, loaded.cpu.getPc(), "CPU must vector through $FFFE");
    }

    @Test
    void mapperIrq_masked_staysAssertedUntilInterruptsEnabled() {
        RomLoader.Loaded loaded = RomLoader.loadFromBytes(buildMmc3Rom(), "mmc3-irq2.nes", opcodeCsv());
        Cartridge cart = loaded.cartridge;

        cart.cpuBusWrite(0xC000, (byte) 0x01);
        cart.cpuBusWrite(0xC001, (byte) 0x00);
        cart.cpuBusWrite(0xE001, (byte) 0x00);
        clockIrqUntilPending(cart, 2);

        // I flag is set after reset — the IRQ must NOT be consumed while
        // masked; hardware holds the line until the CPU takes it.
        loaded.cpu.setStatus((byte) 0x04);  // InterruptDisable
        loaded.nes.tick();
        assertTrue(cart.mapperIrqPending(),
                "masked IRQ must stay asserted, not be silently dropped");

        loaded.cpu.setStatus((byte) 0x00);
        loaded.nes.tick();
        assertFalse(cart.mapperIrqPending(), "IRQ taken once unmasked");
    }
}
