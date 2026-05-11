package net.lomibao.nes.components;

/**
 * Standard NES controller — 8-button shift register at $4016 (player 1).
 * Player 2 ($4017) is not yet implemented; reads return open-bus
 * {@code 0x40} and writes are no-ops (real hardware also routes $4017
 * writes to the APU frame counter, which is its own component).
 *
 * <p>Step 3 of the playable-gen1 plan. Replaces the previous open-bus
 * stub with a real shift-register implementation modelled on bugzmanov
 * ch. 8. Hosts (keyboard, controller, network input) push button state
 * via {@link #setButton(int, boolean)}; the CPU side talks to the live
 * register via the standard write-strobe / read-shift protocol.
 *
 * <h2>Protocol</h2>
 * <pre>
 *   write $4016 with bit 0 = 1   → strobe high; read index resets to 0;
 *                                  reads return live button A bit
 *   write $4016 with bit 0 = 0   → strobe low; read index resumes shifting
 *   read $4016                   → returns next bit (A, B, SEL, START, U,
 *                                  D, L, R), then advances index. After
 *                                  index 7, every subsequent read returns
 *                                  1 until the next strobe.
 * </pre>
 *
 * <h2>Subtleties bugzmanov gets right (ch. 8) that olcNES misses</h2>
 * <ul>
 *   <li>After read 8, every read returns {@code 1} (open-bus high).
 *       Some games depend on this.</li>
 *   <li>Setting strobe to {@code 0} when it was already {@code 0} does
 *       not reset the read index — only the rising-edge of strobe
 *       reloads the latch. We model this as "strobe-high resets index
 *       to 0; strobe-low leaves it alone".</li>
 * </ul>
 */
public class Controller extends CPUBusComponent {

    /** $4016 — controller 1 / player 1 read+write port. */
    private static final int CONTROLLER_1_ADDRESS = 0x4016;
    /** $4017 — controller 2 read port. (APU frame counter on writes.) */
    private static final int CONTROLLER_2_ADDRESS = 0x4017;

    // ---- public button-bit masks (NESdev wiki standard order) ----
    public static final int A      = 0x01;
    public static final int B      = 0x02;
    public static final int SELECT = 0x04;
    public static final int START  = 0x08;
    public static final int UP     = 0x10;
    public static final int DOWN   = 0x20;
    public static final int LEFT   = 0x40;
    public static final int RIGHT  = 0x80;

    /** Live, host-supplied button state. Bit i is set iff the i-th button (in NESdev order) is pressed. */
    private int liveButtons = 0;
    /** Next bit to return on a strobe-low read; 0..7 normal, ≥8 means "always return 1 until next strobe". */
    private int readIndex = 0;
    /** True while the most recent $4016 write had bit 0 set. */
    private boolean strobe = false;

    @Override
    public void cpuBusWrite(int address, byte value) {
        if (address == CONTROLLER_1_ADDRESS) {
            boolean newStrobe = (value & 0x01) != 0;
            if (newStrobe) {
                // Strobe-high resets the index. We do NOT reset on
                // strobe-low transitions — the bugzmanov ch. 8 detail.
                readIndex = 0;
            }
            strobe = newStrobe;
        }
        // $4017 writes belong to the APU frame counter — no-op here.
    }

    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        if (address == CONTROLLER_1_ADDRESS) {
            return readPlayer1();
        } else if (address == CONTROLLER_2_ADDRESS) {
            // No player 2 wired up — open-bus high bit is a common stub.
            return 0x40;
        }
        return 0;
    }

    private int readPlayer1() {
        if (strobe) {
            // While strobe is high, the shift register is continually
            // reloaded with the live state — every read returns A.
            // Index is NOT advanced.
            return liveButtons & 1;
        }
        if (readIndex > 7) {
            // After the 8 buttons have shifted out, the controller line
            // is pulled high — every subsequent read returns 1 until the
            // next strobe pulse.
            return 1;
        }
        int bit = (liveButtons >> readIndex) & 1;
        readIndex++;
        return bit;
    }

    /**
     * Set or clear one or more buttons in the live state. Pass an OR of
     * the bit-mask constants (e.g. {@code setButton(A | START, true)}).
     *
     * @param mask one or more of {@link #A} .. {@link #RIGHT} OR'd together
     * @param pressed {@code true} to press, {@code false} to release
     */
    public void setButton(int mask, boolean pressed) {
        if (pressed) {
            liveButtons |= mask;
        } else {
            liveButtons &= ~mask;
        }
    }

    @Override
    public int getCPUBusStartAddress() {
        return CONTROLLER_1_ADDRESS;
    }

    @Override
    public int getCPUBusEndAddress() {
        return CONTROLLER_2_ADDRESS + 1; // exclusive
    }
}
