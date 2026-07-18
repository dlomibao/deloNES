package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;

import java.util.ArrayList;
import java.util.List;

/**
 * deloNES movie format v1 — text serialize/parse of a {@link Movie}
 * (headless-harness plan, Phase D1; decision D5: own edge-list format,
 * not FM2/BK2).
 *
 * <h2>Grammar (v1)</h2>
 * <pre>
 * deloNES-movie 1
 * emu-version 1
 * region ntsc
 * rom-name Micro Mages (World) (Aftermarket) (Unl).nes
 * rom-sha256 3f8c…(64 hex chars)…e2
 * init-ram zero
 * loader romloader-v1
 * ports gamepad gamepad
 * frames 10000
 * |frame|P1 RLDUTSBA|P2 RLDUTSBA|
 * 1650 ...T.... ........
 * 1670 ........ ........
 * </pre>
 *
 * <p>Header lines are fixed keys in fixed order. Body lines are an
 * <b>edge list</b>: one line per controller-state <em>change</em>, never
 * one per frame (FM2 is per-frame; ours stays diff-friendly and tiny).
 * Each body line is {@code <frame> <P1 cols> <P2 cols>} where the eight
 * column characters use FM2's canonical RLDUTSBA order — RIGHT, LEFT,
 * DOWN, UP, START (T), SELECT (S), B, A — the button's letter when held,
 * {@code '.'} when released. A body line gives the <em>complete</em>
 * two-controller state from that frame on (D2 boundary semantics), and
 * must differ from the previous state. Frames are strictly increasing
 * and strictly less than the {@code frames} header pin, which is the
 * movie's total length — an edge at/past it, or any malformed/truncated
 * line, fails parse loudly with a line number.
 *
 * <h2>Determinism pins (D7)</h2>
 * {@code emu-version} is {@link #EMU_VERSION}, bumped whenever emulation
 * timing or boot state changes (e.g. implementing the odd-frame cycle
 * skip) so old movies are rejected at parse instead of silently
 * desyncing. {@code region}/{@code init-ram}/{@code loader}/{@code ports}
 * are fixed v1 values validated on parse; a future PAL build or boot-seed
 * change is a new pin value, not a new format version.
 *
 * <p>TeaVM-safe (timeline tier): {@code indexOf}/{@code charAt}/
 * {@code substring} tokenization only — no {@code java.util.regex}, no
 * {@code String.split}, no {@code String.format}, no IO. Callers own file
 * IO (fixtures use {@code java.nio.file}; a web host could pass a fetched
 * string).
 */
public final class MovieFormat {

    /** Movie file format version — the number after the magic word. */
    public static final int FORMAT_VERSION = 1;

    /**
     * Emulation-behavior version pin. Bump whenever a change alters
     * deterministic replay (timing, power-on state, loader seed sequence);
     * parse rejects movies recorded under any other value.
     */
    public static final int EMU_VERSION = 1;

    /** Magic word opening every movie file. */
    static final String MAGIC = "deloNES-movie";
    /** Fixed v1 pin values. */
    static final String REGION = "ntsc";
    static final String INIT_RAM = "zero";
    static final String LOADER = "romloader-v1";
    static final String PORTS = "gamepad gamepad";
    /** The exact column-header line separating header from body. */
    static final String COLUMNS_HEADER = "|frame|P1 RLDUTSBA|P2 RLDUTSBA|";

    /** Column letters, index 0..7 left-to-right. */
    private static final String COLUMN_LETTERS = "RLDUTSBA";
    /**
     * Button shown in each column, left-to-right. Note this is exactly the
     * reverse of {@link Button} ordinal (shift-register) order.
     */
    private static final Button[] COLUMN_BUTTONS = {
            Button.RIGHT, Button.LEFT, Button.DOWN, Button.UP,
            Button.START, Button.SELECT, Button.B, Button.A,
    };
    private static final int NUM_PLAYERS = 2;
    private static final int NUM_COLUMNS = 8;

    private MovieFormat() {
        // static serialize/parse only
    }

    /** Strict parse failure — message always carries the 1-based line number. */
    public static final class ParseException extends RuntimeException {
        private final int line;

        public ParseException(int line, String message) {
            super("line " + line + ": " + message);
            this.line = line;
        }

        /** 1-based line number of the offending (or missing) line. */
        public int line() {
            return line;
        }
    }

    // -------------------------------------------------------------------------
    // Serialize
    // -------------------------------------------------------------------------

