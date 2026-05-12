package net.lomibao.nes.render;

/**
 * NES master color palette (64 entries) in {@code 0xAARRGGBB} ARGB format.
 *
 * <p>The NES PPU produces 6-bit color indices (0..63) which are mapped to
 * RGB by a fixed lookup table baked into the console's analog output.
 * This array is the canonical palette used across the desktop renderers
 * ({@code EmulatorScreen}, {@code NestestBackgroundRenderer},
 * {@code DKDiagnosticRunner}). All entries have full alpha (0xFF).
 *
 * <p>Index this with {@code ARGB[nesColorIndex & 0x3F]}.
 */
public final class NesMasterPalette {

    private NesMasterPalette() {}

    /**
     * 64-entry NES color palette in ARGB (0xAARRGGBB). Full alpha throughout.
     * Treat as read-only — do not mutate.
     */
    public static final int[] ARGB = {
        0xFF7C7C7C, 0xFF0000FC, 0xFF0000BC, 0xFF4428BC, 0xFF940084, 0xFFA80020, 0xFFA81000, 0xFF881400,
        0xFF503000, 0xFF007800, 0xFF006800, 0xFF005800, 0xFF004058, 0xFF000000, 0xFF000000, 0xFF000000,
        0xFFBCBCBC, 0xFF0078F8, 0xFF0058F8, 0xFF6844FC, 0xFFD800CC, 0xFFE40058, 0xFFF83800, 0xFFE45C10,
        0xFFAC7C00, 0xFF00B800, 0xFF00A800, 0xFF00A844, 0xFF008888, 0xFF000000, 0xFF000000, 0xFF000000,
        0xFFF8F8F8, 0xFF3CBCFC, 0xFF6888FC, 0xFF9878F8, 0xFFF878F8, 0xFFF85898, 0xFFF87858, 0xFFFCA044,
        0xFFF8B800, 0xFFB8F818, 0xFF58D854, 0xFF58F898, 0xFF00E8D8, 0xFF787878, 0xFF000000, 0xFF000000,
        0xFFFCFCFC, 0xFFA4E4FC, 0xFFB8B8F8, 0xFFD8B8F8, 0xFFF8B8F8, 0xFFF8A4C0, 0xFFF0D0B0, 0xFFFCE0A8,
        0xFFF8D878, 0xFFD8F878, 0xFFB8F8B8, 0xFFB8F8D8, 0xFF00FCFC, 0xFFF8D8F8, 0xFF000000, 0xFF000000
    };

    /**
     * Looks up an ARGB color for a NES color index. The index is masked to
     * the low 6 bits ({@code & 0x3F}) so callers can pass raw palette-RAM
     * bytes without pre-masking.
     *
     * @param nesColorIndex NES palette index; only the low 6 bits are used
     * @return ARGB color (0xAARRGGBB) with full alpha
     */
    public static int argb(int nesColorIndex) {
        return ARGB[nesColorIndex & 0x3F];
    }
}
