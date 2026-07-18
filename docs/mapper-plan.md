# Common-mappers plan

**Branch:** `feature/common-mappers` (off `feature/web-phase0` tip — has the
post-refactor Mapper interface with `int UNMAPPED = -1` sentinel).

**Goal:** ship MAPPER 1, 2, 3, 4, 7, and 30 on top of the existing Mapper000
support, with TDD throughout, parallelised where safe, and stage-gated on
both unit-test pass + measured coverage. End-state: 6 mappers covering
~74% of the NES library plus the most prominent homebrew (Micro Mages).

This doc is the source of truth. Drive execution from it; don't rely on
chat context. Update checkboxes inline as phases land.

---

## Mappers inventory + final-smoke ROM targets

| # | Name | Most-iconic game (final smoke) | Coverage | Complexity | Phase |
|---|---|---|---|---|---|
| 0 | NROM | Donkey Kong (in-repo locally only) | ~10% | ✅ shipped | — |
| 2 | UxROM | **Mega Man** (1987, Capcom) | ~9% | trivial — one PRG-bank reg | B |
| 3 | CNROM | **Adventure Island** (1988, Hudson) | ~6% | trivial — one CHR-bank reg | B |
| 7 | AxROM | **Battletoads** (1991, Rare) | ~3% | easy — PRG-bank + 1-screen mirror | B |
| 1 | MMC1 | **The Legend of Zelda** (1987, Nintendo) | ~28% | moderate — 5-bit serial shift reg | C |
| 4 | MMC3 | **Super Mario Bros. 3** (1990, Nintendo) | ~28% | hard — PRG/CHR layouts + scanline IRQ | D |
| 30 | UNROM-512 | **Micro Mages** (2018, Morphcat) | (homebrew) | low-mod after Phase A | E |

After all 6 land: ~74% of licensed catalog + the flagship UNROM-512
homebrew title.

---

## Decisions resolved up front

