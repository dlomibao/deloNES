package net.lomibao.nes.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides a list of bundled ROM filenames available on the classpath.
 *
 * <p>ROM discovery is driven by {@code roms/index.txt} — a plain-text manifest
 * (one filename per line, blank lines and {@code #} comment lines ignored)
 * committed alongside the ROM files in {@code core/src/main/resources/roms/}.
 * This approach works reliably across IDE, Gradle, and fat-JAR runs because
 * {@link ClassLoader#getResources} directory enumeration is not guaranteed for
 * JARs and varies by JVM implementation.
 *
 * <p>Use {@link #openRom(String)} to obtain an {@link InputStream} for a ROM
 * by its filename (e.g. {@code "nestest.nes"}).
 */
public class RomCatalog {

    private static final String INDEX_RESOURCE = "roms/index.txt";

    private RomCatalog() {}

    /**
     * Returns an unmodifiable list of ROM filenames declared in
     * {@code roms/index.txt}.  Names are returned in manifest order,
     * stripped of leading/trailing whitespace.
     *
     * @return list of ROM filenames (never {@code null}, may be empty)
     * @throws RomCatalogException if the index resource cannot be found or read
     */
    public static List<String> listRoms() {
        InputStream in = RomCatalog.class.getClassLoader().getResourceAsStream(INDEX_RESOURCE);
        if (in == null) {
            throw new RomCatalogException("ROM index not found on classpath: " + INDEX_RESOURCE);
        }
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    names.add(line);
                }
            }
        } catch (IOException e) {
            throw new RomCatalogException("Failed to read ROM index: " + e.getMessage(), e);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Opens an {@link InputStream} for the named ROM file from the classpath.
     *
     * @param romFileName filename as returned by {@link #listRoms()}, e.g. {@code "nestest.nes"}
     * @return open {@link InputStream} — caller is responsible for closing it
     * @throws RomCatalogException if the ROM cannot be found
     */
    public static InputStream openRom(String romFileName) {
        String resource = "roms/" + romFileName;
        InputStream in = RomCatalog.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new RomCatalogException("ROM not found on classpath: " + resource);
        }
        return in;
    }

    /** Unchecked exception thrown when catalog operations fail. */
    public static class RomCatalogException extends RuntimeException {
        public RomCatalogException(String message) {
            super(message);
        }
        public RomCatalogException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
