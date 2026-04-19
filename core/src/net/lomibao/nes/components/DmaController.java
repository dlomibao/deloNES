package net.lomibao.nes.components;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * OAM DMA controller — handles writes to {@code $4014}, the
 * single-byte register that triggers a 256-byte burst copy from CPU memory
 * page {@code $XX00..$XXFF} to PPU OAM {@code $00..$FF}.
 *
 * <p>Step 4 of the playable-gen1 plan. Every NES game that displays
 * sprites does it this way: maintain a 256-byte "shadow OAM" in CPU RAM,
 * then once per frame write {@code $XX} to {@code $4014} to ship the
 * whole shadow into PPU OAM in a single burst.
 *
 * <h2>Cycle timing</h2>
 * <p>DMA takes <strong>513 or 514 CPU cycles</strong> total, during which
 * the CPU is suspended:
 * <ul>
 *   <li><b>1 wait cycle</b> while the OAM DMA control logic prepares.</li>
 *   <li><b>+1 alignment cycle</b> if DMA starts on an odd CPU cycle (the
 *       transfer must begin on an even CPU cycle so the read/write
 *       alternation lines up).</li>
 *   <li><b>256 read/write pairs</b> (512 cycles): even CPU cycles read
 *       from {@code $XX00 + addr}, odd cycles write that byte to
 *       {@code OAM[addr]}.</li>
 * </ul>
 *
 * <p>The CPU bus drives this via {@link #tickDmaCycle}, called once per
 * CPU-turn master tick (every 3rd master clock) when {@link #isActive()}
 * is true — instead of {@code cpu.clock()}.
 */
@Log4j2
public class DmaController extends CPUBusComponent {

    /** {@code $4014} — write-only OAM DMA trigger. */
    private static final int OAMDMA = 0x4014;

    /** True while a DMA burst is in progress. CPU is suspended. */
    @Getter
    private boolean active = false;

    /** True during the wait/alignment cycles before the read/write pairs begin. */
    private boolean waiting = false;

    /** High byte of the source address (set by the {@code $4014} write). */
    @Getter
    private int page = 0;

    /** Low byte of the current transfer address (0..255, increments after each write). */
    private int addr = 0;

    /** Latched byte read on the previous cycle; written to OAM on this cycle. */
    private byte data = 0;

    @Override
    public void cpuBusWrite(int address, byte value) {
        if (address == OAMDMA) {
            page = value & 0xFF;
            addr = 0;
            active = true;
            // Wait at least one cycle, then align to an even master cycle
            // before starting the read/write alternation.
            waiting = true;
        }
    }

    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        // $4014 is write-only on real hardware. Reads return 0 (open-bus
        // would be slightly more accurate but no game depends on it).
        return 0;
    }

    @Override
    public int getCPUBusStartAddress() {
        return OAMDMA;
    }

    @Override
    public int getCPUBusEndAddress() {
        return OAMDMA + 1;
    }

    /**
     * Advance the DMA state machine by exactly one CPU cycle. The CPU bus
     * calls this on its CPU-turn master ticks (every 3rd master clock)
     * when {@link #isActive()} is true — replacing what would normally be
     * {@code cpu.clock()}.
     *
     * @param bus the CPU bus to read source bytes through
     * @param ppu the PPU whose OAM receives the bytes
     * @param masterClockCount the bus's current master clock count;
     *        used to determine even/odd CPU cycle for the read/write
     *        alternation
     */
    public void tickDmaCycle(CPUBus bus, PPU ppu, long masterClockCount) {
        if (waiting) {
            // Wait at least one cycle, then transition to the active phase
            // when we land on an odd master tick — this guarantees the very
            // next CPU-turn (3 master ticks later) lands on an even master
            // tick, where the first read happens.
            if (masterClockCount % 2 == 1) {
                waiting = false;
            }
            return;
        }
        // Read on even master ticks, write on odd master ticks.
        if (masterClockCount % 2 == 0) {
            data = (byte) bus.read((page << 8) | addr, false);
        } else {
            ppu.writeOam(addr, data);
            addr = (addr + 1) & 0xFF;
            if (addr == 0) {
                // Wrapped — all 256 bytes transferred.
                active = false;
                waiting = true; // reset for next DMA
            }
        }
    }
}
