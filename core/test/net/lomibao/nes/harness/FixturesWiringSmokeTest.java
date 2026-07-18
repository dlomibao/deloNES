package net.lomibao.nes.harness;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A0 smoke test — proves the {@code java-test-fixtures} source set is
 * wired: a fixtures-tier class ({@link TestRoms}) is importable from
 * {@code core/test}, sees core's main resources on its classpath, and can
 * throw the opentest4j skip exception (D8).
 */
class FixturesWiringSmokeTest {

    @Test
    void resourceBytes_loadsNestestWithInesMagic() {
        byte[] rom = TestRoms.resourceBytes("/nestest.nes");
        assertTrue(rom.length > 16, "nestest.nes should be non-trivial");
        assertEquals((byte) 0x4E, rom[0]); // 'N'
        assertEquals((byte) 0x45, rom[1]); // 'E'
        assertEquals((byte) 0x53, rom[2]); // 'S'
        assertEquals((byte) 0x1A, rom[3]);
    }

    @Test
    void resourceBytes_missingResourceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TestRoms.resourceBytes("/no-such-resource.bin"));
    }

    @Test
    void opcodeCsv_streamOpens() throws Exception {
        try (InputStream in = TestRoms.opcodeCsv()) {
            assertNotNull(in);
            assertTrue(in.read() >= 0, "opcodes.csv should have content");
        }
    }

    @Test
    void realRomBytesOrSkip_absentRomThrowsTestAborted() {
        assertThrows(TestAbortedException.class,
                () -> TestRoms.realRomBytesOrSkip("definitely-not-present-2f8a1c.nes"));
    }
}
