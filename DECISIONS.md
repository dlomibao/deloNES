# DECISIONS.md — NES 2.0 support (feature/nes20-support)

Design decisions made during implementation, with alternatives considered.
For PR-time review. Companion plan: `aiplan/nes20-support-plan.md`.

## D1 — Extend `INESHeader` in place (no NES2 subclass)

**Options:** (a) `NES2Header extends INESHeader`; (b) separate parser class +
result record; (c) format-aware accessors inside the existing `INESHeader`.

**Chosen: (c).** NES 2.0 is a strict superset of iNES 1.0 sharing bytes 0–7;
every call site (`Cartridge`, validator, web launcher logging) wants "the
mapper number" / "the PRG size" without caring about format. A subclass forces
a factory + instanceof checks for zero benefit; accessors that branch on
`isNES2Format()` internally keep one construction path and one source of truth.

## D2 — Exponent-multiplier sizes supported, capped at 64 MiB

**Options:** (a) reject exponent form outright; (b) support unbounded via
`long`/chunked arrays; (c) support with a sanity cap.

**Chosen: (c), cap = 64 MiB per ROM area.** The exponent form can encode up to
2^63 bytes — meaningless for real carts (largest known dumps are well under
64 MiB) and dangerous as an allocation DoS from a hostile header (the web build
takes arbitrary user files). Compute in `long`, reject > cap with a clear
`IllegalArgumentException`; the validator surfaces it as a normal validation
error. Also reject sizes that aren't a multiple of the bank size (16 KB/8 KB)
so `nPRGBanks`/`nCHRBanks` stay exact for the existing mapper constructors.

## D3 — CHR-RAM size: `max(header byte 11, mapper expectation)`, mapper default as fallback

**Options:** (a) NES 2.0 byte 11 always authoritative (0 ⇒ no CHR memory);
(b) always use `Mapper.getChrRamSize()`; (c) byte 11 when non-zero, else
mapper default; (d) `max` of the two when the header declares CHR-RAM, with a
WARN on disagreement; mapper default when it declares nothing.

**Chosen: (d).** Spec-pure (a) breaks real-world sloppy headers that zero
byte 11 on CHR-RAM carts, yielding a cart with no pattern memory and garbage
rendering; (b) ignores the very field NES 2.0 added for this (UNROM-512
carts legitimately declare 8/16/32 KB variants); (c) — the round-1 choice —
still lets a header that declares 8 KB starve a mapper whose banking assumes
32 KB (UNROM-512): reads past the allocation degrade to silent 0-reads via
the existing bounds checks, i.e. silent garbage. `max` honors correct headers,
never under-allocates below what the wired mapper addresses, and the WARN
makes the disagreement visible. iNES 1.0 path (CHR banks == 0 ⇒
`mapper.getChrRamSize()`) is unchanged. (Revised after review round 1.)

## D4 — Console type: reject VS/PC10/extended at validation; timing: warn only

**Options:** (a) load anything and render garbage; (b) reject both non-NES
console types and PAL timing; (c) reject console types we can't emulate,
warn-but-load on PAL/Dendy timing.

**Chosen: (c), enforced in `Cartridge` (core), not only the desktop validator.**
VS System / PlayChoice-10 use different PPU/palette/IO hardware — loading them
produces broken output with no path to "mostly works", so a clear early error
beats silent garbage. PAL games, by contrast, mostly run on an NTSC core
(wrong speed/timing edge cases) — blocking them would be overkill for a POC
emulator. Matches the header-field plan: parse everything, gate only what is
guaranteed-broken.

**Enforcement point (revised after review round 1):** the desktop validator
alone can't carry this gate — the web drag-drop path (`HtmlLauncher` →
`Cartridge`) bypasses it entirely, so a VS ROM would have loaded and rendered
garbage on the web build. The check therefore lives in `Cartridge`'s
constructor (throw with a descriptive message; the web path's existing
`catch` reports it and falls back to the idle gradient), and the desktop
validator duplicates it only to give a friendlier pre-flight message.

## D5 — PRG-RAM/NVRAM: parse + expose, do not allocate

**Options:** (a) full $6000–$7FFF PRG-RAM implementation now; (b) parse the
size fields and expose accessors, defer the RAM wiring.

**Chosen: (b).** No mapper in the current set wires PRG-RAM (`Cartridge` has
no $6000–$7FFF backing store today), and none of the target ROMs need it.
Allocating memory nothing can address is dead weight; doing the full wiring
drags battery-save persistence into a header-format PR. Recorded as an
explicit follow-up in the plan's out-of-scope list.

## D6 — Shared supported-mapper set on `Cartridge`

**Options:** (a) keep validator's own list; (b) `Cartridge.SUPPORTED_MAPPERS`
constant + `isMapperSupported()`, both the construction switch and the desktop
validator consult it; (c) full mapper-factory registry refactor.

**Chosen: (b).** The bug this PR also fixes — validator still says "only NROM
supported" while `Cartridge` wires 7 mappers — is exactly the drift (a)
invites. (c) is the cleanest long-term but churns every mapper's construction
for no user-visible gain; (b) gets a single source of truth with a minimal
diff. The validator also switches to deriving the mapper number via
`INESHeader` itself, deleting its duplicated DiskDude/mapper-formula code.

Note: `Set.of` is valid here — the branch builds at Java 25
(`sourceCompatibility = VERSION_25`) and TeaVM 0.14 provides the Java 9+
collection factories. A round-1 review flag citing "Java 8 source
compatibility" traced to stale project docs, and was dismissed after
verifying `build.gradle`.

## D7 — Validator keeps reading only the first 16 bytes

**Options:** (a) validate the whole file (sizes vs actual length); (b) header
bytes only, leave file-length integrity to `Cartridge`'s new bounds checks.

**Chosen: (b).** The select-screen validator's job is fast pre-flight triage
of the header; `Cartridge` must bounds-check anyway (web drag-drop bypasses
the validator entirely), so duplicating length math in the validator adds a
second copy of the same arithmetic to keep in sync. Truncated files fail at
load with a descriptive error either way.

## D8 — Unsupported mapper: fail fast at `Cartridge` construction

**Options:** (a) keep the historical `mapper = null` tolerance (construct,
render nothing); (b) throw a descriptive exception from the constructor.

**Chosen: (b).** The "tolerance" was illusory: `chrRead`/`chrWrite`/
`getMirrorMode` null-guard the mapper, but `cpuBusRead`/`cpuBusWrite` do not —
an unknown-mapper ROM NPE'd on the first CPU fetch of the reset vector, i.e.
it always crashed, just later and with a worse message. NES 2.0's 12-bit
mapper space makes unknown mappers more likely, so fail at load with
"unsupported mapper N (supported: 0, 1, 2, 3, 4, 7, 30)". Both frontends
already handle a throwing load path: the desktop validator screens before
`Cartridge` is reached, and the web `installRom` catches, logs, and falls
back to the gradient. The dead `case 66:` stub is removed; the existing null
guards stay as belt-and-braces. (Added after review round 1 — both reviewers
independently flagged the null-tolerance claim.)

## D9 — Truncated files: explicit length check, because `copyOfRange` zero-pads

Not a choice between designs so much as a corrected premise, recorded because
review round 1 caught the plan asserting the opposite: `Arrays.copyOfRange`
does **not** throw when `to > data.length` — it silently zero-pads the tail.
A truncated ROM therefore used to load as zero-filled PRG/CHR and emulate
garbage with no signal. `Cartridge` now checks remaining length before each
slice and throws "file is N bytes, header declares M".
