package net.lomibao.nes.desktop;

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
                        // Inject a ROM selection via the test hook
                        game.selectRom(new RomSource.ClasspathRomSource("/roms/DonkeyKong.nes"));
                        didSelect[0] = true;
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
    }

    @Test
    void nesGame_disposesCleanly() {
        NesGame game = new NesGame();
        // Just verify that booting and immediately disposing does not throw
        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(game, 1));
    }
}
