package net.lomibao.nes.harness;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pixel/region/golden-image assertions over a {@link ScreenCapture}
 * (headless-harness plan, Phase C2 — fixtures tier).
 *
 * <h2>Conventions (D8)</h2>
 * <ul>
 *   <li>Failures throw plain {@link AssertionError} — no JUnit types in the
 *       fixtures API.</li>
 *   <li>Golden comparisons are <b>exact</b>: the emulator is deterministic,
 *       so tolerance thresholds would only hide real one-pixel regressions.</li>
 *   <li>On a region mismatch the <em>actual</em> and an <em>XOR-diff</em>
 *       PNG ({@code 0xFF000000 | ((expected ^ actual) & 0xFFFFFF)} — black
 *       where identical) are written to
 *       {@link ScreenCapture#defaultOutputDir()} as
 *       {@code <golden>-actual.png} / {@code <golden>-diff.png}
 *       (deterministic names — reruns overwrite), and both paths appear in
 *       the error message.</li>
 *   <li>All pixel values are ARGB ({@code 0xAARRGGBB}) — the framebuffer's
 *       native format; see {@link ScreenCapture}.</li>
 * </ul>
 *
 * <p>Committed goldens live on the test classpath under {@code /golden/}
 * ({@code core/test/resources/golden/}) and may only be generated from
 * synthetic, homebrew, or nestest content — never commercial-ROM imagery
 * (plan C2 rule, D12).
 */
public final class ScreenAssertions {

    private ScreenAssertions() {
        // static helpers only
    }

    // -------------------------------------------------------------------------
    // Golden loading
    // -------------------------------------------------------------------------

    /** A named golden image; the name keys the failure-artifact file names. */
    public static final class Golden {
        private final String name;
        private final BufferedImage image;

        Golden(String name, BufferedImage image) {
            this.name = name;
            this.image = image;
        }

        public String name() {
            return name;
        }

        public BufferedImage image() {
            return image;
        }

        public int width() {
            return image.getWidth();
        }

        public int height() {
            return image.getHeight();
        }

        /** {@code name} without a trailing {@code .png}, for artifact naming. */
        String baseName() {
            return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        }
    }

    /**
     * Load a committed golden from the test classpath:
     * {@code /golden/<resourceName>}.
     */
    public static Golden goldenPng(String resourceName) {
        String path = "/golden/" + resourceName;
        try (InputStream in = ScreenAssertions.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException(
                        "Golden image not on the test classpath: " + path
                        + " (expected under core/test/resources/golden/)");
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IllegalArgumentException("Golden resource is not a decodable image: " + path);
            }
            return new Golden(resourceName, img);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading golden " + path, e);
        }
    }

    /** Load a golden from a file — regen flows and test-local goldens. */
    public static Golden goldenFromFile(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Golden file does not exist: " + path);
        }
        try {
            BufferedImage img = ImageIO.read(path.toFile());
            if (img == null) {
                throw new IllegalArgumentException("Golden file is not a decodable image: " + path);
            }
            return new Golden(path.getFileName().toString(), img);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading golden " + path, e);
        }
    }

    // -------------------------------------------------------------------------
    // Assertions
    // -------------------------------------------------------------------------

    /** Exact ARGB match at (x, y); plain {@link AssertionError} on mismatch. */
    static void assertPixel(ScreenCapture cap, int x, int y, int expectedArgb) {
        int actual = cap.pixel(x, y);
        if (actual != expectedArgb) {
            throw new AssertionError(String.format(
                    "pixel (%d,%d): expected 0x%08X but was 0x%08X",
                    x, y, expectedArgb, actual));
        }
    }

    /**
     * Exact comparison of the capture region at ({@code x},{@code y}) sized
     * {@code w}x{@code h} against the golden (whose dimensions must be
     * exactly {@code w}x{@code h}). On mismatch, writes failure artifacts
     * and throws with the first mismatching pixel and both artifact paths.
     *
     * <p>Artifact notes: the {@code -actual.png} is always the full
     * 256x240 screen (context), while {@code -diff.png} is region-sized —
     * their dimensions intentionally differ for sub-region comparisons.
     * The diff visualizes RGB only; an alpha-only mismatch is still
     * DETECTED (full-int compare) but renders black in the diff image —
     * consult the differing 0xAARRGGBB values in the exception message.
     */
    static void assertRegionEquals(ScreenCapture cap, Golden golden,
                                   int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || x < 0 || y < 0
                || x + w > cap.width() || y + h > cap.height()) {
            throw new IllegalArgumentException(String.format(
                    "region (%d,%d) %dx%d outside visible screen %dx%d",
                    x, y, w, h, cap.width(), cap.height()));
        }
        if (golden.width() != w || golden.height() != h) {
            Path actualPng = cap.savePng(artifactPath(golden, "-actual.png"));
            throw new AssertionError(String.format(
                    "golden '%s' is %dx%d but the compared region is %dx%d"
                    + " — golden and region dimensions must match exactly"
                    + " (actual screen: %s)",
                    golden.name(), golden.width(), golden.height(), w, h, actualPng));
        }

        BufferedImage diff = null;
        int mismatches = 0;
        int firstX = -1;
        int firstY = -1;
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int expected = golden.image().getRGB(dx, dy);
                int actual = cap.pixel(x + dx, y + dy);
                if (expected != actual) {
                    if (diff == null) {
                        diff = newBlackImage(w, h);
                        firstX = dx;
                        firstY = dy;
                    }
                    diff.setRGB(dx, dy, 0xFF000000 | ((expected ^ actual) & 0xFFFFFF));
                    mismatches++;
                }
            }
        }
        if (diff == null) {
            return;
        }

        Path actualPng = cap.savePng(artifactPath(golden, "-actual.png"));
        Path diffPng = writePng(diff, artifactPath(golden, "-diff.png"));
        int expectedFirst = golden.image().getRGB(firstX, firstY);
        int actualFirst = cap.pixel(x + firstX, y + firstY);
        throw new AssertionError(String.format(
                "region (%d,%d) %dx%d does not match golden '%s': %d of %d pixels"
                + " differ; first at (%d,%d) expected 0x%08X but was 0x%08X."
                + " Artifacts: actual=%s diff=%s",
                x, y, w, h, golden.name(), mismatches, w * h,
                firstX, firstY, expectedFirst, actualFirst, actualPng, diffPng));
    }

    // -------------------------------------------------------------------------

    private static Path artifactPath(Golden golden, String suffix) {
        return ScreenCapture.defaultOutputDir().resolve(golden.baseName() + suffix);
    }

    private static BufferedImage newBlackImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                img.setRGB(xx, yy, 0xFF000000);
            }
        }
        return img;
    }

    private static Path writePng(BufferedImage img, Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!ImageIO.write(img, "png", path.toFile())) {
                throw new IOException("no PNG writer available");
            }
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing PNG " + path, e);
        }
    }
}
