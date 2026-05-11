package net.lomibao.nes.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test scaffolding for running LibGDX {@link ApplicationListener} code in a
 * headless (no-display) environment.
 *
 * <p>Usage:
 * <pre>
 *   HeadlessTestSupport.runFrames(myListener, 3);
 * </pre>
 *
 * <p>The helper boots a {@link HeadlessApplication} with
 * {@code updatesPerSecond = -1} (which causes the internal render-loop
 * thread to call {@code create()} exactly once and then exit).  A
 * {@link CountDownLatch} is used to wait for {@code create()} to complete
 * on the background thread before the caller drives the lifecycle manually:
 * N calls to {@link ApplicationListener#render()} and
 * {@link ApplicationListener#dispose()}, then exits the application.
 *
 * <p>The headless backend provides no-op GL stubs so tests do not require a
 * real GPU or display.
 */
public class HeadlessTestSupport {

    private HeadlessTestSupport() {}

    /**
     * Boots a {@link HeadlessApplication} wrapping {@code listener}, waits for
     * {@code create()} to be called by the background thread, then manually
     * calls {@code render()} {@code frames} times, {@code dispose()}, and
     * exits the application.
     *
     * @param listener the {@link ApplicationListener} under test
     * @param frames   number of render frames to simulate (must be &gt;= 0)
     * @throws RuntimeException if the background thread does not call
     *                          {@code create()} within 5 seconds
     */
    public static void runFrames(ApplicationListener listener, int frames) {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        // -1 means: call create() once in the background thread, then exit the loop.
        config.updatesPerSecond = -1;

        // Wrap the listener so we can detect when create() has been called.
        CountDownLatch createLatch = new CountDownLatch(1);
        ApplicationListener wrapper = new ApplicationListener() {
            @Override
            public void create() {
                listener.create();
                createLatch.countDown();
            }

            @Override
            public void resize(int width, int height) {
                listener.resize(width, height);
            }

            @Override
            public void render() {
                listener.render();
            }

            @Override
            public void pause() {
                listener.pause();
            }

            @Override
            public void resume() {
                listener.resume();
            }

            @Override
            public void dispose() {
                listener.dispose();
            }
        };

        HeadlessApplication app = new HeadlessApplication(wrapper, config);

        // Wait for the background thread to call create()
        try {
            if (!createLatch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException(
                        "HeadlessTestSupport: create() was not called within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for create()", e);
        }

        try {
            for (int i = 0; i < frames; i++) {
                listener.render();
            }
            listener.dispose();
        } finally {
            app.exit();
        }
    }
}
