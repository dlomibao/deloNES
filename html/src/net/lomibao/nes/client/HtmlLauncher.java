package net.lomibao.nes.client;

import com.badlogic.gdx.ApplicationListener;
import com.github.xpenatan.gdx.backends.teavm.TeaApplication;
import com.github.xpenatan.gdx.backends.teavm.TeaApplicationConfiguration;
import net.lomibao.nes.NesEmulator;

public class HtmlLauncher {

        public static void main(String[] args) {
                TeaApplicationConfiguration config = new TeaApplicationConfiguration("canvas");
                
                // Use fixed size for initial testing
                config.width = 640;
                config.height = 480;
                
                // Use WebGL 2.0 if available
                config.useGL30 = true;
                
                // Create the application
                new TeaApplication(new NesEmulator(), config);
        }
}