| Topic | Decision | Notes |
|---|---|---|
| Coverage tool | **JaCoCo**, enforced per-package threshold | `rom.mapper.*` and any new mapper class must hit ≥90% line coverage. Build fails below threshold. Snippet under "Test infrastructure" §. |
| Blargg test ROMs | **Include in-repo with credit**, no commercial use | Blargg/Shay Green's ROMs have no formal license but have been redistributed for decades by FCEUX, Mesen, Nestopia, etc. The de-facto norm is "freely redistributable for non-commercial emulator development with credit." We'll include only what we need, ship under `core/src/test/resources/test-roms/blargg/<name>/`, and append a Blargg section to the existing top-level [`CREDITS.md`](../CREDITS.md) (which already credits nestest's author kevtris). Owner has been silent on takedown requests for 15+ years; if Blargg ever asks, we remove. |
| Smoke ROMs (one per mapper) | Test at the END of the project with iconic games above | User has DK locally; for others, user provides own ROMs OR we lean on Blargg+synthetic. Plan does NOT include the iconic ROMs in-repo. |
| Browser ROM picker | **Yes, do it** — Phase F, parallel-able with C/D/E | Audit's C3. Hidden `<input type="file">` + drag-drop, byte[] → `Cartridge`. Needed to demo any non-DK mapper in browser. |

---

## Test infrastructure

### JaCoCo coverage gate (Phase A0 — add first)

Add to `core/build.gradle` (inside the `project(":core") { ... }` block):

```gradle
apply plugin: "jacoco"

jacoco {
    toolVersion = "0.8.11"
}

test {
    finalizedBy jacocoTestReport
}

jacocoTestReport {
    dependsOn test
    reports {
        xml.required = true
        html.required = true
    }
}

jacocoTestCoverageVerification {
    dependsOn jacocoTestReport
    violationRules {
        rule {
            element = "CLASS"
            includes = [
                "net.lomibao.nes.rom.mapper.*"
            ]
            // Built-in Mapper interface (no body) is excluded by element=CLASS
            // + 'limit' below targeting instructions; if a class has no
            // executable instructions JaCoCo skips it.
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.90
            }
        }
    }
}

check.dependsOn jacocoTestCoverageVerification
```

Stage gate: `./gradlew core:check` runs tests AND coverage verification.
A failing coverage check fails the build. Reports land at
`core/build/reports/jacoco/test/html/index.html`.

### Synthetic-ROM helper (Phase A0 — add alongside JaCoCo)

`core/test/net/lomibao/nes/rom/mapper/MapperTestSupport.java`:

```java
public final class MapperTestSupport {
    private MapperTestSupport() {}

    /**
     * Build an iNES-format byte[] for unit-testing a specific mapper.
     * Mirroring is horizontal (header bit 0 = 0), trainer absent, no
     * battery-backed RAM. PRG is filled with the supplied seed bytes
     * (or a deterministic pattern if no seed given). CHR is zeros if
     * chrKB == 0, otherwise filled with seed bytes too.
     */
    public static byte[] buildSyntheticROM(
            int mapperId, int prgKB, int chrKB, byte[] prgSeed, byte[] chrSeed) {
        // Header (16 bytes): "NES\x1A" + PRG-banks + CHR-banks + flags6 + flags7
        // PRG (prgKB * 1024 bytes): fill from prgSeed, repeating if needed
        // CHR (chrKB * 1024 bytes): fill from chrSeed, or all zero
        ...
    }
}
```

Used by every mapper unit test to construct realistic `Cartridge`
instances without needing real ROMs.

### Blargg test ROM bundle

Drop these under `core/src/test/resources/test-roms/blargg/`:

| Phase | ROM | Purpose |
|---|---|---|
| A | `cpu_interrupts_v2.nes` (single-ROM build) | Verify IRQ behavior post-A3 — MMC3 prep |
| D | `mmc3_test_2/` (suite) | MMC3 ground truth |

Attribution: append a `Blargg test ROMs` section to the top-level
[`CREDITS.md`](../CREDITS.md) (which already credits kevtris for
`nestest.nes`). Use the same template style as the nestest entry —
author, location in repo, purpose, license disposition, source. No
per-directory README needed.

### Per-phase test count targets

Loose floor — agents should aim higher when natural.

| Phase | Min new tests | Notes |
|---|---|---|
| A0 (infra) | 6 | jacoco wiring + helper |
| A1 (CHR-RAM) | 6 | write/read round-trip; ROM mode no-op |
| A2 (mirroring) | 6 | mirror() dynamic switch propagates |
| A3 (PPU A12) | 6 | A12 rising-edge counted exactly once per scanline |
| B1 (UxROM) | 10 | bank-switch + fixed-last-bank + write-anywhere-in-range |
| B2 (CNROM) | 8 | CHR bank-switch; PRG fixed |
| B3 (AxROM) | 10 | PRG bank + mirroring control bit |
| C (MMC1) | 25 | serial reg, control reg, all bank modes |
| D (MMC3) | 30 | bank registers, layout modes, IRQ counter, A12 |
| E (UNROM-512) | 10 | PRG + CHR + mirror in one reg write |

Cumulative: 422 (today) → ~540 (after all phases).

---

## Phase A — Shared infrastructure (sequential, 4 small PRs)

Each is its own PR, single agent. Must land before any dependent phase
starts.

### A0 — JaCoCo + synthetic-ROM helper

- Add the gradle snippet above to `core/build.gradle`.
- Add `MapperTestSupport.buildSyntheticROM(...)`.
- Add one unit test exercising the helper.
- Confirm `./gradlew core:check` runs jacoco AND fails when coverage
  is artificially gamed down (smoke check).

**Gate:** `core:check` green; `core:jacocoTestReport` lands HTML at
`core/build/reports/jacoco/test/html/`.

### A1 — CHR-RAM write support

- `Cartridge.chrWrite(int addr, byte value)` calling
  `mapper.ppuMapWrite(addr)` and writing to `vCHRMemory`.
- `PPUBus.write` routes `$0000-$1FFF` through `cart.chrWrite` when a
  cartridge is connected.
- `Cartridge` allocates `vCHRMemory` honoring CHR-RAM size from iNES
  header (or default 8KB for `nCHRBanks == 0`).
- For Mapper000: `ppuMapWrite` already returns `address` when
  `nCHRBanks == 0`. No regression.

**Tests:**
- Round-trip: write byte at PPU `$0500`, read same address → expect
  same byte. CHR-RAM mode (Mapper000 with `nCHRBanks == 0`).
- CHR-ROM mode: write is a no-op (Mapper000 with `nCHRBanks > 0`).
- PPU bus routing: write to `$0500` reaches cart, not pattern memory.

**Gate:** all of the above + `core:check` green.

### A2 — Runtime mirroring control

- `PPUBus` / `NameTableMemory` query `mapper.mirror()` per nametable
  access. Cache it once per `clock()` call so the hot path doesn't
  pay the virtual dispatch 89k times/frame.
- Default `Mirror.HARDWARE` → fall back to iNES bit 0 (existing
  behavior).
- Mapper000 keeps returning `HARDWARE` (no behavior change).

**Tests:**
- Construct a stub mapper whose `mirror()` flips mid-test (HORIZONTAL
  → VERTICAL). Write byte to nametable A's `$2000`; assert mirror
  position reflects the new mode (not the iNES header).
- `HARDWARE` mode preserves existing iNES behavior.
- Hot-path perf: PPU.clock() with mirror-cached field doesn't regress
  desktop FPS measurably (sanity check, not strict).

**Gate:** above tests + desktop DK still hits 60 FPS (manual visual).

### A3 — PPU A12 hook for mapper IRQ

- `Mapper` interface gets `default void tickPpuA12(int v, int prevV) {}`.
- `PPU` calls `cart.notifyPpuA12(v, prevV)` on each PPU bus address
  change (cheapest: detect rising edge inline in the address-emit path).
- Performance: must not regress PPU.clock() noticeably. Bench against
  the runFrame ms log line in the web build before/after.

**Tests:**
- Mock mapper counts A12 rising edges across a synthetic scanline;
  assert exactly 1 per scanline at the expected cycle.
- Mapper000 default no-op doesn't crash.

**Gate:** above + web build runFrame within 5% of pre-A3 measurement.

---

## Phase B — Simple mappers in 3 parallel worktrees

**TDD discipline (strict, all three agents):**

```
1. RED:   write MapperNNNTest.java first; confirm test fails without impl
2. GREEN: implement MapperNNN.java to make tests pass
3. REFACTOR: pull common patterns into a helper or shared base if it emerges
4. WIRE: add `case N:` to Cartridge.java's mapper-selection switch (line ~84)
5. INTEGRATE: build a synthetic iNES byte[] in a CartridgeMapperNNNTest,
   construct Cartridge, drive register writes, assert mapping
6. SUITE: `./gradlew core:check` green (tests + coverage ≥90% on the new class)
```

**Conflict point:** all three modify `Cartridge.java`'s mapper switch.
Resolution: land B1 → B2 → B3 serially via squash merge; each rebases
on master before merge. The mapper-switch additions are 3-line
non-conflicting cases as long as they're appended in numeric order.

### B1 — Mapper 2 (UxROM)

**Spec:** NESdev wiki [Mapper 2](https://www.nesdev.org/wiki/UxROM).

- Register: writes to `$8000-$FFFF` set the PRG bank (low 4 bits used,
  some games write higher bits with no effect).
- PRG layout: 16KB switchable at `$8000-$BFFF`, **last 16KB fixed** at
  `$C000-$FFFF`.
- CHR: 8KB fixed (CHR-ROM or CHR-RAM if `nCHRBanks == 0`).
- Mirroring: from iNES header, not switchable.

**Tests (10+):**
- `cpuMapRead($8000)` → first byte of bank 0 after `cpuMapWrite($8000, 0)`
- `cpuMapRead($8000)` → first byte of bank 3 after `cpuMapWrite($8000, 3)`
- `cpuMapRead($C000)` → always points at last bank regardless of register
- Bank register writes to `$9000`, `$ABCD`, `$FFFF` all switch banks
- Out-of-range CPU read (`$0000`, `$7FFF`) → `UNMAPPED`
- CHR read passthrough
- 128KB ROM (8 banks): bank 7 → last; banks 0-6 switchable
- 256KB ROM (16 banks): switchable range expands

### B2 — Mapper 3 (CNROM)

**Spec:** NESdev wiki [Mapper 3](https://www.nesdev.org/wiki/CNROM).

- Register: writes to `$8000-$FFFF` set the CHR bank (low 2 bits; some
  variants use more).
- PRG layout: 16KB or 32KB fixed (no banking).
- CHR layout: 8KB switchable at `$0000-$1FFF`.
- Mirroring: iNES header.

**Tests (8+):**
- `ppuMapRead($0000)` → first byte of CHR bank 0 after
  `cpuMapWrite(anything, 0)`
- `ppuMapRead($0000)` → first byte of CHR bank 3 after `cpuMapWrite(..., 3)`
- PRG reads always point at the same bytes (no PRG banking)
- Bus conflict edge: writing where ROM is also reading (NESdev notes
  CNROM has bus conflicts; OK to ignore for now)

### B3 — Mapper 7 (AxROM)

**Spec:** NESdev wiki [Mapper 7](https://www.nesdev.org/wiki/AxROM).
**Depends on A2.**

- Register: writes to `$8000-$FFFF` — bits 0-2 select **32KB PRG bank**
  (note: 32KB step, not 16KB). Bit 4 selects single-screen mirroring
  (0 = ONESCREEN_LO / NT A; 1 = ONESCREEN_HI / NT B).
- PRG layout: 32KB switchable at `$8000-$FFFF` (whole CPU ROM window).
- CHR: 8KB CHR-RAM typically (no banking).
- Mirroring: always single-screen; controlled by bit 4.

**Tests (10+):**
- PRG 32KB bank switch
- Mirror returns ONESCREEN_LO when bit 4 = 0
- Mirror returns ONESCREEN_HI when bit 4 = 1
- iNES 4-screen bit is overridden by AxROM's runtime mirror

### Phase B gate

- ✅ 3 new mapper test classes, each ≥ test floor and ≥90% coverage
- ✅ `core:check` green (tests + jacoco)
- ✅ Cartridge mapper switch wired for IDs 2, 3, 7
- ✅ Test count grew from 422 → ~452

---

## Phase C — Mapper 1 (MMC1)

**Spec:** NESdev wiki [MMC1](https://www.nesdev.org/wiki/MMC1).

Single agent, sequential. MMC1 is too stateful to parallelize cleanly.

### Registers

MMC1 has FOUR internal 5-bit registers, written via a serial protocol:

- Writes to `$8000-$FFFF` shift bit 0 of the value into a 5-bit shift
  register. After 5 writes, the accumulated value goes to the
  destination register selected by bits 13-14 of the **write address**.
- Bit 7 of any value resets the shifter (and OR's $0C into the control
  register).

**Destination registers:**
- `$8000-$9FFF` → Control: mirroring (bits 0-1), PRG mode (2-3), CHR mode (4)
- `$A000-$BFFF` → CHR bank 0 (4KB or 8KB depending on CHR mode)
- `$C000-$DFFF` → CHR bank 1 (only used in 4KB CHR mode)
- `$E000-$FFFF` → PRG bank (4 or 5 bits depending on PRG mode)

### TDD sub-stages

| Sub | Deliverable | Tests |
|---|---|---|
| C1 | Serial shift register: 5 writes commit, bit 7 resets | 5-6 |
| C2 | Control register: mirror modes (1SCREEN_LO/HI, V, H), PRG modes, CHR modes | 4 |
| C3 | CHR bank registers (4KB mode + 8KB mode) | 6-8 |
| C4 | PRG bank register: 16KB mode (low fixed/high switchable + inverse) + 32KB mode | 6-8 |
| C5 | Cartridge wiring: iNES mapper=1 instantiates MMC1 | 1 |
| C6 | E2E synthetic-ROM: write a small program that switches a PRG bank, asserts the right byte is fetched | 1 |

### Phase C gate

- ✅ ≥25 MMC1 unit tests, ≥90% coverage
- ✅ `core:check` green
- ✅ Synthetic-ROM E2E passes
- ✅ Manual (if user provides Zelda 1 ROM): boots to title screen on desktop

---

## Phase D — Mapper 4 (MMC3)

**Spec:** NESdev wiki [MMC3](https://www.nesdev.org/wiki/MMC3).
**Depends on A3** (PPU A12 hook).

The biggest. Single dedicated agent. ~3-4 days of work.

### Registers

- `$8000` Bank select (even): bits 0-2 R (which register), bit 6 PRG
  mode, bit 7 CHR A12-inversion
- `$8001` Bank data (odd): value goes to register R0..R7
- `$A000` Mirroring (even): bit 0 (vertical vs horizontal)
- `$A001` PRG RAM protect (even): bit 7 enable, bit 6 write-protect
- `$C000` IRQ latch (even): reload value for IRQ counter
- `$C001` IRQ reload (odd): clear counter so next A12 rising edge reloads
- `$E000` IRQ disable (even): also clears pending IRQ
- `$E001` IRQ enable (odd)

R0..R5 are 6-bit CHR banks; R6/R7 are 6-bit PRG banks. PRG/CHR layouts
toggle based on mode bits.

### TDD sub-stages

| Sub | Deliverable | Tests |
|---|---|---|
| D1 | Bank registers R0..R5 (CHR) + R6/R7 (PRG); even/odd address discrimination | 8 |
| D2 | PRG layout mode bit (swap $8000↔$C000 windows) | 4 |
| D3 | CHR A12-inversion mode bit (swap $0000-$0FFF ↔ $1000-$1FFF) | 4 |
| D4 | Mirroring register ($A000) — H/V only on MMC3 | 2 |
| D5 | A12 detection: rising edge on PPU bus address (uses A3 hook) | 4 |
| D6 | IRQ counter: latch, reload, decrement on each A12 rising edge, IRQ assertion | 8 |
| D7 | Blargg `mmc3_test_2` suite: run it headlessly, assert "all tests passed" string in output | 1 |

### Phase D gate

- ✅ ≥30 MMC3 unit tests, ≥90% coverage
- ✅ `core:check` green
- ✅ Blargg `mmc3_test_2` passes headlessly
- ✅ Manual (if user provides SMB3 ROM): boots to title screen on desktop
- ✅ Web build runFrame within 10% of pre-D measurement (A12 hook is
  per-PPU-bus-access; verify it didn't tank perf)

---

## Phase E — Mapper 30 (UNROM-512)

**Spec:** NESdev wiki [UNROM 512](https://www.nesdev.org/wiki/UNROM_512).
**Depends on A1 + A2.** Fast after those land.

### Register

Single register at `$C000-$FFFF`:

```
7654 3210
M.CC PPPP
| ||  ++++ 5-bit PRG bank (16KB step, up to 32×16KB = 512KB PRG)
| ||
| ++------ 2-bit CHR-RAM bank (4×8KB = 32KB CHR-RAM)
+--------- 1-screen mirror select (when 1-screen mode is enabled)
```

Header bit/setup determines whether mirroring is 1-screen-with-select,
or fixed (horizontal/vertical/4-screen via four-screen NT).

### Sub-stages

| Sub | Deliverable | Tests |
|---|---|---|
| E1 | Register decode: PRG bank, CHR bank, mirror | 4 |
| E2 | PRG 16KB switchable at $8000-$BFFF, last 16KB fixed at $C000-$FFFF | 3 |
| E3 | CHR-RAM 8KB switchable (32KB total, 4 banks) | 3 |

### Phase E gate

- ✅ ≥10 UNROM-512 unit tests, ≥90% coverage
- ✅ `core:check` green
- ✅ Manual: Micro Mages boots to title screen on desktop (and in
  browser via the Phase F picker)

---

## Phase F — Browser ROM picker (parallelisable from any point after Phase A)

Audit's C3. Independent of mapper work; one agent can pick this up
alongside any of B/C/D/E.

### Deliverable

- `html/webapp/index.html` — add `<input type="file" id="rom-picker"
  accept=".nes" style="display:none">` plus a "Load ROM" button. Also
  a `<div>` accepting drag-and-drop.
- `html/src/.../HtmlLauncher.java` — expose a JSO function
  `loadRomBytes(byte[])` callable from JS. Reuses the existing
  `setupEmulator(...)` codepath with the supplied bytes.
- JS glue (inside `index.html` or a small `<script src>`) — wires
  `FileReader.readAsArrayBuffer` to `loadRomBytes` via TeaVM JSO
  interop.

### Tests

- Unit test: `WebLauncher.loadRomBytes(byte[])` constructs a Cartridge
  and replaces the current `nes` instance.
- Smoke (headless): load a synthetic NROM byte[], confirm `nes != null`
  after.

### Phase F gate

- ✅ Picker visible in browser; drag-drop also works
- ✅ Selected ROM replaces the running emulator within a frame
- ✅ Old emulator state is disposed cleanly (no leaked Pixmaps etc.)

---

## Parallelism map

```
A0 ─→ A1 ─→ A2 ─→ A3        (sequential infra)
                  │
                  ├─→ B1 ─┐
                  ├─→ B2 ─┤  (parallel worktrees; serial merge)
                  └─→ B3 ─┘
                          │
                          ▼
                          C                (MMC1, sequential)
                          │
                          ▼
                          D                (MMC3, sequential)
                          │
                          ▼
                          E                (UNROM-512, sequential)

(Phase F can run in parallel with any phase ≥ A0.)
```

Total estimate end-to-end: ~7-10 working days.

---

## Subagent prompt templates (use verbatim at kickoff)

When parallel Phase B kicks off, dispatch three `general-purpose` agents
in `worktree` isolation. Each gets a self-contained prompt referencing
this doc. Below are the templates.

### Phase B agent prompt (UxROM example — adapt N for the others)

```
You're implementing iNES Mapper 2 (UxROM) on the deloNES NES emulator,
following the TDD plan in docs/mapper-plan.md (Phase B1).

Context: this repo has a Mapper interface at
core/src/net/lomibao/nes/rom/mapper/Mapper.java that returns
primitive int from cpuMapRead/cpuMapWrite/ppuMapRead/ppuMapWrite,
using `int UNMAPPED = -1` as the sentinel for "address not in range".
Mapper000 is the only existing implementation; mirror it for shape.

Spec: NESdev wiki Mapper 2 (UxROM). PRG-bank-switched at $8000-$BFFF,
last 16KB fixed at $C000-$FFFF, CHR fixed (8KB ROM or RAM).
Mirroring is from iNES header, not switchable.

Do all of the following in strict order:

1. RED: create core/test/.../MapperUxROMTest.java with at least 10
   test cases listed in Phase B1 of the plan. Run `core:test`,
   confirm the tests fail with "no implementation" / NPE.
2. GREEN: create core/src/.../MapperUxROM.java implementing Mapper.
   Re-run, confirm tests pass.
3. REFACTOR: extract any helpers; ensure ≥90% line coverage
   reported by `core:jacocoTestCoverageVerification`.
4. WIRE: add `case 2: mapper = new MapperUxROM(nPRGBanks, nCHRBanks); break;`
   to Cartridge.java's mapper switch (after case 0, before others).
5. INTEGRATE: add a CartridgeMapperUxROMTest using
   MapperTestSupport.buildSyntheticROM(2, ...) to verify Cartridge
   constructs the right mapper and that bank switching is observable
   via Cartridge.cpuBusRead.
6. SUITE: run `./gradlew core:check` — must be green. Coverage on
   MapperUxROM ≥90% line. Full 422+ tests still passing.

Do not modify shared infrastructure (PPUBus, CPUBus, PPU, NesSystem,
or any other component that's not directly a mapper or its caller).
If you find you need to, STOP and surface the issue — that's a
plan-bug, not a Phase B work item.

Use the JAVA_HOME='/c/Program Files/AdoptOpenJDK/jdk-11.0.8.10-hotspot'
prefix on all gradle commands.

Working in an isolated worktree off feature/common-mappers. Commit
on a topic branch named feature/mapper-uxrom-tdd. Push when done.
Open a PR titled "feat(mapper): Mapper 2 (UxROM)" against
feature/common-mappers.
```

(Substitute `MapperCNROM`/`MapperAxROM`, `case 3`/`case 7`,
mapper-cnrom-tdd / mapper-axrom-tdd, and the per-mapper test floor
for B2 / B3.)

### Phase C / D / E agent prompts

Similar shape, but sequential (no worktree isolation needed since
they run alone). Reference Phase C/D/E sections of this doc for spec
and sub-stage decomposition. C and D should land their sub-stages as
separate commits inside one branch (so review is digestible), then a
single squash PR at the end.

### Phase A agent prompts

A0/A1/A2/A3 each get their own small focused agent. Sequential. Pre-stage
the spec from this doc; don't paraphrase.

### Phase F agent prompt

One agent. Independent. Phase F deliverable list is self-contained
above. Branch name `feature/web-rom-picker`. Open PR against
`feature/common-mappers`.

---

## Kickoff readiness checklist

Before firing the first agent:

- [ ] User says "kick off"
- [ ] Branch `feature/common-mappers` exists locally + on origin
- [ ] `feature/web-phase0` PR (#32) merged OR confirmed it will land
  before any mapper PR — otherwise this branch has unmergeable
  changes pending
- [ ] `JAVA_HOME='/c/Program Files/AdoptOpenJDK/jdk-11.0.8.10-hotspot'`
  works (confirmed in the prior session)
- [ ] `./gradlew core:test desktop:test` green (422/422)
- [ ] User has confirmed Blargg-ROM redistribution is OK for this repo
  (yes per decisions table above)
- [ ] User aware: will not have iconic-game ROMs in repo — they
  provide their own copies for manual smoke at end
- [ ] This doc committed to the branch so subagents can reference it

## Risk register

| Risk | Mitigation |
|---|---|
| A3 PPU-A12 hook regresses hot-path perf | Bench runFrame ms before/after on web build. Limit hook to a single int-equality on the PPU bus address-emit path. Fall back to "MMC3-only mode" if needed. |
| Blargg takedown request | Remove ROMs, fall back to synthetic + unit tests. MMC3 loses the Blargg-suite gold-standard check but unit + manual smoke still cover it. |
| MMC3 IRQ timing conflicts with our CPU reset-cycle fix (B8) | Re-run NestestTest after Phase D to confirm 8992/8992 byte-match. The MMC3 IRQ is a wholly separate code path from reset behavior; risk is low. |
| Parallel-merge conflict on Cartridge.java mapper-switch | Land B1 → B2 → B3 in numeric order; each rebases before merge. Cases are 3-line non-conflicting appends. |
| UNROM-512 mirroring interactions with iNES 4-screen bit | Phase A2 spec needs to handle 4-screen NT alongside dynamic mirror(). Add a test covering both. |
| Jacoco coverage gate too tight on edge cases | Threshold is 90% line, not 100%. Defensive logging branches stay below; we accept it. |

## Rollback strategy

Each phase is its own PR against `feature/common-mappers`. Bad merge
→ revert that one PR. Mappers don't depend on each other, so reverting
one (e.g., MMC3) doesn't block shipping the rest.

If we need to abandon: this whole branch can be discarded without
affecting `feature/web-phase0` or `master`. Phase A0 infrastructure
(JaCoCo, MapperTestSupport) is independently valuable and could be
extracted as a standalone PR for `master` even if mappers are deferred.

---

## When kicking off

Re-read this doc end-to-end. Confirm the kickoff readiness checklist
is all green. Start with **Phase A0** (single agent), then A1, A2, A3
in sequence. After A3 gates pass, fire B1+B2+B3 as three parallel
agents.

Do not skip the gates. Do not let an agent modify shared infrastructure
files outside its phase's scope without surfacing it.
