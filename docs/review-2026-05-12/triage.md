# Triage plan

**Drive all post-review work from this file.** Check items as they land.
Tier A is bug fixes done together on `chore/review-cleanup`. Tier B is
correctness improvements after A merges. Tier C is roadmap.

When you complete an item, replace `[ ]` with `[x]` and add the commit
SHA in parens.

---

## Tier A — do on `chore/review-cleanup` (this branch)

Target: all items below in a single PR. Existing 372 tests stay green;
add 1–2 new tests where noted. Each item is short and mechanical.

### Critical correctness fixes

- [ ] **A1** — `CPUBus.write()` routes `$4014` to APU before DmaController.
  Today it works only because `apu` is `null` in NesSystem. The moment
  APU is wired, sprites vanish. Fix: route `$4014` to DMA first, or
  fan-out the way `$4016`/`$4017` does.
  Source: `core/src/net/lomibao/nes/components/CPUBus.java:39-67`.
  See [reports/core-impl.md § critical-1](reports/core-impl.md).

- [ ] **A2** — `Cartridge.java:74` shadowed local hides `nCHRBanks` field.
  Drop the leading `int` so the field actually gets set.
  Source: `core/src/net/lomibao/nes/components/Cartridge.java:74`.
  See [reports/core-impl.md § critical-2](reports/core-impl.md).

- [ ] **A3** — PPU `loadBackgroundShifters()` fires at cycle 1, duplicating
  col 1's bit 7. Per-tile-boundary 1-pixel stutter. Fix: skip `case 0`
  load when `cycle == 1`, OR shift fetch range to start at cycle 9.
  Add a regression test: pixel output across a tile-column boundary
  must match the loaded pattern (not duplicate).
  Source: `core/src/net/lomibao/nes/components/PPU.java:322-331`.
  See [reports/core-impl.md § medium-1](reports/core-impl.md).

