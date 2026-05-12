package net.lomibao.nes.desktop.input;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ControlsConfig}: default values, round-trip JSON
 * serialisation, and auto-write-on-missing behaviour.
 *
 * <p>Uses LibGDX's {@link com.badlogic.gdx.utils.Json} and
 * {@link FileHandle} directly (no LibGDX application context needed for
 * those classes).
 */
class ControlsConfigTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static FileHandle tempHandle(Path dir, String name) {
        return new FileHandle(new File(dir.toFile(), name));
    }

    // -----------------------------------------------------------------------
    // Default values
    // -----------------------------------------------------------------------

    @Test
    void defaults_player1_arrowKeys() {
        ControlsConfig cfg = ControlsConfig.defaults();
        assertEquals(Input.Keys.UP,    cfg.player1.up);
        assertEquals(Input.Keys.DOWN,  cfg.player1.down);
        assertEquals(Input.Keys.LEFT,  cfg.player1.left);
        assertEquals(Input.Keys.RIGHT, cfg.player1.right);
    }

    @Test
    void defaults_player1_actionButtons() {
        ControlsConfig cfg = ControlsConfig.defaults();
        assertEquals(Input.Keys.Z,           cfg.player1.a);
        assertEquals(Input.Keys.X,           cfg.player1.b);
        assertEquals(Input.Keys.ENTER,       cfg.player1.start);
        assertEquals(Input.Keys.SHIFT_RIGHT, cfg.player1.select);
    }

    @Test
    void defaults_player2_wasd() {
        ControlsConfig cfg = ControlsConfig.defaults();
        assertEquals(Input.Keys.W, cfg.player2.up);
        assertEquals(Input.Keys.S, cfg.player2.down);
        assertEquals(Input.Keys.A, cfg.player2.left);
        assertEquals(Input.Keys.D, cfg.player2.right);
    }

    @Test
    void defaults_player2_actionButtons() {
        ControlsConfig cfg = ControlsConfig.defaults();
        assertEquals(Input.Keys.G,          cfg.player2.a);
        assertEquals(Input.Keys.H,          cfg.player2.b);
        assertEquals(Input.Keys.SHIFT_LEFT, cfg.player2.start);
        assertEquals(Input.Keys.TAB,        cfg.player2.select);
    }

    @Test
    void defaults_hotkeys() {
        ControlsConfig cfg = ControlsConfig.defaults();
        assertEquals(Input.Keys.ESCAPE, cfg.hotkeyExit);
        assertEquals(Input.Keys.F5,     cfg.hotkeyReset);
        assertEquals(Input.Keys.P,      cfg.hotkeyPause);
    }

    // -----------------------------------------------------------------------
    // Auto-write when missing
    // -----------------------------------------------------------------------

    @Test
    void load_writesDefaultsWhenFileMissing(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        assertFalse(fh.exists(), "precondition: file must not exist");

        ControlsConfig loaded = ControlsConfig.load(fh);

        assertTrue(fh.exists(), "load() must create the file when it is missing");

        // Check a few representative values
        assertEquals(Input.Keys.Z,      loaded.player1.a);
        assertEquals(Input.Keys.ESCAPE, loaded.hotkeyExit);
    }

    // -----------------------------------------------------------------------
    // Round-trip serialisation
    // -----------------------------------------------------------------------

    @Test
    void roundTrip_preservesAllPlayer1Bindings(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        ControlsConfig original = ControlsConfig.defaults();

        ControlsConfig.save(fh, original);
        ControlsConfig loaded = ControlsConfig.load(fh);

        assertNotNull(loaded.player1, "player1 must not be null after round-trip");
        assertEquals(original.player1.up,     loaded.player1.up,     "P1 up");
        assertEquals(original.player1.down,   loaded.player1.down,   "P1 down");
        assertEquals(original.player1.left,   loaded.player1.left,   "P1 left");
        assertEquals(original.player1.right,  loaded.player1.right,  "P1 right");
        assertEquals(original.player1.a,      loaded.player1.a,      "P1 A");
        assertEquals(original.player1.b,      loaded.player1.b,      "P1 B");
        assertEquals(original.player1.start,  loaded.player1.start,  "P1 start");
        assertEquals(original.player1.select, loaded.player1.select, "P1 select");
    }

    @Test
    void roundTrip_preservesAllPlayer2Bindings(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        ControlsConfig original = ControlsConfig.defaults();

        ControlsConfig.save(fh, original);
        ControlsConfig loaded = ControlsConfig.load(fh);

        assertNotNull(loaded.player2, "player2 must not be null after round-trip");
        assertEquals(original.player2.up,     loaded.player2.up,     "P2 up");
        assertEquals(original.player2.down,   loaded.player2.down,   "P2 down");
        assertEquals(original.player2.left,   loaded.player2.left,   "P2 left");
        assertEquals(original.player2.right,  loaded.player2.right,  "P2 right");
        assertEquals(original.player2.a,      loaded.player2.a,      "P2 A");
        assertEquals(original.player2.b,      loaded.player2.b,      "P2 B");
        assertEquals(original.player2.start,  loaded.player2.start,  "P2 start");
        assertEquals(original.player2.select, loaded.player2.select, "P2 select");
    }

    @Test
    void roundTrip_preservesHotkeys(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        ControlsConfig original = ControlsConfig.defaults();

        ControlsConfig.save(fh, original);
        ControlsConfig loaded = ControlsConfig.load(fh);

        assertEquals(original.hotkeyExit,  loaded.hotkeyExit,  "hotkeyExit");
        assertEquals(original.hotkeyReset, loaded.hotkeyReset, "hotkeyReset");
        assertEquals(original.hotkeyPause, loaded.hotkeyPause, "hotkeyPause");
    }

    @Test
    void roundTrip_customValues(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "custom.json");
        ControlsConfig cfg = ControlsConfig.defaults();
        // Override a few values
        cfg.player1.a      = Input.Keys.NUM_1;
        cfg.player2.start  = Input.Keys.NUM_2;
        cfg.hotkeyPause    = Input.Keys.SPACE;

        ControlsConfig.save(fh, cfg);
        ControlsConfig loaded = ControlsConfig.load(fh);

        assertEquals(Input.Keys.NUM_1,  loaded.player1.a);
        assertEquals(Input.Keys.NUM_2,  loaded.player2.start);
        assertEquals(Input.Keys.SPACE,  loaded.hotkeyPause);
    }

    // -----------------------------------------------------------------------
    // Malformed input must not brick the app (B4)
    // -----------------------------------------------------------------------

    /**
     * Garbage in the config file must not throw — the user's typo should
     * never crash the emulator at startup. Defaults should be returned and
     * the bad file renamed out of the way.
     */
    @Test
    void load_malformedJson_returnsDefaultsAndDoesNotThrow(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        fh.writeString("{ this is not valid json @@@ ", false);

        ControlsConfig loaded = assertDoesNotThrow(() -> ControlsConfig.load(fh));

        // Returned config must be usable defaults.
        assertNotNull(loaded);
        assertNotNull(loaded.player1, "player1 must be populated");
        assertNotNull(loaded.player2, "player2 must be populated");
        assertEquals(Input.Keys.Z,      loaded.player1.a);
        assertEquals(Input.Keys.ESCAPE, loaded.hotkeyExit);
    }

    /**
     * The malformed file should be preserved as {@code <name>.bak} so the
     * user can recover their attempted edits rather than having them silently
     * deleted.
     */
    @Test
    void load_malformedJson_renamesBadFileToBak(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        String badContent = "{ this is not valid json @@@ ";
        fh.writeString(badContent, false);

        ControlsConfig.load(fh);

        FileHandle bak = tempHandle(dir, "controls.json.bak");
        assertTrue(bak.exists(), "bad file must be preserved as controls.json.bak");
        assertEquals(badContent, bak.readString(),
                "backup must contain the original malformed content");
    }

    /**
     * An empty file is technically valid JSON-ish (LibGDX returns null) but
     * useless; treat it as malformed and fall back to defaults.
     */
    @Test
    void load_emptyFile_returnsDefaults(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        fh.writeString("", false);
        assertTrue(fh.exists());
        assertEquals(0L, fh.length(), "precondition: file must be empty");

        ControlsConfig loaded = assertDoesNotThrow(() -> ControlsConfig.load(fh));

        assertNotNull(loaded.player1);
        assertEquals(Input.Keys.UP,    loaded.player1.up);
        assertEquals(Input.Keys.ENTER, loaded.player1.start);
    }

    /**
     * Regression guard: valid JSON that was previously parsing fine must
     * still parse fine after wrapping the parse in a try/catch.
     */
    @Test
    void load_validJson_stillParsesCorrectly(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        ControlsConfig original = ControlsConfig.defaults();
        original.player1.a   = Input.Keys.J;
        original.hotkeyPause = Input.Keys.SPACE;
        ControlsConfig.save(fh, original);

        ControlsConfig loaded = ControlsConfig.load(fh);

        assertEquals(Input.Keys.J,     loaded.player1.a);
        assertEquals(Input.Keys.SPACE, loaded.hotkeyPause);
        // Untouched values still match defaults.
        assertEquals(Input.Keys.UP,    loaded.player1.up);
        assertEquals(Input.Keys.W,     loaded.player2.up);
    }

    /**
     * After a malformed-file recovery the {@code .bak} file is left in place
     * but the primary file is gone. The next call to {@code load()} should
     * therefore write a fresh defaults file (since the primary no longer
     * exists), not throw.
     */
    @Test
    void load_afterRecovery_secondCallWritesFreshDefaults(@TempDir Path dir) {
        FileHandle fh = tempHandle(dir, "controls.json");
        fh.writeString("not json", false);

        ControlsConfig.load(fh);                 // first call: rename to .bak
        assertFalse(fh.exists(), "primary file should have been renamed away");

        ControlsConfig loaded = ControlsConfig.load(fh);   // second call: fresh defaults written

        assertTrue(fh.exists(), "second load() must rewrite a defaults file");
        assertEquals(Input.Keys.Z, loaded.player1.a);
    }
}
