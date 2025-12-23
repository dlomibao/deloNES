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

    @Test
    void testMultiplicationProgram() {
        // Multiplies 10 * 3 = 30 (0x1E)
        String testProgram = "A2 0A 8E 00 00 A2 03 8E 01 00 AC 00 00 A9 00 18 6D 01 00 88 D0 FA 8D 02 00 EA EA EA";
        runProgram(testProgram, 0x0002, 30, 200);
    }

    @Test
    void testAdd() {
        // 1. Addition: LDA #$10, ADC #$20, STA $02
        runProgram("A9 10 69 20 85 02", 0x0002, 0x30, 50);
    }

    @Test
    void testSub() {
        // 2. Subtraction: LDA #$50, SEC, SBC #$10, STA $02
        runProgram("A9 50 38 E9 10 85 02", 0x0002, 0x40, 50);
    }

    @Test
    void testTransfer() {
        // 3. Register Transfer and Increment: LDX #$0A, INX, STX $0002
        runProgram("A2 0A E8 8E 02 00", 0x0002, 0x0B, 50);
    }

    @Test
    void testOr() {
        // 4. Bitwise OR: LDA #$0F, ORA #$F0, STA $02
        runProgram("A9 0F 09 F0 85 02", 0x0002, 0xFF, 50);
    }

    private void runProgram(String hexProgram, int resultAddress, int expectedValue, int maxCycles) {
        byte[] programBytes = hexStringtoByteArray(hexProgram);

        // Reset RAM to avoid Carry/State contamination between runs
        ram.setByteArray(new byte[ram.MEMORY_SIZE]);

        // Write program to 0x8000
        ram.writeRange(0x8000, programBytes);

        // Set Reset Vector to 0x8000
        ram.cpuBusWrite(0xFFFC, (byte) 0x00);
        ram.cpuBusWrite(0xFFFD, (byte) 0x80);

        cpu.reset();

        // Run cycles
        for (int i = 0; i < maxCycles; i++) {
            cpu.clock();
        }

        int result = ram.cpuBusRead(resultAddress);
        assertEquals(expectedValue, result, "Program [" + hexProgram + "] failed. Expected " + expectedValue + " at "
                + String.format("0x%04X", resultAddress));
    }

    private byte[] hexStringtoByteArray(String hexString) {
        String[] hexVals = hexString.split(" ");
        byte[] byteArray = new byte[hexVals.length];
        for (int i = 0; i < hexVals.length; i++) {
            byteArray[i] = (byte) Integer.parseInt(hexVals[i], 16);
        }
        return byteArray;
    }
}
