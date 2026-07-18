package net.lomibao.nes.harness;

import java.util.List;

/**
 * RAM write-trace value types + dump formatting (headless-harness plan,
 * Phase B3). Collected by {@link NesHarness#trace(int, int, Runnable)},
 * which registers a temporary range watch for the duration of the body —
 * the supported replacement for the DIAG_* static hack's
 * "who writes $03B4" workflow:
 *
 * <pre>
 * List&lt;RamTrace.Write&gt; ws = h.trace(0x03B4, 0x03B4, () -&gt; h.runFrames(1));
 * System.out.println(RamTrace.format(ws));
 * </pre>
 */
public final class RamTrace {

    private RamTrace() {
    }

    /** One traced RAM write: {frame, pc, addr, old, new}. */
    public static final class Write {
        private final int frame;
        private final int pc;
        private final int addr;
        private final int oldValue;
        private final int newValue;

        Write(int frame, int pc, int addr, int oldValue, int newValue) {
            this.frame = frame;
            this.pc = pc;
            this.addr = addr;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        /** Harness frame during which the write occurred. */
        public int frame() {
            return frame;
        }

        /** {@code cpu.getPc()} at write time ("pc after fetch"). */
        public int pc() {
            return pc;
        }

        /** Mirror-canonicalized RAM address ($0000-$07FF window). */
        public int addr() {
            return addr;
        }

        /** Pre-write value, 0-255 (always defined — traces are RAM-only). */
        public int oldValue() {
            return oldValue;
        }

        /** Written value, 0-255. */
        public int newValue() {
            return newValue;
        }

        @Override
        public String toString() {
            return String.format("frame=%d pc=$%04X $%04X: $%02X→$%02X",
                    frame, pc & 0xFFFF, addr, oldValue & 0xFF, newValue);
        }
    }

    /**
     * Format a trace as one line per write —
     * {@code frame=12 pc=$C123 $03B4: $00→$EE} — the "who writes $X" dump.
     */
    public static String format(List<Write> writes) {
        StringBuilder sb = new StringBuilder();
        for (Write w : writes) {
            sb.append(w).append('\n');
        }
        return sb.toString();
    }
}
