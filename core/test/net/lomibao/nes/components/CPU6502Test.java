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

    /**
     * BRK is a 2-byte instruction: the pushed return address must be the
     * BRK opcode address + 2 (NESdev / MOS 6502). The old implementation
     * pushed opcode+3, which broke software that passes inline argument
     * bytes after BRK and derives their address from the pushed PC —
     * Micro Mages drives every entity animation this way, and the
     * off-by-one made its player sprite invisible. nestest never
     * executes BRK, so the trace baseline cannot catch this.
     */
    @Test
    void brk_pushesOpcodeAddressPlusTwo() {
        ram.setByteArray(new byte[ram.MEMORY_SIZE]);
        // $8000: BRK, padding byte $42
        ram.writeRange(0x8000, hexStringtoByteArray("00 42"));
        // Reset vector -> $8000, IRQ/BRK vector -> $9000 (spin: JMP $9000)
        ram.cpuBusWrite(0xFFFC, (byte) 0x00);
        ram.cpuBusWrite(0xFFFD, (byte) 0x80);
        ram.cpuBusWrite(0xFFFE, (byte) 0x00);
        ram.cpuBusWrite(0xFFFF, (byte) 0x90);
        ram.writeRange(0x9000, hexStringtoByteArray("4C 00 90"));

        cpu.reset();
        int safety = 0;
        while (cpu.getPc() != 0x9000 && safety++ < 200) {
            cpu.clock();
        }
        assertEquals(0x9000, cpu.getPc(), "BRK must vector through $FFFE");

        // BRK pushed PC-hi, PC-lo, status; stack pointer dropped by 3.
        int sp = cpu.getStkp();
        int pushedLo = ram.cpuBusRead(0x0100 + ((sp + 2) & 0xFF));
        int pushedHi = ram.cpuBusRead(0x0100 + ((sp + 3) & 0xFF));
        int pushedPc = (pushedHi << 8) | pushedLo;
        assertEquals(0x8002, pushedPc,
                "BRK at $8000 must push $8002 (opcode+2), not opcode+3");
    }

    /**
     * Functional pin of the BRK-as-RPC idiom (Micro Mages' set-animation
     * calls): the handler pops the pushed PC, subtracts 2 to find the BRK
     * opcode, and reads the inline argument at offset +1. With the
     * correct pushed address the handler must see the actual argument
     * byte, not the byte after it.
     */
    @Test
    void brk_inlineArgumentReadableViaPushedPc() {
        ram.setByteArray(new byte[ram.MEMORY_SIZE]);
        // $8000: BRK, arg $42
        ram.writeRange(0x8000, hexStringtoByteArray("00 42"));
        ram.cpuBusWrite(0xFFFC, (byte) 0x00);
        ram.cpuBusWrite(0xFFFD, (byte) 0x80);
        ram.cpuBusWrite(0xFFFE, (byte) 0x00);
        ram.cpuBusWrite(0xFFFF, (byte) 0x90);
        // Handler at $9000 (mirrors Micro Mages' $F9E6 idiom):
        //   PLA            ; discard status
        //   PLA / SEC / SBC #$02 / STA $D6   ; lo(pushedPC) - 2
        //   PLA / SBC #$00 / STA $D7         ; hi -> ($D6) = BRK address
        //   LDY #$01 / LDA ($D6),Y / STA $10 ; read inline arg
        //   JMP $9013                        ; spin
        ram.writeRange(0x9000, hexStringtoByteArray(
                "68 68 38 E9 02 85 D6 68 E9 00 85 D7 A0 01 B1 D6 85 10 4C 12 90"));

        cpu.reset();
        int safety = 0;
        while (cpu.getPc() != 0x9012 && safety++ < 400) {
            cpu.clock();
        }
        assertEquals(0x42, ram.cpuBusRead(0x0010),
                "handler must read the inline argument byte after the BRK opcode");
    }
}
