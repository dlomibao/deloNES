# APU research — getting audio samples to the speakers (desktop + web)

**Date:** 2026-07-18
**Branch:** `feature/apu`
**Goal:** before any APU channel emulation exists, establish how
emulator-generated PCM samples reach the speakers on BOTH targets
(desktop LWJGL3/OpenAL, web gdx-teavm 1.5.6 / TeaVM 0.14), what the
risks are, and what the phase-0 derisk POC for each target looks like.
Companion to `docs/web-phase0-findings.md` (same derisk-report role,
one phase earlier — this doc *feeds* the phase-0 work, it doesn't
record its results).

## TL;DR

| Target | Streaming-PCM path | Status |
|---|---|---|
| Desktop (LWJGL3) | `Gdx.audio.newAudioDevice()` → `OpenALAudioDevice` | **Works out of the box.** Blocking `writeSamples`, ~104ms default latency, tunable via `Lwjgl3ApplicationConfiguration.setAudioConfig`. Low risk. |
| Web (gdx-teavm 1.5.6) | `Gdx.audio.newAudioDevice()` | **Throws `GdxRuntimeException("AudioDevice not supported by teaVM backend")`** — confirmed in backend source. Must bypass Gdx.audio and drive WebAudio directly via TeaVM JSO bindings (which ship in `teavm-jso-apis`: `org.teavm.jso.webaudio.*`). Medium risk; this is what phase 0 must retire. |

Sample generation itself lives in `core` (the existing `APU` stub +
`NesSystem` tick loop); output is a host concern (desktop module / html
module), mirroring how the framebuffer is handled today.

---

## 1. Desktop — LibGDX `AudioDevice` on LWJGL3/OpenAL

### API shape

- `Gdx.audio.newAudioDevice(sampleRate, isMono)` returns an
  `AudioDevice`; throws `GdxRuntimeException` on failure. Native
  resource — must `dispose()`.
- `writeSamples(short[]/float[], offset, len)`: 16-bit signed PCM or
  float PCM in [-1,1] (floats are clamped and converted to 16-bit
  internally — the device is 16-bit-only under the hood,
  `bytesPerSample` hardcoded to 2). Stereo is interleaved L/R; mono is
  a first-class mode (`AL_FORMAT_MONO16`), so the NES's mono signal
  does not need duplication.
- Any sample rate OpenAL accepts; 44100 and 48000 both fine.
  (halfNES source carries a comment that macOS historically wanted
  44100 — low-confidence but cheap to honor.)

### Blocking semantics (read the LWJGL3 source, `OpenALAudioDevice`)

`writeSamples` **blocks**: it rotates through a fixed pool of OpenAL
buffers; when none is free it polls `AL_BUFFERS_PROCESSED` and
`Thread.sleep()`s for **one whole buffer-duration** between polls.
Consequences:

- Called from the render thread, an overfull device stalls video by up
  to a buffer-duration granule. With correctly sized buffers and one
  frame's worth of samples per call, it almost never blocks.
- Underrun handling is automatic but audible: if the source stops
  (starved), the device just calls `alSourcePlay()` again on the next
  write — a gap/click, then recovery. No exception, no log.

### Tuning knobs

`Lwjgl3ApplicationConfiguration.setAudioConfig(simultaneousSources,
bufferSize, bufferCount)` — defaults **16 / 512 samples / 9 buffers**.
`AudioDevice.getLatency()` returns total queued capacity in samples =
`bufferSize × bufferCount` (per source). Defaults give 4608 samples ≈
**104ms at 44.1kHz** — high-ish but safe; classic emulator territory is
50–100ms. Known pitfall (libgdx issue #4859): historical defaults were
too small for `AudioDevice` streaming and caused starvation crackle —
i.e., these knobs are load-bearing, don't assume defaults are ideal.
Sensible experiment range: bufferSize 512–1024, bufferCount 3–9.

### How other Java emulators pace (survey: halfNES)

halfNES (`com.grapeshot.halfnes.audio.SwingAudioImpl`) uses
`javax.sound.sampled.SourceDataLine` (not applicable here — but the
*pattern* is):

- APU generates samples **directly at the output rate** (fractional
  cycle accumulator inside the APU — no separate resampler stage).
- One frame's samples are accumulated in a plain `byte[]` and flushed
  once per video frame (`flushFrame`).
- The line buffer is sized to **4 frames** of audio (~67ms).
- Pacing: if the line has room, write; if full, either block on the
  write (audio-driven pacing — the sound card's DAC clock becomes the
  master clock) or drop the frame's audio (when the frame limiter is
  driving). No dynamic rate control.

