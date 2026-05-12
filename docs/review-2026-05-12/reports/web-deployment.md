# JS/web deployment investigation

**Agent:** general-purpose, Opus, read-only worktree off `master`.
**Goal:** what would it take to deploy deloNES as a JS/web build via
LibGDX's TeaVM backend.

## Current state

**Backend selected: TeaVM (modern), with GWT XML files left over as
stale scaffolding.**

- `html/build.gradle` applies `org.teavm` and `org.gretty` plugins,
  depends on `com.github.xpenatan.gdx-teavm:backend-teavm:1.4.0` and
  `gdx-freetype-teavm:1.4.0` (root `build.gradle` lines 18, 137-155).
  Excludes Log4j from the html configurations.
- Root `build.gradle:18` pulls `org.teavm:teavm-gradle-plugin:0.13.0`
  onto the buildscript classpath.
- TeaVM config in `html/build.gradle:7-11`: entry point
  `net.lomibao.nes.client.HtmlLauncher`, output `classes.js`,
  `addedToWebApp=true`.
- Stale GWT artifacts on disk but not driving the build:
  `html/src/net/lomibao/nes/GdxDefinition.gwt.xml`,
  `GdxDefinitionSuperdev.gwt.xml`, `core/src/NesEmulator.gwt.xml`.
  Reference `com.badlogic.gdx.backends.gdx_backends_gwt` and
  `controllers-gwt`, neither on the classpath. Safe to delete.
- Two TeaVM entry points in `html/src/net/lomibao/nes/client/`:
  - `HtmlLauncher.java` — wraps `NesEmulator` (the old single-ROM nestest
    playground in `core/src/.../NesEmulator.java`, **not** `NesGame`).
  - `NestestHtmlLauncher.java` — wraps `NestestBackgroundRenderer`.
    Not referenced by `teavm.js.mainClass`, currently dead.
- `html/src/org/apache/logging/log4j/{Logger,LogManager}.java` +
  `util/Supplier.java` are stub log4j classes routing `log.info/...`
  through `Gdx.app.log`. Cover all 5 log method names used in `core`.
- `html/webapp/index.html` and `styles.css` exist; index.html references
  `classes.js` and uses `<canvas id="canvas">`.
- The new `NesGame` / `RomSelectScreen` / `EmulatorScreen` /
  `ControlsConfig` / `KeyboardInputAdapter` / `GdxKeyState` /
  `iNESHeaderValidator` stack lives in `desktop/`, not `core/`. The
  html module does **not** depend on `:desktop`, so none of it is
  reachable from web. `HtmlLauncher` runs the older `NesEmulator` which
  only loads nestest, has no menu, no controllers, draws a placeholder
  gradient + the badlogic.jpg texture.

## What compiles today

`./gradlew html:generateJavaScript` — **BUILD SUCCESSFUL in 51s**.
Produces:

- `html/build/generated/teavm/js/classes.js` (446 KB unminified)
- `html/build/generated/teavm/js/scripts/{gdx.wasm.js, howler.js}`
- `html/build/generated/teavm/js/{badlogic.jpg, SpaceMono-*.ttf, ...}`
  (copy of `assets/` only — gradle `doLast` block at
  `html/build.gradle:60-66`)

No deprecation/error warnings beyond Gradle 9 noise unrelated to TeaVM.

Build "succeeds" but the output **will not run** — silent runtime
blockers in the audit below. No `html:build` task runs the page;
`generateJavaScript` is the meaningful step.

## Compatibility audit

