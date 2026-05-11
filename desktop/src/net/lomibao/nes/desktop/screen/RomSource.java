package net.lomibao.nes.desktop.screen;

import com.badlogic.gdx.files.FileHandle;

import java.io.IOException;
import java.io.InputStream;

/**
 * Abstraction over where a ROM byte stream comes from. Implementations
 * provide a fresh {@link InputStream} on each call to {@link #open()} and
 * a human-readable display name for the title bar.
 *
 * <p>Java 8 source compatibility — uses an abstract class because sealed
 * types and {@code permits} are not available until Java 17.
 */
public abstract class RomSource {

    /**
     * Opens a fresh input stream for the ROM contents. Caller is
     * responsible for closing.
     */
    public abstract InputStream open() throws IOException;

    /**
     * Human-readable name of the ROM, suitable for window titles
     * (e.g. {@code "DonkeyKong.nes"}).
     */
    public abstract String displayName();

    /**
     * Reads a ROM from the JVM classpath.
     */
    public static final class ClasspathRomSource extends RomSource {
        private final String resourcePath;

        public ClasspathRomSource(String resourcePath) {
            if (resourcePath == null) {
                throw new IllegalArgumentException("resourcePath must not be null");
            }
            this.resourcePath = resourcePath;
        }

        @Override
        public InputStream open() throws IOException {
            InputStream in = getClass().getResourceAsStream(resourcePath);
            if (in == null) {
                throw new IOException("ROM resource not found on classpath: " + resourcePath);
            }
            return in;
        }

        @Override
        public String displayName() {
            int slash = resourcePath.lastIndexOf('/');
            return slash >= 0 ? resourcePath.substring(slash + 1) : resourcePath;
        }

        public String getResourcePath() {
            return resourcePath;
        }
    }

    /**
     * Reads a ROM from a LibGDX {@link FileHandle} (local disk, internal,
     * or external — whatever the handle resolves to).
     */
    public static final class FileRomSource extends RomSource {
        private final FileHandle file;

        public FileRomSource(FileHandle file) {
            if (file == null) {
                throw new IllegalArgumentException("file must not be null");
            }
            this.file = file;
        }

        @Override
        public InputStream open() throws IOException {
            return file.read();
        }

        @Override
        public String displayName() {
            return file.name();
        }

        public FileHandle getFile() {
            return file;
        }
    }
}
