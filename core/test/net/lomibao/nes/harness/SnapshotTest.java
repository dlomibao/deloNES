package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C3 — trigger-driven snapshots (headless-harness plan, revision 2).
 * {@code h.snapshot(name)} dumps {@code name.png} (the visible screen) plus
 * {@code name.txt} (frame, PPUCTRL/PPUMASK/PPUSTATUS peeks, the 2KB CPU RAM,
 * and the 256-byte OAM), wired as an atFrame action (Phase A API) and as a
 * watch-trigger action (Phase B2 — lands here at the B+C join per the plan's
 * round-1 amendment). Names are caller-chosen and deterministic — no
 * timestamps — so replayed runs produce identical artifact paths.
 */
class SnapshotTest {

    private static NesHarness harness() {
        return NesHarnessTestRoms.controllerPollHarness();
    }

    private static void poke(NesHarness h, int addr, int value) {
        h.nes().getCpuBus().write(addr, (byte) value);
    }

    @Test
    void snapshot_writesPngAndTxtWithRamRegistersAndOam() throws Exception {
        NesHarness h = harness();
        h.runFrames(2);
        poke(h, 0x04B2, 0xEE);            // known RAM byte
        h.ppu().writeOam(4, (byte) 0x37); // known OAM byte (sprite 1 Y)

        Path dir = h.snapshot("contents");

        Path png = dir.resolve("contents.png");
        Path txt = dir.resolve("contents.txt");
        assertTrue(Files.exists(png), "missing " + png);
        assertTrue(Files.exists(txt), "missing " + txt);

        String dump = new String(Files.readAllBytes(txt), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(dump.contains("snapshot contents"), dump.substring(0, Math.min(200, dump.length())));
        assertTrue(dump.contains("frame 2"), "frame line missing/wrong");
        assertTrue(dump.contains(String.format("ppuctrl $%02X", h.ppu().peekCtrl())));
        assertTrue(dump.contains(String.format("ppumask $%02X", h.ppu().peekMask())));
        assertTrue(dump.contains(String.format("ppustatus $%02X", h.ppu().peekStatus())));
        // RAM dump row $04B0 carries the poked $EE at offset +2.
        assertTrue(dump.matches("(?s).*04B0: 00 00 EE .*"), "RAM row $04B0 not found with $EE");
        // OAM dump row 00 carries the poked $37 at offset +4.
        assertTrue(dump.matches("(?s).*oam.*\\n00: 00 00 00 00 37 .*"), "OAM row 00 not found with $37");
    }

    @Test
    void snapshot_pngIsTheCurrentVisibleScreen() throws Exception {
        NesHarness h = harness();
        h.runFrames(1);
        Path dir = h.snapshot("screen-match");
        BufferedImage png = ImageIO.read(dir.resolve("screen-match.png").toFile());
        ScreenCapture cap = h.screen();
        assertEquals(cap.pixel(0, 0), png.getRGB(0, 0));
        assertEquals(cap.pixel(128, 120), png.getRGB(128, 120));
        assertEquals(cap.pixel(255, 239), png.getRGB(255, 239));
    }

    /** C3 floor: atFrame-triggered capture through the Phase A hook API. */
    @Test
    void atFrameHook_snapshotCapturesThatBoundary() throws Exception {
        NesHarness h = harness();
        h.atFrame(3, hh -> hh.snapshot("at-frame-3"));
        h.runToFrame(5);

        Path dir = ScreenCapture.defaultOutputDir();
        assertTrue(Files.exists(dir.resolve("at-frame-3.png")));
        String dump = new String(Files.readAllBytes(dir.resolve("at-frame-3.txt")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(dump.contains("frame 3"), "snapshot must capture the frame-3 boundary");
    }

    /** B+C join: watch-triggered capture via Watch.Context (B2 merged). */
    @Test
    void watchTrigger_contextSavePngAndSnapshot_writeArtifacts() {
        NesHarness h = harness();
        h.watch(0x0080).whenBecomes(0x01).then(ctx ->
                ctx.savePng("watch-hit-frame" + ctx.harness().frame() + ".png"));
        h.watch(0x0090).whenBecomes(0x02).then(ctx -> ctx.snapshot("watch-snap"));

        poke(h, 0x0080, 0x01); // fires mid-"frame 0" (synchronous S1 dispatch)
        poke(h, 0x0090, 0x02);

        Path dir = ScreenCapture.defaultOutputDir();
        assertTrue(Files.exists(dir.resolve("watch-hit-frame0.png")));
        assertTrue(Files.exists(dir.resolve("watch-snap.png")));
        assertTrue(Files.exists(dir.resolve("watch-snap.txt")));
    }

    /** Gameplay-shaped wiring: timeline input makes the watch fire during a run. */
    @Test
    void watchTrigger_firesDuringRun_withTimelineInput() {
        NesHarness h = harness();
        h.play(InputTimeline.builder()
                .press(Button.A).atFrame(2).holdFrames(3)
                .build());
        // The poll ROM stores button A's bit at $0300 each poll loop.
        h.watch(0x0300).whenBecomes(0x01).then(ctx ->
                ctx.snapshot("a-pressed"));
        h.runToFrame(4);

        Path dir = ScreenCapture.defaultOutputDir();
        assertTrue(Files.exists(dir.resolve("a-pressed.png")));
        assertTrue(Files.exists(dir.resolve("a-pressed.txt")));
    }
}