| File / class | Status | Reason |
|---|---|---|
| `core/.../NesEmulator.java` (current `HtmlLauncher` target) | **broken** | Constructor reads `/nestest.nes` and `/palettes/ntscpalette.pal` via `getResourceAsStream`. Builds a `CPU6502` whose constructor reads `/opcodes/opcodes.csv`. TeaVM only embeds classpath resources whose paths are registered via the `org.teavm.classlib.ResourceSupplier` SPI. All three reads return `null`. |
| `core/.../CPU6502.java:222,242,247,255` | **broken** (showstopper) | Opcode dispatch uses `cpu.getClass().getDeclaredMethods()` + `Method.invoke`. TeaVM's `TeaReflectionSupplier` only enables reflection on classes matching a `clazzList` set hard-wired to `com.badlogic.gdx.scenes.scene2d` and `net.mgsx.gltf.data`. `net.lomibao.nes.components.CPU6502` is not registered; `getDeclaredMethods()` returns empty; every instruction silently no-ops; CPU never executes. The single biggest blocker. |
| `core/.../CPU6502.java:313` | **broken** | `getResourceAsStream("/opcodes/opcodes.csv")` returns null in TeaVM unless declared via a `ResourceSupplier`. Constructor throws. |
| `core/.../Cartridge.java` | works | Reads bytes from `InputStream`. Pure-Java byte manipulation. Lombok pre-processed. |
| `core/.../util/RomCatalog.java` | partial | `scanFilesystem()` uses `File.list`, `URLDecoder.decode`, `java.util.jar.JarFile`. None work in TeaVM. Method catches `Exception` and returns empty — silent failure. `readManifest` uses `getResourceAsStream("roms/index.txt")` which works with TeaVM's classpath-resource embedding configured. `openRom(name)` uses `getResourceAsStream` — same fix. |
| `core/.../components/PPU.java`, `PPUBus.java`, `ppu/*.java` | works | Plain Java arrays + bitops. No reflection, no I/O. Lombok-stubbed. |
| `core/.../components/CPUBus.java`, `Ram.java`, `Controller.java`, `DmaController.java`, `APU.java` | works | Same. |
| `core/.../components/ppu/ColorPalette.java` | partial | Constructor reads `/palettes/ntscpalette.pal`. Same resource-embedding fix. |
| `core/.../render/PixelRenderer.java` | works | Uses `Pixmap`/`Texture` — fully supported by gdx-teavm. |
| `core/.../render/NestestBackgroundRenderer.java` | partial | Same resource issues. |
| `desktop/.../NesGame.java` | unreachable from html | Not on html classpath. `Gdx.files.local("controls.json")` for TeaVM is `localStorage`-backed — works in principle, unreachable until we depend on `:desktop` or move the class to core. |
| `desktop/.../screen/EmulatorScreen.java` | reachable if moved/depended | Pure-Java + gdx. ROM via `RomSource` (classpath variant works; `Gdx.files.absolute` variant has no real TeaVM backing). |
| `desktop/.../screen/RomSelectScreen.java` | **broken on web** | Imports `org.lwjgl.PointerBuffer`, `MemoryStack`, `TinyFileDialogs` — LWJGL3 native libs, won't link in TeaVM. Need alternative file-input flow (HTML `<input type=file>` via JSO interop) or remove the browse entry on web. |
| `desktop/.../screen/iNESHeaderValidator.java` | works | Pure-Java byte/string checks. |
| `desktop/.../input/ControlsConfig.java` | works | gdx `Json` is in gdx-core and TeaVM-supported. `FileHandle.writeString` to `Gdx.files.local("controls.json")` writes to `localStorage`. Persisted across sessions. |
| `desktop/.../input/{GdxKeyState,KeyboardInputAdapter}.java` | works | `Gdx.input.isKeyPressed` is implemented by gdx-teavm. |
| `desktop/.../DKDiagnosticRunner.java` | exclude | Headless harness — no reason to ship to web. |
| Lombok-generated code in core | works | Compile-time; TeaVM sees plain bytecode. `html:compileJava` succeeds. |
| Java 8 / 11 source compat | works | TeaVM 0.13.0 handles through Java 17 bytecode. |
| `core/.../debug/TileDebugger.java:7-8` (`java.nio.charset`, `java.nio.file`) | safe if isolated | Used only by `CHRTileViewerLauncher` in desktop. Not on the html runtime path. |

