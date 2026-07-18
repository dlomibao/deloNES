package net.lomibao.nes.harness;

import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.rom.RomLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Headless harness facade (headless-harness plan, Phase A2 — fixtures tier).
 * Wraps a {@link RomLoader}-wired {@link NesSystem} with a frame counter,
 * frame-scripted input, and boundary hooks, replacing the hand-rolled
 * {@code runFrame()} loops of the Temp* diagnostic tests.
 *
 * <pre>
 * NesHarness h = NesHarness.fromRealRom("Micro Mages (World) (Aftermarket) (Unl).nes");
 * h.play(InputTimeline.builder()
 *         .press(Button.START).atFrame(1650).holdFrames(20)
 *         .build());
 * h.runToFrame(3300);
 * </pre>
 *
 * <h2>Frame semantics (D2)</h2>
 * {@link #frame()} is 0-based and counts <em>completed</em>
 * {@link NesSystem#runFrame()} calls (the scanline-262→0 wrap boundary —
 * never the NMI listener). Timeline edges and {@link #atFrame(int, Consumer)}
 * hooks for frame {@code N} run while {@code frame() == N}, i.e. at the
 * boundary before frame {@code N}'s first master tick — a game strobing
 * $4016 during frame {@code N} latches the scripted state. The harness
 * drives {@code runFrame()} exclusively (D11).
 *
 * <h2>Observation</h2>
 * {@link #peek(int)} is {@code cpuBus.read(addr, true)}. Note: until seam
 * S3 lands (Phase B1), peeking $4016/$4017 advances the controller shift
 * register — avoid those two addresses.
 *
 * <h2>Skip-if-absent (D8/D12)</h2>
 * {@link #fromRealRom(String)} throws {@link org.opentest4j.TestAbortedException}
 * when the ROM file is missing — JUnit 5 reports the test as skipped;
 * opentest4j is this tier's only test-framework dependency.
 */
public final class NesHarness {

    private final RomLoader.Loaded loaded;
    /** Completed runFrame() calls — see class Javadoc (D2). */
    private int frame;
    private InputTimelinePlayer inputPlayer;
    /** One-shot boundary hooks, keyed by frame; removed as they fire. */
    private final Map<Integer, List<Consumer<NesHarness>>> frameHooks =
            new HashMap<Integer, List<Consumer<NesHarness>>>();

    private NesHarness(RomLoader.Loaded loaded) {
        this.loaded = loaded;
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Boot a harness from raw iNES bytes (synthetic-ROM tests). */
    public static NesHarness fromBytes(byte[] romBytes, String name) {
        return new NesHarness(RomLoader.loadFromBytes(romBytes, name, TestRoms.opcodeCsv()));
    }

    /** Boot from a classpath resource, e.g. {@code "/nestest.nes"}. */
    public static NesHarness fromResource(String resourcePath) {
        return fromBytes(TestRoms.resourceBytes(resourcePath), resourcePath);
    }

    /**
     * Boot from a real (uncommitted) ROM under
     * {@code ~/projects/deloNES/core/src/main/resources/roms/}; skips the
     * calling test ({@code TestAbortedException}) when the file is absent.
     */
    public static NesHarness fromRealRom(String fileName) {
        return fromBytes(TestRoms.realRomBytesOrSkip(fileName), fileName);
    }

    // -------------------------------------------------------------------------
    // Scripting
    // -------------------------------------------------------------------------

    /**
     * Attach (replacing any previous) an input timeline. Edges already in
     * the past — {@code edge.frame() < frame()} — are applied at the next
     * boundary; script from frame 0 for deterministic runs.
     */
    public NesHarness play(InputTimeline timeline) {
        this.inputPlayer = new InputTimelinePlayer(timeline);
        return this;
    }

    /**
     * Register a one-shot hook that runs at the frame-{@code n} boundary
     * (while {@code frame() == n}, after timeline edges for {@code n} are
     * applied, before the frame's first tick). Hooks for a frame already in
     * the past never fire.
     */
    public NesHarness atFrame(int n, Consumer<NesHarness> action) {
        if (n < 0) {
            throw new IllegalArgumentException("frame must be >= 0, got: " + n);
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        List<Consumer<NesHarness>> hooks = frameHooks.get(n);
        if (hooks == null) {
            hooks = new ArrayList<Consumer<NesHarness>>();
            frameHooks.put(n, hooks);
        }
        hooks.add(action);
        return this;
    }

    // -------------------------------------------------------------------------
    // Driving
    // -------------------------------------------------------------------------

    /** Run exactly {@code n} frames. */
    public void runFrames(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, got: " + n);
        }
        for (int i = 0; i < n; i++) {
            stepFrame();
        }
    }

    /** Run until {@code frame() == target}. {@code target} must not be in the past. */
    public void runToFrame(int target) {
        if (target < frame) {
            throw new IllegalArgumentException(
                    "runToFrame(" + target + ") but already at frame " + frame);
        }
        while (frame < target) {
            stepFrame();
        }
    }

    /**
     * Run frames until {@code condition} is true, polling once per frame
     * boundary. Throws {@link AssertionError} (D8) if the condition is still
     * false after {@code maxFrames} additional frames.
     */
    public void runUntil(BooleanSupplier condition, int maxFrames) {
        for (int i = 0; i < maxFrames; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            stepFrame();
        }
        if (condition.getAsBoolean()) {
            return;
        }
        throw new AssertionError(
                "runUntil condition not met within " + maxFrames
                + " frames (now at frame " + frame + ")");
    }

    private void stepFrame() {
        if (inputPlayer != null) {
            inputPlayer.applyUpTo(frame, loaded.controller);
        }
        List<Consumer<NesHarness>> hooks = frameHooks.remove(frame);
        if (hooks != null) {
            for (Consumer<NesHarness> hook : hooks) {
                hook.accept(this);
            }
        }
        loaded.nes.runFrame();
        frame++;
    }

    // -------------------------------------------------------------------------
    // Observation
    // -------------------------------------------------------------------------

    /** Completed {@code runFrame()} calls — 0 before the first frame runs (D2). */
    public int frame() {
        return frame;
    }

    /**
     * Side-effect-free CPU-bus read ({@code read(addr, true)}), returned as
     * an unsigned int 0-255. Avoid $4016/$4017 until seam S3 (Phase B1).
     */
    public int peek(int addr) {
        return loaded.nes.getCpuBus().read(addr, true) & 0xFF;
    }

    // -------------------------------------------------------------------------
    // Component access
    // -------------------------------------------------------------------------

    public NesSystem nes() {
        return loaded.nes;
    }

    public CPU6502 cpu() {
        return loaded.cpu;
    }

    public PPU ppu() {
        return loaded.ppu;
    }

    public Controller controller() {
        return loaded.controller;
    }

    public Cartridge cartridge() {
        return loaded.cartridge;
    }
}
