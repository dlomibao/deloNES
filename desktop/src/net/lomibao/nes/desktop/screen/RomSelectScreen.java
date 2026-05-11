package net.lomibao.nes.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import net.lomibao.nes.util.RomCatalog;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ROM selection screen for the deloNES emulator.
 *
 * <p>Renders a keyboard-navigable list of bundled ROMs (from {@link RomCatalog})
 * plus a "Browse filesystem…" entry at the bottom.
 *
 * <p>Navigation:
 * <ul>
 *   <li>{@code UP} / {@code DOWN} — move selection</li>
 *   <li>{@code ENTER} — confirm selection</li>
 *   <li>{@code ESC} — invoke the {@code onQuit} runnable</li>
 * </ul>
 *
 * <p>On selecting a bundled ROM the header is validated via
 * {@link iNESHeaderValidator}. If validation fails an inline error message is
 * displayed for approximately 3 seconds. If it passes,
 * {@code onSelect.accept(new ClasspathRomSource("/roms/" + name))} is called.
 *
 * <p>On selecting "Browse filesystem…" a native file dialog is opened via
 * LWJGL3 {@code TinyFileDialogs}. The selected path is validated and, on
 * success, {@code onSelect.accept(new FileRomSource(Gdx.files.absolute(path)))}
 * is called.
 */
public class RomSelectScreen implements Screen {

    private static final String TITLE = "deloNES — Select ROM";
    private static final String BROWSE_ENTRY = "Browse filesystem…";

    /** Duration in seconds to display inline validation errors. */
    private static final float ERROR_DISPLAY_SECONDS = 3f;

    private static final float LINE_HEIGHT = 24f;
    private static final float LEFT_MARGIN = 60f;
    private static final float TOP_MARGIN_BELOW_TITLE = 60f;
    private static final float TITLE_Y_FROM_TOP = 40f;

    private final Consumer<RomSource> onSelect;
    private final Runnable onQuit;

    private SpriteBatch batch;
    private BitmapFont font;

    /** All selectable entries: bundled ROMs + the browse entry at the end. */
    private final List<String> entries = new ArrayList<String>();

    /** Index of the highlighted entry. */
    private int selectedIndex = 0;

    /** Non-null while an error message should be shown. */
    private String errorMessage = null;

    /** Counts down the error display timer. */
    private float errorTimer = 0f;

    /**
     * Constructs the ROM selection screen.
     *
     * @param onSelect callback invoked with the chosen {@link RomSource} when the
     *                 user confirms a valid selection
     * @param onQuit   runnable invoked when the user presses Escape
     */
    public RomSelectScreen(Consumer<RomSource> onSelect, Runnable onQuit) {
        this.onSelect = onSelect;
        this.onQuit = onQuit;
    }

    @Override
    public void show() {
        // Non-GL initialization only — GL resources are created lazily in render()
        // so that headless test environments (no GL context) can call show() safely.
        entries.clear();
        try {
            entries.addAll(RomCatalog.listRoms());
        } catch (RomCatalog.RomCatalogException e) {
            // No bundled ROMs available — log and continue; browse still works
            System.err.println("[RomSelectScreen] Could not load ROM catalog: " + e.getMessage());
        }
        entries.add(BROWSE_ENTRY);

        selectedIndex = 0;
    }

    /**
     * Returns {@code true} when a real GL context is available.
     *
     * <p>The headless backend sets {@code Gdx.graphics.getType()} to
     * {@link com.badlogic.gdx.Graphics.GraphicsType#Mock}. In that case
     * we skip GL resource creation and rendering, allowing the screen to
     * be exercised in unit tests without a display.
     */
    private boolean hasGL() {
        return Gdx.graphics != null
                && Gdx.graphics.getType() != com.badlogic.gdx.Graphics.GraphicsType.Mock;
    }

    /** Lazily initialises GL resources. Only called when {@link #hasGL()} is true. */
    private void ensureGLResources() {
        if (batch == null) {
            batch = new SpriteBatch();
        }
        if (font == null) {
            font = new BitmapFont();
            font.setColor(Color.WHITE);
        }
    }

