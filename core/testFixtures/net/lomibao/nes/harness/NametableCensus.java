package net.lomibao.nes.harness;

import net.lomibao.nes.components.PPU;

/**
 * Nametable census over seam S2, {@code PPU.peekPpuBus(int)} (headless-
 * harness plan, Phase B4). All reads are side-effect free — no $2007
 * buffered-read machinery, no loopy-v increments, no A12 mapper clocking —
 * so observing every frame leaves the run bit-identical.
 *
 * <p>Table indices are logical 0-3 ($2000/$2400/$2800/$2C00); mirroring is
 * whatever the cartridge's {@code MirroringMode} maps them to, exactly as
 * the PPU itself would see.
 */
public final class NametableCensus {

    private final PPU ppu;

    NametableCensus(PPU ppu) {
        this.ppu = ppu;
    }

    /**
     * Tile id at {@code (col, row)} of nametable {@code table}.
     *
     * @param table 0-3
     * @param col   0-31
     * @param row   0-29
     */
    public int tileAt(int table, int col, int row) {
        if (table < 0 || table > 3) {
            throw new IllegalArgumentException("table must be 0-3, got: " + table);
        }
        if (col < 0 || col > 31) {
            throw new IllegalArgumentException("col must be 0-31, got: " + col);
        }
        if (row < 0 || row > 29) {
            throw new IllegalArgumentException("row must be 0-29, got: " + row);
        }
        return ppu.peekPpuBus(0x2000 + table * 0x400 + row * 32 + col);
    }

    /**
     * Raw attribute byte covering {@code (col, row)} of nametable
     * {@code table} (one attribute byte spans a 4×4-tile area).
     */
    public int attributeAt(int table, int col, int row) {
        if (table < 0 || table > 3) {
            throw new IllegalArgumentException("table must be 0-3, got: " + table);
        }
        if (col < 0 || col > 31) {
            throw new IllegalArgumentException("col must be 0-31, got: " + col);
        }
        if (row < 0 || row > 29) {
            throw new IllegalArgumentException("row must be 0-29, got: " + row);
        }
        return ppu.peekPpuBus(0x23C0 + table * 0x400 + (row / 4) * 8 + (col / 4));
    }
}
