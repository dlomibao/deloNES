package net.lomibao.nes.harness;

/**
 * Headless runner for blargg's $6000 result protocol
 * (docs/apu-plan.md, "Blargg $6000-protocol harness runner"; research
 * doc §5). Used by the APU phase gates (`apu_test/rom_singles`,
 * `apu_reset`, later `dmc_tests`).
 *
 * <pre>
 * BlarggResult r = BlarggRomRunner.run("test-roms/blargg/apu_test/rom_singles/1-len_ctr.nes");
 * assertEquals(0, r.code(), r.message());
 * </pre>
 *
 * <h2>Protocol</h2>
 * <ul>
 *   <li>$6000 is polled once per frame via side-effect-free
 *       {@link NesHarness#peek(int)}; the value is meaningful only after
 *       the magic bytes $DE $B0 $61 appear at $6001-$6003.</li>
 *   <li>$80 = running; $81 = the ROM wants a console reset — the runner
 *       runs &ge; 7 more frames (&gt;100 ms per the protocol), calls
 *       {@code h.nes().reset()}, and keeps polling.</li>
 *   <li>&lt; $80 = final result code (0 = pass); the ROM's diagnostic
 *       text is the zero-terminated ASCII at $6004.</li>
 * </ul>
 *
 * <p>Fixtures tier (harness D8): failures are plain
 * {@link AssertionError}s — no JUnit types here; one JUnit test method
 * per ROM lives in {@code core/test}.
 */
public final class BlarggRomRunner {

    /** Default frame cap — ~60 s of emulated time. */
    public static final int DEFAULT_FRAME_CAP = 3600;

    /** Status port and magic/message addresses of the protocol. */
    private static final int STATUS = 0x6000;
    private static final int MAGIC0 = 0x6001; // $DE
    private static final int MAGIC1 = 0x6002; // $B0
    private static final int MAGIC2 = 0x6003; // $61
    private static final int MESSAGE = 0x6004;
    /** Safety cap on the diagnostic text read. */
    private static final int MESSAGE_MAX = 512;
    /** Frames run after seeing $81 before reset — protocol wants >100 ms. */
    private static final int RESET_DELAY_FRAMES = 7;

    private BlarggRomRunner() {
        // static runner only
    }

    /** Run a committed ROM (classpath resource path) with the default cap. */
    public static BlarggResult run(String resourcePath) {
        return run(resourcePath, DEFAULT_FRAME_CAP);
    }

    /** Run a committed ROM with an explicit frame cap. */
    public static BlarggResult run(String resourcePath, int maxFrames) {
        String path = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        return run(NesHarness.fromResource(path), maxFrames);
    }

    /**
     * Drive an already-booted harness through the protocol. Exposed so
     * runner tests can feed synthetic protocol-walking ROMs.
     *
     * @throws AssertionError on timeout (cap expiry, magic or not)
     */
    public static BlarggResult run(NesHarness h, int maxFrames) {
        boolean magicSeen = false;
        int lastStatus = -1;
        // After issuing a reset, a stale $81 lingers in PRG-RAM until the
        // ROM's reset path rewrites $6000 — treat $81 as "running" until
        // any other status is observed, so one request = one reset.
        boolean awaitingPostReset = false;
        int framesRun = 0;
        while (framesRun < maxFrames) {
            h.runFrames(1);
            framesRun++;
            if (!magicSeen) {
                magicSeen = h.peek(MAGIC0) == 0xDE
                        && h.peek(MAGIC1) == 0xB0
                        && h.peek(MAGIC2) == 0x61;
                if (!magicSeen) {
                    continue;
                }
            }
            int status = h.peek(STATUS);
            lastStatus = status;
            if (status == 0x81) {
                if (!awaitingPostReset) {
                    h.runFrames(RESET_DELAY_FRAMES);
                    framesRun += RESET_DELAY_FRAMES;
                    h.nes().reset();
                    awaitingPostReset = true;
                }
                continue;
            }
            awaitingPostReset = false;
            if (status >= 0x80) {
                continue; // $80 running (and any other high code: keep going)
            }
            return new BlarggResult(status, readMessage(h));
        }
        throw new AssertionError("blargg ROM timed out after " + framesRun
                + " frames; " + (magicSeen
                        ? "last status=$" + Integer.toHexString(lastStatus).toUpperCase()
                        : "protocol magic $DE $B0 $61 never appeared at $6001-$6003"));
    }

    /** Zero-terminated ASCII at $6004, whitespace-trimmed. */
    private static String readMessage(NesHarness h) {
        StringBuilder sb = new StringBuilder(64);
        for (int addr = MESSAGE; addr < MESSAGE + MESSAGE_MAX; addr++) {
            int b = h.peek(addr);
            if (b == 0) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString().trim();
    }
}