    /**
     * Render {@code movie} as v1 text (always {@code \n} line ends, with a
     * trailing newline). Redundant timeline edges (a press of an
     * already-held button) are normalized away — only real state changes
     * produce body lines.
     */
    public static String serialize(Movie movie) {
        if (movie == null) {
            throw new IllegalArgumentException("movie must not be null");
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append(MAGIC).append(' ').append(FORMAT_VERSION).append('\n');
        sb.append("emu-version ").append(EMU_VERSION).append('\n');
        sb.append("region ").append(REGION).append('\n');
        sb.append("rom-name ").append(movie.romName()).append('\n');
        sb.append("rom-sha256 ").append(movie.romSha256()).append('\n');
        sb.append("init-ram ").append(INIT_RAM).append('\n');
        sb.append("loader ").append(LOADER).append('\n');
        sb.append("ports ").append(PORTS).append('\n');
        sb.append("frames ").append(movie.frames()).append('\n');
        sb.append(COLUMNS_HEADER).append('\n');

        boolean[][] state = new boolean[NUM_PLAYERS][NUM_COLUMNS]; // [player][buttonOrdinal]
        List<InputTimeline.Edge> edges = movie.timeline().edges();
        int i = 0;
        while (i < edges.size()) {
            int frame = edges.get(i).frame();
            boolean changed = false;
            while (i < edges.size() && edges.get(i).frame() == frame) {
                InputTimeline.Edge e = edges.get(i);
                if (state[e.player()][e.button().ordinal()] != e.pressed()) {
                    state[e.player()][e.button().ordinal()] = e.pressed();
                    changed = true;
                }
                i++;
            }
            if (changed) {
                sb.append(frame);
                for (int p = 0; p < NUM_PLAYERS; p++) {
                    sb.append(' ');
                    for (int col = 0; col < NUM_COLUMNS; col++) {
                        boolean held = state[p][COLUMN_BUTTONS[col].ordinal()];
                        sb.append(held ? COLUMN_LETTERS.charAt(col) : '.');
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Parse
    // -------------------------------------------------------------------------

    /**
     * Parse v1 movie text. Strict: any deviation from the grammar —
     * wrong/missing header keys, a pin value this build does not reproduce,
     * malformed or out-of-order body lines, an edge at/past the declared
     * {@code frames} end — throws {@link ParseException} with the 1-based
     * line number.
     */
    public static Movie parse(String text) {
        if (text == null) {
            throw new ParseException(1, "movie text must not be null");
        }
        Lines lines = new Lines(text);

        // -- header (fixed keys, fixed order) --
        String magicValue = headerValue(lines, MAGIC);
        if (parseNonNegativeInt(magicValue, lines.number, MAGIC) != FORMAT_VERSION) {
            throw new ParseException(lines.number,
                    "unsupported format version '" + magicValue
                    + "' (this build reads version " + FORMAT_VERSION + ")");
        }
        String emuValue = headerValue(lines, "emu-version");
        if (parseNonNegativeInt(emuValue, lines.number, "emu-version") != EMU_VERSION) {
            throw new ParseException(lines.number,
                    "incompatible emu-version " + emuValue
                    + " (this build is emu-version " + EMU_VERSION
                    + "; emulation behavior differs — refusing to desync)");
        }
        expectHeaderPin(lines, "region", REGION);
        String romName = headerValue(lines, "rom-name");
        if (romName.isEmpty()) {
            throw new ParseException(lines.number, "rom-name must be non-empty");
        }
        String romSha256;
        try {
            romSha256 = Movie.normalizeSha256(headerValue(lines, "rom-sha256"));
        } catch (IllegalArgumentException e) {
            throw new ParseException(lines.number, e.getMessage());
        }
        expectHeaderPin(lines, "init-ram", INIT_RAM);
        expectHeaderPin(lines, "loader", LOADER);
        expectHeaderPin(lines, "ports", PORTS);
        String framesValue = headerValue(lines, "frames");
        int frames = parseNonNegativeInt(framesValue, lines.number, "frames");
        if (frames < 1) {
            throw new ParseException(lines.number,
                    "frames must be >= 1, got: " + framesValue);
        }
        String columns = lines.next();
        if (columns == null || !columns.equals(COLUMNS_HEADER)) {
            throw new ParseException(lines.number,
                    "expected column-header line '" + COLUMNS_HEADER + "'"
                    + (columns == null ? " (file truncated)" : ", got: '" + columns + "'"));
        }

        // -- body (edge list) --
        List<InputTimeline.Edge> edges = new ArrayList<InputTimeline.Edge>();
        boolean[][] state = new boolean[NUM_PLAYERS][NUM_COLUMNS];
        int prevFrame = -1;
        String line;
        while ((line = lines.next()) != null) {
            prevFrame = parseBodyLine(line, lines.number, frames, prevFrame, state, edges);
        }

        Movie movie;
        try {
            movie = new Movie(romName, romSha256, frames, new InputTimeline(edges));
        } catch (IllegalArgumentException e) {
            throw new ParseException(lines.number, e.getMessage());
        }
        return movie;
    }

    /** Parse one body line, appending its edges; returns the line's frame. */
    private static int parseBodyLine(String line, int lineNo, int frames,
                                     int prevFrame, boolean[][] state,
                                     List<InputTimeline.Edge> edges) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace <= 0) {
            throw new ParseException(lineNo,
                    "malformed body line (expected '<frame> <P1> <P2>'): '" + line + "'");
        }
        int frame = parseNonNegativeInt(line.substring(0, firstSpace), lineNo, "frame");
        if (frame <= prevFrame) {
            throw new ParseException(lineNo,
                    "body frames must be strictly increasing: " + frame
                    + " after " + prevFrame);
        }
        if (frame >= frames) {
            throw new ParseException(lineNo,
                    "edge at frame " + frame + " is at/past the declared movie end"
                    + " (frames " + frames + ") — truncated or corrupt file");
        }
        // Exact layout: frame + ' ' + 8 cols + ' ' + 8 cols, nothing else.
        int expectedLength = firstSpace + 1 + NUM_COLUMNS + 1 + NUM_COLUMNS;
        if (line.length() != expectedLength
                || line.charAt(firstSpace + 1 + NUM_COLUMNS) != ' ') {
            throw new ParseException(lineNo,
                    "malformed body line (expected '<frame> RLDUTSBA RLDUTSBA'"
                    + " with '.' for released): '" + line + "'");
        }
        boolean changed = false;
        for (int p = 0; p < NUM_PLAYERS; p++) {
            int blockStart = firstSpace + 1 + p * (NUM_COLUMNS + 1);
            for (int col = 0; col < NUM_COLUMNS; col++) {
                char c = line.charAt(blockStart + col);
                boolean pressed;
                if (c == '.') {
                    pressed = false;
                } else if (c == COLUMN_LETTERS.charAt(col)) {
                    pressed = true;
                } else {
                    throw new ParseException(lineNo,
                            "invalid column char '" + c + "' at column " + col
                            + " (expected '" + COLUMN_LETTERS.charAt(col) + "' or '.')");
                }
                if (state[p][COLUMN_BUTTONS[col].ordinal()] != pressed) {
                    changed = true;
                }
            }
        }
        if (!changed) {
            throw new ParseException(lineNo,
                    "body line changes nothing — the body is an edge list,"
                    + " one line per state change");
        }
        // Second pass emits edges in (player, button-ordinal) order — the
        // same total order InputTimelineBuilder produces, so parsed and
        // built timelines compare equal edge-for-edge.
        for (int p = 0; p < NUM_PLAYERS; p++) {
            int blockStart = firstSpace + 1 + p * (NUM_COLUMNS + 1);
            for (int ordinal = 0; ordinal < NUM_COLUMNS; ordinal++) {
                int col = NUM_COLUMNS - 1 - ordinal; // column order is reversed ordinal order
                boolean pressed = line.charAt(blockStart + col) != '.';
                if (state[p][ordinal] != pressed) {
                    state[p][ordinal] = pressed;
                    edges.add(new InputTimeline.Edge(
                            frame, p, Button.values()[ordinal], pressed));
                }
            }
        }
        return frame;
    }

    /** Read the next line and require key {@code key}; return the value part. */
    private static String headerValue(Lines lines, String key) {
        String line = lines.next();
        if (line == null) {
            throw new ParseException(lines.number,
                    "file truncated — expected header line '" + key + " …'");
        }
        // "key value": the key, one space, then the (possibly space-containing) value.
        if (line.length() > key.length()
                && line.charAt(key.length()) == ' '
                && line.startsWith(key)) {
            return line.substring(key.length() + 1);
        }
        throw new ParseException(lines.number,
                "expected header line '" + key + " …', got: '" + line + "'");
    }

    /** Header key whose value must equal a fixed v1 pin. */
    private static void expectHeaderPin(Lines lines, String key, String pinned) {
        String value = headerValue(lines, key);
        if (!value.equals(pinned)) {
            throw new ParseException(lines.number,
                    "unknown " + key + " '" + value + "' (this build only supports '"
                    + pinned + "')");
        }
    }

    /** Strict decimal parse: digits only (no sign, no whitespace). */
    private static int parseNonNegativeInt(String s, int lineNo, String what) {
        if (s.isEmpty() || s.length() > 9) {
            throw new ParseException(lineNo, "bad " + what + " number: '" + s + "'");
        }
        int value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new ParseException(lineNo, "bad " + what + " number: '" + s + "'");
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /**
     * Newline iterator over the movie text — {@code indexOf}-based, tracks
     * the 1-based line number. Tolerates one trailing {@code '\r'} per line
     * (CRLF checkouts) and a single trailing newline at end of text.
     */
    private static final class Lines {
        private final String text;
        private int pos;
        /** 1-based number of the line most recently returned (or expected). */
        int number;

        Lines(String text) {
            this.text = text;
        }

        /** Next line without its terminator, or null at end of text. */
        String next() {
            number++;
            if (pos >= text.length()) {
                return null;
            }
            int nl = text.indexOf('\n', pos);
            int end = (nl < 0) ? text.length() : nl;
            String line = text.substring(pos, end);
            pos = (nl < 0) ? text.length() : nl + 1;
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                line = line.substring(0, line.length() - 1);
            }
            return line;
        }
    }
}
