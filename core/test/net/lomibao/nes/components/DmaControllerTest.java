package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DmaController} (Step 4 of the playable-gen1 plan).
 *
 * <p>Contract:
 * <ul>
 *   <li>Writing byte {@code XX} to {@code $4014} initiates a 256-byte burst
 *       from CPU memory {@code $XX00..$XXFF} into PPU OAM
 *       {@code $00..$FF}.</li>
 *   <li>DMA takes <strong>513 or 514 CPU cycles</strong>: 1 wait cycle,
 *       +1 alignment cycle if started on an odd CPU cycle, +512 transfer
 *       cycles (256 alternating reads / writes).</li>
 *   <li>While DMA is active, the CPU is suspended — the bus's CPU-turn
 *       master ticks go to DMA instead of {@code cpu.clock()}.</li>
 *   <li>{@code $4014} is write-only; reads return {@code 0}.</li>
 * </ul>
 */
class DmaControllerTest {

    /** Builds a DmaController + RAM-backed CPU bus + PPU; everything wired through CPUBus. */
    private TestRig newRig() {
        return new TestRig();
    }

    static final class TestRig {
        final DmaController dma = new DmaController();
        final PPU ppu = new PPU();
        final FullAddressRam ram = new FullAddressRam();
        final CPU6502 cpu = new CPU6502();
        final CPUBus bus = CPUBus.builder()
                .cpu(cpu)
                .ppu(ppu)
                .testRam(ram)
                .dma(dma)
                .build()
                .connect();
    }

    @Test
    void dmaController_inactiveAtConstruction() {
        DmaController dma = new DmaController();
        assertFalse(dma.isActive(), "DMA should be inactive immediately after construction");
    }

    @Test
    void writeTo4014_marksDmaActive() {
        TestRig r = newRig();
        r.dma.cpuBusWrite(0x4014, (byte) 0x07);
        assertTrue(r.dma.isActive(), "writing $4014 should set DMA active");
    }

    @Test
    void writeTo4014_recordsPageByte() {
        TestRig r = newRig();
        r.dma.cpuBusWrite(0x4014, (byte) 0xA5);
        assertEquals(0xA5, r.dma.getPage(), "page byte should equal the value written");
    }

    @Test
    void cpuBusRange_isJust4014() {
        DmaController dma = new DmaController();
        assertEquals(0x4014, dma.getCPUBusStartAddress());
        assertEquals(0x4015, dma.getCPUBusEndAddress(), "exclusive end");
        assertTrue(dma.inCPUBusRange(0x4014));
        assertFalse(dma.inCPUBusRange(0x4013));
        assertFalse(dma.inCPUBusRange(0x4015));
    }

    @Test
    void read_at4014_returnsZero_writeOnly() {
        DmaController dma = new DmaController();
        assertEquals(0, dma.cpuBusRead(0x4014, false),
                "$4014 is write-only; read should return 0");
    }

