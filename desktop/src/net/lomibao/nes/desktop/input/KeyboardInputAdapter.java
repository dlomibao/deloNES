package net.lomibao.nes.desktop.input;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.Controller;

/**
 * Polls the keyboard once per frame and forwards button-state changes to the
 * NES {@link Controller}.
 *
 * <h2>Usage</h2>
 * <pre>
 *   KeyboardInputAdapter input = new KeyboardInputAdapter(controller, config, new GdxKeyState());
 *   // inside your render / update loop:
 *   input.poll();
 *   if (input.pausePressed()) { ... }
 * </pre>
 *
 * <h2>Hotkeys</h2>
 * Hotkeys use <em>edge-detection</em>: they report {@code true} only on the
 * single frame the key transitions from released to pressed, preventing
 * repeat-firing while a key is held.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe — call {@link #poll()} from the same thread that drives the
 * LibGDX render loop.
 */
public class KeyboardInputAdapter {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final int NUM_PLAYERS = 2;

    /** NES buttons in the order they appear in {@link Button#values()}. */
    private static final Button[] BUTTONS = Button.values();

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final Controller controller;
    private final ControlsConfig config;
    private final KeyState keyState;

    /** Previous-frame pressed state for each hotkey (for edge-detection). */
    private boolean prevExit;
    private boolean prevReset;
    private boolean prevPause;

    /** Latched edge results for the current frame. */
    private boolean exitEdge;
    private boolean resetEdge;
    private boolean pauseEdge;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * @param controller the shared NES controller component
     * @param config     keyboard mapping (loaded via {@link ControlsConfig#load})
     * @param keyState   key-polling seam ({@link GdxKeyState} in production,
     *                   stub in tests)
     */
    public KeyboardInputAdapter(Controller controller,
                                ControlsConfig config,
                                KeyState keyState) {
        this.controller = controller;
        this.config     = config;
        this.keyState   = keyState;
    }

    // -----------------------------------------------------------------------
    // Per-frame update
    // -----------------------------------------------------------------------

    /**
     * Poll the keyboard and update controller + hotkey state.
     *
     * <p>Must be called exactly once per frame before any hotkey queries.
     */
    public void poll() {
        // ---- Player button state ----
        pollPlayer(0, config.player1);
        pollPlayer(1, config.player2);

        // ---- Hotkeys (edge-detect) ----
        boolean curExit  = keyState.isPressed(config.hotkeyExit);
        boolean curReset = keyState.isPressed(config.hotkeyReset);
        boolean curPause = keyState.isPressed(config.hotkeyPause);

        exitEdge  = curExit  && !prevExit;
        resetEdge = curReset && !prevReset;
        pauseEdge = curPause && !prevPause;

        prevExit  = curExit;
        prevReset = curReset;
        prevPause = curPause;
    }

    // -----------------------------------------------------------------------
    // Hotkey queries
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} only on the frame the "return to menu" key was
     * first pressed (not while held).
     */
    public boolean exitPressed() {
        return exitEdge;
    }

    /**
     * Returns {@code true} only on the frame the "reset" key was first
     * pressed.
     */
    public boolean resetPressed() {
        return resetEdge;
    }

    /**
     * Returns {@code true} only on the frame the "pause" key was first
     * pressed.
     */
    public boolean pausePressed() {
        return pauseEdge;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void pollPlayer(int player, ControlsConfig.PlayerBindings bindings) {
        controller.setButton(player, Button.UP,     keyState.isPressed(bindings.up));
        controller.setButton(player, Button.DOWN,   keyState.isPressed(bindings.down));
        controller.setButton(player, Button.LEFT,   keyState.isPressed(bindings.left));
        controller.setButton(player, Button.RIGHT,  keyState.isPressed(bindings.right));
        controller.setButton(player, Button.A,      keyState.isPressed(bindings.a));
        controller.setButton(player, Button.B,      keyState.isPressed(bindings.b));
        controller.setButton(player, Button.START,  keyState.isPressed(bindings.start));
        controller.setButton(player, Button.SELECT, keyState.isPressed(bindings.select));
    }
}
