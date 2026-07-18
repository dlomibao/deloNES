# deloNES
a Java and LibGDX NES Emulator POC

## Reference links

- [NESdev Wiki](https://www.nesdev.org/wiki/Nesdev_Wiki) — canonical NES hardware reference
- [OneLoneCoder olcNES](https://github.com/OneLoneCoder/olcNES) — C++ reference implementation that inspired this project ([per-component review](docs/olcnes_review.md))
- [bugzmanov NES ebook](https://bugzmanov.github.io/nes_ebook/) — Rust-based step-by-step build with strong PPU/scrolling chapters ([review](docs/bugzmanov_nes_ebook_review.md))


## Devlog
### 7-18-2026 (NES 2.0 + mapper hardening)
* **Full NES 2.0 header support** (PR #33). Modern dumps and homebrew that ship NES 2.0 headers — e.g. Micro Mages — now load instead of being rejected; the emulator previously refused the format in the validator and NPE'd in `Cartridge`'s empty `fileType == 2` branch. `INESHeader` gained format-aware accessors for every 2.0 field: 12-bit mapper (byte-8 bits gated on the format check so iNES 1.0 PRG-RAM bytes can't corrupt the mapper number), submapper, byte-exact PRG/CHR sizes incl. the exponent-multiplier form (64 MiB sanity cap + overflow pre-guard — the web build takes arbitrary user files), RAM/NVRAM shift decode, timing, console type. Also fixed the stale validator that still claimed "only NROM supported" while the branch wires 7 mappers.
* **Load path hardened**: unsupported mappers and VS/PlayChoice-10 ROMs fail fast at `Cartridge` construction with descriptive errors (the old `mapper = null` "tolerance" actually NPE'd at the first reset-vector fetch); truncated files are detected explicitly (`Arrays.copyOfRange` silently zero-pads — a truncated ROM used to emulate garbage with no signal); CHR-RAM allocation is `max(header byte 11, mapper expectation)`.
* **Three-round adversarial review loop** (two independent reviewers per round, whole PR diff) caught serious latent bugs in the mapper stack and fixed them:
  * **UNROM-512 register decode was wrong** — spec is `[NCCP PPPP]` (PRG bits 0-4, CHR bits 5-6); code had PRG 0-3/CHR 4-5, so every CHR bank switch loaded wrong pattern data and PRG banks 16-31 were unreachable. The mapper's own TDD tests had baked in the same wrong layout.
  * **MMC3 scanline IRQ was dead code end-to-end** — the counter worked but nothing delivered it to the CPU. `NesSystem.tick()` now polls the cartridge IRQ line with level-held semantics (`CPU6502.irq()` reports taken/masked; the line stays asserted and retries while the I flag masks it). SMB3-class raster splits now have a path to working.
  * **MMC3 CHR registers are 8-bit, not 6** — the old mask capped CHR at 64KB (silent tile corruption on 128KB+ carts); and CHR-RAM (TNROM-class) writes now route through the same bank translation as reads.
  * **iNES mirroring polarity was inverted repo-wide** (flags-6 bit 0 = 1 means *vertical* per spec). Masked for months by a second bug: `RomLoader`/`EmulatorScreen` each connected an orphan `NameTableMemory` ahead of the PPU's own, pinning all mirroring to HORIZONTAL — header *and* runtime mirror switching (MMC1/AxROM/MMC3/UNROM-512) were inert on both frontends. Fixing the orphan armed the inverted decode; round 2 caught it. Both fixed; five test files had codified the wrong convention (nestest.nes is horizontally mirrored — byte 6 = 0x00 — not vertical as old test comments claimed).
  * **Bank registers wider than the cart now wrap** (modulo bank count, matching hardware's unwired address lines) in all five bank-switching mappers instead of crashing with `ArrayIndexOutOfBoundsException` — also closes a hostile-file crash via the web drag-drop path.
* Design decisions (with alternatives considered) logged in `DECISIONS.md` D1–D12 — new convention for PR-time review.
* Toolchain note: machine setup moved to SDKMAN (`sdkman-cli` via Homebrew tap) with Temurin 25 as default — Gradle 9.1 cannot run on JDK 26 (class file 70).
* Test count: **666 → 679 core tests** (NES 2.0 matrix, synthetic-cart loading, bank wrap, IRQ delivery, mirroring end-to-end regression), all green; nestest 8992/8992 unchanged throughout.

### 5-12-2026 (web port)
* **Donkey Kong playable in the browser.** Title screen, game-start, level 1 gameplay all running via TeaVM/WebGL in Chrome at sustained 60 FPS (JIT-warmed; emulator headroom is ~115 NES FPS / 8.6ms per `runFrame`). Keyboard → controller wiring: Arrows = D-pad, Z = A, X = B, Enter = START, Right Shift = SELECT.
* Phase 0 derisking proved gdx-teavm 1.5.6 + TeaVM 0.14.0 is a viable web target ([findings](docs/web-phase0-findings.md)). Bumped both, switched to the renamed `backend-web` artifact, rewrote `HtmlLauncher` against the new `WebApplication`/`WebApplicationConfiguration` API. Surfaced the 1.5.x asset preload manifest (`assets/preload.txt`) so `Gdx.files.internal()` resolves classpath ROMs + opcode table.
* **C1 (CPU6502 reflection refactor)** landed: per-instruction `Method.invoke` → hand-rolled string switch on opcode name and addressing mode. Same 8992/8992 nestest trace match preserved, mandatory for the web port and a desktop perf win.
* **Perf pass** to hit 60 FPS web — multi-round profile-and-fix off the running DK build. Findings, in order of impact:
  * `LogManager` html-stub's `format()` did `String.replaceFirst("\\{\\}", ...)` per call → compiled a regex per `log.trace`. CPU6502.clock() + PPU.clock() trace per instruction. Made trace/debug no-ops on the web build, replaced regex with a plain `indexOf` loop.
  * `PPU.nesColorToRGB` allocated a 64-int palette **on every call** (~3.7M allocs/sec at 60 FPS). Hoisted to `static final`.
  * `CPUBus.read/write/clock` wrapped every null-check in `Optional.ofNullable(x).map(f).orElse(false)` → 2 Optional allocs + lambda capture per check, per CPU instruction. Replaced with plain `x != null && x.f()` null-guards.
  * `Mapper.cpuMapRead/Write` returned `Integer` → boxed every cart access. Switched to primitive `int` with `Mapper.UNMAPPED = -1` sentinel.
  * `CPUBus.clock` did `masterClockCount % 3 == 0` per master tick → on TeaVM `long` is software-emulated. Added an `int phase` counter cycling 0→1→2; kept `long masterClockCount` for API compat. Same fix on `NesSystem.runFrame`'s safety-cap deadline check.
  * `PPU.checkSpriteZeroHit` called per tick (89k/frame) → after the hit fires, all remaining calls just return. Skip the call entirely with a `spriteZeroHitChecked` boolean; cleared on the pre-render scanline.
  * `CPUBus.read/write` did `component.inCPUBusRange(addr)` per component → 3 virtual calls per range check, ~21 per bus access. Inlined the NES hardware address constants ($0000-$1FFF, $2000-$3FFF, ...) directly. **The single biggest win — ~22 FPS → ~33 FPS.**
  * Also inlined `isShowBackground()` / `isShowBackgroundLeft()` at the per-pixel render call sites in PPU.
* **DonkeyKong.nes still isn't committed** (fair-use copy). `html/build.gradle` preloads it if the dev has a local copy in `core/src/main/resources/roms/`; falls back to bundled `nestest.nes` cleanly.
* TeaVM build settings flipped to `obfuscated = false` + `sourceMap = true` so the live console + DevTools profile show real Java symbols. Re-enable obfuscation before any size-sensitive deploy.

### 5-12-2026
* **Donkey Kong first level playable!** Title screen renders correctly; Mario, Pauline, barrels, oil drum + fire all in place; sprite/BG alignment correct.
* ![Donkey Kong running on deloNES](repoassets/dk_working.gif)
* Fixed PPU BG fetcher pipeline:
  * +2 tile lookahead during visible cycles (so the fetcher loads col 2 at cycle 1 while col 0 — pre-fetched on the previous scanline — renders at cycle 1).
  * Cycles 321-337 prefetch correctly reads col 0 and col 1 of the *next* scanline; BG shifters now shift during prefetch too, so col 0 ends in the HIGH byte by start of next scanline. Previously the leftmost ~16 pixels of every scanline rendered stale shifter data.
* Honored PPUMASK bit 1 (BG leftmost-8 clip): leftmost 8 pixels render backdrop when bit is clear.
* Added `DKDiagnosticRunner` (`./gradlew desktop:traceDK`) — pure-Java headless harness that dumps PPU registers, OAM, nametable summary, palette RAM, and ASCII framebuffer snapshots. The tool that finally pinned down the fetcher pipeline bug after multiple rounds of inconclusive visual-screenshot guessing. Kept in-tree as the canonical agent-friendly debugging entry point.
* **Multi-agent code review pass (15 PRs, 28 items, 50 new tests).** Five parallel agents reviewed core impl, desktop impl, core tests, desktop tests, and web-deployment feasibility; their reports + the resulting triage doc live under `docs/review-2026-05-12/`. Tier A (11 items, PR #16) and Tier B (16 items, PRs #17–#30) landed in this session. Tier C is scoped for later. Real correctness bugs found and fixed along the way:
  * **CPU**: `reset()` wasn't setting the I flag; reset cycle count was 8 instead of 7. The "8992/8992 nestest match" boast was misleading — the P-flag and CYC comparisons were commented out. Re-enabled them; now it's actually 8992/8992 on full P+CYC.
  * **PPU**: NMI rising edge inside VBlank wasn't firing (toggling `$2000` bit 7 mid-VBlank — Battletoads relies on this). BG shifter `LOAD` ran *after* the per-cycle shift on case 0, putting every tile col 2+ one pixel to the right of where it belonged (not visible in DK; would have broken SMB).
  * **Bus**: `$4014` (OAM DMA trigger) was routed to APU before the DMA controller. Latent until APU gets wired in; would have silently swallowed sprite-DMA writes.
  * **Cartridge**: `Cartridge.java:74` shadowed local `int nCHRBanks` meant the field never got set before `Mapper000` was constructed.
  * **iNES validator**: over-rejected NROM dumps with "DiskDude!" trailers; silently misidentified NES 2.0 mappers. Now applies the wiki-canonical DiskDude workaround and rejects NES 2.0 with a specific message.
  * **App stability**: a bad ROM bricked the app (`EmulatorScreen.show()` propagated, leaving a half-constructed screen). Now bounces back to the menu. A malformed `controls.json` crashed startup — now logs, renames to `.bak`, and falls back to defaults. `controls.json` itself moved from working-dir-relative to `~/.deloNES/controls.json` for a stable per-user location (with one-time migration).
  * **Encapsulation**: `PPU.registers` is now `private` with narrow `peek*` helpers for debug renderers (non-destructive — no VBlank-clear side effect) and a single `setRegisterForTest` seam. The previous public field bypassed all register side-effects.
  * **Test harness**: fixed the `HeadlessApplicationTest` double-dispose race and `Gdx.*` static-state leak. Ran clean 10× in a row after.
  * **Hygiene**: extracted the NES master palette (it was duplicated in 4 places), deleted the orphaned `NesEmulator` legacy entry point + `PixelRendererTest` + unused `PaletteMemory`/`PatternMemory`, and fixed `OpcodesTest`'s infinite-loop guard which was being silently clobbered by `writeRange(0x8000, program)`.
* Test count: **372 → 422 (+50)**, all green.
### 5-11-2026
* **ROM startup menu shipped.** Keyboard-navigable `RomSelectScreen` listing bundled ROMs (via `RomCatalog` reading `roms/index.txt`) plus a "Browse filesystem..." option via LWJGL3 `TinyFileDialogs`. Selection transitions to `EmulatorScreen`; Esc returns to menu, F5 resets, P pauses. `NesGame extends Game` glues the screens together.
* Configurable input via `controls.json` (auto-written with defaults on first run): P1 = Arrows + Z/X/Enter/RShift, P2 = WASD + G/H/LShift/Tab. Two-player keyboard input fully wired.
* Replaced the open-bus stub `Controller` with a real shift-register implementation (P1 @ $4016, P2 @ $4017). Fixed `CPUBus.read()` to route $4016/$4017 to the controller *before* the APU (real-hardware order).
* PPU NMI now decoupled via a `nmiPending` latch — `NesSystem.tick()` polls `consumeNmi()` once per master tick and dispatches to `cpu.nmi()`. DK's wait-for-NMI loop now exits.
* OAM DMA actually runs: `EmulatorScreen` and `NestestBackgroundRenderer` migrated to `NesSystem.runFrame()` so `CPUBus.clock()` drives the DMA state machine on $4014 writes. DMA also respects `OAMADDR` per NESdev.
* PPU $2004 (OAMDATA) write/read no longer stubbed — they hit `oam[OAMADDR]` with auto-increment on write.
* Sprite-0 hit: replaced coarse-bounding-box check with per-pixel opacity test (both sprite-0 *and* BG pixel must be opaque), plus leftmost-8 PPUMASK gate and the `x != 255` rule.
* `EmulatorScreen` extracted from `NestestBackgroundRenderer` as a reusable LibGDX `Screen` taking a `RomSource` (classpath or filesystem).
* `DonkeyKong.nes` is *not* committed (fair-use copy only — no redistribution rights). `nestest.nes` is the bundled default. Drop your own DK locally + add to `roms/index.txt` to surface it in the menu, or use the filesystem-browse entry.
### 1-1-2026
* fixed background tile rendering (issue with PPU using ARGB vs RGBA causing alpha being read from wrong spot)
* ![alt text](repoassets/bgrender.gif)
### 12-31-2025
* Background rendering working with bugs! 
* ![alt text](repoassets/brokenbg.png)
### 12-28-2025

* Fixed tests
* add CHR ROM viewer

### 12-25-2025

* Merry Christmas!
* added support for all undocumented/illegal opcodes
* full nestest headless validation 8992/8992 output match

### 12-24-2025

* finally got back to working on this (had a new job + baby)
* added rendering of arbitrary pixel array
* added cpu opcode unit tests
* added nestest validation (identified cpu bugs)

### 6-2-2024

* ppu work
* load color palettes

### 5-29-2024

* cartridge setup work

### 5-16-2024

* added basic mapper interface, started work n loading the cartridge

### 5-15-2024

* finally added to github
* completed instructions (untested)
* created basic way to load test programs
* First successful program output!
* takes 10 multiplies by 3 and puts 30 in memory

```
16:13:12.104 [main] INFO  net.lomibao.nes.NesEmulator - memory 0x0000
16:13:12.104 [main] INFO  net.lomibao.nes.NesEmulator - 0A 03 1E 00 00 00 00 00 00 00 00 00 00 00 00 00 // (10) (3) (30) 

16:13:12.105 [main] INFO  net.lomibao.nes.NesEmulator - memory 0x8000
16:13:12.105 [main] INFO  net.lomibao.nes.NesEmulator - A2 0A 8E 00 00 A2 03 8E 01 00 AC 00 00 A9 00 18 //the program code 
6D 01 00 88 D0 FA 8D 02 00 EA EA EA 00 00 00 00 
```

### 5-14-2024

* added code for around half the instructions

### 5-13-2024

* finished code for cpu addressing modes

### 5-12-2024

* setup Ram and basic cpuBus read/write interaction

### 5-10-2024

* loaded instructions into cpu from csv

### 5-09-2024

* setup base project
* got libgdx running
* got text to display on screen
* setup basic cpuBus class
* setup basic cpu class
* converted instruction timings and data into csv
* setup registers

