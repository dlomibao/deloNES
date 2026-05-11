package net.lomibao.nes.desktop.screen;

import java.io.IOException;
import java.io.InputStream;

/**
 * Abstraction over a ROM source — either a bundled classpath resource or a
 * user-selected file from the filesystem.
 *
 * <p>Implementations: {@link ClasspathRomSource}, {@link FileRomSource}.
 *
 * <p>NOTE: This interface is the Stream C deliverable. This stub exists so
 * Stream D ({@link RomSelectScreen}) compiles without Stream C's branch being
 * merged. The merge will replace this file with Stream C's canonical version.
 */
public interface RomSource {

    /**
     * Opens an {@link InputStream} over the ROM data.
     * The caller is responsible for closing the stream.
     *
     * @return open input stream; never {@code null}
     * @throws IOException if the source cannot be read
     */
    InputStream open() throws IOException;

    /**
     * A human-readable label for the ROM (filename without path).
     *
     * @return display name; never {@code null}
     */
    String getDisplayName();
}
