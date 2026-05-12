# deloNES Copilot Instructions

## Project Overview
deloNES is a Java-based NES emulator using the LibGDX framework. It is a multi-module Gradle project (`core`, `desktop`, `html`).
- **Core Logic:** `core/src/net/lomibao/nes`
- **Language Level:** Java 1.8 compatibility.
- **Frameworks:** LibGDX, Lombok, Log4j2.

## Architecture & patterns

### Component Model
- **Bus-Centric:** Hardware components (`CPU6502`, `PPU`, `Cartridge`, `Ram`) communicate strictly via `CPUBus` or `PPUBus`.
- **Isolation:** Components should not have direct references to each other; use the bus.
- **Example:** `CPU6502` reads memory via `cpuBus.read(address)`.

### CPU Implementation (CRITICAL)
- **Data-Driven Opcodes:** The CPU does **not** implementation a giant switch statement for opcodes.
- **CSV Loading:** Instruction metadata (Name, Opcode, Bytes, Cycles, Addressing Mode) is loaded from `core/src/main/resources/opcodes/opcodes.csv`.
- **Reflection:** Instruction behavior is often mapped dynamically. When modifying CPU logic, check how the CSV maps to methods.

### Graphics (LibGDX)
- **PixelRenderer:** Helper class (`net.lomibao.nes.render.PixelRenderer`) manages a `Pixmap` backed `Texture` for raw pixel manipulation.
- **Rendering Loop:** The emulator core runs its clock cycles (CPU/PPU) and then updates the `PixelRenderer` buffer to be drawn by LibGDX.

### Coding Conventions
- **Lombok:** Use `@Data`, `@Builder`, `@AllArgsConstructor`, and `@NoArgsConstructor` to reduce boilerplate.
- **Logging:** Use `@Log4j2` for logging.
- **Hex literals:** format addresses and byte values in hex (e.g., `0x8000`, `0xFF`) for readability.
- **Bitwise Operations:** Common for flag manipulation (e.g., setting CPU status flags).

## Development & Testing

### Build System
- **Project Root:** All gradle commands must be run from the `deloNES/` subdirectory. The workspace root contains the project folder, but the build script is inside `deloNES`.
- **Gradle Invocation:** Use full path with `& ".\gradlew.bat"` in PowerShell or `./gradlew` in bash. DO NOT use `.\gradlew` without the `&` operator in PowerShell, as it will fail.
- **Build:** `& ".\gradlew.bat" build` (PowerShell) or `./gradlew build` (bash)
- **Run Desktop:** `& ".\gradlew.bat" desktop:run` (PowerShell) or `./gradlew desktop:run` (bash)

### Testing Strategy
- **Accuracy Tests:** The project relies heavily on ROM-based verification (e.g., `nestest.nes`).
- **Log Comparison:** Validation often involves running a test ROM (headless) and comparing the execution log against a known good reference (e.g., `nestest.log`).
- **Unit Tests:** `test_tyx_trace.java` and standard JUnit tests in `core/test`.

### Common Files
- `CPU6502.java`: Main processor logic.
- `CPUBus.java`: System interconnect.
- `PixelRenderer.java`: Video output handling.
- `opcodes.csv`: Instruction definitions.
