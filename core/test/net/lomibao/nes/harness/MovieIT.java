package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.PPU;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D4 — movie-driven integration tests over the two committed
 * {@code core/test/resources/movies/*.dmov} files (headless-harness plan).
 *
 * <p>Committed movies reference their ROM by sha-256 (D12): nestest.nes is
 * in-repo, so its movie always runs; the Micro Mages movie skips when the
 * commercial ROM is absent — the movie file itself contains only input,
 * never ROM content.
 */
class MovieIT {

    private static Movie loadMovie(String resource) {
        return MovieFormat.parse(new String(
                TestRoms.resourceBytes(resource), StandardCharsets.UTF_8));
    }

    /**
     * The committed nestest movie replays end-to-end, its sha-256 pin
     * matches the in-repo ROM, and — the movie==script property — driving
     * the same run from an equivalent builder script produces a
     * bit-identical framebuffer at every frame.
     */
    @Test
    void nestestMovie_replaysIdenticallyToEquivalentScript() {
        Movie movie = loadMovie("/movies/nestest.dmov");
        assertEquals(TestRoms.sha256Hex(TestRoms.resourceBytes("/nestest.nes")),
                movie.romSha256(), "movie must pin the committed nestest ROM");

        // Replay the parsed movie.
        NesHarness movieRun = NesHarness.fromResource("/nestest.nes");
        movieRun.play(movie.timeline());
        long[] movieHashes = new long[movie.frames()];
        for (int f = 0; f < movie.frames(); f++) {
            movieRun.runFrames(1);
            movieHashes[f] = movieRun.screen().fnv1a();
        }

        // Same run, scripted through the Phase-A builder API (kept as sugar).
        NesHarness scriptRun = NesHarness.fromResource("/nestest.nes");
        scriptRun.play(InputTimeline.builder()
                .press(Button.START).atFrame(60).holdFrames(5)
                .press(Button.SELECT).atFrame(200).holdFrames(5)
                .press(Button.START).atFrame(260).holdFrames(5)
                .build());
        long[] scriptHashes = new long[movie.frames()];
        for (int f = 0; f < movie.frames(); f++) {
            scriptRun.runFrames(1);
            scriptHashes[f] = scriptRun.screen().fnv1a();
        }

        assertArrayEquals(movieHashes, scriptHashes,
                "a parsed movie and its equivalent builder script are the same artifact");
    }

    /**
     * The committed Micro Mages boot movie (the A3 hand-rolled script as a
     * movie file) drives the game from power-on into gameplay. Skips when
     * the commercial ROM is not present (D12).
     */
    @Test
    void microMagesMovie_bootsToGameplay() {
        // Parse the committed artifact BEFORE the skip-if-absent ROM load,
        // so CI without the commercial ROM still validates the movie file
        // (Phase D review finding — otherwise the artifact was unchecked
        // in ROM-less environments).
        Movie movie = loadMovie("/movies/micromages-boot.dmov");
        NesHarness h = NesHarness.fromRealRom(TestRoms.MICRO_MAGES); // skips if absent
        assertEquals(TestRoms.sha256Hex(
                        TestRoms.realRomBytesOrSkip(TestRoms.MICRO_MAGES)),
                movie.romSha256(), "movie must pin the exact Micro Mages dump");

        h.play(movie.timeline());
        h.runToFrame(movie.frames());

        assertTrue(liveOamSprites(h.ppu()) >= 3,
                "expected >= 3 live sprites (player + enemies) after replaying the"
                + " boot movie to frame " + h.frame());
    }

    /** OAM Y >= $EF means offscreen — same census convention as MicroMagesBootIT. */
    private static int liveOamSprites(PPU ppu) {
        int live = 0;
        for (int i = 0; i < 64; i++) {
            if ((ppu.readOam(i * 4) & 0xFF) < 0xEF) {
                live++;
            }
        }
        return live;
    }
}
