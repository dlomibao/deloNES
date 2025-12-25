package net.lomibao.nes.render;

/**
 * Test class for PixelRenderer to verify line drawing and other visual
 * features.
 */
public class PixelRendererTest {

    /**
     * Draws a series of test lines on the given renderer.
     * 
     * @param renderer The PixelRenderer to test.
     */
    public static void runLineDrawingTest(PixelRenderer renderer) {
        int w = renderer.getWidth();
        int h = renderer.getHeight();

        // Corners to center
        renderer.drawLine(0, 0, w - 1, h - 1, 0xFF0000FF); // Red
        renderer.drawLine(w - 1, 0, 0, h - 1, 0x00FF00FF); // Green

        // Horizontal and Vertical lines
        renderer.drawLine(w / 2, 0, w / 2, h - 1, 0x0000FFFF); // Blue
        renderer.drawLine(0, h / 2, w - 1, h / 2, 0xFFFF00FF); // Yellow

        // A square frame
        renderer.drawLine(10, 10, w - 11, 10, 0xFFFFFFFF);
        renderer.drawLine(w - 11, 10, w - 11, h - 11, 0xFFFFFFFF);
        renderer.drawLine(w - 11, h - 11, 10, h - 11, 0xFFFFFFFF);
        renderer.drawLine(10, h - 11, 10, 10, 0xFFFFFFFF);

        renderer.updateTexture();
    }
}
