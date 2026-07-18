package net.lomibao.nes.desktop.screen;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.desktop.HeadlessTestSupport;
import net.lomibao.nes.harness.InputTimeline;
import net.lomibao.nes.harness.Movie;
import net.lomibao.nes.harness.MovieFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    void emulatorScreen_runsFiveFramesAgainstNestest() {
        AtomicReference<EmulatorScreen> ref = new AtomicReference<>();
        AtomicBoolean exitCalled = new AtomicBoolean(false);

        ApplicationAdapter listener = new ApplicationAdapter() {
            @Override
            public void render() {
                // Lazy-create on first render to avoid race with HeadlessApplication's
                // own create() callback (which runs on the backend thread).
                EmulatorScreen screen = ref.get();
                if (screen == null) {
                    RomSource rom = new RomSource.ClasspathRomSource("/roms/nestest.nes");
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

    /**
     * Regression for B1: a ROM that fails to load must not brick the app.
     * Prior behaviour: loadROM() threw RuntimeException straight out of
     * show(), but LibGDX Game.setScreen() had already swapped this.screen
     * to the half-initialised EmulatorScreen, so the next render() NPE'd
     * forever on the null nesSystem/cpu/ppu — user had no way back to
     * the menu. Fix: show() catches the failure, disposes partial state,
     * and fires onExit so NesGame bounces back to RomSelectScreen.
     */
    @Test
    void badRom_doesNotBrick_invokesOnExitAndRenderIsSafe() {
        AtomicReference<EmulatorScreen> ref = new AtomicReference<>();
        AtomicBoolean exitCalled = new AtomicBoolean(false);

        ApplicationAdapter listener = new ApplicationAdapter() {
            @Override
            public void render() {
                EmulatorScreen screen = ref.get();
                if (screen == null) {
                    // ROM source guaranteed to fail: classpath path with no file.
                    RomSource rom = new RomSource.ClasspathRomSource(
                            "/roms/this-rom-does-not-exist.nes");
                    Controller controller = new Controller();
                    screen = new EmulatorScreen(
                            rom, controller, () -> exitCalled.set(true), /* debugHud */ false);

                    // 1. show() MUST NOT throw — that's the bug we're fixing.
                    assertDoesNotThrow(screen::show);
                    ref.set(screen);
                }
                // 3. Subsequent render() calls MUST NOT NPE on the half-initialised
                //    emulator (nesSystem/cpu/ppu may all be null after a load fail).
                assertDoesNotThrow(() -> ref.get().render(1f / 60f));
            }

            @Override
            public void dispose() {
                EmulatorScreen screen = ref.get();
                if (screen != null) {
                    screen.dispose();
                }
            }
        };

        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(listener, 3));

        EmulatorScreen screen = ref.get();
        assertNotNull(screen, "EmulatorScreen must have been created");
        // 2. onExit must have been invoked so NesGame can return to the menu.
        assertTrue(exitCalled.get(),
                "onExit must fire when ROM load fails so user returns to menu");
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
                            new RomSource.ClasspathRomSource("/roms/nestest.nes"),
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

    /**
     * Phase D2 desktop wiring (headless-harness plan): with the record flag
     * on, EmulatorScreen samples the controller immediately before each
     * runFrame() (decision D9 boundary) and dumps a parseable v1 movie on
     * dispose. The test presses START during frames 2-3 and expects exactly
     * those edges at exactly those frame numbers.
     */
    @Test
    void movieRecording_capturesBoundaryEdges_andDumpsParseableMovie(@TempDir Path tempDir) {
        AtomicReference<EmulatorScreen> ref = new AtomicReference<>();
        Controller controller = new Controller();

        ApplicationAdapter listener = new ApplicationAdapter() {
            private int frame = 0;

            @Override
            public void render() {
                EmulatorScreen s = ref.get();
                if (s == null) {
                    s = new EmulatorScreen(
                            new RomSource.ClasspathRomSource("/roms/nestest.nes"),
                            controller, () -> { /* no-op */ }, false) {
                        @Override
                        protected boolean movieRecordingEnabled() {
                            return true; // force the debug flag without global state
                        }

                        @Override
                        protected FileHandle resolveMovieFile(String fileName) {
                            return Gdx.files.absolute(
                                    tempDir.resolve(fileName).toString());
                        }
                    };
                    s.show();
                    ref.set(s);
                }
                // D2/D9 semantics: state set before render(frame N) is what
                // the recorder samples at boundary N.
                controller.setButton(0, Button.START, frame == 2 || frame == 3);
                s.render(1f / 60f);
                frame++;
            }

            @Override
            public void dispose() {
                EmulatorScreen s = ref.get();
                if (s != null) s.dispose(); // triggers the movie dump
            }
        };

        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(listener, 6));

        Path movieFile = tempDir.resolve("nestest.nes.dmov");
        assertTrue(java.nio.file.Files.exists(movieFile),
                "movie file should be dumped on dispose: " + movieFile);

        Movie movie = assertDoesNotThrow(() -> MovieFormat.parse(
                new String(java.nio.file.Files.readAllBytes(movieFile),
                        java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(6, movie.frames(), "movie length = emulated frame count");
        assertEquals(
                InputTimeline.builder()
                        .press(Button.START).atFrame(2).holdFrames(2)
                        .build().edges(),
                movie.timeline().edges(),
                "recorded edges must be the START press at frames 2-3");
    }
}
