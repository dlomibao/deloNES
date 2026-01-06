package net.lomibao.nes.client;

import com.badlogic.gdx.ApplicationListener;
import com.github.xpenatan.gdx.backends.teavm.TeaApplication;
import com.github.xpenatan.gdx.backends.teavm.TeaApplicationConfiguration;
import net.lomibao.nes.render.NestestBackgroundRenderer;

/**
 * TeaVM HTML launcher for the NES Background Renderer
 * Runs nestest.nes ROM and displays the rendered background using PPU
 * This is the web/browser equivalent of NestestBackgroundLauncher
 */
public class NestestHtmlLauncher {

        public static void main(String[] args) {
                TeaApplicationConfiguration config = new TeaApplicationConfiguration("canvas");
                
                // Fixed size for nestest rendering
                config.width = 640;
                config.height = 600;
                
                // Use WebGL 2.0 if available
                config.useGL30 = true;
                
                // Create the application with NestestBackgroundRenderer
                // This ensures identical functionality across platforms
                new TeaApplication(new NestestBackgroundRenderer(), config);
        }
}