    @Override
    public void render(float delta) {
        // Update error display timer
        if (errorMessage != null) {
            errorTimer -= delta;
            if (errorTimer <= 0f) {
                errorMessage = null;
            }
        }

        // Handle keyboard input
        handleInput();

        // Skip rendering in headless environments (tests, CI without a display)
        if (!hasGL()) {
            return;
        }

        ensureGLResources();

        // Draw
        Gdx.gl.glClearColor(0.05f, 0.05f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        int screenHeight = Gdx.graphics.getHeight();

        batch.begin();

        // Title
        font.setColor(Color.CYAN);
        font.draw(batch, TITLE, LEFT_MARGIN, screenHeight - TITLE_Y_FROM_TOP);

        // Entry list
        float listTop = screenHeight - TITLE_Y_FROM_TOP - TOP_MARGIN_BELOW_TITLE;
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            float y = listTop - i * LINE_HEIGHT;

            if (i == selectedIndex) {
                font.setColor(Color.YELLOW);
                font.draw(batch, "> " + entry, LEFT_MARGIN, y);
            } else {
                boolean isBrowse = entry.equals(BROWSE_ENTRY);
                font.setColor(isBrowse ? Color.LIGHT_GRAY : Color.WHITE);
                font.draw(batch, "  " + entry, LEFT_MARGIN, y);
            }
        }

        // Inline error message
        if (errorMessage != null) {
            float errorY = listTop - entries.size() * LINE_HEIGHT - LINE_HEIGHT;
            font.setColor(Color.RED);
            font.draw(batch, "Error: " + errorMessage, LEFT_MARGIN, errorY);
        }

        // Navigation hint
        font.setColor(Color.DARK_GRAY);
        font.draw(batch, "UP/DOWN: navigate   ENTER: select   ESC: quit", LEFT_MARGIN, 20f);

        batch.end();
    }

    private void handleInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            selectedIndex = (selectedIndex - 1 + entries.size()) % entries.size();
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            selectedIndex = (selectedIndex + 1) % entries.size();
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            handleSelection();
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            onQuit.run();
        }
    }

    private void handleSelection() {
        String entry = entries.get(selectedIndex);

        if (BROWSE_ENTRY.equals(entry)) {
            browseForRom();
        } else {
            selectBundledRom(entry);
        }
    }

    private void selectBundledRom(String romName) {
        // Read first 16 bytes for header validation
        byte[] header = readFirstBytes("/roms/" + romName, 16);
        if (header == null) {
            showError("Could not read ROM: " + romName);
            return;
        }

        iNESHeaderValidator.ValidationResult result = iNESHeaderValidator.validate(header);
        if (!result.isValid()) {
            showError(result.getErrorMessage());
            return;
        }

        onSelect.accept(new RomSource.ClasspathRomSource("/roms/" + romName));
    }

    private void browseForRom() {
        // Open native file dialog (blocks until user confirms or cancels).
        // TinyFileDialogs requires a PointerBuffer of UTF-8 ByteBuffers for the filter list.
        String path;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer filterBuf = stack.UTF8("*.nes");
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(filterBuf).flip();
            path = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select NES ROM",
                    "",
                    filters,
                    "NES ROM files (*.nes)",
                    false);
        }

        if (path == null || path.isEmpty()) {
            // User cancelled
            return;
        }

        // Validate header of the chosen file
        byte[] header = readFirstBytesFromFile(path, 16);
        if (header == null) {
            showError("Could not read file: " + path);
            return;
        }

        iNESHeaderValidator.ValidationResult result = iNESHeaderValidator.validate(header);
        if (!result.isValid()) {
            showError(result.getErrorMessage());
            return;
        }

        onSelect.accept(new RomSource.FileRomSource(Gdx.files.absolute(path)));
    }

    /**
     * Reads the first {@code count} bytes from a classpath resource.
     *
     * @param resourcePath absolute classpath path, e.g. {@code "/roms/nestest.nes"}
     * @param count        number of bytes to read
     * @return byte array of length up to {@code count}, or {@code null} on failure
     */
    private static byte[] readFirstBytes(String resourcePath, int count) {
        InputStream in = RomSelectScreen.class.getResourceAsStream(resourcePath);
        if (in == null) {
            return null;
        }
        try {
            byte[] buf = new byte[count];
            int read = 0;
            while (read < count) {
                int n = in.read(buf, read, count - read);
                if (n < 0) break;
                read += n;
            }
            if (read < count) {
                // File shorter than expected — return what we got
                byte[] trimmed = new byte[read];
                System.arraycopy(buf, 0, trimmed, 0, read);
                return trimmed;
            }
            return buf;
        } catch (IOException e) {
            return null;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads the first {@code count} bytes from a filesystem file.
     *
     * @param path  absolute filesystem path
     * @param count number of bytes to read
     * @return byte array of length up to {@code count}, or {@code null} on failure
     */
    private static byte[] readFirstBytesFromFile(String path, int count) {
        try {
            com.badlogic.gdx.files.FileHandle fh = Gdx.files.absolute(path);
            if (!fh.exists()) return null;
            InputStream in = fh.read();
            try {
                byte[] buf = new byte[count];
                int read = 0;
                while (read < count) {
                    int n = in.read(buf, read, count - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read < count) {
                    byte[] trimmed = new byte[read];
                    System.arraycopy(buf, 0, trimmed, 0, read);
                    return trimmed;
                }
                return buf;
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void showError(String message) {
        errorMessage = message;
        errorTimer = ERROR_DISPLAY_SECONDS;
        System.err.println("[RomSelectScreen] " + message);
    }

    @Override
    public void resize(int width, int height) {
        // No camera — nothing to resize
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void hide() {}

    @Override
    public void dispose() {
        if (batch != null) {
            batch.dispose();
            batch = null;
        }
        if (font != null) {
            font.dispose();
            font = null;
        }
    }
}
