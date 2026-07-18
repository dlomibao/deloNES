package net.lomibao.nes.harness;

import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.Button;
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
 * {@link #peek(int)} is {@code cpuBus.read(addr, true)} and is side-effect
 * free everywhere — including $4016/$4017, which since seam S3 (Phase B1)
 * no longer advance the controller shift register on readOnly reads.
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

    // ---- Phase B2: watch engine state ----
    /** Registered write watches; the S1 listener is installed iff non-empty. */
    private final List<Watch> watches = new ArrayList<Watch>();
    /** Gated presses queued mid-frame — {player, buttonOrdinal, holdFrames}. */
    private final List<int[]> pendingGatedPresses = new ArrayList<int[]>();
    /** Gated releases due at a frame boundary — frame → {player, buttonOrdinal}. */
    private final Map<Integer, List<int[]>> gatedReleases =
            new HashMap<Integer, List<int[]>>();

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
    // Watches (Phase B2) — exact, mid-frame write triggers off seam S1
    // -------------------------------------------------------------------------

    /**
     * Register a write watch on a single address (mirror-canonicalized —
     * a watch on $0080 also fires for writes to $0880, and $2000 for $2008).
     * Terminal methods on the returned {@link WatchDsl} pick the trigger.
     * Callbacks may throw {@link AssertionError}, which propagates out of
     * {@link #runFrames}/{@link #runToFrame} unwrapped.
     */
    public WatchDsl watch(int addr) {
        return new WatchDsl(this, addr, addr);
    }

    /** Register a write watch on an inclusive address range. */
    public WatchDsl watchRange(int loAddr, int hiAddr) {
        return new WatchDsl(this, loAddr, hiAddr);
    }

    /** Called by {@link WatchDsl}; installs the S1 listener on first use. */
    Watch registerWatch(Watch w) {
        if (watches.isEmpty()) {
            loaded.nes.getCpuBus().setWriteListener(this::onBusWrite);
        }
        watches.add(w);
        return w;
    }

    /** Called by {@link Watch#remove()}; detaches S1 when the last watch goes. */
    void removeWatch(Watch w) {
        watches.remove(w);
        if (watches.isEmpty()) {
            loaded.nes.getCpuBus().setWriteListener(null);
        }
    }

    /** The single S1 {@code BusWriteListener} multiplexing all watches. */
    private void onBusWrite(int addr, int oldValue, byte newValue, int pc) {
        int canonical = Watch.canonical(addr);
        Watch.Write write = null;
        // Dispatch over a snapshot: a callback may remove watches (one-shot
        // pattern); mutating the live list mid-loop would shift a matching
        // watch into an already-visited slot and silently skip it for this
        // write. Removed watches are still filtered (dispatch no-ops after
        // remove()) so a snapshot never fires a stale watch.
        Watch[] snapshot = watches.toArray(new Watch[0]);
        for (int i = 0; i < snapshot.length; i++) {
            Watch watch = snapshot[i];
            if (watch.matches(canonical)) {
                if (write == null) {
                    write = new Watch.Write(
                            frame, pc, addr, canonical, oldValue, newValue & 0xFF);
                }
                watch.dispatch(write);
            }
        }
    }

    /**
     * Phase B3: collect every RAM write in the inclusive window
     * {@code [lo, hi]} that occurs while {@code body} runs — the supported
     * "who writes $X" trace. RAM-only ($0000-$1FFF, mirrors canonicalized);
     * throws {@link IllegalArgumentException} for a non-RAM window. The
     * temporary watch is removed even if {@code body} throws.
     */
    public List<RamTrace.Write> trace(int lo, int hi, Runnable body) {
        if (Watch.canonical(lo) > 0x07FF || Watch.canonical(hi) > 0x07FF) {
            throw new IllegalArgumentException(String.format(
                    "trace window $%04X-$%04X must lie in CPU RAM ($0000-$1FFF)", lo, hi));
        }
        final List<RamTrace.Write> out = new ArrayList<RamTrace.Write>();
        Watch watch = watchRange(lo, hi).onWrite(w -> out.add(new RamTrace.Write(
                w.frame(), w.pc(), w.addr(), w.oldValue(), w.newValue())));
        try {
            body.run();
        } finally {
            watch.remove();
        }
        return out;
    }

    /** Called by {@link Watch.Context#press}; applied at the next boundary. */
    void queueGatedPress(int player, Button button, int holdFrames) {
        if (holdFrames < 1) {
            throw new IllegalArgumentException("holdFrames must be >= 1, got: " + holdFrames);
        }
        pendingGatedPresses.add(new int[] {player, button.ordinal(), holdFrames});
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
        // Gated input (Phase B2): releases due at this boundary first, then
        // presses queued by watch triggers during the previous frame — so a
        // press fired mid-frame N is live for frames N+1 .. N+holdFrames.
        List<int[]> releases = gatedReleases.remove(frame);
        if (releases != null) {
            for (int[] r : releases) {
                loaded.controller.setButton(r[0], Button.values()[r[1]], false);
            }
        }
        if (!pendingGatedPresses.isEmpty()) {
            for (int[] p : pendingGatedPresses) {
                loaded.controller.setButton(p[0], Button.values()[p[1]], true);
                Integer releaseFrame = frame + p[2];
                List<int[]> due = gatedReleases.get(releaseFrame);
                if (due == null) {
                    due = new ArrayList<int[]>();
                    gatedReleases.put(releaseFrame, due);
                }
                due.add(new int[] {p[0], p[1]});
            }
            pendingGatedPresses.clear();
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
     * an unsigned int 0-255. Safe for every address incl. $4016/$4017 (S3).
     */
    public int peek(int addr) {
        return loaded.nes.getCpuBus().read(addr, true) & 0xFF;
    }

    // -------------------------------------------------------------------------
    // Frame capture (Phase C1)
    // -------------------------------------------------------------------------

    /**
     * Snapshot the visible framebuffer as it is now (ARGB ints — see
     * {@link ScreenCapture} for the channel-order contract). Typically used
     * at a frame boundary: {@code h.runToFrame(n); h.screen().assertPixel(...)}.
     */
    public ScreenCapture screen() {
        return ScreenCapture.of(loaded.ppu);
    }

    // -------------------------------------------------------------------------
    // Census helpers (Phase B4)
    // -------------------------------------------------------------------------

    /**
     * Snapshot census of real OAM (via public {@code PPU.readOam}) — the
     * sanctioned view of OAM contents; bus write watches never see OAM-DMA
     * bursts (they bypass {@code CPUBus.write} via {@code ppu.writeOam}).
     */
    public OamCensus oamCensus() {
        return OamCensus.of(loaded.ppu);
    }

    /**
     * Snapshot census of a shadow-OAM RAM page — the OAM-DMA source, e.g.
     * {@code shadowOamCensus(0x02)} for the classic $0200 page. Read via
     * side-effect-free peeks; no new seam needed.
     */
    public OamCensus shadowOamCensus(int page) {
        return OamCensus.ofRamPage(this, page);
    }

    /** Nametable census over seam S2 ({@code PPU.peekPpuBus}) — side-effect free. */
    public NametableCensus nametable() {
        return new NametableCensus(loaded.ppu);
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
