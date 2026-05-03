package net.lomibao.nes.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import net.lomibao.nes.components.*;
import net.lomibao.nes.components.ppu.NameTableMemory;
import net.lomibao.nes.render.PixelRenderer;

/**
 * Desktop application that runs DonkeyKong.nes ROM and renders the background
 * tiles and nametables using the PPU rendering system.
 */
public class NestestBackgroundRenderer extends ApplicationAdapter {
    private SpriteBatch batch;
    private BitmapFont font;
    private PixelRenderer pixelRenderer;
    private Texture paletteTexture;
    private Pixmap palettePixmap;
    
    // NES components
    private CPUBus cpuBus;
    private CPU6502 cpu;
    private PPU ppu;
    private PPUBus ppuBus;
    private Cartridge cartridge;
    private NameTableMemory nameTableMemory;
    
    // Timing
    private static final int CYCLES_PER_FRAME = 29780; // Approximate CPU cycles per frame
    private int frameCount = 0;
    
    @Override
    public void create() {
        System.out.println("Starting NES Background Renderer with DonkeyKong.nes");
        
        // Initialize LibGDX components
        batch = new SpriteBatch();
        font = new BitmapFont();
        pixelRenderer = new PixelRenderer(256, 240);
        
        // Create palette display pixmap (32 colors in a row, 20x20 each)
        palettePixmap = new Pixmap(32 * 20, 20, Pixmap.Format.RGBA8888);
        paletteTexture = new Texture(palettePixmap);
        
        // Initialize NES components
        setupNESSystem();
        
        // Load DonkeyKong ROM
        loadROM();
        
        // Initialize PPU memory with test pattern
        initializeTestPattern();
        
        System.out.println("NES system initialized and ready to render");
    }
    
    /**
     * Sets up the NES system with CPU, PPU, and buses
     */
    private void setupNESSystem() {
        // Create PPU and PPU bus
        ppu = new PPU();
        ppuBus = new PPUBus();
        nameTableMemory = new NameTableMemory();
        
        // Connect PPU bus components
        ppuBus.connect(nameTableMemory);
        ppu.connectPPUBus(ppuBus);
        
        // Create CPU and CPU bus
        cpu = new CPU6502();
        Ram ram = new Ram();
        Controller controller = new Controller();
        
        cpuBus = CPUBus.builder()
                .cpu(cpu)
                .ram(ram)
                .ppu(ppu)
                .controller(controller)
                .build()
                .connect();
        
        // NMI is now polled via ppu.consumeNmi() — see NesSystem.tick.
        // (NestestBackgroundRenderer drives the bus directly; that path
        // doesn't propagate NMI but nestest is a CPU-only test ROM that
        // never enables NMI anyway, so it doesn't matter here.)

        System.out.println("NES components connected");
    }
    
    /**
     * Loads the DonkeyKong.nes ROM file
     */
    private void loadROM() {
        try {
            cartridge = new Cartridge(
                this.getClass().getResourceAsStream("/roms/DonkeyKong.nes"), 
                "DonkeyKong.nes"
            );
            
            // Connect cartridge to buses
            cpuBus.setCartridge(cartridge);
            ppu.setCartridge(cartridge);
        ppuBus.connectCartridge(cartridge);
        ppuBus.connectPPU(ppu);
            
            // Reset system to start execution
            cpu.reset();
            ppu.reset();
            
            System.out.println("System reset complete");
        } catch (Exception e) {
            System.err.println("Failed to load DonkeyKong.nes: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to load ROM", e);
        }
    }
    
    /**
     * Initializes a test pattern in the nametable and palette
     * This ensures we have something to render even if the ROM doesn't set it up
     */
    private void initializeTestPattern() {
        // Set up a simple nametable pattern via PPU registers
        // Enable background rendering
        ppu.cpuBusWrite(0x2001, (byte) 0x08); // PPUMASK: enable background
        
        // Set up palette - write some colors
        ppu.cpuBusWrite(0x2006, (byte) 0x3F); // PPUADDR high byte
        ppu.cpuBusWrite(0x2006, (byte) 0x00); // PPUADDR low byte
        
        // Write background palette
        ppu.cpuBusWrite(0x2007, (byte) 0x0F); // Background color (black)
        ppu.cpuBusWrite(0x2007, (byte) 0x00); // Palette 0, color 1 (dark gray)
        ppu.cpuBusWrite(0x2007, (byte) 0x10); // Palette 0, color 2 (light gray)
        ppu.cpuBusWrite(0x2007, (byte) 0x30); // Palette 0, color 3 (white)
        
        System.out.println("Test pattern initialized");
    }
    
    @Override
    public void render() {
        // Clear screen
        Gdx.gl.glClearColor(0.2f, 0.2f, 0.2f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        
        // Clear PPU screen buffer before rendering new frame
        ppu.clearScreen();
        
        // Run emulation for one frame
        runFrame();
        
        // Get PPU screen buffer and render it
        int[][] screen = ppu.getScreen();
        
        // Extract visible area (256x240) and convert from ARGB to RGBA
        int[][] visibleScreen = new int[240][256];
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 256; x++) {
                // Convert ARGB to RGBA: shift bytes left, move alpha to end
                int argb = screen[y][x];
                int rgba = (argb << 8) | (argb >>> 24);
                visibleScreen[y][x] = rgba;
            }
        }
        
