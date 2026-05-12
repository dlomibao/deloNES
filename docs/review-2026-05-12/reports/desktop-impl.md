# Desktop implementation review

**Agent:** general-purpose, Opus, read-only worktree off `master`.
**Scope:** `desktop/` — NesGame, screens, input, NestestBackgroundRenderer,
DKDiagnosticRunner, DesktopLauncher, build.gradle.

## Severity: critical

### NestestBackgroundRenderer.java:102 + DKDiagnosticRunner.java:53 — hardcoded to missing ROM

Both hardcode `/roms/DonkeyKong.nes` as the ROM resource, but
`DonkeyKong.nes` is no longer in the repo (only `nestest.nes` is). At
runtime `getResourceAsStream` returns `null`, passed to
`Cartridge(InputStream, String)`. `Cartridge.toByteArray(null)` NPEs.

- `DKDiagnosticRunner` line 54 has an explicit `if (in == null) throw
  new RuntimeException("DonkeyKong.nes not on classpath")` so its
  failure mode is clear.
- `NestestBackgroundRenderer.loadROM()` (99-122) has **no null-check**
  and produces an opaque NPE wrapped in a RuntimeException.

**`./gradlew desktop:traceDK` and `./gradlew desktop:runNestest` are
both broken**, despite the kept-for-legacy-compat intent. Also: the
launcher is misnamed — `NestestBackgroundLauncher` advertises running
nestest but the renderer it instantiates loads DonkeyKong.

Fix: point both at `/roms/nestest.nes`, OR remove the legacy tasks, OR
accept a `-Drom=<path>` system property.

### NesGame.java:105-108 (`onRomSelected`) — RomSelectScreen leak

`setScreen(emulatorScreen)` runs without first calling
`getScreen().dispose()` on the existing `RomSelectScreen`. LibGDX
`Game.setScreen()` only calls `hide()` on the prior screen, never
`dispose()`. Every menu → emulator → menu cycle leaks the menu's
`SpriteBatch` + `BitmapFont`.

Compare with `returnToMenu()` (line 90-97) which correctly disposes the
EmulatorScreen. Symmetrize by disposing the menu in `onRomSelected`.

## Severity: medium

### EmulatorScreen.java:116-137 (`show()`) — bad ROM bricks the app

If `loadROM()` throws (line 165-179 wraps any exception in
`RuntimeException`), `show()` propagates. LibGDX `Game.setScreen()` has
already swapped `this.screen` to the new `EmulatorScreen` before
calling `show()`, so the next frame's render invokes
`EmulatorScreen.render()` on a partially-initialized instance:
`nesSystem`, `cpu`, `ppu` may all be null. `runFrame()` (line 246) NPEs.
The user has no path back to the menu. A bad ROM bricks the app.

Catch the load error inside `show()`, dispose any partial GL state,
call `onExit.run()` (or render an error message and let user press Esc).

### iNESHeaderValidator.java:52 — over-rejects valid ROMs, doesn't flag NES 2.0

Mapper number computed from byte 7's high nibble unconditionally. Per
NESdev, incorrect for two real-world cases:

1. **iNES 0.7 / "DiskDude!" headers**: many older NROM dumps stored
   garbage text in bytes 7-15 (e.g. `"DiskDude!"`). Convention: if
   bytes 12-15 are non-zero, treat byte 7's high nibble as unreliable
   and zero it out before computing mapper. Without this, perfectly
   valid NROM ROMs are rejected as "Unsupported mapper N".
2. **NES 2.0 detection**: per NESdev, `(byte7 & 0x0C) == 0x08` signals
   NES 2.0 format, where mapper number is also influenced by byte 8.
   The precheck silently treats NES 2.0 as iNES 1.0.

Since `core/.../INESHeader.java:65` uses the same flawed formula, both
precheck and loader agree (user-facing rejection in the menu matches a
loader-time failure). The core code is out of scope here, but the
desktop precheck inherits the same correctness gap.

### NesGame.java:42 — `controls.json` location depends on launch mode

`Gdx.files.local("controls.json")` resolves relative to working dir,
which differs:

- `./gradlew desktop:run` — `workingDir = ../assets/` → file ends up at
  `assets/controls.json` (likely committed accidentally on first run).
- IDE — usually the project module root.
- Fat JAR via `java -jar` — wherever invoked from.

Three different installations of "your" controls. Use
`Gdx.files.external(".deloNES/controls.json")` for a stable per-user
location.

### ControlsConfig.java:130-138 (`load`) — malformed config crashes startup

If `controls.json` exists but is malformed JSON, `Json.fromJson(...)`
throws `SerializationException` which propagates out of
`NesGame.create()` and crashes the app at startup with no fallback. A
user-editable config file should never brick the app — wrap in
try/catch, log, return `defaults()`. Optionally rename the bad file to
`controls.json.bak`.

