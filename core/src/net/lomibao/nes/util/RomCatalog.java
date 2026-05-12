package net.lomibao.nes.util;

import lombok.extern.log4j.Log4j2;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Provides a list of bundled ROM filenames available on the classpath.
 *
 * <p>Discovery is two-phase, in this order:
 * <ol>
 *   <li><b>Filesystem scan</b> of {@code roms/} when it resolves to a real
 *       directory (the common case in dev / IDE / {@code gradle desktop:run}).
 *       Any {@code .nes} file found is included automatically — drop a ROM in
 *       and it shows up in the menu without code changes.</li>
 *   <li><b>Manifest fallback</b> via {@code roms/index.txt} for fat-JAR runs
 *       where directory enumeration isn't reliable. Filenames are merged with
 *       the filesystem scan, deduplicated, preserving filesystem order then
 *       manifest order.</li>
 * </ol>
 *
 * <p>Use {@link #openRom(String)} to obtain an {@link InputStream} for a ROM
 * by its filename (e.g. {@code "nestest.nes"}).
 */
@Log4j2
public class RomCatalog {

    private static final String INDEX_RESOURCE = "roms/index.txt";
    private static final String ROMS_DIR = "roms/";

    private RomCatalog() {}

    /**
     * Returns an unmodifiable list of ROM filenames discovered on the classpath.
     * Filesystem-discovered ROMs (sorted alphabetically) come first, followed by
     * any manifest entries that weren't already discovered on disk.
     *
     * @return list of ROM filenames (never {@code null}, may be empty)
     * @throws RomCatalogException if neither the filesystem scan nor the manifest
     *         yield any ROMs (i.e. nothing is available to launch)
     */
    public static List<String> listRoms() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(scanFilesystem());
        names.addAll(readManifest());
        if (names.isEmpty()) {
            throw new RomCatalogException(
                    "No ROMs found: neither classpath filesystem scan of '" + ROMS_DIR
                    + "' nor manifest '" + INDEX_RESOURCE + "' yielded any entries.");
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    /**
     * Scans the {@code roms/} classpath directory for {@code .nes} files. Returns
     * an empty list if the directory cannot be enumerated as a real filesystem
     * path (e.g. when running from a JAR), in which case callers should fall
     * back to {@link #readManifest()}.
     */
    private static List<String> scanFilesystem() {
        try {
            URL url = RomCatalog.class.getClassLoader().getResource(ROMS_DIR);
            if (url == null) return Collections.emptyList();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                // Dev / IDE / gradle desktop:run when core's resources are exploded
                // (i.e. live in build/resources/main/roms/, not packed into a jar).
                File dir = new File(url.toURI());
                if (!dir.isDirectory()) return Collections.emptyList();
                String[] entries = dir.list((d, name) -> name.toLowerCase().endsWith(".nes"));
                if (entries == null || entries.length == 0) return Collections.emptyList();
                Arrays.sort(entries);
                return Arrays.asList(entries);
            } else if ("jar".equals(protocol)) {
                // Fat-JAR or dependency-JAR run: enumerate jar entries. URL format:
                //   jar:file:/path/to/foo.jar!/roms/
                String s = url.toString();
                int bang = s.indexOf("!/");
                if (bang < 0) return Collections.emptyList();
                String jarPath = s.substring("jar:file:".length(), bang);
                jarPath = java.net.URLDecoder.decode(jarPath, "UTF-8");
                String entryPrefix = s.substring(bang + 2);
                List<String> names = new ArrayList<>();
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                    java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries();
                    while (e.hasMoreElements()) {
                        String name = e.nextElement().getName();
                        if (name.startsWith(entryPrefix) && name.toLowerCase().endsWith(".nes")) {
                            String fileName = name.substring(entryPrefix.length());
                            if (!fileName.isEmpty() && !fileName.contains("/")) {
                                names.add(fileName);
                            }
                        }
                    }
                }
                Collections.sort(names);
                return names;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            // Any failure (URI parse, security, IO, ...) falls back to the manifest path.
            // Don't propagate — listRoms() still has the manifest as a second source —
            // but log at debug so misconfigurations surface when verbose logging is on.
            log.debug("scanFilesystem failed; falling back to manifest", e);
            return Collections.emptyList();
        }
    }

    /**
     * Reads {@code roms/index.txt} — plain-text manifest, one filename per line,
     * blank lines and {@code #} comment lines ignored. Returns an empty list if
     * the manifest doesn't exist on the classpath.
     */
    private static List<String> readManifest() {
        InputStream in = RomCatalog.class.getClassLoader().getResourceAsStream(INDEX_RESOURCE);
        if (in == null) return Collections.emptyList();
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
        return names;
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
