package net.lomibao.nes.harness;

/**
 * Immutable movie model — an {@link InputTimeline} plus the identity and
 * determinism pins the v1 movie format carries (headless-harness plan,
 * Phase D1).
 *
 * <p>v1 pins {@code region ntsc}, {@code init-ram zero},
 * {@code loader romloader-v1}, and {@code ports gamepad gamepad} as
 * constants (see {@link MovieFormat}), so the model carries only the
 * per-movie fields: ROM name, ROM sha-256 (computed by the caller — e.g.
 * the fixtures tier's {@code TestRoms.sha256Hex}; the core model just
 * carries the string, D7), the total {@link #frames() frame count}
 * (truncation pin), and the timeline itself.
 *
 * <p>TeaVM-safe (timeline tier): no IO, no regex, no {@code String.format}.
 */
public final class Movie {

    /** Length of a sha-256 hex digest in characters. */
    private static final int SHA256_HEX_LENGTH = 64;

    private final String romName;
    private final String romSha256;
    private final int frames;
    private final InputTimeline timeline;

    /**
     * @param romName   the ROM's file name (free text; no line breaks)
     * @param romSha256 sha-256 of the ROM image as 64 hex chars (any case;
     *                  stored lowercase)
     * @param frames    total movie length in frames — must be positive and
     *                  strictly greater than the timeline's last edge frame
     *                  (the format's truncation pin)
     * @param timeline  the input script; may be empty, never null
     */
    public Movie(String romName, String romSha256, int frames, InputTimeline timeline) {
        if (romName == null || romName.isEmpty()) {
            throw new IllegalArgumentException("romName must be non-empty");
        }
        if (romName.indexOf('\n') >= 0 || romName.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("romName must not contain line breaks");
        }
        this.romSha256 = normalizeSha256(romSha256);
        if (frames < 1) {
            throw new IllegalArgumentException("frames must be >= 1, got: " + frames);
        }
        if (timeline == null) {
            throw new IllegalArgumentException("timeline must not be null");
        }
        if (timeline.lastFrame() >= frames) {
            throw new IllegalArgumentException(
                    "timeline last edge (frame " + timeline.lastFrame()
                    + ") must lie before the declared movie end (frames " + frames + ")");
        }
        this.romName = romName;
        this.frames = frames;
        this.timeline = timeline;
    }

    /**
     * Validate 64 hex chars and lowercase them (no regex — timeline tier).
     * Package-private so {@link MovieFormat} can fail sha validation at the
     * header line with the right line number.
     */
    static String normalizeSha256(String sha) {
        if (sha == null || sha.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "romSha256 must be " + SHA256_HEX_LENGTH + " hex chars, got: " + sha);
        }
        StringBuilder out = new StringBuilder(SHA256_HEX_LENGTH);
        for (int i = 0; i < sha.length(); i++) {
            char c = sha.charAt(i);
            if (c >= 'A' && c <= 'F') {
                c = (char) (c - 'A' + 'a');
            }
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException(
                        "romSha256 must be hex, found '" + sha.charAt(i)
                        + "' at index " + i);
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The ROM's file name (identity hint; the sha-256 is authoritative). */
    public String romName() {
        return romName;
    }

    /** sha-256 of the ROM image, 64 lowercase hex chars. */
    public String romSha256() {
        return romSha256;
    }

    /**
     * Total movie length in frames — replay runs frames {@code 0} to
     * {@code frames - 1}; every timeline edge lies strictly before this.
     */
    public int frames() {
        return frames;
    }

    /** The input script (D2 frame semantics — same artifact scripts use). */
    public InputTimeline timeline() {
        return timeline;
    }
}
