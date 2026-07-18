package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C2 gate — golden-image round-trip over nestest background rendering.
 *
 * <p>The golden ({@code core/test/resources/golden/nestest-frame60.png}) is
 * committed: nestest is free content, the only kind allowed under
 * {@code golden/} (plan C2 rule — never commercial-ROM imagery). The match
 * is exact (D8): the emulator is deterministic, so any pixel drift is a real
 * rendering regression; on failure the actual + XOR-diff PNGs land in
 * {@code build/test-output/NestestGoldenTest/}.
 *
 * <p>Regenerate after a deliberate rendering change with
 * {@code ./gradlew core:test --tests "*NestestGoldenTest" -DregenGolden=true}
 * (the build forwards the flag into the test JVM), then commit the new
 * golden with the change that explains it.
 */
class NestestGoldenTest {

    private static final String GOLDEN_NAME = "nestest-frame60.png";
    /** Source-tree location regen writes to (test JVM cwd = core module dir). */
    private static final Path GOLDEN_SOURCE_PATH =
            Paths.get("test", "resources", "golden", GOLDEN_NAME);

    @Test
    void nestestBackground_frame60_matchesCommittedGoldenExactly() {
        NesHarness h = NesHarness.fromResource("/nestest.nes");
        h.runToFrame(60);

        if (Boolean.getBoolean("regenGolden")) {
            Path written = h.screen().savePng(GOLDEN_SOURCE_PATH);
            System.out.println("[regenGolden] wrote " + written.toAbsolutePath());
            // Round-trip against the file just written so a broken encode
            // still fails the regen run.
            h.screen().assertRegionEquals(
                    ScreenAssertions.goldenFromFile(written), 0, 0, 256, 240);
            return;
        }

        h.screen().assertRegionEquals(
                ScreenAssertions.goldenPng(GOLDEN_NAME), 0, 0, 256, 240);

        // Sanity: the frame is a real render, not an untouched all-black
        // buffer (which would make the golden vacuous).
        boolean nonBlackSeen = false;
        for (int y = 0; y < 240 && !nonBlackSeen; y++) {
            for (int x = 0; x < 256; x++) {
                if ((h.screen().pixel(x, y) & 0xFFFFFF) != 0) {
                    nonBlackSeen = true;
                    break;
                }
            }
        }
        assertTrue(nonBlackSeen, "nestest frame 60 should not be all black");
    }
}
