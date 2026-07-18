# APU implementation plan

**Branch:** `feature/apu` (off master 734a4ed).
**Revision: 2** — incorporates two independent review passes (S1
rewritten: scoped routing + PRG-RAM storage + Mapper000 write fix;
CPU IRQ-granularity defect disclosed in Phase C; DMA parity argument
recorded in D4; perf-gate dead band defined; counter-independence,
reset `$4011 &= 1`, licensing-confirmation, and gate-wording fixes).
**Goal:** a full NTSC 2A03 APU — all five channels, frame counter with
IRQs, DMC with CPU stalls, nonlinear mixer — plus audible output on BOTH
hosts (desktop LWJGL3 `AudioDevice`, web TeaVM WebAudio), validated by
blargg's `apu_test`/`apu_reset` suites, without breaking the nestest
8992/8992 baseline or the headless-harness determinism contract.

This doc is the source of truth. Drive execution from it; don't rely on
chat context. Update checkboxes inline as phases land. Decisions are
numbered D1..D19 in the final section and resolve every open decision
from both research docs.

**Required reading before any phase:**

- `docs/apu-research-emulation.md` — hardware model (§1 is the spec every
  unit test is written against), reference-impl verdicts, test-ROM
  inventory (§5), integration notes (§6), recommended architecture (§7).
- `docs/apu-research-audio-output.md` — desktop/web output paths, POC
  definitions, pacing math, quality ladder, tier placement.
- `docs/headless-harness-plan.md` — determinism audit, tier rules,
  D1–D12 conventions this plan extends.
- `docs/mapper-plan.md` — TDD discipline, JaCoCo gate, blargg-ROM
  residency precedent.

---

## Architecture summary (pinned; details in research §7)

- **CPU-cycle-driven, eager, integer-only.** `APU.clock()` called once
  per CPU cycle from the `phase == 0` branch of `CPUBus.clock()`, **first
  in the branch, before the DMA/CPU turn consumer** — the APU never
  stops, even during DMA stalls, and putting it first makes that
  unconditional. This ordering is part of the movie-determinism contract;
  it does not change after Phase A (Phase C's access-cycle compensation
  works around it, never reorders it).
- Internal time: `int cpuCycle` counter (no `long` — TeaVM), wrapped at
  the frame-counter period (29830 / 37282). **The wrap is a
  frame-counter-local convention:** DMC rate/timer counters, channel
  timers, the fractional sample accumulator, and any absolute-cycle
  bookkeeping run on their own independent counters (countdown timers or
  local accumulators) and must never key off the wrapped `cpuCycle` —
  otherwise the wrap corrupts them. Pulse/noise/DMC timers tick
  on APU-cycle parity (every 2nd call); triangle every call; frame
  counter compared against the 6-entry CPU-cycle tables of research §1.2.
- Class shape: `APU` (rewritten in place, keeps its `CPUBusComponent`
  wiring) owns `PulseChannel ×2` (constructor flag for the sweep
  one's/two's-complement split), `TriangleChannel`, `NoiseChannel`,
  `DmcChannel`, `FrameCounter`; shared units `Envelope`,
  `LengthCounter`, `SweepUnit`, `ApuTimer`. New subpackage
  `net.lomibao.nes.components.apu.*` (mirrors `components.ppu.*`
  precedent). Mixer + downsampler: `ApuMixer` (nonlinear lookup tables
  from day one), `ApuSampleBuffer` (float ring, int head/tail).
- IRQs: level-held flags (`frameIrqFlag && !inhibit`, `dmcIrqFlag`)
  OR'd into `apu.irqAsserted()`, polled in `NesSystem.tick()` beside the
  mapper IRQ. No clear-on-taken — software clears per $4015/$4017
  semantics (research §1.7, §6).
- Output: APU produces samples into the core-owned ring; hosts pull once
  per frame. Emulation speed is never derived from the audio device.

### Class inventory + TeaVM tier placement

| Class | Tier | Constraints |
|---|---|---|
| `components.APU` (rewrite) | `core/src` (TeaVM hot path) | no `long` in `clock()`, no allocation, no boxing, no String ops, no reflection; tables as `static final int[]`/`float[]` |
| `components.apu.PulseChannel/TriangleChannel/NoiseChannel/DmcChannel` | `core/src` hot path | same |
| `components.apu.FrameCounter` | `core/src` hot path | same; int cycle compare against 6-entry tables, no `%` |
| `components.apu.Envelope/LengthCounter/SweepUnit/ApuTimer` | `core/src` hot path | same; plain fields, package-visible for unit tests in the same package |
| `components.apu.ApuMixer` | `core/src` (per-APU-cycle accumulate, per-sample divide) | accumulate path runs per APU cycle (~894k/s), not per sample — float is still fine on TeaVM (JS numbers are native doubles; `long` is the poison, not floating point); no allocation |
| `components.apu.ApuSampleBuffer` | `core/src` | pre-allocated `float[]`, `int` head/tail, mono; sample rate is a constructor/setter parameter (D12) |
| `harness.BlarggRomRunner` | `core/testFixtures` (fixtures tier) | may use `java.nio.file`/JUnit-adjacent helpers per harness D1/D8; never on the TeaVM classpath |
| `desktop.audio.AudioOut` | `desktop` module | wraps `Gdx.audio.newAudioDevice`; lifecycle owned by `EmulatorScreen` |
| `desktop.audio.TonePocLauncher` (Phase 0) | `desktop` module | throwaway-ish, behind a Gradle task/flag |
| `client.WebAudioOut` (+ any custom `@JSBody` bindings) | `html` module | JSO only; no `long` indices in the ring interop |
| Phase-0 web probe | `html` module (`HtmlLauncher`, behind flag) | same |

Nothing audio-host-related ever enters `core/src`; nothing in
`core/src` references LibGDX audio or JSO. The fixtures tier stays off
the TeaVM classpath (harness plan D1).

### Production seam inventory (changes outside the new APU package)

All seams are enumerated here; an implementer wanting a seam not listed
has found a plan bug — surface it, don't slip it in.

| Seam | Change | Phase | Why |
|---|---|---|---|
| **S1** | Cartridge PRG-RAM at `$6000-$7FFF`, read + write, **routing scoped to `$4020-$7FFF` only**. Four coordinated changes — see the spec below this table | A | blargg's $6000 result protocol needs RAM at $6000-$7FFF; today writes are dropped at the bus and reads return 0 (Mapper000 maps nothing below $8000) |
| **S2** | `CPUBus.clock()` `phase == 0` branch: `apu.clock()` first, then DMA/CPU turn | A | the clock hook; one virtual call per CPU cycle, TeaVM-benched at the A gate |
| **S3** | `CPUBus.reset()` calls `apu.reset()` | A | `apu_reset` ROMs test reset state ($4015=0, $4017 retained) |
| **S4** | `NesSystem.tick()`: `if (apu != null && apu.irqAsserted()) cpu.irq();` — level-held, no clear-on-success (unlike the mapper line) | C | frame + DMC IRQ delivery |
| **S5** | `CPUBus.clock()` CPU-turn arbitration: DMC stall check before `dma.isActive()` / `cpu.clock()` (see D4) | D | DMC DMA steals CPU read cycles |
| **S6** | `CPU6502`: one getter exposing the in-flight instruction's **base clocks as of the access (pre-page-cross)** — during `runInstruction()` the value is `i.getClocks()` only; `additionalCycle1 & additionalCycle2` are added *after* the instruction body runs, so an APU access made mid-body cannot see them. Indexed-read page-cross cycles are therefore invisible to compensation — a documented caveat, harmless for blargg's absolute-addressing access pattern; C0 measurements confirm | C | cycle-exact $4015/$4017 timing on an atomic-execute CPU (see Phase C risks) |
| **S7** | Hosts: `EmulatorScreen` gains `AudioOut` lifecycle + post-`runFrame()` drain; `HtmlLauncher` gains `WebAudioOut` + gesture resume | 0 (flagged POC) / E (real) | output path |

$4015/$4017 bus routing is deliberately **not** a seam: current routing
is kept as-is (D3).

### S1 spec (rewritten after review — read before implementing)

