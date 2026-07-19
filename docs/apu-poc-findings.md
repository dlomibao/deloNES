# APU Phase 0 — audio-output POC findings

**Status:** POC code landed; headless verification done; **AUDIBLE
verification pending — see the manual verification checklist at the
bottom.** This doc is the Phase 0 gate artifact required by
`docs/apu-plan.md` ("Phase 0 / Gate") and the input to decisions
D11/D17.

Both POCs stream a synthesized **440 Hz square at amplitude 0.15**
(≈ one NES pulse channel at full volume through the nonlinear mixer),
square-not-sine per the plan so buffer seams are audible. They are
derisk code, behind flags, desktop/html modules only, zero `core/src`
changes, and may be deleted/absorbed by Phase E.

---

## POC-D — desktop (`Gdx.audio.newAudioDevice`, LWJGL3/OpenAL)

**Code:**

- `desktop/src/net/lomibao/nes/desktop/audio/SquareToneGenerator.java` —
  square synthesis + 44100/60.0988 fractional accumulator (733/734
  alternating per-frame sample counts; never a hardcoded 735).
- `desktop/src/net/lomibao/nes/desktop/audio/TonePocLauncher.java` —
  standalone launcher; creates the device in `create()`, writes one
  frame's samples per `render()`, logs
  `t / fps / writes / writeSamples p99 / max / latency / skippedTotal`
  every second, exits after the soak.
- Gradle task: `desktop:audioPoc` (mac `-XstartOnFirstThread` handled;
  forwards `delones.audioPoc.*` -D flags).

**Flags (all optional):**

| Flag | Effect |
|---|---|
| `-Ddelones.audioPoc.bufferSize` / `.bufferCount` | `setAudioConfig(16, size, count)`; unset keeps LibGDX defaults 16/512/9 |
| `-Ddelones.audioPoc.stereo=true` | interleaved stereo instead of mono |
| `-Ddelones.audioPoc.underrunTest=true` | deliberately skips 5 frames of writes at t=10s and t=30s |
| `-Ddelones.audioPoc.seconds=N` | soak length, default 75, 0 = run until closed |

**Headless-verifiable findings (measured):**

- Tone math pinned by unit tests
  (`desktop/test/net/lomibao/nes/desktop/audio/SquareToneGeneratorTest.java`,
  `TonePocConfigTest.java`, 9 tests green):
  - per-frame sample counts alternate 733/734 at 44.1 kHz (798/799 at
    48 kHz) with ≤1-sample cumulative error over 601 frames (~10 s);
  - 1 s of samples has 880 ±1 zero crossings (440 Hz square confirmed);
  - phase is continuous across per-frame `fill()` calls (no frame-rate
    buzz from phase resets);
  - flag parsing defaults/overrides/malformed-input behavior.
- Flag-off behavior: `TonePocLauncher` is a separate main class behind
  its own Gradle task — `desktop:run` and all existing launchers are
  untouched; no audio object is ever created unless `desktop:audioPoc`
  is invoked.
- `desktop:test` green apart from the pre-existing `NesGameTest`
  environment flake (headless `create()` timeout — known, unrelated).

**Measured numbers (TO FILL during manual run):**

| Config | latency (samples/ms) | writeSamples p99 | FPS held | notes |
|---|---|---|---|---|
| default (16/512/9) | _ | _ | _ | _ |
| 512/3 | _ | _ | _ | _ |
| 1024/4 | _ | _ | _ | _ |
| stereo (default cfg) | _ | _ | _ | _ |

**Success criteria (from the plan):** clean continuous tone for 60 s at
steady state; `writeSamples` p99 < ~2 ms; FPS stays 60; underrun test
recovers to a clean tone without app intervention.

**Kill/pivot:** none expected. If `writeSamples` blocking proves
unusable, fall back to a dedicated feeder thread draining the same ring
(desktop-only; core unaffected).

---

## POC-W — web (TeaVM JSO ScriptProcessorNode)

**Code:**