- [ ] **A4** — `APU.getIndex()` bounds check uses `&&` where it must be `||`.
  Two-line fix. (Currently the bus filters via `inCPUBusRange` before
  calling, so it's latent — but the guard does not guard.)
  Source: `core/src/net/lomibao/nes/components/APU.java:57`.

- [ ] **A5** — `NestestBackgroundRenderer` and `DKDiagnosticRunner` are
  hardcoded to `/roms/DonkeyKong.nes` which is no longer committed.
  Both will NPE at runtime. Point them at `nestest.nes`, or accept a
  system property override, or guard `null` with a clear error message.
  Source: `desktop/src/net/lomibao/nes/desktop/NestestBackgroundRenderer.java:102`,
  `desktop/src/net/lomibao/nes/desktop/DKDiagnosticRunner.java:53`.
  See [reports/desktop-impl.md § critical-1](reports/desktop-impl.md).

- [ ] **A6** — `NesGame.onRomSelected()` leaks the prior `RomSelectScreen`
  on every menu→emulator transition. `Game.setScreen()` only calls
  `hide()`, never `dispose()`. Add a `getScreen().dispose()` before
  the swap (mirror `returnToMenu()`).
  Source: `desktop/src/net/lomibao/nes/desktop/NesGame.java:105-108`.
  See [reports/desktop-impl.md § critical-2](reports/desktop-impl.md).

### Test fixes

- [ ] **A7** — `EmulatorScreenTest` and `NesGameTest` reference
  `/roms/DonkeyKong.nes`. Switch to `/roms/nestest.nes` so a fresh
  checkout passes CI.
  Source: `desktop/test/net/lomibao/nes/desktop/screen/EmulatorScreenTest.java:37,88`,
  `desktop/test/net/lomibao/nes/desktop/NesGameTest.java:54`.
  See [reports/desktop-tests.md § critical-2](reports/desktop-tests.md).

- [ ] **A8** — `NesGameTest.nesGame_selectRom_transitionsToEmulatorScreen`
  doesn't assert the post-condition its name claims. Add
  `assertTrue(game.getScreen() instanceof EmulatorScreen, ...)` after
  the `selectRom()` call.
  Source: `desktop/test/net/lomibao/nes/desktop/NesGameTest.java:33-69`.
  See [reports/desktop-tests.md § critical-3](reports/desktop-tests.md).

- [ ] **A9** — `HeadlessTestSupport.runFrames()` double-disposes (race) and
  doesn't join the bg thread. Causes the intermittent
  `HeadlessApplicationTest.emptyScreen_renders3FramesWithoutError`
  failure and cross-test `Gdx.*` static state leakage. Fix path:
  (a) remove the manual `listener.dispose()` call, count via a second
  latch counted-down in the wrapper's `dispose()`; (b) `mainLoopThread.join()`
  before returning; (c) clear `Gdx.app/gl/files/input/audio/graphics/net`
  on exit.
  Source: `desktop/test/net/lomibao/nes/desktop/HeadlessTestSupport.java:85-106`.
  See [reports/desktop-tests.md § flaky-headless-app-test](reports/desktop-tests.md).

- [ ] **A10** — `PPURenderingTest` asserts `pixel != 0` but `PPU.clearScreen()`
  initialises to `0xFF000000` (non-zero). Five tests pass at
  construction. Replace with explicit "pixel differs from backdrop
  palette[0]" comparisons. After the fix, the BG-fetcher pipeline
  rewrite will have real coverage.
  Source: `core/test/net/lomibao/nes/components/PPURenderingTest.java:86-260`.
  See [reports/core-tests.md § critical-1](reports/core-tests.md).

- [ ] **A11** — `OpcodesTest.run()` / `runWithSetup()` infinite-loop guard
  is broken: the third byte of `JMP $7FFE` lands at `$8000`, then
  `ram.writeRange(0x8000, program)` clobbers it. Move the guard
  somewhere it won't be overwritten, or write it after `writeRange`.
  Source: `core/test/net/lomibao/nes/components/OpcodesTest.java:33-39,67-73`.
  See [reports/core-tests.md § critical-2](reports/core-tests.md).

### Acceptance for Tier A

- [ ] `./gradlew core:test desktop:test` — all tests green
- [ ] New regression tests for A3 and A10 land alongside their fixes
- [ ] `HeadlessApplicationTest` runs cleanly 10× in a row (`for i in 1..10; do gradle desktop:test --rerun-tasks; done`) — no flake
- [ ] Branch pushed, PR opened against `master`
- [ ] `gh pr checks` returns pass

---

## Tier B — separate PRs after Tier A

Each item is its own PR. Smaller PRs are easier to review and revert.

### Robustness / UX

- [ ] **B1** — `EmulatorScreen.show()` catches ROM-load failure and calls
  `onExit` instead of bricking. User stays in the menu with an error.
  Source: `desktop/src/net/lomibao/nes/desktop/screen/EmulatorScreen.java:116-137`.
  See [reports/desktop-impl.md § medium](reports/desktop-impl.md).

- [ ] **B2** — `iNESHeaderValidator`: implement the DiskDude-tag check
  (if bytes 12-15 non-zero, zero out byte 7's high nibble before
  computing mapper) and reject NES 2.0 (`(byte7 & 0x0C) == 0x08`) with
  a specific error. Mirror what `Cartridge` does, or move the canonical
  parse into core.
  Source: `desktop/src/net/lomibao/nes/desktop/screen/iNESHeaderValidator.java:52`.

- [ ] **B3** — `ControlsConfig` location: use
  `Gdx.files.external(".deloNES/controls.json")` so the file is in a
  stable per-user location regardless of launch mode.
  Source: `desktop/src/net/lomibao/nes/desktop/NesGame.java:42`.

- [ ] **B4** — `ControlsConfig.load()` catches `SerializationException`
  and returns `defaults()`, optionally renaming the bad file to
  `controls.json.bak`. Don't brick the app on a typo.
  Source: `desktop/src/net/lomibao/nes/desktop/input/ControlsConfig.java:130-138`.

### PPU correctness

- [ ] **B5** — NMI rising edge inside VBlank. Writing `$2000` with bit 7
  set while `PPUSTATUS` bit 7 is also set should assert NMI. Currently
  NMI fires only at the once-per-frame VBlank entry. Battletoads, etc.
  rely on this.
  Source: `core/src/net/lomibao/nes/components/PPU.java:272-279`.

- [ ] **B6** — `PPU.registers` → `private` with a narrow test seam. The
  public field bypasses register side-effects (PPUSTATUS read clears
  bit 7 + latch, etc.).
  Source: `core/src/net/lomibao/nes/components/PPU.java:13`.

- [ ] **B7** — DmaController wait-state re-entry alignment. Real hardware
  inserts at least 1 dummy read before each DMA burst; current
  `waiting=true` on completion may shorten the next burst's wait
  phase by 1 cycle depending on alignment.
  Source: `core/src/net/lomibao/nes/components/DmaController.java:71-74`.

### Test improvements

- [ ] **B8** — `NestestTest` uncomment the P-flag and cycle-count
  comparisons (currently the claim of "8992/8992 line match" is
  misleading). Fix whatever those comparisons surface.
  Source: `core/test/net/lomibao/nes/components/NestestTest.java:75-79`.

- [ ] **B9** — `StandardControllerTest` invert the obsolete P2-on-$4017
  tests: assert P2 IS supported, not that it returns open-bus.
  Source: `core/test/net/lomibao/nes/components/StandardControllerTest.java:228-242`.

- [ ] **B10** — `PPUSpriteZeroHitTest` add the missing `x == 255` test
  (drive a sprite-0 column that overlaps x=255 only, with both layers
  on, assert bit 6 stays clear). Also add BG-transparent-at-overlap
  case using `setBgPatternPixelForTest` to override the seeded shadow
  at the exact target cell.
  Source: `core/test/net/lomibao/nes/components/PPUSpriteZeroHitTest.java`.

- [ ] **B11** — Cover the `NesGame.render()` polling branch. Inject a
  spy `KeyboardInputAdapter` (or a counting `KeyState`) and assert
  `poll()` runs only on `EmulatorScreen` frames, not `RomSelectScreen`.
  Source: `desktop/src/net/lomibao/nes/desktop/NesGame.java:49-71`.

### Cleanup

- [ ] **B12** — Extract the 64-entry NES master palette to a single
  constant in `core/.../render/`. Currently duplicated in
  `EmulatorScreen`, `NestestBackgroundRenderer`, and `DKDiagnosticRunner`.

- [ ] **B13** — Update stale comments: `EmulatorScreen` class Javadoc
  ("Phase 2 glue is expected to wire ...") and the `ppu.setCPU(cpu)`
  "no-op stub for parity" comment.

- [ ] **B14** — Delete `NesEmulator` if confirmed dead (no Gradle task
  or class references it). The "retained as dead code" comment is
  unverified.
  Source: `desktop/src/net/lomibao/nes/DesktopLauncher.java:17`.

- [ ] **B15** — `PaletteMemory.java` and `PatternMemory.java` in
  `core/src/.../ppu/` are unused (default `ppuBusRead/Write` from
  `PPUBusComponent` would infinite-loop). Either delete or implement.

- [ ] **B16** — `RomCatalog.scanFilesystem()` swallows all exceptions
  silently. Log at debug so misconfigurations are recoverable.

### Acceptance for Tier B

- [ ] Each item lands as its own PR
- [ ] Tests + CI green for each
- [ ] Cross off in this doc as PRs merge

---

## Tier C — roadmap

Larger pieces. Pick up when there's appetite. **C1 unblocks C2 and is
high-leverage on its own** (CPU dispatch is in every game's hot loop).

### Web build

- [ ] **C1** — Refactor `CPU6502` dispatch to remove `Method.invoke`.
  Replace `Method handler` / `Method addressingHandler` fields with
  `Runnable` (or `IntSupplier`) populated at instruction-table build
  time via opcode-name `switch`. Gates the entire web port and
  significantly improves desktop performance.
  Source: `core/src/net/lomibao/nes/components/CPU6502.java:222,242,247,255`.
  See [reports/web-deployment.md § blockers](reports/web-deployment.md).

- [ ] **C2** — Move game-glue classes from `desktop/` to `core/`:
  `NesGame`, `EmulatorScreen`, `RomSelectScreen` (sans TinyFileDialogs),
  `iNESHeaderValidator`, `RomSource`, `ControlsConfig`, `KeyState`,
  `GdxKeyState`, `KeyboardInputAdapter`. Rename packages to
  `net.lomibao.nes.game.*`. Update desktop launcher imports.

- [ ] **C3** — Make ROM-menu "Browse filesystem…" backend-agnostic.
  `FileBrowser` interface; desktop impl uses `TinyFileDialogs`, html
  impl uses HTML `<input type="file">` via TeaVM JSO interop.

- [ ] **C4** — Register classpath resources for TeaVM embedding.
  Implement `org.teavm.classlib.ResourceSupplier` listing
  `opcodes/opcodes.csv`, `palettes/ntscpalette.pal`, `roms/index.txt`,
  `roms/nestest.nes`. Drop into
  `html/src/main/resources/META-INF/services/...`.

- [ ] **C5** — Flip `HtmlLauncher.main` to instantiate `NesGame`, not
  `NesEmulator`. Update `html/build.gradle` to copy ROM/opcode/palette
  resources into the webapp output. Verify in browser via
  `./gradlew html:generateJavaScript` + `python -m http.server` from
  the output dir.

- [ ] **C6** — Delete stale GWT artifacts:
  `html/src/.../GdxDefinition.gwt.xml`,
  `html/src/.../GdxDefinitionSuperdev.gwt.xml`,
  `core/src/NesEmulator.gwt.xml`, and the dead
  `NestestHtmlLauncher.java`.

- [ ] **C7** — Add CI run for `html:generateJavaScript` on PRs so future
  changes don't silently break web compat.

### PPU completeness

- [ ] **C8** — Odd-frame cycle skip. Real PPU drops cycle 0 of pre-render
  on odd frames when rendering is enabled. `oddFrame` is tracked but
  unused. Update `PPUClockTest` expected cycle count (89342 → 89341
  on odd) when enabling.

- [ ] **C9** — Sprite per-scanline 8-sprite limit + overflow flag
  (`PPUSTATUS` bit 5). Current `SpriteRenderer` renders all 64 sprites.
  Needed for correct flicker and overflow-flag-dependent timing.

- [ ] **C10** — NMI sampling at instruction boundaries (not on master
  tick mid-instruction). Current code asserts NMI immediately on
  `consumeNmi()`. The NMI suppression race exists but isn't modelled.

### Testing infrastructure

- [ ] **C11** — Add `CartridgeTest`, `Mapper000Test`, `INESHeaderTest`.
  Currently iNES parsing is exercised only transitively via
  `NestestTest`.

- [ ] **C12** — Move `PixelRendererTest.java` from `core/src/` to
  `core/test/` (it's in the wrong source set).

- [ ] **C13** — Replace `SpyController extends Controller` in
  `KeyboardInputAdapterTest` with a `ControllerSink` interface that
  both the production class and the spy implement. Removes the
  hidden coupling to a non-final method signature.

### Acceptance for Tier C

- [ ] C1 lands first; verify `NestestTest` still 8992/8992
- [ ] C2-C7 ship together as the "web demo" milestone
- [ ] C8-C10 each separate PRs
- [ ] C11-C13 each separate PRs
