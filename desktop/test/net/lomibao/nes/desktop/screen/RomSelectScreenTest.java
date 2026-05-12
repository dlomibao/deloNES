package net.lomibao.nes.desktop.screen;

import com.badlogic.gdx.ApplicationAdapter;
import net.lomibao.nes.desktop.HeadlessTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for {@link RomSelectScreen} using the headless LibGDX backend.
 *
 * <p>Key constraints of the headless environment:
 * <ul>
 *   <li>No GL context — drawing calls via SpriteBatch/BitmapFont are no-ops
 *       but must not throw.</li>
 *   <li>No window / keyboard device — {@code Gdx.input.isKeyJustPressed(...)}
 *       always returns {@code false}, so keyboard navigation cannot be simulated
 *       here; it is verified manually.</li>
 *   <li>No native libraries — the TinyFileDialogs code path is never executed
 *       by this test (it only runs when the user presses Enter on the browse
 *       entry, which requires keyboard input).</li>
 * </ul>
 */
class RomSelectScreenTest {

    /**
     * Instantiates {@link RomSelectScreen}, advances 3 render frames, and
     * disposes it — all inside a headless LibGDX application. No exception
     * must be thrown.
     */
    @Test
    void smokeTest_instantiateAndRender3Frames_noException() {
        // Capture whether callbacks were accidentally fired during headless run
        boolean[] selectFired = {false};
        boolean[] quitFired = {false};

        HeadlessTestSupport.runFrames(new ApplicationAdapter() {
            private RomSelectScreen screen;

            @Override
            public void create() {
                screen = new RomSelectScreen(
                        romSource -> selectFired[0] = true,
                        () -> quitFired[0] = true
                );
                screen.show();
            }

            @Override
            public void render() {
                // Pass a plausible delta of 1/60 second
                screen.render(1f / 60f);
            }

            @Override
            public void dispose() {
                screen.dispose();
            }
        }, 3);

        assertFalse(selectFired[0], "onSelect should not fire during headless render");
        assertFalse(quitFired[0], "onQuit should not fire during headless render");
    }

    /**
     * Verifies that disposing {@link RomSelectScreen} before {@code show()} is
     * called does not throw (defensive null checks in dispose()).
     */
    @Test
    void dispose_beforeShow_noException() {
        HeadlessTestSupport.runFrames(new ApplicationAdapter() {
            private RomSelectScreen screen;

            @Override
            public void create() {
                screen = new RomSelectScreen(romSource -> {}, () -> {});
                // Deliberately skip show() — simulates early teardown
            }

            @Override
            public void render() {
                // nothing
            }

            @Override
            public void dispose() {
                // Should not throw even though show() was never called
                screen.dispose();
            }
        }, 0);
    }
}
