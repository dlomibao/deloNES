package net.lomibao.nes.components;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.rom.mapper.INESHeader;
import net.lomibao.nes.rom.mapper.Mapper;
import net.lomibao.nes.rom.mapper.Mapper000;

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
        Integer mappedAddress = mapper.cpuMapWrite(address);
        if (mappedAddress != null) {
            vPRGMemory[mappedAddress] = value;
        }
    }

    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        Integer mappedAddress = mapper.cpuMapRead(address);
        if (mappedAddress != null) {
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
     * Gets whether this cartridge uses horizontal mirroring
     * @return true if horizontal mirroring, false if vertical
     */
    public boolean isHorizontalMirroring() {
        return header.isHorizontalMirroring();
    }

}
