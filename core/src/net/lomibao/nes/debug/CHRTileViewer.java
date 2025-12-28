package net.lomibao.nes.debug;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.TileDecoder;
import net.lomibao.nes.render.PixelRenderer;

import java.io.InputStream;

/**
 * CHR ROM Tile Viewer - Displays tiles from a NES ROM using LibGDX
 * 
 * This is a LibGDX ApplicationAdapter that:
 * 1. Loads a NES ROM (default: nestest.nes)
 * 2. Extracts CHR ROM data
 * 3. Decodes tiles using TileDecoder
 * 4. Renders them using PixelRenderer at 2x scale for visibility
 * 
 * To use: Create a desktop application with this as the main class adapter
 */
@Log4j2
public class CHRTileViewer extends ApplicationAdapter {
    private SpriteBatch batch;
    private PixelRenderer renderer;
    private byte[][] chrLayout;
    private String romPath;
    private static final int SCALE = 2; // 2x scale for visibility
    
    public CHRTileViewer() {
        this("nestest.nes");
    }
    
    public CHRTileViewer(String romPath) {
        this.romPath = romPath;
    }
    
    @Override
    public void create() {
        batch = new SpriteBatch();
        
        try {
            // Load ROM and extract CHR data
            log.info("Loading ROM from: {}", romPath);
            InputStream romStream = Gdx.files.internal(romPath).read();
            Cartridge cartridge = new Cartridge(romStream, romPath);
            
            // Check if CHR data is available
            byte[] chrData = cartridge.getCHRROM();
            if (chrData == null || chrData.length == 0) {
                log.error("No CHR ROM data found in cartridge");
                Gdx.app.exit();
                return;
            }
            
            log.info("CHR ROM Size: {} bytes ({} banks)", chrData.length, cartridge.getCHRBanks());
            
            // Decode CHR layout
            chrLayout = TileDecoder.decodeCHRLayout(chrData);
            if (chrLayout == null) {
                log.error("Failed to decode CHR layout");
                Gdx.app.exit();
                return;
            }
            
            log.info("Decoded CHR layout: {}x{} pixels", chrLayout.length, chrLayout[0].length);
            
            // Create renderer at scaled size
            int renderedWidth = chrLayout[0].length * SCALE;
            int renderedHeight = chrLayout.length * SCALE;
            renderer = new PixelRenderer(renderedWidth, renderedHeight);
            
            // Convert CHR layout to RGBA8888 pixel data
            int[][] rgba = convertCHRToRGBA(chrLayout);
            
            // Scale up the pixel data
            int[][] scaledRgba = scalePixels(rgba, SCALE);
            
            // Render to display
            renderer.setPixels(scaledRgba);
            
            // Setup camera to render at top-left corner instead of center
            batch.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            
            log.info("CHR Tile Viewer initialized successfully");
            
        } catch (Exception e) {
            log.error("Error initializing CHR Tile Viewer", e);
            Gdx.app.exit();
        }
    }
    
    /**
     * Convert 2-bit color CHR data to RGBA8888 for display
     */
    private int[][] convertCHRToRGBA(byte[][] chrData) {
        int height = chrData.length;
        int width = chrData[0].length;
        int[][] rgba = new int[height][width];
        
        // NES color palette (simplified NTSC)
        int[] colors = {
            0x626262FF,  // 0 - Gray
            0x0088FFFF,  // 1 - Light Blue
            0xBB00FFFF,  // 2 - Magenta
            0xFF00FFFF   // 3 - Bright Magenta
        };
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                byte colorIdx = chrData[y][x];
                rgba[y][x] = colors[colorIdx & 3];
            }
        }
        
        return rgba;
    }
    
    /**
     * Scale pixel data by a given factor
     */
    private int[][] scalePixels(int[][] pixels, int scale) {
        int originalHeight = pixels.length;
        int originalWidth = pixels[0].length;
        int scaledHeight = originalHeight * scale;
        int scaledWidth = originalWidth * scale;
        
        int[][] scaled = new int[scaledHeight][scaledWidth];
        
        for (int y = 0; y < originalHeight; y++) {
            for (int x = 0; x < originalWidth; x++) {
                int color = pixels[y][x];
                
                // Fill scaled block
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        scaled[y * scale + sy][x * scale + sx] = color;
                    }
                }
            }
        }
        
        return scaled;
    }
    
    @Override
    public void render() {
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        
        batch.begin();
        if (renderer != null) {
            // Render at (0, 0) with proper coordinate system (top-left)
            float yPos = Gdx.graphics.getHeight() - renderer.getHeight();
            renderer.render(batch, 0, yPos);
        }
        batch.end();
    }
    
    @Override
    public void resize(int width, int height) {
    }
    
    @Override
    public void pause() {
    }
    
    @Override
    public void resume() {
    }
    
    @Override
    public void dispose() {
        batch.dispose();
        if (renderer != null) {
            renderer.dispose();
        }
    }
}
