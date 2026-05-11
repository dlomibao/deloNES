# olcNES Review — A Reference for deloNES

A study of OneLoneCoder's [olcNES](https://github.com/OneLoneCoder/olcNES) C++ reference
implementation, written to guide the Java/LibGDX port in this repo (**deloNES**). The goal
is to highlight what olcNES does well and is worth porting verbatim, call out the C++-isms
that should **not** survive the translation, and propose Java-idiomatic shapes (with
testability in mind) for each subsystem.

Source reviewed: `Part #7 - Mappers & Basic Sounds` (latest + most complete). Links below
point at that directory.

- `Bus.h/.cpp` → [Bus.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/Bus.h), [Bus.cpp](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/Bus.cpp)
- `olc6502.h/.cpp` → [olc6502.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/olc6502.h), [olc6502.cpp](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/olc6502.cpp)
- `olc2C02.h/.cpp` → [olc2C02.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/olc2C02.h), [olc2C02.cpp](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/olc2C02.cpp)
- `Cartridge.h/.cpp` → [Cartridge.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/Cartridge.h), [Cartridge.cpp](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/Cartridge.cpp)
- `Mapper.h/.cpp` → [Mapper.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/Mapper.h)
- `Mapper_000.h/.cpp` → [Mapper_000.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/Mapper_000.h)
- `olc2A03.h/.cpp` (APU) → [olc2A03.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/olc2A03.h)

---

## 1. Top-level architecture

### What olcNES does

The `Bus` class is the system container and the clock driver. It **owns** by value:

```cpp
olc6502 cpu;                         // by-value
olc2C02 ppu;                         // by-value
olc2A03 apu;                         // by-value
std::shared_ptr<Cartridge> cart;     // shared, because both buses see it
uint8_t cpuRam[2048];                // plain array, mirrored via addr & 0x07FF
uint8_t controller[2];               // user-facing latch input
```

Wiring happens in the `Bus` constructor (`cpu.ConnectBus(this)`) and
`insertCartridge()` (`ppu.ConnectCartridge(cartridge)`). That's it — no DI container,
no factory, no interfaces. The CPU calls back into the bus via a raw pointer for reads
and writes (`bus->cpuRead/Write`).

**Clocking** is a master-tick pump at PPU speed (`Bus::clock()`):

- Every call: `ppu.clock(); apu.clock();`
- Every 3rd call: `cpu.clock()` (CPU:PPU = 1:3), OR handle DMA if `dma_transfer`
- Check `ppu.nmi` → propagate to CPU
- Check `cart->GetMapper()->irqState()` → propagate IRQ
- Accumulate audio time; emit a sample when `dAudioTime >= dAudioTimePerSystemSample`

The outer app (olcPixelGameEngine loop) keeps calling `Bus::clock()` until
`ppu.frame_complete` goes true.

### deloNES today

- `net.lomibao.nes.NesEmulator` plays the olc "Bus-as-container" role but only loosely —
  it's an `ApplicationAdapter` that owns a `CPUBus` (`core/src/net/lomibao/nes/NesEmulator.java:23`).
- `CPUBus` is a generic **list-of-components** dispatcher rather than a hard-coded switch
  (`core/src/net/lomibao/nes/components/CPUBus.java:12`) — each component declares its
  address range via `CPUBusComponent.getCPUBusStartAddress/End`. Nice abstraction.
- There is currently **no master "system clock"**. `CPUBus.clock()` only ticks the CPU;
  the PPU's `clock()` is not driven from it yet. This is the biggest architectural gap.

### What to copy, what to change

| olcNES pattern | Port verbatim? | Java shape |
|---|---|---|
| Bus owns CPU/PPU/APU/Cartridge by value | **Yes** | Plain final fields on a `NesSystem` class; use constructor injection, not Lombok `@Builder` chains that leave half-built states |
| `cart` as `shared_ptr` because both buses see it | **Conceptually yes** | Just a single `Cartridge` reference held by both `CPUBus` and `PPUBus`; Java GC makes this trivial |
| PPU-rate master tick with `% 3` CPU divider | **Yes** | Introduce `NesSystem.tick()` that drives `ppu.clock()` then `cpu.clock()` on every 3rd tick. Put it **above** `CPUBus`, not inside it. |
| DMA timing in the bus | **Yes in spirit** | Port directly; the "wait for even cycle, then alternate read/write" state machine is correct and subtle |
| `cpu.ConnectBus(this)` from constructor body | **No** — smells of two-phase construction | Pass the bus in the CPU's constructor, or use a setter once at wiring time in a dedicated `assemble()` method |
| Audio sample produced inside `Bus::clock()` | **Split** | Bus returns a boolean "sample ready", audio thread pulls the latest sample from a ring buffer. Decouples clock from the LibGDX audio callback. |

### Deeper note on the CPU:PPU:APU timing

