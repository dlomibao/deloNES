package net.lomibao.nes.desktop;

import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.PPUBus;
import net.lomibao.nes.components.Ram;
import net.lomibao.nes.components.ppu.NameTableMemory;
import net.lomibao.nes.render.NesMasterPalette;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Headless diagnostic runner: loads DonkeyKong.nes, runs N frames using
 * NesSystem.runFrame(), then dumps a detailed snapshot of PPU state and the
 * rendered framebuffer.
 *
 * <p>Designed for agent-driven debugging of rendering issues — output is
 * compact, structured, and easy to grep/diff. No GL context, no LWJGL window.
 *
 * <p>Run with: {@code ./gradlew desktop:traceDK} (frames defaults to 600;
 * override with {@code -PtraceFrames=N}).
 */
public class DKDiagnosticRunner {

    public static void main(String[] args) throws Exception {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 600;

        // Wire NES system identically to EmulatorScreen.
        PPU ppu = new PPU();
        PPUBus ppuBus = new PPUBus();
        NameTableMemory nameTableMemory = new NameTableMemory();
        ppuBus.connect(nameTableMemory);
        ppu.connectPPUBus(ppuBus);

        CPU6502 cpu = new CPU6502();
        Ram ram = new Ram();
        Controller controller = new Controller();

        NesSystem nes = NesSystem.builder()
                .cpu(cpu)
                .ram(ram)
                .ppu(ppu)
                .controller(controller)
                .dma(new DmaController())
                .build();

        ppu.setCPU(cpu);

        // Load ROM. Resolution order:
        //   1. -Drom=<path>  (filesystem path; useful for ad-hoc dumps)
        //   2. /roms/DonkeyKong.nes on classpath  (DK is the runner's default
        //      target — gitignored, so users must drop it under
        //      core/src/main/resources/roms or override via -Drom=...)
        //   3. /roms/nestest.nes on classpath  (always present; lets the
        //      diagnostic harness still produce output even without DK)
        String romOverride = System.getProperty("rom");
        String romName;
        try (InputStream in = openRom(romOverride)) {
            romName = (romOverride != null) ? Paths.get(romOverride).getFileName().toString()
                    : (DKDiagnosticRunner.class.getResource("/roms/DonkeyKong.nes") != null
                            ? "DonkeyKong.nes" : "nestest.nes");
            System.out.println("=== DKDiagnosticRunner: ROM = " + romName + " ===");
            Cartridge cart = new Cartridge(in, romName);
            nes.getCpuBus().setCartridge(cart);
            ppu.setCartridge(cart);
            ppuBus.connectCartridge(cart);
            ppuBus.connectPPU(ppu);
        }

        cpu.reset();
        ppu.reset();

        System.out.println("=== DKDiagnosticRunner: running " + frames + " frames ===");
        for (int i = 0; i < frames; i++) {
            nes.runFrame();
        }
        System.out.println("=== Frame " + frames + " complete ===");

        dumpState(ppu, ppuBus, frames);
    }

    private static InputStream openRom(String override) throws Exception {
        if (override != null && !override.isEmpty()) {
            Path p = Paths.get(override);
            if (!Files.isReadable(p)) {
                throw new RuntimeException("rom override not readable: " + override);
            }
            return new FileInputStream(p.toFile());
        }
        InputStream in = DKDiagnosticRunner.class.getResourceAsStream("/roms/DonkeyKong.nes");
        if (in != null) {
            return in;
        }
        in = DKDiagnosticRunner.class.getResourceAsStream("/roms/nestest.nes");
        if (in == null) {
            throw new RuntimeException(
                "Neither /roms/DonkeyKong.nes nor /roms/nestest.nes on classpath. "
                + "Drop DonkeyKong.nes into core/src/main/resources/roms/ "
                + "or pass -Drom=<path>");
        }
        return in;
    }

