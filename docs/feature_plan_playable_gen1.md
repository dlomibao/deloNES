# Feature Plan — "Playable First-Generation Games"

The next coherent milestone for deloNES, distilled from the existing reviews
([`olcnes_review.md`](olcnes_review.md), [`bugzmanov_nes_ebook_review.md`](bugzmanov_nes_ebook_review.md))
and the current code state. The goal is the smallest set of work that turns
deloNES from "renders nestest backgrounds" into "boots, accepts input, and plays
NROM-only games end-to-end".

## Headline feature: **Playable Mapper-0 Games End-to-End**

Target ROMs (all NROM / Mapper 0, no scrolling needed for the first three):

- **Donkey Kong** — sprites + sprite-0 hit, no scroll
- **Ice Climber** — sprites + simple horizontal scroll within one nametable
- **Super Mario Bros.** — sprites + horizontal scroll across nametables + sprite-0 hit for the status bar split

Definition of done: SMB title screen renders, "PRESS START" responds to keyboard,
Mario walks right past the first pipe with the correct status-bar split. That
exercises every sub-feature below.

---

## Sub-feature inventory

The 8 sub-features below are ordered by dependency (1 unlocks 2…8) and by
impact-per-effort.

| # | Sub-feature | Status | Why it's needed |
|---|---|---|---|
| 1 | System clock / master tick (`NesSystem`) | **Missing** | All timing-correct work depends on this |
| 2 | NMI rising-edge hook + `runWithCallback` | **Partial** | Lets host code redraw + poll input at frame boundary |
| 3 | OAM DMA (`$4014`) | **Missing** | Every game writes sprites via DMA, not by-byte |
| 4 | Sprite (foreground) rendering | **Missing** (OAM array exists, no renderer) | No game shows characters without it |
| 5 | Sprite-0 hit (coarse) | **Missing** | Status-bar splits in SMB depend on it |
| 6 | `StandardController` (proper strobe + shift) | **Stub only** | "Press start" is meaningless without input |
| 7 | Scroll MVP (viewport-rect, two-nametable) | **Missing** | SMB / Ice Climber require horizontal scroll |
| 8 | Frame pacing + LibGDX upload at 60 Hz | **Partial** | Without it, the emulator runs as fast as the host can clock |

Each sub-feature gets a dedicated section below: status, evidence in code, and a
high-level work plan.

---

## 1. System clock / master tick (`NesSystem`)

### What it is

A single object that drives the whole machine on a master clock and coordinates
which chip ticks when. Per-tick responsibilities:

- Tick PPU (and APU, when it exists)
- Tick CPU on every 3rd master tick
- Hand off to the DMA controller if `$4014` was written (sub-feature 3)
- Edge-detect NMI from the PPU and poke the CPU (sub-feature 2)
- Edge-detect IRQ from the cartridge mapper

