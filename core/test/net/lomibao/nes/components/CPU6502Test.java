package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Formal test suite base for the NES CPU.
 */
public class CPU6502Test {
    private CPU6502 cpu;
    private CPUBus bus;
    private FullAddressRam ram;

    @BeforeEach
    void setUp() {
        cpu = new CPU6502();
        ram = new FullAddressRam();
        bus = CPUBus.builder()
                .cpu(cpu)
                .testRam(ram)
                .ppu(new PPU())
                .build()
                .connect();
    }

    @Test
    void testInitialState() {
        assertNotNull(cpu);
        assertEquals(0, cpu.getA());
        assertEquals(0, cpu.getX());
        assertEquals(0, cpu.getY());
    }

    @Test
    void testLDA_Immediate() {
        // LDA #$42
        // Memory: [0x8000] = A9, [0x8001] = 42
        ram.cpuBusWrite(0x8000, (byte) 0xA9);
        ram.cpuBusWrite(0x8001, (byte) 0x42);

        cpu.setPc(0x8000);
        cpu.setCycles(0);

        // Execute LDA
        cpu.clock(); // Fetch A9, start instruction
        while (!cpu.complete()) {
            cpu.clock();
        }

        assertEquals(0x42, cpu.getA());
        assertFalse(cpu.getFlag(CPU6502.Flag.Zero));
        assertFalse(cpu.getFlag(CPU6502.Flag.Negative));
    }

    @Test
    void testStatusFlags() {
        cpu.setStatus((byte) 0);
        cpu.setFlag(CPU6502.Flag.Carry, true);
        assertEquals(0x01, cpu.getStatus() & 0xFF);
        assertTrue(cpu.getFlag(CPU6502.Flag.Carry));

        cpu.setFlag(CPU6502.Flag.Zero, true);
        assertTrue(cpu.getFlag(CPU6502.Flag.Zero));
        assertEquals(0x03, cpu.getStatus() & 0xFF);
    }
}