        // Debug: Check for non-black pixels every 60 frames
        if (frameCount % 60 == 0) {
            int nonZeroCount = 0;
            int sampleColor = visibleScreen[120][128]; // Center pixel (converted RGBA)
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 256; x++) {
                    // 0xFF000000 in ARGB converts to 0x000000FF in RGBA
                    if (visibleScreen[y][x] != 0x000000FF) {
                        nonZeroCount++;
                    }
                }
            }
            int ppumask = Byte.toUnsignedInt(ppu.registers[1]);
            int ppuctrl = Byte.toUnsignedInt(ppu.registers[0]);
            
            // Read some palette colors via PPU bus
            StringBuilder paletteStr = new StringBuilder("Palette: ");
            for (int i = 0; i < 16; i++) {
                int color = ppu.ppuBusRead(0x3F00 + i, true);
                paletteStr.append(String.format("%02X ", color));
            }
            
            System.out.println("Frame " + frameCount + ": Non-black pixels=" + nonZeroCount + 
                             ", Center pixel=0x" + Integer.toHexString(sampleColor) +
                             ", PPUMASK=0x" + Integer.toHexString(ppumask) +
                             ", PPUCTRL=0x" + Integer.toHexString(ppuctrl));
            System.out.println(paletteStr.toString());
        }
        
        // Update pixel renderer with PPU output
        pixelRenderer.setPixels(visibleScreen);
        
        // Update palette display texture
        updatePaletteDisplay();
        
        // Render to screen
        batch.begin();
        
        // Render NES screen at 2x scale
        pixelRenderer.render(batch, 50, 50, 512, 480);
        
        // Render palette display
        batch.draw(paletteTexture, 50, 15, 640, 20);
        
        // Draw info text
        font.draw(batch, "NES Background Renderer - DonkeyKong.nes", 50, 550);
        font.draw(batch, "Frame: " + frameCount, 50, 530);
        font.draw(batch, "CPU PC: 0x" + Integer.toHexString(cpu.getPc()).toUpperCase(), 50, 510);
        font.draw(batch, "PPU Scanline: " + ppu.getScanline() + " Cycle: " + ppu.getCycle(), 250, 530);
        font.draw(batch, "Palette Memory (32 colors):", 50, 5);
        
        batch.end();
        
        frameCount++;
    }
    
    /**
     * Runs the emulation for approximately one frame
     */
    private void runFrame() {
        // Run until frame is complete
        ppu.clearFrameComplete();
        
        int cycles = 0;
        int maxCycles = CYCLES_PER_FRAME * 2; // Safety limit
        
        while (!ppu.isFrameComplete() && cycles < maxCycles) {
            // Execute one CPU instruction (cpu.clock() returns void)
            cpu.clock();
            cycles++;
            
            // PPU runs 3x faster than CPU
            for (int i = 0; i < 3; i++) {
                ppu.clock();
            }
        }
    }
    
    /**
     * Updates the palette display texture with current palette RAM colors
     */
    private void updatePaletteDisplay() {
        palettePixmap.setColor(Color.BLACK);
        palettePixmap.fill();
        
        // Draw all 32 palette colors (16 background + 16 sprite)
        for (int i = 0; i < 32; i++) {
            // Read color directly from PPU's getPaletteColor method
            int nesColor = ppu.getPaletteColor(i);
            
            // Convert NES color to RGB using the PPU's color conversion
            // We'll use a simple direct mapping here - create a small screen to get the color
            int argbColor = convertNESColorToARGB(nesColor);
            
            // Extract RGB components
            int r = (argbColor >> 16) & 0xFF;
            int g = (argbColor >> 8) & 0xFF;
            int b = argbColor & 0xFF;
            
            // Draw 20x20 square for this color
            palettePixmap.setColor(r / 255f, g / 255f, b / 255f, 1f);
            palettePixmap.fillRectangle(i * 20, 0, 20, 20);
        }
        
        // Update texture
        paletteTexture.draw(palettePixmap, 0, 0);
    }
    
    /**
     * Converts NES color index to ARGB color using the NES color palette
     */
    private int convertNESColorToARGB(int nesColorIndex) {
        // NES color palette (64 colors)
        int[] nesColorPalette = {
            0xFF7C7C7C, 0xFF0000FC, 0xFF0000BC, 0xFF4428BC, 0xFF940084, 0xFFA80020, 0xFFA81000, 0xFF881400,
            0xFF503000, 0xFF007800, 0xFF006800, 0xFF005800, 0xFF004058, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFFBCBCBC, 0xFF0078F8, 0xFF0058F8, 0xFF6844FC, 0xFFD800CC, 0xFFE40058, 0xFFF83800, 0xFFE45C10,
            0xFFAC7C00, 0xFF00B800, 0xFF00A800, 0xFF00A844, 0xFF008888, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFFF8F8F8, 0xFF3CBCFC, 0xFF6888FC, 0xFF9878F8, 0xFFF878F8, 0xFFF85898, 0xFFF87858, 0xFFFCA044,
            0xFFF8B800, 0xFFB8F818, 0xFF58D854, 0xFF58F898, 0xFF00E8D8, 0xFF787878, 0xFF000000, 0xFF000000,
            0xFFFCFCFC, 0xFFA4E4FC, 0xFFB8B8F8, 0xFFD8B8F8, 0xFFF8B8F8, 0xFFF8A4C0, 0xFFF0D0B0, 0xFFFCE0A8,
            0xFFF8D878, 0xFFD8F878, 0xFFB8F8B8, 0xFFB8F8D8, 0xFF00FCFC, 0xFFF8D8F8, 0xFF000000, 0xFF000000
        };
        
        return nesColorPalette[nesColorIndex & 0x3F];
    }
    
    @Override
    public void resize(int width, int height) {
        // Handle window resize if needed
    }
    
    @Override
    public void dispose() {
        if (batch != null) batch.dispose();
        if (font != null) font.dispose();
        if (pixelRenderer != null) pixelRenderer.dispose();
        if (paletteTexture != null) paletteTexture.dispose();
        if (palettePixmap != null) palettePixmap.dispose();
        
        System.out.println("NES Background Renderer disposed");
    }
}
