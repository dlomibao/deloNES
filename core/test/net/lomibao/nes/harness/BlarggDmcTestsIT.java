package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D3 — blargg {@code dmc_tests/} tier, best-effort (docs/apu-plan.md).
 *
 * <p><b>PLAN DEVIATION, surfaced in-phase:</b> the plan scoped this tier
 * as "screen-reporting … via harness framebuffer-hash assertions". The
 * ROMs are NOT screen-reporting — disassembly (recorded in the plan doc)
 * shows they disable rendering, never write CHR/nametables, and report
 * <em>by ear</em>: play a DMC sample, click $4011, then park in an
 * eternal beep loop. A framebuffer hash is identically black for pass
 * and fail. These ITs assert the ROMs' <em>observable protocol</em>
 * instead — every $4000-$401F write is captured via the S1 bus-write
 * listener (with the CPU cycle it landed on) plus end-state APU/CPU
 * checks:
 * <ul>
 *   <li><b>status</b> — $4015 bit 4 must stay set for the whole 17-byte
 *       sample and clear on the last-byte fetch: the ROM's poll loop
 *       exit ($4011=$00) must land one sample-duration (~55-58k CPU
 *       cycles at rate 0) after the $4015=$10 start.</li>
 *   <li><b>status_irq</b> — the DMC IRQ must actually be delivered: the
 *       ROM's IRQ handler (and nothing else) writes $4010=$00, one
 *       sample-duration after start (IRQ on last-byte fetch).</li>
 *   <li><b>buffer_retained</b> — enable immediately followed by disable
 *       must retain the already-fetched byte (reader stopped, buffered
 *       byte plays out to silence).</li>
 *   <li><b>latency</b> (plan-designated stretch) — four timed
 *       $4011/$4015 click iterations complete and reach the beep.</li>
 * </ul>
 * All four must reach their terminal beep loop — the shared shell's
 * "test ran to completion" signal. The by-ear audio verdict itself
 * remains inherently manual (Phase E's audible smoke covers listening).
 */
class BlarggDmcTestsIT {

    /** One captured APU-register write: {addr, value, cpuCycle, pc}. */
    private static final class RegWrite {
        final int addr;
        final int value;
        final long cycle;
        final int pc;

        RegWrite(int addr, int value, long cycle, int pc) {
            this.addr = addr;
            this.value = value;
            this.cycle = cycle;
            this.pc = pc;
        }
    }

    /** Run a dmc_tests ROM for 60 frames capturing all $4000-$401F writes. */
    private static List<RegWrite> run(NesHarness h) {
        final List<RegWrite> writes = new ArrayList<>();
        h.nes().getCpuBus().setWriteListener((addr, oldValue, newValue, pc) -> {
            if (addr >= 0x4000 && addr < 0x4020) {
                writes.add(new RegWrite(addr, newValue & 0xFF, h.cpu().getClockCount(), pc));
            }
        });
        try {
            h.runFrames(60);
        } finally {
            h.nes().getCpuBus().setWriteListener(null);
        }
        return writes;
    }

    private static NesHarness load(String rom) {
        return NesHarness.fromResource("/test-roms/blargg/dmc_tests/" + rom);
    }

    private static RegWrite first(List<RegWrite> ws, int addr, int value) {
        for (RegWrite w : ws) {
            if (w.addr == addr && w.value == value) {
                return w;
            }
        }
        throw new AssertionError(String.format(
                "no write of $%02X to $%04X observed", value, addr));
    }

    /** First matching write strictly after the given cycle (skips shell-init writes). */
    private static RegWrite firstAfter(List<RegWrite> ws, int addr, int value, long cycle) {
        for (RegWrite w : ws) {
            if (w.addr == addr && w.value == value && w.cycle > cycle) {
                return w;
            }
        }
        throw new AssertionError(String.format(
                "no write of $%02X to $%04X after cycle %d observed", value, addr, cycle));
    }

    private static int count(List<RegWrite> ws, int addr, int value) {
        int n = 0;
        for (RegWrite w : ws) {
            if (w.addr == addr && w.value == value) {
                n++;
            }
        }
        return n;
    }

    /** The shared shell's terminal beep: pulse-1 tone setup then JMP-self. */
    private static void assertReachedBeepLoop(NesHarness h, List<RegWrite> ws, int loopAddr) {
        first(ws, 0x4000, 0x82); // beep duty/volume
        first(ws, 0x4003, 0x09); // beep timer high + length — last shell write
        int pc = h.cpu().getPc();
        assertTrue(pc >= loopAddr && pc <= loopAddr + 2,
                String.format("CPU must park in the terminal beep loop $%04X, pc=$%04X",
                        loopAddr, pc));
    }

    /**
     * Duration of the 17-byte rate-0 sample all three gate ROMs play
     * ($4010 rate 0 = 428 CPU cycles/bit): 16 byte-periods after the
     * first refill plus start/fetch slack ≈ 55-58k cycles. The window is
     * deliberately generous but excludes both failure shapes: an
     * immediately-clear status (gap ≈ 0) and a hung poll (never exits).
     */
    private static final long SAMPLE_MIN = 45_000;
    private static final long SAMPLE_MAX = 75_000;

    @Test
    void dmcTests_status() {
        NesHarness h = load("status.nes");
        List<RegWrite> ws = run(h);
        // Test body: $4015=$10 starts the sample, then polls BIT $4015
        // until bit 4 clears; the poll exit is the $4011=$00 write.
        RegWrite start = first(ws, 0x4015, 0x10);
        RegWrite pollExit = first(ws, 0x4011, 0x00);
        long gap = pollExit.cycle - start.cycle;
        assertTrue(gap >= SAMPLE_MIN && gap <= SAMPLE_MAX,
                "$4015 bit 4 must stay set for the full 17-byte sample and clear "
                        + "on the last-byte fetch; poll-loop gap was " + gap + " cycles");
        assertReachedBeepLoop(h, ws, 0xE14E);
        assertEquals(0, h.nes().getApu().dmc().bytesRemaining());
    }

    @Test
    void dmcTests_status_irq() {
        NesHarness h = load("status_irq.nes");
        List<RegWrite> ws = run(h);
        // $4010=$80 arms the DMC IRQ; the ONLY $4010=$00 write in the ROM
        // is the first store of its IRQ handler — its presence proves the
        // IRQ was raised on the last-byte fetch AND delivered to the CPU.
        RegWrite start = first(ws, 0x4015, 0x10);
        RegWrite handler = first(ws, 0x4010, 0x00);
        long gap = handler.cycle - start.cycle;
        assertTrue(gap >= SAMPLE_MIN && gap <= SAMPLE_MAX,
                "DMC IRQ handler must run one sample-duration after start "
                        + "(IRQ on last-byte fetch); gap was " + gap + " cycles");
        assertFalse(h.nes().getApu().irqAsserted(),
                "handler's $4010 write (bit 7 clear) clears the DMC IRQ flag");
        assertReachedBeepLoop(h, ws, 0xE154);
    }

    @Test
    void dmcTests_buffer_retained() {
        NesHarness h = load("buffer_retained.nes");
        List<RegWrite> ws = run(h);
        // Test body: $4015=$10 immediately followed by $4015=$00 — the
        // byte fetched at start must be RETAINED and play out.
        RegWrite start = first(ws, 0x4015, 0x10);
        RegWrite stop = firstAfter(ws, 0x4015, 0x00, start.cycle);
        assertTrue(stop.cycle > start.cycle && stop.cycle - start.cycle < 50,
                "enable→disable back-to-back, gap was " + (stop.cycle - start.cycle));
        assertEquals(0, h.nes().getApu().dmc().bytesRemaining(),
                "reader stopped by the disable");
        assertFalse(h.nes().getApu().dmc().isBufferFilled(),
                "the retained byte must have been consumed by the output unit, not dropped");
        assertTrue(h.nes().getApu().dmc().isSilenced(),
                "after the retained byte plays out the channel silences");
        assertReachedBeepLoop(h, ws, 0xE149);
    }

    /** Plan-designated stretch — sensitive to stall placement fine detail. */
    @Test
    void dmcTests_latency_stretch() {
        NesHarness h = load("latency.nes");
        List<RegWrite> ws = run(h);
        // Four timed iterations of $4011=$24 / $4015=$10 / $4011=$20 /
        // $4015=$00 clicks, then the beep.
        assertEquals(4, count(ws, 0x4011, 0x24), "four click iterations");
        assertEquals(4, count(ws, 0x4015, 0x10), "four DMC starts");
        assertReachedBeepLoop(h, ws, 0xE162);
    }
}
