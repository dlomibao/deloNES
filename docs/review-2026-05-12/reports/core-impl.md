# Core implementation review

**Agent:** general-purpose, Opus, read-only worktree off `master`.
**Scope:** `core/` — PPU, Controller, CPUBus, DmaController, SpriteRenderer,
NesSystem, RomCatalog, with skim of Cartridge, Ram, APU, PPUBus,
NameTableMemory, PaletteMemory, CPUBusComponent, Button, CPU6502.

## Severity: critical

### CPUBus.java:39-67 — `$4014` OAM DMA writes never reach `DmaController`

`CPUBus.write` evaluates components in this order: testRam → ram → ppu →
controller → apu → dma → cartridge. The APU's `inCPUBusRange()` covers
`[$4000, $4020)`, which includes `$4014`. Since APU is checked **before**
DMA, any `STA $4014` lands in `APU.registers[0x14]` and **never invokes
`DmaController.cpuBusWrite()`**.

The DMA controller is effectively dead in production — only tests that
poke it directly (`DmaControllerTest` does `r.dma.cpuBusWrite(0x4014,…)`,
bypassing the bus) ever fire it.

**Currently masked because `apu` is `null` in `NesSystem.builder()` —
the `Optional.ofNullable(apu).map(...)` short-circuits to false and
the chain falls through to DMA.** The moment we wire APU (planned in
Tier B-ish work), DMA goes silent and DK loses Mario.

Fix: route `$4014` to DMA before APU, or fan-out the way `$4016`/`$4017`
does. `grep` for `bus.write.*0x4014` returns zero hits — no test would
have caught this.

### Cartridge.java:74 — shadowed local hides field

Inside the `if (fileType == 1)` branch:

```java
int nCHRBanks = header.getCHRROMSize();
int vCHRSize = nCHRBanks == 0 ? 8192 : nCHRBanks * 8192;
```

The `int` declaration shadows the instance field `private int nCHRBanks = 0`.
Outside the block at line 85, the field (still 0) is what
`new Mapper000(nPRGBanks, nCHRBanks)` receives. NROM ignores it (why
nestest works); any future mapper breaks immediately.

Fix: drop the leading `int` to assign the field.

## Severity: medium

### PPU.java:322-331 — cycle-1 LOAD duplicates the first pixel of column 1 at every tile boundary

Fetch/load block runs for cycles 1..256 and 321..337. At cycle 1 the
condition `cycleMod == 0` fires `loadBackgroundShifters()`. But
`bgNextTilePatternLow/High` still hold **column 1**'s pattern (from the
previous scanline's cycle-334/336 fetches that produced the cycle-337
load). The cycle-1 LOAD overwrites the LOW byte with unshifted column 1
right after cycle 1's shift already moved column 1's bit 7 into bit 8 of
the shifter.

Trace with `col0=0xAB`, `col1=0x4D`:

- Cycle 1 end (post-shift, post-reload): HIGH=col0 bits 6-0 with col1 bit
  7 at pos 8; LOW=col1 (unshifted) — col1 bit 7 is now at **both** bit 7
  and bit 8.
- After cycles 2-8 shifts: that duplication propagates so col1 bit 7
  ends up at both bit 14 and bit 15 at start of cycle 9.
- Cycle 9 render reads bit 15 = col1 bit 7 (correct). Cycle 9 shift
  then puts col1 bit 7 (from the duplicate at bit 14) into bit 15.
  **Cycle 10 renders col1 bit 7 again** instead of col1 bit 6.

Result: every tile boundary in the visible scanline stutters by one
pixel. Existing tests don't catch this — `PPURenderingTest` only asserts
"some pixels non-zero" (see core-tests review for separate bug). Worth a
focused regression test asserting per-pixel output across a tile boundary.

Note: the analogous "stale LOAD at cycle 257 / 321" cases are harmless
because no shifting and no rendering happen between cycle 257 and 329's
correct LOAD, so the stale data is overwritten before it matters.

### APU.java:57 — bounds check uses `&&` where it must be `||`

```java
if(address<START_ADDRESS && address>=END_ADDRESS) { … return -1; }
```

Both conditions can never be simultaneously true. Out-of-range addresses
skip the error path and silently compute `(address - 0x4000) % 32`.
Within current routing this is mostly harmless (the bus filters via
`inCPUBusRange` first), but the guard does not guard.

### PPU.java:272-279 — NMI enable mid-VBlank is not honored

NMI is latched only at scanline 241, cycle 1. Per NESdev (`PPUCTRL`
section), writing `$2000` with bit 7 set **while** `PPUSTATUS` bit 7 is
already 1 should also assert NMI. Several games (e.g. Battletoads) rely
on the "enable NMI inside VBlank" behavior.

### PPU.java:282-290 — pre-render scanline does not implement the odd-frame cycle skip

NESdev: on odd frames with rendering enabled, scanline 261 ends after
cycle 339 (one cycle short). Current impl always runs 0..340.
Visible-game impact is small (one master cycle drift every other frame),
but `oddFrame` is tracked and never used — clear intent/behavior
mismatch.

### PPU.java:13 — `public byte[] registers`

