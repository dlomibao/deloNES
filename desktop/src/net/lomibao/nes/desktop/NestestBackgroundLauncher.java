package net.lomibao.nes.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop launcher for the NES Background Renderer
 * Runs nestest.nes and displays the rendered background
 */
public class NestestBackgroundLauncher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("NES Background Renderer - nestest.nes");
        config.setWindowedMode(640, 600);
        config.setResizable(true);
        config.setForegroundFPS(60);
        
        // Enable VSync for smooth rendering
        config.useVsync(true);
        
        new Lwjgl3Application(new NestestBackgroundRenderer(), config);
    }
}
