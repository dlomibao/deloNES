package net.lomibao.nes;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.CPUBus;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.FullAddressRam;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import net.lomibao.nes.components.ppu.ColorPalette;

import java.io.File;
import java.net.URISyntaxException;

@Log4j2
public class NesEmulator extends ApplicationAdapter {
    public static final boolean ENABLE_TEST_RAM=false;//test ram covers the whole address range and gets top priority if enabled
    public static final boolean ENABLE_TEST_CARTRIDGE=true;
    SpriteBatch batch;
    Texture img;
	BitmapFont font;

    CPUBus cpuBus;

    public void setup(){
        cpuBus = CPUBus.builder()
                .cpu(new CPU6502())
                .testRam(ENABLE_TEST_RAM?new FullAddressRam():null)//test ram covers full address range and gets top priority for reading/writing to an address
                .ram(new Ram())
                .ppu(new PPU())
                .build()
                .connect();

        log.info(cpuBus);
        ColorPalette colorPalette=new ColorPalette(this.getClass().getResourceAsStream("/palettes/ntscpalette.pal"),"ntscpalette.pal");

    }
    public void loadTestProgram(){
        String testProgram="A2 0A 8E 00 00 A2 03 8E 01 00 AC 00 00 A9 00 18 6D 01 00 88 D0 FA 8D 02 00 EA EA EA";//multiplies 3*10
        FullAddressRam testRam= cpuBus.getTestRam();
        testRam.writeRange(0x8000,hexStringtoByteArray(testProgram));//write to address 8000
        //reset vector
        testRam.cpuBusWrite(0xFFFC, (byte) 0x00);
        testRam.cpuBusWrite(0xFFFD, (byte) 0x80);
        CPU6502 cpu= cpuBus.getCpu();
        cpu.reset();
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<127;i++){
            sb.setLength(0);
            log.info(i);
            log.info(cpu);
            log.info("memory 0x0000");
            log.info(testRam.getHexRange(0,16,16));
            log.info("memory 0x8000");
            log.info(testRam.getHexRange(0x8000,32,16));
            log.info("\n");
            cpu.clock();
        }




    }
    public static byte[] hexStringtoByteArray(String hexString){
        String[] hexVals=hexString.split(" ");
        byte[] byteArray=new byte[hexVals.length];
        for(int i=0;i<hexVals.length;i++){
            byteArray[i]=(byte)Integer.parseInt(hexVals[i],16);
        }
        return byteArray;
    }

    @Override
    public void create() {
        setup();
        if(ENABLE_TEST_RAM) {
            loadTestProgram();
        }
        if(ENABLE_TEST_CARTRIDGE){
            try {
                cpuBus.setCartridge(new Cartridge(this.getClass().getResourceAsStream("/nestest.nes"),"nestest.nes"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }



        batch = new SpriteBatch();
        img = new Texture("badlogic.jpg");
		//font = new BitmapFont(Gdx.files.internal("SpaceMono-Regular.ttf"),false);
        //font.getData().setScale(0.2f);
        //FreeTypeFontGenerator generator= new FreeType
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.internal("SpaceMono-Regular.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        parameter.size = 24; // Font size
        parameter.color = Color.WHITE; // Font color
        font = generator.generateFont(parameter);
        generator.dispose();


    }

    public int i = 0;

    @Override
    public void render() {
        i++;
        //ScreenUtils.clear(1, 0, 0, .1f);
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.begin();
        //batch.draw(img, i / 10, 0);
		font.draw(batch,"hello",100,200);
        batch.end();


    }

    @Override
    public void dispose() {
        batch.dispose();
        img.dispose();
    }
}
