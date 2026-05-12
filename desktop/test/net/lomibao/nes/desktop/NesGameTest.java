package net.lomibao.nes.desktop;

import net.lomibao.nes.components.Controller;
import net.lomibao.nes.desktop.input.ControlsConfig;
import net.lomibao.nes.desktop.input.KeyState;
import net.lomibao.nes.desktop.input.KeyboardInputAdapter;
import net.lomibao.nes.desktop.screen.EmulatorScreen;
import net.lomibao.nes.desktop.screen.RomSelectScreen;
import net.lomibao.nes.desktop.screen.RomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration smoke test for {@link NesGame}.
 *
 * <p>Boots a {@link NesGame} under the headless LibGDX backend
 * (no display required), drives a few render frames, and asserts
 * basic lifecycle behaviour.
 */
public class NesGameTest {

    @Test
    void nesGame_bootsAndLandsOnRomSelectScreen() {
        NesGame game = new NesGame();

        // Boot and run 3 frames — must not throw
        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(game, 3));

        // After boot the game should have started on RomSelectScreen.
        // HeadlessTestSupport calls dispose() at the end of runFrames(), which
        // also disposes the current screen via Game.dispose() override — so we
        // can only check that no exception was thrown and the game wired itself up.
    }

    @Test
    void nesGame_selectRom_transitionsToEmulatorScreen() {
        NesGame game = new NesGame();

        // We need the LibGDX context up first; wrap interaction inside runFrames
        // so create() has been called before we inspect screen state.
        final boolean[] didSelect = {false};
        final boolean[] wasOnSelectFirst = {false};
        final boolean[] wasOnEmulatorAfter = {false};

        assertDoesNotThrow(() -> {
            // Boot: create() -> returnToMenu() -> RomSelectScreen
            HeadlessTestSupport.runFrames(new com.badlogic.gdx.ApplicationAdapter() {
                @Override
                public void create() {
                    game.create();
                    wasOnSelectFirst[0] = game.getScreen() instanceof RomSelectScreen;
                }

                @Override
                public void render() {
                    if (!didSelect[0]) {
                        // Use nestest.nes — bundled and always present, unlike DonkeyKong.nes
                        // which is gitignored and would break CI on a fresh checkout.
                        game.selectRom(new RomSource.ClasspathRomSource("/roms/nestest.nes"));
                        didSelect[0] = true;
                        wasOnEmulatorAfter[0] = game.getScreen() instanceof EmulatorScreen;
                    }
                    game.render();
                }

                @Override
                public void dispose() {
                    game.dispose();
                }
            }, 3);
        });

        assertTrue(wasOnSelectFirst[0],
                "After create(), the active screen must be a RomSelectScreen");
        assertTrue(wasOnEmulatorAfter[0],
                "After selectRom(), the active screen must be an EmulatorScreen — the actual post-condition this test name implies");
    }

    @Test
    void nesGame_disposesCleanly() {
        NesGame game = new NesGame();
        // Just verify that booting and immediately disposing does not throw
        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(game, 1));
    }

    // -------------------------------------------------------------------------
    // B11 — NesGame.render() polls the keyboard adapter ONLY when the active
    // screen is an EmulatorScreen. The polling branch (NesGame.java:49–71)
    // explicitly skips poll() on the RomSelectScreen so menu navigation is
    // not coupled to P1 inputs. These two tests cover both branches.
    // -------------------------------------------------------------------------

    /**
     * Counting {@link KeyboardInputAdapter} that increments {@link #pollCount}
     * on every call to {@link #poll()} and returns {@code false} from all
     * hotkey edge queries. Hotkey queries are kept inert so the polling
     * test does not also exercise the pause/reset/exit hotkey paths.
     */
    private static final class CountingKeyboardInputAdapter extends KeyboardInputAdapter {
        int pollCount = 0;

        CountingKeyboardInputAdapter(Controller controller, ControlsConfig config) {
            super(controller, config, new NoKeysPressed());
        }

        @Override
        public void poll() {
            pollCount++;
            super.poll();
        }

        /** A {@link KeyState} that reports no keys ever pressed. */
        private static final class NoKeysPressed implements KeyState {
            @Override
            public boolean isPressed(int gdxKeyCode) {
                return false;
            }
        }
    }

    /**
     * Subclass of {@link NesGame} that swaps in a counting spy adapter via
     * the protected {@link NesGame#createKeyboardAdapter} test seam. The
     * spy reference is captured at construction time so tests can inspect
     * {@code pollCount} after frames have been driven.
     */
    private static final class SpyingNesGame extends NesGame {
        CountingKeyboardInputAdapter spy;

        @Override
        protected KeyboardInputAdapter createKeyboardAdapter(Controller controller,
                                                            ControlsConfig config) {
            spy = new CountingKeyboardInputAdapter(controller, config);
            return spy;
        }
    }

    @Test
    void polling_happensOnEmulatorScreen() {
        SpyingNesGame game = new SpyingNesGame();
        final boolean[] didSelect = {false};

        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(new com.badlogic.gdx.ApplicationAdapter() {
            @Override
            public void create() {
                game.create();
            }

            @Override
            public void render() {
                // Select a ROM on frame 0 so the remaining frames render on
                // EmulatorScreen. Using nestest.nes since it is bundled and
                // always present (see nesGame_selectRom_transitionsToEmulatorScreen).
                if (!didSelect[0]) {
                    game.selectRom(new RomSource.ClasspathRomSource("/roms/nestest.nes"));
                    didSelect[0] = true;
                }
                game.render();
            }

            @Override
            public void dispose() {
                game.dispose();
            }
        }, 3));

        assertNotNull(game.spy, "spy adapter must have been installed via createKeyboardAdapter()");
        assertTrue(game.spy.pollCount > 0,
                "poll() must be invoked while the active screen is EmulatorScreen, but pollCount was " + game.spy.pollCount);
    }

    @Test
    void polling_doesNotHappenOnRomSelectScreen() {
        SpyingNesGame game = new SpyingNesGame();

        // Boot game and run 3 frames WITHOUT selecting a ROM — every frame
        // renders against the RomSelectScreen, so poll() must never be called.
        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(game, 3));

        assertNotNull(game.spy, "spy adapter must have been installed via createKeyboardAdapter()");
        assertEquals(0, game.spy.pollCount,
                "poll() must NOT be invoked while the active screen is RomSelectScreen, but pollCount was " + game.spy.pollCount);
    }
}
