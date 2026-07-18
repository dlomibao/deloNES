package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MapperTestSupportTest {

    @Test
    void magic_firstFourBytesAreNES1A() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        assertArrayEquals(new byte[]{0x4E, 0x45, 0x53, 0x1A},
                Arrays.copyOfRange(rom, 0, 4));
    }

    @Test
    void prgKB_writtenAsBankCountInByte4() {
        // 32KB = 2 banks of 16KB
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 32, 8, null, null);
        assertEquals(2, rom[4] & 0xFF);
    }

    @Test
    void chrKB_writtenAsBankCountInByte5() {
        // 16KB CHR = 2 banks of 8KB
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 16, null, null);
        assertEquals(2, rom[5] & 0xFF);
    }

    @Test
    void chrKB_zero_writesZeroBanksAndOmitsCHRSection() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(2, 16, 0, null, null);
        assertEquals(0, rom[5] & 0xFF);
        assertEquals(16 + 16 * 1024, rom.length);
    }

    @Test
    void mapperId_splitAcrossFlags6And7Nibbles() {
        // Mapper 0xA4 → low nibble 0x4 to flags6 high, high nibble 0xA to flags7 high
        byte[] rom = MapperTestSupport.buildSyntheticROM(0xA4, 16, 8, null, null);
        assertEquals(0x40, rom[6] & 0xFF, "flags6 high nibble = mapper low 4 bits");
        assertEquals(0xA0, rom[7] & 0xFF, "flags7 high nibble = mapper high 4 bits");
    }

    @Test
    void mapperId_roundTripsViaINESHeader() {
        // Use a mapper we already support to keep the assertion strict.
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        INESHeader header = new INESHeader(Arrays.copyOfRange(rom, 0, 16));
        assertEquals(0, header.getMapperNumber());
        assertEquals(1, header.getPRGROMSize());
        assertEquals(1, header.getCHRROMSize());
        assertTrue(header.isHorizontalMirroring(), "flags6 bit 0 clear = horizontal per iNES spec");
        assertFalse(header.hasBatteryBackedRAM());
        assertFalse(header.hasTrainer());
        assertFalse(header.isFourScreenVRAM());
        assertFalse(header.isNES2Format());
    }

    @Test
    void mapperId_64_roundTripsViaINESHeader() {
        // Mapper 64 = 0x40 ⇒ bits split: 0x0 in flags6 high, 0x4 in flags7 high.
        byte[] rom = MapperTestSupport.buildSyntheticROM(64, 16, 8, null, null);
        INESHeader header = new INESHeader(Arrays.copyOfRange(rom, 0, 16));
        assertEquals(64, header.getMapperNumber());
    }

    @Test
    void prgSeed_repeatsThroughPRGSection() {
        byte[] seed = {0x11, 0x22, 0x33};
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 0, seed, null);
        // First byte of PRG (offset 16) should be seed[0]
        assertEquals(0x11, rom[16] & 0xFF);
        assertEquals(0x22, rom[17] & 0xFF);
        assertEquals(0x33, rom[18] & 0xFF);
        assertEquals(0x11, rom[19] & 0xFF, "seed wraps");
        // Last byte of PRG: 16KB - 1 = offset 16 + 16384 - 1 = 16399; (16384-1) % 3 == 0
        assertEquals(0x11, rom[16 + 16 * 1024 - 1] & 0xFF);
    }

    @Test
    void chrSeed_repeatsThroughCHRSection() {
        byte[] chrSeed = {0x55, (byte) 0xAA};
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, chrSeed);
        int chrStart = 16 + 16 * 1024;
        assertEquals(0x55, rom[chrStart] & 0xFF);
        assertEquals(0xAA, rom[chrStart + 1] & 0xFF);
        assertEquals(0x55, rom[chrStart + 2] & 0xFF);
    }

    @Test
    void nullPrgSeed_leavesPRGSectionZero() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        for (int i = 16; i < 16 + 16 * 1024; i++) {
            assertEquals(0, rom[i], "PRG byte " + i + " should be zero");
        }
    }

    @Test
    void emptyPrgSeed_treatedAsNull_leavesZero() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 0, new byte[0], null);
        for (int i = 16; i < 16 + 16 * 1024; i++) {
            assertEquals(0, rom[i]);
        }
    }

    @Test
    void totalLength_isHeaderPlusPRGPlusCHR() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 32, 16, null, null);
        assertEquals(16 + 32 * 1024 + 16 * 1024, rom.length);
    }

    @Test
    void invalid_prgKBZero_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MapperTestSupport.buildSyntheticROM(0, 0, 0, null, null));
    }

    @Test
    void invalid_prgKBNotMultipleOf16_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MapperTestSupport.buildSyntheticROM(0, 24, 0, null, null));
    }

    @Test
    void invalid_chrKBNegative_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MapperTestSupport.buildSyntheticROM(0, 16, -8, null, null));
    }

    @Test
    void invalid_chrKBNotMultipleOf8_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MapperTestSupport.buildSyntheticROM(0, 16, 4, null, null));
    }

    @Test
    void invalid_mapperIdOutOfRange_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MapperTestSupport.buildSyntheticROM(256, 16, 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> MapperTestSupport.buildSyntheticROM(-1, 16, 0, null, null));
    }
}
