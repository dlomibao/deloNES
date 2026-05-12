package net.lomibao.nes.desktop;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.files.FileHandle;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.desktop.input.ControlsConfig;
import net.lomibao.nes.desktop.input.GdxKeyState;
import net.lomibao.nes.desktop.input.KeyboardInputAdapter;
import net.lomibao.nes.desktop.screen.EmulatorScreen;
import net.lomibao.nes.desktop.screen.RomSelectScreen;
import net.lomibao.nes.desktop.screen.RomSource;

/**
 * Top-level LibGDX {@link Game} for the deloNES startup-menu feature.
 *
 * <p>Owns a single {@link Controller}, a single {@link ControlsConfig} loaded
 * from a stable per-user {@code controls.json} (see
 * {@link #CONTROLS_CONFIG_EXTERNAL_PATH}), and a single
 * {@link KeyboardInputAdapter}. Transitions between {@link RomSelectScreen}
 * and {@link EmulatorScreen} via callbacks.
 *
 * <p>Keyboard polling via {@link KeyboardInputAdapter#poll()} is performed
 * only when the current screen is an {@link EmulatorScreen}, so P1 keys do
 * not interfere with menu navigation on {@link RomSelectScreen}.
 */
public class NesGame extends Game {

    /**
     * Path (relative to the LibGDX external storage root) where the
     * per-user controls configuration is stored. Resolves to
     * {@code ~/.deloNES/controls.json} on Linux/macOS and
     * {@code %USERPROFILE%\.deloNES\controls.json} on Windows so the file
     * lives in the same place regardless of how the game is launched
     * (Gradle run, IDE, or fat-jar). Previously this was a
     * {@code local("controls.json")} that resolved against the current
     * working directory and ended up in three different places.
     */
    static final String CONTROLS_CONFIG_EXTERNAL_PATH = ".deloNES/controls.json";

    /**
     * Legacy path (relative to the LibGDX local storage root) where prior
     * builds wrote the controls configuration. Used for one-time migration
     * on first load — see {@link #resolveControlsConfigFile()}.
     */
    static final String LEGACY_CONTROLS_CONFIG_LOCAL_PATH = "controls.json";

    // Shared across all screens — never recreated.
    private Controller controller;
    private ControlsConfig controlsConfig;
    private KeyboardInputAdapter keyboardAdapter;

    // Currently active emulator screen — null when on the select screen.
    private EmulatorScreen emulatorScreen;

    // -------------------------------------------------------------------------
    // LibGDX Game lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void create() {
        controller = new Controller();
        FileHandle controlsFile = resolveControlsConfigFile();
        ensureParentExists(controlsFile);
        controlsConfig = ControlsConfig.load(controlsFile);
        keyboardAdapter = createKeyboardAdapter(controller, controlsConfig);

