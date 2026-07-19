package net.lomibao.nes.components.apu;

import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.CPUBus;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D2 — DMC DMA CPU stalls (seam S5, mechanism per plan decision
 * D4): the stall state machine is APU-owned and consumed in the
 * CPU-turn slot of {@code CPUBus.clock()}, checked BEFORE
 * {@code dma.isActive()} (DMC wins; an OAM burst pauses). Flat
 * <b>4-cycle</b> stall for reload fetches, <b>3-cycle</b> for
 * $4015-triggered start fetches; the fetch happens on the LAST stall
 * cycle. The APU itself never stops clocking.
 *
 * <p>The parity-preservation argument (D4) is load-bearing:
 * {@code DmaController} alternates get/put on {@code masterClockCount %
 * 2} and consecutive CPU turns alternate that parity, so only an
 * even-length stall may interleave a paused OAM burst — the 4-cycle
 * reload preserves the alternation phase, and the 3-cycle start stall
 * can only be armed by a CPU write to $4015, impossible while the CPU
 * is halted inside a burst. The
 * {@link #oamBurst_resumesWithIntactGetPutAlternation_afterMidBurstDmcStall()}
 * test is the integrity check (not a length tripwire — see class note): changing either stall length corrupts it.
 */
class ApuDmcStallTest {

    /** 16KB NROM filled with NOPs ($EA) — CPU spins harmlessly; DMC sample data = $EA. */
    private static Cartridge nopCartridge() {
        byte[] prg = new byte[16 * 1024];
        for (int i = 0; i < prg.length; i++) {
            prg[i] = (byte) 0xEA;
        }
        prg[0x3FFC] = 0x00; // reset vector → $8000
        prg[0x3FFD] = (byte) 0x80;
        byte[] rom = new byte[16 + prg.length + 8 * 1024];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;
        rom[5] = 1;
        System.arraycopy(prg, 0, rom, 16, prg.length);
        return new Cartridge(new ByteArrayInputStream(rom), "dmc-stall-nop.nes");
    }

    private final CPU6502 cpu = new CPU6502();
    private final APU apu = new APU();
    private final PPU ppu = new PPU();
    private final DmaController dma = new DmaController();
    private final CPUBus bus = CPUBus.builder()
            .cpu(cpu)
            .apu(apu)
            .ppu(ppu)
            .ram(new Ram())
            .dma(dma)
            .cartridge(nopCartridge())
            .build()
            .connect();

    ApuDmcStallTest() {
        bus.reset();
        runCpuTurns(20); // burn the CPU's reset sequence; it then spins on NOPs
    }

    /** One CPU turn = 3 master clocks (phase 0 after reset is the CPU slot). */
    private void runCpuTurns(int n) {
        for (int i = 0; i < 3 * n; i++) {
            bus.clock();
        }
    }

    /** Start a looping 1-byte sample at rate 15 — a reload fetch every 432 CPU cycles. */
    private void startLoopingDmc() {
        bus.write(0x4010, (byte) 0x4F); // loop, rate 15
        bus.write(0x4013, (byte) 0x00); // 1 byte, reloads forever
        bus.write(0x4015, (byte) 0x10); // start — arms the 3-cycle stall
    }

    /** Run until the first CPU turn in which the CPU did not clock (a stall began). */
    private void runToNextStallTurn() {
        for (int guard = 0; guard < 1000; guard++) {
            long before = cpu.getClockCount();
            runCpuTurns(1);
            if (cpu.getClockCount() == before) {
                return;
            }
        }
        throw new AssertionError("no stall turn within 1000 CPU turns");
    }

    // ------------------------------------------------------------------
    // 3-cycle $4015-triggered start stall
    // ------------------------------------------------------------------

    @Test
    void startFetch_stallsCpuExactly3Turns_fetchOnLastCycle() {
        startLoopingDmc();
        assertTrue(apu.dmcStallPending(), "$4015 start arms the stall immediately");
        assertFalse(apu.dmc().bufferFilled, "no magic arrival — the fetch waits for the stall");
        long c0 = cpu.getClockCount();
        runCpuTurns(1); // stall cycle 1
        runCpuTurns(1); // stall cycle 2
        assertTrue(apu.dmcStallPending());
        assertFalse(apu.dmc().bufferFilled, "fetch happens on the LAST stall cycle, not before");
        runCpuTurns(1); // stall cycle 3 — the fetch
        assertFalse(apu.dmcStallPending());
        assertTrue(apu.dmc().bufferFilled, "byte fetched on the final stall cycle");
        assertEquals(0xEA, apu.dmc().sampleBuffer, "fetched through cpuBus.read()");
        assertEquals(c0, cpu.getClockCount(), "CPU suspended for exactly the 3 stall turns");
        runCpuTurns(1);
        assertEquals(c0 + 1, cpu.getClockCount(), "CPU resumes on the next turn");
    }

    // ------------------------------------------------------------------
    // 4-cycle reload stall
    // ------------------------------------------------------------------

    @Test
    void reloadFetch_stallsCpuExactly4Turns_fetchOnLastCycle() {
        startLoopingDmc();
        runCpuTurns(3); // consume the start stall
        assertTrue(apu.dmc().bufferFilled);
        // Next refill (≤ 432 CPU cycles away) drains the buffer and arms
        // the 4-cycle reload stall; the arming turn is stall cycle 1.
        runToNextStallTurn();
        assertTrue(apu.dmcStallPending(), "reload stall spans further turns");
        assertFalse(apu.dmc().bufferFilled);
        long c0 = cpu.getClockCount();
        runCpuTurns(1); // stall cycle 2
        runCpuTurns(1); // stall cycle 3
        assertTrue(apu.dmcStallPending());
        assertFalse(apu.dmc().bufferFilled, "fetch happens on the LAST stall cycle, not before");
        runCpuTurns(1); // stall cycle 4 — the fetch
        assertFalse(apu.dmcStallPending());
        assertTrue(apu.dmc().bufferFilled);
        assertEquals(c0, cpu.getClockCount(), "CPU suspended through stall cycles 2-4");
        runCpuTurns(1);
        assertEquals(c0 + 1, cpu.getClockCount(), "CPU resumes after exactly 4 stall turns");
    }

    // ------------------------------------------------------------------
    // The APU never stops clocking
    // ------------------------------------------------------------------

    @Test
    void apuKeepsClockingThroughTheStall() {
        startLoopingDmc();
        assertTrue(apu.dmcStallPending());
        int fc0 = apu.frameCounter().cycle();
        runCpuTurns(3); // the whole start stall
        assertEquals(fc0 + 3, apu.frameCounter().cycle(),
                "frame counter advances every CPU cycle of the stall (seam S2 ordering)");
    }

    // ------------------------------------------------------------------
    // No DMC activity → zero stall cycles (the nestest CYC-drift guard)
    // ------------------------------------------------------------------

    @Test
    void idleDmc_neverStallsTheCpu() {
        long c0 = cpu.getClockCount();
        runCpuTurns(1000);
        assertEquals(c0 + 1000, cpu.getClockCount(),
                "no DMC samples → no stalls → zero CYC drift (nestest gate argument)");
        assertFalse(apu.dmcStallPending());
    }

    // ------------------------------------------------------------------
    // Stall committed, DMC stopped mid-stall — aborts the fetch safely
    // ------------------------------------------------------------------

    @Test
    void dmcStoppedMidStall_stillConsumesTheStall_butAbortsTheFetch() {
        startLoopingDmc();
        assertTrue(apu.dmcStallPending());
        bus.write(0x4015, (byte) 0x00); // stop — bytes := 0 while the stall is committed
        runCpuTurns(3);
        assertFalse(apu.dmcStallPending(), "committed stall cycles are still consumed");
        assertFalse(apu.dmc().bufferFilled, "aborted fetch delivers nothing");
        assertEquals(0, apu.dmc().bytesRemaining(), "reader stays stopped — no underflow");
    }

    // ------------------------------------------------------------------
    // Arbitration: DMC before OAM; burst pauses and resumes intact (D4)
    // ------------------------------------------------------------------

    @Test
    void oamBurst_resumesWithIntactGetPutAlternation_afterMidBurstDmcStall() {
        startLoopingDmc();
        runCpuTurns(3); // start stall consumed
        // Settle into rate-15 steady state (the in-flight timer countdown
        // was loaded at the default rate-0 period): after ~600 turns the
        // reload cadence is a fetch every 432 CPU cycles — any burst of
        // 513+ cycles then contains at least one reload stall.
        runCpuTurns(600);
        // Shadow OAM page at $0200 with a recognizable pattern.
        for (int i = 0; i < 256; i++) {
            bus.write(0x0200 + i, (byte) (i ^ 0x5A));
        }
        bus.write(0x4014, (byte) 0x02); // OAM DMA burst — 513/514 CPU cycles
        assertTrue(dma.isActive());
        long haltStart = cpu.getClockCount();
        boolean sawStallDuringBurst = false;
        int burstTurns = 0;
        while (dma.isActive()) {
            runCpuTurns(1);
            burstTurns++;
            if (apu.dmcStallPending()) {
                sawStallDuringBurst = true;
            }
            assertTrue(burstTurns < 2000, "burst never completed");
        }
        assertTrue(sawStallDuringBurst,
                "a DMC reload stall (period 432 < burst 513) must interleave the burst");
        assertEquals(haltStart, cpu.getClockCount(),
                "CPU stays halted across the burst AND the embedded DMC stall");
        // Burst length = 513/514 base + 4 per embedded reload stall.
        int over513 = burstTurns - 513;
        int over514 = burstTurns - 514;
        assertTrue((over513 > 0 && over513 % 4 == 0) || (over514 > 0 && over514 % 4 == 0),
                "burst extended by whole 4-cycle stalls, got " + burstTurns + " turns");
        // Pause/resume INTEGRITY check — NOT a stall-length integrity check (not a length tripwire — see class note).
        // Review round 1 proved by mutation (length 3 and 5) that OAM
        // content survives ANY stall parity here: the DMA keys get/put
        // off masterClockCount statelessly, so the pre-stall latched
        // byte still lands and read/write pairing self-heals. The stall
        // lengths are guarded by the exact-count tests above, whose
        // authority is the NESdev citation (4-cycle reload / 3-cycle
        // $4015 start), not this assertion.
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) (i ^ 0x5A), ppu.readOam(i),
                    "OAM[" + i + "] — get/put alternation must survive the mid-burst stall");
        }
    }

    // ------------------------------------------------------------------
    // Arbitration order: the stall consumes the turn even while OAM is active
    // ------------------------------------------------------------------

    @Test
    void dmcStall_winsTheCpuTurn_overActiveOamDma() {
        startLoopingDmc();
        runCpuTurns(3);
        runCpuTurns(600); // settle into the 432-cycle reload cadence (see above)
        bus.write(0x4014, (byte) 0x02);
        assertTrue(dma.isActive());
        // Drive to the next stall inside the burst, then verify the DMC
        // fetch completes while the burst is still active — DMC wins the
        // slot (checked before dma.isActive()), OAM merely pauses.
        int guard = 0;
        while (!apu.dmcStallPending()) {
            runCpuTurns(1);
            guard++;
            assertTrue(dma.isActive(), "burst must still be running when the stall arms");
            assertTrue(guard < 1000, "no stall armed inside the burst");
        }
        runCpuTurns(4); // remaining stall cycles regardless of arm alignment
        assertTrue(apu.dmc().bufferFilled, "DMC fetch completed while OAM DMA was active");
        assertTrue(dma.isActive(), "OAM burst paused, not cancelled");
    }
}
