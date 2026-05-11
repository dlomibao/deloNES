package net.lomibao.nes.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link HeadlessTestSupport} can boot a headless LibGDX
 * application, drive a fixed number of render frames, and shut down cleanly.
 */
public class HeadlessApplicationTest {

    /**
     * Renders an empty Screen for exactly 3 frames and verifies that all
     * lifecycle methods were called the expected number of times.
     */
    @Test
    void emptyScreen_renders3FramesWithoutError() {
        CountingListener listener = new CountingListener();

        // Should complete without throwing
        HeadlessTestSupport.runFrames(listener, 3);

        assertEquals(1, listener.createCount, "create() should be called once");
        assertEquals(3, listener.renderCount, "render() should be called exactly 3 times");
        assertEquals(1, listener.disposeCount, "dispose() should be called once");
    }

    /** Tracks how many times each lifecycle method is invoked. */
    private static class CountingListener extends ApplicationAdapter {
        int createCount = 0;
        int renderCount = 0;
        int disposeCount = 0;

        @Override
        public void create() {
            createCount++;
        }

        @Override
        public void render() {
            renderCount++;
        }

        @Override
        public void dispose() {
            disposeCount++;
        }
    }
}
