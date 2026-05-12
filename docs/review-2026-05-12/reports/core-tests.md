# Core tests review

**Agent:** general-purpose, Opus, read-only worktree off `master`.
**Scope:** all files under `core/test/net/lomibao/nes/` — PPU tests,
Controller, Sprite, CPU/Opcodes/Nestest, Cartridge, NesSystem,
RomCatalog, etc.

## Severity: critical

### PPURenderingTest — five tests pass at construction time

Tests assert `screen pixel != 0` or `(color & 0xFF000000) == 0xFF000000`,
but `PPU.clearScreen()` (called from the constructor) fills the buffer
with `0xFF000000` (opaque black). Every "non-zero" assertion is
satisfied before any rendering happens. `testFullScanlineRendering`
asserts `nonZeroCount > 200` — 256/256 pixels are 0xFF000000 at
construction, so 256 > 200 is trivially true.

`testBackgroundDisabledRendersBackdrop` is the dual: asserts uniformity,
which is trivially the post-clearScreen state if the disabled path
never touches the buffer.

**Effect:** the entire BG rendering pipeline (recently rewritten) has
effectively zero direct pixel-output test coverage. A regression in
`renderBackgroundPixel`/palette lookup would not be caught.

Affected tests at `core/test/net/lomibao/nes/components/PPURenderingTest.java:86-260`:
- `testPixelOutputToFramebuffer`
- `testFullScanlineRendering`
- `testMultipleScanlines`
- `testColorPaletteMapping`
- `testPatternPixelExtraction`
- `testBackgroundDisabledRendersBackdrop`

Fix: compare against backdrop color, not zero. Or assert pixel matches
expected palette entry.

### OpcodesTest infinite-loop guard is overwritten

`run()` / `runWithSetup()` write `JMP $7FFE` as `4C FE 7F` at addresses
`$7FFE`/`$7FFF`/`$8000`, then `ram.writeRange(0x8000, program)` clobbers
the third byte (`0x7F`). If any test program runs off the end of its
bytes, control jumps somewhere arbitrary instead of looping safely.

Tests have been masking the bug because most programs don't actually
overrun, but new opcode tests that depend on the safety loop catching
wild jumps are unreliable.

Source: `core/test/net/lomibao/nes/components/OpcodesTest.java:33-39, 67-73`.

Fix: write the guard at an address writeRange can't overwrite, or
re-write it after writeRange.

### Tests asserting `== 0` on already-zero state

These tests pass for the wrong reason — the path they claim to test is
never exercised.

- `PPUTileFetchTest:testNoFetchingWhenRenderingDisabled`
  (`PPUTileFetchTest.java:132-144`) — asserts `bgNextTileId == 0` after
  running with rendering disabled; `bgNextTileId` is initialized to 0,
  so a regression that always fetched would also pass (just with
  whatever value the fetcher picked up).
