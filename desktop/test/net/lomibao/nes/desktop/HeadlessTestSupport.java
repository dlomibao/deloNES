package net.lomibao.nes.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
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
 * {@code updatesPerSecond = -1}, which makes the backend thread call
 * {@code create()} exactly once and then fall straight through to
 * {@code pause()} / {@code dispose()} and exit.  To keep the lifecycle
 * deterministic the wrapper blocks inside {@code create()} until the test
 * thread has finished calling {@code render()} N times, then releases the
 * backend thread to run a single {@code dispose()} pass.  The helper waits
 * for that {@code dispose()} to complete and clears the {@code Gdx.*}
 * statics before returning, so successive tests start from a clean slate.
 *
 * <p>The headless backend provides no-op GL stubs so tests do not require a
 * real GPU or display.
 */
public class HeadlessTestSupport {

    private HeadlessTestSupport() {}

    /**
     * Boots a {@link HeadlessApplication} wrapping {@code listener}, waits for
     * {@code create()} to be called by the backend thread, drives N
     * {@code render()} calls on the test thread, then releases the backend
     * thread to perform a single {@code dispose()} pass.  Blocks until that
     * dispose completes and resets the {@code Gdx.*} statics so the next
     * test sees a clean global state.
     *
     * @param listener the {@link ApplicationListener} under test
     * @param frames   number of render frames to simulate (must be &gt;= 0)
     * @throws RuntimeException if the backend thread does not call
     *                          {@code create()} within 5 seconds, or fails
     *                          to dispose within 5 seconds after release
     */
    public static void runFrames(ApplicationListener listener, int frames) {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        // -1 means: call create() once on the backend thread, then exit the loop.
        config.updatesPerSecond = -1;

        CountDownLatch createLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        CountDownLatch disposeLatch = new CountDownLatch(1);

        ApplicationListener wrapper = new ApplicationListener() {
            @Override
            public void create() {
                listener.create();
                createLatch.countDown();
                // Block here so the backend thread can't advance into
                // pause()/dispose() until the test thread is done driving
                // render() calls. This removes the dispose-race that
                // previously made HeadlessApplicationTest flake roughly
                // 50% of the time.
                try {
                    releaseLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
                try {
                    listener.dispose();
                } finally {
                    disposeLatch.countDown();
                }
            }
        };

        HeadlessApplication app = new HeadlessApplication(wrapper, config);

        try {
            if (!createLatch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException(
                        "HeadlessTestSupport: create() was not called within 5 seconds");
            }

            for (int i = 0; i < frames; i++) {
                listener.render();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for create()", e);
        } finally {
            // Release the backend thread so it can run pause() then dispose()
            // exactly once. Then wait for that dispose to complete before
            // clearing the Gdx.* statics so the next test sees a clean slate.
            releaseLatch.countDown();
            try {
                if (!disposeLatch.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException(
                            "HeadlessTestSupport: dispose() was not called within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            app.exit();
            clearGdxStatics();
        }
    }

    /**
     * {@link HeadlessApplication} never nulls the {@code Gdx.*} statics on
     * exit, so successive tests would share references to the previous
     * test's {@code MockGraphics} / {@code MockFiles} / app instance. Clear
     * them so each test starts fresh.
     */
    private static void clearGdxStatics() {
        Gdx.app = null;
        Gdx.gl = null;
        Gdx.gl20 = null;
        Gdx.gl30 = null;
        Gdx.files = null;
        Gdx.input = null;
        Gdx.audio = null;
        Gdx.graphics = null;
        Gdx.net = null;
    }
}
