# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

deloNES is a Java + LibGDX NES (Nintendo Entertainment System) emulator POC. Multi-module Gradle build targeting desktop (LWJGL3) and HTML/WebGL (TeaVM 0.14 + gdx-teavm 1.5.6). **Java 25 LTS** (Gradle 9.1 + Lombok 1.18.42); the Gradle toolchain block auto-provisions JDK 25 via foojay-resolver if it's not already installed locally. Lombok is used in `core`.

## Commands

Gradle wrapper is at repo root (`./gradlew` / `gradlew.bat`).

- Run the emulator (desktop): `./gradlew desktop:run`
- Run with debug JVM: `./gradlew desktop:debug`
- Build desktop fat jar: `./gradlew desktop:dist` (output in `desktop/build/libs/`)
- Run all core tests: `./gradlew core:test`
- Run a single test class: `./gradlew core:test --tests net.lomibao.nes.components.CPU6502Test`
- Run a single test method: `./gradlew core:test --tests net.lomibao.nes.components.NestestTest.methodName`
- Debug/dev launchers (registered as Gradle tasks in `desktop/build.gradle`):
  - `./gradlew desktop:viewCHRTiles` — renders CHR ROM tiles from `nestest.nes` via `CHRTileViewerLauncher`
  - `./gradlew desktop:runNestest` — runs nestest.nes and renders the background (`NestestBackgroundLauncher`)

Desktop `run`-family tasks set `workingDir` to `assets/`, so asset paths are resolved relative to that directory. On macOS the tasks add `-XstartOnFirstThread` automatically.

Tests use JUnit 5 (`useJUnitPlatform`) and stream stdout/stderr (`showStandardStreams = true`); expect verbose output including a per-suite summary line printed by the `afterSuite` hook.

## Architecture

The NES is modeled as discrete hardware components communicating through a bus, mirroring the physical architecture. Top-level package: `net.lomibao.nes` (in `core/src`).

### CPU + CPU bus
- `components.CPU6502` emulates the Ricoh 2A03 (6502 core). Instruction decoding is **data-driven**: opcode metadata is loaded from `core/src/main/resources/opcodes.csv` (generated/maintained alongside `opcodes.json` and `opcode6502.py`), and instruction / addressing-mode implementations are dispatched via reflection. Handles IRQ, NMI, reset. All official + undocumented/illegal opcodes are implemented and validated against nestest (see `NestestTest`).
- `components.CPUBus` routes reads/writes by address range to registered `CPUBusComponent`s: internal RAM (`Ram`, 2KB mirrored), PPU registers (`PPU` at $2000–$3FFF), APU/IO, controllers, and the cartridge's PRG space. `FullAddressRam` exists for test harnesses that want a flat address space.

### PPU + PPU bus
- `components.PPU` implements the Picture Processing Unit: register file ($2000–$2007), cycle/scanline clock, NMI on VBlank, and background tile fetching/rendering. Rendering pipeline produces a pixel buffer consumed by the LibGDX renderer.
- `components.PPUBus` routes PPU-side reads/writes to `PPUBusComponent`s in `components.ppu.*`: `PatternMemory` (CHR, sourced from cartridge), `NameTableMemory` (with `MirroringMode` for horizontal/vertical/4-screen), `PaletteMemory`, plus `ColorPalette` for the NES master palette.
- `components.TileDecoder` converts CHR pattern planes into indexed pixel data; `debug.CHRTileViewer` / `debug.TileDebugger` visualize this.

### Cartridge / mappers
- `components.Cartridge` parses iNES headers (`rom.mapper.INESHeader`) and wires PRG-ROM + CHR-ROM to the buses through a `Mapper` implementation. Currently only `Mapper000` (NROM) is supported. New mappers implement the `Mapper` interface in `rom.mapper`.

### Rendering + launchers
- `render.PixelRenderer` draws an arbitrary RGBA pixel array via LibGDX. **Note** the historical bug fixed 2026-01-01: the PPU output is RGBA; reading it as ARGB flips alpha and causes invisible/garbled tiles — keep channel order consistent end-to-end.
- `NesEmulator` is the `ApplicationAdapter` wired into LibGDX; `DesktopLauncher` (desktop module) is the main entry (`mainClassName = net.lomibao.nes.DesktopLauncher`). Additional `desktop.*Launcher` classes drive the debug Gradle tasks above.

### Module layout
- `core/` — emulator logic + LibGDX-agnostic rendering helpers. Sources in `core/src`, tests in `core/test` (non-Maven layout; configured explicitly in `core/build.gradle`).
- `desktop/` — LWJGL3 backend, entry points, and debug launchers. Uses `../assets` as resources.
- `html/` — GWT/HTML backend scaffold (not actively developed).
- `core/src/main/resources/` — `opcodes.csv`, `nestest.nes`, palette files, `log4j2.xml`. The nestest ROM is checked in and is the primary integration test vector.

### Testing conventions
- Unit tests cover CPU opcodes (`OpcodesTest`, `CPU6502Test`) and PPU subsystems (`PPU*Test` per concern: clock, VBlank, NMI, tile fetch, rendering, nametable access, control registers).
- `NestestTest` runs the canonical nestest ROM headlessly and compares CPU trace output; full 8992/8992 line match is the current baseline — regressions here indicate a CPU/bus correctness break.

## Reference links

Primary external references for emulation correctness questions and architectural inspiration:

- **NESdev Wiki** — https://www.nesdev.org/wiki/Nesdev_Wiki — the canonical hardware reference (CPU, PPU, APU, mappers, test ROM index). Default first stop for any "how does the real hardware behave" question.
- **OneLoneCoder olcNES** — https://github.com/OneLoneCoder/olcNES — C++ reference impl that inspired this project. See [`docs/olcnes_review.md`](docs/olcnes_review.md) for a per-component review (what to copy, what to avoid).
- **bugzmanov NES ebook** — https://bugzmanov.github.io/nes_ebook/ — Rust-based step-by-step build with strong PPU/scrolling/joypad chapters. See [`docs/bugzmanov_nes_ebook_review.md`](docs/bugzmanov_nes_ebook_review.md) for a synthesis of takeaways for deloNES.
