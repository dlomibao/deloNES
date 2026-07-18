package net.lomibao.nes.components;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B1 — seam S1: the single nullable {@link BusWriteListener} on
 * {@link CPUBus}. Contract (headless-harness plan, revision 2):
 * <ul>
 *   <li>listener fires BEFORE the write is routed, with {@code pc} from
 *       {@code cpu.getPc()} at write time ("pc after fetch");</li>
 *   <li>{@code oldValue} is the pre-write RAM value for $0000-$1FFF, taken
 *       via a readOnly snapshot only when a listener is installed;</li>
 *   <li>{@code oldValue == -1} for every non-RAM address (PPU registers,
 *       APU/IO, cartridge space);</li>
 *   <li>a null listener means zero behavior change (the nestest 8992/8992
 *       gate covers the hot path; here we pin state-identity).</li>
 * </ul>
 */
class CPUBusWriteListenerTest {

    /** One captured onWrite dispatch. */
    private static final class Event {
        final int addr;
        final int oldValue;
        final byte newValue;
        final int pc;

        Event(int addr, int oldValue, byte newValue, int pc) {
            this.addr = addr;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.pc = pc;
        }
    }

    private static final class Recorder implements BusWriteListener {
        final List<Event> events = new ArrayList<>();

        @Override
        public void onWrite(int addr, int oldValue, byte newValue, int pc) {
            this.events.add(new Event(addr, oldValue, newValue, pc));
        }

        Event single() {
            assertEquals(1, events.size(), "expected exactly one dispatch");
            return events.get(0);
        }
    }

    /** Minimal bus: CPU + RAM + PPU (so $2000-$3FFF routes somewhere). */
    private static CPUBus bus() {
        return CPUBus.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(new PPU())
                .apu(new APU())
                .build()
                .connect();
    }

    @Test
    void listenerFiresWithAddrValueAndPc() {
        CPUBus bus = bus();
        bus.getCpu().setPc(0xC123);
        Recorder rec = new Recorder();
        bus.setWriteListener(rec);

        bus.write(0x0080, (byte) 0x42);

        Event e = rec.single();
        assertEquals(0x0080, e.addr);
        assertEquals((byte) 0x42, e.newValue);
        assertEquals(0xC123, e.pc, "pc sampled from cpu.getPc() at write time");
    }

    @Test
    void oldValueIsPreWriteRamValue() {
        CPUBus bus = bus();
        bus.write(0x0080, (byte) 0x11); // no listener yet — plain write
        Recorder rec = new Recorder();
        bus.setWriteListener(rec);

        bus.write(0x0080, (byte) 0x22);

        Event e = rec.single();
        assertEquals(0x11, e.oldValue, "snapshot taken BEFORE the write lands");
        assertEquals((byte) 0x22, e.newValue);
        assertEquals(0x22, bus.read(0x0080, true), "the write itself still lands");
    }

    @Test
    void oldValueSnapshotUsesMirroredRamCell() {
        CPUBus bus = bus();
        bus.write(0x0080, (byte) 0x33);
        Recorder rec = new Recorder();
        bus.setWriteListener(rec);

        bus.write(0x0880, (byte) 0x44); // $0880 mirrors $0080

        Event e = rec.single();
        assertEquals(0x0880, e.addr, "listener sees the CPU-visible (as-seen) address");
        assertEquals(0x33, e.oldValue, "old value read through the RAM mirror");
    }

    @Test
    void oldValueIsMinusOneForPpuRegisterWrite() {
        CPUBus bus = bus();
        Recorder rec = new Recorder();
        bus.setWriteListener(rec);

        bus.write(0x2000, (byte) 0x80);

        Event e = rec.single();
        assertEquals(0x2000, e.addr);
        assertEquals(-1, e.oldValue, "PPU registers have no bus-level old value");
    }

    @Test
    void oldValueIsMinusOneForApuIoAndCartSpaceWrites() {
        CPUBus bus = bus();
        Recorder rec = new Recorder();
        bus.setWriteListener(rec);

        bus.write(0x4000, (byte) 0x01); // APU
        bus.write(0x8000, (byte) 0x02); // cartridge PRG space

        assertEquals(2, rec.events.size());
        assertEquals(-1, rec.events.get(0).oldValue);
        assertEquals(-1, rec.events.get(1).oldValue);
    }

    @Test
    void nullListenerMeansNoDispatchAndIdenticalState() {
        CPUBus bus = bus();
        Recorder rec = new Recorder();
        bus.setWriteListener(rec);
        bus.write(0x0010, (byte) 0x77);
        assertEquals(1, rec.events.size());

        bus.setWriteListener(null); // detach — back to the fast path
        bus.write(0x0010, (byte) 0x78);
        bus.write(0x0011, (byte) 0x79);

        assertEquals(1, rec.events.size(), "no dispatch after detach");
        assertEquals(0x78, bus.read(0x0010, true));
        assertEquals(0x79, bus.read(0x0011, true), "writes land identically without a listener");
    }

    @Test
    void listenerFiresForEveryWriteInOrder() {
        CPUBus bus = bus();
        Recorder rec = new Recorder();
        bus.setWriteListener(rec);

        bus.write(0x0000, (byte) 1);
        bus.write(0x0001, (byte) 2);
        bus.write(0x0002, (byte) 3);

        assertEquals(3, rec.events.size());
        assertEquals(0x0000, rec.events.get(0).addr);
        assertEquals(0x0001, rec.events.get(1).addr);
        assertEquals(0x0002, rec.events.get(2).addr);
        assertTrue(rec.events.get(0).newValue == 1
                && rec.events.get(1).newValue == 2
                && rec.events.get(2).newValue == 3);
    }
}
