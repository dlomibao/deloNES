package net.lomibao.nes.client;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;
import net.lomibao.nes.NesSystem;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.PPUBus;
import net.lomibao.nes.components.Ram;
import net.lomibao.nes.components.ppu.NameTableMemory;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

/**
 * Phase 0 web derisking entry point. Renders a moving gradient via
 * Pixmap + Texture upload (the same code path the real emulator will
 * use), logs FPS once a second, probes Gdx.files.internal for ROM
 * access, and logs keyboard events. Used to verify gdx-teavm 1.5.6 +
 * TeaVM 0.14.0 actually deliver a usable browser runtime before any
 * larger porting work begins.
 *
 * <p>See {@code docs/web-phase0-findings.md} for results.
 */
public class HtmlLauncher {

    public static void main(String[] args) {
        WebApplicationConfiguration config = new WebApplicationConfiguration();
        config.width = 0;
        config.height = 0;
        config.useGL30 = true;
        config.showDownloadLogs = true;

        new WebApplication(new Phase0Probe(), config);
    }

    private static class Phase0Probe extends ApplicationAdapter {
        private static final int NES_W = 256;
        private static final int NES_H = 240;

        private SpriteBatch batch;
        private Pixmap pixmap;
        private Texture texture;
        private byte[] frameBytes;
        private int frame;
        private long lastFpsLogMs;

        @Override
        public void create() {
            batch = new SpriteBatch();
            pixmap = new Pixmap(NES_W, NES_H, Format.RGBA8888);
            texture = new Texture(NES_W, NES_H, Format.RGBA8888);
            frameBytes = new byte[NES_W * NES_H * 4];
            lastFpsLogMs = System.currentTimeMillis();

            Gdx.app.log("phase0", "render probe up — NES_W=" + NES_W + " NES_H=" + NES_H);
            probeResources();
            probeInput();
            probeCpu();
        }

        /**
         * Smoke-test the post-C1 string-switch CPU dispatch in the browser.
         * Loads nestest.nes, builds a minimal {@link NesSystem}, resets the
         * CPU, runs a small number of master ticks, logs PC progress and a
         * couple of CPU register values. Any thrown exception during ROM
         * load / reset / clock-loop surfaces as a "CPU PROBE FAIL" log line
         * — proves whether the refactored dispatch table actually executes
         * under TeaVM end-to-end.
         */
        private void probeCpu() {
            try {
                byte[] romBytes = Gdx.files.internal("roms/nestest.nes").readBytes();
                Cartridge cart = new Cartridge(
                        new ByteArrayInputStream(romBytes), "nestest.nes");

                PPU ppu = new PPU();
                PPUBus ppuBus = new PPUBus();
                NameTableMemory nameTableMemory = new NameTableMemory();
                ppuBus.connect(nameTableMemory);
                ppu.connectPPUBus(ppuBus);

                // CPU6502 needs the opcode CSV. The no-arg constructor reads
                // /opcodes/opcodes.csv from the classpath, which TeaVM does not
                // embed; use the InputStream constructor against the preloaded
                // asset instead.
                byte[] csvBytes = Gdx.files.internal("opcodes/opcodes.csv").readBytes();
                CPU6502 cpu = new CPU6502(new ByteArrayInputStream(csvBytes));
                Ram ram = new Ram();

                NesSystem nes = NesSystem.builder()
                        .cpu(cpu).ram(ram).ppu(ppu)
                        .dma(new DmaController())
                        .build();

                nes.getCpuBus().setCartridge(cart);
                ppu.setCartridge(cart);
                ppuBus.connectCartridge(cart);
                ppuBus.connectPPU(ppu);

                cpu.reset();
                ppu.reset();

                int initialPc = cpu.getPc();
                int ticksToRun = 1000;
                for (int i = 0; i < ticksToRun; i++) {
                    nes.tick();
                }
                int finalPc = cpu.getPc();

                Gdx.app.log("phase0",
                        "CPU PROBE OK after " + ticksToRun + " ticks: "
                        + "initialPC=0x" + Integer.toHexString(initialPc)
                        + " finalPC=0x" + Integer.toHexString(finalPc)
                        + " A=0x" + Integer.toHexString(cpu.getA())
                        + " X=0x" + Integer.toHexString(cpu.getX())
                        + " Y=0x" + Integer.toHexString(cpu.getY())
                        + " SP=0x" + Integer.toHexString(cpu.getStkp())
                        + " status=0x" + Integer.toHexString(cpu.getStatus() & 0xff)
                        + " clockCount=" + cpu.getClockCount());

                if (finalPc == initialPc) {
                    Gdx.app.error("phase0",
                            "CPU PROBE WARN: PC did not advance — dispatch may "
                            + "be returning silently. Check string-switch defaults.");
                }
            } catch (Throwable t) {
                Gdx.app.error("phase0", "CPU PROBE FAIL: " + t.getMessage(), t);
            }
        }

        private void probeResources() {
            try {
                byte[] bytes = Gdx.files.internal("roms/nestest.nes").readBytes();
                Gdx.app.log("phase0",
                        "RESOURCE PROBE OK: nestest.nes loaded, " + bytes.length + " bytes, "
                        + "first 4 = "
                        + Integer.toHexString(bytes[0] & 0xff) + " "
                        + Integer.toHexString(bytes[1] & 0xff) + " "
                        + Integer.toHexString(bytes[2] & 0xff) + " "
                        + Integer.toHexString(bytes[3] & 0xff)
                        + " (expect 4e 45 53 1a)");
            } catch (Throwable t) {
                Gdx.app.error("phase0", "RESOURCE PROBE FAIL: " + t.getMessage(), t);
            }
        }

        private void probeInput() {
            Gdx.input.setInputProcessor(new InputAdapter() {
                @Override
                public boolean keyDown(int keycode) {
                    Gdx.app.log("phase0", "INPUT keyDown=" + keycode
                            + " (" + Input.Keys.toString(keycode) + ")");
                    return true;
                }
            });
        }

        @Override
        public void render() {
            frame++;

            // Fast path: fill a byte[] then bulk-put into the pixmap's
            // ByteBuffer. drawPixel() per-pixel crawls at ~3 FPS on
            // gdx-teavm because of the JS call-per-pixel overhead.
            int offset = frame & 0xff;
            int idx = 0;
            for (int y = 0; y < NES_H; y++) {
                for (int x = 0; x < NES_W; x++) {
                    frameBytes[idx++] = (byte) ((x + offset) & 0xff);
                    frameBytes[idx++] = (byte) ((y + offset) & 0xff);
                    frameBytes[idx++] = (byte) ((x ^ y) & 0xff);
                    frameBytes[idx++] = (byte) 0xff;
                }
            }
            ByteBuffer pixels = pixmap.getPixels();
            pixels.rewind();
            pixels.put(frameBytes);
            pixels.rewind();
            texture.draw(pixmap, 0, 0);

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

            batch.begin();
            batch.draw(texture, 0, 0,
                    Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            batch.end();

            long now = System.currentTimeMillis();
            if (now - lastFpsLogMs >= 1000) {
                Gdx.app.log("phase0",
                        "FPS=" + Gdx.graphics.getFramesPerSecond()
                        + " frame=" + frame);
                lastFpsLogMs = now;
            }
        }

        @Override
        public void dispose() {
            if (batch != null) batch.dispose();
            if (texture != null) texture.dispose();
            if (pixmap != null) pixmap.dispose();
        }
    }
}
