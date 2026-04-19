# bugzmanov "Writing NES Emulator in Rust" — A Reference for deloNES

A study of Rafael Bagmanov's online book
[**Writing NES Emulator in Rust**](https://bugzmanov.github.io/nes_ebook/) ("bugzmanov"
hereafter), written to guide the Java/LibGDX port in this repo (**deloNES**). The aim is
to capture what bugzmanov does **differently or better** than OneLoneCoder's olcNES
(already reviewed in `docs/olcnes_review.md`) and to give a concrete, prioritised list of
things to port.

> **Companion doc:** `docs/olcnes_review.md` already covers Bus topology, the loopy
> v/t/x/w background pipeline, the `% 3` master clock, DMA timing, mapper
> interface shape, and the APU. This doc deliberately does **not** repeat those points —
> bugzmanov is structurally simpler than olc on every one of them. Instead this doc
> focuses on what bugzmanov teaches well that olc does not.

## Chapters covered (and what's actually in them)

bugzmanov is organised into 9 numbered chapters with sub-sections. The full TOC,
extracted from the print build:

| Ch. | Title | URL | Notes |
|---|---|---|---|
| 1 | Introduction (Why NES, Why Rust, Prerequisites) | [chapter_1](https://bugzmanov.github.io/nes_ebook/chapter_1.html) | Pedagogy + setup |
| 2 | NES Platform Main Components | [chapter_2](https://bugzmanov.github.io/nes_ebook/chapter_2.html) | Architecture overview |
| 3 | Emulating CPU | [chapter_3](https://bugzmanov.github.io/nes_ebook/chapter_3.html) | Intro |
| 3.1 | Let's get started (LDA / TAX / fetch-decode-execute) | [chapter_3_1](https://bugzmanov.github.io/nes_ebook/chapter_3_1.html) | First opcodes |
| 3.2 | Memory addressing modes | [chapter_3_2](https://bugzmanov.github.io/nes_ebook/chapter_3_2.html) | `get_operand_address` table |
| 3.3 | Implementing the rest of CPU instructions | [chapter_3_3](https://bugzmanov.github.io/nes_ebook/chapter_3_3.html) | ADC/SBC, PHP/PLP/RTI, branches |
| 3.4 | Running our first game (Snake demo) | [chapter_3_4](https://bugzmanov.github.io/nes_ebook/chapter_3_4.html) | `run_with_callback`, SDL2 |
| 4 | Emulating BUS | [chapter_4](https://bugzmanov.github.io/nes_ebook/chapter_4.html) | RAM mirroring, bus abstraction |
| 5 | Cartridges (iNES) | [chapter_5](https://bugzmanov.github.io/nes_ebook/chapter_5.html) | Header parsing, mapper-0, mirroring |
| 5.1 | Running our first test ROM (nestest) | [chapter_5_1](https://bugzmanov.github.io/nes_ebook/chapter_5_1.html) | Trace, log diff, illegal opcodes |
| 6 | Emulating PPU (intro / 4-phase plan) | [chapter_6](https://bugzmanov.github.io/nes_ebook/chapter_6.html) | Roadmap |
| 6.1 | PPU sketch / memory map | [chapter_6_1](https://bugzmanov.github.io/nes_ebook/chapter_6_1.html) | CHR / VRAM / OAM / palette |
| 6.2 | PPUADDR + PPUDATA + Mirroring + connecting to bus | [chapter_6_2](https://bugzmanov.github.io/nes_ebook/chapter_6_2.html) | `AddrRegister`, ControlRegister bitflags |
| 6.3 | Emulating Interrupts (NMI, clock cycles, BRK/IRQ) | [chapter_6_3](https://bugzmanov.github.io/nes_ebook/chapter_6_3.html) | catch-up `cycles*3`, `tick()` |
| 6.4 | Rendering CHR ROM Tiles + Palette | [chapter_6_4](https://bugzmanov.github.io/nes_ebook/chapter_6_4.html) | `Frame`, `show_tile()` |
| 6.5 | Rendering Background + Working with Colors + Sprites + Joypads | [chapter_6_5](https://bugzmanov.github.io/nes_ebook/chapter_6_5.html) | The big rendering chapter |
| 7 | PPU Scrolling (sprite-0 hit + viewport-shift renderer) | [chapter_7](https://bugzmanov.github.io/nes_ebook/chapter_7.html) | The famous scroll explanation |
| 8 | Joypads | [chapter_8](https://bugzmanov.github.io/nes_ebook/chapter_8.html) | Strobe + shift-register |
| 9 | (Audio Processing Unit) | [chapter_9](https://bugzmanov.github.io/nes_ebook/chapter_9.html) | **Empty / placeholder.** Book ends after the Afterword in Chapter 7. |

> **Gap to flag honestly.** The numbered Chapter 9 page exists but contains no body —
> only navigation chrome. The Afterword (rendered at the end of the print build) closes
> the book after the Scrolling chapter. **bugzmanov does NOT cover the APU.**
> Sub-pages `chapter_5_2`, `chapter_5_3`, `chapter_6_6`, `chapter_6_7`, `chapter_8_1`
> all return 404 — the section content for those topics is folded into the parent
> chapter. WebFetch was rate/length-limited on the print build, so the bulk of my
> citations come from individual chapter URLs plus targeted `awk` extractions over
> the print build (which has every chapter inlined).

---

## 1. What this book uniquely brings

### Pedagogy

- **Visible-progress-first.** Chapter 1 explicitly calls out: *"One of the problems with
  writing an emulator is that you don't get any feedback until the very end."* Every
  chapter ends with a runnable artefact: 6 opcodes → load/transfer; addressing modes
  table → real LDA programs; bus + cartridge → first nestest pass; PPU registers + tiles
  → CHR-ROM dump viewer; background → static-screen Pac-Man; sprites → animated
  Pac-Man; scroll → Super Mario Bros walking. **deloNES already does this culturally**
  with `desktop:viewCHRTiles` and `desktop:runNestest` Gradle tasks; preserve that
  philosophy.
- **Snake assembly demo (3.4)** as the CPU-only milestone. A 6502 assembly listing of
  Snake from "Easy 6502" runs against a 32×32 in-memory framebuffer at `$0200..$0600`
  with input at `$FF` and an RNG at `$FE`. No PPU needed. **deloNES has nothing
  equivalent** — see action item below.
- **Iterative refactoring shown in the text.** Each chapter starts with the simplest
  thing that works, then refactors (e.g. `update_zero_and_negative_flags` extracted as a
  helper after the first three opcodes). This is teaching-quality code, not reference
  code.

### Why this complements olcNES for deloNES authors

| | olcNES | bugzmanov |
|---|---|---|
| Language | C++ | Rust |
| Tone | "Here's the working emulator" | "Let's build it stepwise" |
| Code per concept | Dense; one big `clock()` | Small steps, refactored |
| Background pipeline | Cycle-accurate (loopy v/t/x/w) | **Frame-at-a-time, post-NMI render** |
| Scroll | Implicit in the loopy pipeline | A whole dedicated chapter, viewport-rect approach |
| Mappers covered | NROM, MMC1, UxROM, AxROM, MMC3 | NROM only |
| APU | Unfinished, but exists | **Not covered** |
| Test culture | None visible | Rust `#[test]` per opcode, nestest log diff |
| Joypad | Tiny | Tiny but explained |

For deloNES specifically: **read olcNES for the cycle-accurate target architecture;
read bugzmanov for the testing discipline and for the "MVP that displays pixels"
shortcut path** — useful when bringing up a new subsystem before going accurate.

---

## 2. CPU lessons

### 2.1 Dispatch style

bugzmanov uses Rust `match` on the opcode byte:

```rust
match opcode {
    0xA9 => { /* LDA Immediate */ }
    0xAA => { /* TAX */ }
    0x00 => { return; } // BRK
}
```

…and lifts repeated logic into helpers (`update_zero_and_negative_flags`,
`get_operand_address`). Eventually the `match` is keyed by *base mnemonic* and the
addressing mode is fetched from a lookup table. olcNES instead uses a 256-entry table of
function pointers.

**deloNES is already strictly better than both.** `core/src/main/resources/opcodes.csv`
+ reflection in `CPU6502.java` makes the table data, not code. You can regenerate or
tweak the table without recompiling. Keep it.

What is worth borrowing from bugzmanov:

- **Single addressing-mode resolver.** bugzmanov writes one `get_operand_address(mode)
  -> u16` that all instruction handlers call. This is a clean shape — verify that
  deloNES's reflection-dispatched addressing-mode methods all funnel through a single
  resolver-style entry point so that page-cross detection lives in **one** place.
- **`run_with_callback`** (Ch. 3.4):
  ```rust
  pub fn run_with_callback<F>(&mut self, mut callback: F)
  where F: FnMut(&mut CPU)
  ```
  Lets the host inject per-instruction work (input polling, tracing, screen redraw for
  the Snake demo). **deloNES should add `CPU6502.runWithCallback(Consumer<CPU6502>)`.**
  This is exactly how the nestest trace test works in Ch. 5.1, and it is how
  `desktop:runNestest` in deloNES would naturally hook a tracer in.

### 2.2 Status flags

bugzmanov keeps `status: u8` and bit-bangs by hand. olcNES uses a bitmask enum with
`GetFlag/SetFlag`. deloNES already mirrors olcNES — no change.

### 2.3 ADC / SBC

bugzmanov's notes (Ch. 3.3) are valuable — *"the Ricoh modification of the chip didn't
support decimal mode"* (so D-flag handling is a no-op), and `SBC` is implemented as
`A + (!B) + C`. Both already true in deloNES `CPU6502`, but if you ever audit the C
flag, bugzmanov's chapter is the cleanest derivation in book form.

### 2.4 PHP / PLP / RTI and the B flag

bugzmanov calls out (Ch. 3.3) that PHP / PLP / RTI are the only non-interrupt
instructions that touch the B flag (bit 4) and that **the pushed status byte must have
bits 4 and 5 forced**. olcNES has the right behaviour but does not explain it. deloNES
already passes nestest 8992/8992, so this is correct, but the *explanation* in
bugzmanov is the one to point a contributor at when they ask "why is the B-flag
weird?"

### 2.5 Test patterns — the strongest part of the CPU chapters

bugzmanov shows Rust unit tests of this shape after every few opcodes:

```rust
#[test]
fn test_0xa9_lda_immediate_load_data() {
    let mut cpu = CPU::new();
    cpu.load_and_run(vec![0xa9, 0x05, 0x00]);
    assert_eq!(cpu.register_a, 5);
    assert!(cpu.status & 0b0000_0010 == 0);
    assert!(cpu.status & 0b1000_0000 == 0);
}
```

Two ergonomic helpers make this readable: `cpu.load(program)` puts bytes at
`$8000` and sets the reset vector, and `cpu.load_and_run(program)` does the whole
load/reset/run sequence. The `0x00` (BRK) at the end stops the loop.

**Recommendation for deloNES:** add a `Cpu6502TestHarness` (or static factory on
`CPU6502`) that exposes:

```java
static CPU6502 loadProgram(int... bytes);   // writes to $8000..., sets reset, runs reset
void runUntilBrk();                         // convenience for tests
```

Then port a handful of bugzmanov-shaped per-opcode tests as Java/JUnit5 to live
alongside `OpcodesTest.java`. The current `OpcodesTest` is more macro-level; the
bugzmanov style gives you 40+ tiny single-purpose tests that pinpoint regressions.

---

## 3. Memory map / Bus

bugzmanov's `Bus` (Ch. 4) is **simpler than olcNES's** and **simpler than deloNES's**.
It is an `if/else` chain:

```rust
match addr {
    RAM ..= RAM_MIRRORS_END => self.cpu_vram[(addr & 0b00000111_11111111) as usize],
    PPU_REGISTERS ..= PPU_REGISTERS_MIRRORS_END => {
        let mirror_down_addr = addr & 0b00100000_00000111;
        self.read_from_ppu_register(mirror_down_addr)
    }
    0x8000 ..= 0xFFFF => self.read_prg_rom(addr),
    _ => { println!("Ignoring mem access at {}", addr); 0 }
}
```

Two things worth lifting:

1. **Named address-range constants** (`RAM`, `RAM_MIRRORS_END`, `PPU_REGISTERS`,
   `PPU_REGISTERS_MIRRORS_END`) — much more grep-able than magic numbers. deloNES
   currently scatters `0x2000` / `0x4000` literals across `PPU.java` and `CPUBus.java`.
   Pull them into a `MemoryMap` final-static-int container.
2. **Explicit "ignored access" path** that logs the address — easier to debug stuck
   ROMs than silent zero. Add to `CPUBus.read/write` for unmapped addresses.

deloNES's `CPUBusComponent` list-of-ranges dispatch (`CPUBus.java:12`) is more
extensible than either bugzmanov's `match` or olcNES's `if/else`. Keep it. **Do NOT
port bugzmanov's flat match.**

---

## 4. ROM / iNES parsing

bugzmanov (Ch. 5) parses iNES into:

```rust
pub struct Rom {
    pub prg_rom: Vec<u8>,
    pub chr_rom: Vec<u8>,
    pub mapper: u8,
    pub screen_mirroring: Mirroring,
}
```

Highlights:

- Parses **iNES 1.0 only**, with an explicit error on iNES 2.0 (`if (raw[7] >> 2) & 0b11
  != 0 { return Err("NES2.0 format is not supported"); }`). olcNES detects v2 but does
  not explicitly reject it. **bugzmanov's "fail loudly" behaviour is the better default.**
  deloNES `INESHeader` should add an explicit `Iv2NotSupportedException` rather than
  silently parsing v2 fields wrong.
- Recognises the trainer flag (bit 2 of header byte 6) and seeks past 512 bytes when
  set. deloNES already handles this in `Cartridge`/`INESHeader`; verify against
  `core/src/net/lomibao/nes/rom/mapper/INESHeader.java`.
- Computes `mapper_id = (raw[7] & 0b1111_0000) | (raw[6] >> 4)`. Identical to olcNES.

bugzmanov also keeps the `Rom` constructor pure (no I/O — it takes a `&[u8]`); deloNES
already does the same with its `InputStream`-based `Cartridge` constructor.

---

## 5. PPU — the biggest section

### 5.1 Register modeling (Ch. 6.1, 6.2)

bugzmanov uses Rust `bitflags!` macro to wrap each register byte:

```rust
bitflags! {
    pub struct ControlRegister: u8 {
        const NAMETABLE1            = 0b0000_0001;
        const NAMETABLE2            = 0b0000_0010;
        const VRAM_ADD_INCREMENT    = 0b0000_0100;
        const SPRITE_PATTERN_ADDR   = 0b0000_1000;
        const BACKROUND_PATTERN_ADDR= 0b0001_0000;
        const SPRITE_SIZE           = 0b0010_0000;
        const MASTER_SLAVE_SELECT   = 0b0100_0000;
        const GENERATE_NMI          = 0b1000_0000;
    }
}
impl ControlRegister {
    pub fn vram_addr_increment(&self) -> u8 {
        if !self.contains(ControlRegister::VRAM_ADD_INCREMENT) { 1 } else { 32 }
    }
    pub fn update(&mut self, data: u8) { self.bits = data; }
}
```

The same pattern repeats for `ControlRegister`, `MaskRegister`, `StatusRegister`,
`ScrollRegister`, and a custom `AddrRegister`.

**Java shape.** Java's nearest equivalent is an immutable wrapper or static helpers:

```java
public final class PpuCtrl {
    public static final int NAMETABLE_LO       = 0b0000_0001;
    public static final int NAMETABLE_HI       = 0b0000_0010;
    public static final int VRAM_ADD_INCREMENT = 0b0000_0100;
    public static final int SPRITE_PATTERN     = 0b0000_1000;
    public static final int BG_PATTERN         = 0b0001_0000;
    public static final int SPRITE_SIZE        = 0b0010_0000;
    public static final int MASTER_SLAVE       = 0b0100_0000;
    public static final int GENERATE_NMI       = 0b1000_0000;

    public static int vramAddrIncrement(int ctrl) { return (ctrl & VRAM_ADD_INCREMENT) != 0 ? 32 : 1; }
    public static int nametableAddr(int ctrl) {
        return 0x2000 + (ctrl & 0b11) * 0x400;
    }
}
```

Currently `PPU.java:13` keeps a single `byte[] registers` array; reads/writes go through
`switch (address & 0x7)`. This works, but adding **typed accessor helpers** (one
`PpuCtrl` / `PpuMask` / `PpuStatus` static class each) makes call sites at
`PPU.java:101` and `PPU.java:156` much more legible and protects against accidental bit
mistakes.

### 5.2 PPUADDR + PPUDATA — bugzmanov's clearest exposition

The `AddrRegister` (Ch. 6.2) is a small state machine over a `(u8, u8)` pair plus a
`hi_ptr` toggle:

```rust
pub struct AddrRegister { value: (u8, u8), hi_ptr: bool }

impl AddrRegister {
    pub fn update(&mut self, data: u8) {
        if self.hi_ptr { self.value.0 = data; } else { self.value.1 = data; }
        if self.get() > 0x3fff { self.set(self.get() & 0b11_1111_1111_1111); }
        self.hi_ptr = !self.hi_ptr;
    }
    pub fn increment(&mut self, inc: u8) { /* low-byte += inc with carry, mirror >0x3fff */ }
    pub fn reset_latch(&mut self) { self.hi_ptr = true; }
}
```

Plus the buffered-read protocol for `$2007`:

> *"Because CHR ROM and RAM are considered external devices to PPU, PPU can't return
> the value immediately... The first read from 0x2007 would return the content of this
> internal buffer filled during the previous load operation. From the CPU perspective,
> this is a dummy read."*
>
> *"IMPORTANT: This buffered reading behavior is specific only to ROM and RAM. Reading
> palette data from $3F00-$3FFF works differently. The palette data is placed
> immediately on the data bus, and hence no dummy read is required."*

deloNES already implements both of these (`PPU.java:25-27` shows `ppuAddress`,
`addressLatch`, `ppuDataBuffer`) — but the bugzmanov chapter is the **best plain-English
explanation in book form** of why `$2007` reads need a dummy. Link contributors at
[ch. 6.2](https://bugzmanov.github.io/nes_ebook/chapter_6_2.html) when reviewing
`PPU.cpuBusRead` palette-bypass code.

### 5.3 NMI + clock (Ch. 6.3)

bugzmanov uses a "catch-up" ticker — the CPU runs an instruction, then advances the
PPU by `cycles * 3`:

```rust
pub fn tick(&mut self, cycles: u8) {
    self.cycles += cycles as usize;
    let nmi_before = self.ppu.nmi_interrupt.is_some();
    self.ppu.tick(cycles * 3);
    let nmi_after = self.ppu.nmi_interrupt.is_some();
    if !nmi_before && nmi_after {
        (self.gameloop_callback)(&self.ppu, &mut self.joypad1);
    }
}
```

This is **strictly looser** than olcNES's per-master-cycle alternation, and it is what
deloNES is closest to today (PPU.clock not yet driven from the system clock per
`docs/olcnes_review.md` §1). Two pragmatic notes:

- The `nmi_before / nmi_after` edge-trigger is the right shape: trigger the host
  callback on the **rising edge** of the NMI flag, not on every PPU tick. Worth porting
  exactly.
- Catch-up is fine for first-gen games (Pac-Man, Donkey Kong, SMB without raster
  effects). For raster splits or sprite-0-hit-driven status polling, you eventually
  need finer granularity. **Recommendation:** start with bugzmanov's catch-up shape
  in deloNES, leave a `// TODO: per-master-cycle` comment, and only refactor when a
  test ROM forces it.

### 5.4 Background rendering — the simplification you need to know about

This is the **single biggest difference** between bugzmanov and olcNES:

**bugzmanov renders the entire frame in one pass at NMI time.** No 8-cycle
fetch/shifter pipeline, no loopy v/t/x/w internal registers, no per-cycle pixel
production.

The render function is just (Ch. 6.5):

```rust
pub fn render(ppu: &NesPPU, frame: &mut Frame) {
    let bank = ppu.ctrl.bknd_pattern_addr();
    for i in 0..0x03c0 {                     // 960 background tiles
        let tile = ppu.vram[i] as u16;
        let tile_x = i % 32;
        let tile_y = i / 32;
        let tile = &ppu.chr_rom[(bank + tile * 16) as usize..=(bank + tile * 16 + 15) as usize];
        for y in 0..=7 {
            let mut upper = tile[y];
            let mut lower = tile[y + 8];
            for x in (0..=7).rev() {
                let value = (1 & upper) << 1 | (1 & lower);
                upper >>= 1; lower >>= 1;
                let rgb = /* palette lookup */;
                frame.set_pixel(tile_x*8 + x, tile_y*8 + y, rgb)
            }
        }
    }
}
```

He explicitly flags this as a **deliberate shortcut** (his caps): *"WARNING This is
quite a drastic simplification that limits the types of games it will be possible to
play on the emulator."* It precludes split scrolls, mid-frame palette swaps, and most
raster effects.

**Where deloNES sits today.** From the file shape (`PPU.java:206 clock()` does
per-tick fetches, `PPURenderingTest` exists), deloNES has already moved past the
bugzmanov shortcut into a per-cycle fetch model. **That is the right direction.** Do
NOT regress to bugzmanov's full-frame renderer; **do** keep his code around as a
sanity check / golden image generator (run both and diff frames during PPU
refactors). See action item #6 below.

### 5.5 Attribute table decode

bugzmanov's `bg_pallette` helper (Ch. 6.5) is the cleanest explanation of attribute
decoding I've seen in any source:

```rust
fn bg_pallette(ppu: &NesPPU, tile_column: usize, tile_row: usize) -> [u8;4] {
    let attr_table_idx = tile_row / 4 * 8 + tile_column / 4;
    let attr_byte = ppu.vram[0x3c0 + attr_table_idx];
    let pallet_idx = match (tile_column % 4 / 2, tile_row % 4 / 2) {
        (0,0) => attr_byte         & 0b11,
        (1,0) => (attr_byte >> 2)  & 0b11,
        (0,1) => (attr_byte >> 4)  & 0b11,
        (1,1) => (attr_byte >> 6)  & 0b11,
        _ => panic!(),
    };
    let start = 1 + (pallet_idx as usize) * 4;
    [ppu.palette_table[0],
     ppu.palette_table[start],
     ppu.palette_table[start+1],
     ppu.palette_table[start+2]]
}
```

Note the off-by-one *"1 +"* on `start` — the universal background colour at index 0 of
each 4-colour group is fetched from the master `palette_table[0]` (mirror of `$3F00`)
regardless of which palette the attribute byte selects. **deloNES should grep
`PaletteMemory` to make sure this aliasing is implemented.**

### 5.6 Sprites / OAM (Ch. 6.5)

Per-sprite OAM layout (4 bytes):

| Byte | Field |
|---|---|
| 0 | Y position |
| 1 | Tile index |
| 2 | Attributes (palette bits 0-1, priority bit 5, h-flip bit 6, v-flip bit 7) |
| 3 | X position |

bugzmanov iterates OAM **in reverse** (`step_by(4).rev()`) so that lower-indexed
sprites end up drawn on top — a poor man's priority. Full OAM dump per frame, no
secondary-OAM evaluation, no per-scanline 8-sprite limit, no sprite-vs-sprite priority,
and **no proper sprite-0 hit pixel test** — see 5.7.

What's worth porting:

- **The 4-byte field decode** as a static helper class `OamSprite` with `y/tileId/attr/x`
  accessors. Avoid the C++ trick of re-casting an array pointer.
- **The h-flip / v-flip pixel-write pattern**:
  ```rust
  match (flip_horizontal, flip_vertical) {
      (false, false) => frame.set_pixel(tile_x + x,     tile_y + y,     rgb),
      (true,  false) => frame.set_pixel(tile_x + 7 - x, tile_y + y,     rgb),
      (false, true ) => frame.set_pixel(tile_x + x,     tile_y + 7 - y, rgb),
      (true,  true ) => frame.set_pixel(tile_x + 7 - x, tile_y + 7 - y, rgb),
  }
  ```
- **Skipping `value == 0` pixels** (transparent for sprites, vs background where 0 means
  universal background colour). bugzmanov has a `'ololo: for x ... continue 'ololo;`
  in Rust; in Java just `if (value == 0) continue;`.

What NOT to port: the priority-by-iteration-order trick if you intend to be accurate.
Real PPU honours bit 5 of the attribute byte (sprite vs background priority) per pixel.

### 5.7 Sprite-0 hit (Ch. 7)

bugzmanov gives the cheapest possible approximation:

```rust
fn is_sprite_0_hit(&self, cycle: usize) -> bool {
    let y = self.oam_data[0] as usize;
    let x = self.oam_data[3] as usize;
    (y == self.scanline as usize) && x <= cycle && self.mask.show_sprites()
}
```

He flags it as inadequate: *"This is a very rough simulation of the behavior. The
accurate one requires checking opaque pixels of a sprite colliding with opaque pixels
of background."* For SMB and Ice Climber it's good enough.

**Action for deloNES:** when sprite rendering lands, implement bugzmanov's coarse
version first (it's 6 lines), then upgrade to opaque-pixel checking once you have a
sprite-0 hit test ROM in CI.

### 5.8 Mirroring (Ch. 6.2)

bugzmanov computes the nametable mirror via:

```rust
pub fn mirror_vram_addr(&self, addr: u16) -> u16 {
    let mirrored_vram = addr & 0b10111111111111;        // 0x3000-0x3eff -> 0x2000-0x2eff
    let vram_index = mirrored_vram - 0x2000;
    let name_table = vram_index / 0x400;
    match (&self.mirroring, name_table) {
        (Mirroring::Vertical,  2) | (Mirroring::Vertical,  3) => vram_index - 0x800,
        (Mirroring::Horizontal,2)                              => vram_index - 0x400,
        (Mirroring::Horizontal,1)                              => vram_index - 0x400,
        (Mirroring::Horizontal,3)                              => vram_index - 0x800,
        _ => vram_index,
    }
}
```

deloNES already factors this into `NameTableMemory` + `MirroringMode`
(`core/src/net/lomibao/nes/components/ppu/MirroringMode.java`). Confirm the same match
table is implemented and add a unit test per `(MirroringMode, source_addr) → physical
offset` cell — exactly what `NameTableMemoryTest.java` should encode.

### 5.9 The Scrolling chapter

This is the chapter bugzmanov is famous for. The **mental model** is the part to
internalise:

> *"For each frame, we would scan through both nametables. For each nametable we would
> specify visible part of the nametable... and apply shift transformation for each
> visible pixel — shift_x, shift_y."*

He defines a `Rect` (visible portion of a nametable) and a per-nametable `(shift_x,
shift_y)` translation, then renders both nametables clipped + shifted into the
framebuffer. For horizontal scrolling at `scroll_x = 200`:

| Nametable | Source rect | Shift |
|---|---|---|
| Base (e.g. `$2000`) | `(200, 0, 256, 240)` | `(-200, 0)` |
| Other (e.g. `$2400`) | `(0, 0, 200, 240)` | `(56, 0)` |

He explicitly notes **two gotchas**:

1. *"The palette of a tile is defined by the nametable the tile belongs to, not by the
   base nametable specified in the Control register"* — i.e., when crossing a
   nametable boundary, you must read the **other** nametable's attribute table for the
   right palette index.
2. *"For horizontal scrolling the content of the base nametable always goes to the left
   part of the viewport (or top part in case of vertical scrolling)"*.

**This is the simplest possible scroll implementation.** It does not model loopy
v/t/x/w. It does not handle mid-frame scroll changes (no split scroll). It cannot run
e.g. *Battletoads* status bars. But for SMB / Ice Climber / Donkey Kong it works.

For deloNES the right move is **two-stage**:

1. **MVP:** port bugzmanov's `render_name_table(ppu, frame, name_table, view_port,
   shift_x, shift_y)` shape into the existing `PixelRenderer` path. Gets SMB on screen.
2. **Accurate:** swap to the loopy v/t/x/w pipeline (described in
   `docs/olcnes_review.md` §3) once you need split scroll.

### 5.10 PPU summary table — port priority

| Behaviour | bugzmanov ch. | deloNES status | Action |
|---|---|---|---|
| Buffered $2007 read, palette bypass | 6.2 | Implemented (`PPU.java:27`) | Verify against unit test |
| PPUADDR latch toggle, 14-bit mirror | 6.2 | Implemented (`PPU.java:26`) | Add `$2002` read clears latch — bugzmanov ch. 6.2 |
| ControlRegister bitflags | 6.2 | Raw `byte[] registers` | Refactor to typed `PpuCtrl` static helpers |
| StatusRegister bitflags | 6.2 | Same | Same |
| Tile decoder (CHR pattern → 4-colour) | 6.4 | Implemented (`TileDecoder`) | None |
| Background per-tile renderer | 6.5 | Implemented per-cycle | Keep accurate path; add bugzmanov path as test golden |
| Attribute table decode | 6.5 | (verify) | Port `bg_pallette` helper into `PaletteMemory` |
| Universal-bg-colour aliasing ($3F10/$3F14/$3F18/$3F1C → $3F00/...) | 6.5 implicit | (verify) | Add to `PaletteMemory.read/write` |
| Sprite OAM render | 6.5 | **Not yet** | Port full Ch 6.5 listing |
| Sprite h/v flip | 6.5 | **Not yet** | Port the 4-arm match |
| Sprite-0 hit (coarse) | 7 | **Not yet** | Port the 6-line approximation first |
| Sprite-0 hit (opaque pixel test) | not in book | **Not yet** | Future |
| Scroll (viewport-rect, MVP) | 7 | **Not yet** | Port for SMB-grade games |
| Scroll (loopy v/t/x/w, accurate) | not in book | **Not yet** | See `docs/olcnes_review.md` §3 |

---

## 6. Joypad / controller (Ch. 8)

bugzmanov's joypad is exactly the right shape and is small enough to port verbatim:

```rust
bitflags! {
    pub struct JoypadButton: u8 {
        const RIGHT      = 0b1000_0000;
        const LEFT       = 0b0100_0000;
        const DOWN       = 0b0010_0000;
        const UP         = 0b0001_0000;
        const START      = 0b0000_1000;
        const SELECT     = 0b0000_0100;
        const BUTTON_B   = 0b0000_0010;
        const BUTTON_A   = 0b0000_0001;
    }
}
pub struct Joypad { strobe: bool, button_index: u8, button_status: JoypadButton }

impl Joypad {
    pub fn write(&mut self, data: u8) {
        self.strobe = data & 1 == 1;
        if self.strobe { self.button_index = 0 }
    }
    pub fn read(&mut self) -> u8 {
        if self.button_index > 7 { return 1; }
        let response = (self.button_status.bits & (1 << self.button_index)) >> self.button_index;
        if !self.strobe && self.button_index <= 7 { self.button_index += 1; }
        response
    }
}
```

Key correctnesses bugzmanov gets right that olcNES gets wrong:

- **After button 7 is read, the controller returns `1` for every subsequent read** (that
  `if self.button_index > 7 { return 1; }`) until the next strobe. olcNES just keeps
  shifting zeros. Some games depend on this.
- **Setting `strobe = false` does NOT reset `button_index`.** Only `strobe = true`
  resets it. Subtle but correct.
- The standard handshake is *strobe high, strobe low, then 8 reads* — both matched.

**Java shape for `core/src/net/lomibao/nes/components/Controller.java`:**

```java
public final class StandardController implements CPUBusComponent {
    public static final int RIGHT = 0x80, LEFT = 0x40, DOWN = 0x20, UP = 0x10,
                            START = 0x08, SELECT = 0x04, B = 0x02, A = 0x01;

    private boolean strobe;
    private int buttonIndex;
    private int buttonStatus;        // bit-packed live state, set by input layer

    public void write(int data) {
        strobe = (data & 1) != 0;
        if (strobe) buttonIndex = 0;
    }
    public int read() {
        if (buttonIndex > 7) return 1;
        int bit = (buttonStatus >> buttonIndex) & 1;
        if (!strobe) buttonIndex++;
        return bit;
    }
    public void setButton(int mask, boolean pressed) {
        buttonStatus = pressed ? buttonStatus | mask : buttonStatus & ~mask;
    }
}
```

Plus a desktop key-map in `DesktopLauncher`:

```java
keyMap.put(Input.Keys.UP,    StandardController.UP);
keyMap.put(Input.Keys.DOWN,  StandardController.DOWN);
keyMap.put(Input.Keys.LEFT,  StandardController.LEFT);
keyMap.put(Input.Keys.RIGHT, StandardController.RIGHT);
keyMap.put(Input.Keys.SPACE, StandardController.SELECT);
keyMap.put(Input.Keys.ENTER, StandardController.START);
keyMap.put(Input.Keys.A,     StandardController.A);
keyMap.put(Input.Keys.S,     StandardController.B);
```

---

## 7. APU

**bugzmanov does not cover the APU.** The book ends after PPU Scrolling, and the
afterword acknowledges that the audio chapter was not written. There is a stub URL
(`chapter_9.html`) but it returns only the navigation chrome.

For APU work in deloNES, fall back to:

- `docs/olcnes_review.md` §8 (already covers olc's "VERY UNFINISHED" pulse-only APU and
  the right Java interface shape).
- The [NesDev wiki APU pages](https://www.nesdev.org/wiki/APU) for the channel
  specifications.
- Other open-source emus with finished APUs (e.g. blargg's `nes_apu` in Mesen / FCEUX)
  for behavioural test ROMs.

---

## 8. Testing & TDD style — the strongest pedagogy in the book

bugzmanov is the only one of the three reference materials (olcNES C++, NesDev wiki,
this book) that actively models a **test-first culture**. Things to lift:

### 8.1 Per-opcode unit tests at first-class granularity

After implementing a single opcode, bugzmanov writes a 5-line test that constructs a
CPU, loads a 3-byte program, runs it, and asserts `register_a` and the relevant flag
bits. **Action: replicate this in deloNES `OpcodesTest.java`** with helpers:

```java
static byte[] program(int... bytes) { /* int -> byte */ }
static CPU6502 runProgram(int... bytes) { ... }
```

Then you can write 50+ tiny tests that pinpoint exactly which opcode regressed.

### 8.2 nestest log diff (Ch. 5.1)

bugzmanov's nestest setup:

1. Patch `cpu.program_counter = 0xC000;` after reset (nestest auto-mode entry point).
2. Run with a `run_with_callback` tracer that prints
   `C000  4C F5 C5  JMP $C5F5    A:00 X:00 Y:00 P:24 SP:FD CYC:7`
3. `diff` against `nestest.log`.
4. First mismatch is at `$C6BD` where nestest hits an unofficial opcode — implement,
   repeat.

deloNES already passes 8992/8992 (per CLAUDE.md), so the *tracer + log diff* flow is
already wired. **Formalise it as a snapshot test** so a future PPU refactor that
accidentally writes to `$2000`-`$2007` from CPU code doesn't silently break
`NestestTest`.

### 8.3 Testing without a real ROM

bugzmanov's CPU tests use `cpu.load_and_run(vec![0xa9, 0x05, 0x00])` — no cartridge,
no PPU, no bus topology. The CPU is constructed with an in-memory backing store. This
makes per-opcode tests fast and isolated.

deloNES has `FullAddressRam` for exactly this purpose. **Make it the default test
fixture** for any `CPU6502*Test` that is testing pure CPU semantics.

### 8.4 Recommended additional integration ROMs

bugzmanov mentions but does not deeply use these — they are still the canonical PPU
test set:

- `cpu_dummy_reads.nes` — covers `$2002` read side-effects
- `ppu_vbl_nmi/*.nes` — VBlank/NMI timing
- `sprite_hit_tests_2005.10.05/*.nes` — sprite-0 hit timing
- `oam_read.nes`, `oam_stress.nes` — OAM behaviour

deloNES should add a `PpuTestRomTest` JUnit class that runs these headlessly and
asserts the result code in the status palette (NesDev convention: result is written as
a colour to the screen).

---

## 9. Concrete "port this to deloNES" action list

In rough priority order (highest impact / lowest effort first):

| # | Action | deloNES file(s) | bugzmanov ref |
|---|---|---|---|
| 1 | Add `CPU6502.runWithCallback(Consumer<CPU6502>)` to enable headless tracers and the Snake demo | `core/src/net/lomibao/nes/components/CPU6502.java` | [ch. 3.4](https://bugzmanov.github.io/nes_ebook/chapter_3_4.html) |
| 2 | Add bugzmanov-style per-opcode JUnit tests using `loadAndRun(byte...)` helper | `core/test/net/lomibao/nes/components/OpcodesTest.java` | [ch. 3.1](https://bugzmanov.github.io/nes_ebook/chapter_3_1.html), [ch. 3.3](https://bugzmanov.github.io/nes_ebook/chapter_3_3.html) |
| 3 | Implement `StandardController` with the strobe / 8-shift / "1 forever after 7" semantics | `core/src/net/lomibao/nes/components/Controller.java` | [ch. 8](https://bugzmanov.github.io/nes_ebook/chapter_8.html) |
| 4 | Sprite rendering MVP (bugzmanov shape: full-OAM scan, h/v flip, transparent on `value==0`) | new `core/src/net/lomibao/nes/components/ppu/SpriteRenderer.java` and `PPU.java:206` | [ch. 6.5](https://bugzmanov.github.io/nes_ebook/chapter_6_5.html) |
| 5 | Sprite-0-hit coarse approximation in PPU tick | `PPU.java:206` | [ch. 7](https://bugzmanov.github.io/nes_ebook/chapter_7.html) |
| 6 | Add a "golden frame" generator that uses bugzmanov's full-frame renderer and diffs against the per-cycle pipeline output for regression testing | new `core/test/.../PPUGoldenFrameTest.java` | [ch. 6.5](https://bugzmanov.github.io/nes_ebook/chapter_6_5.html) |
| 7 | Refactor PPU register access into typed `PpuCtrl/PpuMask/PpuStatus` static helpers (replace `byte[] registers` `switch (addr & 7)` blocks at `PPU.java:101`, `PPU.java:156`) | `core/src/net/lomibao/nes/components/PPU.java`, new `core/src/net/lomibao/nes/components/ppu/PpuCtrl.java` etc. | [ch. 6.2](https://bugzmanov.github.io/nes_ebook/chapter_6_2.html) |
| 8 | Verify palette aliasing (`$3F10/$3F14/$3F18/$3F1C` → `$3F00/$3F04/$3F08/$3F0C`) lives in `PaletteMemory` and add a test | `core/src/net/lomibao/nes/components/ppu/PaletteMemory.java`, `core/test/.../PaletteMemoryTest.java` | [ch. 6.5](https://bugzmanov.github.io/nes_ebook/chapter_6_5.html) |
| 9 | Add the `bg_pallette(tile_col, tile_row)` attribute-decode helper as a unit-tested standalone | `core/src/net/lomibao/nes/components/ppu/AttributeTable.java` | [ch. 6.5](https://bugzmanov.github.io/nes_ebook/chapter_6_5.html) |
| 10 | Add a "Snake" assembly demo launcher (load 6502 Snake at `$0600`, framebuffer at `$0200..$05FF`) as `desktop:runSnake` | `desktop/build.gradle`, `desktop/src/.../SnakeLauncher.java` | [ch. 3.4](https://bugzmanov.github.io/nes_ebook/chapter_3_4.html) |
| 11 | Explicit reject of iNES 2.0 in header parse | `core/src/net/lomibao/nes/rom/mapper/INESHeader.java` | [ch. 5](https://bugzmanov.github.io/nes_ebook/chapter_5.html) |
| 12 | Pull magic addresses (`0x2000`, `0x4000`, `0x07FF`...) into a `MemoryMap` constants class | new `core/src/net/lomibao/nes/components/MemoryMap.java` | [ch. 4](https://bugzmanov.github.io/nes_ebook/chapter_4.html) |
| 13 | Scroll MVP: `render_name_table(ppu, frame, nameTable, viewportRect, shiftX, shiftY)` viewport-rect renderer | new `core/src/net/lomibao/nes/render/ScrollRenderer.java` | [ch. 7](https://bugzmanov.github.io/nes_ebook/chapter_7.html) |
| 14 | NMI rising-edge callback hook (host registers `Consumer<PPU>`, fires only on the false→true transition) | `PPU.java`, `NesEmulator.java:23` | [ch. 6.3](https://bugzmanov.github.io/nes_ebook/chapter_6_3.html) |
| 15 | Per-instruction "ignored mem access at `$XXXX`" log line in `CPUBus.read/write` | `core/src/net/lomibao/nes/components/CPUBus.java:12` | [ch. 4](https://bugzmanov.github.io/nes_ebook/chapter_4.html) |

Items 1–6 unlock playable Pac-Man / Donkey Kong / SMB. Items 7–10 are quality-of-life
that pay off long term. Items 11–15 are small polish.

---

## 10. What bugzmanov does that you should NOT copy directly

| Pattern | Why not |
|---|---|
| **Single-pass post-NMI frame renderer** | Caps you at first-gen games; deloNES is already past this. Keep bugzmanov's shape only as a golden-image generator for tests. |
| **Reverse-iteration sprite priority** (`step_by(4).rev()`) | Skips real per-pixel priority bit. Wrong for many games. |
| **Mutable `Box<dyn FnMut>` callback owned by `Bus`** | Rust-isms. Java should expose a `BiConsumer<PPU, StandardController>` or just a `FrameRenderedListener` interface — no lifetime gymnastics needed. |
| **`bitflags!` macro everywhere** | Java's nearest equivalent is verbose. Use `static final int` masks + helper methods, NOT enums per bit (see `docs/olcnes_review.md` §3). |
| **`(u8, u8)` tuple inside `AddrRegister`** | Use a single `int` and bit-shift. The tuple representation invites subtle carry bugs that bugzmanov has to fix manually in `increment()`. |
| **Catch-up CPU cycles × 3** | Fine to start with — but plan to swap to per-master-cycle stepping when sprite-0 raster effects come. Don't bake the catch-up assumption into too many call sites. |
| **`'ololo` Rust loop labels** | Just `continue;` in Java. |
| **Panicking in render functions** (`panic!("can't be")`) | In Java, throw `IllegalStateException` only for genuine invariants; never in pixel hot paths. |
| **`bus.read_prg_rom(addr)` baked into `Bus`** | Cartridge access should always go through the `Mapper` interface. bugzmanov skips this because he only supports mapper 0; deloNES already has the `Mapper` indirection (`rom/mapper/Mapper.java`) — keep it. |

---

## 11. Pages I couldn't fetch / known gaps

- `chapter_5_2.html`, `chapter_5_3.html`, `chapter_6_6.html`, `chapter_6_7.html`,
  `chapter_8_1.html`, `chapter_10.html`, `chapter_11.html` — **all 404.** The book's
  sub-sections appear to live inline in their parent chapter pages, not at separate
  URLs. Content for those topics was extracted from the print build
  (`https://bugzmanov.github.io/nes_ebook/print.html`) which inlines every section.
- `chapter_9.html` exists but contains only navigation chrome — **no APU body.** The
  book ends after Chapter 7 (Scrolling) plus an Afterword. Treat the APU as un-covered
  by bugzmanov.
- WebFetch length-truncates the print build. I extracted needed sections via direct
  `grep`/`awk` over a downloaded copy. Anything quoted above with a Rust code block has
  been verified verbatim against the print build text.

---

## 12. Cross-reference summary

| If you want to learn... | Read this in bugzmanov | Read this in olcNES doc |
|---|---|---|
| 6502 fetch-decode-execute basics | ch. 3.1, 3.2 | §2 |
| Addressing modes table | ch. 3.2 | §2 |
| ADC/SBC, B-flag, branches | ch. 3.3 | §2 |
| Snake assembly demo | ch. 3.4 | n/a |
| Bus + RAM mirroring | ch. 4 | §1, §4 |
| iNES parsing | ch. 5 | §6 |
| nestest harness | ch. 5.1 | §11 |
| PPU register layout | ch. 6.1, 6.2 | §3 |
| PPUADDR/PPUDATA buffer | ch. 6.2 | §3, §10.2 |
| NMI + clocking | ch. 6.3 | §1, §10.4 |
| CHR tile decode | ch. 6.4 | §3 |
| Background renderer (simple) | ch. 6.5 | n/a |
| Background renderer (cycle-accurate, loopy) | n/a | §3 |
| Attribute decode | ch. 6.5 | §10.3 |
| Sprite OAM render (simple) | ch. 6.5 | §3 |
| Sprite-0 hit (coarse) | ch. 7 | §11 |
| Sprite-0 hit (accurate) | n/a | §12.2 |
| Scroll (viewport-rect MVP) | ch. 7 | n/a |
| Scroll (loopy v/t/x/w) | n/a | §3 |
| Joypad | ch. 8 | §9 |
| Mappers beyond NROM | n/a | §7 |
| APU | n/a | §8 |

---

*End of review.*
