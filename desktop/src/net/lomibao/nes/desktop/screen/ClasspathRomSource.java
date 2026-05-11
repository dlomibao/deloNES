package net.lomibao.nes.desktop.screen;

import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link RomSource} backed by a classpath resource.
 *
 * <p>NOTE: This class is the Stream C deliverable stub. The merge will replace
 * it with Stream C's canonical version if signatures differ.
 */
public class ClasspathRomSource implements RomSource {

    private final String resourcePath;

    /**
     * @param resourcePath absolute classpath resource path, e.g. {@code "/roms/nestest.nes"}
     */
    public ClasspathRomSource(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    @Override
    public InputStream open() throws IOException {
        InputStream in = ClasspathRomSource.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException("Classpath resource not found: " + resourcePath);
        }
        return in;
    }

    @Override
    public String getDisplayName() {
        // Return everything after the last '/'
        int slash = resourcePath.lastIndexOf('/');
        return slash >= 0 ? resourcePath.substring(slash + 1) : resourcePath;
    }
}
