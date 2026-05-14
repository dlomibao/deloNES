package net.lomibao.nes.components;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.rom.mapper.INESHeader;
import net.lomibao.nes.rom.mapper.Mapper;
import net.lomibao.nes.rom.mapper.Mapper000;
import net.lomibao.nes.rom.mapper.MapperAxROM;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

@Log4j2
public class Cartridge extends CPUBusComponent {
    // The CPU only goes to the cartridge for addresses 0x4020 to 0xFFFF, and the
    // PPU only goes to the cartridge for addresses 0x0000 to 0x3EFF
    int CPU_START_ADDRESS = 0x4020;
    int CPU_END_ADDRESS = 0xFFFF;// inclusive

    byte[] data = null;
    String fileName;
    public INESHeader header;
    private boolean bImageValid = false;
    private int nPRGBanks = 0;
    private int nCHRBanks = 0;
    private Mapper mapper = null;
    private byte[] vPRGMemory;
    private byte[] vCHRMemory;

    public static final int HEADER_SIZE = 16;
    public static final int TRAINER_SIZE = 512;

    @SneakyThrows
    public Cartridge(InputStream inputStream, String name) {
        fileName = name;
        try {
            data = toByteArray(inputStream);
            log.info("loaded {}. read {} bytes", fileName, data.length);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        header = new INESHeader(Arrays.copyOfRange(data, 0, HEADER_SIZE));
        log.info("bytes[0:4]=" + new String(Arrays.copyOfRange(header.getHeaderBytes(), 0, 4), "UTF-8"));
        log.info("PRG ROM Size: " + header.getPRGROMSize() + " x 16 KB");
        log.info("CHR ROM Size: " + header.getCHRROMSize() + " x 8 KB");
        log.info("Horizontal Mirroring: " + header.isHorizontalMirroring());
        log.info("Battery Backed RAM: " + header.hasBatteryBackedRAM());
        log.info("Trainer: " + header.hasTrainer());
        log.info("Four Screen VRAM: " + header.isFourScreenVRAM());
        log.info("Mapper Number: " + header.getMapperNumber());
        log.info("VS Unisystem: " + header.isVSUnisystem());
        log.info("PlayChoice-10: " + header.isPlayChoice10());
        log.info("NES 2.0 Format: " + header.isNES2Format());
        log.info("PRG RAM Size: " + header.getPRGRAMSize() + " x 8 KB");
        log.info("PAL: " + header.isPAL());
        log.info("PRG RAM Present: " + header.hasPRGRAMPresent());
        int offset = HEADER_SIZE;
        if (header.hasTrainer()) {
            // skip trainer section for now
            offset += TRAINER_SIZE;
        }

        int fileType = (header.getFlags7() & 0x0C) == 0x08 ? 2 : 1;

        if (fileType == 1) {
            nPRGBanks = header.getSizeOfPRGRom();
            int vPRGSize = nPRGBanks * 16384;
            vPRGMemory = Arrays.copyOfRange(data, offset, offset + vPRGSize);
            offset += vPRGSize;
            nCHRBanks = header.getCHRROMSize();
            int vCHRSize = nCHRBanks == 0 ? 8192 : nCHRBanks * 8192;
            vCHRMemory = Arrays.copyOfRange(data, offset, offset + vCHRSize);
        } else if (fileType == 2) {
            // Todo complete

        }

        int mapperType = header.getMapperNumber();
        switch (mapperType) {
            case 0:
                mapper = new Mapper000(nPRGBanks, nCHRBanks);
                break;
            case 1:
                break;
            case 2:
                break;
            case 3:
                break;
            case 4:
                break;
            case 7:
                mapper = new MapperAxROM(nPRGBanks, nCHRBanks);
                break;
            case 66:
                break;
        }

    }

    public static byte[] toByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int bytesRead;
        byte[] data = new byte[1024];
        while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    @Override
    public int getCPUBusStartAddress() {
        return CPU_START_ADDRESS;
    }

    @Override
    public int getCPUBusEndAddress() {
        return CPU_END_ADDRESS + 1; // to get exclusive
    }

    @Override
    public void cpuBusWrite(int address, byte value) {
        // Phase B3 (Mapper 7 / AxROM): forward the byte value to the
        // mapper so register-write mappers (AxROM, UxROM, CNROM, MMC1,
        // MMC3, ...) can latch the bank-select / control bits. The
        // default Mapper interface implementation of the value-carrying
        // overload delegates to the no-value version, so Mapper000 and
        // other PRG-write-only mappers are unaffected.
        int mappedAddress = mapper.cpuMapWrite(address, Byte.toUnsignedInt(value));
        if (mappedAddress >= 0) {
            vPRGMemory[mappedAddress] = value;
        }
    }

    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        int mappedAddress = mapper.cpuMapRead(address);
        if (mappedAddress >= 0) {
            return Byte.toUnsignedInt(vPRGMemory[mappedAddress]);
        }
        return 0;
    }

