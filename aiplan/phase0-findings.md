# Phase 0 Findings

## 0.2 — Cartridge Mapper Probe

### How mapper number is detected from the iNES header

`INESHeader.getMapperNumber()` extracts the mapper ID from bytes 6 and 7 of the
16-byte iNES header using the standard iNES 1.0 formula:

```java
return ((headerBytes[6] >> 4) & 0x0F) | (headerBytes[7] & 0xF0);
```

Upper nibble of byte 6 provides the low 4 bits; upper nibble of byte 7 provides
the high 4 bits. This yields the correct 8-bit mapper number for iNES 1.0.
NES 2.0 extended mapper bits (byte 8 bits 0-3) are **not** read, so NES 2.0
ROMs with mapper numbers >= 256 will be mis-identified.

The detection path in `Cartridge` constructor:

1. Reads 16-byte header via `INESHeader`.
2. Parses PRG/CHR sizes and optional 512-byte trainer skip.
3. Calls `header.getMapperNumber()` and dispatches on `mapperType`.

### What happens with unsupported mappers

The `switch` in `Cartridge` (line 83–97) handles cases 0, 1, 2, 3, 4, and 66,
but **only case 0 assigns `mapper`**. Cases 1–4 and 66 are empty `break`
statements — they fall through leaving `mapper = null`.

**Consequence:** Any code path that calls `cpuBusRead` or `cpuBusWrite` on a
Cartridge loaded with an unsupported mapper will throw a
`NullPointerException` on `mapper.cpuMapRead(address)` /
`mapper.cpuMapWrite(address)`. There is no guard, no exception message, and no
logging before the NPE. The app will crash at the first CPU access to cartridge
space (`0x8000–0xFFFF`).

**Implication for Stream D (error handling):**
- Add a `mapper != null` guard in `cpuBusRead`/`cpuBusWrite` with a clear
  error log and safe fallback return (0 / no-op).
- Add a `bImageValid` flag path — the field exists but is never set to `true`
  anywhere, so it provides no safety net currently.
- Consider throwing a descriptive checked exception from the Cartridge
  constructor for unsupported mappers rather than silently storing `null`.

---

## 0.3 — 0x4017 Routing Check

### CPUBus read routing for 0x4016 and 0x4017

In `CPUBus.read()`, addresses are matched against registered components in
this order (relevant excerpt):

```java
} else if (Optional.ofNullable(apu).map(a -> a.inCPUBusRange(addr)).orElse(false)) {
    return apu.cpuBusRead(addr, readOnly);
} else if (Optional.ofNullable(cartridge).map(c -> c.inCPUBusRange(addr)).orElse(false)) {
    return cartridge.cpuBusRead(addr, readOnly);
} else if (Optional.ofNullable(controller).map(c -> c.inCPUBusRange(addr)).orElse(false)) {
    return controller.cpuBusRead(addr, readOnly);
```

`APU` is registered for `0x4000–0x401F` (inclusive, `END_ADDRESS = 0x4020`
exclusive). Both `0x4016` and `0x4017` fall inside this range.

**Therefore: reads to 0x4016 AND 0x4017 are intercepted by APU first.**
The Controller component is never reached for either address during reads
(when both APU and Controller are connected).

### Write routing for 0x4016 and 0x4017

In `CPUBus.write()` the Controller is checked before APU:

```java
} else if (Optional.ofNullable(controller).map(c -> c.inCPUBusRange(addr)).orElse(false)) {
    controller.cpuBusWrite(addr, value);
} else if (Optional.ofNullable(dma).map(d -> d.inCPUBusRange(addr)).orElse(false)) {
```

However `write()` does **not** include APU as a candidate at all in the visible
path — APU writes go through the first `else if (ram...)` fallback chain and APU
is not listed. Actually looking at the write() body: ram → ppu → controller →
dma. APU is absent from writes, so Controller.cpuBusWrite(0x4016) does fire.

**Summary of the bug:**
| Address | Operation | Routed to | Correct? |
|---------|-----------|-----------|----------|
| 0x4016  | write     | Controller | YES |
| 0x4016  | read      | APU (raw register byte) | NO — should go to Controller |
| 0x4017  | write     | Controller (no-op there; should be APU frame counter) | PARTIAL |
| 0x4017  | read      | APU (raw register byte) | NO — should go to Controller |

### Implication for Stream A

Stream A must fix the read routing. Two viable approaches:

1. **Exclude 0x4016–0x4017 from APU's range** — change `APU.END_ADDRESS` to
   `0x4016` (exclusive), shifting the last two APU-IO registers out of APU's
   inCPUBusRange. This is the minimal-change fix but requires auditing which
   APU registers legitimately live at 0x4015 and below.

2. **Raise Controller priority above APU in `CPUBus.read()`** — check
   Controller before APU. This fixes read routing without touching APU address
   ranges. APU still shadows 0x4017 writes (frame counter), which is actually
   correct NES behavior for writes; only reads need the Controller to win.

Recommended: approach 2 for reads (move Controller check above APU in
`CPUBus.read()`). For writes, 0x4017 should reach APU frame counter — currently
it silently goes to Controller which no-ops it, so APU frame counter writes are
lost. Stream A should also add APU to the write path.

The existing `Controller.cpuBusRead(0x4017)` correctly returns `0x40`
(open-bus stub), which is the right value when no player 2 is connected, but it
is unreachable under the current bus routing.
