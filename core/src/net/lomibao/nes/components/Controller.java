package net.lomibao.nes.components;

/**
 * Standard NES dual controller — two 8-button shift registers at $4016 (P1)
 * and $4017 (P2).
 *
 * <h2>Protocol</h2>
 * <pre>
 *   write $4016 with bit 0 = 1  → strobe high; while high, shift registers are
 *                                  continuously reloaded from live state; reads
 *                                  always return the A bit (bit 0).
 *   write $4016 with bit 0 = 0  → strobe falls; current live state is latched
 *                                  into both shift registers; read index resets
 *                                  to 0 for sequential reads.
 *   read $4016                  → P1: return next latched bit, LSB first (A,B,
 *                                  SELECT,START,UP,DOWN,LEFT,RIGHT). After 8
 *                                  reads returns 1 (open-bus). Upper bits 0x40.
 *   read $4017                  → P2: same protocol. Upper bits 0x40.
 * </pre>
 *
 * <h2>Two-player model</h2>
 * Internal state is {@code boolean[2][8]} — player index 0/1, button index
 * matches {@link Button#ordinal()}.
 *
 * <h2>Backward compatibility</h2>
 * The legacy single-int-mask API ({@code setButton(int mask, boolean pressed)}
 * and the public integer constants {@code A}, {@code B}, … {@code RIGHT}) is
 * retained for code that predates the enum API. It always operates on player 0.
 */
public class Controller extends CPUBusComponent {

    /** $4016 — controller 1 / player 1 read+write port. */
    private static final int CONTROLLER_1_ADDRESS = 0x4016;
    /** $4017 — controller 2 read port. (APU frame counter on writes.) */
    private static final int CONTROLLER_2_ADDRESS = 0x4017;

    /** Number of players (always 2 for a standard NES). */
    private static final int NUM_PLAYERS = 2;
    /** Number of buttons per controller (8). */
    private static final int NUM_BUTTONS = 8;

    // ---- public button-bit mask constants (NESdev wiki order, legacy API) ----
    public static final int A      = 0x01;
    public static final int B      = 0x02;
    public static final int SELECT = 0x04;
    public static final int START  = 0x08;
    public static final int UP     = 0x10;
    public static final int DOWN   = 0x20;
    public static final int LEFT   = 0x40;
    public static final int RIGHT  = 0x80;

    /**
     * Live button state for both players. {@code liveState[player][buttonOrdinal]}.
     * Updated by the host any time; latched into the shift register on strobe
     * falling edge.
     */
    private final boolean[][] liveState = new boolean[NUM_PLAYERS][NUM_BUTTONS];

    /**
     * Latched shift-register state for both players. Snapshot taken on the
     * 1→0 falling edge of strobe. Reads consume bits from here.
     */
    private final boolean[][] latchedState = new boolean[NUM_PLAYERS][NUM_BUTTONS];

    /**
     * Read index for each player (0–7 = valid button, &ge;8 = past end →
     * return 1).
     */
    private final int[] readIndex = new int[NUM_PLAYERS];

    /** True while the most recent $4016 write had bit 0 set. */
    private boolean strobe = false;

    // -------------------------------------------------------------------------
    // CPUBusComponent interface
    // -------------------------------------------------------------------------