    @Test
    void dma_copies256BytesFromCpuMemoryToOam_inOrder() {
        // Fill $0700-$07FF with a known pattern: ram[$07XX] = XX ^ 0x55
        TestRig r = newRig();
        for (int i = 0; i < 256; i++) {
            r.ram.cpuBusWrite(0x0700 + i, (byte) (i ^ 0x55));
        }

        // Trigger DMA from page $07
        r.dma.cpuBusWrite(0x4014, (byte) 0x07);

        // Tick until DMA completes (≤ 514 CPU cycles × 3 master ticks per CPU turn = ≤ 1542 master ticks).
        int safety = 0;
        while (r.dma.isActive() && safety++ < 2000) {
            r.bus.clock();
        }
        assertFalse(r.dma.isActive(), "DMA should complete within the safety window");

        // Verify OAM matches the source pattern byte-for-byte.
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) (i ^ 0x55), r.ppu.readOam(i),
                    "OAM[" + i + "] mismatch");
        }
    }

    @Test
    void dma_correctlyFollowsHighByteOfPage() {
        // Verify the address calculation page<<8|addr by using a non-zero
        // start byte at the BEGINNING of the page only.
        TestRig r = newRig();
        r.ram.cpuBusWrite(0x2A00, (byte) 0xDE);
        r.ram.cpuBusWrite(0x2AFF, (byte) 0xAD);
        r.dma.cpuBusWrite(0x4014, (byte) 0x2A);

        int safety = 0;
        while (r.dma.isActive() && safety++ < 2000) {
            r.bus.clock();
        }

        assertEquals((byte) 0xDE, r.ppu.readOam(0), "OAM[0] should be ram[$2A00]");
        assertEquals((byte) 0xAD, r.ppu.readOam(255), "OAM[255] should be ram[$2AFF]");
    }

    @Test
    void dma_consumesBetween513And514CpuCycles() {
        // Count CPU-turn master ticks (every 3rd) consumed by DMA.
        TestRig r = newRig();
        // Fill source page so reads succeed.
        for (int i = 0; i < 256; i++) {
            r.ram.cpuBusWrite(0x0500 + i, (byte) i);
        }
        r.dma.cpuBusWrite(0x4014, (byte) 0x05);

        long mcStart = r.bus.getMasterClockCount();
        int safety = 0;
        while (r.dma.isActive() && safety++ < 2000) {
            r.bus.clock();
        }
        long mcDelta = r.bus.getMasterClockCount() - mcStart;

        // Each CPU turn is 3 master ticks (CPU runs at 1/3 PPU speed).
        // 513 CPU cycles → 1539 master ticks; 514 → 1542. Allow inclusive
        // range plus the master tick that the bus.clock() loop took to
        // observe isActive()=false.
        assertTrue(mcDelta >= 513L * 3 && mcDelta <= 514L * 3 + 3,
                "DMA should consume 513-514 CPU cycles (1539-1545 master ticks); got " + mcDelta);
    }

    @Test
    void dma_doesNotAdvanceCpuClock_whileActive() {
        TestRig r = newRig();
        // Fill source page
        for (int i = 0; i < 256; i++) {
            r.ram.cpuBusWrite(0x0600 + i, (byte) (i & 0xFF));
        }
        // Set CPU at a sane state — cpu.clockCount = 0 right after construction
        long cpuClockStart = r.cpu.getClockCount();
        r.dma.cpuBusWrite(0x4014, (byte) 0x06);
        // Tick exactly enough to be IN the middle of DMA but not done.
        // 100 CPU turns × 3 master ticks = 300 master ticks
        for (int i = 0; i < 300; i++) {
            r.bus.clock();
        }
        assertTrue(r.dma.isActive(), "precondition: DMA still active mid-transfer");
        long cpuClockMid = r.cpu.getClockCount();
        assertEquals(cpuClockStart, cpuClockMid,
                "CPU clock should NOT advance while DMA is active (CPU is suspended)");
    }

    @Test
    void dma_cpuResumes_afterDmaCompletes() {
        // After DMA completes, the next CPU-turn master tick should clock
        // the CPU normally.
        TestRig r = newRig();
        // Set up a tiny program at the reset vector so CPU has work to do.
        // Reset vector at $FFFC/$FFFD points to $8000.
        r.ram.cpuBusWrite(0xFFFC, (byte) 0x00);
        r.ram.cpuBusWrite(0xFFFD, (byte) 0x80);
        // NOPs at $8000+ so the CPU runs cleanly.
        for (int i = 0; i < 32; i++) {
            r.ram.cpuBusWrite(0x8000 + i, (byte) 0xEA);
        }
        r.cpu.reset();
        // Drive 8 master ticks to drain the reset cycles.
        for (int i = 0; i < 24; i++) r.bus.clock();
        long cpuBeforeDma = r.cpu.getClockCount();

        // Trigger DMA, run to completion.
        r.dma.cpuBusWrite(0x4014, (byte) 0x06);
        int safety = 0;
        while (r.dma.isActive() && safety++ < 2000) {
            r.bus.clock();
        }
        long cpuAfterDma = r.cpu.getClockCount();
        // CPU shouldn't have advanced during DMA.
        assertEquals(cpuBeforeDma, cpuAfterDma, "CPU must be suspended during DMA");

        // Run a few more master ticks — CPU should now resume.
        for (int i = 0; i < 30; i++) {
            r.bus.clock();
        }
        long cpuAfterResume = r.cpu.getClockCount();
        assertTrue(cpuAfterResume > cpuAfterDma,
                "CPU should resume clocking after DMA completes");
    }

    @Test
    void dma_canBeTriggeredASecondTime_afterCompletion() {
        TestRig r = newRig();
        for (int i = 0; i < 256; i++) {
            r.ram.cpuBusWrite(0x0300 + i, (byte) (0xFF - i));
            r.ram.cpuBusWrite(0x0400 + i, (byte) i);
        }
        // First DMA from page $03
        r.dma.cpuBusWrite(0x4014, (byte) 0x03);
        int safety = 0;
        while (r.dma.isActive() && safety++ < 2000) r.bus.clock();
        assertEquals((byte) 0xFF, r.ppu.readOam(0), "first DMA should land first byte");

        // Second DMA from page $04 — overwrites OAM
        r.dma.cpuBusWrite(0x4014, (byte) 0x04);
        safety = 0;
        while (r.dma.isActive() && safety++ < 2000) r.bus.clock();
        assertEquals((byte) 0, r.ppu.readOam(0), "second DMA should overwrite OAM[0]");
        assertEquals((byte) 255, r.ppu.readOam(255), "second DMA should overwrite OAM[255]");
    }

    // ---------------------------------------------------------------------
    // B7 — per-burst wait-state + alignment contract
    //
    // NESdev (https://www.nesdev.org/wiki/DMA): OAM DMA takes
    //   halt cycle (1) + optional alignment (0 or 1) + 256 read/write pairs (512)
    //   = 513 or 514 cycles.
    //
    // The alignment cycle is consumed when DMA's first "get" (read) attempt
    // would otherwise land on a "put" (write) cycle. In this emulator we
    // model get cycles as even master ticks (read) and put cycles as odd
    // master ticks (write) — a common simplification since we don't model
    // the APU's get/put phase relative to the CPU clock.
    //
    // The per-burst contract is: every burst independently performs the
    // halt + maybe-align phase based on the current master clock parity at
    // the time of the first DMA tick. No state may carry over from a prior
    // burst.
    // ---------------------------------------------------------------------

    /** Rig with no CPU — used to count DMA cycles without CPU side effects. */
    static final class HeadlessRig {
        final DmaController dma = new DmaController();
        final PPU ppu = new PPU();
        final FullAddressRam ram = new FullAddressRam();
        final CPUBus bus = CPUBus.builder()
                .ppu(ppu)
                .testRam(ram)
                .dma(dma)
                .build()
                .connect();

        /** Fill source page so OAM reads succeed. */
        void fillPage(int page) {
            for (int i = 0; i < 256; i++) {
                ram.cpuBusWrite((page << 8) + i, (byte) i);
            }
        }

        /**
         * Drive bus.clock() until DMA finishes; return the number of CPU
         * cycles consumed (i.e., the count of CPU turns owned by DMA).
         */
        long runDmaToCompletion() {
            long dmaCpuCycles = 0;
            int safety = 0;
            while (dma.isActive() && safety++ < 2000) {
                long mc = bus.getMasterClockCount();
                // Every bus.clock() at mc%3==0 is a CPU turn. While dma.isActive()
                // is true ENTERING the tick, that CPU turn belongs to DMA.
                if (mc % 3 == 0) dmaCpuCycles++;
                bus.clock();
            }
            assertFalse(dma.isActive(), "DMA must complete within the safety window");
            return dmaCpuCycles;
        }
    }

    @Test
    void dma_singleBurstFromEvenCpuCycle_takes513Cycles() {
        // Trigger DMA when master clock is at an "even-CPU-turn" position
        // such that the FIRST DMA tick lands on a master tick where mc%2==1
        // (i.e., the wait state immediately yields and the first read happens
        // on the next CPU turn at an even mc). That gives 1 halt + 0 align +
        // 512 R/W = 513 cycles.
        HeadlessRig r = new HeadlessRig();
        r.fillPage(0x05);
        // Advance bus by 1 tick so the next CPU-turn (mc%3==0) lands at mc=3.
        // mc=3 is odd → first tickDmaCycle exits waiting on this same tick →
        // 1 halt cycle, 0 align, 512 R/W = 513 CPU cycles total.
        r.bus.clock(); // mc 0 -> 1
        r.dma.cpuBusWrite(0x4014, (byte) 0x05);
        long cycles = r.runDmaToCompletion();
        assertEquals(513L, cycles,
                "burst started so its first DMA tick lands on a get cycle should take exactly 513 cycles");
    }

    @Test
    void dma_singleBurstFromOddCpuCycle_takes514Cycles() {
        // Trigger DMA at mc=0 so the FIRST DMA tick is at mc=0 (even master
        // tick = put cycle in our model). The halt yields nothing yet; the
        // next CPU turn at mc=3 (odd = get cycle) is an alignment cycle.
        // 1 halt + 1 align + 512 R/W = 514 cycles.
        HeadlessRig r = new HeadlessRig();
        r.fillPage(0x06);
        // No pre-advance — bus.masterClockCount is 0, so first tick is at mc=0.
        r.dma.cpuBusWrite(0x4014, (byte) 0x06);
        long cycles = r.runDmaToCompletion();
        assertEquals(514L, cycles,
                "burst started so its first DMA tick lands on a put cycle should take exactly 514 cycles");
    }

    @Test
    void dma_backToBackBursts_eachIndependentlyAlign_noCarryOver() {
        // Run multiple bursts back-to-back. Each burst MUST independently
        // perform its own halt + alignment based on the master clock parity
        // at the time of its first DMA tick — no "waiting" state carries
        // over from the previous burst's tail.
        HeadlessRig r = new HeadlessRig();
        for (int p = 0x07; p <= 0x0A; p++) r.fillPage(p);

        // Burst 1: start at mc=0 → first DMA tick at mc=0 (even, put cycle
        // in this model) → halt + align + 512 R/W = 514 cycles. After this
        // the bus mc = 1540 (mc%3=1, next CPU turn at mc=1542, even).
        r.dma.cpuBusWrite(0x4014, (byte) 0x07);
        long burst1Cycles = r.runDmaToCompletion();
        assertEquals(514L, burst1Cycles, "burst 1 from even start should be 514");

        // Burst 2: trigger immediately. Next CPU turn at mc=1542 (even) →
        // same first-tick parity as burst 1 → 514 cycles again. This
        // demonstrates the controller doesn't "remember" it just finished —
        // it independently goes through halt+align.
        r.dma.cpuBusWrite(0x4014, (byte) 0x08);
        long burst2Cycles = r.runDmaToCompletion();
        assertEquals(514L, burst2Cycles,
                "burst 2 (triggered with no extra ticks between) — first DMA tick at mc=1542 (even) → 514");

        // Burst 3: pre-advance the bus by 3 ticks (one CPU-turn's worth)
        // to FLIP the next-CPU-turn parity. After burst 2 mc=3082;
        // 3 bus.clock() calls → mc=3085. Next CPU turn at mc=3087 (odd) → 513 cycles.
        r.bus.clock();
        r.bus.clock();
        r.bus.clock();
        r.dma.cpuBusWrite(0x4014, (byte) 0x09);
        long burst3Cycles = r.runDmaToCompletion();
        assertEquals(513L, burst3Cycles,
                "burst 3 with one extra CPU turn before it — first DMA tick at mc=3087 (odd) → 513");

        // Final OAM sanity: the third burst's data ($0900 onwards) must
        // have been correctly transferred — proves the controller still
        // functions correctly when its alignment behavior is exercised.
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) i, r.ppu.readOam(i),
                    "burst 3 OAM[" + i + "] should equal source byte");
        }
    }
}
