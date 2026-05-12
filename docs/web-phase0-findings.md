# Phase 0 web derisk — findings

**Date:** 2026-05-12
**Branch:** `feature/web-phase0`
**Goal:** before investing in the full Tier C web port (C1-C7), prove the
gdx-teavm + TeaVM pipeline can actually reach a real browser, load
embedded ROMs, render pixel data, and receive keyboard input.

## Verdict

**🟢 Go.** The platform is viable for shipping. Page boots in Chrome,
all probes fire, and **the fast pixel-upload path hits sustained 60 FPS
on a 256×240 RGBA buffer** — equal to the desktop target.

## Stack at end of Phase 0

| Piece | Version | Notes |
|---|---|---|
| TeaVM gradle plugin | `0.14.0` (was 0.13.0) | clean bump |
| gdx-teavm backend | `backend-web:1.5.6` (was `backend-teavm:1.4.0`) | artifact **renamed** in 1.5.x; major API rewrite (`TeaApplication` → `WebApplication`) |
| libGDX | `1.14.0` | unchanged |
| Build JDK | `11` | works; no JDK 17 forced (audit confirmed 11 still supported across all deps) |

## Probe results

### 1. Render probe — ✅ works at 60 FPS (fast path)

256×240 RGBA `Pixmap` filled with a moving gradient per frame, uploaded
via `texture.draw(pixmap, 0, 0)`, drawn fullscreen via `SpriteBatch`.

**Two paths tested:**

| Path | Sustained FPS | Verdict |
|---|---|---|
| `pixmap.drawPixel(x,y,rgba)` × 61k calls/frame | **~3 FPS** | Unusable. Per-call JS overhead dominates. |
| `byte[256*240*4]` fill → `pixmap.getPixels().rewind().put(bytes)` → `texture.draw(pixmap,0,0)` | **~60 FPS sustained** | Ship this. |

For the fast path: 1026 frames in 18 wall-clock seconds = 57 real FPS;
`getFramesPerSecond()` agrees at 60-61. **This is the path the real
PPU→screen renderer should take** — pre-allocate one `byte[]` of
`NES_W*NES_H*4`, fill it from the PPU framebuffer, bulk-put into the
pixmap each frame, single `texture.draw()` call.

**`Gdx.graphics.getFramesPerSecond()` reliability:** trustworthy after
~1 second of warmup. The first FPS log of each run shows `61` regardless
of actual rate (likely the rAF tick rate before any measurement
stabilizes), but readings stabilize to the true rate within the first
second. Cross-checked by computing FPS from `frame_count /
elapsed_seconds` — matches the reported value after warmup.

