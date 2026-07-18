package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.Mapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * NES 2.0 loading path through {@link Cartridge}: header-driven PRG/CHR
 * slicing, CHR-RAM sizing (DECISIONS.md D3), fail-fast gates for unsupported
 * mappers (D8) and console types (D4), and truncation detection (D9).
 *
 * <p>Synthetic ROMs are built in memory; one smoke test additionally loads
 * the real Micro Mages NROM demo dump when it is present locally (the file
 * is not committed) and is skipped otherwise.
 */
class CartridgeNes2Test {

    private static final int PRG_BANK = 16384;
    private static final int CHR_BANK = 8192;

    /**
     * Builds an in-memory ROM: 16-byte header + PRG + CHR, with recognizable
     * fill bytes so slicing can be asserted. {@code nes2} stamps byte 7
     * bits 2-3 = 0b10.
     */
    private static byte[] makeRom(int mapperNumber, boolean nes2,
                                  int prgBanks, int chrBanks) {
        byte[] rom = new byte[16 + prgBanks * PRG_BANK + chrBanks * CHR_BANK];
        rom[0] = 0x4E; rom[1] = 0x45; rom[2] = 0x53; rom[3] = 0x1A;
        rom[4] = (byte) prgBanks;
        rom[5] = (byte) chrBanks;
        rom[6] = (byte) ((mapperNumber & 0x0F) << 4);
        rom[7] = (byte) ((mapperNumber & 0xF0) | (nes2 ? 0x08 : 0x00));
        java.util.Arrays.fill(rom, 16, 16 + prgBanks * PRG_BANK, (byte) 0xAA);
        java.util.Arrays.fill(rom, 16 + prgBanks * PRG_BANK, rom.length, (byte) 0xBB);
        return rom;
    }

    private static Cartridge load(byte[] rom) {
        return new Cartridge(new ByteArrayInputStream(rom), "synthetic.nes");
    }

    /**
     * (a) NES 2.0 NROM 32K+8K — the exact shape of the Micro Mages demo
     * build's header bytes 4-7 = {@code 02 01 00 08}. Must load with mapper 0
     * and slice PRG/CHR correctly.
     */
    @Test
    void nes2_nrom32k8k_loads() {
        Cartridge cart = load(makeRom(0, true, 2, 1));
        assertTrue(cart.header.isNES2Format());
        assertEquals(0, cart.header.getMapperNumber());
        assertEquals(2 * PRG_BANK, cart.header.getPRGROMSizeBytes());
        // PRG fill byte visible through the CPU bus at $8000 (NROM maps
        // $8000 to PRG offset 0)
        assertEquals(0xAA, cart.cpuBusRead(0x8000));
        // CHR fill byte visible through chrRead
        assertEquals(0xBB, cart.chrRead(0x0000));
        assertEquals(1, cart.getCHRBanks());
    }

    /** Same content with an iNES 1.0 header still loads (regression). */
    @Test
    void ines10_nrom32k8k_stillLoads() {
        Cartridge cart = load(makeRom(0, false, 2, 1));
        assertFalse(cart.header.isNES2Format());
        assertEquals(0xAA, cart.cpuBusRead(0x8000));
        assertEquals(0xBB, cart.chrRead(0x0000));
    }

    /**
     * (b) CHR-RAM sizing: mapper 30 (UNROM-512) expects 32KB CHR-RAM. A
     * NES 2.0 header declaring 32KB (shift 9) gets exactly that; a header
     * declaring only 8KB (shift 7) must not starve the mapper — max rule.
     */
    @Test
    void nes2_mapper30_chrRamHonorsHeaderAndMapperMax() {
        byte[] rom32 = makeRom(30, true, 2, 0);
        rom32[11] = 0x09; // CHR-RAM shift 9 = 32KB
        assertEquals(32768, load(rom32).getCHRROM().length);

        byte[] rom8 = makeRom(30, true, 2, 0);
        rom8[11] = 0x07; // CHR-RAM shift 7 = 8KB < mapper's 32KB expectation
        assertEquals(32768, load(rom8).getCHRROM().length,
                "allocation must never fall below what the mapper's banking addresses");
    }

    /** A header declaring MORE CHR-RAM than the mapper default must win. */
    @Test
    void nes2_mapper0_chrRamLargerThanMapperDefault_headerWins() {
        byte[] rom = makeRom(0, true, 2, 0);
        rom[11] = 0x08; // CHR-RAM shift 8 = 16KB > NROM default 8KB
        assertEquals(16384, load(rom).getCHRROM().length);
    }

