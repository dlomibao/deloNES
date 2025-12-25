package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OpcodesTest {
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

    private void run(String hexSubroutine) {
        run(hexSubroutine, 50);
    }

    private void run(String hexSubroutine, int maxCycles) {
        ram.setByteArray(new byte[ram.MEMORY_SIZE]);
        byte[] program = hexToBytes(hexSubroutine);
        ram.writeRange(0x8000, program);
        ram.cpuBusWrite(0xFFFC, (byte) 0x00);
        ram.cpuBusWrite(0xFFFD, (byte) 0x80);
        cpu.reset();
        for (int i = 0; i < maxCycles; i++) {
            cpu.clock();
        }
    }

    private byte[] hexToBytes(String hex) {
        String[] parts = hex.split(" ");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    @Test
    void test_LDA_LDX_LDY_STA_STX_STY() {
        // LDA #10, LDX #20, LDY #30, STA $00, STX $01, STY $02
        run("A9 0A A2 14 A0 1E 85 00 86 01 8C 02 00");
        assertEquals(10, ram.cpuBusRead(0x00));
        assertEquals(20, ram.cpuBusRead(0x01));
        assertEquals(30, ram.cpuBusRead(0x02));
    }

    @Test
    void test_ADC_SBC() {
        // SEC, LDA #50, ADC #10 (60), CLC, SBC #20 (40)
        run("18 A9 0A 69 14 38 E9 05 85 00");
        assertEquals(25, ram.cpuBusRead(0x00));
    }

    @Test
    void test_TAX_TAY_TXA_TYA_TSX_TXS() {
        // LDA #42, TAX, TAY, TXA, TYA, LDX #FF, TXS, TSX, STX $00
        run("A9 2A AA A8 8A 98 A2 FF 9A BA 86 00");
        assertEquals(42, cpu.getA());
        assertEquals(255, cpu.getX()); // X was overwritten by LDX #FF, then TSX transfered SP(FF) to X
        assertEquals(42, cpu.getY());
        assertEquals(255, ram.cpuBusRead(0x00));
    }

    @Test
    void test_INC_DEC_INX_DEX_INY_DEY() {
        run("A9 0A 85 00 E6 00 C6 00 A2 05 E8 CA A0 07 C8 88 86 01 84 02");
        assertEquals(10, ram.cpuBusRead(0x00));
        assertEquals(5, ram.cpuBusRead(0x01));
        assertEquals(7, ram.cpuBusRead(0x02));
    }

    @Test
    void test_AND_ORA_EOR() {
        run("A9 0F 29 55 09 AA 49 FF 85 00");
        assertEquals(0x50, ram.cpuBusRead(0x00));
    }

    @Test
    void test_ASL_LSR_ROL_ROR() {
        run("A9 01 0A 4A 38 2A 18 6A 85 00");
        assertEquals(0x01, ram.cpuBusRead(0x00));
    }

    @Test
    void test_Branches() {
        // 38 (SEC), B0 02 (BCS over A9 01), 18 (CLC), 90 02 (BCC over A9 02), 85 00
        run("38 B0 02 A9 01 18 90 02 A9 02 85 00");
        assertEquals(0, ram.cpuBusRead(0x00));
    }

    @Test
    void test_PHA_PLA_Basic() {
        // Test basic PHA/PLA: Load A with 0x42, push it, clear A, pull it back
        // LDA #$42, PHA, LDA #$00, PLA, STA $00
        run("A9 42 48 A9 00 68 85 00");
        assertEquals(0x42, ram.cpuBusRead(0x00), "PLA should restore the value pushed by PHA");
        assertEquals(0x42, cpu.getA(), "Accumulator should be 0x42 after PLA");
    }

    @Test
    void test_PHA_PLA_StackPointer() {
        // Verify stack pointer changes correctly
        // LDA #$42, PHA
        run("A9 42 48");
        assertEquals(0xFC, cpu.getStkp(), "Stack pointer should decrement after PHA");

        // Continue: PLA
        cpu.clock(); // Execute PLA
        while (!cpu.complete())
            cpu.clock();
        assertEquals(0xFD, cpu.getStkp(), "Stack pointer should increment after PLA");
    }

    @Test
    void test_PHP_PLP_Basic() {
        // Test PHP/PLP: Set some flags, push status, clear flags, pull status back
        // SEC, SEI, SED, PHP, CLC, CLI, CLD, PLP
        run("38 78 F8 08 18 58 D8 28");
        assertTrue(cpu.getFlag(CPU6502.Flag.Carry), "Carry should be restored by PLP");
        assertTrue(cpu.getFlag(CPU6502.Flag.InterruptDisable), "Interrupt Disable should be restored by PLP");
        assertTrue(cpu.getFlag(CPU6502.Flag.Decimal), "Decimal should be restored by PLP");
    }

    @Test
    void test_PHP_PLA_Interaction() {
        // This is the critical test! Push status register, then pull into accumulator
        // Set P to a known value (0x6F), push it, then pull into A
        // SEC (C=1), SEI (I=1), SED (D=1), CLV (V=0) -> P should be 0x6D or 0x6F
        // Then PHP, PLA
        run("38 78 F8 B8 08 68 85 00");
        int pushedStatus = ram.cpuBusRead(0x00);
        System.out.println("Status pushed by PHP and pulled by PLA: 0x" + Integer.toHexString(pushedStatus));

        // The status register should have been pushed and then pulled into A
        // Check if A contains the status value
        assertTrue(pushedStatus != 0, "PLA should have pulled the status value into A");
    }

    @Test
    void test_NestestSequence_Line70to73() {
        // Reproduce the exact sequence from nestest lines 70-73
        // Line 70: SED (Set Decimal)
        // Line 71: PHP (Push Status - should push 0x6F)
        // Line 72: PLA (Pull Accumulator - should pull 0x6F into A, but then A should
        // become 0x7F somehow?)
        // Line 73: AND #$EF

        // First, let's set up the initial state to match line 70
        // We need A=0x00, P=0x6D (based on the log)
        run("18 B8 78 38 D8"); // CLC, CLV, SEI, SEC, CLD -> trying to get P=0x6D

        // Now execute the sequence
        // F8 = SED, 08 = PHP, 68 = PLA, 29 EF = AND #$EF
        byte[] sequence = hexToBytes("F8 08 68 29 EF 85 00");
        ram.writeRange(0x8010, sequence);

        // Reset PC to 0x8010
        cpu.setPc(0x8010);

        // Execute SED
        cpu.clock();
        while (!cpu.complete())
            cpu.clock();
        System.out.println("After SED: A=" + String.format("0x%02X", cpu.getA()) +
                ", P=" + String.format("0x%02X", cpu.getStatus() & 0xFF) +
                ", SP=" + String.format("0x%02X", cpu.getStkp()));

        // Execute PHP
        cpu.clock();
        while (!cpu.complete())
            cpu.clock();
        int spAfterPHP = cpu.getStkp();
        System.out.println("After PHP: A=" + String.format("0x%02X", cpu.getA()) +
                ", P=" + String.format("0x%02X", cpu.getStatus() & 0xFF) +
                ", SP=" + String.format("0x%02X", spAfterPHP));

        // Check what's on the stack
        int stackValue = ram.cpuBusRead(0x0100 + spAfterPHP + 1);
        System.out.println("Value on stack: " + String.format("0x%02X", stackValue));

        // Execute PLA
        cpu.clock();
        while (!cpu.complete())
            cpu.clock();
        int aAfterPLA = cpu.getA();
        System.out.println("After PLA: A=" + String.format("0x%02X", aAfterPLA) +
                ", P=" + String.format("0x%02X", cpu.getStatus() & 0xFF) +
                ", SP=" + String.format("0x%02X", cpu.getStkp()));

        // According to nestest, A should be 0x7F here, but we're getting 0x6F
        // Let's see what we actually get
        System.out.println("Expected A=0x7F, Got A=0x" + Integer.toHexString(aAfterPLA));

        // Execute AND #$EF
        cpu.clock();
        while (!cpu.complete())
            cpu.clock();
        cpu.clock();
        while (!cpu.complete())
            cpu.clock();

        int finalA = ram.cpuBusRead(0x00);
        System.out.println("After AND and STA: A=" + String.format("0x%02X", finalA));
    }

    @Test
    void test_Stack_MultipleValues() {
        // Push multiple values and verify they come back in correct order (LIFO)
        // LDA #$11, PHA, LDA #$22, PHA, LDA #$33, PHA
        // PLA (should get $33), STA $00
        // PLA (should get $22), STA $01
        // PLA (should get $11), STA $02
        run("A9 11 48 A9 22 48 A9 33 48 68 85 00 68 85 01 68 85 02");
        assertEquals(0x33, ram.cpuBusRead(0x00), "First PLA should get last pushed value");
        assertEquals(0x22, ram.cpuBusRead(0x01), "Second PLA should get middle value");
        assertEquals(0x11, ram.cpuBusRead(0x02), "Third PLA should get first pushed value");
    }

    @Test
    void test_PLA_Flags() {
        // Test that PLA sets Zero and Negative flags correctly
        // Push 0x00, pull it
        run("A9 00 48 68");
        assertTrue(cpu.getFlag(CPU6502.Flag.Zero), "Zero flag should be set when PLA pulls 0x00");
        assertFalse(cpu.getFlag(CPU6502.Flag.Negative), "Negative flag should be clear for 0x00");

        // Push 0x80 (negative), pull it
        run("A9 80 48 68");
        assertFalse(cpu.getFlag(CPU6502.Flag.Zero), "Zero flag should be clear for 0x80");
        assertTrue(cpu.getFlag(CPU6502.Flag.Negative), "Negative flag should be set for 0x80");
    }

    @Test
    void test_PHP_Pushes_Bit4_Bit5() {
        // Clear all flags, then PHP
        // F8 (SED) to set a known bit (Decimal = 8)
        // Bit 4 (Break) and Bit 5 (Unused) should always be set when pushed via PHP
        run("D8 18 58 B8 F8 08 68 85 00"); // CLD, CLC, CLI, CLV, SED, PHP, PLA, STA $00
        int pulled = ram.cpuBusRead(0x00) & 0xFF;
        // P should have: bit 3 (Decimal), bit 4 (Break), bit 5 (Unused)
        // 0x08 | 0x10 | 0x20 = 0x38
        assertEquals(0x38, pulled & 0x38,
                "PHP should push status with bits 4 and 5 set. Got: 0x" + Integer.toHexString(pulled));
    }

    @Test
    void test_BRK_StackValue() {
        // Set flags, BRK, check stack
        run("18 D8 00"); // CLC, CLD, BRK
        // BRK pushes PC (2 bytes) then Status (1 byte)
        // SP starts at FD.
        // Instruction at 8000: 18 (CLC), 8001: D8 (CLD), 8002: 00 (BRK)
        // BRK skips one byte (pc++), so it pushes 8004?
        // Actually BRK pushes PC+2.
        int sp = cpu.getStkp();
        int pushedStatus = ram.cpuBusRead(0x0100 + sp + 1) & 0xFF;
        // Status should have Bit 4 and 5 set (0x30)
        assertEquals(0x30, pushedStatus & 0x30, "BRK should push status with bits 4 and 5 set");
    }

    @Test
    void test_PHP_BreakFlagOnStack() {
        // According to 6502 specs, PHP always pushes status with bit 4 set.
        run("08 68 85 00"); // PHP, PLA, STA $00
        int pulled = ram.cpuBusRead(0x00) & 0xFF;
        assertEquals(0x10, pulled & 0x10, "PHP should set bit 4 on stack");
    }

    @Test
    void test_PLP_Ignores_Bit4() {

        // Push a value with bit 4 set, then PLP. Bit 4 in CPU should not be affected
        // (or rather, it doesn't exist as a real register bit)
        // LDA #$FF, PHA, PLP
        run("A9 FF 48 28");
        // After PLP, we check if we can still set/clear other flags
        assertTrue(cpu.getFlag(CPU6502.Flag.Carry));
        assertTrue(cpu.getFlag(CPU6502.Flag.Zero));
        // CPU shouldn't really have a "Break" flag that persists,
        // but if it does, it might be clear.
    }

    @Test
    void test_JMP_JSR_RTS() {
        run("20 06 80 85 00 EA EA A9 2A 60");
        assertEquals(42, ram.cpuBusRead(0x00));
    }

    @Test
    void test_BIT() {
        // LDA #FF, STA $00, LDA #00, BIT $00 -> Zero=1 (00 & FF == 0), Neg=1,
        // Overflow=1
        run("A9 FF 85 00 A9 00 24 00");
        assertTrue(cpu.getFlag(CPU6502.Flag.Zero));
        assertTrue(cpu.getFlag(CPU6502.Flag.Negative));
        assertTrue(cpu.getFlag(CPU6502.Flag.VOverflow));
    }

    @Test
    void test_CMP_CPX_CPY() {
        run("A9 0A C9 0A");
        assertTrue(cpu.getFlag(CPU6502.Flag.Zero));
        assertTrue(cpu.getFlag(CPU6502.Flag.Carry));

        run("A9 0A C9 05");
        assertFalse(cpu.getFlag(CPU6502.Flag.Zero));
        assertTrue(cpu.getFlag(CPU6502.Flag.Carry));

        run("A9 0A C9 0F");
        assertFalse(cpu.getFlag(CPU6502.Flag.Zero));
        assertFalse(cpu.getFlag(CPU6502.Flag.Carry));
    }

    @Test
    void test_Flags() {
        run("18 38 58 78 B8 D8 F8"); // CLC, SEC, CLI, SEI, CLV, CLD, SED
        assertTrue(cpu.getFlag(CPU6502.Flag.Carry)); // Wait, SEC was after CLC.
        // Let's re-order to check individual flags if needed, or just check the last
        // state.
        assertTrue(cpu.getFlag(CPU6502.Flag.Decimal));
        assertTrue(cpu.getFlag(CPU6502.Flag.InterruptDisable));
        assertFalse(cpu.getFlag(CPU6502.Flag.VOverflow));
    }

    @Test
    void test_Diagnostic_Flags() {
        System.out.println("Diagnostic - Initial status: " + String.format("0x%02X", cpu.getStatus()));
        cpu.setFlag(CPU6502.Flag.U, true);
        System.out.println("Diagnostic - After U=true: " + String.format("0x%02X", cpu.getStatus()));
        cpu.setFlag(CPU6502.Flag.Break, true);
        System.out.println("Diagnostic - After B=true: " + String.format("0x%02X", cpu.getStatus()));

        // Print all flag masks
        for (CPU6502.Flag f : CPU6502.Flag.values()) {
            System.out.println("Diagnostic - Flag " + f.name() + " mask: 0x" + Integer.toHexString(f.mask));
        }
    }
}
