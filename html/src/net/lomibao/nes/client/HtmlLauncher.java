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
