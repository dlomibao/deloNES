package net.lomibao.nes.desktop;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
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
 * from {@code controls.json} in the working directory, and a single
 * {@link KeyboardInputAdapter}. Transitions between {@link RomSelectScreen}
 * and {@link EmulatorScreen} via callbacks.
 *
 * <p>Keyboard polling via {@link KeyboardInputAdapter#poll()} is performed
 * only when the current screen is an {@link EmulatorScreen}, so P1 keys do
 * not interfere with menu navigation on {@link RomSelectScreen}.
 */
public class NesGame extends Game {

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
        controlsConfig = ControlsConfig.load(Gdx.files.local("controls.json"));
        keyboardAdapter = new KeyboardInputAdapter(controller, controlsConfig, new GdxKeyState());

        returnToMenu();
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