### Performance expectations

`runFrame()` runs ~89,000 master ticks per frame. TeaVM-compiled JS for
tight numeric loops on modern V8 is typically 3-10× slower than JVM.
DK runs at 60 fps on desktop → should fit on a desktop browser.

**Bigger risk:** per-opcode reflection dispatch (`Method.invoke` on every
CPU cycle). TeaVM-emulated reflection is *much* slower than direct
calls. Going to web will force a refactor of `CPU6502` to a static
dispatch table (giant `switch` or `Runnable[]` populated once).

## Path to a minimal working web demo

Goal: page loads, ROM-select menu shows, user picks nestest, frame
renders. Bypass `NesEmulator` entirely — wire the modern `NesGame` flow.

1. **Move game-glue classes from `desktop/` to `core/`** (or a new
   `core/.../game/`): `NesGame`, `EmulatorScreen`, `RomSelectScreen`
   (minus filesystem browse), `iNESHeaderValidator`, `RomSource` +
   inner `ClasspathRomSource`, `ControlsConfig`, `GdxKeyState`,
   `KeyboardInputAdapter`, `KeyState`. Rename packages from
   `net.lomibao.nes.desktop.*` to `net.lomibao.nes.game.*`. Update
   desktop launcher imports.
2. **Strip TinyFileDialogs filesystem-browse from `RomSelectScreen`.**
   Delete lines 11-13, 48 (`BROWSE_ENTRY`), 99
   (`entries.add(BROWSE_ENTRY)`), and `browseForRom()` (lines 229-264).
   Optionally re-introduce via a `FileBrowser` interface with desktop
   and html impls later.
3. **Eliminate reflection in `CPU6502`.** Replace `Method handler` /
   `Method addressingHandler` fields in `CPU6502.Instruction` with two
   `Runnable` (or two `@FunctionalInterface IntSupplier`) fields.
   Populate at instruction-table construction by switching on the
   opcode name string. ~70 opcode names × 13 addressing modes —
   mechanical, half-day of careful work plus a `NestestTest` parity
   run.
4. **Register classpath resources for TeaVM embedding.** Implement an
   `org.teavm.classlib.ResourceSupplier` returning
   `["opcodes/opcodes.csv", "palettes/ntscpalette.pal",
   "roms/nestest.nes", "roms/index.txt"]`. Drop a
   `META-INF/services/org.teavm.classlib.ResourceSupplier` file in
   `html/src/main/resources/` listing the impl. Causes
   `ClassLoaderNativeGenerator` to base64-embed the bytes into
   `classes.js`.
5. **Rewire `HtmlLauncher.main` to instantiate `NesGame`** instead of
   `NesEmulator`.
6. **Update `html/build.gradle generateJavaScript.doLast`** to also
   copy `core/src/main/resources/roms/`, `opcodes/`, `palettes/` into
   the output dir — gdx-teavm's `Gdx.files.internal` uses XHR by
   default; even with TeaVM resource embedding, anything routed
   through `Gdx.files` needs an HTTP-reachable file. Belt-and-
   suspenders: embed via ResourceSupplier AND serve via HTTP.
   `RomCatalog`/`Cartridge`/`CPU6502` use raw `getResourceAsStream`
   and only need the embedding.
7. **Delete GWT files:** `html/src/.../GdxDefinition*.gwt.xml`,
   `core/src/NesEmulator.gwt.xml`, and the dead
   `NestestHtmlLauncher.java`.
8. **Verify in browser:** run `./gradlew html:generateJavaScript`,
   serve `html/build/generated/teavm/js/` (`python -m http.server`),
   open the page. Expect ROM-select to render and nestest to start.

**Estimated effort: ~2-3 days of focused work.** `CPU6502`
reflection-to-dispatch-table is the longest single item; everything
else is wiring.