- `html/src/net/lomibao/nes/client/WebAudioTonePoc.java` — the whole
  probe: `AudioContext.create()` → `createScriptProcessor(2048, 0, 1)`
  → `GainNode` → `destination`; unsynchronized Java `float[]` ring
  (safe: TeaVM is single-threaded — rAF producer and `onaudioprocess`
  consumer interleave on the main thread); per-second console logging
  of ring occupancy min/max, callback counts, starved callbacks,
  dropped samples, and `ctx.sampleRate`; `resume()` on first gesture
  via **capture-phase** document listeners for `mousedown`/`click`
  (capture-phase because the existing index.html canvas handler calls
  `stopPropagation()`, which would eat a bubble-phase listener; the
  gesture surfaces themselves — canvas mousedown, ROM-picker click —
  are the ones the plan names).
- `html/src/net/lomibao/nes/client/HtmlLauncher.java` — creates the
  probe in `create()` **only when the flag is present** and pumps
  `onFrame()` from `render()` (so the soak runs on top of the live
  DK/nestest emulation at 60 FPS — main-thread contention is the whole
  question).

**Flag:** URL query parameter **`?audioPoc=1`** (any value containing
`audioPoc`), e.g. `http://localhost:8080/?audioPoc=1`. The plan leaves
the web flag mechanism as "a probe flag in HtmlLauncher"; `-D` system
properties don't exist in the browser, so a query param is the web
equivalent. Flag off ⇒ `WebAudioTonePoc.createIfEnabled()` returns null
before any WebAudio object is constructed — zero audio objects, zero
listeners, render loop untouched.

**Headless-verifiable findings (measured):**

- **The big derisk: TeaVM 0.14 accepts the 2016-era
  `org.teavm.jso.webaudio` bindings** — `html:build` /
  `generateJavaScript` compiles the whole chain
  (`createScriptProcessor(int,int,int)`,
  `ScriptProcessorNode.setOnAudioProcess(...)`,
  `AudioBuffer.copyToChannel(float[], int)`, `GainNode`/`AudioParam`,
  `resume()`/`getState()`) and emits it into `classes.js` — verified
  in the generated output: `new AudioContext()`,
  `createScriptProcessor(2048, 0, 1)`, `onaudioprocess = ...`,
  capture-phase `addEventListener(..., !!1)` all present. No custom
  `@JSClass`/`@JSBody` bindings were needed.
- **Binding gap found (first confirmed 2016-era rot):** the plan's
  suggested entry point `AudioContext.create()` is broken in jso-apis
  0.14 — its `@JSBody` script is literally `return new Context();`,
  and `Context` is not a browser global, so it would throw
  `ReferenceError` at runtime (it is also `@Deprecated`). The POC uses
  the `@JSClass`-mapped constructor `new AudioContext()` instead,
  which emits the correct `new AudioContext()` JS. Phase E3 must do
  the same. (Cost of the gap: zero — no custom binding needed.)
- Baseline repair (separate commit): the web build was broken at
  branch baseline — core's `Cartridge` CHR-RAM warning calls a 3-arg
  `log.warn(...)` overload the html log4j shim didn't declare;
  `html:generateJavaScript` failed before any POC code existed. Fixed
  in the shim (`html/src/org/apache/logging/log4j/`).
- The API surface was verified against the shipped
  `teavm-jso-apis-0.14.0.jar` before writing code: `AudioContext` has
  `create()`, `resume()`, `getState()`, `getSampleRate()`,
  `createScriptProcessor(bufferSize, inputChannels, outputChannels)`;
  `AudioBuffer.copyToChannel` has a `float[]` overload (TeaVM marshals
  Java `float[]` ↔ `Float32Array`); **no AudioWorklet binding exists**
  (only the dead AudioWorker draft) — consistent with the research doc.
- Ring accounting is plain-int (no `long` in the per-sample paths, per
  the TeaVM hot-path rules).

**Runtime numbers (TO FILL during manual run):**

- `ctx.sampleRate` observed: _ (record per browser — feeds D12)
- `getState()` transitions: created=_ → after gesture=_
- ring occupancy min/max at steady state: _
- starved callbacks / dropped samples over 60 s: _
- browsers tested: Chrome _, second engine _

**Success criteria (from the plan):** tone starts only after the click
(autoplay gate proven); clean tone for 60 s while the emulator renders
at 60 FPS; ring occupancy bounded (no monotonic drain/growth); works in
Chrome + one other engine; actual `ctx.sampleRate` recorded for D12.

