package net.lomibao.nes.rom;

import net.lomibao.nes.components.CPU6502;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RomLoader}. The browser ROM picker (Phase F) calls
 * {@link RomLoader#loadFromBytes} every time the user picks a new {@code .nes}
 * file; these tests pin the contract that loader does, on its own, what
 * {@code HtmlLauncher.setupEmulator()} previously did inline.
 *
 * <p>Synthetic ROMs are built per-test rather than loaded from disk so the
 * test suite does not depend on any specific real ROM being present and the
 * inputs stay self-documenting.
 */
class RomLoaderTest {

    /** opcodes.csv classpath resource path (same one CPU6502's no-arg ctor uses). */
    private static final String OPCODE_CSV_RESOURCE = "/opcodes/opcodes.csv";

    /**
     * Build a minimal but valid iNES 1.0 NROM (mapper 0) byte[]:
     * 16-byte header + (prgBanks * 16 KB) PRG + (chrBanks * 8 KB) CHR.
     *
     * <p>The PRG is zero-filled except for the reset vector at $FFFC/$FFFD,
     * which we point at $C000 (start of the second 16K PRG window in the
     * mapped CPU address space — Mapper000 mirrors a single 16K PRG bank
     * into both halves so $C000 is always valid even for 1-bank carts).
     */
    private static byte[] buildSyntheticNROM(int prgBanks, int chrBanks) {
        if (prgBanks <= 0) {
            throw new IllegalArgumentException("prgBanks must be >= 1");
        }
        int prgSize = prgBanks * 16384;
        int chrSize = chrBanks * 8192;
        byte[] rom = new byte[16 + prgSize + chrSize];
        rom[0] = (byte) 'N';
        rom[1] = (byte) 'E';
        rom[2] = (byte) 'S';
        rom[3] = (byte) 0x1A;
        rom[4] = (byte) prgBanks;
        rom[5] = (byte) chrBanks;
        // Flags 6/7 = 0 ⇒ mapper 0, horizontal-only off ⇒ vertical mirroring.

        // Reset vector: PRG window is mapped to $8000-$FFFF; for 1-bank NROM
        // $C000-$FFFF mirrors $8000-$BFFF, so offset $3FFC inside the PRG bank
        // is $FFFC in CPU address space. Point reset vector at $C000.
        int resetVectorOffset = 16 + prgSize - 4; // last 4 bytes of last PRG bank
        rom[resetVectorOffset] = (byte) 0x00;     // PCL
        rom[resetVectorOffset + 1] = (byte) 0xC0; // PCH
        // NMI + IRQ vectors zeroed; CPU never reaches them in these tests.
        return rom;
    }

    private static InputStream opcodeCsv() {
        InputStream in = CPU6502.class.getResourceAsStream(OPCODE_CSV_RESOURCE);
        assertNotNull(in, "opcodes.csv classpath resource missing");
        return in;
    }

    // ---------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------

    @Test
    void load_minimalNROM_returnsWiredSystem() {
        byte[] rom = buildSyntheticNROM(1, 1);

        RomLoader.Loaded loaded = RomLoader.loadFromBytes(rom, "synthetic.nes", opcodeCsv());

        assertNotNull(loaded, "Loaded struct must not be null");
        assertNotNull(loaded.nes, "NesSystem must be wired");
        assertNotNull(loaded.cpu, "CPU must be wired");
        assertNotNull(loaded.ppu, "PPU must be wired");
        assertNotNull(loaded.controller, "Controller must be wired");
        assertNotNull(loaded.cartridge, "Cartridge must be parsed");
    }

    @Test
    void load_minimalNROM_cartridgeHeaderReflectsBuiltInput() {
        byte[] rom = buildSyntheticNROM(2, 1);
        RomLoader.Loaded loaded = RomLoader.loadFromBytes(rom, "synthetic-2prg.nes", opcodeCsv());
        assertEquals(0, loaded.cartridge.header.getMapperNumber(),
                "Mapper 0 is the only one set by buildSyntheticNROM");
        assertEquals(2, loaded.cartridge.header.getPRGROMSize(),
                "PRG bank count must round-trip through cartridge.header");
        assertEquals(1, loaded.cartridge.header.getCHRROMSize());
    }

    @Test
    void load_minimalNROM_resetVectorIsFollowed() {
        // Reset vector points at $C000; after cpu.reset() PC should equal $C000.
        byte[] rom = buildSyntheticNROM(1, 1);
        RomLoader.Loaded loaded = RomLoader.loadFromBytes(rom, "synthetic-rv.nes", opcodeCsv());
        assertEquals(0xC000, loaded.cpu.getPc(),
                "CPU.reset() must follow the cartridge's $FFFC/$FFFD reset vector");
    }

    @Test
    void load_minimalNROM_canRunOneFrameWithoutThrowing() {
        // Smoke test: the cartridge is wired such that the CPU bus, PPU bus,
        // and master clock can actually step. We don't care what the screen
        // shows — only that 89,342 master ticks complete without an NPE.
        byte[] rom = buildSyntheticNROM(1, 1);
        RomLoader.Loaded loaded = RomLoader.loadFromBytes(rom, "synthetic-frame.nes", opcodeCsv());
        // Reset vector at $C000 lands in unwritten PRG, which Mapper000 reads
        // as 0x00 == BRK. BRK pushes return addr + flags then jumps via IRQ
        // vector at $FFFE/$FFFF (also 0x0000) — an infinite-loop pattern,
        // but each iteration is a real instruction so the clock advances.
        loaded.nes.runFrame();
        assertTrue(loaded.nes.getMasterClockCount() > 0,
                "Master clock must advance during runFrame()");
    }

    @Test
    void load_zeroChrBanks_stillBuildsCartridge() {
        // NROM with CHR-RAM (chrBanks == 0) is legal; the cartridge code
        // allocates an 8KB CHR-RAM window in that case.
        byte[] rom = buildSyntheticNROM(1, 0);
        RomLoader.Loaded loaded = RomLoader.loadFromBytes(rom, "chr-ram.nes", opcodeCsv());
        assertNotNull(loaded.cartridge);
        assertEquals(0, loaded.cartridge.header.getCHRROMSize());
    }

    // ---------------------------------------------------------------------------
    // Validation errors
    // ---------------------------------------------------------------------------

    @Test
    void load_nullBytes_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RomLoader.loadFromBytes(null, "null.nes", opcodeCsv()));
        assertTrue(ex.getMessage().contains("null"), "Error must mention null bytes");
    }

    @Test
    void load_tooSmall_throwsIllegalArgument() {
        byte[] tooSmall = new byte[100]; // not even a full PRG bank
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RomLoader.loadFromBytes(tooSmall, "tiny.nes", opcodeCsv()));
        assertTrue(ex.getMessage().contains("16-byte header")
                        || ex.getMessage().toLowerCase().contains("ines"),
                "Error must explain the iNES minimum size");
    }

    @Test
    void load_badMagic_throwsIllegalArgument() {
        byte[] rom = buildSyntheticNROM(1, 1);
        rom[0] = (byte) 'X'; // breaks the magic
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RomLoader.loadFromBytes(rom, "bad-magic.nes", opcodeCsv()));
        assertTrue(ex.getMessage().contains("iNES magic"),
                "Error must call out the magic-byte mismatch");
    }

    @Test
    void load_nullOpcodeStream_throwsNpe() {
        byte[] rom = buildSyntheticNROM(1, 1);
        assertThrows(NullPointerException.class,
                () -> RomLoader.loadFromBytes(rom, "no-csv.nes", null));
    }

    @Test
    void load_nullName_isToleratedAndDefaultsApplied() {
        // Null ROM name is a defensive case — surface as a usable placeholder
        // instead of NPE inside the cartridge log line.
        byte[] rom = buildSyntheticNROM(1, 1);
        RomLoader.Loaded loaded = RomLoader.loadFromBytes(rom, null, opcodeCsv());
        assertNotNull(loaded);
        assertNotNull(loaded.cartridge);
    }

    @Test
    void load_twiceInARow_returnsIndependentInstances() {
        // Phase F: the picker calls loadFromBytes repeatedly. Each load must
        // build a fresh NesSystem — no shared mutable state would otherwise
        // leak state across switches.
        byte[] rom = buildSyntheticNROM(1, 1);
        RomLoader.Loaded a = RomLoader.loadFromBytes(rom, "a.nes", opcodeCsv());
        RomLoader.Loaded b = RomLoader.loadFromBytes(rom, "b.nes", opcodeCsv());
        assertTrue(a.nes != b.nes, "Each load must produce a distinct NesSystem");
        assertTrue(a.cpu != b.cpu, "Each load must produce a distinct CPU");
        assertTrue(a.ppu != b.ppu, "Each load must produce a distinct PPU");
        assertTrue(a.cartridge != b.cartridge, "Each load must produce a distinct Cartridge");
    }
}
