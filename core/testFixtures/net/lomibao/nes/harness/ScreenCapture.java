package net.lomibao.nes.harness;

import net.lomibao.nes.components.PPU;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Immutable snapshot of the PPU's visible framebuffer (headless-harness
 * plan, Phase C1 — fixtures tier), with PNG export via {@code javax.imageio}
 * (decision D6: imageio is confined to this tier, never {@code core/src}).
 *
 * <h2>Channel order (the named regression risk)</h2>
 * The framebuffer ints from {@link PPU#getVisibleScreenPixels1D()} are
 * <b>ARGB</b> ({@code 0xAARRGGBB}) — the same convention as
 * {@link PPU#getPaletteColorRgb(int)} and {@code BufferedImage.getRGB} —
 * despite older Javadoc calling them "RGBA" (the desktop hosts convert with
 * {@code (argb << 8) | (argb >>> 24)} before LibGDX's RGBA8888 Pixmap; see
 * {@code EmulatorScreen}). This class does <em>zero</em> channel shuffling:
 * ARGB ints go straight into a {@link BufferedImage#TYPE_INT_ARGB} image,
 * so a red framebuffer pixel is red in the PNG. {@code ScreenCaptureTest}
 * pins this with known-color pixels (the 2026-01-01 alpha-flip bug class —
 * CLAUDE.md).
 *
 * <p>All pixel values accepted/returned by this class and
 * {@link ScreenAssertions} are ARGB.
 */
public final class ScreenCapture {

    /** Visible framebuffer width (pixels). */
    public static final int WIDTH = PPU.VISIBLE_WIDTH;
    /** Visible framebuffer height (scanlines). */
    public static final int HEIGHT = PPU.VISIBLE_HEIGHT;

    /** ARGB pixels, row-major, {@code argb[y * WIDTH + x]}. Never aliased out. */
    private final int[] argb;

    private ScreenCapture(int[] argb) {
        this.argb = argb;
    }

    /**
     * Snapshot the PPU's visible screen as it is <em>now</em> — subsequent
     * emulation does not affect the capture.
     */
    public static ScreenCapture of(PPU ppu) {
        // getVisibleScreenPixels1D() already returns a fresh copy.
        return new ScreenCapture(ppu.getVisibleScreenPixels1D());
    }

    public int width() {
        return WIDTH;
    }

    public int height() {
        return HEIGHT;
    }

    /**
     * The ARGB value at (x, y) — {@code 0xAARRGGBB}, alpha always 0xFF for
     * anything the PPU rendered.
     */
    public int pixel(int x, int y) {
        checkBounds(x, y);
        return argb[y * WIDTH + x];
    }

    /** Copy the capture into a fresh {@link BufferedImage#TYPE_INT_ARGB} image. */
    public BufferedImage toImage() {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, WIDTH, HEIGHT, argb, 0, WIDTH);
        return img;
    }

    /**
     * Write the capture as a PNG, creating parent directories as needed.
     *
     * @return {@code path}, for chaining into assertion messages
     */
    public Path savePng(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!ImageIO.write(toImage(), "png", path.toFile())) {
                throw new IOException("no PNG writer available");
            }
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing PNG " + path, e);
        }
    }

    /**
     * Write the capture as {@code <defaultOutputDir>/<fileName>} — i.e.
     * {@code build/test-output/<callingTestClass>/<fileName>} (plan C1).
     * File names are caller-chosen and must be deterministic (no timestamps)
     * so reruns overwrite rather than accumulate.
     */
    public Path savePng(String fileName) {
        return savePng(defaultOutputDir().resolve(fileName));
    }

    /**
     * Default capture output dir: {@code build/test-output/<testClass>/},
     * relative to the Gradle module dir (the test JVM's working directory).
     * {@code <testClass>} is the simple name of the nearest stack frame
     * whose class name ends in {@code Test} or {@code IT}; falls back to
     * {@code "harness"} outside a test. Never a committed path.
     */
    public static Path defaultOutputDir() {
        return Paths.get("build", "test-output", callingTestClassSimpleName());
    }

    private static String callingTestClassSimpleName() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.endsWith("Test") || cls.endsWith("IT")) {
                int dot = cls.lastIndexOf('.');
                return dot < 0 ? cls : cls.substring(dot + 1);
            }
        }
        return "harness";
    }

    /**
     * 64-bit FNV-1a hash of the visible framebuffer, hashing each ARGB
     * pixel's four bytes most-significant first (Phase D3 — the
     * determinism-proof fingerprint: two captures hash equal iff they are
     * bit-identical for all practical purposes of the replay tests).
     */
    public long fnv1a() {
        long hash = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        for (int px : argb) {
            for (int shift = 24; shift >= 0; shift -= 8) {
                hash ^= (px >>> shift) & 0xFF;
                hash *= 0x100000001b3L;  // FNV-1a 64-bit prime
            }
        }
        return hash;
    }

    // -------------------------------------------------------------------------
    // Assertions (Phase C2) — see ScreenAssertions for conventions (D8)
    // -------------------------------------------------------------------------

    /** Exact ARGB match at (x, y); plain {@link AssertionError} on mismatch. */
    public void assertPixel(int x, int y, int expectedArgb) {
        ScreenAssertions.assertPixel(this, x, y, expectedArgb);
    }

    /**
     * Exact golden comparison of the region at ({@code x},{@code y}) sized
     * {@code w}x{@code h}. On mismatch, writes {@code <golden>-actual.png}
     * and {@code <golden>-diff.png} failure artifacts under
     * {@link #defaultOutputDir()} and throws with both paths in the message.
     */
    public void assertRegionEquals(ScreenAssertions.Golden golden, int x, int y, int w, int h) {
        ScreenAssertions.assertRegionEquals(this, golden, x, y, w, h);
    }

    private static void checkBounds(int x, int y) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
            throw new IllegalArgumentException(
                    "pixel (" + x + "," + y + ") outside visible screen "
                    + WIDTH + "x" + HEIGHT);
        }
    }
}
