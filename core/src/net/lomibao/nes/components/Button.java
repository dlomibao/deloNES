package net.lomibao.nes.components;

/**
 * NES controller buttons in the order they are shifted out of the hardware
 * shift register: A first, RIGHT last. This matches the NESdev wiki standard
 * read order at $4016 / $4017.
 *
 * <p>The {@link #ordinal()} of each value corresponds to the bit index
 * within the 8-bit shift register (0 = first bit read).
 */
public enum Button {
    /** Button A — bit 0 (first bit shifted out). */
    A,
    /** Button B — bit 1. */
    B,
    /** Select button — bit 2. */
    SELECT,
    /** Start button — bit 3. */
    START,
    /** D-pad Up — bit 4. */
    UP,
    /** D-pad Down — bit 5. */
    DOWN,
    /** D-pad Left — bit 6. */
    LEFT,
    /** D-pad Right — bit 7 (last bit shifted out). */
    RIGHT
}
