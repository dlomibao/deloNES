package net.lomibao.nes.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity checks for the extracted NES master palette. Guards against the
 * "someone re-edited the table and dropped/added an entry" regression that
 * would silently break the desktop renderers.
 */
class NesMasterPaletteTest {

    @Test
    void paletteHasExactly64Entries() {
        assertEquals(64, NesMasterPalette.ARGB.length,
                "NES master palette must be 64 entries (6-bit color index)");
    }

    @Test
    void everyEntryHasFullAlpha() {
        for (int i = 0; i < NesMasterPalette.ARGB.length; i++) {
            int alpha = (NesMasterPalette.ARGB[i] >>> 24) & 0xFF;
            assertEquals(0xFF, alpha,
                    "palette entry " + i + " (0x" + Integer.toHexString(NesMasterPalette.ARGB[i])
                            + ") must have full alpha");
        }
    }

    @Test
    void argbMasksIndexToLow6Bits() {
        // 0x40 wraps to 0x00, 0x7F wraps to 0x3F — exercises the & 0x3F.
        assertEquals(NesMasterPalette.ARGB[0], NesMasterPalette.argb(0x40));
        assertEquals(NesMasterPalette.ARGB[0x3F], NesMasterPalette.argb(0x7F));
        assertEquals(NesMasterPalette.ARGB[0x3F], NesMasterPalette.argb(-1));
    }

    @Test
    void firstAndLastEntriesMatchExpectedColors() {
        // Anchors so accidental table edits trip a loud failure.
        assertEquals(0xFF7C7C7C, NesMasterPalette.ARGB[0]);
        assertEquals(0xFF000000, NesMasterPalette.ARGB[63]);
        assertTrue(NesMasterPalette.argb(0) == NesMasterPalette.ARGB[0]);
    }
}
