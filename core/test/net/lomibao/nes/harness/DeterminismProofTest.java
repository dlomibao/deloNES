package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D3 — the determinism proofs (headless-harness plan; TAS
 * prerequisite). Three pins:
 *
 * <ol>
 *   <li>Replaying the same movie twice from fresh {@code RomLoader} boots
 *       yields bit-identical framebuffers at <em>every</em> sampled frame
 *       (per-frame FNV-1a hash streams equal over thousands of frames).</li>
 *   <li>Record a scripted run, serialize, parse, replay from a fresh boot
 *       — the replay's hash stream equals the one observed while
 *       recording (record → replay identity, the TAS property).</li>
 *   <li>No wall-clock or randomness source exists anywhere in
 *       {@code core/src/net/lomibao/nes} (grep guard from the
 *       determinism audit, decision D7).</li>
 * </ol>
 */
class DeterminismProofTest {

    /** Frames per proof run — "N thousand" per the plan; keeps runtime sane. */
    private static final int PROOF_FRAMES = 2000;

    /** The scripted input both proofs drive nestest with. */
    private static InputTimeline script() {
        return InputTimeline.builder()
                .press(Button.START).atFrame(60).holdFrames(5)
                .press(Button.SELECT).atFrame(200).holdFrames(5)
                .press(Button.START).atFrame(260).holdFrames(5)
                .build();
    }

    /** Fresh-boot replay of {@code timeline}; FNV-1a hash after each frame. */
    private static long[] replayHashes(InputTimeline timeline, int frames) {
        NesHarness h = NesHarness.fromResource("/nestest.nes");
        h.play(timeline);
        long[] hashes = new long[frames];
        for (int f = 0; f < frames; f++) {
            h.runFrames(1);
            hashes[f] = h.screen().fnv1a();
        }
        return hashes;
    }

    @Test
    void sameMovieTwice_fromFreshBoots_identicalHashAtEveryFrame() {
        Movie movie = new Movie("nestest.nes",
                TestRoms.sha256Hex(TestRoms.resourceBytes("/nestest.nes")),
                PROOF_FRAMES, script());

        long[] first = replayHashes(movie.timeline(), movie.frames());
        long[] second = replayHashes(movie.timeline(), movie.frames());
        assertArrayEquals(first, second,
                "same movie + same ROM from fresh boots must be bit-identical every frame");

        // Non-vacuity: the run must actually render evolving content — a
        // detector that hashed a frozen black screen would pass trivially.
        Set<Long> distinct = new HashSet<Long>();
        for (long h : first) {
            distinct.add(h);
        }
        assertTrue(distinct.size() > 1,
                "framebuffer never changed over " + PROOF_FRAMES + " frames — vacuous proof");
    }

    @Test
    void recordSerializeParseReplay_identicalHashStream() {
        // -- record a scripted run, hashing every frame as it happens --
        NesHarness h = NesHarness.fromResource("/nestest.nes");
        h.play(script());
        final InputRecorder recorder = new InputRecorder();
        for (int f = 0; f < PROOF_FRAMES; f++) {
            // Recorder samples at the same D2 boundary the player applies
            // edges at (atFrame hooks run after that frame's edges).
            h.atFrame(f, hh -> recorder.sampleFrame(hh.controller()));
        }
        long[] recordedHashes = new long[PROOF_FRAMES];
        for (int f = 0; f < PROOF_FRAMES; f++) {
            h.runFrames(1);
            recordedHashes[f] = h.screen().fnv1a();
        }

        // -- serialize → parse --
        Movie recorded = recorder.toMovie("nestest.nes",
                TestRoms.sha256Hex(TestRoms.resourceBytes("/nestest.nes")));
        assertEquals(PROOF_FRAMES, recorded.frames());
        Movie parsed = MovieFormat.parse(MovieFormat.serialize(recorded));
        assertEquals(script().edges(), parsed.timeline().edges(),
                "recording a scripted run must round-trip the script's edges verbatim");

        // -- replay the parsed movie from a fresh boot --
        long[] replayedHashes = replayHashes(parsed.timeline(), parsed.frames());
        assertArrayEquals(recordedHashes, replayedHashes,
                "record → serialize → parse → replay must reproduce the "
                + "original run's framebuffer at every frame");
    }

    @Test
    void coreSources_containNoWallClockOrRandomness() throws IOException {
        // Working dir of core:test is the core/ module dir (same assumption
        // ScreenCapture.defaultOutputDir makes).
        Path root = Paths.get("src", "net", "lomibao", "nes");
        assertTrue(Files.isDirectory(root), "expected core sources at " + root.toAbsolutePath());

        String[] forbidden = {
                "java.util.Random", "new Random(", "ThreadLocalRandom",
                "Math.random(",
                "System.nanoTime", "System.currentTimeMillis",
                "Instant.now", "LocalDate.now", "LocalDateTime.now",
                "new java.util.Date", "new Date(", "SecureRandom",
        };
        List<String> offenders = new ArrayList<String>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.toString().endsWith(".java")) {
                    continue;
                }
                scanned++;
                String source = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                for (String needle : forbidden) {
                    if (source.contains(needle)) {
                        offenders.add(p + " contains " + needle);
                    }
                }
            }
        }
        assertTrue(scanned >= 40, // core/src holds ~44 sources today
                "guard looks broken — only scanned " + scanned + " core sources");
        assertTrue(offenders.isEmpty(),
                "wall-clock/randomness must never enter core emulation "
                + "(deterministic-replay contract, D7): " + offenders);
    }
}
