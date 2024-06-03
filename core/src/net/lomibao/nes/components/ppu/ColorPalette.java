package net.lomibao.nes.components.ppu;

import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
@Log4j2
public class ColorPalette {
    private String paletteName;
    private int[][] palette;//[color index][0,1,2=red,green,blue]
    public ColorPalette(InputStream inputStream, String name){
        this.paletteName=name;
        load(inputStream);
    }

    public void load(InputStream inputStream){
        try {
            byte[] data = new byte[3];
            this.palette=new int[64][3];
            int idx=0;
            while (inputStream.read(data, 0, data.length) != -1) {
                for(int c=0;c<3;c++){//color channel r,g,b
                    palette[idx][c]=Byte.toUnsignedInt(data[c]);
                }
                log.info("{} :: ({},{},{})",idx,palette[idx][0],palette[idx][1],palette[idx][2]);
                idx++;
            }
        }catch (Exception e){
            log.error("failed to load palette ",e);
        }
    }
}
