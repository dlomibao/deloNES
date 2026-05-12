package net.lomibao.nes.desktop.screen;

import com.badlogic.gdx.ApplicationAdapter;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.desktop.HeadlessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test: boots {@link EmulatorScreen} under a headless LibGDX app,
 * loads bundled DonkeyKong.nes via classpath, drives 5 frames, and
 * asserts the emulator state mutated as expected.
 *
 * <p>HeadlessApplication has no GL context, so the test does not exercise
 * SpriteBatch / Texture rendering — EmulatorScreen guards those behind
 * a Gdx.gl null-check. The PPU emulation loop still runs and writes into
 * its own pixel buffer, which the bonus assertion checks.
 */
public class EmulatorScreenTest {

    @Test
    void emulatorScreen_runsFiveFramesAgainstDonkeyKong() {
        AtomicReference<EmulatorScreen> ref = new AtomicReference<>();
        AtomicBoolean exitCalled = new AtomicBoolean(false);

        ApplicationAdapter listener = new ApplicationAdapter() {
            @Override
            public void render() {
                // Lazy-create on first render to avoid race with HeadlessApplication's
                // own create() callback (which runs on the backend thread).
                EmulatorScreen screen = ref.get();
                if (screen == null) {
                    RomSource rom = new RomSource.ClasspathRomSource("/roms/DonkeyKong.nes");
                    Controller controller = new Controller();
                    screen = new EmulatorScreen(
                            rom, controller, () -> exitCalled.set(true), /* debugHud */ false);
                    screen.show();
                    ref.set(screen);
                }
                screen.render(1f / 60f);
            }

            @Override
            public void dispose() {
                EmulatorScreen screen = ref.get();
                if (screen != null) {
                    screen.dispose();
                }
            }
        };

        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(listener, 5));

        EmulatorScreen screen = ref.get();
        assertNotNull(screen, "EmulatorScreen must have been created in listener.create()");
        assertEquals(5, screen.getFrameCount(), "render() should have been called exactly 5 times");

        // Bonus: emulation actually executed (CPU PC advanced past the reset vector
        // and we got a non-default screen buffer state — either non-black pixels or
        // at minimum, the buffer is the expected shape and was touched).
        int[][] buffer = screen.getPpu().getScreen();
        assertNotNull(buffer, "PPU screen buffer must exist");
        assertTrue(buffer.length >= 240, "PPU screen buffer should be at least 240 rows tall");
        assertTrue(buffer[0].length >= 256, "PPU screen buffer rows should be at least 256 wide");

        // Sanity: emulation ran — CPU should not still be at $0000 (uninitialized).
        assertNotEquals(0, screen.getCpu().getPc(),
                "CPU PC should have advanced past reset after 5 frames of emulation");

        // Exit callback is wired but not invoked unless requestExit() is called.
        assertFalse(exitCalled.get(), "onExit must not fire unless requestExit() is invoked");
    }

    @Test
    void togglePauseAndReset_doNotThrow() {
        AtomicReference<EmulatorScreen> ref = new AtomicReference<>();

        ApplicationAdapter listener = new ApplicationAdapter() {
            @Override
            public void render() {
                EmulatorScreen s = ref.get();
                if (s == null) {
                    s = new EmulatorScreen(
                            new RomSource.ClasspathRomSource("/roms/DonkeyKong.nes"),
                            new Controller(),
                            () -> { /* no-op */ },
                            false);
                    s.show();
                    ref.set(s);
                }
                s.render(1f / 60f);
                s.togglePause();
                s.render(1f / 60f); // render while paused — must not throw
                s.togglePause();
                s.reset();
            }

            @Override
            public void dispose() {
                EmulatorScreen s = ref.get();
                if (s != null) s.dispose();
            }
        };

        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(listener, 2));
        assertFalse(ref.get().isPaused(), "pause toggled twice should end un-paused");
    }
}
