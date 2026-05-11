package net.lomibao.nes.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;

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
 * {@code updatesPerSecond = -1} (which disables the internal render-loop
 * thread), then manually drives the full lifecycle:
 * {@link ApplicationListener#create()}, N calls to
 * {@link ApplicationListener#render()}, and
 * {@link ApplicationListener#dispose()}.
 *
 * <p>The headless backend provides no-op GL stubs so tests do not require a
 * real GPU or display.
 */
public class HeadlessTestSupport {

    private HeadlessTestSupport() {}

    /**
     * Boots a {@link HeadlessApplication} wrapping {@code listener}, then
     * manually calls {@code create()}, {@code render()} {@code frames} times,
     * and {@code dispose()}, then exits the application.
     *
     * @param listener the {@link ApplicationListener} under test
     * @param frames   number of render frames to simulate (must be &gt;= 0)
     */
    public static void runFrames(ApplicationListener listener, int frames) {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        // -1 disables the internal render-loop thread; lifecycle is manual below.
        config.updatesPerSecond = -1;

        // The HeadlessApplication constructor calls listener.create() synchronously
        // on the calling thread when updatesPerSecond = -1 (no render thread).
        HeadlessApplication app = new HeadlessApplication(listener, config);
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
