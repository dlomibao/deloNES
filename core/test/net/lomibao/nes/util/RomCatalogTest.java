package net.lomibao.nes.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RomCatalog}.  The roms/index.txt manifest must contain at
 * least {@code nestest.nes} for these assertions to pass.
 */
public class RomCatalogTest {

    @Test
    void listRoms_containsNestest() {
        List<String> roms = RomCatalog.listRoms();
        assertNotNull(roms, "listRoms() must not return null");
        assertTrue(roms.contains("nestest.nes"),
                "Expected nestest.nes in catalog but got: " + roms);
    }

    @Test
    void listRoms_isNotEmpty() {
        List<String> roms = RomCatalog.listRoms();
        assertFalse(roms.isEmpty(), "ROM catalog must not be empty");
    }

    @Test
    void openRom_nestestIsReadable() throws IOException {
        try (InputStream in = RomCatalog.openRom("nestest.nes")) {
            assertNotNull(in, "openRom must return a non-null stream for nestest.nes");
            // iNES magic bytes: 0x4E 0x45 0x53 0x1A ("NES\x1A")
            byte[] header = new byte[4];
            int read = in.read(header);
            assertEquals(4, read, "Should read 4 bytes from nestest.nes header");
            assertEquals(0x4E, Byte.toUnsignedInt(header[0]), "Byte 0 should be 'N' (0x4E)");
            assertEquals(0x45, Byte.toUnsignedInt(header[1]), "Byte 1 should be 'E' (0x45)");
            assertEquals(0x53, Byte.toUnsignedInt(header[2]), "Byte 2 should be 'S' (0x53)");
            assertEquals(0x1A, Byte.toUnsignedInt(header[3]), "Byte 3 should be 0x1A (iNES magic)");
        }
    }

    @Test
    void openRom_missingRomThrows() {
        assertThrows(RomCatalog.RomCatalogException.class,
                () -> RomCatalog.openRom("nonexistent_rom_that_does_not_exist.nes"),
                "Opening a missing ROM should throw RomCatalogException");
    }

    @Test
    void listRoms_returnsUnmodifiableList() {
        List<String> roms = RomCatalog.listRoms();
        assertThrows(UnsupportedOperationException.class,
                () -> roms.add("fake.nes"),
                "listRoms() should return an unmodifiable list");
    }
}
