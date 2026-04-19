package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CPU6502#runWithCallback} introduced in Step 2 of the
 * playable-gen1 plan. The callback is invoked once before each instruction
 * fetch — the host (debugger, tracer, Snake demo, headless test) uses it to
 * inject per-instruction work without subclassing.
 *
 * <p>Loop termination is by callback throwing {@link CallbackStop}; this
 * matches bugzmanov's {@code FnMut(&mut CPU)} pattern (ch. 3.4) where the
 * Rust closure can simply return.
 */
class CPU6502RunWithCallbackTest {

    /** Sentinel used to break out of {@code runWithCallback} cleanly. */
    static class CallbackStop extends RuntimeException {}

    /**
     * Wires a CPU to a {@link FullAddressRam} so we can poke a tiny program
     * directly into memory. No cartridge needed.
     */
    private CPU6502 newCpuWithRam() {
        CPU6502 cpu = new CPU6502();
        FullAddressRam ram = new FullAddressRam();
        CPUBus.builder()
                .cpu(cpu)
                .testRam(ram)
                .build()
                .connect();
        return cpu;
    }

    /** Loads a program at $8000, sets reset vector to $8000, then resets. */
    private void loadProgramAt8000(CPU6502 cpu, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            cpu.cpuBusWrite(0x8000 + i, (byte) bytes[i]);
        }
        // reset vector at $FFFC/$FFFD points to program start
        cpu.cpuBusWrite(0xFFFC, (byte) 0x00);
        cpu.cpuBusWrite(0xFFFD, (byte) 0x80);
        cpu.reset();
    }

    @Test
    void runWithCallback_invokesCallbackBeforeEachInstruction() {
        CPU6502 cpu = newCpuWithRam();
        // 3 NOPs ($EA), then BRK ($00) to halt
        loadProgramAt8000(cpu, 0xEA, 0xEA, 0xEA, 0x00);

        List<Integer> seenPc = new ArrayList<>();
        cpu.runWithCallback(c -> {
            seenPc.add(c.getPc());
            // Stop after observing 4 PCs (3 NOPs + 1 BRK fetch)
            if (seenPc.size() >= 4) {
                throw new CallbackStop();
            }
        });

        // Callback should have observed PC at exactly the instruction-fetch
        // points: $8000, $8001, $8002, $8003 — one per instruction (NOPs are
        // 1 byte each).
        assertEquals(4, seenPc.size(), "callback should fire once per instruction");
        assertEquals(0x8000, seenPc.get(0).intValue());
        assertEquals(0x8001, seenPc.get(1).intValue());
        assertEquals(0x8002, seenPc.get(2).intValue());
        assertEquals(0x8003, seenPc.get(3).intValue());
    }

    @Test
    void runWithCallback_canStopByThrowingFromCallback() {
        CPU6502 cpu = newCpuWithRam();
        // Long NOP sled — callback must be the one to terminate.
        int[] program = new int[200];
        for (int i = 0; i < program.length - 1; i++) {
            program[i] = 0xEA;
        }
        program[program.length - 1] = 0x00;
        loadProgramAt8000(cpu, program);

        int[] count = {0};
        cpu.runWithCallback(c -> {
            count[0]++;
            if (count[0] == 5) {
                throw new CallbackStop();
            }
        });

        assertEquals(5, count[0],
                "throwing CallbackStop should immediately exit runWithCallback");
    }

    @Test
    void runWithCallback_advancesCpuStateBetweenCallbacks() {
        CPU6502 cpu = newCpuWithRam();
        // LDA #$42, BRK
        loadProgramAt8000(cpu, 0xA9, 0x42, 0x00);

        int[] step = {0};
        cpu.runWithCallback(c -> {
            step[0]++;
            if (step[0] == 1) {
                // Before the LDA fires, A should be its reset value (0).
                assertEquals(0, c.getA() & 0xFF, "A should be 0 before LDA executes");
            } else if (step[0] == 2) {
                // After LDA #$42 ran, before BRK fetches, A should be $42.
                assertEquals(0x42, c.getA() & 0xFF, "A should be $42 after LDA #$42");
                throw new CallbackStop();
            }
        });
    }
}
