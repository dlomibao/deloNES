package net.lomibao.nes.harness;

import net.lomibao.nes.components.PPU;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C4 — CI-safety audit (headless-harness plan, revision 2).
 *
 * <p>The fixtures tier must keep working under plain {@code gradle
 * core:test} in a headless shell — no GL context, no display, no LibGDX on
 * the execution path. This ArchUnit-lite guard scans every compiled
 * fixtures class's bytes for the constant-pool string {@code com/badlogic}:
 * any import, field type, method signature, or string reference to LibGDX
 * shows up there. The scan itself is validated against {@code core/src}
 * (whose {@code ColorPalette} legitimately imports LibGDX) so a silently
 * broken detector cannot pass vacuously.
 *
 * <p>The fixtures code source may be a classes directory or the
 * {@code core-<version>-test-fixtures.jar} Gradle publishes to the test
 * runtime classpath — both are handled.
 */
class FixturesCiSafetyTest {

    private static final byte[] NEEDLE = "com/badlogic".getBytes(StandardCharsets.US_ASCII);

    @Test
    void fixturesTier_hasNoReachableLibgdxReferences() throws Exception {
        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        scanCodeSource(NesHarness.class, scanned, offenders);

        assertTrue(scanned.size() >= 8,
                "expected to scan the whole fixtures tier, found only "
                + scanned.size() + " classes: " + scanned);
        assertTrue(scanned.stream().anyMatch(n -> n.contains("ScreenCapture")),
                "scan should cover ScreenCapture; scanned: " + scanned);
        assertTrue(offenders.isEmpty(),
                "fixtures tier must never reference LibGDX (com.badlogic) — "
                + "it has to run headless under plain core:test. Offenders: " + offenders);

        // Detector positive control: core/src legitimately references
        // LibGDX (ColorPalette), so the same scan there MUST find hits —
        // proving the needle scan is not vacuous.
        List<String> mainScanned = new ArrayList<>();
        List<String> mainOffenders = new ArrayList<>();
        scanCodeSource(PPU.class, mainScanned, mainOffenders);
        assertFalse(mainOffenders.isEmpty(),
                "detector self-check failed: no com/badlogic refs found in core/src classes");
    }

    /** Scan every .class in the class's code source (directory or jar). */
    private static void scanCodeSource(Class<?> cls, List<String> scanned,
                                       List<String> offenders) throws Exception {
        Path location = Paths.get(
                cls.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    if (p.toString().endsWith(".class")) {
                        record(location.relativize(p).toString(),
                                Files.readAllBytes(p), scanned, offenders);
                    }
                }
            }
            return;
        }
        try (ZipFile zip = new ZipFile(location.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    record(entry.getName(), in.readAllBytes(), scanned, offenders);
                }
            }
        }
    }

    private static void record(String name, byte[] bytes,
                               List<String> scanned, List<String> offenders) {
        scanned.add(name);
        if (containsNeedle(bytes)) {
            offenders.add(name);
        }
    }

    private static boolean containsNeedle(byte[] bytes) {
        outer:
        for (int i = 0; i <= bytes.length - NEEDLE.length; i++) {
            for (int j = 0; j < NEEDLE.length; j++) {
                if (bytes[i + j] != NEEDLE[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
