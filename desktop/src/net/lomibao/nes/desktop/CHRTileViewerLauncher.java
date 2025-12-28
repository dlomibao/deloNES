package net.lomibao.nes.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import net.lomibao.nes.debug.CHRTileViewer;

/**
 * Desktop launcher for the CHR Tile Viewer
 * Displays tiles from a NES ROM using PixelRenderer
 */
public class CHRTileViewerLauncher {
    public static void main(String[] args) {
        String romPath = args.length > 0 ? args[0] : "nestest.nes";
        
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("NES CHR ROM Tile Viewer - " + romPath);
        config.setWindowedMode(800, 600);
        config.setResizable(true);
        config.setForegroundFPS(60);
        
        new Lwjgl3Application(new CHRTileViewer(romPath), config);
    }
}
