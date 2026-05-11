package net.lomibao.nes.desktop.screen;

import com.badlogic.gdx.files.FileHandle;

import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link RomSource} backed by an absolute filesystem path via a LibGDX
 * {@link FileHandle}.
 *
 * <p>NOTE: This class is the Stream C deliverable stub. The merge will replace
 * it with Stream C's canonical version if signatures differ.
 */
public class FileRomSource implements RomSource {

    private final FileHandle file;

    /**
     * @param file a LibGDX {@link FileHandle} pointing to the ROM file; typically obtained
     *             via {@code Gdx.files.absolute(path)}
     */
    public FileRomSource(FileHandle file) {
        this.file = file;
    }

    @Override
    public InputStream open() throws IOException {
        if (!file.exists()) {
            throw new IOException("ROM file not found: " + file.path());
        }
        return file.read();
    }

    @Override
    public String getDisplayName() {
        return file.name();
    }
}
