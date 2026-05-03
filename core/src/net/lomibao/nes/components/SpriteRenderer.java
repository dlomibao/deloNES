package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MVP sprite renderer (Step 5 of the playable-gen1 plan).
 *
 * <p>Composites all 64 OAM sprites onto the PPU's background framebuffer
 * in a single post-render pass at scanline 240. Implements the bugzmanov
 * ch. 6.5 / olcNES §3 sprite rules:
 *
 * <ul>
 *   <li>OAM is iterated <strong>in reverse</strong> (sprite 63 → sprite 0)
 *       so lower-indexed sprites end up on top.</li>
 *   <li>Pattern pixel value 0 = transparent (skipped — does not overwrite
 *       background or other sprites).</li>
 *   <li>OAM byte 0 is "Y - 1" — the sprite is drawn one scanline LOWER
 *       than the value indicates.</li>
 *   <li>Attribute byte: bits 0-1 = sprite palette (0..3 → palette RAM
 *       0x10..0x1F); bit 5 = priority (0 = front, 1 = behind background);
 *       bit 6 = horizontal flip; bit 7 = vertical flip.</li>
 *   <li>8x16 mode (PPUCTRL bit 5): tile-id bit 0 selects pattern table
 *       ($0000 if 0, $1000 if 1) — overrides PPUCTRL bit 3. Tile-id bits
 *       1-7 indicate the top tile; bottom tile is the next index.</li>
 *   <li>PPUMASK bit 4 gates the whole pass; bit 2 clips sprites in the
 *       leftmost 8 columns.</li>
 *   <li>Per-pixel priority: when bit 5 = 1 (sprite behind), the sprite
 *       is hidden where the bg pattern pixel is non-zero (opaque). Where
 *       bg is transparent (pattern pixel == 0), the sprite always wins.</li>
 * </ul>
 *
 * <h2>What this MVP does NOT do (future work)</h2>
 * <ul>
 *   <li>No per-scanline 8-sprite limit (real PPU drops sprite 9+ on a
 *       scanline; we render all 64 every frame).</li>
 *   <li>No proper sprite-0 hit (Step 6).</li>
 *   <li>No secondary OAM evaluation timing.</li>
 *   <li>No grayscale/emphasis bits applied to sprites.</li>
 * </ul>
 */
@Log4j2
final class SpriteRenderer {

    /**
     * Guards the "no PPU bus wired" warning so we log once per JVM
     * lifetime instead of 60 times per second when sprites are enabled
     * but CHR isn't reachable.
     */
    private static final AtomicBoolean NO_BUS_WARNED = new AtomicBoolean(false);

    private SpriteRenderer() {}

    /**
     * Composite all 64 sprites from PPU OAM onto the PPU framebuffer.
     * Reads pattern bytes via {@code ppu.ppuBus()} (the cartridge's CHR
     * ROM/RAM) and palette colors via {@code ppu.getPaletteColorRgb()}.
     *
     * @param ppu the PPU whose OAM, palette RAM, framebuffer, and bg
     *            pattern shadow are read/written
     */
    static void render(PPU ppu) {
        if (!ppu.isShowSprites()) {
            // PPUMASK bit 4 disabled — skip sprite pass entirely.
            return;
        }
        byte[] oam = ppu.oam();
        boolean is8x16 = ppu.getSpriteSize() == 16;
        int spriteHeight = is8x16 ? 16 : 8;
        int defaultPatternBase = ppu.getSpritePatternTableAddress();
        boolean showLeft8 = ppu.isShowSpritesLeft();
        PPUBus bus = ppu.ppuBus();
        if (bus == null) {
            // PPU has no PPUBus wired — typically a wiring bug rather than a
            // legitimate state. Log once so the missing connection surfaces
            // in CI/dev logs without spamming 60 lines/second.
            if (NO_BUS_WARNED.compareAndSet(false, true)) {
                log.warn("SpriteRenderer: PPU has no PPUBus wired; sprites will not render. " +
                        "Did you forget to call PPU.connectPPUBus(...)?");
            }
            return;
        }

        // Reverse iteration so lower OAM index wins z-order.
        for (int spriteIdx = 63; spriteIdx >= 0; spriteIdx--) {
            int oamBase = spriteIdx * 4;
            int spriteY = Byte.toUnsignedInt(oam[oamBase]) + 1; // OAM Y is "top - 1"
            int tileId = Byte.toUnsignedInt(oam[oamBase + 1]);
            int attr = Byte.toUnsignedInt(oam[oamBase + 2]);
            int spriteX = Byte.toUnsignedInt(oam[oamBase + 3]);

            int paletteIdx = attr & 0x03;       // sprite palette 0..3 → RAM $10/$14/$18/$1C
            boolean priorityBehind = (attr & 0x20) != 0;
            boolean hflip = (attr & 0x40) != 0;
            boolean vflip = (attr & 0x80) != 0;

            // Resolve pattern table + tile-in-table for this sprite. In
            // 8x16 mode tile-id bit 0 picks the table (NOT PPUCTRL bit 3),
            // and the top tile is at (tileId & 0xFE).
            int patternTableBase;
            int topTileId;
            if (is8x16) {
                patternTableBase = (tileId & 0x01) != 0 ? 0x1000 : 0x0000;
                topTileId = tileId & 0xFE;
            } else {
                patternTableBase = defaultPatternBase;
                topTileId = tileId;
            }

            // Walk every row of the sprite. Off-screen rows are skipped.
            for (int row = 0; row < spriteHeight; row++) {
                int screenY = spriteY + row;
                if (screenY < 0 || screenY >= PPU.VISIBLE_HEIGHT) continue;

                int rowInSprite = vflip ? (spriteHeight - 1 - row) : row;
                int tileForRow;
                int rowInTile;
                if (is8x16 && rowInSprite >= 8) {
                    tileForRow = topTileId + 1;
                    rowInTile = rowInSprite - 8;
                } else {
                    tileForRow = topTileId;
                    rowInTile = rowInSprite;
                }
                int patternAddr = patternTableBase + tileForRow * 16 + rowInTile;
                int patternLow = bus.read(patternAddr);
                int patternHigh = bus.read(patternAddr + 8);

                // Walk the 8 columns of this row.
                for (int col = 0; col < 8; col++) {
                    int screenX = spriteX + col;
                    if (screenX < 0 || screenX >= PPU.VISIBLE_WIDTH) continue;
                    if (screenX < 8 && !showLeft8) continue;

                    int colInTile = hflip ? col : (7 - col);
                    int p0 = (patternLow >> colInTile) & 0x01;
                    int p1 = (patternHigh >> colInTile) & 0x01;
                    int patternPixel = (p1 << 1) | p0;
                    if (patternPixel == 0) continue; // sprite-transparent

                    // Priority: if sprite-behind AND bg is opaque here, bg wins.
                    if (priorityBehind && ppu.getBgPatternPixel(screenY, screenX) != 0) {
                        continue;
                    }

                    int paletteRamIdx = 0x10 + (paletteIdx * 4) + patternPixel;
                    int color = ppu.getPaletteColorRgb(paletteRamIdx);
                    ppu.putScreenPixel(screenX, screenY, color);
                }
            }
        }
    }
}
