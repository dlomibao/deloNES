# Controller API Contract

This document is the shared contract between **Stream A** (controller bus
wiring & read/write logic) and **Stream B** (keyboard/input mapping). Both
streams MUST conform to this spec. Any change requires updating this document
and coordinating with both streams.

---

## Button Enum

```java
package net.lomibao.nes.components;

/**
 * NES controller buttons in the standard NESdev shift-register read order.
 * Bit position matches the order the bits are shifted out on $4016/$4017 reads:
 * bit 0 = A (first read), bit 7 = Right (eighth read).
 */
public enum Button {
    A,       // bit 0 — first bit shifted out; value 0x01 in mask constants
    B,       // bit 1 — 0x02
    SELECT,  // bit 2 — 0x04
    START,   // bit 3 — 0x08
    UP,      // bit 4 — 0x10
    DOWN,    // bit 5 — 0x20
    LEFT,    // bit 6 — 0x40
    RIGHT    // bit 7 — 0x80; eighth read
}
```

The ordinal of each enum constant equals its bit position in the internal
latch byte and in the integer mask constants (`A = 0x01 << 0`, ...,
`RIGHT = 0x01 << 7`). This is the order mandated by NESdev wiki "Standard
controller" and matches the iNES/nestest reference.

---

## Public API on `Controller`

### `void setButton(int player, Button button, boolean pressed)`

Set or clear a single button for the specified player.

| Parameter | Values | Notes |
|-----------|--------|-------|
| `player`  | `0` or `1` | Player 0 → port $4016; Player 1 → port $4017 |
| `button`  | any `Button` enum value | |
| `pressed` | `true` = press, `false` = release | |

The method updates the **live button state** immediately. The latch is not
affected until the next strobe-falling-edge event.

```java
controller.setButton(0, Button.A, true);   // press A on player 1
controller.setButton(0, Button.A, false);  // release A on player 1
controller.setButton(1, Button.START, true); // press Start on player 2
```

*Note: the existing `setButton(int mask, boolean pressed)` single-player
convenience method remains for internal/test use, but Stream B MUST use the
two-player form above for all user-facing input mapping.*

---

## CPU Bus Protocol

### Write to $4016 — strobe control

```
cpuBusWrite(0x4016, value)
```

Only **bit 0** of `value` is significant (per NES hardware):

| Bit 0 | Effect |
|-------|--------|
| `1`   | **Strobe high.** The shift register is continuously reloaded with the live button state. Reads return the current state of button A (bit 0 of live state) without advancing the read index. |
| `0`   | **Strobe low (falling edge).** The shift register latches the current live button state. Subsequent reads shift bits out LSB-first: A, B, Select, Start, Up, Down, Left, Right. |

Strobe-low while already low does **not** re-latch — only the `1→0`
transition latches. (A strobe-high always resets the read index to 0.)

Writes to $4017 are routed to the **APU frame counter**, NOT to this
component. Controller must be a no-op for $4017 writes.

### Read from $4016 — Player 1 data

```
cpuBusRead(0x4016) → byte
```

Returns the next latched bit, LSB-first, in the order: A (bit 0), B, Select,
Start, Up, Down, Left, Right.

- **While strobe is high:** returns live state of button A on every read (index
  not advanced).
- **Reads 0–7:** returns 1-bit value for that button position, then advances
  internal index.
- **Reads 8+:** returns `1` (controller line pulled high). Continues returning
  `1` until the next strobe pulse. This is required; some games (e.g. Punch-Out)
  read more than 8 times to detect the signature.

**Open-bus upper bits:** bits 7–1 of the returned byte are open-bus. The NES
hardware convention (and what many games rely on) is `0x40` ORed into the
upper bits. Final return value: `0x40 | bit`. This matches the existing stub
behavior.

### Read from $4017 — Player 2 data

```
cpuBusRead(0x4017) → byte
```

Same shift-register protocol as $4016 but for Player 2 controller. If no
player 2 is connected, every read returns `0x40` (open-bus high, no buttons
pressed). Upper bits: `0x40` same as player 1.

---

## Implementation Invariants (Stream A must enforce)

1. `controller.inCPUBusRange(0x4016)` returns `true`.
2. `controller.inCPUBusRange(0x4017)` returns `true`.
3. **CPUBus.read() must check Controller BEFORE APU** — currently APU's range
   `[0x4000, 0x4020)` swallows $4016 and $4017. See `phase0-findings.md §0.3`.
   Fix: move the Controller check above the APU check in `CPUBus.read()`.
4. `CPUBus.write()` already checks Controller before APU for $4016/$4017 writes.
   No change required for writes.
5. After 8 successful reads (index 0–7 exhausted), `cpuBusRead($4016)` MUST
   return `1` (not `0`) for all subsequent reads until next strobe.

---

## Stream B Integration Points

Stream B (keyboard input) MUST:

1. Hold a reference to the `Controller` instance obtained from the `NesSystem`
   or `CPUBus` (never create its own Controller).
2. Map key-down events to `controller.setButton(player, button, true)`.
3. Map key-up events to `controller.setButton(player, button, false)`.
4. Default keyboard mapping (player 0):

| NES Button | Key |
|------------|-----|
| A          | Z   |
| B          | X   |
| Select     | Right Shift |
| Start      | Enter |
| Up         | Up Arrow |
| Down       | Down Arrow |
| Left       | Left Arrow |
| Right      | Right Arrow |

Stream B MUST NOT call `controller.cpuBusRead` or `controller.cpuBusWrite`
directly — those are the CPU bus protocol methods exclusively for `CPUBus`.
