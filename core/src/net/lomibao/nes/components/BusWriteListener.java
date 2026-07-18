package net.lomibao.nes.components;

/**
 * Seam S1 (headless-harness plan, Phase B1): observer for CPU-bus writes.
 *
 * <p>A single nullable listener may be installed on {@link CPUBus} via
 * {@code setWriteListener}. With no listener installed the bus write path
 * pays exactly one null-check branch (TeaVM hot-path convention — no
 * Optional, no lambda capture). With a listener attached, each write
 * additionally pays a readOnly RAM snapshot (RAM range only) and this
 * callback — acceptable, since a listener is only ever attached by the
 * test harness.
 *
 * <p><b>OAM-DMA caveat:</b> {@code DmaController.tickDmaCycle} writes OAM
 * via {@code ppu.writeOam} directly, never through {@code CPUBus.write} —
 * this listener will NOT see the 256-byte OAM burst (it DOES see the CPU
 * stores that populate the shadow page, and the $4014 trigger write).
 */
public interface BusWriteListener {

    /**
     * Invoked before every CPU-bus write is routed to its component.
     *
     * @param addr     the CPU-visible (as-seen, 16-bit masked) address
     * @param oldValue the pre-write RAM value for $0000-$1FFF, or {@code -1}
     *                 for every other address (PPU regs, APU/IO, cart space
     *                 have no well-defined "old value" at the bus level)
     * @param newValue the byte being written
     * @param pc       {@code cpu.getPc()} at write time ("pc after fetch")
     */
    void onWrite(int addr, int oldValue, byte newValue, int pc);
}