    /** (c) byte 11 = 0 with no CHR-ROM falls back to the mapper default. */
    @Test
    void nes2_chrRamUndeclared_fallsBackToMapperDefault() {
        Cartridge cart = load(makeRom(0, true, 2, 0));
        assertEquals(8192, cart.getCHRROM().length);
    }

    /** (d) Trainer flag skips 512 bytes before PRG. */
    @Test
    void trainer_skips512Bytes() {
        byte[] plain = makeRom(0, true, 2, 1);
        byte[] rom = new byte[plain.length + 512];
        System.arraycopy(plain, 0, rom, 0, 16);
        rom[6] |= 0x04; // trainer present
        java.util.Arrays.fill(rom, 16, 16 + 512, (byte) 0x77); // trainer filler
        System.arraycopy(plain, 16, rom, 16 + 512, plain.length - 16);
        Cartridge cart = load(rom);
        assertEquals(0xAA, cart.cpuBusRead(0x8000),
                "PRG must start after the 512-byte trainer, not inside it");
    }

    /**
     * (e) Truncated file: Arrays.copyOfRange would silently zero-pad, so the
     * constructor must throw a descriptive error instead (D9).
     */
    @Test
    void truncatedRom_throwsDescriptively() {
        byte[] rom = makeRom(0, true, 2, 1);
        byte[] cut = java.util.Arrays.copyOfRange(rom, 0, 16 + PRG_BANK); // half the PRG
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> load(cut));
        assertTrue(e.getMessage().contains("Truncated"), e.getMessage());
    }

    /**
     * (f) Unsupported mapper fails at construction with the mapper number and
     * the supported set in the message (D8) — not with an NPE at the first
     * CPU fetch as before.
     */
    @Test
    void unsupportedMapper_throwsAtConstruction() {
        byte[] rom = makeRom(66, false, 2, 1); // GxROM — dead stub removed
        RuntimeException e = assertThrows(RuntimeException.class, () -> load(rom));
        assertTrue(e.getMessage().contains("66"), e.getMessage());
        assertTrue(e.getMessage().contains("supported"), e.getMessage());
    }

    /** NES 2.0 12-bit mapper numbers reach the same gate. */
    @Test
    void unsupportedHighMapper_throwsAtConstruction() {
        byte[] rom = makeRom(5, true, 2, 1);
        rom[8] = 0x01; // mapper 261
        RuntimeException e = assertThrows(RuntimeException.class, () -> load(rom));
        assertTrue(e.getMessage().contains("261"), e.getMessage());
    }

    /** (g) VS System console type throws at construction (D4). */
    @Test
    void vsConsoleType_throwsAtConstruction() {
        byte[] rom = makeRom(0, true, 2, 1);
        rom[7] |= 0x01; // VS System
        RuntimeException e = assertThrows(RuntimeException.class, () -> load(rom));
        assertTrue(e.getMessage().contains("console"), e.getMessage());
    }

    /** Mirroring still resolves for a NES 2.0 cart (header bit path). */
    @Test
    void nes2_mirroring_resolves() {
        byte[] rom = makeRom(0, true, 2, 1);
        rom[6] |= 0x01; // horizontal
        assertEquals(Mapper.Mirror.HORIZONTAL, load(rom).getMirrorMode());
    }

    // ---------------------------------------------------------------------------
    // Real-ROM smoke test (skipped unless the file exists locally)
    // ---------------------------------------------------------------------------

    /**
     * Loads the real Micro Mages NROM demo dump when present. The ROM is not
     * committed (commercial aftermarket title); the test self-skips when the
     * file is absent, so CI is unaffected.
     */
    @Test
    void microMages_realRom_loadsWhenPresent() throws Exception {
        Path rom = Paths.get(System.getProperty("user.home"),
                "projects/deloNES/core/src/main/resources/roms/Micro Mages (World) (Aftermarket) (Unl).nes");
        assumeTrue(Files.exists(rom), "Micro Mages ROM not present locally — skipping");
        try (InputStream in = new FileInputStream(rom.toFile())) {
            Cartridge cart = new Cartridge(in, "micromages.nes");
            assertTrue(cart.header.isNES2Format());
            assertEquals(0, cart.header.getMapperNumber());
            assertEquals(32768, cart.header.getPRGROMSizeBytes());
            assertEquals(8192, cart.header.getCHRROMSizeBytes());
            // Reset vector must point into PRG space ($8000-$FFFF)
            int resetVector = cart.cpuBusRead(0xFFFC) | (cart.cpuBusRead(0xFFFD) << 8);
            assertTrue(resetVector >= 0x8000,
                    "reset vector 0x" + Integer.toHexString(resetVector) + " outside PRG space");
        }
    }
}
