

package net.lomibao.nes.render;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Disposable;

/**
 * A reusable component for rendering an arbitrary pixel array using LibGDX.
 */
public class PixelRenderer implements Disposable {
    private final Pixmap pixmap;
    private final Texture texture;
    private final int width;
    private final int height;

    public PixelRenderer(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        this.texture = new Texture(pixmap);
    }

    /**
     * Updates the pixel data from an integer array (RGBA8888 format).
     * 
     * @param pixels An array of size width * height containing RGBA8888 pixel data.
     */
    public void setPixels(int[] pixels) {
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("Pixel array size does not match renderer dimensions.");
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixmap.drawPixel(x, y, pixels[y * width + x]);
            }
        }
        texture.draw(pixmap, 0, 0);
    }

    /**
     * Updates the pixel data from a 2D integer array (RGBA8888 format).
     * 
     * @param pixels A 2D array of size [height][width] containing RGBA8888 pixel
     *               data.
     */
    public void setPixels(int[][] pixels) {
        if (pixels.length != height || (pixels.length > 0 && pixels[0].length != width)) {
            throw new IllegalArgumentException("Pixel array dimensions do not match renderer dimensions.");
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixmap.drawPixel(x, y, pixels[y][x]);
            }
        }
        texture.draw(pixmap, 0, 0);
    }

    /**
     * Updates a single pixel.
     * 
     * @param x    X coordinate
     * @param y    Y coordinate
     * @param rgba RGBA8888 value
     */
    public void setPixel(int x, int y, int rgba) {
        pixmap.drawPixel(x, y, rgba);
    }

    /**
     * Draws a line between two points using Bresenham's algorithm.
     * 
     * @param x0   Start X
     * @param y0   Start Y
     * @param x1   End X
     * @param y1   End Y
     * @param rgba RGBA8888 value
     */
    public void drawLine(int x0, int y0, int x1, int y1, int rgba) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            setPixel(x0, y0, rgba);
            if (x0 == x1 && y0 == y1)
                break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /**
     * Uploads the current pixmap data to the GPU texture.
     * Should be called after all pixel updates are done for the frame.
     */
    public void updateTexture() {
        texture.draw(pixmap, 0, 0);
    }

    /**
     * Renders the texture to the screen.
     * 
     * @param batch The SpriteBatch to use for rendering.
     * @param x     Target X position
     * @param y     Target Y position
     */
    public void render(SpriteBatch batch, float x, float y) {
        batch.draw(texture, x, y);
    }

    /**
     * Renders the texture scaled to a target size.
     * 
     * @param batch        The SpriteBatch to use for rendering.
     * @param x            Target X position
     * @param y            Target Y position
     * @param targetWidth  Target width
     * @param targetHeight Target height
     */
    public void render(SpriteBatch batch, float x, float y, float targetWidth, float targetHeight) {
        batch.draw(texture, x, y, targetWidth, targetHeight);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public void dispose() {
        pixmap.dispose();
        texture.dispose();
    }
}