**In-POC fallback (only if SPN misbehaves at runtime):**
AudioBufferSourceNode queue — one `createBuffer`/`copyToChannel`/
`start(scheduledTime)` per video frame with an accumulated schedule
clock. **Kill/pivot** (only if both fail to hold a clean tone beside
60 FPS emulation in Chrome): AudioWorklet + hand-written JS worklet +
postMessage ring ("days, not hours"); capture the occupancy/callback
logs above to write that plan. Phases A–D are not blocked by this
pivot.

**D11 decision input:** pending the manual soak. SPN compiled and is
wired; no runtime evidence yet.
**D17 decision input:** pending — strawman shipped in the POC is ring
8192 samples (~170 ms @48k ceiling, target occupancy is lower) + SPN
2048; shrink toward the 50–90 ms target during the soak.

---

## Manual verification checklist (USER — audible checks)

Phase 0's success criteria are audible by definition; the following
must be run by a human with speakers. Record numbers in the TO FILL
tables above.

### Desktop (POC-D)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
export PATH="$JAVA_HOME/bin:$PATH"

# 1. Default config soak (75 s, auto-exits):
./gradlew desktop:audioPoc
#    LISTEN: clean continuous 440 Hz square for 60+ s, no clicks/gaps.
#    WATCH:  per-second log — fps stays 60, p99 < ~2 ms.

# 2. Variant configs:
./gradlew desktop:audioPoc -Ddelones.audioPoc.bufferSize=512 -Ddelones.audioPoc.bufferCount=3
./gradlew desktop:audioPoc -Ddelones.audioPoc.bufferSize=1024 -Ddelones.audioPoc.bufferCount=4
#    Note latency line at startup for each; listen for starvation crackle
#    on 512/3 (libgdx #4859 territory).

# 3. Stereo variant:
./gradlew desktop:audioPoc -Ddelones.audioPoc.stereo=true

# 4. Underrun recovery (skips 5 frames of writes at t=10s and t=30s):
./gradlew desktop:audioPoc -Ddelones.audioPoc.underrunTest=true
#    LISTEN: audible gap/click at 10 s and 30 s, then clean tone resumes
#    with no app intervention.
```

### Web (POC-W)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
export PATH="$JAVA_HOME/bin:$PATH"

# Build + serve the live web app:
./gradlew html:generateJavaScript
cp html/webapp/index.html html/webapp/styles.css html/build/generated/teavm/js/
(cd html/build/generated/teavm/js && python3 -m http.server 8080)

# Open in Chrome:
#   http://localhost:8080/?audioPoc=1
```

Then:

1. **Autoplay gate:** before clicking anything, console should show
   `[audioPoc] AudioContext created, state=suspended` and NO tone.
   Click the canvas (or the Load ROM button) → console logs the
   `resume()` + state transition to `running`, tone starts.
2. **Soak:** leave running 60+ s with the emulator visibly at 60 FPS
   (DK ROM if available; nestest fallback otherwise). LISTEN for a
   clean tone; WATCH the per-second `[audioPoc]` line — ring occupancy
   min/max must stay bounded (no monotonic drain to 0 / growth to the
   ring cap), starved-callback count should stop increasing after
   startup.
3. **Sample rate:** record the logged `ctx.sampleRate` (44100 vs
   48000) in this doc for D12.
4. **Second engine:** repeat 1–2 in Firefox or Safari.
5. **Flag off:** reload without `?audioPoc=1` — no `[audioPoc]` console
   lines, no tone, emulator unchanged.

## Fallback notes

**SPN zero-input-channel quirk (check BEFORE concluding binding failure):**
several engines have historically not fired `onaudioprocess` when
`numberOfInputChannels == 0` (long-standing Chromium/WebKit SPN quirk).
If the soak yields silence with `callbacks=0` in the per-second log, try
`createScriptProcessor(2048, 1, 1)` (ignore the input) before pivoting
to the AudioBufferSourceNode fallback.


## Addendum — one more manual item for the same browser session

While running the POC-W checklist, ALSO capture the **web perf number**
owed by the Phase B D9 waiver (docs/apu-plan.md): load the page WITHOUT
`?audioPoc=1`, let the emulator run 60s, and record the steady-state
`runFrame=X.XXms` value from the per-second console log. Compare against
a pre-A1 build if available; otherwise record the absolute number here —
the Phase E gate needs it on file.
