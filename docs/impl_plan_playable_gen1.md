# Implementation Plan — "Playable First-Generation Games"

Companion to [`feature_plan_playable_gen1.md`](feature_plan_playable_gen1.md). Where the
feature plan defines *what* and *why*, this doc defines *how*: a strict
test-driven order of operations, the regression guards that protect the
existing 8992/8992 nestest baseline, and a parallelisation analysis showing
where multiple subagents can work simultaneously without stepping on each
other.

---

## 1. Capability progression (after each step)

Baseline: CPU passes nestest, backgrounds render from CHR ROM, CHR tile viewer
works, no input, no sprites, no scroll, timing ad-hoc.

| Step | Sub-feature | What deloNES can do after this step | What still won't work |
|---|---|---|---|
| 0 | *(baseline)* | Run nestest headlessly; view CHR tiles; render static BG | No input / sprites / scroll / pacing |
| 1 | `NesSystem` master tick | Any ROM's CPU + PPU advance via single `runFrame()`; BG reflects what the game's NMI handler writes; launchers stop duplicating the tick loop | Still no input / sprites / scroll |
| 2 | NMI hook + `runWithCallback` | Per-instruction tracer trivial; headless tests subscribe to frame events; **Snake assembly demo runnable with no PPU** (`desktop:runSnake`); nestest log-diff becomes a snapshot test | No visible game characters yet |
| 3 | `StandardController` | Keyboard input works for any non-sprite-dependent scene: DK title starts a game, menus navigate, intros advance; Snake is keyboard-playable | Playfield still blank — no Mario, no DK, no ghosts |
| 4 | OAM DMA `$4014` | Games' shadow-OAM reaches the PPU; CPU correctly suspended ~513 cycles | Sprites in OAM but still not rendered |
| 5 | Sprite renderer (MVP) | **Donkey Kong, Pac-Man, Galaga, Balloon Fight, Ice Climber, Mario Bros. playable on single screen** — characters animate, h/v flip + 8×16 + per-pixel priority all work | SMB crashes past title (no sprite-0); scrolling games can't scroll |
| 6 | Sprite-0 hit (coarse) | SMB / Ice Climber / Excitebike status-bar splits work; games polling `$2002` bit 6 stop hanging; nesdev sprite-0 tests partially pass | *Punch-Out!!* still fails (needs opaque-pixel accuracy) |
| 7 | Scroll MVP (viewport-rect) | **Super Mario Bros. playable end-to-end**: Mario walks right through 1-1, pipes scroll, status bar stays put via sprite-0 split | Split-scroll mid-scanline glitches (rare in NROM); *Battletoads* HUD wrong (it's MMC3 anyway) |
| 8 | Frame pacing 60 Hz | Game speed is real (Mario runs at correct speed); `desktop:runRom -Prom=...` is the shipping demo; headless replay deterministic | No sound; Mapper 0 only; NTSC only |

---

## 2. The standing regression guards

Before any sub-feature work begins, and after every commit:

```bash
./gradlew core:test --tests net.lomibao.nes.components.NestestTest
```

**Must remain 8992/8992.** This is non-negotiable; any sub-feature that breaks
nestest blocks merge until it passes again.

```bash
./gradlew core:test
```

**Full test suite must stay green.** Existing PPU tests
(`PPUClockTest`, `PPUVBlankTest`, `PPUNMITest`, `PPUTileFetchTest`,
`PPURenderingTest`, `PPUNametableAccessTest`, `PPUControlRegistersTest`,
`PPUBusTest`, `NameTableMemoryTest`, `TileDecoderTest`) are the safety net
for PPU refactors. If any flake or break, fix the test before proceeding.

A useful pattern: each sub-feature opens a `_xxx_baseline_passes` JUnit test
*first* that simply re-runs nestest, so a CI failure pinpoints which sub-feature
broke the baseline.

---

## 3. Per-step TDD order of operations

For every step the rule is:

1. **Red** — write the new tests first (compile + fail for the right reason)
2. **Green** — minimum impl to pass the new tests
3. **Refactor** — clean up; full suite stays green
4. **Regression** — re-run nestest; commit

Below, each step lists:
- **Tests-first** — the JUnit classes and the per-class assertions to write
- **Impl steps** — minimum code, in order, to turn the tests green
- **Files touched** — paths a subagent or contributor must know about
- **Definition of done** — measurable

### Step 1 — `NesSystem` master tick

**Tests-first** (`core/test/.../NesSystemTest.java`):
- `tick_advancesPpuOncePerCall`
- `tick_advancesCpuEveryThirdCall`
- `runFrame_runsUntilFrameComplete_thenReturns`
- `runFrame_invokesPpuClockExactly341x262TimesPerFrame` (with rendering off — odd-frame skip not yet relevant)
- `nestest_baseline_passes_via_NesSystem` — wraps the existing nestest run inside `NesSystem` and asserts identical 8992/8992

**Impl steps:**
1. New `core/src/net/lomibao/nes/NesSystem.java` with constructor wiring CPU + CPUBus + PPU + PPUBus + Cartridge + Controller.
2. Methods: `tick()`, `runFrame()`, `reset()`.
3. Keep legacy `cpu.clock()` callable (existing tests use it); `NesSystem.tick()` becomes the new owner.
4. Update `desktop/src/.../NestestBackgroundRenderer.java:230` to call `NesSystem.runFrame()` instead of the inline loop.

**Files touched:** new `NesSystem.java`; light edits to `NestestBackgroundRenderer.java`. CPU/PPU/CPUBus untouched.

**Done when:** `NesSystemTest` green, all existing PPU + CPU tests green, nestest 8992/8992.

---

### Step 2 — NMI hook + `runWithCallback`

**Tests-first:**
- `core/test/.../CPU6502RunWithCallbackTest.java`:
  - `runWithCallback_invokesCallbackBeforeEachInstruction`
  - `runWithCallback_canStopByThrowingFromCallback`
  - `runWithCallback_compatibleWithNestestTrace` (golden file — uses callback to emit the same trace lines NestestTest already validates)
- `core/test/.../NesSystemNmiHookTest.java`:
  - `frameRenderedListener_firesOnceOnFalseToTrueNmi`
  - `frameRenderedListener_doesNotFireWhenNmiSuppressedByPPUCTRLBit7Zero`

**Impl steps:**
1. Add `PPU.consumeNmi() : boolean` (returns + clears latch). Stop calling `cpu.nmi()` from inside PPU.
2. `NesSystem.tick()` polls `ppu.consumeNmi()` and calls `cpu.nmi()` itself.
3. `NesSystem.setFrameRenderedListener(Consumer<NesSystem>)` invoked at the same poll point.
4. `CPU6502.runWithCallback(Consumer<CPU6502> beforeEachInstruction)` — wraps the existing `clock()` loop.

**Files touched:** `PPU.java`, `CPU6502.java`, `NesSystem.java`. **Hot file conflict** with step 1 (NesSystem) — must complete after step 1.

**Done when:** new tests green; `NestestTest` still 8992/8992 (the test now optionally uses the callback to assert trace, but old assertion path stays).

---

### Step 3 — `StandardController` (intentionally moved earlier than original feature plan order — independent file, can run parallel to step 2)

**Tests-first** (`core/test/.../StandardControllerTest.java`):
- `write_strobeHigh_freezesIndexAtZero`
- `write_strobeLow_doesNotResetIndex`
- `read_returnsButtonsInOrder_AThenBThenSelectThenStartThenUDLR`
- `read_returnsOneAfterEighthRead_untilNextStrobe`
- `setButton_updatesLiveStateNotLatchedSnapshot_whileStrobeLow`
- `setButton_updatesLatchedSnapshot_whileStrobeHigh`

**Impl steps:**
1. Replace `core/src/.../components/Controller.java` body with bugzmanov-shape implementation (Java code in [bugzmanov_nes_ebook_review.md §6](bugzmanov_nes_ebook_review.md#6-joypad--controller-ch-8)).
2. Keep address range `$4016..$4017` so existing CPUBus wiring still works.
3. Add public `setButton(int mask, boolean pressed)`.
4. **Don't** wire keyboard yet — desktop input can come later (under step 8) or in a small follow-up; the test surface here is pure unit-tested.

**Files touched:** `Controller.java` (rewrite). Zero overlap with steps 1, 2, 4, 5, 7 — fully parallelisable.

**Done when:** new tests green; nestest 8992/8992 (controller is rarely touched by nestest itself but the bus wiring must still work).

---

### Step 4 — OAM DMA (`$4014`)

**Tests-first** (`core/test/.../DmaControllerTest.java`):
- `writeTo4014_setsDmaPending_andRecordsPage`
- `dma_copies256BytesFromCpuRamToOam_inOrder`
- `dma_takes513CyclesWhenStartedOnEvenCycle`
- `dma_takes514CyclesWhenStartedOnOddCycle`
- `dma_suspendsCpu_duringTransfer`
- `dma_resumesCpuAfterTransfer`

**Impl steps:**
1. New `core/src/.../components/DmaController.java`, `CPUBusComponent` at `$4014`.
2. Add package-private `PPU.writeOam(int idx, byte v)` setter.
3. `NesSystem.tick()` checks `dma.isActive()` *before* invoking `cpu.clock()`; DMA gets the cycle instead.
4. State machine inside DMA: align-to-even, then 256× alternating read/write.

**Files touched:** new `DmaController.java`; small additions to `PPU.java` (oam setter) and `NesSystem.java` (cycle arbitration). **Conflicts with step 5 (sprites)** — both add code paths that depend on OAM. Step 4 must finish before step 5.

**Done when:** new tests green; nestest 8992/8992 (nestest itself doesn't issue DMA, but adding DMA to the bus must not break existing reads/writes).

---

### Step 5 — Sprite renderer (MVP)

**Tests-first** (`core/test/.../SpriteRendererTest.java` + `core/test/.../components/PPUSpriteTest.java`):
- `singleSpriteAt_x100_y50_rendersExpectedPixels`
- `spritePixel_value0_isTransparent`
- `horizontalFlip_mirrorsPixelsLeftRight`
- `verticalFlip_mirrorsPixelsTopBottom`
- `bothFlips_mirrorBothAxes`
- `eightBySixteenMode_picksPatternTableFromTileIdBit0_notPpuctrlBit3`
- `priorityBack_spriteHiddenWhereBgPixelNonZero`
- `priorityFront_spriteOverridesBg`
- `mask_disablesSpritesInLeft8PixelsWhenBit2Clear`
- `mask_disablesAllSpritesWhenBit4Clear`
- `lowerOamIndexDrawsOnTopOfHigherIndex`

**Impl steps:**
1. New `core/src/.../components/ppu/SpriteRenderer.java`. Pure function: `(byte[] oam, byte[] chr, byte[] palette, int spritePatternBase, boolean is8x16, byte ppumask) -> int[][] spriteLayer`.
2. Modify `PPU` to call `SpriteRenderer` once per frame at the appropriate boundary (post-background, pre-vblank), composite into `screen[][]` honouring per-pixel priority.
3. Honour PPUMASK bits 2 + 4.

**Files touched:** new `SpriteRenderer.java`; modifies `PPU.java` rendering hot path. **Conflicts with step 6 (sprite-0 hit), step 7 (scroll)** — all touch `PPU.java`. Sequence: 5 → 6 → 7.

**Done when:** new tests green; existing `PPURenderingTest` still green; nestest 8992/8992; manual smoke test: load Donkey Kong ROM via `runNestest`-style launcher, observe Mario + DK + barrel sprites on title screen.

---

### Step 6 — Sprite-0 hit (coarse)

**Tests-first** (`core/test/.../components/PPUSpriteZeroHitTest.java`):
- `bit6_setWhenSpriteZero_yEqualsScanline_andCycleGreaterEqualX_andRenderingOn`
- `bit6_clearedAtPreRenderScanline261Dot1`
- `bit6_notSetWhenSpritesDisabled`
- `bit6_notSetWhenBackgroundDisabled`

**Impl steps:**
1. In `PPU.clock()` per-cycle path, after the per-pixel mux, check the coarse predicate from [bugzmanov_nes_ebook_review.md §5.7](bugzmanov_nes_ebook_review.md#57-sprite-0-hit-ch-7).
2. OR bit 6 of `registers[2]` (PPUSTATUS) on hit.
3. Existing pre-render clear at `PPU.java:236` already handles the reset half.

**Files touched:** `PPU.java` only. **Conflicts with step 7** — both touch `PPU.clock()`. Run after step 5 and before step 7 (or after step 7 if step 7 doesn't touch the sprite path; reviewing the merge before commit is mandatory either way).

**Done when:** new tests green; nestest 8992/8992; manual smoke test: SMB title screen no longer hangs after pressing START.

---

### Step 7 — Scroll MVP (viewport-rect)

**Tests-first:**
- `core/test/.../components/PPUScrollRegisterTest.java`:
  - `ppuScroll_firstWriteSetsCoarseXAndFineX`
  - `ppuScroll_secondWriteSetsCoarseYAndFineY`
  - `ppuStatusRead_resetsLatch`
  - `ppuAddrFirstWriteSharesLatchWithScroll`
- `core/test/.../render/ScrollRendererTest.java`:
  - `scrollX0_renderEquivalentToPreScrollImpl` (regression — must match current background renderer pixel-for-pixel)
  - `scrollX100_horizontalMirror_leftPortionFromBaseNT_rightFromOtherNT`
  - `scrollX0_scrollY32_verticalMirror_topPortionFromBaseNT_bottomFromOtherNT`
  - `attributePalette_fetchedFromTilesOwnNametable_notBaseNametable` (the bugzmanov ch. 7 gotcha)

**Impl steps:**
1. Implement PPUSCROLL ($2005) write protocol (latch toggle + loopy `t` updates).
2. Refactor PPUADDR ($2006) write protocol to share the same latch.
3. PPUSTATUS ($2002) read clears the latch (already partially in place — verify).
4. New `core/src/.../render/ScrollRenderer.java` — viewport-rect renderer per [bugzmanov_nes_ebook_review.md §5.9](bugzmanov_nes_ebook_review.md#59-the-scrolling-chapter).
5. `PPU` calls `ScrollRenderer` at frame boundary (NMI time) instead of the current single-NT background path. Snapshot `(scrollX, scrollY, baseNT)` from the loopy `t`.

**Files touched:** `PPU.java` (register handlers, frame-end render hook); new `ScrollRenderer.java`. **Conflicts with steps 5 + 6** — all touch `PPU.java`.

**Done when:** new tests green; existing `PPURenderingTest` green (the `scrollX=0` case); nestest 8992/8992; manual smoke test: SMB level 1-1 scrolls horizontally as Mario walks right.

---

### Step 8 — Frame pacing + LibGDX upload at 60 Hz

**Tests-first:**
- `core/test/.../FramePacingTest.java`:
  - `runFrame_oncePerNtscPeriod_givenFixedDeltaT`
  - `runFrame_skipsZero_runsTwo_ifDeltaExceedsTwoFrames`
  - `headlessReplay_NFrames_producesDeterministicCpuCycleCount`
- `desktop/test/...` (if a desktop test source set is added) — manual integration test instead is fine.

**Impl steps:**
1. `NesSystem.runFrame()` already returns when `ppu.isFrameComplete()` (step 1).
2. New helper `NesSystem.advance(double deltaSeconds)` that runs 0/1/2 frames based on accumulated time.
3. New Gradle task `desktop:runRom` taking `-Prom=path/to/file.nes`, mirroring `desktop:runNestest`.
4. New `desktop/src/.../KeyboardInput.java` translating LibGDX `Input.Keys.*` → `StandardController.setButton`.
5. Single-shared `Pixmap` reuse instead of per-frame allocation.

**Files touched:** `NesSystem.java`, `desktop/build.gradle`, new `desktop/.../KeyboardInput.java`, new `desktop/.../RomLauncher.java`, optional touches to `PixelRenderer.java`. **Zero overlap with PPU work** — fully parallel to anything except step 1.

**Done when:** new tests green; manual smoke test: SMB at correct speed, controller responds.

---

## 4. Parallelisation analysis

### Dependency graph

```
                    ┌────────────────────────────┐
                    │  Step 1 — NesSystem        │  (foundation; everyone waits)
                    └────────────┬───────────────┘
                                 │
         ┌───────────────────────┼──────────────────────┬──────────────────┐
         │                       │                      │                  │
         ▼                       ▼                      ▼                  ▼
  ┌──────────────┐        ┌──────────────┐       ┌──────────────┐   ┌──────────────┐
  │ Step 2       │        │ Step 3       │       │ Step 4       │   │ Step 8       │
  │ NMI hook +   │        │ Standard     │       │ OAM DMA      │   │ Frame pacing │
  │ runWith-     │        │ Controller   │       │              │   │ + Rom        │
  │ Callback     │        │              │       │              │   │ launcher     │
  └──────┬───────┘        └──────────────┘       └──────┬───────┘   └──────┬───────┘
         │                                              │                  │
         │                                              ▼                  │
         │                                       ┌──────────────┐          │
         │                                       │ Step 5       │          │
         │                                       │ Sprite       │          │
         │                                       │ renderer MVP │          │
         │                                       └──────┬───────┘          │
         │                                              │                  │
         │                                ┌─────────────┴──────┐           │
         │                                ▼                    ▼           │
         │                         ┌──────────────┐    ┌──────────────┐    │
         │                         │ Step 6       │    │ Step 7       │    │
         │                         │ Sprite-0 hit │    │ Scroll MVP   │    │
         │                         └──────────────┘    └──────────────┘    │
         │                                                                 │
         └─────────────────────────────────────────────────────────────────┘
                                       (all merge into main)
```

### File-conflict matrix

| Step | New files | Modifies |
|---|---|---|
| 1 | `NesSystem.java`, `NesSystemTest.java` | `NestestBackgroundRenderer.java` (light) |
| 2 | `CPU6502RunWithCallbackTest.java`, `NesSystemNmiHookTest.java` | `PPU.java`, `CPU6502.java`, `NesSystem.java` |
| 3 | `StandardControllerTest.java` | `Controller.java` (rewrite) |
| 4 | `DmaController.java`, `DmaControllerTest.java` | `PPU.java` (oam setter), `NesSystem.java` |
| 5 | `SpriteRenderer.java`, `SpriteRendererTest.java`, `PPUSpriteTest.java` | `PPU.java` (rendering hot path) |
| 6 | `PPUSpriteZeroHitTest.java` | `PPU.java` (`clock()` per-cycle) |
| 7 | `ScrollRenderer.java`, `ScrollRendererTest.java`, `PPUScrollRegisterTest.java` | `PPU.java` (register writes + frame-end render) |
| 8 | `KeyboardInput.java`, `RomLauncher.java`, `FramePacingTest.java` | `NesSystem.java`, `desktop/build.gradle` |

### Hot files (multi-step contention)

- **`PPU.java`** — touched by steps 2, 4, 5, 6, 7. Highest merge risk.
- **`NesSystem.java`** — touched by steps 1, 2, 4, 8.

### Recommended waves (parallel subagent batches)

#### Wave A (sequential — foundation, ~1 agent)
- **Step 1** alone. Everything depends on it. ~30 min of work; not worth parallelising.

#### Wave B (parallel — 3 agents simultaneously, after Wave A merges)
| Agent | Step | Why isolated |
|---|---|---|
| B1 | Step 3 (StandardController) | Self-contained file rewrite + new test — no `PPU.java` or `NesSystem.java` edits |
| B2 | Step 8 prerequisites (KeyboardInput + RomLauncher skeleton, *not* the pacing-NesSystem changes yet) | Pure desktop module; doesn't touch `core` |
| B3 | Step 2 (NMI hook + runWithCallback) | Touches `PPU.java` + `CPU6502.java` + `NesSystem.java` but with a small, well-defined surface |

> **Conflict guard:** B3 is the *only* agent in this wave that touches `PPU.java`. B1 and B2 are clean. Have B3 commit first; B1/B2 then rebase trivially.

#### Wave C (sequential — DMA, ~1 agent)
- **Step 4** alone. It edits `PPU.java` (oam setter) and `NesSystem.java`. Cannot parallelise with Wave D because Wave D depends on its OAM-population semantics.

#### Wave D (sequential — sprites, ~1 agent)
- **Step 5** alone. Massive `PPU.java` work; nothing else should be in flight on `PPU.java` simultaneously.

#### Wave E (parallel — 2 agents simultaneously, after Wave D merges)
| Agent | Step | Why parallel-safe |
|---|---|---|
| E1 | Step 6 (Sprite-0 hit) | Tiny `PPU.clock()` addition (~10 lines), localised to per-cycle path |
| E2 | Step 7 (Scroll MVP) | Larger, but in a different region of `PPU.java`: register write handlers ($2005/$2006) and frame-end render hook. Plus the new `ScrollRenderer.java` |

> **Conflict guard:** E1 and E2 both edit `PPU.java`. Have E1 commit first (it's smaller). E2 rebases. The two changes shouldn't textually overlap — E1 is in `clock()` body, E2 is in register-write switch + a separate render call site — but a code review at merge time is mandatory.

#### Wave F (sequential — pacing finishes, ~1 agent)
- **Step 8 finalisation**: wire `NesSystem.advance(deltaSeconds)`, plug Wave-B's `KeyboardInput`/`RomLauncher` into the now-complete `NesSystem`, add the `desktop:runRom` Gradle task, manual smoke-test SMB.

### Total wall-clock vs serial

| Schedule | Steps | Wall clock (estimate, 1× agent = 1 unit) |
|---|---|---|
| Strict serial | 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 | ~8 units |
| Waved parallel | A(1) → B(2,3,8a) → C(4) → D(5) → E(6,7) → F(8b) | ~5 units |

Roughly **40% reduction** in wall-clock time, at the cost of two careful merges
(B3 onto B1/B2 in Wave B; E2 onto E1 in Wave E).

---

## 5. Subagent prompt template

When spawning a subagent for any step, the prompt should include:

```
You are implementing Step N of docs/impl_plan_playable_gen1.md for deloNES.

CONTEXT:
- Project: Java 8 + LibGDX NES emulator. See CLAUDE.md.
- Reviews: docs/olcnes_review.md, docs/bugzmanov_nes_ebook_review.md.
- This step: <one-sentence summary>.

CONSTRAINTS:
- TEST-FIRST. Write the new JUnit5 tests in core/test/... first; verify they
  fail; then write impl until green.
- THE NESTEST BASELINE MUST REMAIN 8992/8992. Run before starting and after
  every commit:
    ./gradlew core:test --tests net.lomibao.nes.components.NestestTest
- Full suite must remain green:
    ./gradlew core:test
- Files you may modify: <explicit allowlist from the per-step section above>.
- Files you must NOT modify: <explicit blocklist>.

DELIVERABLES:
- New tests in core/test/...
- New impl in core/src/...
- A short PR-style summary at the end listing test names added and impl files
  touched.
- Confirmation that nestest is still 8992/8992 and full suite is green.
```

---

## 6. Cut-line / "minimum playable" subset

If time pressure forces a cut, the smallest subset that gets *something* on
screen with input is:

**Steps 1 + 3 + 4 + 5** = 4 of 8 sub-features.

That delivers Donkey Kong / Pac-Man / Galaga / Balloon Fight / Ice Climber
playable on a single screen with keyboard input — but no SMB and no precise
timing. Steps 2, 6, 7, 8 can land later as polish.

---

## 7. Cross-references

- Architecture + per-component patterns: [`olcnes_review.md`](olcnes_review.md)
- MVP shapes for sprite renderer / controller / scroll / sprite-0 hit: [`bugzmanov_nes_ebook_review.md`](bugzmanov_nes_ebook_review.md)
- Feature definitions and DoD: [`feature_plan_playable_gen1.md`](feature_plan_playable_gen1.md)
- Build commands + test entry points: [`../CLAUDE.md`](../CLAUDE.md)
