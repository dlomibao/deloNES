package net.lomibao.nes.components.apu;

import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import org.junit.jupiter.api.Test;

/**
 * C0 spike (temporary, docs/apu-plan.md Phase C): measure — don't assume —
 * the access-cycle accuracy the atomic-instruction CPU delivers for
 * $4015/$4017. Prints numbers; the findings note in the plan doc records
 * them. Deleted (or evolved into C2 tests) when C0 closes.
 */
class C0MeasurementSpike {

    /** Build a minimal system: CPU + RAM + PPU + APU, no cartridge. */
    private static Object[] system() {
        CPU6502 cpu = new CPU6502();
        Ram ram = new Ram();
        PPU ppu = new PPU();
        APU apu = new APU();
        NesSystem nes = NesSystem.builder().cpu(cpu).ram(ram).ppu(ppu).apu(apu).build();
        return new Object[] {nes, cpu, ram, apu};
    }

    /** Poke a program into RAM starting at $0000 (reset vector reads 0 with no cart). */
    private static void load(Ram ram, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            ram.cpuBusWrite(i, (byte) bytes[i]);
        }
    }

    @Test
    void measure_accessCycleOfStaAbs4017_andLdaAbs4015() {
        Object[] s = system();
        NesSystem nes = (NesSystem) s[0];
        CPU6502 cpu = (CPU6502) s[1];
        Ram ram = (Ram) s[2];

        // $0000: LDA #$00 (2cy); STA $4017 (4cy); LDA $4015 (4cy); JMP $0006
        load(ram,
                0xA9, 0x00,        // LDA #$00
                0x8D, 0x17, 0x40,  // STA $4017
                0xAD, 0x15, 0x40,  // LDA $4015  @ $0005
                0x4C, 0x05, 0x00); // JMP $0005

        final long[] writeSeen = {-1};
        final long[] readSeen = {-1};
        nes.getCpuBus().setWriteListener((addr, old, v, pc) -> {
            if (addr == 0x4017 && writeSeen[0] < 0) {
                writeSeen[0] = cpu.getClockCount();
            }
        });
        cpu.reset();
        // Drain reset (7 cycles), then run a while.
        for (int i = 0; i < 3 * 100; i++) {
            nes.tick();
        }
        System.out.println("[C0] STA $4017 write observed at cpu clockCount=" + writeSeen[0]
                + " (reset=7cy, LDA imm=2cy => instruction START would be 9;"
                + " hardware write cycle = start+3 = 12)");
    }

    @Test
    void measure_firstCycle4015ReadsFrameIrqSet() {
        Object[] s = system();
        NesSystem nes = (NesSystem) s[0];
        CPU6502 cpu = (CPU6502) s[1];
        Ram ram = (Ram) s[2];
        APU apu = (APU) s[3];

        // Poll loop: LDA $4015 (4cy); AND #$40 (2cy); BEQ loop (3cy taken).
        // On flag set: JAM into a NOP spin so we can read A afterwards.
        load(ram,
                0xAD, 0x15, 0x40,  // $0000 LDA $4015
                0x29, 0x40,        // $0003 AND #$40
                0xF0, 0xF9,        // $0005 BEQ $0000
                0x4C, 0x07, 0x00); // $0007 JMP $0007
        cpu.reset();
        long flagCycle = -1;
        for (int i = 0; i < 3 * 40000 && flagCycle < 0; i++) {
            nes.tick();
            if (cpu.getPc() == 0x0007 && flagCycle < 0) {
                flagCycle = cpu.getClockCount();
            }
        }
        // The frame counter's own cycle counter vs the CPU's: the APU has
        // been clocked once per CPU cycle since power-on (incl. reset's 7).
        System.out.println("[C0] poll loop first saw $4015 bit6=1; cpu clockCount=" + flagCycle
                + " frameCounter.cycle()=" + apu.frameCounter().cycle()
                + " (hardware flag-set window: FC cycles 29828/29829/29830)");
    }
}
