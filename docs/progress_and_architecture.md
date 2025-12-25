# deloNES Project Documentation

## Overview
deloNES is a NES (Nintendo Entertainment System) emulator built using Java and the LibGDX framework. The goal of the project is to provide a clean, modular, and cross-platform emulator.

## Architecture

The project follows a component-based architecture where each hardware piece of the NES is represented by a class. These components communicate through a central bus system.

### Core Components

- **[CPU6502](file:///c:/Users/derek/projects/delones/deloNES/core/src/net/lomibao/nes/components/CPU6502.java)**:
    - Emulates the Ricoh 2A03 processor (based on MOS 6502).
    - Uses a data-driven approach by loading opcodes from a CSV file (`opcodes.csv`).
    - Implements instructions and addressing modes using reflection for modularity.
    - Handles interrupts (IRQ, NMI, Reset).

- **[CPUBus](file:///c:/Users/derek/projects/delones/deloNES/core/src/net/lomibao/nes/components/CPUBus.java)**:
    - Acts as the central communication hub for the CPU.
    - Routes read/write requests to the appropriate components (RAM, PPU, APU, Cartridge) based on the address range.

- **[PPU](file:///c:/Users/derek/projects/delones/deloNES/core/src/net/lomibao/nes/components/PPU.java)**:
    - Emulates the Picture Processing Unit.
    - Currently in the early stages (register mapping implemented, clock loop skeletal).

- **[Cartridge](file:///c:/Users/derek/projects/delones/deloNES/core/src/net/lomibao/nes/components/Cartridge.java)**:
    - Handles loading of iNES ROM files.
    - Supports Mapper 000 (NROM).
    - Manages PRG-ROM (Program) and CHR-ROM (Character/Graphics) data.

- **Ram / FullAddressRam**:
    - Provides memory storage for the system. `Ram` handles the standard 2KB internal RAM, while `FullAddressRam` is used for testing purposes.

### Design Principles

- **Separation of Concerns**: Each component is isolated and only interacts with others through the Bus.
- **Modularity**: New Mappers or hardware components can be added by implementing the relevant interfaces.
- **Cross-Platform**: Leverages LibGDX to target Desktop and potentially Web (HTML/GWT).

## Current Progress

- [x] CPU 6502 Core Implementation (Opcodes, Addressing Modes, Interrupts).
- [x] Bus Architecture and Component Registration.
- [x] iNES ROM Loading and Header Parsing.
- [x] Basic RAM Implementation.
- [/] PPU Implementation (Skeleton in place, rendering logic pending).
- [ ] APU Implementation (Future milestone).
- [/] LibGDX UI and Rendering (Basic setup done, emulation loop integration pending).

## Progress Roadmap

1. **Complete PPU Core**: Implement background and sprite rendering.
2. **Emulation Loop**: Integrate the CPU/PPU clock cycles into the LibGDX `render` loop.
3. **Input Handling**: Map keyboard/controller inputs to the NES joypad registers.
4. **Enhanced Mapping**: Add support for more mappers (MMC1, MMC3, etc.).
5. **Audio (APU)**: Implement sound channels.
