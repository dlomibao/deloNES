# Credits

deloNES bundles a few third-party assets that aren't original to this
project. They are listed here with their authors and the terms under
which they're included.

## Test ROMs

### nestest.nes

**Author:** Kevin Horton (a.k.a. **kevtris**)

**Location in repo:**
- `core/src/main/resources/nestest.nes`
- `core/src/main/resources/roms/nestest.nes`

**Purpose:** Canonical NES CPU correctness test. Drives `NestestTest`'s
8992/8992 byte-for-byte trace match against Nintendulator's known-good
log; it's the integration-test backbone for the 6502 emulator core.

**License:** No formal license is published with the ROM. nestest has
been redistributed for two decades by every major NES emulator project
(FCEUX, Mesen, Nestopia, Bizhawk, ...) under the de-facto convention
of "freely redistributable for non-commercial emulator development,
with credit to the author." deloNES is included for that purpose. If
Kevin Horton ever requests removal, we'll remove it.

**Source:** Originally distributed via NESdev. Archived alongside other
NES test ROMs at <https://github.com/christopherpow/nes-test-roms>.

---

## Reference materials

The implementation borrows heavily from the following community
references. They're not bundled here, but the project would not exist
without them.

- **NESdev Wiki** — <https://www.nesdev.org/wiki/Nesdev_Wiki>. The
  canonical hardware reference for every CPU/PPU/APU/mapper question.
- **OneLoneCoder olcNES** — <https://github.com/OneLoneCoder/olcNES>.
  The C++ reference implementation that inspired the original deloNES
  CPU and PPU shapes. Per-component review in
  [`docs/olcnes_review.md`](docs/olcnes_review.md).
- **bugzmanov NES ebook** — <https://bugzmanov.github.io/nes_ebook/>.
  Step-by-step build with strong PPU/scrolling/joypad chapters.
  Synthesis in [`docs/bugzmanov_nes_ebook_review.md`](docs/bugzmanov_nes_ebook_review.md).
