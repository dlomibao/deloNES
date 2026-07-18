package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.Mapper;
import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link Cartridge} + {@link
 * net.lomibao.nes.rom.mapper.MapperUNROM512} (iNES mapper 30).
 *
 * <p>Validates the full Cartridge wiring:
 * <ul>
 *   <li>case 30 in the mapper-construction switch picks MapperUNROM512</li>
 *   <li>{@code mapper.getChrRamSize()} is consulted at allocation time
 *       so the CHR-RAM allocation is 32KB (NOT the default 8KB)</li>
 *   <li>CPU writes through {@code cpuBusWrite} latch the bank register</li>
 *   <li>{@code chrRead}/{@code chrWrite} route through {@code
 *       mapper.ppuMapRead}/{@code ppuMapWrite} so the active CHR bank
 *       is observable through Cartridge's public CHR access methods</li>
 * </ul>
 *
 * <p>The synthetic ROM is 128KB PRG (8 × 16KB banks, Micro Mages size),
 * CHR-RAM cart (chrKB = 0). Each PRG bank gets a signature byte at
 * offset 0 so {@code cpuBusRead($8000)} reveals the active bank.
 */
class CartridgeMapperUNROM512Test {

    /**
     * Builds a 128KB UNROM-512 image where the byte at PRG-offset
     * {@code bank * 0x4000} is set to {@code 0xB0 | bank}. Every other
     * byte in PRG is {@code 0xEE}.
     */
    private static byte[] buildUNROM512Image() {
        int prgKB = 128;                       // 8 × 16KB
        int prgBytes = prgKB * 1024;
        byte[] seed = new byte[prgBytes];
        for (int i = 0; i < prgBytes; i++) {
            seed[i] = (byte) 0xEE;
        }
        for (int bank = 0; bank < 8; bank++) {
            seed[bank * 0x4000] = (byte) (0xB0 | bank);
        }
        // chrKB == 0 → CHR-RAM cart; iNES header reports zero CHR banks.
        return MapperTestSupport.buildSyntheticROM(30, prgKB, 0, seed, null);
    }

    private static Cartridge buildCartridge() {
        byte[] rom = buildUNROM512Image();
        return new Cartridge(new ByteArrayInputStream(rom), "synthetic-unrom512.nes");
    }

    // ---- Mapper construction (case 30) ----

    @Test
    void powerOn_readsBank0Signature_atLowWindow() {
        Cartridge cart = buildCartridge();
        assertEquals(0xB0, cart.cpuBusRead(0x8000),
                "power-on bank is 0, so $8000 should read bank 0's signature");
    }

    @Test
    void powerOn_readsLastBankSignature_atHighWindow() {
        Cartridge cart = buildCartridge();
        // $C000 is fixed to the LAST bank (index 7) → signature 0xB7.
        assertEquals(0xB7, cart.cpuBusRead(0xC000),
                "high window must always read from the LAST bank");
    }

    // ---- PRG bank switching ----

    @Test
    void writeBank3_swapsLowWindow_highStaysAtLast() {
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x03);
        assertEquals(0xB3, cart.cpuBusRead(0x8000),
                "after writing 0x03, $8000 should read bank 3's signature");
        assertEquals(0xB7, cart.cpuBusRead(0xC000),
                "high window must remain on the last bank after a bank switch");
    }

    @Test
    void writeAtAnyHighAddress_selectsRegister() {
        Cartridge cart = buildCartridge();
        // The register hits anywhere in $8000-$FFFF.
        cart.cpuBusWrite(0xFFFF, (byte) 0x05);
        assertEquals(0xB5, cart.cpuBusRead(0x8000));
    }

    @Test
    void cpuWrite_doesNotCorruptPRGMemory() {
        // UNROM-512 PRG is mask-ROM; register writes must NOT also land
        // in vPRGMemory.
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x01);
        assertEquals(0xB1, cart.cpuBusRead(0x8000));
        cart.cpuBusWrite(0x8000, (byte) 0x00);
        assertEquals(0xB0, cart.cpuBusRead(0x8000));
    }

    // ---- 32KB CHR-RAM allocation (option (c) — getChrRamSize override) ----

    @Test
    void chrRAM_allocationIs32KB_notDefault8KB() {
        // The Cartridge constructor must consult mapper.getChrRamSize()
        // when allocating CHR-RAM. UNROM-512 overrides to 32KB; without
        // that, only 8KB would be allocated and the test below would
        // silently fail (writes wrapping or zeroing out).
        Cartridge cart = buildCartridge();
        assertEquals(32 * 1024, cart.getCHRROM().length,
                "Cartridge must allocate 32KB CHR-RAM for UNROM-512");
    }

    @Test
    void chrWrite_bank0_thenRead_returnsByte() {
        Cartridge cart = buildCartridge();
        // Power-on: CHR bank 0.
        cart.chrWrite(0x0000, (byte) 0xC0);
        cart.chrWrite(0x1FFF, (byte) 0xCF);
        assertEquals(0xC0, cart.chrRead(0x0000));
        assertEquals(0xCF, cart.chrRead(0x1FFF));
    }

    @Test
    void chrBankSwitch_preservesPerBankBytes() {
        // The acid test for 32KB CHR-RAM with bank switching: write a
        // distinct byte to PPU $0000 in each of the 4 banks, then
        // switch back through all 4 banks and assert each one's byte
        // is independent — proves the 32KB allocation is real and the
        // bank-translated offset is computed correctly.
        Cartridge cart = buildCartridge();
        // Write 0xD0..0xD3 to PPU $0000 in CHR banks 0..3.
        for (int bank = 0; bank < 4; bank++) {
            cart.cpuBusWrite(0x8000, (byte) (bank << 5));  // bits 5-6 → CHR bank
            cart.chrWrite(0x0000, (byte) (0xD0 | bank));
        }
        // Read them back in the same order.
        for (int bank = 0; bank < 4; bank++) {
            cart.cpuBusWrite(0x8000, (byte) (bank << 5));
            assertEquals(0xD0 | bank, cart.chrRead(0x0000),
                    "CHR bank " + bank + " should have its own independent byte");
        }
    }

    // ---- Mirroring (one-screen with select) ----

    @Test
    void powerOn_mirrorIsONESCREEN_LO() {
        Cartridge cart = buildCartridge();
        assertEquals(Mapper.Mirror.ONESCREEN_LO, cart.getMirrorMode(),
                "UNROM-512 defaults to ONESCREEN_LO on power-on");
    }

    @Test
    void writeBit7_switchesMirrorToONESCREEN_HI() {
        Cartridge cart = buildCartridge();
        cart.cpuBusWrite(0x8000, (byte) 0x80);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, cart.getMirrorMode());
    }
}
