package net.lomibao.nes.harness;

import net.lomibao.nes.components.PPU;

/**
 * Sprite census over a 256-byte OAM image (headless-harness plan, Phase B4)
 * — the supported replacement for the Temp* "loop over oam() counting
 * y &lt; 0xEF" diagnostics.
 *
 * <p>The census is an immutable snapshot taken at construction:
 * {@link NesHarness#oamCensus()} snapshots real OAM via the public
 * {@code PPU.readOam(int)}; {@link NesHarness#shadowOamCensus(int)}
 * snapshots a CPU-RAM shadow page (the OAM-DMA source) via side-effect-free
 * peeks.
 *
 * <p><b>Why this exists (OAM-DMA bypass):</b> {@code DmaController} writes
 * OAM through {@code ppu.writeOam} directly, never through
 * {@code CPUBus.write} — bus write watches never see the 256-byte burst
 * (only the CPU stores that populate the shadow page, and the $4014
 * trigger write). This census is the sanctioned view of OAM contents.
 */
public final class OamCensus {

    /** First off-screen Y: sprites with {@code y >= 0xEF} never render. */
    private static final int OFFSCREEN_Y = 0xEF;

    private final int[] bytes;

    private OamCensus(int[] bytes) {
        this.bytes = bytes;
    }

    /** Snapshot the PPU's real OAM. */
    static OamCensus of(PPU ppu) {
        int[] bytes = new int[256];
        for (int i = 0; i < 256; i++) {
            bytes[i] = ppu.readOam(i) & 0xFF;
        }
        return new OamCensus(bytes);
    }

    /** Snapshot a shadow-OAM RAM page ({@code page << 8 | i}) via peeks. */
    static OamCensus ofRamPage(NesHarness h, int page) {
        int base = (page & 0xFF) << 8;
        int[] bytes = new int[256];
        for (int i = 0; i < 256; i++) {
            bytes[i] = h.peek(base + i);
        }
        return new OamCensus(bytes);
    }

    /** Count of sprite slots (0-63) with on-screen Y ({@code y < 0xEF}). */
    public int liveSprites() {
        int count = 0;
        for (int slot = 0; slot < 64; slot++) {
            if (y(slot) < OFFSCREEN_Y) {
                count++;
            }
        }
        return count;
    }

    public int y(int slot) {
        return bytes[slot * 4];
    }

    public int tile(int slot) {
        return bytes[slot * 4 + 1];
    }

    public int attr(int slot) {
        return bytes[slot * 4 + 2];
    }

    public int x(int slot) {
        return bytes[slot * 4 + 3];
    }

    /**
     * Assert some live sprite uses {@code tile} with X in
     * {@code [xLo, xHi]} (inclusive). Throws plain {@link AssertionError}
     * (D8 — no JUnit dependency).
     */
    public void assertSpriteAt(int tile, int xLo, int xHi) {
        for (int slot = 0; slot < 64; slot++) {
            if (y(slot) < OFFSCREEN_Y && tile(slot) == tile
                    && x(slot) >= xLo && x(slot) <= xHi) {
                return;
            }
        }
        throw new AssertionError(String.format(
                "no live sprite with tile $%02X (0x%02X) and x in [%d, %d]; %d live sprites: %s",
                tile, tile, xLo, xHi, liveSprites(), summary()));
    }

    /** One-line duty summary of live sprites: {@code slot(tile@x,y)} entries. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < 64; slot++) {
            if (y(slot) < OFFSCREEN_Y) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(slot).append("($")
                        .append(Integer.toHexString(tile(slot)).toUpperCase())
                        .append('@').append(x(slot)).append(',').append(y(slot))
                        .append(')');
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}
