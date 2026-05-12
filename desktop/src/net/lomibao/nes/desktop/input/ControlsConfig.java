package net.lomibao.nes.desktop.input;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.SerializationException;

/**
 * Keyboard-to-NES-button mapping configuration.
 *
 * <p>Holds two {@link PlayerBindings} (one per player) and three hotkey
 * keycodes (escape-to-menu, reset, pause).  Serialised as JSON via
 * LibGDX's {@link com.badlogic.gdx.utils.Json}.
 *
 * <h2>Default mappings</h2>
 * <pre>
 * P1: arrows=U/D/L/R, Z=A, X=B, Enter=Start, RShift=Select
 * P2: WASD=U/D/L/R, G=A, H=B, LShift=Start, Tab=Select
 * Hotkeys: Esc=back-to-menu, F5=reset, P=pause
 * </pre>
 */
public class ControlsConfig {

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * One player's button → GDX keycode bindings.
     *
     * <p>Field names are lowercase to get clean JSON keys.  All fields are
     * {@code public} so LibGDX's Json serialiser can read and write them
     * without reflection gymnastics.
     */
    public static class PlayerBindings {
        public int up;
        public int down;
        public int left;
        public int right;
        public int a;
        public int b;
        public int start;
        public int select;

        /** Required by LibGDX Json (zero-arg constructor). */
        public PlayerBindings() {}

        public PlayerBindings(int up, int down, int left, int right,
                              int a, int b, int start, int select) {
            this.up     = up;
            this.down   = down;
            this.left   = left;
            this.right  = right;
            this.a      = a;
            this.b      = b;
            this.start  = start;
            this.select = select;
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    public PlayerBindings player1;
    public PlayerBindings player2;

    /** Hotkey: return to the startup menu. */
    public int hotkeyExit;
    /** Hotkey: soft-reset the emulator. */
    public int hotkeyReset;
    /** Hotkey: toggle pause. */
    public int hotkeyPause;

    /** Required by LibGDX Json. */
    public ControlsConfig() {}

    // -----------------------------------------------------------------------
    // Factory helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a {@code ControlsConfig} populated with the project-specified
     * defaults.
     */
    public static ControlsConfig defaults() {
        ControlsConfig cfg = new ControlsConfig();

        cfg.player1 = new PlayerBindings(
                Input.Keys.UP,          // Up    → Up arrow
                Input.Keys.DOWN,        // Down  → Down arrow
                Input.Keys.LEFT,        // Left  → Left arrow
                Input.Keys.RIGHT,       // Right → Right arrow
                Input.Keys.Z,           // A     → Z
                Input.Keys.X,           // B     → X
                Input.Keys.ENTER,       // Start → Enter
                Input.Keys.SHIFT_RIGHT  // Select→ Right Shift
        );

        cfg.player2 = new PlayerBindings(
                Input.Keys.W,           // Up    → W
                Input.Keys.S,           // Down  → S
                Input.Keys.A,           // Left  → A
                Input.Keys.D,           // Right → D
                Input.Keys.G,           // A     → G
                Input.Keys.H,           // B     → H
                Input.Keys.SHIFT_LEFT,  // Start → Left Shift
                Input.Keys.TAB          // Select→ Tab
        );

        cfg.hotkeyExit  = Input.Keys.ESCAPE;
        cfg.hotkeyReset = Input.Keys.F5;
        cfg.hotkeyPause = Input.Keys.P;

        return cfg;
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /**
     * Loads a {@link ControlsConfig} from {@code path}.
     *
     * <p>If the file does not exist the defaults are written to {@code path}
     * and returned, so callers always get a valid config.
     *
     * <p>If the file exists but is malformed (invalid JSON, schema mismatch,
     * empty, etc.) the bad file is renamed to {@code <name>.bak} so the user
     * can recover their attempt, a loud warning is logged to {@code stderr},
     * and {@link #defaults()} is returned. A user-editable config must NEVER
     * brick the application.
     *
     * @param path FileHandle pointing at {@code controls.json} (or equivalent)
     * @return the loaded or freshly-defaulted config
     */
    public static ControlsConfig load(FileHandle path) {
        if (!path.exists()) {
            ControlsConfig defaults = defaults();
            save(path, defaults);
            return defaults;
        }
        try {
            Json json = new Json();
            ControlsConfig loaded = json.fromJson(ControlsConfig.class, path);
            if (loaded == null) {
                // Empty file → fromJson returns null instead of throwing.
                throw new SerializationException("controls.json is empty");
            }
            return loaded;
        } catch (RuntimeException e) {
            // RuntimeException covers SerializationException (LibGDX's parse
            // error) and any other surprise from Json.fromJson — file
            // disappears mid-read, security-manager interference, etc.
            // Rename the bad file so the user can recover their attempt, then
            // fall back to defaults. Do NOT silently swallow — this needs to
            // be loud enough to find in logs when a user reports "my custom
            // bindings disappeared".
            String backupName = path.name() + ".bak";
            String backupNote;
            try {
                FileHandle backup = path.sibling(backupName);
                if (backup.exists()) {
                    backup.delete();
                }
                path.moveTo(backup);
                backupNote = "renamed to " + backupName;
            } catch (RuntimeException renameError) {
                backupNote = "could NOT be renamed (" + renameError.getMessage() + ")";
            }
            System.err.println(
                    "[ControlsConfig] WARNING: failed to parse " + path.path()
                            + " — " + e.getMessage()
                            + "; bad file " + backupNote
                            + "; falling back to default key bindings.");
            return defaults();
        }
    }

    /**
     * Serialises {@code cfg} to {@code path} as pretty-printed JSON.
     *
     * @param path FileHandle to write; intermediate directories are created
     *             if the handle type supports it (e.g. absolute FileHandle)
     * @param cfg  the config to persist
     */
    public static void save(FileHandle path, ControlsConfig cfg) {
        Json json = new Json();
        json.setOutputType(com.badlogic.gdx.utils.JsonWriter.OutputType.json);
        path.writeString(json.prettyPrint(cfg), false);
    }
}
