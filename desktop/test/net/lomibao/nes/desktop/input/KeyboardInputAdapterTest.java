package net.lomibao.nes.desktop.input;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.Controller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KeyboardInputAdapter}.
 *
 * <p><strong>Compile dependency on Stream A:</strong> these tests reference
 * {@code net.lomibao.nes.components.Button} and the two-player form of
 * {@code Controller.setButton(int player, Button button, boolean pressed)}.
 * Until Stream A's branch merges, this file will not compile.  The logic is
 * otherwise complete and correct against the spec.
 *
 * <h2>Test doubles</h2>
 * <ul>
 *   <li>{@code FakeKeyState} — a {@code Map<Integer,Boolean>} stub.</li>
 *   <li>{@code SpyController} — subclass of {@link Controller} that records
 *       every {@code setButton} call for assertion.</li>
 * </ul>
 */
class KeyboardInputAdapterTest {

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    /** Stub KeyState backed by a plain HashMap. */
    static class FakeKeyState implements KeyState {
        private final Map<Integer, Boolean> keys = new HashMap<>();

        void press(int keyCode)   { keys.put(keyCode, true);  }
        void release(int keyCode) { keys.put(keyCode, false); }

        @Override
        public boolean isPressed(int gdxKeyCode) {
            Boolean v = keys.get(gdxKeyCode);
            return v != null && v;
        }
    }

    /** Recorded invocation of setButton(player, button, pressed). */
    static class SetButtonCall {
        final int player;
        final Button button;
        final boolean pressed;

        SetButtonCall(int player, Button button, boolean pressed) {
            this.player  = player;
            this.button  = button;
            this.pressed = pressed;
        }

        @Override
        public String toString() {
            return "setButton(" + player + ", " + button + ", " + pressed + ")";
        }
    }

    /**
     * Spy subclass — intercepts the two-player {@code setButton} API added by
     * Stream A and records the calls.
     *
     * <p>NOTE: this depends on Stream A adding
     * {@code public void setButton(int player, Button button, boolean pressed)}
     * to {@link Controller} as a non-final, overridable method.
     */
    static class SpyController extends Controller {
        final List<SetButtonCall> calls = new ArrayList<>();

        @Override
        public void setButton(int player, Button button, boolean pressed) {
            calls.add(new SetButtonCall(player, button, pressed));
            // Also forward to super so the live button state stays consistent
            // (optional — tests only query calls, not bus state).
            super.setButton(player, button, pressed);
        }

        /** Returns true if any recorded call matches the given arguments. */
        boolean wasCalledWith(int player, Button button, boolean pressed) {
            for (SetButtonCall c : calls) {
                if (c.player == player && c.button == button && c.pressed == pressed) {
                    return true;
                }
            }
            return false;
        }

