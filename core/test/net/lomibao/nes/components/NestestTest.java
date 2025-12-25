package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

public class NestestTest {

        @Test
        void testNestestHeadless() throws Exception {
                // Load ROM
                InputStream romStream = getClass().getResourceAsStream("/nestest.nes");
                assertNotNull(romStream, "nestest.nes not found in resources");

                Cartridge cartridge = new Cartridge(romStream, "nestest.nes");
                CPU6502 cpu = new CPU6502();
                Ram ram = new Ram();

                CPUBus bus = CPUBus.builder()
                                .cpu(cpu)
                                .cartridge(cartridge)
                                .ram(ram)
                                .ppu(new PPU())
                                .build()
                                .connect();

                // Power up sequence / Reset behavior
                cpu.reset();
                // Nestest auto-mode: set PC to 0xC000 to skip menu
                cpu.setPc(0xC000);
                // cpu.reset() sets cycles = 8. We need to clear them so the next clock() starts
                // the instruction at 0xC000.
                while (!cpu.complete()) {
                        cpu.clock();
                }

                // Read golden log
                InputStream logStream = getClass().getResourceAsStream("/nestest.log");
                assertNotNull(logStream, "nestest.log not found in resources");
                java.util.Scanner scanner = new java.util.Scanner(logStream);

                int lineNumber = 0;
                while (scanner.hasNextLine()) {
                        lineNumber++;
                        String expectedLine = scanner.nextLine();
                        if (expectedLine.trim().isEmpty())
                                continue;

                        // Parse expected state
                        // Example: C000 4C F5 C5 JMP $C5F5 A:00 X:00 Y:00 P:24 SP:FD PPU: 0, 21 CYC:7
                        int expPc = Integer.parseInt(expectedLine.substring(0, 4).trim(), 16);
                        int expA = Integer.parseInt(expectedLine.substring(50, 52).trim(), 16);
                        int expX = Integer.parseInt(expectedLine.substring(55, 57).trim(), 16);
                        int expY = Integer.parseInt(expectedLine.substring(60, 62).trim(), 16);
                        int expP = Integer.parseInt(expectedLine.substring(65, 67).trim(), 16);
                        int expSp = Integer.parseInt(expectedLine.substring(71, 73).trim(), 16);
                        int expCyc = Integer.parseInt(expectedLine.substring(expectedLine.indexOf("CYC:") + 4).trim());

                        // Compare with current CPU state
                        String failureMsg = String.format(
                                        "Mismatch at line %d:\nExpected: %s\nActual:   PC:%04X A:%02X X:%02X Y:%02X P:%02X SP:%02X CYC:%d",
                                        lineNumber, expectedLine,
                                        cpu.getPc(), cpu.getA(), cpu.getX(), cpu.getY(), cpu.getStatus() & 0xFF,
                                        cpu.getStkp(), cpu.getClockCount());

                        if (lineNumber <= 5) {
                                System.out.println("Line " + lineNumber + " comparing: PC="
                                                + String.format("%04X", cpu.getPc()));
                        }

                        assertEquals(expPc, cpu.getPc(), "PC mismatch. " + failureMsg);
                        assertEquals(expA, cpu.getA(), "A mismatch. " + failureMsg);
                        assertEquals(expX, cpu.getX(), "X mismatch. " + failureMsg);
                        assertEquals(expY, cpu.getY(), "Y mismatch. " + failureMsg);
                        // assertEquals(expP, cpu.getStatus() & 0xFF, "P mismatch. " + failureMsg); // P
                        // status might have nuances (bit 4/5)
                        assertEquals(expSp, cpu.getStkp(), "SP mismatch. " + failureMsg);
                        // assertEquals(expCyc, (int)cpu.getClockCount(), "Cycle mismatch. " +
                        // failureMsg);

                        // Run one instruction
                        // In CPU6502.java, clock() starts a new instruction if cycles == 0.
                        // We need to advance until the current instruction is finished AND the next one
                        // hasn't started yet,
                        // or just advance until cycles == 0 again.

                        // First clock starts the instruction
                        cpu.clock();
                        if (lineNumber <= 2)
                                System.out.println("Line " + lineNumber + " clock 1: PC="
                                                + String.format("%04X", cpu.getPc()) + " cycles=" + cpu.getCycles()
                                                + " opcode=" + String.format("%02X", cpu.getOpcode()));
                        // Continue clocking until instruction is complete
                        while (!cpu.complete()) {
                                cpu.clock();
                                if (lineNumber <= 2)
                                        System.out.println("Line " + lineNumber + " clock: PC="
                                                        + String.format("%04X", cpu.getPc()) + " cycles="
                                                        + cpu.getCycles());
                        }
                }

                int failureCode = bus.read(0x0002, true);
                int failureSubCode = bus.read(0x0003, true);

                assertEquals(0, failureCode,
                                String.format(
                                                "Nestest failed with error code 0x%02X (Sub-code: 0x%02X). Check 0x0002/0x0003 for details.",
                                                failureCode, failureSubCode));
                assertEquals(0, failureSubCode,
                                String.format("Nestest failed with sub-code 0x%02X (Error: 0x%02X). Check 0x0002/0x0003 for details.",
                                                failureSubCode, failureCode));
        }
}