This is the [olc `Bus::clock()`](olcnes_review.md#1-top-level-architecture)
pattern but lifted **above** `CPUBus` so `CPUBus` stays a pure memory decoder.

### Current status: **Missing**

- `desktop/src/net/lomibao/nes/desktop/NestestBackgroundRenderer.java:230` does
  the right shape ad-hoc (`cpu.clock(); for(i<3) ppu.clock()`) inside a desktop
  launcher.
- `core/src/net/lomibao/nes/components/CPUBus.java` only ticks the CPU.
- There is no `NesSystem` class. `NesEmulator` (the LibGDX `ApplicationAdapter`)
  is the closest thing but is rendering-coupled.

### Work to do

1. New class `core/src/net/lomibao/nes/NesSystem.java`. Constructor wires
   `CPU6502 + CPUBus + PPU + PPUBus + Cartridge + Controllers + DmaController`.
2. `NesSystem.tick()`: ppu.clock(); if (++masterDiv % 3 == 0) { dmaOrCpu(); };
   poll NMI; poll mapper IRQ.
3. `NesSystem.runFrame()`: loops `tick()` until `ppu.isFrameComplete()`.
4. Move the ad-hoc loop in `NestestBackgroundRenderer` and any future launcher
   to call `NesSystem.runFrame()` so it lives in one place.
5. Test: a `NesSystemTickTest` that runs N ticks and asserts CPU advanced N/3
   instructions, PPU advanced N cycles.

---

## 2. NMI rising-edge hook + `runWithCallback`

### What it is

Two related host-integration affordances:

- **NMI rising-edge hook**: `NesSystem` exposes a `setFrameRenderedListener(Consumer<NesSystem>)`
  that fires once per false→true transition of the PPU's NMI line — i.e., once
  per frame at the start of VBlank. The host (LibGDX `render()`, headless test,
  Snake demo) uses this as its "swap buffers + poll input" trigger.
- **`CPU6502.runWithCallback(Consumer<CPU6502>)`**: per the
  [bugzmanov ch. 3.4 pattern](bugzmanov_nes_ebook_review.md#21-dispatch-style),
  lets a tracer or a CPU-only demo (Snake) run without standing up a PPU.

### Current status: **Partial**

- `core/src/net/lomibao/nes/components/PPU.java:229` already calls `cpu.nmi()`
  directly when entering VBlank. That's an internal coupling — works but
  prevents headless renderers from intercepting NMI cleanly.
- No `runWithCallback`. The CPU clock loop is internal.

### Work to do

1. PPU exposes `consumeNmi()` returning a boolean (clears the latched flag), per
   the olc shape. Remove the direct `cpu.nmi()` call; `NesSystem.tick()` polls
   instead.
2. `CPU6502.runWithCallback(Consumer<CPU6502> beforeEachInstruction)` —
   primarily for Snake demo + nestest tracer (already passes 8992/8992; this
   formalises the hook for a snapshot-style test).
3. `NesSystem.setFrameRenderedListener(Consumer<NesSystem>)` invoked at NMI
   edge.
4. Refactor `NestestBackgroundRenderer` to use the listener instead of the busy
   loop.

---

## 3. OAM DMA (`$4014`)

### What it is

Writing any byte `XX` to `$4014` triggers a 256-byte burst copy of CPU memory
`$XX00..$XXFF` into PPU OAM (`$0000..$00FF` of OAM). The CPU is suspended for
513 or 514 cycles (1 dummy cycle if started on an odd CPU cycle, then 256×
read/write pairs). This is how every NES game actually populates sprites — they
maintain a "shadow OAM" in CPU RAM and DMA-copy it once per frame.

### Current status: **Missing**

- `Controller.java:24` comment notes `$4017` overlap with APU frame counter but
  neither `$4014` writes nor the DMA state machine are handled anywhere.
- `Grep` for `0x4014` / `DMA` / `OAMDMA` returns nothing in `core/`.

### Work to do

1. New class `core/src/net/lomibao/nes/components/DmaController.java` —
   `CPUBusComponent` registered at `$4014` (single-byte range). On write,
   record `dmaPage = value` and `dmaPending = true`.
2. `DmaController.tick(masterCycle, cpuBus, ppuOam)` — state machine:
   - Wait for even master cycle (1 dummy cycle if odd).
   - For 256 iterations: even = `data = cpuBus.read(page<<8 | i)`,
     odd = `ppuOam[i] = data`.
   - Suspend CPU (`NesSystem` checks `dma.isActive()` before invoking
     `cpu.clock()`).
3. PPU exposes a package-private `byte[] oam()` accessor or a
   `writeOam(int idx, byte v)` setter; prefer the setter.
4. Test: `DmaControllerTest` runs DMA from a populated `Ram`, asserts OAM is a
   byte-for-byte copy, asserts CPU was suspended for ≥513 cycles.

---

## 4. Sprite (foreground) rendering

### What it is

Per-pixel sprite output composited with the background. Uses the 256-byte OAM
populated by DMA. Each sprite is 4 bytes `[Y, tileId, attr, X]`. Per scanline,
the PPU does **secondary OAM evaluation** to find up to 8 sprites whose Y
intersects the scanline, then fetches their pattern bytes during cycles
257–320, then shifts them out during the next visible scanline.

For an MVP, the [bugzmanov 6.5 shape](bugzmanov_nes_ebook_review.md#56-sprites--oam-ch-65)
— iterate full OAM in reverse, decode flip bits, skip transparent
(`pixel == 0`) — gets every first-gen game on screen. Per-pixel priority bit
(attr bit 5) is needed for SMB so Mario goes behind pipes.

### Current status: **Missing**

- `PPU.java:22`: `byte[] oam = new byte[256]` exists.
- `PPU.java:671`–`732` — helpers for "sprite pattern table addr" / "sprite
  size" / "sprite enable" exist, but no sprite *renderer*.
- `PPURenderingTest` exists for backgrounds; no sprite test class.

### Work to do

1. New class `core/src/net/lomibao/nes/components/ppu/SpriteRenderer.java`
   (or methods on `PPU`). MVP shape: full-frame pass after background is laid
   down, full OAM scan in reverse (priority by index), per-sprite 8×8 or 8×16
   loop with h/v flip and palette lookup.
2. Honour PPUMASK bit 4 (sprites visible) and bit 2 (sprites in left 8 pixels).
3. Per-pixel composite rule:
   ```
   if (sprite.pixel != 0 && (bg.pixel == 0 || sprite.priority == FOREGROUND))
       output = sprite
   else
       output = bg
   ```
4. 8×16 sprite mode (PPUCTRL bit 5): tile fetch uses the **sprite's** tile-id
   bit 0 to pick the pattern table, not PPUCTRL bit 3.
5. Tests:
   - `SpriteRendererTest` — populate OAM with a known tile + position, render
     a frame, assert pixels at expected coords.
   - `SpriteFlipTest` — h-flip, v-flip, both.
   - `SpritePriorityTest` — sprite behind background pixel ≠ 0 stays hidden.

---

## 5. Sprite-0 hit (coarse)

### What it is

Bit 6 of PPUSTATUS (`$2002`). Set when a non-transparent pixel of sprite 0
overlaps a non-transparent pixel of the background, during rendering. Cleared
at pre-render (scanline 261, dot 1). SMB polls this to know when the scanline
counter has reached the status bar so it can change scroll mid-frame.

The [bugzmanov ch. 7 6-line approximation](bugzmanov_nes_ebook_review.md#57-sprite-0-hit-ch-7)
(set the flag when `scanline == oam[0].y && cycle >= oam[0].x && rendering on`)
is enough for SMB.

### Current status: **Missing**

- `PPU.java:236` already clears bit 6 of PPUSTATUS at pre-render. Nothing ever
  *sets* it.

### Work to do

1. In `PPU.clock()` (the per-cycle path), at every visible cycle, check the
   coarse predicate and OR bit 6 into PPUSTATUS.
2. Test: place sprite 0 at known coords with a known tile, run until the
   sprite-0 scanline, assert PPUSTATUS bit 6 set; assert it is cleared at
   pre-render.
3. **Stretch:** opaque-pixel-vs-opaque-pixel test using the actual rendered
   frame buffer for the scanline. Required for some test ROMs but not for
   shipping SMB.

---

## 6. `StandardController` (proper strobe + shift register)

### What it is

`$4016` write: bit 0 is the strobe latch. While strobe is high, the
shift-register snapshot is continuously refilled from the live button state.
On the falling edge (strobe high → low) the snapshot freezes.

`$4016` read: returns bit 0 of the snapshot, then shifts right one position.
**After 8 reads, every subsequent read returns 1** until the next strobe
cycle — this is the
[bugzmanov ch. 8 detail olcNES gets wrong](bugzmanov_nes_ebook_review.md#6-joypad--controller-ch-8).

### Current status: **Stub**

- `core/src/net/lomibao/nes/components/Controller.java` exists, returns `0x40`
  open-bus for everything. Strobe field is recorded but never used.

### Work to do

1. Replace `Controller.java` with a real `StandardController` that holds:
   - `int liveButtons` (8 bits, set by host input layer)
   - `int latched` (snapshot)
   - `int readIndex` (0..8)
   - `boolean strobe`
2. Implement bugzmanov's `read()` / `write()` shape verbatim (Java port in the
   review at [§6](bugzmanov_nes_ebook_review.md#6-joypad--controller-ch-8)).
3. Public `setButton(int mask, boolean pressed)` for the input layer.
4. Wire keyboard in `desktop/src/net/lomibao/nes/DesktopLauncher.java` (or a
   new `KeyboardInput` helper) — Arrow keys + Z/X/Enter/Right-Shift is the
   conventional binding.
5. Tests:
   - `ControllerStrobeTest` — strobe-high keeps refilling, strobe-low freezes.
   - `ControllerShiftTest` — 8 reads return the 8 buttons in order, 9th onward
     returns 1.
   - `ControllerLatchTest` — pressing a button after strobe-low does not
     leak into the snapshot.

---

## 7. Scroll MVP (viewport-rect, two-nametable)

### What it is

Horizontal/vertical scrolling implemented as: for each visible nametable, work
out a rectangle of source tiles that should appear, and the (shiftX, shiftY)
where they should be drawn in the framebuffer. For `scroll_x = 200` with
horizontal mirroring, base nametable maps to viewport `(0..56, 0..240)` and
the other nametable maps to viewport `(56..256, 0..240)`.

This is the [bugzmanov ch. 7 mental model](bugzmanov_nes_ebook_review.md#59-the-scrolling-chapter).
It does *not* support mid-frame scroll changes (split scroll for status bars
needs sub-feature 5 + the loopy v/t/x/w pipeline). For Donkey Kong / Pac-Man
no scroll at all is needed; for SMB we get the playfield scroll, and the
status bar split comes from sprite-0-hit + a `scroll_x` reset that happens in
the game's NMI handler — which works as long as the host honours scroll
register writes per frame.

### Current status: **Missing**

- `PPU.java:127` has a `// TODO: Implement scroll protocol` comment in PPUSCROLL
  ($2005) handling.
- `PPU.java:746`: nametable address calc explicitly notes "without scroll".
- The `fineX` field at `PPU.java:59` exists but isn't fed.

### Work to do

1. Implement PPUSCROLL ($2005) write protocol: latch toggle, first write =
   coarse-X + fine-X, second write = coarse-Y + fine-Y. Stash into a single
   loopy `t` register int.
2. Implement PPUADDR ($2006) write protocol's interaction with the same latch
   (well-documented in [olcnes_review §10.2](olcnes_review.md#102-cpu--ppu-registers-20002fff)).
3. **Two paths possible:**
   - **(a) bugzmanov MVP**: post-NMI render two nametables clipped+shifted by
     the snapshotted scroll values. Easy to write, easy to test, ships SMB.
   - **(b) loopy v/t/x/w**: per-cycle pipeline as in
     [olcnes_review §3](olcnes_review.md#3-ppu2c02-olc2c02). Required for
     mid-scanline scroll. More work.
4. Recommend **path (a) first**, behind a `ScrollRenderer` collaborator that
   `PPU.clock()` invokes only at frame boundary. Path (b) becomes a follow-up
   feature ("Cycle-Accurate Scroll").
5. Tests: `ScrollViewportTest` with synthetic two-nametable VRAM, scrolled
   `scroll_x = 100`, assert pixels at viewport `(0,0)` match nametable-0 col
   100 etc.
6. **Don't forget the cross-nametable attribute palette gotcha** flagged in
   bugzmanov ch. 7 — when fetching a tile from the second nametable, also use
   that nametable's attribute table for its palette.

---

## 8. Frame pacing + LibGDX upload at 60 Hz

### What it is

Run `NesSystem.runFrame()` at exactly NTSC 60.0988 Hz, upload the resulting
RGBA pixel buffer to a single LibGDX `Pixmap`/`Texture`, draw the texture
filling the window. Audio (when APU lands) needs the same pacing because
sample rate is master-clock-derived.

### Current status: **Partial**

- `desktop/src/net/lomibao/nes/desktop/NestestBackgroundRenderer.java` already
  pumps a frame and draws to a `Pixmap`/`Texture`, but pacing is "as fast as
  LibGDX `render()` calls us".
- `render/PixelRenderer.java` exists. Channel-order issue (RGBA vs ARGB)
  already fixed per CLAUDE.md note.

### Work to do

1. `NesSystem.runFrame()` returns when frame is complete; the LibGDX `render()`
   delta-time accumulator decides whether to run 0, 1, or 2 frames this paint.
2. Single shared `Pixmap` reused per frame; upload via `Texture.draw(pixmap)`
   then `SpriteBatch.draw(texture)`.
3. New `desktop:runRom` Gradle task (mirrors `desktop:runNestest`) that takes
   a ROM path arg, instantiates `NesSystem`, hooks keyboard input.
4. Test (headless): `RunFrameTest` runs N frames against a known ROM and
   asserts CPU/PPU cycle counts per frame are within tolerance.

---

## Implementation order (suggested)

1. **Sub-feature 1** (`NesSystem`) — unblocks everything else, low risk.
2. **Sub-feature 2** (NMI hook + `runWithCallback`) — small, immediate test
   ergonomics win.
3. **Sub-feature 6** (`StandardController`) — small, isolated, lets you start
   manual-testing existing CHR-only ROMs by feel.
4. **Sub-feature 3** (OAM DMA) — prerequisite to sub-feature 4.
5. **Sub-feature 4** (Sprite renderer MVP) — Donkey Kong / Pac-Man on screen
   at this point.
6. **Sub-feature 5** (Sprite-0 hit coarse) — required by SMB before scroll.
7. **Sub-feature 7** (Scroll MVP) — SMB walking left-to-right at this point.
8. **Sub-feature 8** (Frame pacing) — polish; do once the rest is correct.

After all 8 land, the demo target is: `./gradlew desktop:runRom -Prom=smb.nes`
brings up Super Mario Bros. with keyboard input and the status-bar split
intact.

---

## Out of scope for this feature

These are **future** features — explicitly deferred to keep this one shippable:

- **Cycle-accurate loopy v/t/x/w scroll** (split-scroll mid-scanline, required
  for *Battletoads* status bar)
- **APU** (no sound)
- **More mappers** (MMC1, UxROM, MMC3) — would need a `MapperRegistry`; tracked
  separately
- **PAL region** (NTSC only)
- **iNES 2.0** ROM headers (explicit reject is fine; full parse is later work)
- **Save states** / rewind
- **Second controller**, Zapper, Famicom expansion

---

## Risks / open questions

1. **Per-cycle vs catch-up clocking.** Sub-feature 1 picks one. The current
   PPU code (`PPU.java:206 clock()`) already is per-cycle for backgrounds; the
   `NesSystem.tick()` should drive it that way. But if future profiling shows
   it's a bottleneck, a "tick N ppu cycles in one call" fast-path is easy to
   add later.
2. **Sprite-0 hit accuracy ceiling.** The coarse approximation works for SMB
   but not for *Mike Tyson's Punch-Out!!* and a handful of others. Decide
   later whether to invest in the opaque-pixel test.
3. **Mid-frame scroll changes.** Path (a) in sub-feature 7 reads the scroll
   register *once per frame*. Some games (e.g. SMB itself for the status bar)
   write to PPUSCROLL in the NMI handler — that works because the write
   happens before the next frame renders. Mid-scanline writes during rendering
   won't work and are deferred to "Cycle-Accurate Scroll".
4. **OAM DMA timing edge cases.** olc handles the "wait for even master cycle"
   quirk; bugzmanov skips it. Implementing it correctly is one extra `if`,
   worth doing on first pass to avoid a future debugging session.

---

## Cross-references

- Architectural patterns + per-component recommendations:
  [`olcnes_review.md`](olcnes_review.md)
- MVP shapes for sprite renderer, controller, scroll, sprite-0 hit:
  [`bugzmanov_nes_ebook_review.md`](bugzmanov_nes_ebook_review.md)
- Repo-wide conventions, build commands:
  [`../CLAUDE.md`](../CLAUDE.md)