- `PPUVBlankTest:testSprite0HitClearsAtPreRender`
  (`PPUVBlankTest.java:179-194`) — writes to PPUSTATUS (read-only —
  the write is ignored, as test's own comment admits), advances to
  pre-render, asserts bit 6 is clear. Bit 6 was never set; the clear
  path isn't actually exercised.

## Severity: medium

### StandardControllerTest enshrines obsolete behavior

`controller2_4017_returnsOpenBus_byDefault` and
`controller2_4017_writeIsNoOp` enshrine an obsolete impl that returned
`0x40` for any `$4017` read. The current `Controller` actually services
P2 button reads at `$4017` (verified in `ControllerTest`). The tests
still pass — but only because they don't press P2 buttons, so
`readPlayer(1)` returns `0 | 0x40 = 0x40` by accident.

Source: `core/test/net/lomibao/nes/components/StandardControllerTest.java:228-242`.

Fix: invert the tests to assert P2 IS supported.

### NestestTest has critical comparisons commented out

Comments and CLAUDE.md describe this as a "full 8992/8992 line match",
but the test has:
- the `P` (status) flag comparison commented out (line 75)
- the cycle-count comparison commented out (line 78)

A status-flag regression (e.g. BRK setting bit 4/5 wrong) or a
cycle-count regression (wrong addressing-mode penalty cycles) will not
fail this test.

Source: `core/test/net/lomibao/nes/components/NestestTest.java:75-79`.

### PPUTileFetchTest:testFetchCycleRanges — assertion isn't tight

Name says "fetch cycle range 321-336", but the assertion is
`assertEquals(0x88, ppu.getBgNextTileId())` after refilling **all** of
`$2000-$2FFF` with `0x88`. With all addresses returning the same byte,
the assertion would pass even if the fetcher was reading the WRONG
address. The test does not pin down that the prefetch window reads a
different NT cell than the visible window.

Source: `core/test/net/lomibao/nes/components/PPUTileFetchTest.java:186-211`.

### PPUNametableAccessTest:testWriteToDifferentNametables — comment wrong

Comment claims "nestest.nes uses vertical mirroring" but nestest is
horizontal-mirroring (flag 6 bit 0 = 0 → horizontal). Test passes
because assertion `val0 == val2` and `val1 == val3` holds for ANY
consistent two-way mirroring. Test does not actually verify the
mirroring topology.

Source: `core/test/net/lomibao/nes/components/PPUNametableAccessTest.java:151-167`.

### PPUClockTest:testFullFrameCycleCount / testMultipleFrames — blocks odd-frame skip

Assertion `89342 cycles per frame` enshrines the absence of the NESdev
odd-frame cycle skip. Real PPU drops cycle 0 of pre-render on odd frames
when rendering is enabled, yielding 89341 cycles. Tests run with
rendering disabled (so the skip wouldn't apply on real HW), so this
isn't wrong today, but it blocks the eventual odd-frame-skip feature.

Source: `core/test/net/lomibao/nes/components/PPUClockTest.java:121-150`.

### PPUVBlankTest:testVBlankFlagDoesNotSetIfClearedByReadBeforeScanline241 — name vs assertion mismatch

Test name says "does NOT set after clear", but assertion is
`assertTrue(isVBlankFlagSet())`. The test verifies the OPPOSITE of its
name — VBlank still sets at 241 even after being cleared earlier. Doc
bug, not test bug.

Source: `core/test/net/lomibao/nes/components/PPUVBlankTest.java:113-135`.

### PPUNMITest:testNMIWithoutCPUDoesNotCrash — tautology

Javadoc says "the failure mode it once guarded is now structurally
impossible." Should be removed or replaced with a meaningful assertion.

Source: `core/test/net/lomibao/nes/components/PPUNMITest.java:177-195`.

### PPUScrollRegisterTest:ppuAddr_twoWrites_setsPpuAddress — assertion is just "doesn't throw"

Only asserts `assertDoesNotThrow`, not the actual address. Could pass
even if PPUADDR were broken (swapped high/low, wrong mask) as long as
it doesn't NPE.

Source: `core/test/net/lomibao/nes/components/PPUScrollRegisterTest.java:108-116`.

### PPUSpriteZeroHitTest — missing boundary coverage

The new per-pixel impl handles `x == 255` (NESdev: bit 6 never sets at
x=255) and the dual-mask leftmost-8 rule. Tests cover both gates being
independently OFF in the leftmost-8, but no test for:
- `x == 255` exactly (drive sprite 0 to (some scanline, x=255) with
  both layers enabled, assert bit 6 stays clear).
- BG-transparent at the overlap cell (`bgPatternPixel[scanline][x] == 0`
  branch). Tests seed shadow with `1` across the entire frame in setUp;
  override at the specific cell tested.

### NesSystemNmiHookTest:frameRenderedListener_doesNotFire_whenNmiSuppressed

Couples "frame rendered" listener to NMI rising edge. Test enshrines
the decision: a frame is only emitted when NMI fires. Hosts that want
every frame regardless of NMI (e.g. debugger stepping with NMI
disabled) can't rely on the listener.

## Severity: low / nit

- **PPUControlRegistersTest:testPPUMASKBit4EnablesSpriteRendering**
  asserts `ppu.getBgNextTileId() >= 0` — vacuously true for an int field.
  (`PPUControlRegistersTest.java:158-175`)
- **PPUBusTest:testFirstMatchingComponentHandlesAddress** asserts
  `value >= 0 && value <= 0xFF` — true for any byte.
  (`PPUBusTest.java:99-109`)
- **PPUBusTest:testPaletteAddressDoesNotReachBus** asserts return == 0
  — same as any unrouted address. (`PPUBusTest.java:124-132`)
- **PPUTest:testPPUInitialization** uses `assertNotNull(ppu)` on a
  just-constructed local. Same pattern in **CPU6502Test:testInitialState**.
- **PPUTest:testPPURegisterMirroring** name says "Read from 0x2008
  (should mirror to 0x2000)" but never READS via the bus; reads
  `ppu.registers[0]` directly. Read-mirroring path not exercised.
- **NameTableMemoryTest:testMirroringModeEnumExists** —
  `assertNotNull(MirroringMode.HORIZONTAL)` can't fail (compile-error
  otherwise).
- **TileDecoderTest:testTileIndexToByteOffset** only asserts non-null
  for three tile indices.
- **PPUTileFetchTest:testAttributeFetchCycle3** comment says
  "bottom-right quadrant" but the actual quadrant at the fetched cell
  is top-right (`quadX=1, quadY=0`). Assertion happens to also equal 3
  for top-right; passes for the wrong comment-level reason.
- **PPUSpriteZeroHitTest:setUp** pre-seeds the bg-pattern shadow with
  `1` across the entire 256x240 visible frame in every test's
  `@BeforeEach`. Convenient for happy-path tests but means no
  "BG-transparent at this pixel" test path. Regression in the
  `bgPatternPixel[scanline][x] == 0` check (PPU.java:475) not caught.
- **NesSystemTest:runFrame_throwsIllegalState_whenSafetyCapTrips** —
  anonymous PPU subclass with no PPUBus or other state. Fragile pattern.
- **PPUScrolledFetchTest** mirroring overrides may leak if anyone adds
  shared state (per-test `@BeforeEach` mitigates today).
- **NMI rising-edge re-trigger** (write PPUCTRL bit 7 high mid-VBlank
  with VBlank flag set) — neither impl nor test covers.

## Missing coverage

Important code paths in `core/src/` with **no direct test**:

- **`Cartridge.java`** — no `CartridgeTest.java`. iNES header parsing,
  trainer skipping, PRG/CHR sizing, mapper handshake exercised only
  through `NestestTest`. Malformed ROMs produce confusing errors with
  no isolating test.
- **`rom/mapper/Mapper000.java` / `Mapper.java`** — no direct mapper
  tests. PRG mirroring (16 KB carts mirroring `$C000` → `$8000`),
  CHR RAM vs CHR ROM behavior, all transitive only.
- **`PixelRenderer.java`** in `render/` — has a sibling file
  `PixelRendererTest.java` but it lives in `core/src/`, not `core/test/`.
  Wrong source set, or not actually a test.
- **`NestestBackgroundRenderer.java`** — debug renderer, untested.
- **`CHRTileViewer.java`, `TileDebugger.java`** in `debug/` — untested.
- **`CPU6502.nmi()` rising-edge / NMI suppression race** (NESdev "NMI
  suppression race"). Not modelled; no test.
- **NMI re-trigger** when PPUCTRL bit 7 is set mid-VBlank with VBlank
  flag still set. Real hardware fires NMI on that rising edge; impl
  stores the byte only.
- **Odd-frame cycle skip**: PPU skips cycle 0 of pre-render on odd
  frames when rendering enabled. Not implemented; tests assert 89342
  cycles/frame, locking out the fix.
- **PPU `$2007` write with +32 increment** crossing `$3F00` or the
  `$3000` mirror. `PPUNametableAccessTest.testAddressAutoIncrement`
  covers +1 to palette only.
- **PPU `$2007` "dummy read" rule** after `$2006` setup. The test
  helper does the dummy read but no test directly asserts the
  semantic.
- **Per-scanline 8-sprite limit** and PPUSTATUS bit 5 overflow flag.
  Not implemented; no test.
- **`SpriteRenderer.render()`** 8-sprite limit, overflow flag, and the
  documented "what this MVP does NOT do" list — no negative-coverage
  tests pinning the gaps.
- **`DmaController` alignment cycle** (513 vs 514 depending on odd/even
  start). Existing test asserts the range, not which value is observed.
- **`Controller` rapid-fire / strobe-during-shift** — tests cover clean
  strobe 1→0 and 0→0, not strobe-1 written mid-shift.
- **`PPU.checkSpriteZeroHit()` corner case at x=255** — `if (x == 255)
  return;` at PPU.java:459, no test pins this. Delete the line; nothing
  fails.
- **`PPU.consumeNmi()` after `reset()` mid-VBlank race** — basic
  reset-clears-latch covered; "reset during VBlank then re-enter
  VBlank without intermediate pre-render" not.

---

**Working tree:** clean.
