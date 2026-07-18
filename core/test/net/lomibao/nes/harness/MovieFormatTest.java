package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D1 — movie format v1 (docs/headless-harness-plan.md).
 *
 * <p>Pins the exact text grammar (edge-list body, FM2's RLDUTSBA column
 * order, determinism header pins), the strict-parse failure modes (line
 * numbers, truncation guard via the {@code frames} pin, unknown region,
 * garbage lines), and serialize/parse round-trip identity.
 */
class MovieFormatTest {

    /** All-zeros sha-256 placeholder — format-valid, content-irrelevant. */
    private static final String SHA =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private static Movie movie(int frames, InputTimeline timeline) {
        return new Movie("test.nes", SHA, frames, timeline);
    }

    private static String header(int frames) {
        return "deloNES-movie 1\n"
                + "emu-version " + MovieFormat.EMU_VERSION + "\n"
                + "region ntsc\n"
                + "rom-name test.nes\n"
                + "rom-sha256 " + SHA + "\n"
                + "init-ram zero\n"
                + "loader romloader-v1\n"
                + "ports gamepad gamepad\n"
                + "frames " + frames + "\n"
                + "|frame|P1 RLDUTSBA|P2 RLDUTSBA|\n";
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    @Test
    void serialize_emitsExactGoldenText() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.START).atFrame(1650).holdFrames(20)
                .press(Button.A).atFrame(1800).holdFrames(20)
                .build();
        String text = MovieFormat.serialize(movie(3000, t));
        // Note: T (START) sits at column index 4 — RLDUTSBA order. The plan's
        // sketch line "1650 ...T...." is internally inconsistent with its own
        // RLDUTSBA rule; the column-order rule (FM2's) is authoritative.
        assertEquals(header(3000)
                + "1650 ....T... ........\n"
                + "1670 ........ ........\n"
                + "1800 .......A ........\n"
                + "1820 ........ ........\n", text);
    }

    @Test
    void serialize_columnOrderIsRldutsba() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.RIGHT).atFrame(0).holdFrames(1)
                .press(Button.LEFT).atFrame(0).holdFrames(1)
                .press(Button.DOWN).atFrame(0).holdFrames(1)
                .press(Button.UP).atFrame(0).holdFrames(1)
                .press(Button.START).atFrame(0).holdFrames(1)
                .press(Button.SELECT).atFrame(0).holdFrames(1)
                .press(Button.B).atFrame(0).holdFrames(1)
                .press(Button.A).atFrame(0).holdFrames(1)
                .build();
        String text = MovieFormat.serialize(movie(2, t));
        assertTrue(text.endsWith("0 RLDUTSBA ........\n1 ........ ........\n"),
                "all-buttons frame should render the full RLDUTSBA column set:\n" + text);
    }

    @Test
    void serialize_routesPlayer2ToSecondColumnBlock() {
        InputTimeline t = InputTimeline.builder()
                .player(1).press(Button.B).atFrame(100).holdFrames(2)
                .build();
        String text = MovieFormat.serialize(movie(200, t));
        assertTrue(text.endsWith("100 ........ ......B.\n102 ........ ........\n"),
                "P2 press must land in the second column block:\n" + text);
    }

    @Test
    void serialize_isEdgeList_oneLinePerChangeNotPerFrame() {
        // A 500-frame hold is exactly two body lines: the press and the release.
        InputTimeline t = InputTimeline.builder()
                .hold(Button.RIGHT).fromFrame(10).toFrame(509)
                .build();
        String text = MovieFormat.serialize(movie(600, t));
        String body = text.substring(header(600).length());
        assertEquals("10 R....... ........\n510 ........ ........\n", body);
    }

    @Test
    void serialize_emptyTimeline_isHeaderOnly() {
        String text = MovieFormat.serialize(movie(50, InputTimeline.builder().build()));
        assertEquals(header(50), text);
    }

    // -------------------------------------------------------------------------
    // Round trip
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_preservesHeaderFieldsAndEveryEdge() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.START).atFrame(1650).holdFrames(20)
                .press(Button.A).atFrame(1800).holdFrames(20)
                .player(1).press(Button.B).atFrame(1700).holdFrames(3)
                .build();
        Movie original = movie(4000, t);
        Movie parsed = MovieFormat.parse(MovieFormat.serialize(original));
        assertEquals(original.romName(), parsed.romName());
        assertEquals(original.romSha256(), parsed.romSha256());
        assertEquals(original.frames(), parsed.frames());
        assertEquals(original.timeline().edges(), parsed.timeline().edges());
    }

    @Test
    void roundTrip_textIsAFixedPoint() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.UP).atFrame(3).holdFrames(7)
                .hold(Button.A).fromFrame(5).toFrame(12)
                .build();
        String once = MovieFormat.serialize(movie(20, t));
        assertEquals(once, MovieFormat.serialize(MovieFormat.parse(once)));
    }

    // -------------------------------------------------------------------------
    // Strict parse failures — header
    // -------------------------------------------------------------------------

    @Test
    void parse_rejectsBadMagic_atLine1() {
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse("FCEUX-fm2 3\n"));
        assertEquals(1, e.line());
    }

    @Test
    void parse_rejectsEmuVersionMismatch_atLine2() {
        String text = header(10).replace(
                "emu-version " + MovieFormat.EMU_VERSION,
                "emu-version " + (MovieFormat.EMU_VERSION + 1));
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(text));
        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("emu-version"), e.getMessage());
    }

    @Test
    void parse_rejectsUnknownRegion_atLine3() {
        String text = header(10).replace("region ntsc", "region pal");
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(text));
        assertEquals(3, e.line());
        assertTrue(e.getMessage().contains("region"), e.getMessage());
    }

    @Test
    void parse_rejectsMalformedSha256() {
        assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(header(10).replace(SHA, "not-a-sha")));
        assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(header(10).replace(SHA, SHA + "00"))); // 66 chars
    }

    @Test
    void parse_rejectsBadFramesCount_atLine9() {
        for (String bad : new String[] {"frames 0", "frames -5", "frames ten", "frames"}) {
            String text = header(10).replace("frames 10", bad);
            MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                    () -> MovieFormat.parse(text), bad);
            assertEquals(9, e.line(), bad);
        }
    }

    @Test
    void parse_rejectsWrongHeaderKeyOrder() {
        String text = header(10)
                .replace("region ntsc", "init-ram zero")
                .replace("init-ram zero\nloader", "region ntsc\nloader");
        assertThrows(MovieFormat.ParseException.class, () -> MovieFormat.parse(text));
    }

    @Test
    void parse_rejectsTruncatedHeader_withLineNumberPastEnd() {
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse("deloNES-movie 1\nemu-version "
                        + MovieFormat.EMU_VERSION + "\nregion ntsc\n"));
        assertEquals(4, e.line());
        assertTrue(e.getMessage().contains("rom-name"), e.getMessage());
    }

    @Test
    void parse_rejectsMissingColumnsHeaderLine() {
        // Header ends after "frames" — the |frame|…| pin line is missing.
        String text = header(10).replace("|frame|P1 RLDUTSBA|P2 RLDUTSBA|\n", "");
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(text));
        assertEquals(10, e.line());
    }

    // -------------------------------------------------------------------------
    // Strict parse failures — body (truncation guard + garbage lines)
    // -------------------------------------------------------------------------

    @Test
    void parse_rejectsEdgeAtOrPastDeclaredFrames() {
        // frames 10 pins the movie length; an edge at frame 10 is past the end.
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(header(10) + "10 .......A ........\n"));
        assertEquals(11, e.line());
        assertTrue(e.getMessage().contains("frames"), e.getMessage());
    }

    @Test
    void parse_rejectsNonIncreasingBodyFrames() {
        String text = header(10)
                + "3 .......A ........\n"
                + "3 ......B. ........\n";
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(text));
        assertEquals(12, e.line());
    }

    @Test
    void parse_rejectsGarbageBodyLines() {
        String[] garbage = {
                "x .......A ........",       // non-numeric frame
                "3 .......A",                // missing P2 block (mid-line truncation)
                "3 .......A .......",        // P2 block only 7 chars
                "3 .......Q ........",       // invalid column letter
                "3 A....... ........",       // right letter, wrong column
                "3  .......A ........",      // double separator space
                "3 .......A ........ x",     // trailing junk
                "",                          // blank line
        };
        for (String line : garbage) {
            String text = header(10) + line + "\n";
            MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                    () -> MovieFormat.parse(text), "should reject: [" + line + "]");
            assertEquals(11, e.line(), "line number for: [" + line + "]");
        }
    }

    @Test
    void parse_rejectsNoChangeBodyLine() {
        // Edge-list contract: every body line must change controller state.
        String text = header(10)
                + "2 .......A ........\n"
                + "5 .......A ........\n"; // identical state — not an edge
        MovieFormat.ParseException e = assertThrows(MovieFormat.ParseException.class,
                () -> MovieFormat.parse(text));
        assertEquals(12, e.line());
    }

    @Test
    void parse_acceptsEmptyBodyAndNoTrailingNewline() {
        Movie noBody = MovieFormat.parse(header(10));
        assertEquals(0, noBody.timeline().edges().size());
        assertEquals(10, noBody.frames());

        Movie noTrailing = MovieFormat.parse(header(10) + "4 .......A ........");
        assertEquals(1, noTrailing.timeline().edges().size());
        InputTimeline.Edge edge = noTrailing.timeline().edges().get(0);
        assertEquals(4, edge.frame());
        assertEquals(Button.A, edge.button());
        assertTrue(edge.pressed());
    }

    // -------------------------------------------------------------------------
    // Movie model validation
    // -------------------------------------------------------------------------

    @Test
    void movie_rejectsTimelineExtendingPastDeclaredFrames() {
        InputTimeline t = InputTimeline.builder()
                .press(Button.A).atFrame(90).holdFrames(20) // release edge at 110
                .build();
        assertThrows(IllegalArgumentException.class, () -> movie(100, t));
    }

    @Test
    void movie_validatesFields() {
        InputTimeline empty = InputTimeline.builder().build();
        assertThrows(IllegalArgumentException.class, () -> new Movie("", SHA, 10, empty));
        assertThrows(IllegalArgumentException.class, () -> new Movie("a\nb", SHA, 10, empty));
        assertThrows(IllegalArgumentException.class, () -> new Movie("x.nes", "abc", 10, empty));
        assertThrows(IllegalArgumentException.class, () -> new Movie("x.nes", SHA, 0, empty));
        assertThrows(IllegalArgumentException.class, () -> new Movie("x.nes", SHA, 10, null));
        // Uppercase sha input is normalized to the canonical lowercase form.
        assertEquals(SHA.replace('0', 'a'),
                new Movie("x.nes", SHA.replace('0', 'A'), 10, empty).romSha256());
    }
}