        void clearCalls() {
            calls.clear();
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private FakeKeyState    keyState;
    private SpyController   controller;
    private ControlsConfig  config;
    private KeyboardInputAdapter adapter;

    @BeforeEach
    void setUp() {
        keyState   = new FakeKeyState();
        controller = new SpyController();
        config     = ControlsConfig.defaults();
        adapter    = new KeyboardInputAdapter(controller, config, keyState);
    }

    // -----------------------------------------------------------------------
    // P1 button → setButton mapping
    // -----------------------------------------------------------------------

    @Test
    void p1_pressZ_callsSetButtonATrue() {
        keyState.press(config.player1.a);  // Z key
        adapter.poll();
        assertTrue(controller.wasCalledWith(0, Button.A, true),
                "pressing P1's A key (Z) must call setButton(0, A, true)");
    }

    @Test
    void p1_releaseZ_callsSetButtonAFalse() {
        keyState.press(config.player1.a);
        adapter.poll();
        controller.clearCalls();

        keyState.release(config.player1.a);
        adapter.poll();
        assertTrue(controller.wasCalledWith(0, Button.A, false),
                "releasing P1's A key (Z) must call setButton(0, A, false)");
    }

    @Test
    void p1_allEightButtonsMapped() {
        // Press every P1 key simultaneously
        keyState.press(config.player1.up);
        keyState.press(config.player1.down);
        keyState.press(config.player1.left);
        keyState.press(config.player1.right);
        keyState.press(config.player1.a);
        keyState.press(config.player1.b);
        keyState.press(config.player1.start);
        keyState.press(config.player1.select);

        adapter.poll();

        assertTrue(controller.wasCalledWith(0, Button.UP,     true), "P1 UP");
        assertTrue(controller.wasCalledWith(0, Button.DOWN,   true), "P1 DOWN");
        assertTrue(controller.wasCalledWith(0, Button.LEFT,   true), "P1 LEFT");
        assertTrue(controller.wasCalledWith(0, Button.RIGHT,  true), "P1 RIGHT");
        assertTrue(controller.wasCalledWith(0, Button.A,      true), "P1 A");
        assertTrue(controller.wasCalledWith(0, Button.B,      true), "P1 B");
        assertTrue(controller.wasCalledWith(0, Button.START,  true), "P1 START");
        assertTrue(controller.wasCalledWith(0, Button.SELECT, true), "P1 SELECT");
    }

    // -----------------------------------------------------------------------
    // P2 button → setButton mapping
    // -----------------------------------------------------------------------

    @Test
    void p2_pressG_callsSetButtonATrue() {
        keyState.press(config.player2.a);  // G key
        adapter.poll();
        assertTrue(controller.wasCalledWith(1, Button.A, true),
                "pressing P2's A key (G) must call setButton(1, A, true)");
    }

    @Test
    void p2_releaseG_callsSetButtonAFalse() {
        keyState.press(config.player2.a);
        adapter.poll();
        controller.clearCalls();

        keyState.release(config.player2.a);
        adapter.poll();
        assertTrue(controller.wasCalledWith(1, Button.A, false),
                "releasing P2's A key (G) must call setButton(1, A, false)");
    }

    @Test
    void p2_allEightButtonsMapped() {
        keyState.press(config.player2.up);
        keyState.press(config.player2.down);
        keyState.press(config.player2.left);
        keyState.press(config.player2.right);
        keyState.press(config.player2.a);
        keyState.press(config.player2.b);
        keyState.press(config.player2.start);
        keyState.press(config.player2.select);

        adapter.poll();

        assertTrue(controller.wasCalledWith(1, Button.UP,     true), "P2 UP");
        assertTrue(controller.wasCalledWith(1, Button.DOWN,   true), "P2 DOWN");
        assertTrue(controller.wasCalledWith(1, Button.LEFT,   true), "P2 LEFT");
        assertTrue(controller.wasCalledWith(1, Button.RIGHT,  true), "P2 RIGHT");
        assertTrue(controller.wasCalledWith(1, Button.A,      true), "P2 A");
        assertTrue(controller.wasCalledWith(1, Button.B,      true), "P2 B");
        assertTrue(controller.wasCalledWith(1, Button.START,  true), "P2 START");
        assertTrue(controller.wasCalledWith(1, Button.SELECT, true), "P2 SELECT");
    }

    // -----------------------------------------------------------------------
    // Hotkey edge-detection
    // -----------------------------------------------------------------------

    @Test
    void pause_onlyTrueOnFirstFrame() {
        keyState.press(config.hotkeyPause);

        adapter.poll();
        assertTrue(adapter.pausePressed(), "pause must be true on first frame the key is pressed");

        // Key still held — should NOT fire again
        adapter.poll();
        assertFalse(adapter.pausePressed(), "pause must be false while key remains held");
    }

    @Test
    void pause_retriggers_afterReleaseAndRepress() {
        keyState.press(config.hotkeyPause);
        adapter.poll();
        assertTrue(adapter.pausePressed());

        keyState.release(config.hotkeyPause);
        adapter.poll();
        assertFalse(adapter.pausePressed(), "pause must be false after key released");

        keyState.press(config.hotkeyPause);
        adapter.poll();
        assertTrue(adapter.pausePressed(), "pause must fire again on re-press");
    }

    @Test
    void reset_edgeDetect() {
        keyState.press(config.hotkeyReset);
        adapter.poll();
        assertTrue(adapter.resetPressed(), "reset fires on first frame");

        adapter.poll();
        assertFalse(adapter.resetPressed(), "reset does not repeat while held");
    }

    @Test
    void exit_edgeDetect() {
        keyState.press(config.hotkeyExit);
        adapter.poll();
        assertTrue(adapter.exitPressed(), "exit fires on first frame");

        adapter.poll();
        assertFalse(adapter.exitPressed(), "exit does not repeat while held");
    }

    @Test
    void noHotkeyPressed_allEdgesAreFalse() {
        adapter.poll();
        assertFalse(adapter.pausePressed());
        assertFalse(adapter.resetPressed());
        assertFalse(adapter.exitPressed());
    }
}