### RomSelectScreen.java:296 — duplicated nested try/finally vs try-with-resources

`try { in.close(); } catch (IOException ignored) {}` after manual
finally-close — pattern duplicated in `readFirstBytesFromFile`
(line 327). Switch both to try-with-resources.

## Severity: low / nit

### KeyboardInputAdapter.java:97-99 — `prev*` flags persist across screen transitions

Adapter only polls on `EmulatorScreen` frames, but `prev*` persists.
Corner case: user holds a hotkey while on `RomSelectScreen`, returns to
`EmulatorScreen`, first hotkey press in new session may be swallowed.
Mitigation: reset `prev*` on entering `EmulatorScreen`, or always poll
and gate elsewhere.

### NesGame.java:58-66 — exit + pause/reset ordering

When `exitPressed()` and `pausePressed()`/`resetPressed()` both fire on
the same frame, we toggle pause/reset then exit. Cosmetic — state is
discarded by the screen transition.

### NesGame.java:96 — `returnToMenu()` disposes before `setScreen`

Order is `old.dispose(); setScreen(new RomSelectScreen)`. `Game.setScreen`
then calls `hide()` on the already-disposed screen. Works only because
`EmulatorScreen.hide()` is a no-op. Standard pattern is
`setScreen(new); old.dispose();`.

### NES master palette duplicated three times

`EmulatorScreen.java:266-278`, `NestestBackgroundRenderer.java:283-297`,
`DKDiagnosticRunner.java:197-208` each inline the same 64-entry NES
master palette. Likely a fourth copy in `core/.../render/ColorPalette`.
Extract to a single constant.

### EmulatorScreen.java:209-210 — unlabeled ARGB↔RGBA shuffle

`int rgba = (argb << 8) | (argb >>> 24);` is unexplained bit-twiddling.
Same pattern in `NestestBackgroundRenderer.java:165-169`. CLAUDE.md
asserts "PPU output is RGBA" — but `PPU.java:530` initializes the
buffer to `0xFF000000` ("Black with full alpha"), which matches **ARGB**.
The desktop side is self-consistent with the legacy renderer, but the
docstrings on both ends disagree. Sharpen the comments.

### EmulatorScreen.java:34-36 — stale class Javadoc

"Phase 2 glue is expected to wire a keyboard adapter that calls
`togglePause()`…" — that glue is now in `NesGame.java:58-66`. Update
class Javadoc.

### EmulatorScreen.java:161 — stale `ppu.setCPU(cpu)` comment

"No-op stub kept for parity with NestestBackgroundRenderer (Phase 0)" —
if `setCPU` is no longer a no-op, update; if it really is, justify
keeping it.

### DesktopLauncher.java:17 — unverifiable "retained as dead code" comment

`NesEmulator` should be either confirmed-dead-and-deleted, or
documented where it's referenced. Standalone Lwjgl3Application +
`NesGame` flow doesn't reference it.

### iNESHeaderValidator class name lowercase

Violates Java naming conventions (lowercase first letter). Cosmetic;
codebase appears to tolerate this elsewhere.

### RomSelectScreen.java:178 — layout edge case

`float errorY = listTop - entries.size() * LINE_HEIGHT - LINE_HEIGHT;`
collides with the navigation hint at `y=20f` when many entries are
present. Low priority — catalog is small today.

### RomSource.java:45 — caller-owns-close not documented

`ClasspathRomSource.open()` returns raw classpath InputStream; if
`Cartridge.toByteArray` throws mid-stream, the stream may leak. Callers
correctly use try-with-resources today (EmulatorScreen.java:166), so
fine in practice. Document the caller-owns contract.

### desktop/build.gradle:106-119 (`dist` task)

Fat jar uses `DuplicatesStrategy.EXCLUDE` which silently drops
conflicting entries. Consider `WARN` so duplicates are logged. Also no
`Implementation-Version` / `Built-Date` in manifest. Low priority for
a POC.

### DKDiagnosticRunner.java:194-208 — third copy of master palette

See palette-duplication note. As long as ALL palette tables stay in
sync, this is fine; if they diverge, the diagnostic's backdrop
classification will be silently wrong.

---

**Summary:** Two criticals (broken `runNestest`/`traceDK` due to missing
`DonkeyKong.nes`, and GL-resource leak in menu/emulator cycling).
Several mediums around bad-input handling: header over-rejection, ROM
load failure bricks app, controls.json location, malformed-config
startup crash. Rest are stale comments, duplicate constants, and minor
lifecycle ordering nits. Architecture otherwise clean.

**Working tree:** unchanged.