    @Override
    public void cpuBusWrite(int address, byte value) {
        if (address == CONTROLLER_1_ADDRESS) {
            boolean newStrobe = (value & 0x01) != 0;
            if (newStrobe && !strobe) {
                // Rising edge: while strobe is high the registers are
                // continuously reloaded — nothing to latch yet.
            }
            if (!newStrobe && strobe) {
                // Falling edge 1→0: latch current live state into shift registers
                // and reset read indices for both players.
                for (int p = 0; p < NUM_PLAYERS; p++) {
                    System.arraycopy(liveState[p], 0, latchedState[p], 0, NUM_BUTTONS);
                    readIndex[p] = 0;
                }
            }
            strobe = newStrobe;
        }
        // $4017 writes belong to the APU frame counter — no-op here.
    }

    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        if (address == CONTROLLER_1_ADDRESS) {
            return readPlayer(0, readOnly);
        } else if (address == CONTROLLER_2_ADDRESS) {
            return readPlayer(1, readOnly);
        }
        return 0;
    }

    /**
     * Seam S3 (headless-harness plan, Phase B1): when {@code readOnly} is
     * true, return the bit at the current {@code readIndex} WITHOUT
     * advancing it (and with no other side effects), so harness peeks of
     * $4016/$4017 cannot desync the joypad shift register mid-game.
     */
    private int readPlayer(int player, boolean readOnly) {
        if (strobe) {
            // While strobe is high, the shift register is continuously reloaded
            // with live state — every read returns the A button bit (index 0).
            // The read index does NOT advance.
            return (liveState[player][Button.A.ordinal()] ? 1 : 0) | 0x40;
        }
        if (readIndex[player] > 7) {
            // After all 8 buttons have shifted out, the controller line is
            // pulled high — every subsequent read returns 1 until next strobe.
            return 1 | 0x40;
        }
        int bit = latchedState[player][readIndex[player]] ? 1 : 0;
        if (!readOnly) {
            readIndex[player]++;
        }
        return bit | 0x40;
    }

    // -------------------------------------------------------------------------
    // New enum-based API (primary)
    // -------------------------------------------------------------------------

    /**
     * Set or clear a single button for the given player.
     *
     * @param player  0 for player 1, 1 for player 2
     * @param button  the button to set
     * @param pressed {@code true} to press, {@code false} to release
     */
    public void setButton(int player, Button button, boolean pressed) {
        if (player < 0 || player >= NUM_PLAYERS) {
            throw new IllegalArgumentException("player must be 0 or 1, got: " + player);
        }
        liveState[player][button.ordinal()] = pressed;
    }

    /**
     * Seam S4 (headless-harness plan, Phase D2): read a single button's
     * <em>live</em> state — what the host has set via
     * {@link #setButton(int, Button, boolean)}, not the latched shift
     * register. Side-effect free. The harness {@code InputRecorder} samples
     * this once per frame boundary; recording here (rather than in a host's
     * key-mapping layer) keeps the record path host-agnostic.
     *
     * @param player 0 for player 1, 1 for player 2
     * @param button the button to query
     * @return {@code true} while the button is held
     */
    public boolean isPressed(int player, Button button) {
        if (player < 0 || player >= NUM_PLAYERS) {
            throw new IllegalArgumentException("player must be 0 or 1, got: " + player);
        }
        return liveState[player][button.ordinal()];
    }

    // -------------------------------------------------------------------------
    // Legacy mask-based API (backward compatibility — player 0 only)
    // -------------------------------------------------------------------------

    /**
     * Set or clear one or more buttons in the live state for <em>player 0</em>
     * (player 1). Uses the packed-bitmask constants defined on this class
     * ({@link #A}, {@link #B}, …, {@link #RIGHT}).
     *
     * @param mask    one or more of {@link #A} .. {@link #RIGHT} OR'd together
     * @param pressed {@code true} to press, {@code false} to release
     * @deprecated Prefer {@link #setButton(int, Button, boolean)}.
     */
    @Deprecated
    public void setButton(int mask, boolean pressed) {
        for (Button btn : Button.values()) {
            int bit = 1 << btn.ordinal();
            if ((mask & bit) != 0) {
                liveState[0][btn.ordinal()] = pressed;
            }
        }
    }

    // -------------------------------------------------------------------------
    // CPUBusComponent address range
    // -------------------------------------------------------------------------

    @Override
    public int getCPUBusStartAddress() {
        return CONTROLLER_1_ADDRESS;
    }

    @Override
    public int getCPUBusEndAddress() {
        return CONTROLLER_2_ADDRESS + 1; // exclusive
    }
}
