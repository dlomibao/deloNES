package net.lomibao.nes;

import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C3 — frame-IRQ delivery through {@link NesSystem#tick()} (seam
 * S4): the APU's level-held line is polled every tick; delivery happens
 * whenever the CPU's I flag allows it; taking the interrupt NEVER clears
 * the flag (software must read $4015 / set $4017 bit 6).
 *
 * <p>No cartridge: the reset and IRQ vectors read as $0000, so programs
 * are poked into RAM at $0000 and delivery is observed via the stack
 * pointer (each taken IRQ pushes 3 bytes).
 */
class NesSystemApuIrqTest {

    private CPU6502 cpu;
    private Ram ram;
    private APU apu;
    private NesSystem nes;

    private void build(int... program) {
        cpu = new CPU6502();
        ram = new Ram();
        apu = new APU();
        nes = NesSystem.builder().cpu(cpu).ram(ram).ppu(new PPU()).apu(apu).build();
        for (int i = 0; i < program.length; i++) {
            ram.cpuBusWrite(i, (byte) program[i]);
        }
        cpu.reset();
    }

    /** Run the APU to the frame-IRQ window so the line is asserted. */
    private void assertFrameLine() {
        for (int i = 0; i < 29828; i++) {
            apu.clock();
        }
        assertTrue(apu.irqAsserted(), "precondition: frame IRQ line asserted");
    }

    private void tick(int n) {
        for (int i = 0; i < n; i++) {
            nes.tick();
        }
    }

    @Test
    void maskedByIFlag_noDelivery_lineStaysAsserted() {
        build(0x78, 0x4C, 0x01, 0x00); // SEI; JMP $0001 (spin)
        tick(3 * 20); // let SEI execute
        assertFrameLine();
        int stkpBefore = cpu.getStkp();
        tick(3 * 200);
        assertEquals(stkpBefore, cpu.getStkp(),
                "I flag masks the frame IRQ — nothing may be pushed");
        assertTrue(apu.irqAsserted(),
                "the level-held line stays asserted while masked (retried, not dropped)");
    }

    @Test
    void unmasked_delivered_andFlagNotClearedOnTaken() {
        // CLI; spin. The IRQ vector reads $0000 (no cart), so the "handler"
        // is the CLI itself — the level line would re-deliver, which is
        // exactly the no-clear-on-taken hardware behavior under test.
        build(0x58, 0x4C, 0x01, 0x00); // CLI; JMP $0001
        tick(3 * 20); // CLI executed
        assertFrameLine();
        int stkpBefore = cpu.getStkp();
        tick(3 * 30);
        assertTrue(cpu.getStkp() < stkpBefore, "IRQ must be taken once unmasked");
        assertTrue(apu.frameCounter().isFrameIrqFlag(),
                "taking the interrupt must NOT clear the frame IRQ flag (no clear-on-taken)");
        assertTrue(apu.irqAsserted(), "line still asserted after delivery");
    }

    @Test
    void levelHeld_reDeliversWhileSoftwareNeverClears() {
        build(0x58, 0x4C, 0x01, 0x00); // CLI; JMP $0001 — handler = $0000 = CLI again
        tick(3 * 20);
        assertFrameLine();
        tick(3 * 40); // first delivery + handler CLI + re-delivery
        int stkpAfterFirst = cpu.getStkp();
        tick(3 * 40);
        assertTrue(cpu.getStkp() != stkpAfterFirst,
                "the held line re-delivers as long as the flag is never cleared");
    }

    @Test
    void softwareClearVia4015Read_stopsDelivery() {
        build(0x78, 0x4C, 0x01, 0x00); // SEI; spin — keep delivery masked
        tick(3 * 20);
        assertFrameLine();
        tick(3 * 10);
        // Software (here: the test acting as the handler) reads $4015.
        nes.getCpuBus().read(0x4015);
        assertFalse(apu.irqAsserted(), "$4015 read clears the frame flag → line drops");
        int stkpBefore = cpu.getStkp();
        tick(3 * 200);
        assertEquals(stkpBefore, cpu.getStkp(), "no further delivery once cleared");
    }
}
