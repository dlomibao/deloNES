package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.rom.mapper.INESHeader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

@Log4j2
public class Cartridge extends CPUBusComponent {
    //The CPU only goes to the cartridge for addresses 0x4020 to 0xFFFF, and the PPU only goes to the cartridge for addresses 0x0000 to 0x3EFF
    int CPU_START_ADDRESS=0x4020;
    int CPU_END_ADDRESS=0xFFFF;//inclusive

    byte[] data=null;
    String fileName;
    INESHeader header;
    public Cartridge(InputStream inputStream,String name){
        fileName=name;
        try {
            data=toByteArray(inputStream);
            log.info("loaded {}. read {} bytes",fileName,data.length);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        header=new INESHeader(Arrays.copyOfRange(data,0,16));
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
    public Cartridge(File file){
        this.fileName=file.getName();
        this.data=new byte[(int)file.length()];
        try(BufferedInputStream is=new BufferedInputStream(new FileInputStream(file))){
            int size=is.read(data);
            log.info("loaded {}. read {} bytes",fileName,size);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        header=new INESHeader(Arrays.copyOfRange(data,0,16));
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
    }
    public Cartridge(String fileName){
        this(new File(fileName));

        
    }





    @Override
    public int getCPUBusStartAddress() {
        return CPU_START_ADDRESS;
    }

    @Override
    public int getCPUBusEndAddress() {
        return CPU_END_ADDRESS+1;//to get exclusive
    }



}
