package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;

import java.util.function.Consumer;

/**
 * A registered memory watch (headless-harness plan, Phase B2) — the handle
 * returned by the {@link WatchDsl} terminal methods. Watches are the
 * <em>exact</em> trigger tier: they fire mid-frame, synchronously from the
 * S1 {@code CPUBus} write-listener seam, with PC attribution.
 *
 * <h2>Pinned semantics (plan, API-sketch section)</h2>
 * <ul>
 *   <li>Old values exist only for CPU RAM ($0000-$1FFF); value-tracking
 *       kinds are registration-restricted to that range. Non-RAM addresses
 *       report {@link Write#oldValue()} {@code == -1}.</li>
 *   <li>Matching is mirror-canonicalized: RAM on {@code addr & 0x07FF},
 *       PPU registers on {@code 0x2000 + (addr & 7)}.</li>
 *   <li>Callbacks may throw {@link AssertionError}; it propagates out of
 *       {@code runFrames}/{@code runToFrame} unwrapped.</li>
 *   <li>OAM-DMA bursts bypass {@code CPUBus.write} entirely
 *       ({@code DmaController} calls {@code ppu.writeOam} directly), so
 *       watches never see them — {@code OamCensus} is the sanctioned view
 *       of OAM contents.</li>
 * </ul>
 */
public final class Watch {

    enum Kind { WRITE, CHANGE, BECOMES, CROSS_ABOVE, CROSS_BELOW }

    private final NesHarness harness;
    /** Canonical inclusive match bounds. */
    private final int lo;
    private final int hi;
    private final Kind kind;
    /** BECOMES target value, or CROSS_* threshold; unused for WRITE/CHANGE. */
    private final int value;
    private final Consumer<Write> writeCallback;
    private final Consumer<Context> contextCallback;

    Watch(NesHarness harness, int lo, int hi, Kind kind, int value,
          Consumer<Write> writeCallback, Consumer<Context> contextCallback) {
        this.harness = harness;
        this.lo = lo;
        this.hi = hi;
        this.kind = kind;
        this.value = value;
        this.writeCallback = writeCallback;
        this.contextCallback = contextCallback;
    }

    /**
     * Mirror canonicalization: RAM mirrors collapse to $0000-$07FF, PPU
     * register mirrors to $2000-$2007; everything else is itself.
     */
    static int canonical(int addr) {
        addr &= 0xFFFF;
        if (addr < 0x2000) {
            return addr & 0x07FF;
        }
        if (addr < 0x4000) {
            return 0x2000 | (addr & 7);
        }
        return addr;
    }

    boolean matches(int canonicalAddr) {
        return canonicalAddr >= lo && canonicalAddr <= hi;
    }

    private boolean removed = false;

    /** Evaluate the trigger against one bus write; fire the callback if it trips. */
    void dispatch(Write w) {
        if (removed) {
            // Dispatch runs over a snapshot; a watch removed by an earlier
            // callback in the same write must not fire.
            return;
        }
        switch (kind) {
            case WRITE:
                fire(w);
                break;
            case CHANGE:
                if (w.oldValue() != w.newValue()) {
                    fire(w);
                }
                break;
            case BECOMES:
                if (w.newValue() == value && w.oldValue() != value) {
                    fire(w);
                }
                break;
            case CROSS_ABOVE:
                if (w.oldValue() <= value && w.newValue() > value) {
                    fire(w);
                }
                break;
            case CROSS_BELOW:
                if (w.oldValue() >= value && w.newValue() < value) {
                    fire(w);
                }
                break;
        }
    }

    private void fire(Write w) {
        if (writeCallback != null) {
            writeCallback.accept(w);
        }
        if (contextCallback != null) {
            contextCallback.accept(new Context(harness, w));
        }
    }

    /** Unregister this watch; when the last watch goes, the S1 listener detaches. */
    public void remove() {
        removed = true;
        harness.removeWatch(this);
    }

    // -------------------------------------------------------------------------

    /**
     * One observed CPU-bus write. Byte payloads are normalized to unsigned
     * ints (0-255) so comparisons like {@code w.newValue() == 0xEE} read
     * naturally.
     */
    public static final class Write {
        private final int frame;
        private final int pc;
        private final int busAddr;
        private final int addr;
        private final int oldValue;
        private final int newValue;

        Write(int frame, int pc, int busAddr, int addr, int oldValue, int newValue) {
            this.frame = frame;
            this.pc = pc;
            this.busAddr = busAddr;
            this.addr = addr;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        /** Harness frame ({@code h.frame()}) during which the write occurred. */
        public int frame() {
            return frame;
        }

        /** {@code cpu.getPc()} at write time ("pc after fetch"). */
        public int pc() {
            return pc;
        }

        /** The CPU-visible (as-seen) address. */
        public int busAddr() {
            return busAddr;
        }

        /** The mirror-canonicalized address (what watches match on). */
        public int addr() {
            return addr;
        }

        /** Pre-write value for CPU RAM ($0000-$1FFF); {@code -1} elsewhere. */
        public int oldValue() {
            return oldValue;
        }

        /** The written value as an unsigned int, 0-255. */
        public int newValue() {
            return newValue;
        }

        public String pcHex() {
            return hex16(pc);
        }

        /** Canonical address, e.g. {@code "$0300"}. */
        public String addrHex() {
            return hex16(addr);
        }

        /** {@code "$--"} when no old value exists (non-RAM). */
        public String oldHex() {
            return oldValue < 0 ? "$--" : hex8(oldValue);
        }

        public String newHex() {
            return hex8(newValue);
        }

        @Override
        public String toString() {
            return "frame=" + frame + " pc=" + pcHex() + " " + addrHex()
                    + ": " + oldHex() + "→" + newHex()
                    + (busAddr == addr ? "" : " (bus " + hex16(busAddr) + ")");
        }

        private static String hex16(int v) {
            String s = Integer.toHexString(v & 0xFFFF).toUpperCase();
            return "$0000".substring(0, 5 - s.length()) + s;
        }

        private static String hex8(int v) {
            String s = Integer.toHexString(v & 0xFF).toUpperCase();
            return "$00".substring(0, 3 - s.length()) + s;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Handed to {@code then(...)} trigger actions. Gated presses take
     * effect at the NEXT frame boundary (D2 — the trigger fires mid-frame,
     * but input only ever changes between frames, keeping runs
     * deterministic and replayable).
     */
    public static final class Context {
        private final NesHarness harness;
        private final Write write;

        Context(NesHarness harness, Write write) {
            this.harness = harness;
            this.write = write;
        }

        /** The bus write that tripped the trigger. */
        public Write write() {
            return write;
        }

        public NesHarness harness() {
            return harness;
        }

        /** Queue a player-0 press applied at the next frame boundary. */
        public void press(Button button, int holdFrames) {
            press(0, button, holdFrames);
        }

        /** Queue a press for the given player, applied at the next frame boundary. */
        public void press(int player, Button button, int holdFrames) {
            harness.queueGatedPress(player, button, holdFrames);
        }
    }
}