The three standard pacing architectures, in increasing quality:

1. **Video-driven + slack buffer** (POC tier): vsync paces emulation at
   ~60Hz; write ~735 samples/frame; the device buffer absorbs jitter.
   Drift between the display clock (60.000), NES clock (60.0988) and
   the audio crystal (~±0.1%) slowly walks the buffer toward
   underrun/overrun — audible hiccup every N seconds. Acceptable for a
   POC, not for the end state.
2. **Audio-driven** (halfNES-when-blocking, many emulators): let the
   blocking `writeSamples` be the frame limiter (vsync off or
   decoupled). Perfect audio, video jitters up to one frame.
   `AudioDevice`'s sleep-granularity blocking makes this workable but
   coarse on LWJGL3.
3. **Dynamic rate control** (known-good end state — byuu/Near's
   "Dynamic Rate Control" article; libretro's ratecontrol paper): keep
   vsync for video, watch audio-buffer fill level, and micro-adjust the
   resampling ratio (±~0.5% max) to hold the buffer half-full. Pitch
   shifts are inaudible at that magnitude. Requires querying buffer
   fill — on `AudioDevice` this must be inferred (samples written −
   time elapsed) since the API exposes no direct fill query; that
   inference is a mild weakness worth noting for the planner.

---

## 2. Web — the risky target

### What gdx-teavm 1.5.6 actually provides

Read directly from the `xpenatan/gdx-teavm` sources:

- `backend-web` audio =
  `…backends.web.webaudio.howler.HowlTeaAudio`, backed by **howler.js**
  (the `howler.js` file our `html/build.gradle` already extracts from
  the backend JAR next to `gdx.wasm.js`). It implements `Sound` and
  `Music` (decoded-asset playback) only.
- **`newAudioDevice` throws** `GdxRuntimeException("AudioDevice not
  supported by teaVM backend")`. `newAudioRecorder` likewise. There is
  no PCM streaming path in the backend, and no open issue/roadmap item
  for one (issue sweep: all audio issues are Sound/Music/autoplay
  bugs). This matches upstream libGDX GWT ("Direct PCM output is not
  supported in the JavaScript/WebGL backend") — gdx-teavm inherited
  the same gap.

So `Gdx.audio` is a dead end for an APU. The web host must talk to the
browser's WebAudio API directly.

### TeaVM gives us the bindings already

`teavm-jso-apis` ships `org.teavm.jso.webaudio.*`:
`AudioContext` (static `create()`, `resume()`, `getSampleRate()`,
`createBuffer`, `createBufferSource`, `createScriptProcessor`,
`createGain`, `createOscillator`…), `ScriptProcessorNode`,
`AudioBuffer` (`copyToChannel(Float32Array,…)`),
`AudioBufferSourceNode`, `GainNode`, plus typed-array interop
(`org.teavm.jso.typedarrays.Float32Array`). The project already uses
JSO (`@JSExport` in `HtmlLauncher`), and `backend-web` itself is built
on JSO DOM bindings, so the dependency is present. **Caveat to verify
in phase 0:** the jso-apis WebAudio surface dates from ~2016 — it has
`ScriptProcessorNode` but **no AudioWorklet** binding (only the dead
"AudioWorker" draft spec), and `AudioContext.create()` takes no
options bag (can't request a specific sampleRate). Anything missing is
a few lines of custom `@JSClass`/`@JSBody` binding — cheap, proven
pattern in this repo.

### Three candidate mechanisms

| Mechanism | Effort | Latency | Longevity | Notes |
|---|---|---|---|---|
| **ScriptProcessorNode** | Lowest — pure Java via existing JSO binding | bufferSize/rate + output latency (2048 @ 48k ≈ 43ms + ~20ms) | **Deprecated** since ~2014 but still shipped by every browser in 2026; Chrome has only removed it from *extensions*. Removal from the web platform keeps not happening because too much depends on it. | `onaudioprocess` fires on the **main thread** — normally its fatal flaw, but TeaVM is single-threaded anyway, so consumption and production already share one thread. A plain Java `float[]` ring buffer needs zero synchronization. |
| **AudioBufferSourceNode queue** ("buffer chaining") | Low — schedule one `AudioBuffer` per emulated frame at precisely accumulated `ctx.currentTime`-based start times | Whatever lead you schedule (2–4 frames ≈ 33–66ms) | Uses only non-deprecated, universally supported nodes | Push model — fits our render loop perfectly (no callback at all). Costs: per-frame `AudioBuffer` allocation (GC churn, minor), and you own drift/seam management: schedule too tight → gaps at buffer boundaries; too loose → latency growth. Well-trodden path in emscripten emulator ports. |
| **AudioWorklet** | Highest | Best (128-sample quanta on the audio rendering thread) | The blessed modern API | The processor must be a **separate JS module executed on the audio thread** — TeaVM-compiled Java cannot run there. Requires a hand-written JS worklet file + a ring buffer shared via `postMessage` or `SharedArrayBuffer` (SAB additionally requires COOP/COEP headers — a static-hosting complication). Real projects report it as heavy machinery (WebAudio spec issue #2632). |

**Recommendation:** ScriptProcessorNode first (least code, all-Java,
main-thread model matches TeaVM's), with the AudioBuffer-queue
approach as the fallback if SPN misbehaves, and AudioWorklet as the
deliberate later upgrade only if measured glitching demands it.

### Autoplay policy / user gesture

An `AudioContext` created before a user gesture starts `"suspended"`;
`resume()` must be called from inside a gesture handler. Confirmed as
the standard flow in gdx-teavm issue #117 ("The AudioContext was not
allowed to start" → answer: gate behind a click). Our
`html/webapp/index.html` **already has the hooks**: the canvas
`mousedown` → `window.focus()` shim (added in web phase 0 for
keyboard focus) and the ROM-picker button `click` handler. Either is a
natural `resume()` point; no new UI needed. The web POC must verify
resume-from-gesture actually transitions state to `"running"`.

### Threading reality on TeaVM

No real threads (documented in `HtmlLauncher`'s own comments).
Everything — rAF render loop, `runFrame()` sample generation, SPN
`onaudioprocess`, JS event handlers — interleaves on the main thread.
Implications:

- A long `runFrame()` (~8.6ms today) can delay an `onaudioprocess`
  callback; the SPN buffer size must cover worst-case main-thread
  occupancy (2048–4096 samples, not 256).
- Ring buffer can be an unsynchronized `float[]` + int head/tail
  (no `long` indices — TeaVM software-emulates `long`, a proven
  hot-path cost in this repo's perf pass).
- If the tab is backgrounded, rAF stops but audio callbacks continue →
  buffer drains → silence. Acceptable; note it, don't fight it.

### Browser sample-rate wrinkle

`AudioContext` opens at the device rate — usually 48000, sometimes
44100. The 2016-era JSO binding can't pass `{sampleRate:44100}` to the
constructor (custom binding could). Two clean options: generate at
`ctx.getSampleRate()` (core's samples-per-frame becomes a parameter,
not a constant), or fix 44100 in core and let a custom-bound
constructor ask the browser to resample. Parameterizing core is the
simpler and more honest choice.

---

## 3. Sample-rate and pacing architecture

### The numbers

- NTSC CPU clock: **1,789,773 Hz**; PPU frame: 341×262÷3 ≈ 29,780.5
  CPU cycles; frame rate **60.0988 Hz**.
- Samples per frame: 44100 ÷ 60.0988 = **733.8**; 48000 ÷ 60.0988 =
  **798.7**. Both fractional → per-frame sample counts must alternate
  (733/734/…) via an accumulator; any "735 exactly" constant bakes in
  a 0.16% drift (735 is 44100/60.000 — correct only if you also decide
  to run the NES at exactly 60.0, which vsync-paced emulators
  effectively do; pick one story, don't mix them).
- Downsampling ratio: 1,789,773 ÷ 44,100 ≈ **40.58 CPU cycles per
  output sample** — keep the fraction in an int/float accumulator (no
  `long`, no division in the hot loop).

### Downsampling quality ladder

1. **Naive decimation** (grab the mixer output every ~40.58th cycle):
   aliases, but NES pulse/triangle content makes it *acceptable for a
   POC* — many shipped emulators did exactly this for years.
2. **Box averaging** (average all ~40 mixer outputs per sample):
   one add per APU cycle + one multiply per sample; kills the worst
   aliasing; halfNES-adjacent quality. Good default for "playable".
3. **Band-limited synthesis** (Blargg's blip_buf / blip-buffer
   technique — delta buffer + precomputed band-limited steps): the
   known-good end state, used by bsnes/higan/Mesen-class emulators.
   Cheap at runtime because APU waveforms change state only a few
   thousand times per second — you record *transitions*, not cycles.
   A blip-style resampler also makes DRC's variable ratio nearly free.

Add a cheap first-order high-pass (~90Hz) and low-pass (~14kHz) at the
mixer output regardless of tier — the NES hardware has both, and the
high-pass removes the DC offset that otherwise thumps on pause/resume.

### Pacing / drift end-state

Three clocks disagree: display (~60.000), NES (60.0988), audio crystal
(nominal ±0.1%). POC answer: oversize the buffer, accept a rare click.
Production answer (recommended): **dynamic rate control** — measure
audio-buffer fill each frame, nudge the effective resample ratio
within ±0.5% to hold half-full (byuu article / libretro ratecontrol
paper). Works identically on both targets; on web the "fill level" is
directly computable (scheduled-ahead time, or ring-buffer occupancy),
on desktop it's inferred. Alternative acceptable end-state for desktop
only: audio-driven pacing via blocking `writeSamples`.

---

## 4. Repo constraints → where the code must live

From CLAUDE.md, `readme.md` devlog (perf pass), and
`docs/headless-harness-plan.md` (tier rules):

- **`core/src` is TeaVM-compiled.** Hot-path rules proven on this
  repo: no reflection, no regex/`String.split`/`String.format`, no
  boxing, **no `long` arithmetic in per-cycle loops** (software-
  emulated on TeaVM; the `% 3` fix and `runFrame` deadline fix were
  both material). No `javax.*` in `core/src` (harness plan D1/D6
  confined `javax.imageio` to the fixtures tier; `javax.sound` gets
  the same treatment — and in practice we never need it, since desktop
  uses LibGDX `AudioDevice`, not JavaSound).
- **`core` may reference LibGDX APIs** (`render.PixelRenderer` does),
  but the framebuffer precedent is the better template: core produces
  a plain-Java buffer (`int[][] screen`), each host owns presentation
  (`EmulatorScreen` → `PixelRenderer`; `HtmlLauncher` → bulk-put
  pixmap). Audio should mirror this exactly: core produces samples
  into a plain `float[]`/`short[]` ring; the desktop module wraps
  `AudioDevice`; the html module wraps JSO WebAudio. Neither host
  class can live in core anyway (`AudioDevice` is unsupported on the
  web backend; JSO classes don't exist on desktop).
- **Natural generation boundary:** `NesSystem.tick()` already drives
  everything per master tick; the APU stub is already wired on the CPU
  bus (register writes land today — done for movie-replay parity).
  APU clocking hooks into the existing tick cadence; hosts drain the
  ring once per frame — desktop right after `nesSystem.runFrame()` in
  `EmulatorScreen.render()`, web in `renderEmulatorFrame()`. The
  `frameRenderedListener` (fires at VBlank) is an alternative drain
  point but adds nothing over the post-`runFrame()` position, and its
  exception-swallowing makes audio bugs quieter — prefer the explicit
  host-loop drain.

---

## 5. Recommended phase-0 derisk plan (no APU emulation required)

Both POCs stream a **synthesized 440Hz square wave at NES-realistic
volume** — zero emulator involvement, pure output-path proof. Square
(not sine) on purpose: it is what the APU pulse channels produce, and
it exposes buffer seams/discontinuities much more audibly than a sine.

### POC-D (desktop) — half a day

1. In a debug launcher (or a temporary flag in `EmulatorScreen`),
   create `Gdx.audio.newAudioDevice(44100, true)` (mono).
2. Each `render()` call, synthesize the next N samples of a 440Hz
   square (N from a 44100/60.0988 fractional accumulator, i.e., 733/734
   alternating) into a pre-allocated `float[]`, and `writeSamples`.
3. Log `getLatency()`, per-call `writeSamples` wall time, and FPS every
   second for 60+ seconds.
4. Variant runs: default `setAudioConfig` vs 512/3 vs 1024/4; mono vs
   stereo; deliberately skip 5 frames of writes (simulate GC/stall) to
   hear and observe underrun recovery.

**Success criteria:** clean continuous tone for 60s with no audible
click/gap at steady state; `writeSamples` p99 wall time under ~2ms
(i.e., not eating the 16ms frame budget); FPS stays 60; underrun test
recovers to clean tone without app intervention.

**Risks retired:** blocking behavior of `writeSamples` on the render
thread; real latency of default vs tuned buffer configs; mono support;
underrun audibility/recovery; whether vsync-paced 60Hz feeding at
60.0988Hz sample counts drifts audibly within minutes (this measures
the size of the future DRC problem).

### POC-W (web) — one to two days; **this is the actual derisk**

1. In `HtmlLauncher` (behind a temporary probe flag, like the phase-0
   probes), build the chain via `org.teavm.jso.webaudio`:
   `AudioContext.create()` → `createScriptProcessor(2048, 0, 1)` →
   `destination`, plus a `GainNode` for a volume/mute handle.
2. Wire `resume()` into the existing index.html canvas-mousedown /
   ROM-picker click path; log `getState()` transitions
   (`suspended` → `running`).
3. `onaudioprocess`: copy from a Java `float[]` ring buffer into the
   output channel's `Float32Array`; the render loop *produces* the
   440Hz square into the ring at `ctx.getSampleRate()`-derived
   samples-per-frame. Log ring occupancy min/max each second.
4. Soak 60+ seconds with the DK ROM emulating + rendering
   simultaneously (audio POC on top of the real 60fps video load —
   main-thread contention is the whole question on TeaVM).
5. If SPN proves unusable (callback starvation, missing binding
   behavior at runtime), fall back to the AudioBufferSourceNode
   queue: one `createBuffer` per video frame, `copyToChannel`,
   `start(scheduledTime)` with an accumulated schedule clock — same
   success criteria.

**Success criteria:** tone starts only after the click (autoplay gate
proven); clean tone for 60s *while the emulator renders at 60 FPS*;
ring occupancy stays bounded (no monotonic drain/growth); works in
Chrome + one other engine (Firefox or Safari); console shows actual
`ctx.sampleRate` (44100 vs 48000 recorded for the planner).

**Risks retired:** `newAudioDevice`-throws confirmed at runtime and
routed around; the 2016-era jso-apis WebAudio bindings actually
function on TeaVM 0.14 (or the custom-binding fallback cost is now
known); autoplay-resume via existing gesture handlers; main-thread
contention between `runFrame()` (~8.6ms) and audio callbacks at a
given SPN buffer size; device sample-rate variability; achievable
latency floor.

**Kill/pivot criterion:** if neither SPN nor buffer-queueing can hold
a clean tone alongside 60 FPS emulation in Chrome, the pivot is the
AudioWorklet + hand-written JS worklet + postMessage ring — costed as
"days, not hours", and phase 0 should measure enough (occupancy logs,
callback timing) to write that plan.

---

## 6. Recommended production architecture

- **Core (timeline tier, TeaVM-safe):** APU emulation writes into an
  `ApuSampleBuffer` owned by core — pre-allocated `float[]` ring,
  `int` head/tail, mono, parameterized output sample rate set at
  construction by the host (desktop: 44100; web: `ctx.sampleRate`).
  Fractional-decimation (later blip-buffer) downsampling lives in
  core next to the APU, since it consumes cycle-rate data. No `long`,
  no allocation, no boxing in any per-cycle or per-sample path.
  `NesSystem` exposes the buffer; nothing in core touches an output
  API.
- **Desktop host (`desktop` module):** a small `AudioOut` wrapper over
  `Gdx.audio.newAudioDevice` created in `EmulatorScreen.show()`,
  drained right after `runFrame()` in `render()`, disposed in
  `dispose()`, paused/flushed on `togglePause()`. Buffer config from
  `setAudioConfig` per POC-D findings.
- **Web host (`html` module):** a parallel wrapper over the JSO
  WebAudio chain chosen by POC-W, created in `create()`, resumed on
  first gesture, drained in `renderEmulatorFrame()` (SPN pulls from
  the ring on its own callback; the "drain" is just production-side
  accounting). Muted/rebuilt across `swapRom()`.
- **Pacing:** ship v1 video-driven with a half-full-target ring
  (~100ms desktop, ~50–90ms web) and per-frame fractional sample
  counts; add dynamic rate control as a follow-up on both targets
  (ratio nudge computed from ring occupancy — identical core logic,
  host-supplied fill metric). Desktop may alternatively adopt
  audio-driven blocking if DRC is deferred.
- **Testing:** because samples land in a plain core-owned ring, the
  headless harness can assert on audio without any host (e.g., "pulse1
  produces a 440Hz square after these register writes") — same
  pattern as the framebuffer golden tests. No new tier needed.

## 7. Open decisions for the planner

1. **Web mechanism:** ScriptProcessorNode (deprecated-but-everywhere,
   least code) vs AudioBufferSourceNode queue (undeprecated, push
   model) vs AudioWorklet-from-day-one (future-proof, needs JS shim +
   possibly COOP/COEP). Recommendation: let POC-W decide between the
   first two; worklet only on measured failure.
2. **Sample rate policy:** parameterize core on host-supplied rate
   (recommended) vs fix 44100 everywhere and add a custom JSO
   constructor binding to force browser resampling.
3. **Pacing end-state:** DRC on both targets (recommended) vs
   desktop-audio-driven + web-DRC hybrid vs POC-tier drop/dup only.
4. **Downsampler tier for first playable:** naive decimation vs box
   averaging (recommended floor) — and whether blip-buffer is
   scheduled now or as a quality follow-up.
5. **Mono vs stereo output:** NES is mono; mono works on desktop
   AudioDevice and WebAudio. Any reason (future pan effects, host
   quirks discovered in POC) to pay for interleaved stereo?
6. **Drain point:** host render loop after `runFrame()` (recommended)
   vs `frameRenderedListener`.
7. **Buffer/latency targets:** what latency is "good enough" per
   target (POC data will inform; ~100ms desktop / ~50–90ms web are
   the strawmen).
8. **Pause/mute semantics:** flush-and-stop vs feed-silence on
   `togglePause()`, menu screens, and web tab-background.
9. **Third-party option:** a "gdx-webaudio" TeaVM library exists in
   the ecosystem (spatial-audio-oriented); hand-rolled JSO is small
   enough that taking a dependency looks unjustified — confirm.

## Key sources

- libGDX wiki, Playing PCM audio — AudioDevice API, "Direct PCM output
  is not supported in the JavaScript/WebGL backend":
  https://libgdx.com/wiki/audio/playing-pcm-audio
- LWJGL3 `OpenALAudioDevice` source (blocking/underrun behavior):
  https://github.com/libgdx/libgdx/blob/master/backends/gdx-backend-lwjgl3/src/com/badlogic/gdx/backends/lwjgl3/audio/OpenALAudioDevice.java
- `Lwjgl3ApplicationConfiguration.setAudioConfig` (defaults 16/512/9):
  https://github.com/libgdx/libgdx/blob/master/backends/gdx-backend-lwjgl3/src/com/badlogic/gdx/backends/lwjgl3/Lwjgl3ApplicationConfiguration.java
- libGDX issue #4859 — default buffer sizes vs AudioDevice starvation:
  https://github.com/libgdx/libgdx/issues/4859
- gdx-teavm `HowlTeaAudio` (newAudioDevice throws):
  https://github.com/xpenatan/gdx-teavm — `backends/backend-web/.../webaudio/howler/HowlTeaAudio.java`
- gdx-teavm issue #117 — autoplay gate, click-to-continue:
  https://github.com/xpenatan/gdx-teavm/issues/117
- TeaVM JSO WebAudio bindings:
  https://github.com/konsoletyper/teavm — `jso/apis/src/main/java/org/teavm/jso/webaudio/`
- halfNES `SwingAudioImpl` (Java emulator audio pacing reference):
  https://github.com/andrew-hoffman/halfnes — `src/main/java/com/grapeshot/halfnes/audio/SwingAudioImpl.java`
- Near/byuu, "Dynamic Rate Control":
  https://byuu.net/audio/dynamic-rate-control/ (mirror: https://bsnes.org/articles/dynamic-rate-control/)
- libretro, "Dynamic Rate Control for Retro Game Emulators" (Arntzen):
  https://docs.libretro.com/guides/ratecontrol.pdf
- Chrome Developers, "Audio Worklet" (SPN deprecation rationale):
  https://developer.chrome.com/blog/audio-worklet
- MDN, `ScriptProcessorNode` (deprecated, still universally shipped):
  https://developer.mozilla.org/en-US/docs/Web/API/ScriptProcessorNode
- WebAudio spec issue #2632 — AudioWorklet real-world friction:
  https://github.com/WebAudio/web-audio-api/issues/2632