        returnToMenu();
    }

    /**
     * Test seam: tests subclass {@link NesGame} and override this method
     * to inject a spy/counting {@link KeyboardInputAdapter}. Production
     * code calls this exactly once from {@link #create()}.
     *
     * @param controller the shared NES controller component
     * @param config     the loaded controls configuration
     * @return a fresh {@link KeyboardInputAdapter} bound to the given
     *         controller and config
     */
    protected KeyboardInputAdapter createKeyboardAdapter(Controller controller,
                                                        ControlsConfig config) {
        return new KeyboardInputAdapter(controller, config, new GdxKeyState());
    }

    /**
     * Returns the {@link FileHandle} pointing at the per-user
     * {@code controls.json}. Production resolves to
     * {@link #CONTROLS_CONFIG_EXTERNAL_PATH} under LibGDX external storage
     * (i.e. the user's home directory), so the file lives in a stable
     * location regardless of how the game is launched.
     *
     * <p>If a legacy {@link #LEGACY_CONTROLS_CONFIG_LOCAL_PATH} file exists
     * in the working directory and no per-user file has been written yet,
     * the legacy file is copied to the new location so users keep their
     * custom bindings on first run after upgrading. The legacy file is
     * left in place — if the upgrade is rolled back the old file is still
     * usable, and {@code ControlsConfig.load()} on the new location will
     * not overwrite it.
     *
     * <p>Tests override this method to inject a temp-directory
     * {@link FileHandle} so they do not pollute the user's home folder.
     *
     * @return file handle for the controls configuration
     */
    protected FileHandle resolveControlsConfigFile() {
        FileHandle target = Gdx.files.external(CONTROLS_CONFIG_EXTERNAL_PATH);
        FileHandle legacy;
        try {
            legacy = Gdx.files.local(LEGACY_CONTROLS_CONFIG_LOCAL_PATH);
        } catch (RuntimeException e) {
            // Gdx.files is not set up (e.g. exotic test path). Nothing to migrate.
            legacy = null;
        }
        migrateLegacyControlsConfig(legacy, target);
        return target;
    }

    /**
     * One-time migration of a legacy {@code controls.json} into the new
     * per-user external location. If {@code target} already exists, or
     * {@code legacy} is null / does not exist, this is a no-op.
     *
     * <p>Package-private (and parameterised on the legacy handle) so tests
     * can exercise it without staging a real file in the JVM working
     * directory.
     *
     * @param legacy the legacy controls.json (may be null or non-existent)
     * @param target external-storage file handle to migrate into
     */
    static void migrateLegacyControlsConfig(FileHandle legacy, FileHandle target) {
        if (target.exists()) {
            return;
        }
        if (legacy == null || !legacy.exists()) {
            return;
        }
        try {
            ensureParentExists(target);
            legacy.copyTo(target);
            System.err.println(
                    "[NesGame] migrated legacy " + legacy.path()
                            + " -> " + target.file().getAbsolutePath());
        } catch (RuntimeException e) {
            // Migration is best-effort. ControlsConfig.load() will write
            // fresh defaults to the new location if migration failed.
            System.err.println(
                    "[NesGame] WARNING: failed to migrate legacy controls.json: "
                            + e.getMessage());
        }
    }

    /**
     * Ensures the parent directory of {@code file} exists so a subsequent
     * write does not fail with {@code FileNotFoundException}. LibGDX's
     * {@link FileHandle#writeString} does not auto-create intermediate
     * directories for external/absolute handles.
     */
    private static void ensureParentExists(FileHandle file) {
        FileHandle parent = file.parent();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    @Override
    public void render() {
        if (emulatorScreen != null) {
            // Poll keyboard BEFORE rendering so the controller state is current.
            keyboardAdapter.poll();

            // Drive the base Game render (calls screen.render(delta) internally).
            super.render();

            // Check hotkeys AFTER render.
            if (keyboardAdapter.pausePressed()) {
                emulatorScreen.togglePause();
            }
            if (keyboardAdapter.resetPressed()) {
                emulatorScreen.reset();
            }
            if (keyboardAdapter.exitPressed()) {
                emulatorScreen.requestExit();
            }
        } else {
            // On the select screen — do NOT poll the keyboard adapter.
            super.render();
        }
    }

    @Override
    public void dispose() {
        // Game.dispose() calls screen.hide() but does NOT call screen.dispose().
        // We must dispose the current screen ourselves.
        if (getScreen() != null) {
            getScreen().dispose();
        }
    }

    // -------------------------------------------------------------------------
    // Screen transitions
    // -------------------------------------------------------------------------

    /**
     * Transitions to the ROM selection screen. Disposes any active
     * {@link EmulatorScreen} first.
     */
    private void returnToMenu() {
        if (emulatorScreen != null) {
            EmulatorScreen old = emulatorScreen;
            emulatorScreen = null;
            old.dispose();
        }
        setScreen(new RomSelectScreen(this::onRomSelected, this::onQuit));
    }

    /**
     * Called by {@link RomSelectScreen} when the user confirms a ROM.
     * Transitions to a new {@link EmulatorScreen}.
     *
     * @param rom the ROM source chosen by the user
     */
    private void onRomSelected(RomSource rom) {
        Screen previous = getScreen();
        emulatorScreen = new EmulatorScreen(rom, controller, this::returnToMenu, false);
        setScreen(emulatorScreen);
        // Game.setScreen() only calls hide() on the prior screen — never
        // dispose() — so the menu's SpriteBatch / BitmapFont would leak on
        // every menu->emulator transition. Symmetric with returnToMenu().
        if (previous != null && previous != emulatorScreen) {
            previous.dispose();
        }
    }

    /**
     * Called by {@link RomSelectScreen} when the user presses Escape.
     * Exits the application.
     */
    private void onQuit() {
        Gdx.app.exit();
    }

    // -------------------------------------------------------------------------
    // Test-hook: allows integration tests to drive ROM selection without
    // keyboard events.
    // -------------------------------------------------------------------------

    /**
     * Directly triggers a ROM selection as if the user had chosen the given
     * ROM on the select screen. Intended for integration tests that cannot
     * pump keyboard events through the headless backend.
     *
     * @param rom the ROM to load
     */
    public void selectRom(RomSource rom) {
        onRomSelected(rom);
    }
}