Direct exposure means any caller (and several test paths) can stomp on
PPUCTRL/PPUMASK/PPUSTATUS without going through `cpuBusWrite`, silently
bypassing side-effect contracts (status read clears bit 7 + latch).
Tests intentionally use this for setup, which is fine, but production
code could too. Make private; expose a narrow test seam.

### DmaController.java:71-74 — `waiting = true` re-trigger may cut next DMA short

At completion (line 126) the code does `waiting = true; active = false;`.
If `cpuBusWrite($4014)` is called again later, the constructor
reinitialises everything *except* the alignment-bit logic, but the first
thing `tickDmaCycle` does on the next DMA is enter the `waiting` branch
— and the wait-exit condition `masterClockCount % 2 == 1` may already
be true on the very next CPU turn. That can shorten the wait phase from
1-2 cycles to 0-1 cycles in some alignments. Real hardware always
inserts at least one "dummy read" before the first transfer
(https://www.nesdev.org/wiki/DMA). Uncertain whether observable on any
game — flagging because the contract docstring claims "1 wait cycle, +1
alignment if odd."

## Severity: low / nit

### Ram.java:37 — bounds check uses `>` instead of `>=`

`if (address < ADDRESS_RANGE_START || address > ADDRESS_RANGE_START+ADDRESS_RANGE_SIZE)`
accepts `address == start + 4 × 2KB = 0x2000` as in-range. `inCPUBusRange`
on the bus uses exclusive end so 0x2000 routes to the PPU first and the
case never fires, but the discrepancy is a latent landmine for any
future direct caller.

### Ram.java:7-8 — `MEMORY_SIZE` / `ADDRESS_RANGE_SIZE` are `short` not `int`

Java `short` is signed 16-bit, max 32767. 2KB / 8KB fit, but every
consumer immediately widens to int. Confusing. Use `int`.

### PPUBus.java:55 — masks address but routes to a component whose range is `[0x2000, 0x3F00)`

`addr & 0x3FFF` is fine, but `NameTableMemory` only handles
`0x2000..0x3F00`. The gap `0x3F00..0x3FFF` produces an `error`-level log
entry that's *meant* to be unreachable. Confirmed unreachable from
`PPU.writePPUData` but possible if any future bus consumer routes a
palette address here.

### ppu/PaletteMemory.java + ppu/PatternMemory.java — dead code

Both classes have empty `connectPPUBus`/getter implementations and
inherit the default `ppuBusRead`/`ppuBusWrite` from `PPUBusComponent`,
which would cause infinite recursion if invoked
(`getPPUBus().read(addr)` re-routes to itself). Grep confirms neither
is instantiated anywhere. Delete or move to test scaffold.

### PPUBusComponent.java:8-23 — default `ppuBusRead`/`ppuBusWrite` is a recursion landmine

A subclass that forgets to override these inherits a delegate that calls
`ppuBus.read(addr)`, which the bus then re-routes back via
`inPPUusRange` (note: typo, missing `B`) to the same component. Any
component whose range contains the address infinite-loops.
`NameTableMemory` correctly overrides; `PaletteMemory`/`PatternMemory`
don't — fortunate they're never instantiated. Delete the defaults or
make them throw.

### PPU.java:283-290 — `frameComplete = false` set on pre-render line is redundant

`frameComplete` is set true only at the 261→0 scanline wrap; the
`clearFrameComplete()` (called by `NesSystem.runFrame`) and next-frame
entry already manage it. The pre-render reset writes false on top of
false. Harmless.

### PPU.java:438 — `cpu.cycles = 8` on NMI clobbers in-progress instruction

Outside review scope (CPU), but flagged because `NesSystem.tick` invokes
`cpu.nmi()` immediately on `consumeNmi()` regardless of CPU instruction
state. If the CPU is mid-instruction (cycles > 0 at that master tick),
the NMI overwrites PC/stack mid-flight. Real hardware samples NMI at
instruction boundaries. Uncertain whether any nestest-validated path
exposes this.

### Controller.java:82-94 — strobe rising-edge branch is empty

The `if (newStrobe && !strobe)` block exists only for the comment. Dead.
Remove or document why the empty body matters.

### NameTableMemory.java:115 — four-screen fallback silently downgrades

`FOUR_SCREEN` logs once-per-write a warning and falls back to
horizontal. Future test ROM with four-screen mirroring would flood
logs. Either implement, or extend `NameTableMemory` to 4KB and use the
standard 4-table mapping.

### RomCatalog.java:107-110 — bare `catch (Exception e)` swallows everything silently

The filesystem scan returns empty on **any** exception, including
SecurityException or programming errors. Log the swallowed exception at
debug level.

### NesSystem.java:131-138 — `runFrame` safety cap is overly generous

3× the nominal frame length is 268,026 ticks — close to a quarter
second of wall time at 60Hz. If `isFrameComplete` is broken, tests look
hung for 250ms before throwing. A 1.5× cap is plenty.

### PPU.java:519-522 — `clearBgPatternShadow` not called from `connectPPUBus`

Reset only clears the shadow. If a host swaps cartridges or re-wires
the bus mid-emulation, stale BG pixel values from the previous game can
survive into the next frame's sprite-priority compositing. Tests reset
before each, so not exercised.

---

**Working tree:** clean. No files modified during review.
