package net.lomibao.nes.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import net.lomibao.nes.components.Controller;

/**
 * LibGDX keyboard → standard NES controller bridge (Step 8 of the
 * playable-gen1 plan).
 *
 * <p>Standard SMB-friendly mapping:
 * <pre>
 *   Arrow keys  → D-pad (Up/Down/Left/Right)
 *   Z / Y       → A
 *   X           → B
 *   Enter       → Start
 *   R-Shift     → Select
 * </pre>
 *
 * <p>Use is one of two patterns:
 * <ol>
 *   <li>Per-frame poll: call {@link #pollAll(Controller)} from the host's
 *       frame-rendered listener. Cheap; reflects the current keyboard
 *       state every frame.</li>
 *   <li>Event-driven: register an {@link com.badlogic.gdx.InputAdapter}
 *       and call {@link #applyKey(Controller, int, boolean)} from
 *       {@code keyDown}/{@code keyUp}.</li>
 * </ol>
 */
public final class KeyboardInput {

    private KeyboardInput() {}

    /**
     * Translate a LibGDX key code into the NES button bit-mask, or {@code 0}
     * if no mapping exists.
     */
    public static int keyToButtonMask(int keyCode) {
        switch (keyCode) {
            case Keys.UP:    return Controller.UP;
            case Keys.DOWN:  return Controller.DOWN;
            case Keys.LEFT:  return Controller.LEFT;
            case Keys.RIGHT: return Controller.RIGHT;
            case Keys.Z:
            case Keys.Y:     return Controller.A;
            case Keys.X:     return Controller.B;
            case Keys.ENTER: return Controller.START;
            case Keys.SHIFT_RIGHT: return Controller.SELECT;
            default: return 0;
        }
    }

    /** Apply a single key event (down/up) to the controller. */
    public static void applyKey(Controller controller, int keyCode, boolean pressed) {
        int mask = keyToButtonMask(keyCode);
        if (mask != 0) {
            controller.setButton(mask, pressed);
        }
    }

    /**
     * Poll every mapped key from {@link Gdx#input} and update the
     * controller's live state. Call once per frame from the
     * frame-rendered listener.
     */
    public static void pollAll(Controller controller) {
        controller.setButton(Controller.UP,     Gdx.input.isKeyPressed(Keys.UP));
        controller.setButton(Controller.DOWN,   Gdx.input.isKeyPressed(Keys.DOWN));
        controller.setButton(Controller.LEFT,   Gdx.input.isKeyPressed(Keys.LEFT));
        controller.setButton(Controller.RIGHT,  Gdx.input.isKeyPressed(Keys.RIGHT));
        controller.setButton(Controller.A,      Gdx.input.isKeyPressed(Keys.Z) || Gdx.input.isKeyPressed(Keys.Y));
        controller.setButton(Controller.B,      Gdx.input.isKeyPressed(Keys.X));
        controller.setButton(Controller.START,  Gdx.input.isKeyPressed(Keys.ENTER));
        controller.setButton(Controller.SELECT, Gdx.input.isKeyPressed(Keys.SHIFT_RIGHT));
    }
}
