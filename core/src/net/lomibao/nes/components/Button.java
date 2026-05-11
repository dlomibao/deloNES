package net.lomibao.nes.components;

/**
 * NES controller buttons in the standard NESdev shift-register read order.
 *
 * <p>Bit position matches the order the bits are shifted out on $4016/$4017
 * reads: bit 0 = A (first read), bit 7 = Right (eighth read).
 *
 * <p>The ordinal of each enum constant equals its bit position in the
 * internal latch byte and in the integer mask constants
 * ({@code A = 0x01 << 0}, ..., {@code RIGHT = 0x01 << 7}). This is the order
 * mandated by the NESdev wiki "Standard controller" entry and matches the
 * iNES/nestest reference.
 *
 * <p><strong>Stream A contract:</strong> Stream A wires this enum into
 * {@link Controller#setButton(int, Button, boolean)}.  Stream B (keyboard
 * input) uses it for all user-facing input mapping.
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
