package net.lomibao.nes;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.ScreenUtils;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.Bus;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
@Log4j2
public class NesEmulator extends ApplicationAdapter {
    SpriteBatch batch;
    Texture img;
	BitmapFont font;

    Bus bus;

    public void setup(){
        bus=Bus.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(new PPU())
                .build()
                .connect();
        log.info(bus);

    }

    @Override
    public void create() {
        setup();



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
