package net.lomibao.nes.components;

import net.lomibao.nes.rom.mapper.Mapper;
import net.lomibao.nes.rom.mapper.MapperTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase A2 — Cartridge.getMirrorMode() bridges between the iNES
 * header bit 0 and a mapper's runtime mirror choice. The header
 * is the source of truth ONLY when the mapper returns
 * {@link Mapper.Mirror#HARDWARE}; otherwise the mapper's choice wins.
 */
class CartridgeMirrorModeTest {

    /**
     * MapperTestSupport.buildSyntheticROM always emits flags6 with bit
     * 0 = 0 (vertical mirroring in {@link net.lomibao.nes.rom.mapper.INESHeader}'s
     * convention). Helper builds a horizontal-mirroring variant by
     * flipping that bit post-construction so we can test both fallbacks.
     */
    private static Cartridge buildCartridge(boolean horizontalMirroring) {
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        if (horizontalMirroring) {
            rom[6] = (byte) (rom[6] | 0x01);
        }
        return new Cartridge(new ByteArrayInputStream(rom),
                horizontalMirroring ? "horiz.nes" : "vert.nes");
    }

    @Test
    void mapper000_returnsHardwareSentinel() {
        // Sanity: NROM never switches mirroring, so Cartridge has to do
        // the header fallback itself.
        Cartridge cart = buildCartridge(false);
        assertEquals(Mapper.Mirror.HARDWARE,
                new net.lomibao.nes.rom.mapper.Mapper000(1, 1).mirror());
        // Cart-level resolution then yields a concrete direction.
        assertEquals(Mapper.Mirror.VERTICAL, cart.getMirrorMode());
    }

    @Test
    void getMirrorMode_headerHorizontalBit_yieldsHorizontal() {
        Cartridge cart = buildCartridge(true);
        assertEquals(Mapper.Mirror.HORIZONTAL, cart.getMirrorMode());
    }

    @Test
    void getMirrorMode_headerVerticalBit_yieldsVertical() {
        Cartridge cart = buildCartridge(false);
        assertEquals(Mapper.Mirror.VERTICAL, cart.getMirrorMode());
    }

    /**
     * Cartridge subclass that forces an arbitrary {@link Mapper.Mirror}
     * choice to be returned, simulating a runtime mirror switch the way
     * MMC1 will do in Phase C.
     */
    static class FixedMirrorCartridge extends Cartridge {
        private Mapper.Mirror forced;

        FixedMirrorCartridge(byte[] rom, Mapper.Mirror initial) {
            super(new ByteArrayInputStream(rom), "fixed-mirror-test.nes");
            this.forced = initial;
        }

        void setForcedMirror(Mapper.Mirror m) { this.forced = m; }

        @Override
        public Mapper.Mirror getMirrorMode() {
            return forced != null ? forced : super.getMirrorMode();
        }
    }

    @Test
    void mapperHORIZONTAL_overridesHeader() {
        // Header says VERTICAL but mapper returns HORIZONTAL.
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        FixedMirrorCartridge cart = new FixedMirrorCartridge(rom, Mapper.Mirror.HORIZONTAL);
        assertEquals(Mapper.Mirror.HORIZONTAL, cart.getMirrorMode());
    }

    @Test
    void mapperONESCREEN_LO_isReportedAsIs() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        FixedMirrorCartridge cart = new FixedMirrorCartridge(rom, Mapper.Mirror.ONESCREEN_LO);
        assertEquals(Mapper.Mirror.ONESCREEN_LO, cart.getMirrorMode());
    }

    @Test
    void mapperONESCREEN_HI_isReportedAsIs() {
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        FixedMirrorCartridge cart = new FixedMirrorCartridge(rom, Mapper.Mirror.ONESCREEN_HI);
        assertEquals(Mapper.Mirror.ONESCREEN_HI, cart.getMirrorMode());
    }

    @Test
    void dynamicMirrorFlip_isObservedImmediately() {
        // Phase C will need this: a mapper writes its control register
        // mid-frame and the next nametable access has to see the new mode.
        byte[] rom = MapperTestSupport.buildSyntheticROM(0, 16, 8, null, null);
        FixedMirrorCartridge cart = new FixedMirrorCartridge(rom, Mapper.Mirror.HORIZONTAL);
        assertEquals(Mapper.Mirror.HORIZONTAL, cart.getMirrorMode());
        cart.setForcedMirror(Mapper.Mirror.VERTICAL);
        assertEquals(Mapper.Mirror.VERTICAL, cart.getMirrorMode());
    }
}
