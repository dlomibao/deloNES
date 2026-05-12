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
 * the CPU is suspended (see NESdev: <a
 * href="https://www.nesdev.org/wiki/DMA">DMA</a>):
 * <ul>
 *   <li><b>1 halt cycle</b> while the OAM DMA control logic prepares.</li>
 *   <li><b>+1 alignment cycle</b> if DMA's first read attempt would land
 *       on a "put" (write) cycle. On real hardware these are APU get/put
 *       phases; this emulator models them as even master ticks (get,
 *       read-allowed) and odd master ticks (put, write-allowed). The
 *       alignment recomputes <em>per burst</em>: each {@code $4014} write
 *       restarts the halt+align dance independently of any prior burst's
 *       tail state.</li>
 *   <li><b>256 read/write pairs</b> (512 cycles): even master ticks read
 *       from {@code $XX00 + addr}, odd master ticks write that byte to
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

    /** Transfer index 0..255 (source byte offset within the page). Increments after each write. */
    private int srcIndex = 0;

    /** OAMADDR captured at the start of the DMA burst — destination base in OAM. */
    private int oamAddrBase = 0;

    /** Latched byte read on the previous cycle; written to OAM on this cycle. */
    private byte data = 0;

    @Override
    public void cpuBusWrite(int address, byte value) {
        if (address == OAMDMA) {
            page = value & 0xFF;
            srcIndex = 0;
            // NESdev: DMA writes to OAM starting at OAMADDR, wrapping at byte 256.
            // Most games write 0 to OAMADDR first so this commonly resolves to 0,
            // but games that don't depend on this offset for correct sprite layout.
            CPUBus bus = getBus();
            PPU ppu = bus != null ? bus.getPpu() : null;
            oamAddrBase = ppu != null ? ppu.getOamAddr() : 0;
            active = true;
            // Restart the halt + alignment phase for THIS burst. Every
            // $4014 write resets this flag independently — alignment is
            // never inherited from a prior burst (see tickDmaCycle).
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
            // Halt + alignment phase: consume CPU cycles until we land on
            // an odd master tick. That odd tick is itself consumed as the
            // last halt/align cycle; the NEXT CPU turn (3 master ticks
            // later, i.e. even master tick) does the first read.
            //
            // Two cases:
            //   - First tick parity is ODD: this tick is the halt cycle,
            //     we clear waiting, and the next CPU turn reads. Total
            //     halt+align = 1 cycle → 513 cycles total.
            //   - First tick parity is EVEN: this tick is the halt cycle
            //     (waiting stays true). The next CPU turn (odd) is the
            //     alignment cycle, clears waiting. The CPU turn after
            //     that (even) reads. Total halt+align = 2 cycles → 514
            //     cycles total.
            if (masterClockCount % 2 == 1) {
                waiting = false;
            }
            return;
        }
        // Read on even master ticks (get cycles), write on odd master ticks
        // (put cycles). The halt+align logic above guarantees the first
        // read of every burst lands on an even tick.
        if (masterClockCount % 2 == 0) {
            data = (byte) bus.read((page << 8) | srcIndex, false);
        } else {
            ppu.writeOam((srcIndex + oamAddrBase) & 0xFF, data);
            srcIndex = (srcIndex + 1) & 0xFF;
            if (srcIndex == 0) {
                // Wrapped — all 256 bytes transferred. Clear active so the
                // CPU resumes on the next CPU-turn master tick. The waiting
                // flag is intentionally NOT touched here: the next $4014
                // write resets it to true (see cpuBusWrite), so back-to-back
                // bursts independently re-align based on the master clock
                // parity at the time of THAT burst's first DMA tick.
                active = false;
            }
        }
    }
}