## Path to full feature parity

Beyond a minimal demo:

1. **Keyboard input mapping for browser**: ensure canvas focus and that
   the page doesn't swallow `SHIFT`/`TAB`/`ENTER` (default hotkeys).
   Verify with a small key-display debug overlay.
2. **`controls.json` round-trip via `Gdx.files.local`**: gdx-teavm
   backs `local` with `localStorage`. Test save → reload → persisted.
3. **Audio**: deloNES has no working APU emulation (`APU.java` is
   mostly stub registers). No web-specific work until audio is added;
   `howler.js` is already extracted and ready.
4. **Performance pass**: profile after reflection removal. If CPU
   dispatch is still too slow, lift `Instruction.runInstruction()`
   into a switch directly inside `CPU6502.clock()`. PPU `runFrame` is
   already a tight loop and should be fine.
5. **File-browse alternative**: add a `RomFilePicker` interface; html
   backend implements with hidden `<input type="file">` via TeaVM JSO,
   read with `FileReader.readAsArrayBuffer` → `byte[]` →
   `Cartridge`. Current screen takes a `RomSource`, slots in cleanly.
6. **Bundle ROMs lawfully**: only `nestest.nes` is in repo. Web flavor
   is the file-picker above plus an optional "drag a `.nes` onto the
   page" handler — `TeaApplication.setupFileDrop` already wires DnD.
7. **Gradle config**: a `serveJs` task or a checked-in `index.html →
   build/dist/webapp` deploy pipeline. Existing `prepareWebapp` +
   `buildNestestHtml` tasks (`html/build.gradle:69-110`) reference
   outdated GWT paths. Replace.
8. **CI**: run `html:generateJavaScript` on PRs to catch regressions
   in TeaVM compatibility (a stray new `java.io.File` import elsewhere
   in core, etc.).

**Estimated effort: another 3-7 days** on top of the minimal demo.

## Risks

- **Reflection performance**: even with reflection registered for
  `CPU6502`, TeaVM reflection is slow enough to potentially miss 60 fps.
  Static-dispatch refactor is the safe path.
- **gdx-teavm bugs**: `com.github.xpenatan.gdx-teavm:backend-teavm:1.4.0`
  is community-maintained, not official LibGDX. Expect occasional
  surprises with `FreeType`, `Json`, `Pixmap` formats.
- **Resource embedding payload size**: nestest.nes ~24 KB, opcodes.csv
  ~30 KB, palette 192 bytes. Base64-embedded (~4/3 inflation) adds
  ~70 KB to `classes.js`. Fine.
- **Lombok `@SneakyThrows` + TeaVM**: works in quick test; watch for
  stack-trace oddities.
- **Browser CORS / file:// loading**: page must be HTTP-served (gretty
  setup does this). Opening `index.html` directly fails XHR for
  assets.
- **Stale GWT XML**: harmless today but confusing.

## Recommendation

TeaVM scaffolding is **usable but incomplete**. Every piece is wired
structurally (gradle plugin, deps, entry point, webapp shell, log4j
stub), the build genuinely compiles to JS, and prior work chose TeaVM
correctly. What's missing: (a) entry point points at the dead playground
class, (b) reflection isn't registered, (c) classpath resources aren't
embedded, (d) new screens live in `desktop/` not `core/`.

**Effort to playable-in-browser DonkeyKong: medium — ~1 working week.**
The `CPU6502` reflection removal is by far the highest-leverage change
and benefits the desktop build too (gratuitous reflection in a hot
loop).

**Recommended sequencing:**
1. Refactor `CPU6502` dispatch on desktop, gated by `NestestTest`.
2. Move game-glue classes from `desktop/` to `core/`.
3. Flip the html launcher to `NesGame`, add the `ResourceSupplier`,
   delete GWT cruft.
4. Iterate in browser.

---

**Working tree:** clean.