The naive version of this seam ("route all `$4020-$FFFF` writes to
`cartridge.cpuBusWrite`") is **wrong twice over** and must not be built:
`Mapper000.cpuMapWrite` returns *mapped* offsets for `$8000-$FFFF`
(`Mapper000.java:25-31`) and `Cartridge.cpuBusWrite` writes them into
`vPRGMemory` — full-range routing would let stray stores **corrupt
NROM PRG-ROM**; and on every banking mapper (MMC1/UxROM/CNROM/AxROM/
MMC3/UNROM-512) it would newly activate `$8000+` register latches for
writes the bus previously dropped — a large movie-visible behavior
change this branch does not own. The actual seam, four parts:

1. **Storage:** `Cartridge` gains a dedicated `byte[] prgRam = new
   byte[8192]` (zero-filled — consistent with the pinned zero-RAM boot
   determinism). `vPRGMemory` is sized exactly to ROM and is never the
   backing store for RAM.
2. **Cartridge branches:** `Cartridge.cpuBusRead` and `cpuBusWrite`
   handle `$6000-$7FFF` via `prgRam[addr & 0x1FFF]` **ahead of the
   mapper call** — mappers only ever produce `vPRGMemory` offsets and
   are not consulted for the PRG-RAM window. (Battery/size-varying
   PRG-RAM from headers is out of scope; flat 8KB always-present is
   enough for the blargg protocol and matches Family-Basic-style NROM.)
3. **Bus routing, scoped:** `CPUBus.write()` routes **`$4020-$7FFF`
   only** to `cartridge.cpuBusWrite`. `$8000-$FFFF` writes keep today's
   behavior (dropped at the bus with the "no device" error log) — full
   write routing to mapper registers is recorded as a future item for a
   mapper-focused branch, where the determinism/movie impact can be
   owned properly. The read path needs **no routing change**
   (`CPUBus.read` already sends `$4020-$FFFF` to `cartridge.cpuBusRead`;
   part 2 makes `$6000-$7FFF` reads return PRG-RAM instead of 0).
4. **Mapper000 write fix (defensive, with RED test):**
   `Mapper000.cpuMapWrite` returns `UNMAPPED` for `$8000-$FFFF` — NROM
   has no registers and its PRG is ROM. Unreachable through the bus
   after part 3's scoping, but `Cartridge.cpuBusWrite` is a public
   surface and the current mapping is a live corruption hazard.

**Logging semantics, stated explicitly:** `Cartridge.cpuBusWrite`
*silently drops* `UNMAPPED` writes (no log — the "no device" line lives
in `CPUBus.write`, not the cartridge). After S1, a genuinely unmapped
`$4020-$5FFF` write therefore reaches the cartridge and is dropped
silently instead of logged. Accepted: add one debug-level (not error)
log in the cartridge's drop path if diagnosis ever needs it; no
behavior depends on it.

---

## Blargg $6000-protocol harness runner (specify once, use in A–E)

`core/testFixtures/net/lomibao/nes/harness/BlarggRomRunner.java`, built
on `NesHarness` — a `NestestTest`-style runner for the 14+ headless
blargg ROMs:

```java
BlarggResult r = BlarggRomRunner.run("test-roms/blargg/apu_test/rom_singles/1-len_ctr.nes");
assertEquals(0, r.code(), r.message());   // message = ROM's own diagnostic text
```

Contract (per research §5):

- Load via `NesHarness.fromResource(...)` (ROMs are checked in — D7).
- Poll `$6000` once per frame via `h.peek` (side-effect-free). Protocol
  is **valid only after** magic `$DE $B0 $61` appears at `$6001-$6003`;
  until then keep running.
- `$80` = running → keep going, frame cap 3600 frames (~60 s) →
  `AssertionError("timed out; last status=...")` on expiry.
- `$81` = needs reset → run ≥ 7 more frames (>100 ms per protocol),
  call `h`'s system `reset()`, continue polling.
- `< $80` = final result code; read the zero-terminated ASCII message
  from `$6004` via `h.peek` and return `{code, message}`.
- Plain `AssertionError` on failure paths, no JUnit types in the runner
  (harness D8). One JUnit test method per ROM in `core/test`, grouped
  per phase (`BlarggApuPhase1Test`, etc.), so gates can name exact
  classes.

Tests of the runner itself (Phase A0): magic gating, timeout, $81 reset
handshake against a synthetic ROM that walks the protocol.

---

## Phase 0 — Audio-output derisk POCs  *(land first; no APU emulation)*

Two spikes, exactly as scoped in `apu-research-audio-output.md` §5.
Both stream a synthesized **440 Hz square at NES-realistic volume**
(square, not sine — it is what pulse channels produce and exposes
buffer seams audibly). They land as minimal committed code behind
flags, **desktop/html modules only, zero `core/src` changes**, and are
allowed to be deleted/absorbed by Phase E. Do not gold-plate them.

### 0-D — Desktop POC (POC-D) — ~half a day

- Gradle task `desktop:audioPoc` → `TonePocLauncher` (or a
  `-Ddelones.audioPoc=true` flag in `EmulatorScreen`).
- `Gdx.audio.newAudioDevice(44100, true)` (mono); each `render()`
  synthesizes N samples of the square (N from a 44100/60.0988 fractional
  accumulator → 733/734 alternating) into a pre-allocated `float[]`,
  `writeSamples`.
- Log `getLatency()`, per-call `writeSamples` wall time (p99), FPS,
  every second for 60+ s.
- Variant runs: default `setAudioConfig` vs 512/3 vs 1024/4; mono vs
  stereo; deliberately skip 5 frames of writes to hear underrun
  recovery.

**Success criteria:** clean continuous tone for 60 s at steady state;
`writeSamples` p99 < ~2 ms; FPS stays 60; underrun test recovers to a
clean tone without app intervention.

**Risks retired:** blocking behavior of `writeSamples` on the render
thread; real latency of default vs tuned configs; mono support;
underrun audibility/recovery; magnitude of the 60.000-vs-60.0988 drift
problem (informs D13's DRC follow-up).

**Kill/pivot:** none expected — desktop is the low-risk leg. If
`writeSamples` blocking is somehow unusable, fall back to a dedicated
feeder thread draining the same ring (desktop-only; core unaffected).

### 0-W — Web POC (POC-W) — 1–2 days; **the actual derisk**

- Behind a probe flag in `HtmlLauncher` (web-phase0 probe precedent):
  `org.teavm.jso.webaudio` chain `AudioContext.create()` →
  `createScriptProcessor(2048, 0, 1)` → `GainNode` → `destination`.
- `resume()` wired into the existing index.html canvas-mousedown /
  ROM-picker click handlers; log `getState()` transitions.
- `onaudioprocess` copies from an unsynchronized Java `float[]` ring
  into the output `Float32Array`; the rAF loop produces the square at
  `ctx.getSampleRate()`-derived per-frame counts. Log ring occupancy
  min/max per second, and the actual `ctx.sampleRate`.
- Soak 60+ s **with the DK ROM emulating + rendering at 60 FPS
  simultaneously** — main-thread contention is the whole question.
- If SPN misbehaves (callback starvation, binding gaps at runtime on
  TeaVM 0.14): fall back to the AudioBufferSourceNode queue (one
  `createBuffer`/`copyToChannel`/`start(scheduledTime)` per video frame
  with an accumulated schedule clock), same success criteria. Any
  missing 2016-era JSO surface gets a small custom `@JSClass`/`@JSBody`
  binding (proven pattern in this repo).

**Success criteria:** tone starts only after the click (autoplay gate
proven); clean tone for 60 s while the emulator renders at 60 FPS; ring
occupancy bounded (no monotonic drain/growth); works in Chrome + one
other engine; actual `ctx.sampleRate` recorded for D12.

**Risks retired:** `newAudioDevice`-throws routed around at runtime;
2016-era jso-apis WebAudio bindings function on TeaVM 0.14 (or custom
binding cost known); autoplay-resume via existing gestures; main-thread
contention at a given SPN buffer size; device sample-rate variability;
achievable latency floor.

**Kill/pivot:** if neither SPN nor buffer-queueing holds a clean tone
alongside 60 FPS emulation in Chrome, pivot to AudioWorklet +
hand-written JS worklet + postMessage ring — costed "days, not hours";
in that case Phase 0 must capture occupancy/callback-timing logs
sufficient to write that plan, and Phase E's web half re-plans before
starting. Phases A–D are **not** blocked by this pivot (they are
output-independent).

**Gate:** both POC success-criteria checklists recorded in a short
`docs/apu-poc-findings.md` (numbers, chosen configs, chosen web
mechanism for D11/D17); `core:check` untouched and green.

---

## Phase A — Length counters, $4015, frame-counter skeleton, blargg runner

The first CPU-visible APU behavior. Replaces the register-echo stub.

Deliverables:

- **A0 — infra.** Seam S1 per its four-part spec above (PRG-RAM
  storage, cartridge branches, $4020-$7FFF-scoped routing, Mapper000
  write fix), `BlarggRomRunner` + its own tests, blargg ROM bundle
  checked in under `core/src/test/resources/test-roms/blargg/`
  (`apu_test/rom_singles/`, `apu_reset/`) with a CREDITS.md entry —
  **precondition: explicit user confirmation of the redistribution
  stance (D7) before the ROM-commit lands** — ✅ **CONFIRMED by Derek
  2026-07-18** ("I approve committing test roms to repo"); the
  skip-if-absent fallback is moot. JaCoCo gate extended to
  `net.lomibao.nes.components.apu.*` at ≥90% line (mapper-plan
  precedent).
- **A1 — skeleton.** `APU` rewrite: register decode for $4000-$4017
  (writes stored into real channel/unit state, not a byte array),
  `clock()` + int cycle counter, seams S2 + S3. `FrameCounter` with the
  6-entry NTSC CPU-cycle tables (both modes), quarter/half-frame
  dispatch, **$4017 bit-7 immediate quarter+half clock**, bit-6
  inhibit-clears-flag. The 3/4-cycle write delay and the 3-cycle IRQ-set
  window are Phase C (a simple "flag set at 29828" single-cycle model is
  fine here). Power-up `$4017 = $00` — 4-step, IRQ-enabled — modeled
  faithfully from day one (D8); the flag is set/read/cleared correctly
  but **not yet delivered** to the CPU (delivery is Phase C, so no game
  behavior changes beyond $4015 visibility).
- **A2 — `LengthCounter`** (32-entry table, halt, clock-on-half-frame,
  blocked-while-disabled) instantiated ×4 (pulse ×2 / triangle / noise
  — channels exist as length-counter-only shells).
- **A3 — $4015 semantics.** Write: enables, clear-length-on-disable,
  clears DMC IRQ flag (flag exists, always 0 until D). Read:
  `IF-D NT21` status bits, read-clears-frame-IRQ (not DMC), bit 5 = 0
  (open bus is a non-goal, D10). Same-cycle set/read race per §1.7.
- **A4 — reset/power-up.** `apu.reset()`: acts as $4015=$00, retains
  last $4017, noise LFSR := 1, triangle phase 0.

TDD sub-stages & floors (RED → GREEN → REFACTOR → SUITE per mapper-plan):

| Sub | Tests | RED assertions |
|---|---|---|
| A0 | 12 | runner magic/timeout/$81-reset vs synthetic ROM; $6000-$7FFF write→read round-trip through the bus (both directions of the read fix); PRG-RAM zero-filled at boot; **RED: `$8000` write via `Cartridge.cpuBusWrite` does NOT alter `vPRGMemory` (Mapper000 cpuMapWrite→UNMAPPED fix)**; `$8000+` bus writes still dropped at the bus (routing scoped); `$4020-$5FFF` write reaches cartridge and is dropped without corrupting anything |
| A1 | 10 | frame-counter event cycles both modes (table-driven against §1.2); $4017 bit-7 immediate clock; bit-6 clears flag; mode-1 never sets flag; wrap at 29830/37282 |
| A2 | 10 | all 32 table entries; halt gates clocking; disabled channel blocks reload; half-frame cadence |
| A3 | 10 | write enables/clears; read bit mapping; read-clears-frame-flag; same-cycle race; $4017 write does NOT clear frame flag |
| A4 | 6 | reset semantics incl. $4017 retention |

**Gates:**

- ✅ `core:check` green (tests + JaCoCo ≥90% on `components.apu.*`)
- ✅ **`NestestTest` 8992/8992.** nestest's golden log comes from
  Nintendulator, which has a real APU — correct $4015 semantics should
  match *better* than the stub. Any diff is a bus-wiring/semantics bug
  to fix in-phase, not to waive.
- ✅ Blargg: `1-len_ctr`, `2-len_table`, `3-irq_flag`,
  `apu_reset/{4015_cleared, len_ctrs_enabled, irq_flag_cleared}` all
  pass via the runner. (`works_immediately` RELOCATED to the Phase D
  gate set — see the plan-bug note below and the Phase D gates.)
  - **PLAN BUG, surfaced 2026-07-18 (Phase A execution):**
    `works_immediately` is unpassable in Phase A as specified. Its ROM
    source (log check #1) requires `$4015` to read `$1F` — bit 4 = DMC
    bytes-remaining > 0, primed by `$4010=$8F`/`$4013=1` — ~6000 cycles
    after power, and check #2 requires `$8F` (bit 7 = DMC IRQ flag set
    when the 17-byte sample at rate 15 completes). A3's own spec pins
    bits 4/7 to "always 0 until D". The other six Phase A ROMs pass;
    `works_immediately` is committed as a `@Disabled` test citing this
    note. **ADJUDICATED 2026-07-18 (Phase A review round 1, both
    reviewers unanimous, each corroborating the analysis against the
    ROM binary): moved to the Phase D gate set.** Pulling a minimal DMC
    counter forward would violate A3's bit-4/7 contract, duplicate
    D1-owned work, and add movie-visible DMC state a phase early with
    no gate coverage. Phase D's definition-of-done inherits the ROM and
    re-enables the test. No code was improvised around it.
- ✅ Web build: avg `runFrame` ms measured against the pre-APU baseline
  (record the baseline before A1; this benches seam S2's per-CPU-cycle
  call), judged by the **D9 perf band**: ≤5% = pass; **5–10% = advisory
  — the phase may land, but the regression is logged in this doc,
  investigated, and cheap micro-fixes applied first**; >10% sustained
  across two consecutive gates = hard trigger for the F3 catch-up
  refactor. This band applies verbatim to every later phase's
  "runFrame within the D9 band" gate line.
- ✅ Harness determinism: replay-twice framebuffer-hash proof (harness
  D3a) re-run green; `MovieFormat.EMU_VERSION` bumped **1 → 2** here
  (D2) — after the bump, v1 movies hard-fail at parse, so the committed
  movie ITs **must be regenerated in-phase** with a commit note
  (`$4015` reads are now live — movie-visible).

## Phase B — Envelope, sweep, all four tone channels complete

Pure unit-test phase — **no automated ROM exists for envelope or sweep**
(research §5 gap); §1.3–§1.5 of the research doc is the spec.
Adds nothing CPU-readable beyond Phase A ⇒ not movie-visible.

Deliverables:

- **B1 — `Envelope`** (shared pulse/noise): start flag, divider, decay,
  loop, constant-volume passthrough with decay still running.
- **B2 — `SweepUnit`**: divider+reload, target-period computation
  **recomputed continuously** (mutes even when disabled), one's-complement
  negate on pulse 1 vs two's-complement on pulse 2, target > $7FF and
  t < 8 muting.
- **B3 — `PulseChannel` complete**: duty tables (§1.3 playback order),
  11-bit timer at APU-cycle rate, $4003/$4007 side effects (length load,
  **phase reset**, envelope start, timer countdown NOT reset), muting
  precedence.
- **B4 — `TriangleChannel`**: 32-step sequence, CPU-rate timer, linear
  counter + reload-flag/control-flag dance, halt-freezes-phase,
  ultrasonic guard (skip stepping when t < 2).
- **B5 — `NoiseChannel`**: 15-bit LFSR both feedback modes, period
  table, $400F side effects.

| Sub | Tests | Notes |
|---|---|---|
| B1 | 8 | start/reload, divider wrap, loop vs no-loop, constant-volume with live decay |
| B2 | 12 | complement split (the audible p1/p2 difference), continuous muting while disabled, reload semantics, shift-0 no-update |
| B3 | 10 | all four duty sequences, phase-reset on $4003, mute precedence, f = CPU/(16·(t+1)) spot checks |
| B4 | 8 | linear counter reload/control interplay, halt freeze (no click), sequence symmetry, ultrasonic guard |
| B5 | 8 | LFSR sequences both modes (93/31-step loop check), silencing conditions |

**Gates:** `core:check` green (≥90% on new classes); `NestestTest`
8992/8992; blargg Phase A set still green; web runFrame within the D9
perf band (the
channels now do real work every cycle — this is the phase most likely
to arm D9).

> **D9 WEB-BENCH WAIVER (Phase B close, 2026-07-18, adjudicated per the
> review round-1 finding):** no in-repo web bench exists and headless
> Chrome cannot create the WebGL context the web build needs (verified:
> `pixelStorei` on null under `--headless=new`, SwiftShader included).
> Substitute measured: interleaved JVM proxy (best-of-5 x 600 frames,
> nestest, this machine) — pre-APU 0.816 ms/frame vs Phase B 0.852
> ms/frame = **+4.4%**, within the <=5% pass band; run noise +-10%. The
> proxy cannot capture TeaVM-specific costs, so the REAL web number
> (console `runFrame=` line, 60s soak, vs a pre-A1 build) is OWED at the
> user's next browser session (bundled with the Phase 0 listening
> checklist) and MUST be recorded here before the Phase E gate; if it
> lands in the 5-10% band it is advisory-recorded per D9, >10% arms F3.

## Phase C — Cycle-exact frame counter + IRQ delivery

The hard-timing phase, and the first interrupt-timing change.

**Central risk, stated up front — TWO known CPU timing defects, neither
a new discovery:**

1. *Access timing:* `CPU6502` executes each instruction atomically on
   its first `clock()` and idles the remaining cycles — bus accesses
   land at instruction-start, up to ~5 cycles early vs hardware. Blargg
   `4-jitter` / `5-len_timing` / `6-irq_flag_timing` measure $4015
   access timing to ±1 cycle. Mitigation is **access-cycle
   compensation** (sub-stage C2), not a CPU rewrite.
2. *IRQ granularity:* `cpu.irq()` as polled from `NesSystem.tick()` can
   fire **mid-instruction** — it clobbers the in-flight instruction's
   remaining-cycle count with `cycles = 7` instead of completing the
   instruction first (a real 6502 takes interrupts at instruction
   boundaries after its per-cycle poll). This defect exists today for
   the mapper IRQ line and is independent of defect 1. It is harmless
   for the C gate ROMs (they read flags via $4015 with IRQs masked or
   handler-timing-insensitive), and it is precisely why 2005-suite
   `08.irq_timing` is stretch, not gate. An implementer hitting weird
   IRQ-relative cycle counts in C0/C3 should recognize this as the
   known cause, not file it as a new APU bug.

Deliverables:

- **C0 — spike (timeboxed 1 day).** Run `4-jitter`, `5-len_timing`,
  `6-irq_flag_timing` against the Phase B APU; record exact failure
  deltas. This calibrates C2 and is committed as a findings note in this
  doc (checkbox + numbers).

  > **☑ C0 FINDINGS (measured 2026-07-18, Phase B APU @ 57f8266):**
  >
  > | ROM | Result | ROM diagnostic |
  > |---|---|---|
  > | `4-jitter` | **FAIL #2** | "Frame irq is set too soon" |
  > | `5-len_timing` | **FAIL #2** | "Channel: 0 / First length of mode 0 is too soon" |
  > | `6-irq_flag_timing` | **FAIL #2** | "Flag first set too soon" |
  > | `apu_reset/4017_timing` | PASS | already green pre-C1 (boot offset 0 + immediate $4017 reset is within this ROM's tolerance) |
  > | `apu_reset/4017_written` | PASS | |
  >
  > Access-cycle measurement (directed spike, minimal CPU+RAM+APU
  > system, S1 write-listener probe): a 4-cycle `STA $4017` lands its
  > bus write at the instruction's **first** cycle (cpu clockCount 9 =
  > dispatch cycle after 7-cycle reset + 2-cycle `LDA #imm`); hardware
  > lands it on the **final** cycle (start+3). Measured earliness =
  > `baseClocks − 1` = 3 for 4-cycle absolute stores/loads — exactly
  > the S6 compensation formula. Poll-loop spike confirms the frame
  > IRQ flag becomes CPU-visible at frame-counter cycles 29828–30 with
  > the current zero boot offset.
  >
  > Failure-direction analysis: all three failures are sign-consistent
  > single-digit "too soon" — the $4017-write path runs ~6–7 CPU cycles
  > early (≈3 missing C1 write-delay + 3 early store access), partially
  > offset by $4015 reads observing 3 cycles early. C1 + C2 close
  > deltas of exactly this shape; **no kill-pivot indication at C0**.
  > Note for C1: `4017_timing` passes *today* with boot offset 0 —
  > adding the C1 write delay shifts the sequence and the boot offset
  > must be recalibrated against it.
- **C1 — frame-counter exactness.** $4017 write-delay counter (3 or 4
  CPU cycles by write parity); the 3-consecutive-cycle IRQ-flag window
  (29828/29829/29830) incl. re-set after a mid-window $4015 read;
  boot-time frame-counter offset ("as if $4017 written 9–12 cycles
  before the first instruction", pinned to whatever constant makes
  `apu_reset/4017_timing` pass — document the chosen constant).

  > **☑ C1 BOOT OFFSET (calibrated at C2 close):**
  > `APU.FRAME_COUNTER_BOOT_OFFSET = 0`. Our reset path applies the
  > offset while the CPU still burns its 7 reset cycles (the APU clocks
  > through them), so the effective sequencer position at the first
  > instruction is **7** — inside the §1.9 "9–12 cycles before the
  > first instruction, minus the 3/4-cycle write delay" ≈ 5–9 window.
  > `apu_reset/4017_timing` passes with this constant both before and
  > after the C1 write delay landed.
  >
  > **☑ C2 MODEL CORRECTION (found by `6-irq_flag_timing` #5):** the
  > research §1.7 "same-cycle $4015 read returns 1 without clearing"
  > race is the wrong mechanism — with it, the flag's last set cycle
  > reads one too late ("flag last set too late"). Hardware truth: the
  > read always clears the register; the *remaining* window cycles
  > (29829/29830) re-assert it, which is what makes mid-window reads
  > appear not to clear. A read on the last set cycle (29830) clears
  > for good. `FrameCounter.clearFrameIrqFlagOnRead()` now always
  > clears; the window re-assertion carries the observable semantics.
- **C2 — access-cycle compensation (seam S6).** When the CPU
  reads/writes $4015/$4017 during an atomically-executed instruction,
  the APU services the access as-of
  `cpuCycle + (instructionBaseClocks − 1)` — base clocks per the S6
  getter definition: pre-page-cross, since the additional cycles are
  added after the instruction body and are unobservable mid-body
  (reads/writes occur on the final cycle of read/store instructions on
  a 6502): the APU runs its
  frame counter forward to that cycle for the access, then the eager
  per-cycle `clock()` calls that follow become no-ops until real time
  catches up (a small `syncedTo` marker inside `FrameCounter` — this is
  a *contained, partial* adoption of the Mesen catch-up idea, only where
  timing demands it). RMW instructions to APU space are rare enough to
  pin the same rule and note the approximation.
- **C3 — IRQ delivery (seam S4).** `apu.irqAsserted()` polled in
  `NesSystem.tick()`; level-held until software clears the flag. Frame
  IRQ now actually reaches games (power-up $4017=$00 means games that
  never write $40 must service or mask it — that is hardware truth, D8).

| Sub | Tests | Notes |
|---|---|---|
| C1 | 10 | write-delay by parity; 3-cycle flag window; read-in-window re-set; boot offset |
| C2 | 6 | compensated $4015 read lands on the correct side of a frame edge for 2/3/4-cycle instructions; eager clocks after a compensated access don't double-clock |
| C3 | 6 | irqAsserted level semantics; masked-then-unmasked delivery; no clear-on-taken; DMC/frame flags independent |

**Gates:**

- ✅ `core:check` green; `NestestTest` 8992/8992 (the automated run ends
  ~26.5k cycles in, before the first frame-IRQ window at ~29.8k, so no
  frame IRQ can fire during the golden log; its log is from an
  APU-bearing emulator; a diff means the boot-offset or delivery wiring
  is wrong — see the determinism section for the full CYC argument).
- ✅ Blargg: `4-jitter`, `5-len_timing`, `6-irq_flag_timing`,
  `apu_reset/{4017_timing, 4017_written}` pass.
  *Stretch (non-gate):* 2005 suite `08.irq_timing` / `09.reset_timing` /
  `10.len_halt_timing` / `11.len_reload_timing` via framebuffer-hash
  assertions (screen-reporting ROMs).
- ✅ Determinism proofs re-run; committed movie ITs regenerated (frame
  IRQ delivery is movie-visible). EMU_VERSION stays 2 (D2 — one bump
  per master-visible generation; this branch is one generation).
- ✅ Web runFrame within the D9 perf band (like every phase gate —
  round-2 review caught this line missing from C alone).

> **☑ PHASE C GATES (closed 2026-07-18):** `core:check` green (1003
> tests, 0 failed); `NestestTest` 8992/8992; blargg `4-jitter`,
> `5-len_timing`, `6-irq_flag_timing`, `apu_reset/4017_timing`,
> `apu_reset/4017_written` all PASS (re-enabled in
> `BlarggApuPhaseCTest`); determinism proofs re-run green; committed
> movie ITs (nestest + Micro Mages boot) replay unmodified — the
> input-only `.dmov` artifacts stay valid, so no regeneration was
> needed; golden `nestest-frame60.png` exact-match unchanged;
> EMU_VERSION stays 2 (D2). **Kill-pivot NOT taken** — the atomic CPU
> + C2 compensation satisfied all three timing ROMs.
>
> **D9 (advisory per the Phase B waiver):** interleaved JVM proxy,
> best-of (3×5 runs) × 600 frames, nestest, same machine/method:
> pre-APU base `734a4ed` 1.512 ms/frame vs Phase C 1.548 ms/frame =
> **+2.4%** cumulative (A+B+C), within the ≤5% band; run noise ±5%
> observed (one CUR round beat two PRE rounds). The REAL web number
> remains OWED before the Phase E gate per the Phase B waiver.
- **Kill-pivot:** if C0+C2 show the atomic CPU cannot satisfy 4/5/6
  without micro-stepped execution, descope those three ROMs to a
  follow-up "cycle-stepped CPU" project, keep C1/C3 (flag semantics +
  delivery, `3-irq_flag`-level accuracy), gate on
  `apu_reset/4017_written` only, and record the descope here. In this
  branch of the pivot the C1 boot-offset constant loses its calibrating
  gate ROM (`4017_timing`): pin it instead to the NESdev-documented
  midpoint (research §1.9's 9–12-cycle range — pick and document one
  value), and **keep running `4017_timing` informationally**
  (non-gating, result logged in the runner output) so the constant has
  a measured error bar and the follow-up project inherits a baseline.
  Do not attempt a CPU rewrite inside this branch.

> **C GATE-EVIDENCE NOTE (review round 1, mutation-verified):** the five
> Phase C blargg ROMs do NOT pin the 3-vs-4 write-delay parity direction,
> the 29829 re-assertion, or the C2 odd-shift parity flip — mutations of
> each pass every ROM. Those mechanics rest on the NESdev citation
> ("during an APU cycle -> 3, between -> 4", wiki verbatim) plus the
> dedicated unit tests, which mutation testing confirmed DO kill each
> mutant. Absolute phase alignment to hardware is unfalsifiable by the
> current gates. Also corrected in this round: the boot-offset accounting
> is +8, not +7 (APU-before-CPU ordering in the dispatch turn).

## Phase D — DMC + DMC DMA stalls

The last correctness phase and the second interrupt/stall-timing change.

Deliverables:

- **D1 — `DmcChannel` functional.** Rate table, output unit (7-bit delta
  counter, ±2 clamp), shift register + 8-clock refill, memory reader
  ($C000+A×64, L×16+1, $FFFF→$8000 wrap), loop, DMC IRQ on last-byte
  fetch, $4011 direct-load, $4015 bit-4/bit-7 semantics + write-clears-
  DMC-IRQ + restart/stop rules, and reset behavior: **`$4011 &= 1` on
  reset** (research §1.9 — kept in scope here rather than in D10's
  non-goals because `apu_reset` ROMs can check it; extends Phase A4's
  `apu.reset()`). Fetches performed via `cpuBus.read()`
  **without stalls yet** (byte magically arrives) — this alone passes
  `7-dmc_basics` and `8-dmc_rates`.
- **D2 — CPU stall (seam S5, mechanism per D4).** Stall state machine
  lives inside `DmcChannel`/`APU` (`apu.dmcStallPending()`); the
  `phase == 0` branch checks it **before** `dma.isActive()` (DMC wins;
  OAM burst pauses for the duration — cycle-exact collision counts are a
  non-goal, D10). Flat **4-cycle** stall for reload fetches, **3-cycle**
  for $4015-triggered start fetches; fetch happens on the last stall
  cycle. No get/put alignment, no halt-only-on-read-cycles, no
  double-read glitch (all D10 non-goals; `dmc_dma_during_read4` and
  `sprdma_and_dmc_dma` are explicitly out).
- **D3 — screen-reporting DMC tier (best-effort gate).**
  `dmc_tests/{status, status_irq, buffer_retained}` via harness
  framebuffer-hash assertions. `latency.nes` is stretch (sensitive to
  stall placement fine detail).

  > **☑ D3 PLAN BUG, surfaced 2026-07-18 (Phase D execution):** the
  > `dmc_tests/` ROMs are **not screen-reporting** — full disassembly
  > (all four share one shell; 16KB PRG, CHR-RAM, reset at $E0C0) shows
  > they disable rendering ($2000/$2001 = 0), never write CHR or
  > nametables, and report **by ear**: init $4010/$4012/$4013 from a
  > per-ROM parameter table, click $4011, run the test body, then park
  > forever in a pulse-1 beep loop ($4000=$82, $4002=$01, $4003=$09,
  > JMP-self). A framebuffer hash is identically black for pass and
  > fail — the planned assertion tier is vacuous, not merely hard.
  > **Best-effort substitute (implemented as `BlarggDmcTestsIT`):**
  > capture every $4000-$401F write through the S1 bus-write listener
  > (with CPU cycle + PC) and assert each ROM's observable protocol —
  > `status`: the BIT-$4015 poll loop exits one 17-byte rate-0
  > sample-duration (~55-58k cycles; window 45-75k) after $4015=$10,
  > pinning bit 4's set-while-playing/clear-on-last-fetch lifecycle;
  > `status_irq`: the ROM's IRQ handler (sole writer of $4010=$00) runs
  > one sample-duration after start — proof the DMC IRQ was raised on
  > the last-byte fetch and DELIVERED; `buffer_retained`: back-to-back
  > enable/disable leaves the fetched byte to play out (reader stopped,
  > buffer drained, channel silenced); `latency` (stretch): all four
  > timed $4011/$4015 click iterations complete. All four must reach
  > their terminal beep loop (PC parked at the JMP-self). **Result: all
  > four PASS, including the latency stretch.** The by-ear verdict
  > itself remains manual and is folded into Phase E's audible smoke.

| Sub | Tests | Notes |
|---|---|---|
| D1 | 15 | delta clamp both ends; refill cadence; address wrap; length reload vs IRQ paths; IRQ-on-fetch (not on-drain); $4015 restart with bytes==0; stop-after-buffered-byte; reset applies `$4011 &= 1` |
| D2 | 8 | stall cycle counts 4/3; CPU suspended exactly N CPU-turns; APU keeps clocking through the stall; DMC-before-OAM arbitration order; **"OAM burst resumes with intact get/put alternation after a mid-burst DMC stall" — the parity tripwire from D4** |
| D3 | 2–4 | framebuffer-hash ITs |

**Gates:** `core:check` green; **`NestestTest` 8992/8992 — the critical
one** (DMC stalls add CPU cycles, but nestest plays no DMC samples, so
any CYC drift = arbitration bug); blargg `7-dmc_basics`, `8-dmc_rates`,
`apu_reset/works_immediately` (relocated from Phase A — re-enable
`BlarggApuPhase1Test.apuReset_works_immediately`)
pass; `dmc_tests/{status,status_irq,buffer_retained}` pass;
determinism proofs re-run + committed movies regenerated (stalls are
movie-visible); web runFrame within the D9 perf band.

> **☑ PHASE D GATES (closed 2026-07-18):** `core:check` green (1044
> tests, 0 failed, 1 pre-existing MMC3 real-ROM skip); **`NestestTest`
> 8992/8992 with zero CYC drift** — nestest plays no DMC ⇒ no stalls ⇒
> the per-line CYC assertions pass untouched, backed by the D2 unit
> guard "1000 idle CPU turns → exactly 1000 CPU clocks"; blargg
> `7-dmc_basics`, `8-dmc_rates` PASS (`BlarggApuPhaseDTest`);
> `apu_reset` full set PASS **including `works_immediately`**
> (`BlarggApuPhase1Test.apuReset_works_immediately` re-enabled per the
> Phase A adjudication — the relocated gate is honored); D3 tier
> recorded above — `dmc_tests/{status,status_irq,buffer_retained}` PASS
> plus the `latency` stretch, via the behavioral substitute (the ROMs
> are audio-reporting; see the D3 plan-bug note); the D2 parity
> tripwire ("OAM burst resumes with intact get/put alternation after a
> mid-burst DMC stall") is green with byte-exact OAM content.
> Determinism proofs re-run green (replay-twice hash identity,
> record/serialize/parse/replay, no-wall-clock grep); committed movie
> ITs (nestest + Micro Mages boot) replay unmodified — neither ROM
> triggers DMC in the recorded segments, so the input-only `.dmov`
> artifacts stay valid and no regeneration was needed; EMU_VERSION
> stays 2 (D2 one-generation rule).
>
> **D9 (advisory per the Phase B waiver):** interleaved JVM proxy,
> best-of (3×5 runs) × 600 frames, nestest, same machine/method.
> Initial Phase D measurement: pre-APU base `734a4ed` 1.5573 vs Phase D
> 1.6458 ms/frame = **+5.7%** cumulative — inside the 5-10% advisory
> band, so the D9-mandated cheap micro-fix was applied: the per-APU-
> cycle DMC reader poll (`needsSampleByte()`) now runs only on DMC
> output-clock cycles (`clockTimer()` returns whether it fired — the
> only clock-driven trigger; $4015 starts arm at the write site).
> Post-fix: pre-APU 1.5311 vs Phase D 1.5851 ms/frame = **+3.5%**
> cumulative best-of (+4.4% by medians), within the ≤5% pass band;
> inter-round noise ±4-5% observed. The REAL web number remains OWED
> before the Phase E gate per the Phase B waiver.

## Phase E — Mixer, sample buffer, audible output on both hosts

Depends on A–B for channel outputs; benefits from C–D landing first so
the single EMU_VERSION generation is closed before audio ships (D1).
Consumes Phase 0's POC findings; POC flag code is absorbed or deleted.

Deliverables:

- **E1 — `ApuMixer` + `ApuSampleBuffer`** (core). Nonlinear lookup
  tables (`pulse_table[31]`, `tnd_table[203]`, §1.8); **box-average
  downsampler** (D5/D14): accumulate the mixed output every CPU cycle,
  emit one sample per ~40.58 cycles via a fractional accumulator
  (float/fixed-point int, no `long`, no division in the loop);
  first-order 90 Hz HP + 14 kHz LP IIRs on the sample stream (the HP
  doubles as DC-blocker). Sample rate is a parameter (default 44100;
  host may set before first frame — D12). Per-frame sample counts
  alternate (733/734 at 44.1k) — never a hardcoded 735.
  The channel→mixer boundary is kept delta-friendly (channels report
  output level changes; the box filter consumes them) so a blip_buf
  port later is a mixer-only swap (D5).
- **E2 — desktop `AudioOut`** (`desktop.audio`). Wraps
  `Gdx.audio.newAudioDevice(44100, mono)`; created in
  `EmulatorScreen.show()`, drained **immediately after `runFrame()` in
  `render()`** (D16), disposed in `dispose()`; on `togglePause()` stop
  writing and let the device drain (D18). `setAudioConfig` per POC-D
  numbers (D17). Video-driven pacing with slack buffer; drift accepted
  v1 (D6/D13).
- **E3 — web `WebAudioOut`** (`html`). The POC-W-chosen mechanism (D11)
  productionized: created in `create()`, resumed on first gesture,
  produced-into from `renderEmulatorFrame()` after `runFrame()`; gain
  mute + ring clear on pause/`swapRom()`/tab-background (D18). Sample
  rate = `ctx.getSampleRate()` fed into E1's parameter (D12).
- **E4 — headless audio determinism.** Harness-tier proof: replay the
  same movie twice → FNV-1a hash of the emitted sample stream identical
  (extends harness D3); a unit test that a scripted pulse-1 register
  sequence yields the expected fundamental (zero-crossing count over N
  samples) — the "440 Hz square from registers" test from the audio
  research §6.

| Sub | Tests | Notes |
|---|---|---|
| E1 | 12 | table values spot-checked against §1.8 formulas; [0]==0; box-average sample counts alternate correctly over 1000 frames (no drift vs cycle count); IIR stability; ring wrap/overflow policy (overwrite-oldest, count dropped) |
| E2/E3 | 4 | host-side smoke where testable headlessly (ring accounting); the rest is manual |
| E4 | 3 | twice-replay hash identity; register→frequency; silence when all channels disabled (post-HP) |

**Gates:** `core:check` green; `NestestTest` 8992/8992; all prior
blargg gates green; **Phase B's owed D9 web-bench number on file (see
the Phase B waiver block — the E gate cannot close without it)**; **manual audible smoke:** DK on desktop and web
sounds recognizably correct (jump/walk effects, no sustained crackle
over 5 minutes); `apu_mixer/*.nes` by-ear check is explicitly
informational, not a gate (D10); web runFrame + audio callback hold
60 FPS in Chrome.

## Phase F — Quality follow-ups *(scoped here, separate effort)*

Not part of this branch's definition of done; recorded so review passes
don't relitigate:

- **F1 — blip_buf port** (~300 lines integer math, TeaVM-safe) as a
  drop-in mixer swap behind the E1 delta boundary (D5 rung 3).
- **F2 — dynamic rate control** on both hosts (±0.5% resample-ratio
  nudge from ring occupancy; desktop fill inferred, web computed) —
  never feeds back into emulation content (D13).
- **F3 — Mesen-style lazy catch-up** for the whole APU, only if D9's
  trigger fires.

---

## Determinism / TAS section

**When timing becomes movie-visible:**

| Phase | CPU-visible change | Movie-visible? |
|---|---|---|
| 0 | none (host-only, flagged) | no |
| A | $4015 reads return live status (games that poll $4015 diverge); S1 also makes $6000-$7FFF PRG-RAM round-trip for ALL carts (previously dropped/0 — independently CPU-visible to any game touching that window) | **yes — first movie-visible phase** |
| B | nothing new readable | no |
| C | frame IRQ delivered; $4015 read timing cycle-exact | **yes** |
| D | DMC stalls add CPU cycles; DMC IRQ; $4015 bits 4/7 live | **yes** |
| E | audio samples (observation-only stream) | no |

**EMU_VERSION strategy (D2):** one bump, **1 → 2, landed in Phase A**
(the first movie-visible phase), constant for the whole branch. Phase
PRs land against `feature/apu` (mapper/harness precedent), so master
sees exactly one version transition at branch merge — movies recorded
on master v1 are invalidated loudly exactly once. To be plain: after
the Phase A bump, **v1 movies cannot "pass" — `MovieFormat` parse
hard-rejects the version mismatch** — so the committed movie ITs
(nestest movie, Micro Mages boot) **must be regenerated in Phase A**,
and regenerated again at the C and D gates if the timing changes there
desync them, each with a commit note; that intra-branch churn is why
per-phase bumps buy nothing.

**Proofs to re-verify per phase:** (a) harness D3a replay-twice
framebuffer-hash identity — at A, C, D gates (the phases that touch
CPU-visible state); (b) committed movie ITs regenerated where required
(mandatory at A per the version bump; at C/D on desync) and green; (c)
the no-wall-clock/no-Random grep guard automatically covers the new
`components.apu` package (it scans `core/src/net/lomibao/nes`); (d)
Phase E adds the audio-stream hash proof (E4). Audio downsampler state
(fractional accumulator, IIR state) is reset on `reset()`/ROM load and
is a pure function of the tick stream — it is included in the E4 audio
hash but **never** part of the movie format or the framebuffer proofs
(audio is observation-only).

**nestest CYC safety argument:** the APU adds zero CPU cycles except
DMC stalls (Phase D), and nestest plays no DMC samples ⇒ no stalls ⇒
CYC column unaffected. Frame IRQ: the automated `$C000` run completes
in **~26.5k CPU cycles — before the first frame-IRQ window at ~29.8k
cycles ever arrives** — so no frame IRQ can fire at any point during
the golden log, regardless of I-flag state (a stronger argument than
"nestest masks IRQs": the window is simply never reached). Empirical
anchor: the current no-IRQ stub already passes 8992/8992, and the
run-length argument says a correct frame counter changes nothing in
that window. The flag's $4015 visibility matches the
Nintendulator-derived golden log (Nintendulator has a real APU).
Conclusion: 8992/8992 must hold at **every** phase gate; any diff is by
definition a bus-wiring, $4015-semantics, or stall-arbitration bug —
never an accepted delta.

---

## Parallelism map

```
0-D ─┐
0-W ─┴─(findings doc)──►  A0 → A1 → A2 → A3 → A4      (sequential)
                              │
                              ▼
                          B1..B5      (single agent; B3 needs B1+B2)
                              │
                              ▼
                          C0 → C1 → C2 → C3
                              │
                              ▼
                          D1 → D2 → D3
                              │
                              ▼
                          E1 → (E2 ∥ E3) → E4
```

0-D and 0-W run in parallel (different modules). E2 and E3 can run as
parallel worktrees (desktop vs html, shared dependency only on E1).
Everything else is sequential — the APU is one tightly-coupled
component; parallel channel work would fight over `APU.java`.

## Risk register

| Risk | Mitigation |
|---|---|
| Atomic-execute CPU can't hit ±1-cycle $4015 timing (Phase C) | C0 spike measures first; C2 access-cycle compensation (seam S6); explicit kill-pivot descoping 4/5/6-jitter ROMs to a future cycle-stepped-CPU project — never a CPU rewrite in this branch |
| Per-CPU-cycle `apu.clock()` regresses web runFrame | Bench at every gate against the D9 band (≤5% pass / 5–10% advisory-and-recorded / >10% sustained = hard trigger); channels are int-only, no allocation; the hard trigger arms the contained lazy-catch-up refactor (F3) |
| nestest 8992/8992 breaks on live $4015 reads (Phase A) | Golden log is from an APU-bearing emulator — a diff indicts our semantics; fix in-phase, never waive the gate |
| POC-W fails on both SPN and buffer-queue | Kill-pivot to AudioWorklet with logs captured to plan it; A–D proceed regardless (output-independent) |
| Blargg takedown request | Same policy as mapper-plan: remove ROMs, keep unit tests + protocol runner for local use; CREDITS.md documents provenance |
| $6000 PRG-RAM seam (S1) breaks an existing game/movie | Routing is scoped to `$4020-$7FFF`, so `$8000+` mapper-register latches see no new traffic; NROM PRG-ROM protected by the Mapper000 `cpuMapWrite`→UNMAPPED fix (RED-tested — note the *unfixed* mapper would let writes corrupt `vPRGMemory`); NestestTest + movie ITs gate it |
| DMC stall arbitration corrupts OAM DMA | D2 tests pin DMC-before-OAM order and that OAM resumes; collision cycle-exactness is a ratified non-goal |
| Buffer under/overrun audible in v1 pacing | Accepted for v1 (D6); ring policy = overwrite-oldest + counter; DRC scoped as F2 with POC-D drift data already in hand |

## Out of scope (ratified non-goals — D10)

- PAL timing (NTSC-only; tables isolated so PAL is additive later).
- $4015/$4016/$4017 double-read glitches (`dmc_dma_during_read4`).
- OAM+DMC DMA collision cycle-exactness (`sprdma_and_dmc_dma`); v1 rule
  is simply "DMC wins".
- DMC get/put alignment and halt-only-on-read-cycle refinement (flat
  4/3-cycle stalls).
- 2A03 revision-dependent power-up variance beyond the blargg-tested
  subset (research §1.9).
- Open-bus modeling ($4015 bit 5, $4014 reads).
- `apu_mixer` / `volume_tests` as automated gates (by-ear only).
- AudioWorklet (kill-pivot fallback only), stereo output, audio
  capture/export, Famicom expansion audio (VRC6 etc.).
- blip_buf, dynamic rate control, lazy catch-up (scoped as Phase F
  follow-ups, not this branch).
- Savestate serialization of APU state (the harness Phase E scoping doc
  owns that; APU lands with "capture version tag now, real state later"
  noted there).

---

## Decisions (D1–D19)

Sources: `apu-research-emulation.md` §8 items 1–10 → D1–D10;
`apu-research-audio-output.md` §7 items 1–9 → D11–D19.

**D1 — Phasing & audible-output placement: 4-phase blargg order (A–D)
first, output integration as Phase E, POCs as Phase 0.**
Options: (a) audio early (after B) for morale; (b) audio last for
rigor; (c) interleaved. Chosen: **(b)**, with Phase 0 supplying the
morale/derisk win up front — a 440 Hz tone on both hosts in week one
retires the output risk that early-audio was meant to retire, without
shipping a mixer against half-built channels. E after C+D also means
audio ships only after the branch's single movie-visible generation is
closed (D2) and the mixer sees final channel semantics including DMC.
E2/E3 note: E depends only on A+B technically; if the branch stalls in
C, E may be pulled forward at the cost of re-verifying after D — the
default order stands.

**D2 — EMU_VERSION: one bump, 1 → 2, landed in Phase A; master sees one
transition at branch merge.**
Options: per-timing-phase bumps (A, C, D — three); one bump at merge;
flag-gating timing changes until D. Chosen: single bump at the first
movie-visible phase (A — live $4015 reads), constant across the branch.
Phase PRs target `feature/apu` (repo precedent: harness #41, mappers),
so intra-branch timing evolution (C's IRQ delivery, D's stalls) never
reaches master piecemeal; committed movie ITs are regenerated at A/C/D
gates instead. Flag-gating was rejected as a dual-mode surface with its
own desync risk; per-phase bumps buy nothing master-visible.

**D3 — $4015/$4017 bus routing: keep as-is.**
Keep the "controller AND APU both see $4017 writes" dual dispatch
(hardware-correct: $4017 is both controller strobe/read port and frame
counter), $4015 reads → APU, $4016/$4017 reads → controller only.
No routing refactor: the inlined range checks were a deliberate perf
pass; the APU rewrite changes what's *behind* `apu.cpuBusWrite`, not
the routing. Open-bus tightening ($4015 bit 5, $4014 reads) rejected —
non-goal (D10). The one routing change is seam S1 (cartridge writes),
needed by the test protocol, not by the APU proper.

**D4 — DMC stall: state machine inside the APU/DmcChannel + one
arbitration check in `CPUBus.clock()`, ordered before OAM DMA;
`DmaController` untouched.**
Options: (a) extend `DmaController` into a general DMA arbiter; (b)
separate `DmcDma` class sharing the slot; (c) APU-owned stall counter +
bus check. Chosen: **(c)** — the DMC owns the fetch trigger and target
buffer, so the state lives with it; the bus contributes only
`if (apu.dmcStallPending()) { apu.tickDmcStall(this); } else …` in the
CPU-turn branch. Extending `DmaController` couples two unrelated state
machines for a collision case we ratified as a non-goal. Flat 4-cycle
(reload) / 3-cycle ($4015-start) stalls for v1; get/put alignment and
the double-read glitch are D10 non-goals (Mesen-style CPU-side modeling
is the recorded v2 route if `dmc_dma_during_read4` is ever chased).
**Parity-preservation argument (why the flat stalls are safe):**
`DmaController` alternates its OAM read/write on `masterClockCount % 2`,
and CPU turns come every 3 master ticks, so consecutive CPU turns
alternate master-clock parity. A flat **4-cycle** reload stall consumes
an even number of CPU turns' parity flips, so a paused mid-burst OAM
transfer resumes on the same phase of its get/put alternation it was
interrupted on; the **3-cycle** $4015-start stall can only be triggered
by a CPU write to $4015, which cannot occur while the CPU is halted
inside an OAM burst — so the odd-length stall never interleaves with an
active burst. Corollary: **changing either stall length later silently
corrupts OAM get/put alternation**; the Phase D2 "OAM resumes with
intact alternation" test is the tripwire and must be kept.

**D5 — Mixer ladder rung per phase: nonlinear lookup tables from day
one; downsampler = box-average in Phase E; blip_buf = Phase F swap.**
Options per research quality ladder: naive decimation / box-average /
blip. Chosen: box-average as the shipping v1 rung (one add per cycle +
one multiply per sample; kills the worst aliasing; halfNES-adjacent),
never naive decimation (saves nothing meaningful), blip deferred behind
the delta-friendly channel→mixer boundary so it lands as a mixer-only
PR (F1). The nonlinear tables are not a rung — they're correctness
(channel balance, DMC cancellation) at identical cost to a linear mix.

**D6 — Desktop pacing v1: video-driven + slack buffer; drift accepted.**
Options: accept drift with drop/overwrite recovery; audio-driven
blocking writes; immediate DRC. Chosen: video-driven (the emulator's
existing vsync pacing stays master), ~100 ms device buffer absorbing
jitter, rare click accepted and measured (POC-D quantifies the drift
walk rate). Audio-driven pacing rejected: it inverts the master clock
(olc's mistake) and cannot work headless/web. DRC is the end-state but
is follow-up F2 (D13) — never feeding emulation content, only resample
ratio.

**D7 — Test-ROM residency: check blargg APU ROMs into
`core/src/test/resources/test-roms/blargg/` with CREDITS.md entry; CI
runs them unconditionally.**
Licensing, stated precisely: **no formal license exists** for blargg's
ROMs. This adopts the same *de-facto-redistribution norm assumed by
mapper-plan* (decades of redistribution by FCEUX/Mesen/Nestopia,
credit given, remove on request) — and note mapper-plan itself carries
an **unchecked user-confirmation item** for exactly this
(mapper-plan.md:589), so the assumption has never actually been signed
off. Accordingly, Phase A0 carries an explicit user-confirmation step
**before** the ROM-commit lands; if confirmation is withheld, fall back
to skip-if-absent loading from a local path (CartridgeNes2Test
convention) and note that CI then cannot gate on the suite.
`apu_test/rom_singles/` + `apu_reset/` in
Phase A; `dmc_tests/` in Phase D; 2005-suite singles only if the C
stretch tier is pursued. With confirmation given, skip-if-absent is
unnecessary for these (unlike commercial ROMs) — the suite actually
gates CI.

**D8 — $4017 power-up: faithful $00 (4-step, IRQ enabled) from Phase A.**
Options: faithful from day one vs staged behind Phase C. Chosen:
faithful immediately — `apu_reset/works_immediately` and
`irq_flag_cleared` are Phase A gates and check this state, and the
risk window is closed by construction: the flag is set/readable from A
but **delivery** only wires up in C, so no game can be broken by an
undeliverable IRQ in between. When C lands, delivery is hardware-truth
(games must write $40 or serve it) and is covered by the C gate ROMs.

**D9 — Catch-up optimization trigger: sustained >10% web `runFrame`
regression vs the pre-APU baseline, measured at every phase gate,
after cheap micro-fixes are exhausted.**
The eager design keeps the door open (everything driven off the cycle
counter, no mutable elapsed state); the refactor (F3) is contained
inside the APU. The band is three-valued, so there is no dead zone:
**≤5% = pass** (mapper-plan A3 precedent); **5–10% = advisory** — the
phase may land with the regression recorded in its gate checklist and
cheap micro-fixes attempted; **>10% sustained across two consecutive
gates = hard trigger** arming F3. Pinning numbers prevents the eager
v1 from silently becoming a web regression.

**D10 — Non-goals ratified.** The "Out of scope" list above is the
canonical set: PAL, double-read glitches, OAM+DMC collision exactness,
get/put alignment, revision-variant power-up, open bus, audible-ROM
automation, AudioWorklet-by-default, stereo, blip/DRC/lazy-catch-up
(→ Phase F), expansion audio, APU savestates. Review passes cite this
decision instead of relitigating.

**D11 — Web mechanism: ScriptProcessorNode first; AudioBufferSourceNode
queue as the in-POC fallback; AudioWorklet only on kill-pivot.**
Chosen per research recommendation: SPN is least code, all-Java via
existing JSO bindings, and its main-thread flaw is neutralized by
TeaVM's single-threaded model (unsynchronized ring). Deprecated-but-
universally-shipped is acceptable for this POC-to-v1 horizon; the ABSN
queue is the undeprecated push-model fallback exercised in POC-W if SPN
misbehaves. POC-W's soak result makes the final call and records it in
the findings doc; Phase E3 implements whichever won.

**D12 — Sample-rate policy: parameterize core on a host-supplied rate.**
Options: parameterize (44100 desktop / `ctx.getSampleRate()` web) vs
fix 44100 + custom JSO constructor binding to force browser
resampling. Chosen: parameterize — simpler, honest, avoids a
browser-side resample of already-resampled output, and headless tests
default to 44100. Per-frame sample counts always come from the
fractional accumulator (never a 735 constant).

**D13 — Pacing end-state: dynamic rate control on both targets, as
follow-up F2.**
Options: DRC both (chosen); desktop-audio-driven + web-DRC hybrid
(rejected — two pacing architectures to maintain, and audio-driven
conflicts with the vsync-paced host loops and pause semantics);
POC-tier drop/dup forever (rejected — audible hiccups every N seconds
are beneath the end-state bar). DRC nudges only the resample ratio
(±0.5%), computed from host-supplied fill metrics; emulation content
stays deterministic. v1 ships D6's accepted-drift tier.

**D14 — Downsampler tier for first playable: box averaging.**
(The output-doc twin of D5.) Naive decimation rejected even for v1 —
its cost saving over box-average is one add per cycle, and the aliasing
on high pulse/triangle notes is exactly what users notice first.
blip-buffer is scheduled as F1, not now.

**D15 — Mono output everywhere.**
The NES is mono; desktop `AudioDevice` supports `AL_FORMAT_MONO16`
first-class and WebAudio takes a 1-channel buffer. Stereo interleaving
would double buffer traffic for zero fidelity. Future pan effects are a
host-side nicety that can duplicate the channel then; nothing in POC
findings may override this without reopening the decision.

**D16 — Drain point: host render loop, immediately after
`nesSystem.runFrame()` — `EmulatorScreen.render()` on desktop,
`renderEmulatorFrame()` on web.**
`frameRenderedListener` rejected: it fires at NMI (scanline 241, not
the frame boundary the hosts and harness step by), and its
exception-swallowing makes audio bugs silent. The explicit post-
`runFrame()` position is the same boundary the harness/recorder use
(harness D2/D9) — one definition of "frame" everywhere.

**D17 — Buffer/latency targets: desktop ~100 ms device capacity
(`setAudioConfig` tuned by POC-D, strawman 1024×4); web ring ~4 frames
(~66 ms) + SPN 2048-sample buffer (~43 ms at 48k) — note this web
strawman totals **~109 ms, exceeding the research doc's 50–90 ms
target**; it is a deliberately safe starting point for the POC, and
POC-W's job includes shrinking the ring and/or SPN buffer toward the
target while holding zero starvation under the 60 FPS load. Final
numbers come from POC data, recorded in `docs/apu-poc-findings.md`.**
These are targets with a floor rule (no sustained crackle at 60 FPS)
rather than hard constants; Phase E adopts the POC-measured values and
this decision's numbers are updated inline when the findings doc lands.

**D18 — Pause/mute semantics: desktop stops writing and lets the device
drain; web mutes via GainNode and clears the ring; both clear
ring/downsampler state on resume, `reset()`, and `swapRom()`.**
Feed-silence rejected on desktop (keeps the blocking write path active
during pause for no benefit); flush APIs on `AudioDevice` are limited,
and OpenAL's auto-recovery makes stop-writing the simplest clean
behavior. Web tab-background (rAF stops, callbacks drain to silence) is
accepted, not fought. Emulator-side determinism is unaffected — these
are host-side sinks.

**D19 — No third-party web-audio dependency.**
The ecosystem "gdx-webaudio" library is spatial-audio-oriented; our
need is one node chain plus possibly a few `@JSBody` lines. A
dependency adds supply-chain and TeaVM-version-compat surface for
negative code savings. Hand-rolled JSO, per POC-W.
