package net.lomibao.nes.harness;

import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.Button;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E4 — the audio-stream determinism proof (extends harness D3 /
 * {@link DeterminismProofTest}): replaying the same movie twice from
 * fresh boots yields a bit-identical <em>emitted sample stream</em> —
 * pinned as a per-frame 64-bit FNV-1a hash over each sample's raw float
 * bits (same hash family as {@code ScreenCapture.fnv1a()}).
 *
 * <p>Downsampler/IIR state is reset on boot and is a pure function of
 * the tick stream, so the streams must match exactly; audio is
 * observation-only (never part of the movie format or the framebuffer
 * proofs — determinism section of docs/apu-plan.md).
 */
class AudioDeterminismProofTest {

    /** Enough frames to cross NMI/IRQ/frame-counter seams thousands of times. */
    private static final int PROOF_FRAMES = 1000;

    /** Same shape as the D3 proof script — exercise input edges too. */
    private static InputTimeline script() {
        return InputTimeline.builder()
                .press(Button.START).atFrame(60).holdFrames(5)
                .press(Button.SELECT).atFrame(200).holdFrames(5)
                .press(Button.START).atFrame(260).holdFrames(5)
                .build();
    }

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long fnv1aFloats(long hash, float[] samples, int n) {
        for (int i = 0; i < n; i++) {
            int bits = Float.floatToRawIntBits(samples[i]);
            for (int shift = 24; shift >= 0; shift -= 8) {
                hash ^= (bits >>> shift) & 0xFF;
                hash *= FNV_PRIME;
            }
        }
        return hash;
    }

    /**
     * Fresh-boot replay: after each frame, drain the APU ring and fold
     * the samples into a running FNV-1a; also record the per-frame
     * running hash and the total sample count.
     */
    private static Result replay(InputTimeline timeline, int frames) {
        NesHarness h = NesHarness.fromResource("/nestest.nes");
        h.play(timeline);
        APU apu = h.nes().getApu();
        assertNotNull(apu, "RomLoader must wire an APU");
        float[] scratch = new float[2048];
        Result r = new Result(frames);
        long hash = FNV_OFFSET;
        for (int f = 0; f < frames; f++) {
            h.runFrames(1);
            int n;
            while ((n = apu.sampleBuffer().read(scratch, scratch.length)) > 0) {
                hash = fnv1aFloats(hash, scratch, n);
                r.totalSamples += n;
            }
            r.frameHashes[f] = hash;
        }
        assertTrue(apu.sampleBuffer().dropped() == 0,
                "per-frame draining must never overflow the ring");
        return r;
    }

    private static final class Result {
        final long[] frameHashes;
        int totalSamples;

        Result(int frames) {
            frameHashes = new long[frames];
        }
    }

    @Test
    void sameMovieTwice_fromFreshBoots_identicalAudioStreamHash() {
        Movie movie = new Movie("nestest.nes",
                TestRoms.sha256Hex(TestRoms.resourceBytes("/nestest.nes")),
                PROOF_FRAMES, script());

        Result first = replay(movie.timeline(), movie.frames());
        Result second = replay(movie.timeline(), movie.frames());

        assertArrayEquals(first.frameHashes, second.frameHashes,
                "same movie + same ROM from fresh boots must emit a bit-identical "
                + "sample stream at every frame boundary");

        // Non-vacuity: the stream cadence must match the box-average
        // ratio — PROOF_FRAMES * 29780.5 cycles / (1789773/44100) per
        // sample ~ 733.74 samples/frame. A dead mixer (0 samples) or a
        // hardcoded 735 both fail this window.
        double expected = PROOF_FRAMES * 29780.5 * 44100.0 / 1789773.0;
        assertTrue(Math.abs(first.totalSamples - expected) < PROOF_FRAMES * 0.5,
                "sample cadence off: got " + first.totalSamples + ", expected ~" + (long) expected);
    }
}