olc divides CPU by 3 and ticks the APU every master tick — but the real 2A03's APU runs on
the CPU clock and only half the frame sequencer ticks fire per CPU cycle. olc's APU is
explicitly marked **"VERY UNFINISHED"** at the top of
[olc2A03.h](https://github.com/OneLoneCoder/olcNES/blob/master/Part%20%237%20-%20Mappers%20%26%20Basic%20Sounds/olc2A03.h).
deloNES should model the APU clock domain as CPU-rate and keep the PPU on its own ticker
from the start, even if the first APU impl is stubbed.

---

## 2. CPU6502 (`olc6502`)

### Design highlights worth emulating

**The opcode lookup table.** Each entry is `{name, operateFn, addrModeFn, baseCycles}`:

```cpp
struct INSTRUCTION {
    std::string name;
    uint8_t (olc6502::*operate )(void) = nullptr;
    uint8_t (olc6502::*addrmode)(void) = nullptr;
    uint8_t cycles = 0;
};
std::vector<INSTRUCTION> lookup;  // 256 entries, indexed by opcode byte
```

No `switch (opcode)` decode tree. Dispatch is just
`lookup[opcode].addrmode(); lookup[opcode].operate()`. **deloNES already goes further**
than olc here: `core/src/net/lomibao/nes/components/CPU6502.java` builds the same table
from an external `opcodes.csv` plus reflection, so the table is data rather than code.
This is strictly better — keep it.

**Status register as a bitmask enum.** olc uses `enum FLAGS6502 { C = 1<<0, Z = 1<<1, ... }`
and `GetFlag/SetFlag` helpers. Simple, fast, testable in isolation. deloNES mirrors this.

**Two-phase instruction execution.** First the addressing mode fills `addr_abs`/`addr_rel`
and returns a "maybe +1 cycle" flag; then the operation consumes those and also returns a
"maybe +1 cycle" flag. The instruction pays a cycle only if **both** voted for it. This
matches how page-boundary crossings really add a cycle on NES. Port it.

**`fetch()` indirection.** Addressing modes don't immediately read the byte — they set
`addr_abs`. The operate function calls `fetch()` which does the read lazily (unless the
mode was `IMP`/accumulator). Clean separation.

**`clock_count` and `cycles` remaining.** Each `clock()` call just decrements `cycles` if
mid-instruction; when `cycles == 0` it decodes the next opcode. This gives accurate
per-cycle behaviour even though dispatch happens once per instruction. Port it.

### C++-isms to avoid

- **Member function pointers** (`uint8_t (olc6502::*operate)(void)`) — deloNES already
  solved this via reflection. Keep that.
- **`Bus *bus = nullptr;`** raw pointer — replace with a `CPUBus` field injected at
  construction; never nullable.
- **`disassemble()` lives inside the CPU class.** Disassembly is a debug concern; pull it
  into `net.lomibao.nes.debug.Disassembler` that reads from a `Bus` and operates on the
  opcode table. This is already partially done under `debug/`.
- **`XXX()` as the catch-all for illegal opcodes.** deloNES has the full illegal opcode
  set working, which is strictly better.
- **`#define LOGMODE`** conditional logging inside the header. In Java use SLF4J/Log4j2
  `log.isTraceEnabled()` guards (Lombok `@Log4j2` is already in use).

### Java-idiomatic translation notes

- Keep the CSV-driven table; expose the `Instruction` type as a `public record`
  (currently a static class at `CPU6502.java:202`) so tests can construct synthetic
  programs.
- `FLAGS6502` → a Java `enum StatusFlag { C(1), Z(2), ... }` with `int getMask()`; keep
  the raw `byte status` field too for fast bulk ops (PHP/PLP/BRK).
- Expose package-private hooks for tests: `setPC(int)`, `setStatus(byte)`, so nestest-style
  log comparison tests stay easy.
- Keep CPU ignorant of the bus type via a narrow interface (`BusReader`) so tests can
  swap in an in-memory fake without standing up a real `CPUBus`.

---

## 3. PPU2C02 (`olc2C02`)

### Design highlights worth emulating

**Loopy's `v`/`t`/`x`/`w` register model.** The PPU uses two internal 15-bit registers
(`vram_addr`, `tram_addr`), a 3-bit `fine_x`, and a 1-bit `address_latch`. olc uses a
union/bitfield over a `uint16_t` to give named access to `coarse_x`, `coarse_y`,
`nametable_x/y`, `fine_y`:

```cpp
union loopy_register {
    struct {
        uint16_t coarse_x : 5;
        uint16_t coarse_y : 5;
        uint16_t nametable_x : 1;
        uint16_t nametable_y : 1;
        uint16_t fine_y : 3;
        uint16_t unused : 1;
    };
    uint16_t reg = 0x0000;
};
```

**The four scroll helpers.** `IncrementScrollX`, `IncrementScrollY`, `TransferAddressX`,
`TransferAddressY` are each defined as `auto … = [&](){ … };` lambdas inside `clock()`.
They are exactly the operations from NesDev's
[PPU scrolling page](https://www.nesdev.org/wiki/PPU_scrolling). Port them verbatim as
private methods on `PPU`.

**The background pipeline state machine.** The 8-cycle fetch cycle is implemented as
`switch ((cycle - 1) % 8)` with cases 0 (load shifters), 2 (fetch NT byte),
4 (fetch AT byte), 6 (fetch pattern LSB), 7 (fetch pattern MSB, increment X). Clean,
testable. Combined with two 16-bit shifters (`bg_shifter_pattern_lo/hi`) that are shifted
every cycle, this is the **correct** way to model the pipeline and should be lifted
directly.

**PPUSTATUS $2002 read side-effect.** Reading `$2002` returns `(status & 0xE0) | (ppu_data_buffer & 0x1F)`
(the 5 low bits leak the stale bus) AND clears `vertical_blank` AND resets `address_latch`.
One of the most important emulation gotchas, and olc models it correctly.

**PPUDATA $2007 read buffer.** Reads are delayed by one fetch **except** for palette reads
(`>= 0x3F00`). Port exactly.

**Odd-frame cycle skip.** `if (scanline==0 && cycle==0 && odd_frame && rendering) cycle=1;`
— small but needed for sprite-0 tests like *Battletoads* title screen.

### C++-isms to avoid

- **`union` / bitfield structs for registers.** In Java, write explicit getter/setter code
  over an `int status`, `int control`, `int mask`, and an `int vramAddr` with static helper
  methods:

  ```java
  static int coarseX(int loopy) { return loopy & 0x1F; }
  static int setCoarseX(int loopy, int v) { return (loopy & ~0x1F) | (v & 0x1F); }
  ```

  Or promote to a `LoopyRegister` value object (`record`) with wither methods. Either is
  fine; the bitfield union is not.
- **Embedded `olcPixelGameEngine.h` Sprite and Pixel types.** The PPU owns
  `olc::Sprite* sprScreen`, `olc::Sprite* sprNameTable[2]`, `olc::Sprite* sprPatternTable[2]`
  plus `olc::Pixel palScreen[0x40]`. That mixes display/debug buffers into the emulation
  core. deloNES already separates this well via `render/PixelRenderer` and
  `components/ppu/ColorPalette` — preserve that boundary.
- **`uint8_t* pOAM = (uint8_t*)OAM;`** — casting a struct array to `uint8_t*` to let DMA
  write into it. In Java: use `byte[] oam = new byte[256]` and decode entries via static
  helper methods `oamY(oam, i)`, `oamTileId(oam, i)`, etc. No aliasing trick needed.
- **Lambdas declared inside `clock()`.** Fine in C++ for local state capture; in Java make
  them `private` methods to keep stack frames and names stable in profilers/stacks. This
  is already how deloNES `PPU.java:206 clock()` is structured.
- **Mirroring logic repeated twice** (once in `ppuRead`, once in `ppuWrite`) — factor to a
  `NameTableMemory.resolve(addr, mirror)`. deloNES already has `NameTableMemory` +
  `MirroringMode`, so route reads/writes through that.

### Java-idiomatic translation notes

- Split the PPU into collaborators:
  - `PPU` — cycle/scanline state machine, shifters, register side-effects
  - `PPUBus` (exists) — address decoding and mirroring for PPU space
  - `PaletteMemory` / `NameTableMemory` / `PatternMemory` (exist) — storage
  - `OamMemory` (new) — 256-byte + secondary 32-byte OAM
  - `SpriteEvaluator` (new) — sprite-0 hit detection, secondary OAM fill
- Use `ColorPalette` (exists) fed from `.pal` file, not inlined `olc::Pixel(...)` RGB
  literals (which olc has 64 of at the top of `olc2C02.cpp`, lines ~60–130).
- deloNES cross-refs:
  - `core/src/net/lomibao/nes/components/PPU.java:8` — PPU class
  - `core/src/net/lomibao/nes/components/PPU.java:101` — `cpuBusWrite` (register write)
  - `core/src/net/lomibao/nes/components/PPU.java:156` — `cpuBusRead`
  - `core/src/net/lomibao/nes/components/PPU.java:206` — `clock`

---

## 4. CPU Bus (main memory map)

### What olc does

`Bus::cpuWrite` uses a straight `if/else if` chain over address ranges:

```cpp
if (cart->cpuWrite(addr, data)) {}                             // cartridge veto
else if (addr <= 0x1FFF)  cpuRam[addr & 0x07FF] = data;        // 2KB mirrored x4
else if (addr <= 0x3FFF)  ppu.cpuWrite(addr & 0x0007, data);   // PPU regs mirrored
else if (addr <= 0x4013)  apu.cpuWrite(addr, data);
else if (addr == 0x4014)  { dma_page = data; dma_transfer = true; }
else if (addr == 0x4016)  controller_state[addr & 1] = controller[addr & 1];
```

The cartridge gets first dibs **on every write**, which supports MMC3-style mapper
register writes that overlap ranges.

### Smells & deloNES comparison

- **Cartridge-first "veto"** is actually a nice pattern — preserve it.
- **Hard-coded address range chain** is cheap and fast but hard to extend. deloNES already
  has a cleaner abstraction in `CPUBus.java:12`: each component declares its address range
  (`CPUBusComponent.getCPUBusStartAddress/End`), and `CPUBus.read/write` iterates the list.
  Keep it — but make sure cartridge takes priority (it currently does via being a
  `CPUBusComponent`).
- **DMA page register lives in `Bus`**. deloNES should either own DMA state in a dedicated
  `DmaController` collaborator or colocate it on the `NesSystem` driver — not on the
  generic `CPUBus`, because it's about clock coordination, not memory decode.

### Tests worth writing (Java-only, hard in C++)

- RAM mirroring: write at `0x0000` → read at `0x0800`, `0x1000`, `0x1800` returns same.
- PPU reg mirroring: write `0x2000` → assert `cpuBusWrite(0x3FF8)` hits PPU control with
  `addr & 0x7 == 0`.
- Open bus: reading `0x4018..0x401F` (unimplemented) should return 0 (or the last bus
  value — olc just returns 0).

---

## 5. PPU Bus

### What olc does

There is **no separate `PPUBus` class** in olc. `olc2C02::ppuRead/ppuWrite` directly
handle address decode:

```cpp
addr &= 0x3FFF;                                // mask to 14 bits
if (cart->ppuRead(addr, data)) { /* mapper consumed */ }
else if (addr <= 0x1FFF)       { data = tblPattern[(addr>>12)&1][addr & 0x0FFF]; }
else if (addr <= 0x3EFF)       { /* nametable w/ mirroring by cart->Mirror() */ }
else if (addr <= 0x3FFF)       { /* palette, with $3F10/$3F14/$3F18/$3F1C aliasing */ }
```

The fact that PPU itself routes to the cartridge `ppuRead` (for CHR-ROM) and to internal
nametable/palette arrays is fine for a monolith, but awkward for testing.

### Smells & Java shape

- **PPU knows about cartridge and nametables directly.** deloNES already factors this out
  (`PPUBus.java:15` with `connectCartridge` at line 23, `connectPPU` at line 30). Keep
  that. PPU should call `ppuBus.read(addr)` and let the bus dispatch to `Cartridge`,
  `NameTableMemory`, `PaletteMemory`. That makes palette aliasing
  (`$3F10 ≡ $3F00`, etc.) a `PaletteMemory` unit test, not a PPU test.
- **Mirroring mode comes from cart** (`cart->Mirror()`). In Java expose a
  `MirroringMode mirroringMode()` on `Cartridge` and an `applyMirroring(int addr, MirroringMode)`
  helper on `NameTableMemory`. deloNES already has `MirroringMode` at
  `core/src/net/lomibao/nes/components/ppu/MirroringMode.java`.
- **Palette aliasing** — the four background colour mirrors (`$3F10` mirrors `$3F00`,
  etc.) must live in `PaletteMemory.read/write`, not the PPU state machine.

---

## 6. Cartridge + iNES header

### What olc does

Local-to-constructor header struct via raw fstream `read((char*)&header, sizeof)`:

```cpp
struct sHeader {
    char name[4]; uint8_t prg_rom_chunks; uint8_t chr_rom_chunks;
    uint8_t mapper1; uint8_t mapper2; uint8_t prg_ram_size;
    uint8_t tv_system1; uint8_t tv_system2; char unused[5];
} header;
ifs.read((char*)&header, sizeof(sHeader));
```

Mapper ID = `((mapper2 >> 4) << 4) | (mapper1 >> 4)`. Trainer detection via
`header.mapper1 & 0x04` → `seekg(512, cur)`. iNES v2 is detected via
`(header.mapper2 & 0x0C) == 0x08`.

Cartridge owns `std::vector<uint8_t> vPRGMemory`, `vCHRMemory` and a
`std::shared_ptr<Mapper>` that was picked via a `switch (nMapperID)` over
`make_shared<Mapper_NNN>(...)`.

CHR-RAM vs CHR-ROM: if `nCHRBanks == 0`, allocate 8 KB RAM; writes through
`Mapper_000::ppuMapWrite` are only accepted when `nCHRBanks == 0`. Nice small detail.

### Smells

- **Packed-struct trick is UB-adjacent** and nonportable (alignment, endianness). In Java,
  read the 16 header bytes into a `byte[]`, parse fields explicitly:

  ```java
  public record INesHeader(int prgBanks, int chrBanks, int mapperId,
                           MirroringMode mirroring, boolean hasTrainer,
                           boolean isNes2) {
      public static INesHeader parse(byte[] h) { … }
  }
  ```

- **Mapper factory as `switch` in the cartridge ctor.** Use a
  `Map<Integer, IntFunction<Mapper>>` or a `MapperRegistry` so new mappers plug in without
  touching `Cartridge`. deloNES currently only has `Mapper000`; introduce the registry
  now, before more arrive.
- **File I/O in the cartridge ctor** — couples to filesystem. deloNES already takes an
  `InputStream` (`Cartridge.java:38`), which is better. Keep it.
- **iNES v2 path exists but is mostly dead code** in olc. Decide early: either parse v2
  properly (extended mapper IDs, submappers, PRG-RAM size nybble) or explicitly reject v2
  ROMs with a clear error.

### deloNES cross-refs

- `core/src/net/lomibao/nes/components/Cartridge.java:18` — Cartridge class
- `core/src/net/lomibao/nes/rom/INESHeader` (referenced at `Cartridge.java:26`)

---

## 7. Mapper interface

### What olc does

```cpp
virtual bool cpuMapRead (uint16_t addr, uint32_t &mapped_addr, uint8_t &data) = 0;
virtual bool cpuMapWrite(uint16_t addr, uint32_t &mapped_addr, uint8_t data = 0) = 0;
virtual bool ppuMapRead (uint16_t addr, uint32_t &mapped_addr) = 0;
virtual bool ppuMapWrite(uint16_t addr, uint32_t &mapped_addr) = 0;
virtual MIRROR mirror();                      // default HARDWARE
virtual bool irqState(); virtual void irqClear();
virtual void scanline();                      // MMC3 hook
```

Returning `bool` + out-parameters is the C++ "did you handle it?" idiom. The magic value
`mapped_addr == 0xFFFFFFFF` is used to mean "mapper produced data directly, don't index
into the PRG/CHR buffer" (used for cartridge RAM reads).

### Smells

- **`bool` + two reference out-parameters + sentinel 0xFFFFFFFF** is the single ugliest
  pattern in olcNES. In Java, return a proper result type:

  ```java
  sealed interface MapResult {
      record Hit(int bankOffset) implements MapResult {}   // index into PRG/CHR buffer
      record Direct(byte data) implements MapResult {}     // mapper-supplied data (RAM)
      record Miss() implements MapResult {}                // not handled
  }
  ```

  Or an `Optional<Integer>` if you drop the Direct case, plus a separate `chrRamRead` path.
- **`virtual void scanline()`** on the base class is fine in concept (MMC3 scanline counter
  hook), but olc calls it from the PPU via `mapper->scanline()` — tight coupling. In Java,
  expose a `Mapper.onScanline()` hook invoked from the PPU's scanline-start code, and
  document that only cycle-counting mappers override it.
- **`irqState()` / `irqClear()`** pair is classic mutable-side-effect; nicer as
  `consumeIrq()` returning a boolean.

### Testability wins

A `MapResult`-returning mapper is trivially unit-testable with no Cartridge or Bus needed.
Every existing mapper test in olcNES requires building a fake `std::vector<uint8_t>`
backing array — Java doesn't.

---

## 8. APU (`olc2A03`)

olc's APU is explicitly marked *"VERY UNFINISHED"* and is the weakest part of the repo.

### What is present

- Two pulse channels (1 + 2) with: `sequencer`, `envelope`, `lengthcounter`, `sweeper`,
  and an analytical `oscpulse` that uses truncated Fourier series of duty-cycle pulse waves.
- Noise channel — envelope + length counter, but the LFSR output is approximated with a
  fixed sequence (`noise_seq.sequence = 0xDBDB`).
- **No triangle channel** (field exists, hook absent).
- **No DMC channel**.

### Interesting ideas

- **Analytical band-limited pulse synthesis**: `oscpulse::sample(t)` computes
  `∑ -sin(n·ω·t)/n - -sin(n·ω·t - p·n)/n` over 20 harmonics. That's a clever way to avoid
  aliasing without a proper DSP filter. Worth keeping as a reference "simple high-quality"
  mode, but the reference NES mixer is per-sample linear combination with specific
  non-linear mixing weights — most accuracy-focused emus do bit-accurate sample generation.

### C++-isms and smells

- **Inline lambdas captured inside the sequencer struct**
  (`uint8_t clock(bool bEnable, std::function<void(uint32_t&)> funcManip)`) — a tight
  coupling hack. In Java, make the sequence-manipulation strategy an enum value or a
  `UnaryOperator<Integer>`.
- **Visual debug fields** (`pulse1_visual`, `pulse2_visual`, `noise_visual`,
  `triangle_visual`) mixed into the APU for the olc screen overlay. Keep these out of the
  Java APU.
- **`double` time-based output**. NES audio is fundamentally sample-based at a fixed rate
  (CPU-clock-derived). Model the APU as "one sample per APU tick, push into a ring buffer,
  LibGDX audio thread consumes at 44.1 kHz with simple resampler."

### Java shape

```java
interface AudioChannel {
    void writeRegister(int addrLocal, int value);   // $4000..$4003 etc.
    int clock();                                     // returns 0..15 (4-bit DAC)
    void clockQuarterFrame();
    void clockHalfFrame();
    boolean lengthCounterActive();
    void setEnabled(boolean on);
}
```

Then `Pulse1`, `Pulse2`, `Triangle`, `Noise`, `Dmc` each implement it. The `APU` class
owns all five and the frame sequencer / mixer. deloNES's `components/APU.java` is already
a stub — a good place to start from the interface.

---

## 9. Controllers

### What olc does

Tiny. `Bus` has `uint8_t controller[2]` (the current button state the UI writes) and
`uint8_t controller_state[2]` (the 8-bit latched shift register). Writing anywhere in
`$4016..$4017` copies `controller` → `controller_state`. Each read of `$4016`/`$4017`
returns the MSB of `controller_state` and shifts left.

### Smells

- Write to `$4016` **should latch on bit 0 rising edge, not on any write**. olc takes a
  shortcut — both writes latch. Good enough for most games, wrong for a couple.
- Two separate "current" vs "latched" arrays are fine; Java shape:
  ```java
  class StandardController {
      int live;          // 8 bits, updated by input layer
      int shift;         // latched snapshot
      void write(int v)  { if ((v & 1) != 0) shift = live; }
      int  read()        { int bit = (shift >> 7) & 1; shift = (shift << 1) & 0xFF; return bit; }
  }
  ```
  deloNES `components/Controller.java` is a stub — port this shape.

---

## 10. Component interactions (the hard parts)

### 10.1 CPU ↔ Bus

- CPU stays ignorant of the bus topology; just `read(addr)`/`write(addr, value)`.
- **RAM mirroring**: olc does `addr & 0x07FF` — trivially correct. Put this inside
  `Ram.cpuBusRead/Write`, not in the bus.
- **Cycle accounting**: olc counts on *instruction* granularity via `cycles--`. Good enough
  for NES; don't try to go sub-instruction (subcycle) unless chasing *exact*
  read-modify-write timing.

### 10.2 CPU ↔ PPU registers ($2000–$3FFF)

The eight registers mirror every 8 bytes across 8 KB. olc applies `addr & 0x7` in the bus,
so the PPU only ever sees `0..7`. deloNES mirrors this (`PPU.java:101 cpuBusWrite` switches
on `address & 0x7`). Good.

**Side-effects are the emulation hotspots** — all well-modelled in olc, worth replicating:

| Register | Write side-effect | Read side-effect |
|---|---|---|
| $2000 PPUCTRL | copies `nametable_x/y` bits to `tram_addr` | n/a |
| $2002 PPUSTATUS | n/a | clears vblank; resets `address_latch`; returns `status & 0xE0 \| buffer & 0x1F` |
| $2005 PPUSCROLL | writes 1 & 2 toggle latch; update coarse/fine X then Y in `tram_addr` | n/a |
| $2006 PPUADDR | writes 1 & 2 toggle latch; second write copies `tram_addr → vram_addr` | n/a |
| $2007 PPUDATA | writes at `vram_addr`, then `vram_addr += 1 or 32` | returns **previous** buffer, then refills; palette reads skip the buffer |

### 10.3 PPU ↔ PPUBus

- Pattern `$0000-$1FFF` → cartridge CHR (via mapper `ppuMapRead`), falls back to internal
  `tblPattern` (which is almost never used — most cartridges always hit).
- Nametables `$2000-$3EFF` → cart-selected mirroring mode picks which of 2 KB of internal
  VRAM (`tblName[2][1024]`) you hit. olc inlines the case analysis twice. Factor to
  `NameTableMemory`.
- Palette `$3F00-$3FFF` → 32-byte `tblPalette`, with `$3F10/$3F14/$3F18/$3F1C` aliased to
  `$3F00/$3F04/$3F08/$3F0C`, AND bit 0 of `$3F00..$3F1F` forced to monochrome under
  grayscale mask. olc handles the aliasing; handle it in `PaletteMemory`.

### 10.4 NMI signalling

olc:
```cpp
if (ppu.nmi) { ppu.nmi = false; cpu.nmi(); }
```
This is polled every master tick from the Bus. Simple and correct. **Don't** put this
inside either the CPU or the PPU — the coupling belongs at the system level.

Port: `NesSystem.tick()` → after `ppu.clock()` check `ppu.consumeNmi()` (a boolean
transition). For IRQ from mapper: `mapper.consumeIrq()`.

### 10.5 Clock coordination & frame pacing

- Master tick rate = PPU clock ≈ 5.369 MHz (NTSC).
- CPU ticks on every 3rd master tick.
- APU *actually* ticks at CPU rate (olc cheats by clocking it every master tick — fix this
  when porting).
- A frame ends when `ppu.frame_complete` is set (end of scanline 261). The outer loop
  (olcPixelGameEngine's `OnUserUpdate`) drains `Bus::clock()` until that happens, then
  renders the sprite.

**Java pacing**: a `NesSystem.runFrame()` method that loops `tick()` until the PPU signals
frame complete, then returns. Keep LibGDX/`render()` off the hot path by writing pixels
into an off-heap `Pixmap` / `IntBuffer` only at frame boundaries.

### 10.6 DMA

Quirks to preserve:
1. A write to `$4014` sets `dma_transfer=true`. CPU is suspended.
2. DMA must start on an **even** master tick (1 dummy cycle if odd).
3. 256 iterations of `cpuRead(page<<8 | addr)` on even, `ppu.pOAM[addr++] = data` on odd.
4. When `addr` wraps to 0, DMA ends.

→ Port as a `DmaController` state machine invoked from `NesSystem.tick()` before
`cpu.clock()`. Testable without standing up a CPU.

---

## 11. Testing gaps to close in deloNES

Things olc is structurally unable to test cleanly but Java can, one-per-class-ish:

### CPU
- **Every addressing mode in isolation** — construct a tiny program + synthetic `BusReader`,
  assert `fetched` / `addr_abs`.
- **Page-cross cycle penalty** for every instruction that pays it.
- **BRK vs IRQ vs NMI vector handling** (push order, B/U bit handling in pushed status).
- **nestest.log line-by-line diff** (you already pass 8992/8992 — formalise as a snapshot
  test).

### PPU
- **PPUSTATUS $2002 read** clears vblank AND resets `address_latch`.
- **$2005 → $2006 interaction**: writes to $2005 then $2006 leave scroll in a specific
  shared state (the famous "loopy t" scramble). Reproduce from NesDev table and test.
- **PPUDATA read buffer**: first read at $2007 after VRAM address set returns stale
  buffer, next read returns the real byte. Palette read bypasses.
- **Increment mode**: after read/write, `vram_addr += 1` or `+= 32` based on PPUCTRL bit 2.
- **Palette aliasing**: write `$3F10`, read `$3F00` returns same.
- **Odd-frame cycle skip**: verify at the scanline-0/cycle-0 transition with rendering on.
- **Sprite-0 hit timing** (once sprites land): tests from
  [nesdev `sprite_hit_tests_2005.10.05`](https://www.nesdev.org/wiki/Emulator_tests).

### Bus
- **RAM mirror bands** (`0x0000`/`0x0800`/`0x1000`/`0x1800` alias).
- **PPU register mirror** every 8 bytes across `$2000-$3FFF`.
- **Unmapped reads** (`$4018..$401F`) return a defined value (0 or open-bus last).

### Cartridge / Mapper
- **iNES trainer byte** — header with trainer bit set should seek +512 before PRG.
- **Mapper 000 16 KB vs 32 KB**: `$C000-$FFFF` mirrors `$8000-$BFFF` only when 1 PRG bank.
- **CHR-RAM writability**: on a 0-CHR-bank cart, writes to `$0000-$1FFF` through the PPU
  bus are accepted; on a CHR-ROM cart, they are silently ignored.
- **Nametable mirroring modes**: vertical vs horizontal map the same address to different
  NT banks — pure unit test on `NameTableMemory`.

### APU (once implemented)
- Frame sequencer 4-step vs 5-step timing.
- Length counter load table (the odd [10, 254, 20, 2, ...] list olc hard-codes).
- Sweep unit mute conditions (`target < 8 || target > 0x7FF`).
- DMC DMA stealing a CPU cycle.

---

## 12. Open questions / where olc cuts corners

1. **Mid-scanline register writes during rendering.** olc only reads `control`/`mask` at
   well-defined points. Games that change the mask mid-scanline for split-screen effects
   may glitch. For deloNES: decide whether to be register-accurate during rendering — most
   open-source emulators aren't, and test ROMs that rely on this are rare.
2. **Accurate sprite overflow bug.** The real 2C02 has a hardware bug in the overflow
   detection that increments the OAM index incorrectly when the counter hits 9+. olc's
   sprite overflow flag is a straight count. A few test ROMs check the buggy behaviour.
3. **PPU open-bus decay.** The low 5 bits of `$2002` reads come from a decaying bus latch.
   olc approximates as "current `ppu_data_buffer & 0x1F`". Most test ROMs accept this.
4. **Exact DMC timing.** olc has no DMC. Some games (e.g. *Battletoads*) abuse DMC DMA for
   IRQ timing. Decide up-front whether deloNES will model this.
5. **PAL vs NTSC.** olc is NTSC-only (CPU:PPU 1:3, 262 scanlines). PAL is 1:3.2 and 312
   scanlines with a different palette. A `Region` enum threaded through `NesSystem` saves
   a refactor later.
6. **iNES v2.** olc detects it but doesn't fully parse. Pick one.
7. **Reset vs power-on state.** olc zeros everything on reset. Real 2C02 preserves some
   state across reset (e.g. VRAM addr latch). Usually not observable.
8. **`olcPGEX_Sound.h` audio backend.** olc couples the APU output to a specific sound
   extension. deloNES should expose APU as "produces samples into a `float[]`" and let a
   `SoundController` (LibGDX `AudioDevice` / Web Audio in GWT) pull.

---

## 13. Cheat-sheet: what to copy verbatim vs reshape

| olcNES thing | Verdict |
|---|---|
| CPU opcode lookup table | **Better already** (CSV + reflection) |
| 6502 status flags as bitmask | Copy (already present) |
| Two-phase addressing-then-op with +1 cycle vote | Copy exactly |
| `Bus::clock()` master tick with % 3 CPU divider | Copy — into `NesSystem`, not `CPUBus` |
| DMA state machine | Copy exactly — into a `DmaController` |
| Loopy v/t/x/w registers + IncrementScrollX/Y + TransferAddressX/Y | Copy exactly — as methods, not lambdas |
| Background 8-cycle `switch((cycle-1)%8)` pipeline | Copy exactly |
| PPUSTATUS read side-effects and PPUDATA buffer | Copy exactly |
| Odd-frame cycle skip | Copy |
| Palette aliasing | Into `PaletteMemory` |
| Nametable mirroring | Into `NameTableMemory` |
| `bool cpuMapRead(..., uint32_t&, uint8_t&)` | **Replace** with sealed `MapResult` |
| `union` bitfield register structs | **Replace** with bit helpers or a `record` |
| Raw `olc::Pixel palScreen[0x40]` literals | **Replace** with `.pal` loader (done) |
| APU `oscpulse` analytical Fourier | Keep as an optional mode; do bit-accurate for reference |
| `olcPixelGameEngine.h` tangled into PPU | **Remove** — deloNES already separates render |
| `disassemble()` inside CPU | Move to `debug/Disassembler` (partially done) |
| `std::shared_ptr<Mapper>` | Just a reference — Java does the rest |
| iNES header as packed struct + `fread` | Parse byte-by-byte into a `record INesHeader` |

---

## 14. Quick component ownership diagram (target Java shape)

```
NesSystem
├── CPUBus                        (address decode / mirroring)
│   ├── Ram (2 KB)
│   ├── Cartridge  ──── holds ──► Mapper (interface)
│   ├── PPU        ──── via $2000-$3FFF
│   ├── APU        ──── via $4000-$4017 (minus $4014, $4016)
│   └── Controllers
├── PPUBus                        (CHR, nametable, palette decode)
│   ├── Cartridge  (CHR-ROM / CHR-RAM via Mapper.ppuMapRead/Write)
│   ├── NameTableMemory (2 KB + mirroring)
│   └── PaletteMemory (32 B + aliasing)
├── CPU6502       (→ CPUBus)
├── PPU           (→ PPUBus, emits NMI)
├── APU           (→ CPUBus read for DMC sample fetches)
├── DmaController (on $4014 write, steals CPU cycles, reads CPUBus, writes OAM)
└── Clock / tick driver
```

The arrow direction matters: `PPU` knows `PPUBus` but **not** Cartridge; Cartridge is a
service both buses consume. This is the single biggest architectural improvement over
olc's monolith.

---

## 15. Things I could not locate or verify

- **No `Part #6 - Mappers`** directory exists upstream — olc went straight from Part #5
  (PPU Foregrounds) to Part #7 (Mappers & Basic Sounds). So Part #7 is the source of
  truth for mappers; this review uses it.
- **Mapper_001 through Mapper_004 / Mapper_066** exist as headers but were not reviewed in
  detail for this doc. When deloNES needs them, do a focused re-read — especially MMC3
  (Mapper_004) for its scanline-counting IRQ, which drives the `mapper->scanline()` hook
  on the base class.
- The `olcPixelGameEngine.h` and `olcPGEX_Sound.h` headers are third-party and specific to
  the PGE framework; they can be ignored entirely for the Java port — LibGDX equivalents
  (`Pixmap`, `Texture`, `AudioDevice`) are in no way the same, and the right boundary is
  "PPU produces an `int[VISIBLE_WIDTH * VISIBLE_HEIGHT]` RGBA frame buffer, renderer
  uploads it."
- olc's APU `olcNes_Sounds1.cpp` main file was not reviewed; the relevant APU logic is all
  in `olc2A03.h/.cpp`.

---

*End of review.*