**Earlier false negative (worth recording so future-me doesn't repeat):**
I initially declared the bulk-put path "silently broken" after seeing
no `phase0:` logs on first reload. The actual cause: I screenshot'd /
read the console before preload + create() had completed. The build
was fine; I just didn't wait long enough. Lesson: on gdx-teavm, give
preload + initial render at least ~10 seconds before declaring create()
didn't run.

### 2. Resource probe — ✅ works

`Gdx.files.internal("roms/nestest.nes").readBytes()` returns the
expected 24,592 bytes; first four bytes match iNES magic
`4e 45 53 1a`.

**Gotcha discovered:** gdx-teavm 1.5.x replaced raw resource fetching
with a **preload manifest**. The runtime fetches `assets/preload.txt` on
startup; if the file is missing the asset cache is empty and every
subsequent `Gdx.files.internal(...)` returns nothing. Format is
5-field colon-delimited per line:

```
i:b:roms/nestest.nes:24592:1
```
(fileType : assetType : url-relative-to-`assets/` : length : overwrite-cache-flag)

Build wiring (`html/build.gradle generateJavaScript.doLast`):

1. Copy root `assets/` to `${jsDir}/assets/` (keeps startup-logo.png etc.)
2. Copy `core/src/main/resources/roms/` to `${jsDir}/assets/roms/`
3. Scan and emit `${jsDir}/assets/preload.txt`

### 3. Input probe — ✅ works

Wired `Gdx.input.setInputProcessor(new InputAdapter())` with `keyDown`
logging. After clicking the canvas to focus + sending keys via CDP
`Input.dispatchKeyEvent`:

```
phase0: INPUT keyDown=29 (A)
phase0: INPUT keyDown=19 (Up)
phase0: INPUT keyDown=62 (Space)
```

Keycodes match desktop `Input.Keys.*` exactly (A=29, Up=19, Space=62).
The desktop `KeyboardInputAdapter` will port without keycode-translation
fixups.

Initial test attempts (via JS `KeyboardEvent` dispatch) didn't reach
the InputProcessor — canvas needed real CDP-level keystrokes after a
focusing click. **Implication for real port:** the page needs an
explicit click-to-focus story (one click on canvas, then keys work).
Standard for browser games; covered by the existing index.html's
`canvas.addEventListener('mousedown', ..., window.focus())` shim.

### 4. Reflection probe — skipped (intentionally)

Original plan included probing `CPU6502.class.getDeclaredMethods().length`
to determine if `TeaReflectionSupplier` reflection-registration is needed.
**Skipped** because the up-front research established that even with
working reflection, per-instruction `Method.invoke` would be 10–100×
slower than direct dispatch in JS — making C1 (CPU dispatch table
refactor) mandatory regardless of reflection support. Probing wouldn't
change any downstream decisions.

## Surprises / things the original audit missed or got wrong

1. **gdx-teavm 1.5.x is a major API rewrite.** Package `…backends.teavm`
   → `…backends.web`. Classes `TeaApplication`/`TeaApplicationConfiguration`
   → `WebApplication`/`WebApplicationConfiguration`. Artifact name
   `backend-teavm` → `backend-web`. Old artifact name's Maven Central
   release stopped at 1.4.0. Build will need to track the new
   artifact path going forward.

2. **Preload manifest is mandatory in 1.5.x.** No `preload.txt` = no
   `Gdx.files.internal(...)` access, regardless of whether the files
   are physically copied to the right path on disk. The audit didn't
   mention this because it was a 1.4.0 audit and 1.4.0 fetched
   resources directly.

3. **Audit's "TeaReflectionSupplier is hardcoded to scene2d+gltf" claim
   was outdated.** As of 1.4.0, `TeaBuildReflectionListener` SPI exposes
   per-class registration via predicate/regex. Doesn't change Phase 0
   conclusions (reflection at hot-loop frequency is too slow regardless)
   but is worth knowing.

4. **`pixmap.drawPixel` per-frame is unusable** on gdx-teavm — 61k
   per-pixel JS calls per frame caps at ~3 FPS. **The replacement is
   trivial and standard libgdx**: pre-allocate a `byte[NES_W*NES_H*4]`,
   write your RGBA bytes into it directly, then
   `pixmap.getPixels().rewind().put(bytes)` once per frame. Sustains
   60 FPS. Bake this into C2's renderer port from the start; don't ship
   a `drawPixel`-shaped PPU output path.

## Repository changes landed on this branch

- `build.gradle`: TeaVM plugin 0.13.0 → 0.14.0
- `build.gradle`: gdxTeaVMVersion 1.4.0 → 1.5.6, artifact
  `backend-teavm` → `backend-web`
- `html/build.gradle`: rewrote the `generateJavaScript.doLast` block to
  stage `assets/`, surface ROMs under `assets/roms/`, and emit
  `assets/preload.txt`.
- `html/src/.../HtmlLauncher.java`: full rewrite using new
  `WebApplication`/`WebApplicationConfiguration` API; embeds the three
  probes; includes inline `phase0:` logging.
- `html/src/.../NestestHtmlLauncher.java`: **deleted** (dead, already
  scoped under Tier C C6).

## Recommended next steps (post-Phase 0)

Now that the platform is proven, the real port plan from the audit
holds, with one adjustment:

1. **C1 (CPU6502 dispatch refactor) is unavoidable.** Schedule it first.
   It's the longest single item AND a desktop perf win regardless.
2. **C2's renderer port must use the bulk-put pixel path** (see "Render
   probe" above). Don't write `pixmap.drawPixel` into `EmulatorScreen`'s
   web-portable form. Reusable shape: a `Pixmap` + a pre-allocated
   `byte[NES_W*NES_H*4]` filled from the PPU's framebuffer, one
   bulk-put + one `texture.draw()` per frame. No new derisking required.

The audit's C2-C7 ordering otherwise stands. C8-C10 (PPU completeness)
and C11-C13 (tests) are unaffected by web platform decisions.

## How to reproduce

```bash
# On feature/web-phase0
JAVA_HOME='/c/Program Files/AdoptOpenJDK/jdk-11.0.8.10-hotspot' \
  ./gradlew html:generateJavaScript

cd html/build/generated/teavm/js
python -m http.server 8765

# Open Chrome at http://localhost:8765/
# DevTools console will show phase0: render/RESOURCE/INPUT/FPS logs
```
