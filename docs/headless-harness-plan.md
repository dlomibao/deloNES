# Headless test/automation harness plan

**Branch:** `feature/headless-harness` (off master 9cf34fe).
**Revision: 2** — incorporates both review passes (watch old-value
semantics, Controller readOnly fix, explicit seam inventory S1–S4,
mirror canonicalization, DMA-bypass caveat, movie-format hardening).

**Goal:** a first-class, in-tree harness for scripting, observing, and
asserting on headless emulator runs — frame-scripted input, memory
watchpoints, frame capture with pixel assertions, and a
recordable/replayable input timeline that future TAS work can build on.
Phase A alone must make one-off diagnostics like the `TempPcTrace` /
`TempFrameDump` / `TempGameplayDiag` throwaways (see "Motivating
diagnostics" below) unnecessary.

This doc is the source of truth. Drive execution from it; don't rely on
chat context. Update checkboxes inline as phases land. Decisions are
numbered D1..D12 in the final section (DECISIONS.md will reference them
at PR time).

---

## Motivating diagnostics (what Phase A must replace)

The sprite-pipeline debugging sessions of 2026-07 produced uncommitted
throwaway tests in the `nes20` worktree
(`core/test/net/lomibao/nes/components/TempPcTrace.java`, plus the
since-deleted `TempFrameDump` / `TempGameplayDiag`). Every one of them
hand-rolled the same four things:

1. **Boot-to-gameplay input script** — `int[][] script = {{1650, START},
   {1800, A}, {1900, START}}` with manual `runFrame()` loops and
   hold-for-20-frames bookkeeping.
2. **Bus-write spying** — mutable `static` DIAG_* fields hacked into
   `CPUBus`/`PPU` (`DIAG_RAM_WRITES`, `DIAG_LO/HI`, `DIAG_FETCH_SET`,
   `DIAG_REG_SNAPSHOTS`) to log `{pc, addr, value}` triples for a RAM
   range during one frame.
3. **OAM / shadow-OAM census** — loop over `ppu.oam()` and the DMA source
   page counting entries with `y < 0xEF`, printed as a per-frame duty
   string.
4. **Frame dumps** — framebuffer-to-PNG snapshots at chosen frames.

The harness turns each of these into one line of supported API and
deletes the DIAG_* statics for good.

---

## Existing seams the harness builds on (read these first)

| Seam | File | What it gives us |
|---|---|---|
| `RomLoader.loadFromBytes(byte[], name, csvStream)` → `Loaded{nes,cpu,ppu,controller,cartridge}` | `core/src/net/lomibao/nes/rom/RomLoader.java` | Fully-wired headless system, no LibGDX. The harness's foundation. |
| `NesSystem.tick()` / `runFrame()` / `setFrameRenderedListener` | `core/src/net/lomibao/nes/NesSystem.java` | Deterministic stepping. `runFrame()` returns at the scanline-262→0 wrap (frameComplete), NOT at the NMI (scanline 241). |
| `Controller.setButton(int player, Button, boolean)` | `core/src/net/lomibao/nes/components/Controller.java` | Live state; latched into shift registers on $4016 strobe falling edge. Both players. |
| `CPUBus.write/read` inlined routing | `core/src/net/lomibao/nes/components/CPUBus.java` | The single choke point for CPU-visible memory traffic — the watch engine hooks here. Lombok `@Data @Builder`. |
| `PPU.getVisibleScreenPixels1D()` (ARGB ints (0xAARRGGBB — corrected from "RGBA" after Phase C empirically pinned the format)), `peekCtrl/peekMask/peekStatus`, `readOam(int)`, `getScanline/getCycle/isOddFrame` | `core/src/net/lomibao/nes/components/PPU.java` | Framebuffer + non-destructive register introspection. `oam()` and `ppuBus()` are package-private. |
| `CPU6502.runWithCallback(Predicate)` , `getPc()` | `core/src/net/lomibao/nes/components/CPU6502.java` | PC introspection for write attribution. |
| Skip-if-absent real-ROM pattern | `core/test/net/lomibao/nes/components/CartridgeNes2Test.java` (`microMages_realRom_loadsWhenPresent`) | `assumeTrue(Files.exists(rom))` against `~/projects/deloNES/core/src/main/resources/roms/...`. Commercial ROMs are never committed. |
| Synthetic-ROM builders | `core/test/net/lomibao/nes/rom/mapper/MapperTestSupport.java`, `RomLoaderTest`, `CartridgeNes2Test` | iNES byte[] fabrication for ROM-free unit tests. |

---

## Where it lives (D1)

Three tiers, split by who reuses what:

```
core/src/net/lomibao/nes/harness/        ← "timeline tier" (ships in core)
    InputTimeline.java                     immutable frame-indexed event list
    InputTimelineBuilder.java              the fluent press/at/hold DSL
    InputTimelinePlayer.java               applies events to a Controller at frame boundaries
    InputRecorder.java                     captures live Controller state per frame (Phase D)
    MovieFormat.java                       text serialize/parse of a timeline (Phase D)
    TimeBase.java                          NTSC fps constant + seconds→frame rounding

core/testFixtures/net/lomibao/nes/harness/   ← "harness tier" (java-test-fixtures)
    NesHarness.java                        facade: load, step, frame counter, wiring
    WatchDsl.java / Watch.java             memory-watch registration + trigger plumbing
    ScreenCapture.java                     framebuffer → PNG (javax.imageio) + pixel access
    ScreenAssertions.java                  pixel/region/golden-image assertions
    OamCensus.java / NametableCensus.java  sprite + nametable census helpers
    RamTrace.java                          {pc, addr, old, new} write log (replaces DIAG_*)
    TestRoms.java                          resource/real-ROM loading incl. skip-if-absent

core/test/net/lomibao/nes/harness/       ← tests OF the harness itself
```

Rationale:

- **Timeline tier in `core/src`** because the desktop host must record
  live play into the same `InputTimeline`/`MovieFormat`, and the web host
  could later replay one. Everything in this package is TeaVM-0.14-safe:
  plain collections, `String`/`StringBuilder`, no `javax.imageio`, no
  `java.nio.file`, no reflection, no `String.format` in hot paths, and
  **no `java.util.regex`** — `MovieFormat` tokenizes with
  `indexOf`/`charAt`/`substring`, never `String.split` or `Pattern`.
  `MovieFormat` parses/serializes to `String` — file IO stays with the
  caller (test fixtures use `Files`, web could use a fetched string).
- **Harness tier as a Gradle `java-test-fixtures` source set** on `core`
  (Gradle 9.1 supports it; wire `sourceSets.testFixtures.java.srcDirs =
  ["testFixtures/"]` to match the repo's non-Maven layout). This is where
  `javax.imageio`, `java.nio.file`, and JUnit-adjacent assertion helpers
  live — never compiled by TeaVM, but consumable by BOTH `core/test` and
  `desktop` tests via `testImplementation testFixtures(project(":core"))`.
- Alternatives considered in D1 (plain `core/test` util package; a new
  `harness/` Gradle module; everything in `core/src`) — see Decisions.

### Production seam inventory (S1–S4)

The harness needs a small, explicitly enumerated set of production
changes in `core/src` — all TeaVM-safe, all landed in the phase named:

| Seam | Change | Phase | Why |
|---|---|---|---|
| **S1** | `CPUBus` bus-write listener — single nullable field, null-guard dispatch in `write()` | B1 | Watchpoints + PC-attributed write traces; replaces the DIAG_* static hack permanently. |
| **S2** | `PPU.peekPpuBus(int addr)` — **public**, read-only PPU-bus read (no buffered-read side effects, no v increment) | B4 | Nametable census. Must be public: package-private seams in `net.lomibao.nes.components` are unreachable from fixtures classes in `net.lomibao.nes.harness` — package-private only crosses source sets within the *same* package. Narrow + documented "diagnostics only", consistent with the B6 peek-seam precedent. |
| **S3** | `Controller.cpuBusRead(addr, readOnly=true)` honors the flag — returns the current shift-register bit **without advancing `readIndex`** | B1 | Today `readPlayer` advances `readIndex` unconditionally, so `h.peek(0x4016/7)` or a frame poll touching those addresses would desync the joypad shift register mid-game. This fix is what makes the "observation is side-effect free" invariant true. Tiny, covered by ControllerTest additions. |
| **S4** | `Controller.isPressed(int player, Button)` — public read of live state | D2 | `InputRecorder` samples live controller state per frame. Alternative (recording from the desktop key-mapping layer) was rejected: it would fork the record path per host and couldn't serve a future web recorder. |

Nothing else in production changes; anything an implementer finds
wanting a fifth seam is a plan bug to surface, not a thing to slip in.

---

## Determinism audit (TAS prerequisite)

Current state, verified against `core/src` at 9cf34fe:

| Source | Status | Harness action |
|---|---|---|
| Initial CPU RAM | `new byte[2048]` → all zeros (`Ram.java`). Deterministic but not hardware-realistic (real NES powers up with garbage; some games mis-boot on all-zero). | Pin "zero RAM" as the documented power-on state; record it in the movie header (`init-ram: zero`) so a future 0xFF/pattern option can't silently desync old movies. |
| Initial PPU state (registers, OAM, nametable/palette RAM) | Zeroed arrays + `ppu.reset()`. `RomLoader` additionally seeds PPUMASK/palette writes ($2001=$08, palette $3F00-$3F03). Deterministic. | Movie header records `loader: romloader-v1` so a future change to the seed sequence bumps the tag. |
| `java.util.Random` / `nanoTime` / `currentTimeMillis` in core | **None** (grepped; only desktop host uses wall-clock for pacing). | Nothing to pin. Add an ArchUnit-style grep test in Phase D that fails if these ever appear in `core/src/net/lomibao/nes` (cheap regression guard). |
| Odd-frame cycle skip | **Not implemented** — `oddFrame` toggles but every frame is exactly 341×262 PPU ticks. Fully deterministic today. | Fine for replay. Movie header carries `emu-version` so that if the skip is ever implemented (a timing change), old movies are flagged incompatible instead of silently desyncing. |
| OAM-DMA alignment | `DmaController` computes the +1 alignment cycle from `masterClockCount` parity — deterministic given tick determinism. | Covered by the whole-run determinism test (Phase D3). |
| Mapper IRQ (MMC3) | Driven by PPU A12 edges — deterministic. | Same. |
| Input application | THE real risk. Desktop applies key events whenever LibGDX delivers them (mid-frame relative to emulation). | Harness applies input ONLY at frame boundaries (before frame N's first tick — D2). Desktop recording latches once per `runFrame()` call at the same boundary (D9). |
| `frameRenderedListener` | Fires at NMI (scanline 241), not the `runFrame()` wrap boundary — two different "per frame" hooks exist. | Harness defines "frame N" exclusively by `runFrame()` call count (D2) and never uses the NMI listener for input or watches. |
| `readOnly` bus reads | Almost side-effect free — **known hole:** `Controller.cpuBusRead` ignores the `readOnly` flag and advances `readIndex` on every read, so peeking $4016/$4017 today would desync the joypad shift register. | Fixed by seam **S3** in Phase B1 (Controller honors `readOnly`). After S3, all harness observation uses `read(addr, true)` / `peek*`; enforced by a Phase B test (observing every frame produces a bit-identical framebuffer vs not observing, including $4016/$4017 peeks). |

---

## API sketches (review these, then build to them)

### Fluent input DSL (Phase A, timeline tier)

```java
InputTimeline timeline = InputTimeline.builder()          // player 0 default
        .press(Button.START).atFrame(1650).holdFrames(20)
        .press(Button.A).atFrame(1800).holdFrames(20)
        .press(Button.START).atSeconds(31.6).holdFrames(20) // NTSC sugar, D3
        .player(1).press(Button.B).atFrame(100).holdFrames(2)
        .hold(Button.RIGHT).fromFrame(1950).toFrame(2100)   // explicit range form
        .build();                                           // immutable, sorted
```

Semantics (D2): an event `atFrame(N)` means the button is already down
when frame N's first master tick runs, so a game that strobes $4016
during frame N latches it. `holdFrames(k)` releases before frame N+k.
Internally the builder compiles to a sorted list of
`(frame, player, button, pressed)` edges — the same representation the
recorder emits and `MovieFormat` serializes, so scripted and recorded
timelines are the same artifact.

### Harness facade + watches + capture (Phases A–C, fixtures tier)

```java
NesHarness h = NesHarness.fromRealRom("Micro Mages (World) (Aftermarket) (Unl).nes");
        // skips when absent (TestAbortedException per D8), CartridgeNes2Test convention
// or: NesHarness.fromResource("/roms/nestest.nes");  NesHarness.fromBytes(bytes, name);

h.play(timeline);                                  // attach an InputTimeline

h.watch(0x0080).whenBecomes(0x01)                  // Phase B — value-edge watch (RAM only)
        .then(ctx -> ctx.press(Button.A, 2));      // gate input on emu state
h.watchRange(0x0700, 0x07FF).onWrite(w ->          // Phase B — bus-write watch
        log.info("pc={} addr={} {}→{}", w.pcHex(), w.addrHex(), w.oldHex(), w.newHex()));
h.watch(0x03B4).onChange(w -> h.screen().savePng("change-" + h.frame() + ".png"));
h.watch(0x2000).onWrite(w -> {});                  // non-RAM: write-only view, w.oldValue() == -1
h.watchRange(0x0300, 0x03FF).onWrite(w ->          // watches can fire assertions directly:
        // Write wrapper normalizes byte payloads to unsigned int (0-255),
        // so comparisons like == 0xEE read naturally.
        { if (w.newValue() == 0xEE) throw new AssertionError(
              "corruption at " + w.addrHex() + " frame " + h.frame() + " pc " + w.pcHex()); });

h.atFrame(1900, ctx -> ctx.snapshot("menu"));      // Phase C — PNG + RAM + reg dump
h.runToFrame(3500);                                // drives runFrame(), fires everything
h.runFrames(120);
h.runUntil(() -> h.peek(0x0080) == 1, 600);        // poll-per-frame, frame cap

// Phase C assertions — plain AssertionError, no JUnit dependency (D8)
h.screen().assertPixel(120, 100, 0xFF0000FF);            // ARGB, exact
h.screen().assertRegionEquals(goldenPng("title.png"), 0, 0, 256, 240);
h.screen().savePng(buildOutputDir().resolve("frame3500.png"));
assertEquals(3, h.oamCensus().liveSprites());            // y < 0xEF count
h.oamCensus().assertSpriteAt(/*tile*/0x32, /*xRange*/100, 140);
int tileId = h.nametable().tileAt(0, /*col*/5, /*row*/10);
List<RamTrace.Write> ws = h.trace(0x0700, 0x07FF, () -> h.runFrames(1));
```

`h.frame()` is the number of **completed** `runFrame()` calls: it starts
at 0, increments after each `runFrame()` returns, and timeline edges for
frame N are applied while `h.frame() == N` (i.e. before frame N's first
tick). The recorder uses the same base, so scripted and recorded
timelines agree from frame 0. `h.peek(addr)` is
`cpuBus.read(addr, true)`. Everything observational is side-effect free
(after S3 — see the seam inventory).

Watch semantics (Phase B, pinned here so the DSL can't drift):

- **Old values exist only for CPU RAM ($0000-$1FFF).** When (and only
  when) a listener is installed, the S1 dispatch snapshots the old value
  with a readOnly RAM read *before* routing the write. Value-tracking
  triggers — `onChange`, `whenBecomes`, `crossesAbove/Below` — are
  therefore **restricted to the RAM range**; registering one outside
  $0000-$1FFF throws `IllegalArgumentException` at registration time.
- Non-RAM addresses get `onWrite` only, with `oldValue() == -1`
  (sentinel; the record type stays unified).
- **Mirror canonicalization:** watch matching normalizes addresses —
  RAM watches match on `addr & 0x07FF`, PPU-register watches on
  `0x2000 + (addr & 7)` — so a watch on $0080 fires for a write to
  $0880 and a watch on $2000 fires for $2008. `w.addr()` reports the
  canonical address; `w.busAddr()` the CPU-visible one. Tested both ways.
- **OAM-DMA bypass:** `DmaController.tickDmaCycle` writes OAM via
  `ppu.writeOam` directly, never through `CPUBus.write` — write watches
  will NOT see the 256-byte OAM burst (they DO see the CPU stores that
  populate the shadow page, and the $4014 trigger write). `OamCensus`
  is the sanctioned view of OAM contents.

### Movie format v1 (Phase D, timeline tier)

```
deloNES-movie 1
emu-version 1
region ntsc
rom-name Micro Mages (World) (Aftermarket) (Unl).nes
rom-sha256 3f8c…e2
init-ram zero
loader romloader-v1
ports gamepad gamepad
frames 10000
|frame|P1 RLDUTSBA|P2 RLDUTSBA|
1650 ...T.... ........
1670 ........ ........
1800 .......A ........
```

Edge-list encoding (one line per state CHANGE, not per frame — FM2 is
per-frame; ours stays diff-friendly and tiny). Button columns use FM2's
canonical RLDUTSBA order so eyeballs trained on FM2 transfer. Header
keys are the determinism pins from the audit, plus two integrity pins:
`frames <count>` is the movie's total length — a file whose last edge
exceeds it, or that ends mid-line, fails parse loudly (truncation
guard) — and `region ntsc` is fixed for now (PAL is a future value, not
a v2 format). `emu-version` is the constant
`MovieFormat.EMU_VERSION` (timeline tier), bumped whenever emulation
timing or boot state changes. Parsing uses `indexOf`/`charAt`
tokenization only — no regex, no `String.split` (TeaVM tier rule). See
D5 for the FM2/BK2-vs-own survey.

---

## Phases

Each phase is independently landable: its own PR, `./gradlew core:check`
green (tests + existing JaCoCo gate), no phase depends on a later one.
TDD discipline per sub-stage: RED (tests first, watch them fail) →
GREEN → REFACTOR → SUITE, exactly as `docs/mapper-plan.md` Phase B
prescribes.

### Phase A — Harness core + frame-scripted input  *(replaces Temp\* workflow)*

Deliverables:

- **A0 — Gradle wiring.** `java-test-fixtures` plugin on `core`,
  `sourceSets.testFixtures.java.srcDirs = ["testFixtures/"]`, `desktop`
  test dependency on `testFixtures(project(":core"))`. Declare the
  fixtures-tier deps up front so this PR doesn't grow mid-flight:
  `testFixturesCompileOnly "org.projectlombok:lombok:<repo version>"`,
  `testFixturesAnnotationProcessor "org.projectlombok:lombok:<repo version>"`,
  `testFixturesImplementation` log4j-api (match `core`'s versions), and
  `testFixturesApi "org.opentest4j:opentest4j:<junit5-bundled version>"`
  (for D8/skip semantics — see Phase A2). One smoke test in `core/test`
  that imports a fixtures class. Confirm `html`/TeaVM build is untouched
  (fixtures never on its classpath).
- **A1 — `InputTimeline` + builder + player** (core/src, timeline tier).
  Edge-compiled immutable timeline; `InputTimelinePlayer.applyUpTo(frame,
  controller)` idempotent per frame. `TimeBase` with
  `NTSC_FPS = 60.0988` and `framesAt(seconds) = Math.round(seconds * NTSC_FPS)` (D3).
- **A2 — `NesHarness` facade** (fixtures tier). `fromBytes/fromResource/
  fromRealRom`, `runFrames/runToFrame/runUntil`, `frame()` (0-based,
  incremented after each completed `runFrame()` — D2), `peek()`,
  `play(timeline)`, `atFrame(n, action)` one-shot hooks. Input applied at
  the frame boundary before ticking (D2). `fromRealRom` skip-if-absent
  throws `org.opentest4j.TestAbortedException` directly — opentest4j is
  the fixtures tier's ONLY test-framework dependency (JUnit 5 treats the
  exception as a skip natively, so the `assumeTrue` convention's effect
  is preserved without a JUnit type in the fixtures API; consistent with
  D8).
- **A3 — Migration proof.** Rewrite `TempPcTrace.bootToGameplay()`'s
  script as a ~5-line harness test (skip-if-absent), committed as
  `MicroMagesBootIT` demonstrating the workflow. Delete nothing from
  nes20 (that worktree is not ours), but the plan's PR description shows
  the before/after.

TDD sub-stages & test floors:

| Sub | Tests | RED assertions |
|---|---|---|
| A1 | 10 | builder ordering, overlap merge, hold semantics, player-1 routing, atSeconds rounding (incl. the 60.0988 boundary cases), immutability |
| A2 | 8 | frame counter vs runFrame calls, input visible to a synthetic ROM that strobes+reads $4016 into RAM, runUntil cap throws, fromRealRom skips cleanly when absent |
| A3 | 1 | Micro Mages reaches gameplay: some RAM address known to change on level entry (reuse knowledge from the diag sessions) |

**Gate:** `core:check` green; the A3 integration test passes locally with
the ROM present and skips in CI.

### Phase B — Memory watches + trace attribution

Deliverables:

- **B1 — seams S1 + S3** (production changes):

  **S1 — `CPUBus` write listener:**

  ```java
  public interface BusWriteListener {           // core/src, TeaVM-safe
      /** oldValue is the pre-write RAM value for $0000-$1FFF, or -1
       *  for every other address (PPU regs, APU/IO, cart space have no
       *  well-defined "old value" at the bus level). */
      void onWrite(int addr, int oldValue, byte newValue, int pc);
  }
  // CPUBus field: private BusWriteListener writeListener;  (settable, nullable)
  // in write(), BEFORE routing the write:
  //   if (writeListener != null) {
  //       int old = (ram != null && addr < 0x2000)
  //               ? ram.cpuBusRead(addr, true) : -1;   // readOnly snapshot
  //       writeListener.onWrite(addr, old, value, cpu.getPc());
  //   }
  ```

  Honest cost accounting: with **no listener installed** this is one
  null-check branch (the nestest-parity gate below). With a listener
  attached, each write additionally pays the readOnly RAM snapshot and
  the callback — acceptable, since a listener is only ever attached by
  the harness. No Optional, no per-call lambda capture (the `clock()`
  hot-path convention). `pc` from `cpu.getPc()` at write time ("pc
  after fetch", same semantics the DIAG hack had — document it).
  The old-value snapshot is what makes RAM-only `onChange`/
  `whenBecomes`/`crossesAbove/Below` well-defined; see the pinned watch
  semantics in the API-sketch section.

  **S3 — `Controller` honors `readOnly`:** `readPlayer` gets a
  `readOnly` parameter; when true it returns the bit at the current
  `readIndex` without advancing it (and without strobe-reload side
  effects). Without this, any peek/poll of $4016/$4017 desyncs the
  joypad shift register — see the determinism-audit row.

  Bench: desktop DK still 60 FPS, web `runFrame` ms within noise for
  the listener-null case.
- **B2 — Watch engine + DSL** (fixtures tier). Two trigger tiers,
  explicit in the API (D4):
  - *write watches* — exact, fire mid-frame from the S1 seam
    (`onWrite` anywhere; `onChange`, `whenBecomes`, `crossesAbove/Below`
    **RAM-range only**, enforced with `IllegalArgumentException` at
    registration; address ranges; mirror-canonicalized matching per the
    pinned watch semantics);
  - *frame polls* — evaluated once per frame boundary via `peek`
    (`runUntil`, watch-gated input `then(ctx -> ctx.press(...))` — the
    press takes effect at the NEXT frame boundary, keeping D2's
    determinism story intact).

  Watch callbacks may throw `AssertionError` to fail the test from
  inside a trigger; the engine lets it propagate out of `runFrames`/
  `runToFrame` unwrapped (contrast `frameRenderedListener`, which
  swallows listener exceptions — watches deliberately do NOT route
  through it).
- **B3 — `RamTrace`** — `h.trace(lo, hi, runnable)` collects
  `{frame, pc, addr, old, new}`; formatted dump helper reproduces the
  TempPcTrace "who writes $03B4" output.
- **B4 — PPU-side census helpers + seam S2.** `OamCensus` over the
  public `readOam(int)`; `NametableCensus` via seam **S2**
  (`PPU.peekPpuBus(int)`, public — package-private would be unreachable
  from the `harness` package; see the seam inventory). Shadow-OAM census
  reads the DMA page via `peek` (no new seam needed). Document here
  that OAM-DMA bursts bypass `CPUBus.write` (they go straight to
  `ppu.writeOam`), so write watches never see them — `OamCensus` is the
  sanctioned view of OAM contents.

| Sub | Tests | Notes |
|---|---|---|
| B1 | 10 | listener fires with correct pc/old/new; old snapshot taken before the write lands; oldValue == -1 for PPU-reg and cart-space writes; null listener = zero behavior change (bit-identical nestest trace); S3: $4016/$4017 readOnly read does not advance readIndex, normal read still does (ControllerTest additions) |
| B2 | 15 | each trigger kind; range watch; whenBecomes edge (not level); value-tracking watch outside RAM throws IAE at registration; mirror canonicalization both directions ($0880 write fires $0080 watch, $2008 fires $2000 watch; addr() canonical vs busAddr() as-seen); callback AssertionError propagates out of runFrames; gated input lands next frame; watch removal |
| B3 | 4 | trace window bounds, frame attribution |
| B4 | 6 | census on synthetic OAM; y≥0xEF excluded; nametable tile fetch readOnly-ness (framebuffer unchanged by observing) |

**Gate:** `core:check` green; `NestestTest` still 8992/8992 (B1 touches
the bus write path); web-build runFrame within 5% (mapper-plan A3
precedent).

### Phase C — Frame capture & assertions

Deliverables:

- **C1 — `ScreenCapture`** (fixtures tier). Wrap
  `ppu.getVisibleScreenPixels1D()`; ARGB-int → `BufferedImage`
  conversion with an explicit channel-order unit test (the 2026-01-01
  RGBA/ARGB alpha-flip bug is the named regression risk — CLAUDE.md).
  `savePng(Path)` via `javax.imageio` (fixtures only — D6). Default
  output dir `core/build/test-output/<testClass>/`, never committed.
- **C2 — Pixel/region assertions.** `assertPixel`, `assertRegionEquals`
  (golden PNG, exact match since output is deterministic — D8; on
  failure, write actual + XOR-diff PNGs next to the report and include
  their paths in the AssertionError message). Golden files live under
  `core/test/resources/golden/` and may only be generated from
  synthetic/homebrew/nestest content (never commercial-ROM imagery).
- **C3 — Trigger-driven capture.** `ctx.savePng(...)`, `ctx.snapshot(name)`
  = PNG + 2KB RAM copy + `peekCtrl/peekMask/peekStatus` + OAM copy,
  dumped as `name.png` + `name.txt`. Wire as a watch/atFrame action.
- **C4 — CI-safety audit test.** A test asserting the fixtures tier
  never touches LibGDX classes (classpath scan or ArchUnit-lite grep) —
  the harness must keep running under plain `gradle core:test`, no GL,
  no display.

| Sub | Tests | Notes |
|---|---|---|
| C1 | 6 | channel order (a red pixel is red in the PNG bytes), PNG round-trip read-back, dir creation |
| C2 | 8 | pass/fail paths, diff artifact emission, golden round-trip on nestest first frame |
| C3 | 4 | snapshot contents; `atFrame`-triggered capture (Phase A API). Watch-triggered capture test lands at the B+C join (C3 depends on B2 for that one case) |
| C4 | 1 | no `com.badlogic` refs from fixtures |

**Gate:** `core:check` green in a headless shell (no DISPLAY); one
golden-image test over nestest background rendering committed.

### Phase D — Recording, replay, movie format (TAS foundation)

Deliverables:

- **D1 — `MovieFormat`** (core/src, timeline tier). Serialize/parse the
  v1 text format sketched above, String-in/String-out (TeaVM-safe;
  `indexOf`/`charAt` tokenization only — no regex/`String.split`).
  Strict parse errors with line numbers; a missing/violated
  `frames <count>` pin (truncated file, edge past the declared end)
  fails parse loudly. Header pins: `emu-version`
  (`MovieFormat.EMU_VERSION`), `region` (`ntsc` only for now),
  `rom-sha256` (helper in fixtures computes it; the core model just
  carries the string), `init-ram`, `loader`, `frames`.
- **D2 — `InputRecorder`** (core/src). Samples both controllers' live
  state once per frame boundary via seam **S4**
  (`Controller.isPressed(player, button)`) and emits edges into an
  `InputTimeline`. Desktop wiring (`desktop` module): the host's
  existing per-render `runFrame()` loop calls
  `recorder.sampleFrame(controller)` immediately before `runFrame()` —
  same boundary the harness replays at (D9). A debug key/flag dumps the
  movie to `~/.deloNES/movies/` (reuse the B3-controls.json per-user
  location convention).
- **D3 — Determinism proof tests.** (a) Replay the same movie twice from
  a fresh `RomLoader` boot → assert per-frame FNV-1a hashes of the
  framebuffer are identical across N thousand frames. (b) Record a
  scripted run, serialize, parse, replay → identical hash stream.
  (c) The "no wall-clock/Random in core" grep guard from the
  determinism audit.
- **D4 — Movie-driven integration tests.** Commit a movie for nestest
  and (skip-if-absent) one for Micro Mages boot-to-gameplay, replacing
  the A3 hand-rolled script; keep A3's API as sugar.

| Sub | Tests | Notes |
|---|---|---|
| D1 | 12 | round-trip, header validation, truncation guard (`frames` pin), unknown region rejected, bad-line diagnostics, RLDUTSBA ordering, edge-list semantics |
| D2 | 6 | recorded edges == applied edges for a scripted run; two-player |
| D3 | 3 | the three proofs above |
| D4 | 2 | movie ITs |

**Gate:** `core:check` green; record→replay hash-identity holds for a
10,000-frame Micro Mages run locally.

### Phase E — Savestate hooks scoping  *(scope only — no implementation)*

Explicitly a follow-up. This phase produces a short design note
(`docs/savestate-scope.md`), not code. The scoping inventory the note
must cover, from reading the components today:

- **CPU6502:** a/x/y/sp/pc/status + cycle-remaining counter + pending
  IRQ/NMI latches. Mostly private — needs a `CpuState` snapshot struct
  or package-private copy in/out.
- **PPU:** registers[8], v/t/x/w loopy state, shifters/latches, OAM,
  secondary OAM, scanline/cycle/oddFrame/frameComplete, NMI latch,
  read buffer. Largest surface; the private-registers refactor (B6)
  means state export must be a deliberate seam, not field access.
- **Buses/RAM:** `Ram.byteArray`, nametable + palette RAM,
  `masterClockCount`/`phase`.
- **DMA:** active flag, page/addr progress, alignment sub-state.
- **APU:** currently near-stub — flag as "capture version tag now, real
  state later".
- **Mappers:** each mapper's bank registers + MMC1 shifter + MMC3 IRQ
  counter — propose a `Mapper.saveState()/loadState(byte[])` default
  method pair.
- Format question (versioned binary vs text) and the movie-format
  interaction (`savestate-anchored` movies, BK2-style) are noted as open
  and OUT of scope here.

**Gate:** doc reviewed by Derek; issues filed per component.

---

## Parallelism map

```
A0 → A1 → A2 → A3            (sequential; A is the foundation)
            │
            ├─→ B1 → B2 → B3 → B4     (B1 lands seams S1+S3; B4 lands S2)
            └─→ C1 → C2 → C3 → C4     (C independent of B)
                    │
        B, C  ──────┴─→ D1 → D2 → D3 → D4
                              │
                              └─→ E (doc only, any time after D1)
```

B and C can run as parallel worktrees after Phase A lands (no shared
files: B touches CPUBus + watch classes, C touches capture classes).
D needs both.

---

## Risk register

| Risk | Mitigation |
|---|---|
| B1 listener branch regresses the bus hot path | One nullable-field check; bench desktop DK 60 FPS + web runFrame before/after; NestestTest 8992/8992 as the correctness gate. |
| `java-test-fixtures` fights the non-Maven layout | A0 is deliberately tiny (wiring + smoke test) so the fight happens first, in isolation. Fallback: plain `core/test/.../harness/` package now, extract fixtures when desktop actually consumes it (records as amended D1). |
| Golden-image tests turn brittle when PPU rendering improves | Goldens are opt-in per test, regenerated via a `-DregenGolden` flag; keep the count low (nestest background + synthetic patterns only). |
| Frame-boundary input differs from real-hardware polling timing | Documented as the harness's determinism contract (D2). Games poll during NMI; a boundary-applied press is stable-by-construction. If a game ever needs sub-frame input, that's a movie-format v2 (`frame:subtick`) extension — noted, not built. |
| `atSeconds` rounding surprises ("3.5s" ≠ someone's mental 210) | D3 documents `Math.round(s × 60.0988)`; builder Javadoc shows the formula; tests pin boundary cases. |
| Commercial-ROM leakage via goldens/movies | C2/D4 rules: goldens only from free content; committed movies reference ROMs by sha256 and skip when absent. |

## Out of scope

- Full savestate design/implementation (Phase E scopes it only).
- Video/GIF/APNG export and audio capture.
- Netplay, input over network.
- Rerecording UI / TAS editor / frame-advance desktop UX.
- FM2/BK2 bidirectional converters (import/export helpers are a
  possible post-D follow-up; see D5).
- PAL timing (`TimeBase` is NTSC-only; the constant is isolated so PAL
  is additive later).
- Sub-frame input timing (movie v2 idea, per risk register).

---

## Decisions (D1–D12)

**D1 — Placement: split timeline tier (`core/src`) + harness tier
(`java-test-fixtures` on core).**
Alternatives: (a) everything in `core/test` — dead end because desktop
live-recording (Phase D2) can't depend on another module's test sources;
(b) a new top-level `harness` Gradle module — heavier than needed, and
its core-facing half would still want package-private seams; (c)
everything in `core/src` — pollutes the TeaVM/web classpath with
`javax.imageio`/`java.nio.file` (TeaVM only compiles reachable code, so
it might limp along, but it's one accidental reference away from a
broken web build). The split keeps TeaVM-hostile code structurally out
of `core/src` while sharing the replay/record model with desktop and web.

**D2 — Frame semantics: `frame()` is 0-based and increments after each
completed `runFrame()`; input edges for frame N apply while
`frame() == N`, i.e. at the boundary before frame N's first tick.**
The recorder samples on the same base, so scripted and recorded
timelines agree at frame 0.
Alternative: apply at the NMI (`frameRenderedListener`) — rejected
because the NMI fires at scanline 241 while `runFrame()` returns at the
scanline wrap; mixing the two gives off-by-⅓-frame ambiguity. The wrap
boundary is what both the harness and the desktop loop step by, so it is
the single definition of "frame" everywhere (input, watches-gated input,
recorder sampling, movie frame numbers).

**D3 — Seconds sugar: `frames = Math.round(seconds × 60.0988)`, NTSC
constant `60.0988` (NESdev: 60.0988139 Hz).**
Alternatives: floor (biases early; surprising for "at 3.5s"), exact
rational 21_477_272 / (4 × 341 × 262 × 3)-style derivation (spurious
precision — our PPU lacks the odd-frame skip, so the emulated rate is
not the hardware rate anyway). `Math.round` of the published NTSC rate
matches author intent ("about 3.5 seconds in") and is documented in
`TimeBase` alongside the caveat that it is sugar, not a wall-clock
contract.

**D4 — Watch engine: a single nullable `BusWriteListener` seam on
`CPUBus` + explicit per-frame polling tier.**
Alternatives: (a) subclass/wrap `Ram` — misses PPU-register, cartridge
and mirror-aware traffic and fights the inlined routing; (b) a
listener-list with iteration per write — needless allocation/indirection
on a hot path (bus *writes* run in the low thousands per frame — the
89k/frame figure is `clock()` calls, not writes — but the convention
here is still zero avoidable indirection); the harness multiplexes its
own watches behind the one listener; (c) polling only — cannot attribute writes to a
PC, which was the single most useful feature of the DIAG hack. Two tiers
are surfaced honestly in the DSL: write watches (exact, mid-frame),
frame polls (boundary, cheap).

**D5 — Movie format: own v1 text format (edge-list, FM2-ordered button
columns), not FM2 or BK2.**
Survey: **FM2** (FCEUX) is line-per-frame text — human-readable, but its
sync contract embeds FCEUX-specific fields (`FDS`, `fourscore`,
savestate anchors, `guid`, rerecord counts) and assumes FCEUX timing
(odd-frame skip, power-on state) that deloNES does not reproduce, so an
FM2 written here would desync there and vice versa — false-compatibility
is worse than none. **BK2** (BizHawk) is a zip of text members — richer
(savestate-anchored movies) but drags in archive handling and an even
larger sync surface. Own-format costs are one ~150-line
parser/serializer and buys exact fit to our determinism pins
(`init-ram`, `loader`, `emu-version`, `rom-sha256`). Keeping FM2's
RLDUTSBA column order and text-ness leaves a straightforward
FM2-import converter as a contained follow-up if ever wanted.

**D6 — PNG encoding via `javax.imageio`, confined to the fixtures tier.**
Alternatives: hand-rolled minimal PNG encoder in `core/src` (pure
`Deflater`) to make capture available to the web build — rejected:
`java.util.zip` support in TeaVM 0.14 is not dependable, and the web
host already has canvas-native ways to snapshot. If browser capture is
ever needed, it belongs in the web host, not core.

**D7 — Determinism pinning: document-and-tag rather than change.**
Zero-filled power-on RAM stays (changing to 0xFF/pattern now would churn
every existing test); the movie header carries `init-ram`/`loader`/
`emu-version` tags so any future change to boot state or timing (e.g.
implementing the odd-frame skip) invalidates old movies loudly at parse
time instead of desyncing silently. A grep-guard test keeps
wall-clock/Random out of core.

**D8 — Assertions throw plain `AssertionError`; golden compares are
exact.**
No JUnit types in fixtures API — desktop tests, future tooling, or a
main() smoke runner can consume the harness without a JUnit-version
coupling (JUnit still catches `AssertionError` natively). The one
sanctioned exception: `org.opentest4j` (the framework-neutral spec jar
JUnit 5 itself builds on) for `TestAbortedException`, so
`fromRealRom` can skip-if-absent without importing JUnit — the
alternative (returning an Optional and keeping `assumeTrue` in test
code) was rejected as boilerplate at every call site. Exact golden
match (no tolerance) because the emulator is deterministic; tolerance
thresholds hide real one-pixel regressions of exactly the kind the
sprite-zero-hit work chased. Failure artifacts (actual + diff PNG) make
exactness debuggable.

**D9 — Desktop recording samples controller state (via seam S4)
immediately before each `runFrame()` call.**
Alternative: record at the $4016 strobe falling edge (what the game
actually latched — the most faithful option) — deferred: it requires a
Controller seam and records at game-defined times, complicating frame
numbering; sampling at the D2 boundary is what replay reproduces
bit-exactly, which is the property TAS needs. Strobe-edge recording is
noted as a v2 refinement if a game with unusual polling shows drift.

**D10 — Census seams: use existing public `readOam(int)`; add one
narrow **public** `PPU.peekPpuBus(int)` (seam S2) for nametable census
rather than exposing `ppuBus()`.**
It must be public, not package-private: fixtures classes live in
`net.lomibao.nes.harness`, and package-private only crosses source sets
within the same package — a `components`-package shim inside
testFixtures was considered and rejected as a visibility trick that
future readers would misread as production code. Public-but-narrow is
consistent with the B6 "registers private, narrow peek/test seams"
direction and avoids handing fixtures a mutable bus reference.

**D11 — Harness drives `runFrame()`, never `runWithCallback` or raw
`tick()` loops, except for explicit power-user escapes
(`h.tickOnce()`, `h.runTicks(n)`).**
Keeps the frame counter authoritative (D2). The tick-level escapes exist
because TempPcTrace's PC-histogram use case is tick-granular; they carry
a documented "frame counter unaffected until the frame completes"
caveat.

**D12 — Real-ROM policy unchanged: skip-if-absent (fixtures throw `org.opentest4j.TestAbortedException` per D8),
sha256-referenced in movies, nothing commercial committed** (goldens
included). Direct continuation of the CartridgeNes2Test /
mapper-plan smoke-ROM policy.