    @Override
    public int cpuBusRead(int address) {
        return cpuBusRead(address, false);
    }

    /**
     * Returns the raw CHR ROM memory
     * @return CHR ROM byte array (8KB per bank)
     */
    public byte[] getCHRROM() {
        return vCHRMemory;
    }

    /**
     * Returns the number of CHR banks
     * @return number of 8KB CHR banks
     */
    public int getCHRBanks() {
        return nCHRBanks;
    }

    /**
     * Reads a single byte from CHR ROM using PPU address space
     * @param address PPU address (0x0000-0x1FFF)
     * @return byte value at address, or 0 if out of bounds
     */
    public int chrRead(int address) {
        if (vCHRMemory == null || address < 0 || address >= vCHRMemory.length) {
            return 0;
        }
        return Byte.toUnsignedInt(vCHRMemory[address]);
    }

    /**
     * Writes a byte to CHR memory using PPU address space. The mapper
     * decides whether the write lands (CHR-RAM carts: yes; CHR-ROM
     * carts: no — {@link Mapper#UNMAPPED} short-circuits the write).
     *
     * @param address PPU address (typically $0000-$1FFF); negative or
     *                out-of-range addresses are silently ignored
     * @param value   byte to write
     */
    public void chrWrite(int address, byte value) {
        if (vCHRMemory == null || mapper == null) {
            return;
        }
        int mappedAddress = mapper.ppuMapWrite(address);
        if (mappedAddress >= 0 && mappedAddress < vCHRMemory.length) {
            vCHRMemory[mappedAddress] = value;
        }
    }
    
    /**
     * Gets whether this cartridge uses horizontal mirroring
     * @return true if horizontal mirroring, false if vertical
     */
    public boolean isHorizontalMirroring() {
        return header.isHorizontalMirroring();
    }

    /**
     * Resolves the cartridge's <em>effective</em> nametable mirroring
     * mode. Mappers that don't switch mirroring at runtime return
     * {@link Mapper.Mirror#HARDWARE}; this method then falls back to
     * the iNES header bit. Mappers that do switch (MMC1, AxROM, MMC3,
     * UNROM-512) return one of the concrete variants and this method
     * passes it through unchanged.
     *
     * <p>Called per nametable access by {@code NameTableMemory}. If
     * profiling shows the virtual dispatch cost matters, cache the
     * result for a frame in {@code PPU.clock()}.
     */
    public Mapper.Mirror getMirrorMode() {
        Mapper.Mirror m = mapper == null ? Mapper.Mirror.HARDWARE : mapper.mirror();
        if (m == Mapper.Mirror.HARDWARE) {
            return header.isHorizontalMirroring()
                    ? Mapper.Mirror.HORIZONTAL
                    : Mapper.Mirror.VERTICAL;
        }
        return m;
    }

    /**
     * Forwards a PPU A12 transition (any PPU bus address change) to
     * the mapper. Called by {@link PPUBus} on every read/write. The
     * default mapper implementation is a no-op; MMC3 overrides to
     * clock its scanline IRQ counter. Phase A3 hook.
     *
     * @param address          new PPU bus address (14-bit)
     * @param previousAddress  prior PPU bus address (14-bit)
     */
    public void notifyPpuA12(int address, int previousAddress) {
        if (mapper != null) {
            mapper.tickPpuA12(address, previousAddress);
        }
    }

}
