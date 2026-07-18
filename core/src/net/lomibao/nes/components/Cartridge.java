package net.lomibao.nes.components;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.rom.mapper.INESHeader;
import net.lomibao.nes.rom.mapper.Mapper;
import net.lomibao.nes.rom.mapper.Mapper000;
import net.lomibao.nes.rom.mapper.MapperAxROM;
import net.lomibao.nes.rom.mapper.MapperCNROM;
import net.lomibao.nes.rom.mapper.MapperMMC1;
import net.lomibao.nes.rom.mapper.MapperMMC3;
import net.lomibao.nes.rom.mapper.MapperUNROM512;
import net.lomibao.nes.rom.mapper.MapperUxROM;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;

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

    /**
     * Seam S1 (docs/apu-plan.md): dedicated 8KB PRG-RAM backing
     * $6000-$7FFF. Zero-filled at construction — consistent with the
     * pinned zero-RAM boot determinism. {@code vPRGMemory} is sized
     * exactly to ROM and is never the backing store for RAM; mappers are
     * not consulted for this window. Flat always-present 8KB (battery /
     * header-declared sizes are out of scope) — enough for the blargg
     * $6000 result protocol and matches Family-Basic-style NROM.
     */
    private final byte[] prgRam = new byte[8192];

    /** First CPU address of the PRG-RAM window (inclusive). */
    private static final int PRG_RAM_START = 0x6000;
    /** Last CPU address of the PRG-RAM window (inclusive). */
    private static final int PRG_RAM_END = 0x7FFF;

    public static final int HEADER_SIZE = 16;
    public static final int TRAINER_SIZE = 512;

    /**
     * Mappers this Cartridge can construct. Single source of truth — the
     * construction switch below and the desktop pre-flight validator
     * ({@code iNESHeaderValidator}) both consult it, so the two can't drift.
     */
    public static final Set<Integer> SUPPORTED_MAPPERS = Set.of(0, 1, 2, 3, 4, 7, 30);

    public static boolean isMapperSupported(int mapperNumber) {
        return SUPPORTED_MAPPERS.contains(mapperNumber);
    }

    /** Sorted, comma-separated {@link #SUPPORTED_MAPPERS} for error messages. */
    public static String supportedMapperList() {
        return SUPPORTED_MAPPERS.stream().sorted()
                .map(String::valueOf)
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    @SneakyThrows
    public Cartridge(InputStream inputStream, String name) {
        fileName = name;
        try {
            data = toByteArray(inputStream);
            log.info("loaded {}. read {} bytes", fileName, data.length);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (data.length < HEADER_SIZE) {
            throw new RuntimeException("ROM file is " + data.length
                    + " bytes — shorter than the " + HEADER_SIZE + "-byte iNES header");
        }
        header = new INESHeader(Arrays.copyOfRange(data, 0, HEADER_SIZE));
        log.info("bytes[0:4]=" + new String(Arrays.copyOfRange(header.getHeaderBytes(), 0, 4), "UTF-8"));
        log.info("NES 2.0 Format: " + header.isNES2Format());
        log.info("PRG ROM Size: " + header.getPRGROMSizeBytes() + " bytes");
        log.info("CHR ROM Size: " + header.getCHRROMSizeBytes() + " bytes");
        log.info("Horizontal Mirroring: " + header.isHorizontalMirroring());
        log.info("Battery Backed RAM: " + header.hasBatteryBackedRAM());
        log.info("Trainer: " + header.hasTrainer());
        log.info("Four Screen VRAM: " + header.isFourScreenVRAM());
        log.info("Mapper Number: " + header.getMapperNumber()
                + " (submapper " + header.getSubmapper() + ")");
        log.info("Console Type: " + header.getConsoleType());
        log.info("TV Timing: " + header.getTimingMode());
        log.info("PRG RAM/NVRAM: " + header.getPRGRAMSizeBytes()
                + "/" + header.getPRGNVRAMSizeBytes() + " bytes");
        log.info("CHR RAM/NVRAM: " + header.getCHRRAMSizeBytes()
                + "/" + header.getCHRNVRAMSizeBytes() + " bytes");
        log.info("Misc ROM count: " + header.getMiscRomCount());

        // VS System / PlayChoice-10 / extended consoles use different
        // PPU/palette/IO hardware — loading them can only render garbage.
        // Gate here (not just in the desktop validator) because the web
        // drag-drop path constructs a Cartridge directly. (DECISIONS.md D4)
        if (header.getConsoleType() != 0) {
            throw new RuntimeException("Unsupported console type "
                    + header.getConsoleType()
                    + " (VS System / PlayChoice-10 / extended) — only NES/Famicom ROMs run");
        }
        if (header.getTimingMode() != INESHeader.TvTiming.NTSC) {
            log.warn("ROM declares {} timing; this emulator runs NTSC timing only",
                    header.getTimingMode());
        }

        int offset = HEADER_SIZE;
        if (header.hasTrainer()) {
            // skip trainer section for now
            offset += TRAINER_SIZE;
        }

        // Unified iNES 1.0 / NES 2.0 load path: the header accessors return
        // byte-exact sizes for either format, so nothing below branches on it.
        // Note Arrays.copyOfRange does NOT throw when to > data.length — it
        // silently zero-pads — so a truncated file must be caught explicitly
        // or it emulates garbage with no signal. (DECISIONS.md D9)
        int vPRGSize = header.getPRGROMSizeBytes();
        requireRemaining(offset, vPRGSize, "PRG-ROM");
        nPRGBanks = vPRGSize / 16384;
        vPRGMemory = Arrays.copyOfRange(data, offset, offset + vPRGSize);
        offset += vPRGSize;

        int chrRomSize = header.getCHRROMSizeBytes();
        nCHRBanks = chrRomSize / 8192;

        // Construct the mapper BEFORE allocating vCHRMemory so we can
        // consult mapper.getChrRamSize() for CHR-RAM carts (nCHRBanks == 0).
        // Default is 8KB; UNROM-512 overrides to 32KB (4 × 8KB banks).
        int mapperType = header.getMapperNumber();
        switch (mapperType) {
            case 0:
                mapper = new Mapper000(nPRGBanks, nCHRBanks);
                break;
            case 1:
                mapper = new MapperMMC1(nPRGBanks, nCHRBanks);
                break;
            case 2:
                mapper = new MapperUxROM(nPRGBanks, nCHRBanks);
                break;
            case 3:
                mapper = new MapperCNROM(nPRGBanks, nCHRBanks);
                break;
            case 4:
                mapper = new MapperMMC3(nPRGBanks, nCHRBanks);
                break;
            case 7:
                mapper = new MapperAxROM(nPRGBanks, nCHRBanks);
                break;
            case 30:
                mapper = new MapperUNROM512(nPRGBanks, nCHRBanks);
                break;
        }
        // Fail fast: cpuBusRead/cpuBusWrite dereference the mapper on every
        // CPU cycle, so a null mapper NPEs at the first reset-vector fetch
        // anyway — better a clear error at load time. (DECISIONS.md D8)
        if (mapper == null) {
            throw new RuntimeException("Unsupported mapper " + mapperType
                    + " (supported: " + supportedMapperList() + ")");
        }

        if (chrRomSize > 0) {
            requireRemaining(offset, chrRomSize, "CHR-ROM");
            vCHRMemory = Arrays.copyOfRange(data, offset, offset + chrRomSize);
        } else {
            // CHR-RAM cart. NES 2.0 byte 11 declares the size; never
            // under-allocate below what the mapper's banking addresses
            // (UNROM-512 assumes 32KB) — max of the two. (DECISIONS.md D3)
            int headerChrRam = header.getCHRRAMSizeBytes();
            int mapperChrRam = mapper.getChrRamSize();
            if (headerChrRam > 0 && headerChrRam != mapperChrRam) {
                log.warn("Header declares {} bytes CHR-RAM but mapper {} expects {} — allocating the larger",
                        headerChrRam, mapperType, mapperChrRam);
            }
            vCHRMemory = new byte[Math.max(headerChrRam, mapperChrRam)];
        }
    }

    /**
     * Verifies {@code count} bytes exist at {@code offset} in the ROM file.
     *
     * @throws RuntimeException naming the section and both sizes if not
     */
    private void requireRemaining(int offset, int count, String section) {
        if ((long) offset + count > data.length) {
            throw new RuntimeException("Truncated ROM: file is " + data.length
                    + " bytes but the header declares " + section
                    + " through byte " + ((long) offset + count));
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
        // Seam S1: the $6000-$7FFF PRG-RAM window is handled AHEAD of the
        // mapper call — mappers only ever produce vPRGMemory offsets and
        // are never consulted for PRG-RAM.
        if (address >= PRG_RAM_START && address <= PRG_RAM_END) {
            prgRam[address & 0x1FFF] = value;
            return;
        }
        // Use the value-aware overload so register-latching mappers
        // (UxROM, CNROM, AxROM, MMC1, MMC3, UNROM-512) can capture the
        // byte being written. The default Mapper implementation
        // delegates to the address-only form, so NROM is unaffected.
        int mappedAddress = mapper.cpuMapWrite(address, Byte.toUnsignedInt(value));
        if (mappedAddress >= 0) {
            vPRGMemory[mappedAddress] = value;
        }
        // UNMAPPED writes are silently dropped here by design — the
        // "no device" error line lives in CPUBus.write, not the cartridge
        // (S1 logging semantics; add a debug-level log if diagnosis ever
        // needs it, no behavior depends on one).
    }

    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        // Seam S1: PRG-RAM window ahead of the mapper (see cpuBusWrite).
        if (address >= PRG_RAM_START && address <= PRG_RAM_END) {
            return Byte.toUnsignedInt(prgRam[address & 0x1FFF]);
        }
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
     * Reads a single byte from CHR ROM using PPU address space. Routes
     * through {@link Mapper#ppuMapRead(int)} so CHR-banking mappers
     * (CNROM, MMC1, MMC3, AxROM, UNROM-512) see the post-translation
     * offset into {@code vCHRMemory}. Mapper000's ppuMapRead is a
     * pass-through, preserving the original direct-index behavior.
     *
     * @param address PPU address (0x0000-0x1FFF)
     * @return byte value at address, or 0 if out of bounds / unmapped
     */
    public int chrRead(int address) {
        if (vCHRMemory == null) {
            return 0;
        }
        int mappedAddress = mapper == null ? address : mapper.ppuMapRead(address);
        if (mappedAddress < 0 || mappedAddress >= vCHRMemory.length) {
            return 0;
        }
        return Byte.toUnsignedInt(vCHRMemory[mappedAddress]);
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

    /**
     * True when the mapper is asserting its IRQ line (MMC3 scanline
     * counter). Polled by {@code NesSystem.tick()} to drive
     * {@code CPU6502.irq()}; mappers without an IRQ source always
     * report false.
     */
    public boolean mapperIrqPending() {
        return mapper != null && mapper.reqState();
    }

    /** Deasserts the mapper's IRQ line after the CPU has taken the IRQ. */
    public void mapperIrqClear() {
        if (mapper != null) {
            mapper.irqClear();
        }
    }

}
