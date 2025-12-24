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
    void test_Stack() {
        run("A9 2A 48 A9 00 68 85 00 08 68 85 01");
        assertEquals(42, ram.cpuBusRead(0x00));
        assertTrue(ram.cpuBusRead(0x01) != 0);
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
    void test_NOP() {
        run("EA EA EA", 14);
        assertEquals(0x8003, cpu.getPc());
    }
}
