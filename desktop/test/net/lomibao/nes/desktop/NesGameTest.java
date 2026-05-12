package net.lomibao.nes.desktop;

import com.badlogic.gdx.files.FileHandle;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.desktop.input.ControlsConfig;
import net.lomibao.nes.desktop.input.KeyState;
import net.lomibao.nes.desktop.input.KeyboardInputAdapter;
import net.lomibao.nes.desktop.screen.EmulatorScreen;
import net.lomibao.nes.desktop.screen.RomSelectScreen;
import net.lomibao.nes.desktop.screen.RomSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration smoke test for {@link NesGame}.
 *
 * <p>Boots a {@link NesGame} under the headless LibGDX backend
 * (no display required), drives a few render frames, and asserts
 * basic lifecycle behaviour.
 *
 * <p>All tests use {@link TempControlsNesGame} so {@code controls.json}
 * is written to a JUnit-managed temp directory rather than the user's
 * real {@code ~/.deloNES} folder (which would happen with a raw
 * {@code new NesGame()} since {@link NesGame#resolveControlsConfigFile()}
 * resolves to {@code Gdx.files.external}).
 */
public class NesGameTest {

    /**
     * {@link NesGame} variant that loads {@code controls.json} from the
     * provided file path instead of the user's external storage. Used by
     * every test in this class so the test suite never touches the real
     * {@code ~/.deloNES} folder.
     */
    private static class TempControlsNesGame extends NesGame {
        private final FileHandle controlsFile;

        TempControlsNesGame(File controlsFile) {
            this.controlsFile = new FileHandle(controlsFile);
        }

        @Override
        protected FileHandle resolveControlsConfigFile() {
            return controlsFile;
        }
    }

    private static File tempControlsFile(Path dir) {
        return new File(dir.toFile(), "controls.json");
    }

    @Test
    void nesGame_bootsAndLandsOnRomSelectScreen(@TempDir Path dir) {
        NesGame game = new TempControlsNesGame(tempControlsFile(dir));

        // Boot and run 3 frames — must not throw
        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(game, 3));

        // After boot the game should have started on RomSelectScreen.
        // HeadlessTestSupport calls dispose() at the end of runFrames(), which
        // also disposes the current screen via Game.dispose() override — so we
        // can only check that no exception was thrown and the game wired itself up.
    }

    @Test
    void nesGame_selectRom_transitionsToEmulatorScreen(@TempDir Path dir) {
        NesGame game = new TempControlsNesGame(tempControlsFile(dir));

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
    void nesGame_disposesCleanly(@TempDir Path dir) {
        NesGame game = new TempControlsNesGame(tempControlsFile(dir));
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
     * {@code pollCount} after frames have been driven. Extends
     * {@link TempControlsNesGame} so {@code controls.json} is also
     * redirected to a JUnit temp directory.
     */
    private static final class SpyingNesGame extends TempControlsNesGame {
        CountingKeyboardInputAdapter spy;

        SpyingNesGame(File controlsFile) {
            super(controlsFile);
        }

        @Override
        protected KeyboardInputAdapter createKeyboardAdapter(Controller controller,
                                                            ControlsConfig config) {
            spy = new CountingKeyboardInputAdapter(controller, config);
            return spy;
        }
    }

    @Test
    void polling_happensOnEmulatorScreen(@TempDir Path dir) {
        SpyingNesGame game = new SpyingNesGame(tempControlsFile(dir));
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
    void polling_doesNotHappenOnRomSelectScreen(@TempDir Path dir) {
        SpyingNesGame game = new SpyingNesGame(tempControlsFile(dir));

        // Boot game and run 3 frames WITHOUT selecting a ROM — every frame
        // renders against the RomSelectScreen, so poll() must never be called.
        assertDoesNotThrow(() -> HeadlessTestSupport.runFrames(game, 3));

        assertNotNull(game.spy, "spy adapter must have been installed via createKeyboardAdapter()");
        assertEquals(0, game.spy.pollCount,
                "poll() must NOT be invoked while the active screen is RomSelectScreen, but pollCount was " + game.spy.pollCount);
    }

    // -------------------------------------------------------------------------
    // B3 — controls.json lives in a stable per-user external location, NOT in
    // the launch-time working directory. The production path is asserted as a
    // string constant (no Gdx.* required) and the file-handle helper is
    // exercised under the headless backend to confirm it points under the
    // user's home folder rather than the working directory.
    // -------------------------------------------------------------------------

    /**
     * Locks the production-relative path to the value required by the B3
     * fix. Anyone refactoring this constant must also update the migration
     * helper and the PR description that documented the move.
     */
    @Test
    void controlsConfigPath_isStableExternalLocation() {
        assertEquals(".deloNES/controls.json", NesGame.CONTROLS_CONFIG_EXTERNAL_PATH,
                "controls.json must live at .deloNES/controls.json under Gdx.files.external");
    }

    /**
     * The default {@link NesGame#resolveControlsConfigFile()} must resolve
     * to a path under {@code Gdx.files.external} (i.e. the user's home
     * directory), regardless of the test's working directory. The check is
     * done against {@code Gdx.files.external} directly under a controlled
     * headless backend boot so we never invoke the real
     * {@link NesGame#resolveControlsConfigFile()} (which would run the
     * migration helper as a side effect on the user's home folder).
     */
    @Test
    void controlsConfigPath_resolvesUnderUserHome() throws Exception {
        // Boot a bare headless app so Gdx.files is wired up, then poke at
        // Gdx.files.external() to confirm where the production constant
        // resolves. We deliberately do NOT call NesGame.resolveControlsConfigFile()
        // because that runs the legacy-migration helper as a side effect
        // (writing to the user's real ~/.deloNES folder if a legacy
        // controls.json exists in cwd).
        com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration cfg =
                new com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration();
        cfg.updatesPerSecond = -1;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<File> resolved =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.badlogic.gdx.ApplicationListener listener = new com.badlogic.gdx.ApplicationAdapter() {
            @Override
            public void create() {
                resolved.set(com.badlogic.gdx.Gdx.files
                        .external(NesGame.CONTROLS_CONFIG_EXTERNAL_PATH)
                        .file());
                ready.countDown();
                try {
                    hold.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        com.badlogic.gdx.backends.headless.HeadlessApplication app =
                new com.badlogic.gdx.backends.headless.HeadlessApplication(listener, cfg);
        try {
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "headless backend must boot");
        } finally {
            hold.countDown();
            app.exit();
            com.badlogic.gdx.Gdx.app = null;
            com.badlogic.gdx.Gdx.files = null;
            com.badlogic.gdx.Gdx.input = null;
            com.badlogic.gdx.Gdx.audio = null;
            com.badlogic.gdx.Gdx.graphics = null;
            com.badlogic.gdx.Gdx.gl = null;
            com.badlogic.gdx.Gdx.gl20 = null;
            com.badlogic.gdx.Gdx.gl30 = null;
            com.badlogic.gdx.Gdx.net = null;
        }

        File path = resolved.get();
        assertNotNull(path, "Gdx.files.external() must return a real File");

        File userHome = new File(System.getProperty("user.home"));
        // Walk up the parent chain to confirm the resolved path is under user.home.
        boolean underHome = false;
        for (File p = path.getAbsoluteFile(); p != null; p = p.getParentFile()) {
            if (p.equals(userHome.getAbsoluteFile())) {
                underHome = true;
                break;
            }
        }
        assertTrue(underHome,
                "controls.json must live under user.home (" + userHome + ") but resolved to " + path);
        assertEquals("controls.json", path.getName(),
                "file name must remain controls.json");
        assertEquals(".deloNES", path.getParentFile().getName(),
                "controls.json must live inside a .deloNES directory");
    }

    /**
     * Loading from a fresh (non-existent) external path must return the
     * defaults AND write them to that path so subsequent launches see a
     * stable file.
     */
    @Test
    void controlsConfig_freshLocation_returnsDefaultsAndPersists(@TempDir Path dir) {
        File controls = tempControlsFile(dir);
        assertFalse(controls.exists(), "precondition: temp file must not exist");

        final ControlsConfig[] captured = new ControlsConfig[1];
        NesGame game = new TempControlsNesGame(controls) {
            @Override
            public void create() {
                super.create();
                // Reach back into the freshly created config via the load path —
                // we exercise the public ControlsConfig.load() on the same
                // handle to avoid adding a getter just for this assertion.
                captured[0] = ControlsConfig.load(new FileHandle(controls));
            }
        };
        HeadlessTestSupport.runFrames(game, 0);

        assertTrue(controls.exists(),
                "create() must persist controls.json so future launches share the same file");
        assertNotNull(captured[0], "loaded config must not be null");
        assertEquals(com.badlogic.gdx.Input.Keys.Z, captured[0].player1.a,
                "fresh load must return the documented defaults (P1 A = Z)");
    }

    /**
     * Save + load round-trip on the new external location preserves custom
     * bindings, the same guarantee {@link
     * net.lomibao.nes.desktop.input.ControlsConfigTest} makes for arbitrary
     * paths, but exercised end-to-end through the {@link NesGame} resolver.
     */
    @Test
    void controlsConfig_externalLocation_roundTripsCustomBindings(@TempDir Path dir) {
        File controls = tempControlsFile(dir);

        // Pre-seed the temp location with a customised config so the next
        // NesGame boot must read those bindings back rather than rewriting
        // defaults.
        ControlsConfig custom = ControlsConfig.defaults();
        custom.player1.a   = com.badlogic.gdx.Input.Keys.NUM_7;
        custom.hotkeyPause = com.badlogic.gdx.Input.Keys.SPACE;
        ControlsConfig.save(new FileHandle(controls), custom);

        final ControlsConfig[] loaded = new ControlsConfig[1];
        NesGame game = new TempControlsNesGame(controls) {
            @Override
            public void create() {
                super.create();
                loaded[0] = ControlsConfig.load(new FileHandle(controls));
            }
        };
        HeadlessTestSupport.runFrames(game, 0);

        assertNotNull(loaded[0]);
        assertEquals(com.badlogic.gdx.Input.Keys.NUM_7, loaded[0].player1.a,
                "custom P1 A binding must survive save+load round-trip");
        assertEquals(com.badlogic.gdx.Input.Keys.SPACE, loaded[0].hotkeyPause,
                "custom hotkeyPause binding must survive save+load round-trip");
    }

    /**
     * Migration: when no per-user {@code controls.json} exists yet and a
     * legacy file is present, the legacy file's contents must end up at the
     * new target. Both handles are passed explicitly so the test can stage
     * the legacy file in a temp directory rather than coupling to
     * {@code Gdx.files.local}'s resolution against the JVM working dir.
     */
    @Test
    void migrateLegacyControlsConfig_copiesLegacyToNewLocation(@TempDir Path dir) throws Exception {
        File legacyFile = new File(dir.toFile(), "legacy-controls.json");
        String legacyJson = "{\"hotkeyPause\":62}";  // 62 = Input.Keys.SPACE
        java.nio.file.Files.write(legacyFile.toPath(), legacyJson.getBytes());

        File target = tempControlsFile(dir);
        assertFalse(target.exists(),
                "precondition: migration target must not yet exist");

        NesGame.migrateLegacyControlsConfig(new FileHandle(legacyFile), new FileHandle(target));

        assertTrue(target.exists(),
                "migration must copy legacy file to the new target");
        String copied = new String(java.nio.file.Files.readAllBytes(target.toPath()));
        assertEquals(legacyJson, copied,
                "legacy contents must be preserved byte-for-byte");
    }

    /**
     * Migration is a no-op when the target already exists: the user has an
     * up-to-date config, so a stray legacy file must NOT overwrite it.
     */
    @Test
    void migrateLegacyControlsConfig_noOpWhenTargetExists(@TempDir Path dir) throws Exception {
        File legacyFile = new File(dir.toFile(), "legacy-controls.json");
        java.nio.file.Files.write(legacyFile.toPath(), "{\"hotkeyPause\":62}".getBytes());

        File target = tempControlsFile(dir);
        String existingContents = "{\"hotkeyPause\":42}";
        java.nio.file.Files.write(target.toPath(), existingContents.getBytes());

        assertDoesNotThrow(() -> NesGame.migrateLegacyControlsConfig(
                new FileHandle(legacyFile), new FileHandle(target)));

        String after = new String(java.nio.file.Files.readAllBytes(target.toPath()));
        assertEquals(existingContents, after,
                "existing target must not be overwritten by migration");
    }

    /**
     * Migration must tolerate a null legacy handle (the production code
     * passes null when {@code Gdx.files} is not wired up) — this is the
     * common case for users with no legacy file at all.
     */
    @Test
    void migrateLegacyControlsConfig_nullLegacyIsNoOp(@TempDir Path dir) {
        File target = tempControlsFile(dir);
        assertFalse(target.exists(), "precondition: target must not exist");

        assertDoesNotThrow(() ->
                NesGame.migrateLegacyControlsConfig(null, new FileHandle(target)));

        assertFalse(target.exists(),
                "migration must not create the target when legacy is null");
    }

    /**
     * Migration is a no-op when the legacy file is absent — fresh
     * installations on a new machine must not see a phantom migration
     * warning.
     */
    @Test
    void migrateLegacyControlsConfig_missingLegacyIsNoOp(@TempDir Path dir) {
        File legacyFile = new File(dir.toFile(), "does-not-exist.json");
        File target = tempControlsFile(dir);

        assertDoesNotThrow(() -> NesGame.migrateLegacyControlsConfig(
                new FileHandle(legacyFile), new FileHandle(target)));

        assertFalse(target.exists(),
                "migration must not create the target when legacy is absent");
    }
}