    private static void dumpState(PPU ppu, PPUBus ppuBus, int frame) {
        int ctrl = ppu.peekCtrl();
        int mask = ppu.peekMask();
        int status = ppu.peekStatus();

        System.out.println();
        System.out.println("--- PPU registers @ frame " + frame + " ---");
        System.out.printf("PPUCTRL=0x%02X (NMI=%d 8x16=%d bg-pat=%s spr-pat=%s vinc=%d nt=%d)%n",
                ctrl,
                (ctrl >> 7) & 1, (ctrl >> 5) & 1,
                ((ctrl & 0x10) != 0) ? "$1000" : "$0000",
                ((ctrl & 0x08) != 0) ? "$1000" : "$0000",
                ((ctrl & 0x04) != 0) ? 32 : 1,
                ctrl & 0x03);
        System.out.printf("PPUMASK=0x%02X (bg-l8=%d spr-l8=%d bg=%d spr=%d)%n",
                mask, (mask >> 1) & 1, (mask >> 2) & 1, (mask >> 3) & 1, (mask >> 4) & 1);
        System.out.printf("PPUSTATUS=0x%02X  scrollX=%d scrollY=%d%n",
                status, ppu.getScrollX(), ppu.getScrollY());

        // --- OAM ---
        System.out.println();
        System.out.println("--- OAM (sprites with Y < 240) ---");
        int activeCount = 0;
        for (int i = 0; i < 64; i++) {
            int b = i * 4;
            int y = Byte.toUnsignedInt(ppu.readOam(b));
            if (y >= 240) continue;
            int tile = Byte.toUnsignedInt(ppu.readOam(b + 1));
            int attr = Byte.toUnsignedInt(ppu.readOam(b + 2));
            int x = Byte.toUnsignedInt(ppu.readOam(b + 3));
            System.out.printf("  sprite %2d: Y=%3d X=%3d tile=0x%02X attr=0x%02X%n",
                    i, y, x, tile, attr);
            activeCount++;
        }
        System.out.println("  (" + activeCount + " active sprites)");

        // --- Nametable summary: which rows have non-blank content ---
        System.out.println();
        System.out.println("--- Nametable $2000: rows with non-0x24 (non-blank) tiles ---");
        int blankTile = 0x24;
        for (int row = 0; row < 30; row++) {
            int rowAddr = 0x2000 + row * 32;
            int nonBlank = 0;
            for (int col = 0; col < 32; col++) {
                int tile = ppuBus.read(rowAddr + col) & 0xFF;
                if (tile != blankTile) nonBlank++;
            }
            if (nonBlank > 0) {
                StringBuilder sb = new StringBuilder(String.format("  row %2d (y=%3d-%3d, %2d non-blank): ",
                        row, row * 8, row * 8 + 7, nonBlank));
                for (int col = 0; col < 32; col++) {
                    int tile = ppuBus.read(rowAddr + col) & 0xFF;
                    sb.append(String.format("%02X ", tile));
                }
                System.out.println(sb);
            }
        }

        // --- Palette RAM ---
        System.out.println();
        System.out.println("--- Palette RAM ($3F00..$3F1F) ---");
        StringBuilder palette = new StringBuilder("  bg:  ");
        for (int i = 0; i < 16; i++) {
            palette.append(String.format("%02X ", ppu.getPaletteColor(i)));
        }
        System.out.println(palette);
        StringBuilder spritePal = new StringBuilder("  spr: ");
        for (int i = 16; i < 32; i++) {
            spritePal.append(String.format("%02X ", ppu.getPaletteColor(i)));
        }
        System.out.println(spritePal);

        // --- Framebuffer dump: leftmost 16 pixels of every 8th scanline ---
        // Renders as ASCII where:
        //   '.' = backdrop color (palette[0])
        //   '#' = non-backdrop, opaque color
        // Plus prints actual ARGB hex values for the first scanline of each row.
        System.out.println();
        System.out.println("--- Framebuffer left edge: x=0..15 at every 8th scanline ---");
        int[][] screen = ppu.getScreen();
        int backdrop = getBackdropRgba(ppu);
        System.out.printf("  (backdrop palette[0]=0x%02X → ARGB 0x%08X)%n",
                ppu.getPaletteColor(0), backdrop);
        for (int y = 0; y < 240; y += 8) {
            StringBuilder ascii = new StringBuilder();
            StringBuilder hex = new StringBuilder();
            for (int x = 0; x < 16; x++) {
                int rgba = screen[y][x];
                ascii.append(rgba == backdrop ? '.' : '#');
                hex.append(String.format("%08X ", rgba));
            }
            System.out.printf("  y=%3d: |%s|  %s%n", y, ascii, hex);
        }

        // --- Framebuffer right edge for comparison (x=240..255 at same rows) ---
        System.out.println();
        System.out.println("--- Framebuffer right edge: x=240..255 at every 8th scanline ---");
        for (int y = 0; y < 240; y += 8) {
            StringBuilder ascii = new StringBuilder();
            for (int x = 240; x < 256; x++) {
                int rgba = screen[y][x];
                ascii.append(rgba == backdrop ? '.' : '#');
            }
            System.out.printf("  y=%3d: |%s|%n", y, ascii);
        }

        // --- Full first 32 pixels of scanlines 0, 8, 16, 24, 32 (top of frame) ---
        System.out.println();
        System.out.println("--- Top-of-frame detail: x=0..31 at scanlines 0/8/16/24/32 ---");
        for (int y : new int[]{0, 8, 16, 24, 32}) {
            StringBuilder ascii = new StringBuilder();
            for (int x = 0; x < 32; x++) {
                int rgba = screen[y][x];
                ascii.append(rgba == backdrop ? '.' : '#');
            }
            System.out.printf("  y=%3d: |%s|%n", y, ascii);
        }
    }

    private static int getBackdropRgba(PPU ppu) {
        // Use the same NES master palette the PixelRenderer / EmulatorScreen uses.
        // We just need a stable value to compare against.
        return NesMasterPalette.argb(ppu.getPaletteColor(0));
    }
}
