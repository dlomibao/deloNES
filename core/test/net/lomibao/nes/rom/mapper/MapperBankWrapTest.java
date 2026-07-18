package net.lomibao.nes.rom.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRG bank registers wider than the cart wrap to the actual bank count —
 * hardware leaves upper address lines unwired, so an out-of-range bank
 * index wraps rather than reading past the end of PRG ROM. Without the
 * wrap, {@code Cartridge.cpuBusRead} would index {@code vPRGMemory} out of
 * bounds and crash the emulator loop (review finding, PR #33 round 1).
 */
class MapperBankWrapTest {

    private static final int PRG_16K = 0x4000;
    private static final int PRG_8K  = 0x2000;
    private static final int PRG_32K = 0x8000;

    @Test
    void uxrom_bankBeyondCartSize_wraps() {
        // 4-bank (64KB) cart; 4-bit register can address 16 banks.
        MapperUxROM m = new MapperUxROM(4, 1);
        m.cpuMapWrite(0x8000, 0x0F);  // bank 15 → 15 % 4 = 3
        assertEquals(3 * PRG_16K, m.cpuMapRead(0x8000));
    }

    @Test
    void mmc1_bankBeyondCartSize_wraps() {
        // 4-bank (64KB) cart in PRG mode 3 (power-on default).
        MapperMMC1 m = new MapperMMC1(4, 1);
        // Serial-write PRG register = 0x0F (bank 15) at $E000: LSB first.
        for (int i = 0; i < 4; i++) {
            m.cpuMapWrite(0xE000, 0x01);
        }
        m.cpuMapWrite(0xE000, 0x00);  // 5th write commits 0b01111 = 15
        // 15 % 4 = 3 at the switchable $8000 window.
        assertEquals(3 * PRG_16K, m.cpuMapRead(0x8000));
    }

    /**
     * 32KB PRG mode on a malformed odd bank count: the wrap must happen at
     * 32KB granularity, or the last (even) 16KB bank pairs with one past
     * the end of PRG ROM (round-2 review finding).
     */
    @Test
    void mmc1_32kMode_oddBankCount_staysInBounds() {
        MapperMMC1 m = new MapperMMC1(3, 1);  // 3 banks — no clean 32KB pairing
        // Serial-write control = 0 ($8000) → PRG mode 0 (32KB switchable).
        for (int i = 0; i < 5; i++) {
            m.cpuMapWrite(0x8000, 0x00);
        }
        // Serial-write PRG register = 2 ($E000): bank 2 = the odd last bank.
        int[] bits = {0, 1, 0, 0, 0};  // LSB first → 0b00010 = 2
        for (int b : bits) {
            m.cpuMapWrite(0xE000, b);
        }
        // Only one full 32KB pair exists (banks 0-1); bank 2 wraps to it.
        // The high half of the window must stay inside the 3-bank ROM.
        int offset = m.cpuMapRead(0xFFFF);
        assertTrue(offset < 3 * PRG_16K,
                "32KB window must not address past the end of a 3-bank ROM, got " + offset);
        assertEquals(0x7FFF, offset, "bank 2 wraps to pair 0 → offset $7FFF");
    }

    @Test
    void mmc3_prgBankBeyondCartSize_wraps() {
        // 2×16KB = 4 8KB banks; R6 = 0x3F wraps to 0x3F % 4 = 3.
        MapperMMC3 m = new MapperMMC3(2, 1);
        m.cpuMapWrite(0x8000, 0x06);   // select R6
        m.cpuMapWrite(0x8001, 0x3F);
        assertEquals(3 * PRG_8K, m.cpuMapRead(0x8000));
    }

    @Test
    void axrom_bankBeyondCartSize_wraps() {
        // 4×16KB = 2 32KB banks; register bank 5 wraps to 5 % 2 = 1.
        MapperAxROM m = new MapperAxROM(4, 0);
        m.cpuMapWrite(0x8000, 0x05);
        assertEquals(1 * PRG_32K, m.cpuMapRead(0x8000));
    }

    @Test
    void mmc3_chrBankBeyondCartSize_wraps() {
        // 1×8KB CHR = 8 1KB banks; R2 = 200 wraps to 200 % 8 = 0.
        MapperMMC3 m = new MapperMMC3(2, 1);
        m.cpuMapWrite(0x8000, 0x02);   // select R2
        m.cpuMapWrite(0x8001, 200);
        assertEquals(0, m.ppuMapRead(0x1000) / 0x0400);
    }

    /**
     * The reason CHR registers latch 8 bits: a 256KB CHR cart (SMB3-class)
     * has 256 1KB banks, and banks 64+ were unreachable under the old
     * 6-bit mask (review finding, PR #33 round 1).
     */
    @Test
    void mmc3_chrRegisterIs8Bit_reachesBanksAbove63() {
        MapperMMC3 m = new MapperMMC3(16, 32);  // 32×8KB = 256 1KB CHR banks
        m.cpuMapWrite(0x8000, 0x02);   // select R2 (1KB slot at $1000, invert=0)
        m.cpuMapWrite(0x8001, 200);
        assertEquals(200 * 0x0400, m.ppuMapRead(0x1000),
                "CHR registers are 8-bit — bank 200 must be reachable, not masked to 6 bits");
    }
}
