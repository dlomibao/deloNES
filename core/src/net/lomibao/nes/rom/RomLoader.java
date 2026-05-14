package net.lomibao.nes.rom;

import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.PPUBus;
import net.lomibao.nes.components.Ram;
import net.lomibao.nes.components.ppu.NameTableMemory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

/**
 * Hosts-agnostic helper that builds a fully-wired {@link NesSystem} from a
 * raw iNES ROM byte[]. Centralises the duplicate "construct CPU + PPU + RAM +
 * bus + cartridge + reset" boilerplate that lived in {@code HtmlLauncher} and
 * desktop launchers, so the loading sequence can be unit-tested without
 * touching LibGDX or TeaVM. Phase F's browser ROM picker reuses this from the
 * web host to hot-swap the running emulator at runtime.
 *
 * <p>This class never throws checked exceptions: an {@link IllegalArgumentException}
 * is raised for null / obviously-malformed input, and a {@link RuntimeException}
 * (wrapping the underlying cause) for unsupported mappers or iNES parse errors.
 * Hosts should catch {@link RuntimeException} and fall back to whatever they
 * use when no ROM is available (the web build renders a gradient).
 */
public final class RomLoader {

    /** Minimum size for a syntactically valid iNES file: 16-byte header + 1 PRG bank. */
    private static final int MIN_INES_SIZE = 16 + 16384;

    /**
     * Result of a successful load — the wired {@link NesSystem} plus the
     * already-constructed components the host wants to keep references to
     * (CPU for PC reads, PPU for framebuffer access, Controller for key
     * mapping, Cartridge for header/mapper inspection).
     *
     * <p>Returning a struct (rather than just the {@code NesSystem}) avoids
     * making the host call back into NesSystem.getCpuBus().getCartridge()
     * etc.: those wires are private/Lombok-only and the host already needs
     * dedicated references to the controller for input mapping.
     */
    public static final class Loaded {
        public final NesSystem nes;
        public final CPU6502 cpu;
        public final PPU ppu;
        public final Controller controller;
        public final Cartridge cartridge;

        Loaded(NesSystem nes, CPU6502 cpu, PPU ppu, Controller controller, Cartridge cartridge) {
            this.nes = nes;
            this.cpu = cpu;
            this.ppu = ppu;
            this.controller = controller;
            this.cartridge = cartridge;
        }
    }

    private RomLoader() {
        // utility class
    }

    /**
     * Build a fully-wired NesSystem from an iNES byte[] and an opcode CSV
     * {@link InputStream}. Both args are required.
     *
     * <p>The caller is responsible for closing the CSV stream — this method
     * hands the stream to {@link CPU6502#CPU6502(InputStream)} which closes
     * it after parsing.
     *
     * @param romBytes the raw iNES file bytes (header + PRG + optional CHR)
     * @param romName  human-readable identifier used in cartridge log lines
     * @param opcodeCsv stream over the {@code opcodes.csv} resource bytes
     * @return a {@link Loaded} struct holding the wired system and component refs
     * @throws IllegalArgumentException if {@code romBytes} is null/too small
     *         or {@code opcodeCsv} is null
     * @throws RuntimeException wrapping any error encountered constructing
     *         the cartridge (iNES parse failure, unsupported mapper, etc.)
     */
    public static Loaded loadFromBytes(byte[] romBytes, String romName, InputStream opcodeCsv) {
        Objects.requireNonNull(opcodeCsv, "opcodeCsv InputStream is required");
        if (romBytes == null) {
            throw new IllegalArgumentException("romBytes is null — pick a non-empty .nes file");
        }
        if (romBytes.length < MIN_INES_SIZE) {
            throw new IllegalArgumentException(
                    "ROM is only " + romBytes.length + " bytes; iNES needs >= "
                    + MIN_INES_SIZE + " (16-byte header + at least one 16 KB PRG bank)");
        }
        // Optional magic-byte check. Cartridge will happily parse anything,
        // so detect "user picked something that isn't a NES rom" up-front
        // and surface a usable error message.
        if (!(romBytes[0] == (byte) 0x4E
                && romBytes[1] == (byte) 0x45
                && romBytes[2] == (byte) 0x53
                && romBytes[3] == (byte) 0x1A)) {
            throw new IllegalArgumentException(
                    "ROM does not start with iNES magic 'NES\\x1A' — "
                    + "header[0..3]=0x"
                    + Integer.toHexString(romBytes[0] & 0xff) + " "
                    + Integer.toHexString(romBytes[1] & 0xff) + " "
                    + Integer.toHexString(romBytes[2] & 0xff) + " "
                    + Integer.toHexString(romBytes[3] & 0xff));
        }

        Cartridge cart;
        try {
            cart = new Cartridge(new ByteArrayInputStream(romBytes),
                    romName == null ? "<unnamed>.nes" : romName);
        } catch (RuntimeException e) {
            // Cartridge constructor uses @SneakyThrows + reads the input stream;
            // surface failures as RuntimeException with the original message
            // intact so hosts can show something useful.
            throw new RuntimeException("Failed to parse iNES ROM: " + e.getMessage(), e);
        }

        PPU ppu = new PPU();
        PPUBus ppuBus = new PPUBus();
        NameTableMemory nameTableMemory = new NameTableMemory();
        ppuBus.connect(nameTableMemory);
        ppu.connectPPUBus(ppuBus);

        CPU6502 cpu = new CPU6502(opcodeCsv);
        Ram ram = new Ram();
        Controller controller = new Controller();

        NesSystem nes = NesSystem.builder()
                .cpu(cpu).ram(ram).ppu(ppu)
                .apu(new APU())
                .controller(controller)
                .dma(new DmaController())
                .build();

        nes.getCpuBus().setCartridge(cart);
        ppu.setCartridge(cart);
        ppuBus.connectCartridge(cart);
        ppuBus.connectPPU(ppu);

        cpu.reset();
        ppu.reset();

        // Seed a default palette + enable BG rendering so the canvas shows
        // something sensible before the cart programs the PPU itself. This
        // mirrors the EmulatorScreen.initializeTestPattern shim on desktop;
        // most carts overwrite these within their first NMI handler.
        ppu.cpuBusWrite(0x2001, (byte) 0x08);
        ppu.cpuBusWrite(0x2006, (byte) 0x3F);
        ppu.cpuBusWrite(0x2006, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x0F);
        ppu.cpuBusWrite(0x2007, (byte) 0x00);
        ppu.cpuBusWrite(0x2007, (byte) 0x10);
        ppu.cpuBusWrite(0x2007, (byte) 0x30);

        return new Loaded(nes, cpu, ppu, controller, cart);
    }
}
