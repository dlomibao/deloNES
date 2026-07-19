package net.lomibao.nes.desktop.audio;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;

import java.util.Arrays;
import java.util.Properties;

/**
 * Phase 0 APU POC-D (derisk code — docs/apu-plan.md, "Phase 0 / 0-D").
 * Streams a 440 Hz square wave through {@code Gdx.audio.newAudioDevice}
 * from the render loop and logs the numbers the plan asks for: device
 * latency, per-call {@code writeSamples} wall time (p99/max), and FPS,
 * once per second.
 *
 * <p>Run via {@code ./gradlew desktop:audioPoc}. Variant runs:
 * <ul>
 *   <li>{@code -Ddelones.audioPoc.bufferSize=512 -Ddelones.audioPoc.bufferCount=3}
 *       (or 1024/4) — tune {@link Lwjgl3ApplicationConfiguration#setAudioConfig};
 *       unset keeps the LibGDX defaults (16/512/9).</li>
 *   <li>{@code -Ddelones.audioPoc.stereo=true} — interleaved stereo instead of mono.</li>
 *   <li>{@code -Ddelones.audioPoc.underrunTest=true} — deliberately skip 5
 *       frames of writes at t=10s and t=30s to hear underrun recovery.</li>
 *   <li>{@code -Ddelones.audioPoc.seconds=N} — soak length (default 75, 0 = run
 *       until closed).</li>
 * </ul>
 *
 * <p>Success criteria + findings go to {@code docs/apu-poc-findings.md}.
 * This class may be deleted/absorbed by Phase E; do not build on it.
 */
public class TonePocLauncher extends ApplicationAdapter {

    /** Parsed {@code delones.audioPoc.*} flags. Package-visible for tests. */
    static final class Config {
        /** -1 = leave LibGDX default in place. */
        int bufferSize = -1;
        int bufferCount = -1;
        boolean stereo = false;
        boolean underrunTest = false;
        int runSeconds = 75;

        static Config parse(Properties props) {
            Config c = new Config();
            c.bufferSize = intProp(props, "delones.audioPoc.bufferSize", -1);
            c.bufferCount = intProp(props, "delones.audioPoc.bufferCount", -1);
            c.stereo = Boolean.parseBoolean(props.getProperty("delones.audioPoc.stereo", "false"));
            c.underrunTest = Boolean.parseBoolean(props.getProperty("delones.audioPoc.underrunTest", "false"));
            c.runSeconds = intProp(props, "delones.audioPoc.seconds", 75);
            return c;
        }

        private static int intProp(Properties props, String key, int fallback) {
            String v = props.getProperty(key);
            if (v == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                System.err.println("[audioPoc] ignoring malformed " + key + "=" + v);
                return fallback;
            }
        }
    }

    /**
     * p-th percentile of the first {@code count} entries (sorts a copy).
     * 0 when there are no samples. Package-visible for tests.
     */
    static long percentileNanos(long[] samples, int count, double p) {
        if (count <= 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(samples, count);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p * count) - 1;
        if (idx < 0) {
            idx = 0;
        }
        return copy[idx];
    }

    private static final int SAMPLE_RATE = 44100;
    private static final double TONE_HZ = 440.0;
    /** Roughly one NES pulse channel at full volume through the mixer. */
    private static final float AMPLITUDE = 0.15f;

    private final Config config;
    private AudioDevice device;
    private SquareToneGenerator generator;
    private float[] monoBuf;
    private float[] stereoBuf;

    // Per-second instrumentation.
    private long[] writeNanos = new long[240];
    private int writeCount;
    private long lastLogMs;
    private long startMs;
    private int skippedWritesTotal;
    /** Frames still to skip for the in-progress underrun burst. */
    private int underrunSkipRemaining;
    private boolean underrun10Fired;
    private boolean underrun30Fired;

    TonePocLauncher(Config config) {
        this.config = config;
    }

    public static void main(String[] args) {
        Config config = Config.parse(System.getProperties());
        Lwjgl3ApplicationConfiguration app = new Lwjgl3ApplicationConfiguration();
        app.setTitle("deloNES APU POC-D — 440Hz square");
        app.setWindowedMode(480, 120);
        app.setForegroundFPS(60);
        if (config.bufferSize > 0 && config.bufferCount > 0) {
            app.setAudioConfig(16, config.bufferSize, config.bufferCount);
            System.out.println("[audioPoc] setAudioConfig(16, " + config.bufferSize
                    + ", " + config.bufferCount + ")");
        } else {
            System.out.println("[audioPoc] using LibGDX default audio config (16/512/9)");
        }
        new Lwjgl3Application(new TonePocLauncher(config), app);
    }

    @Override
    public void create() {
        boolean mono = !config.stereo;
        device = Gdx.audio.newAudioDevice(SAMPLE_RATE, mono);
        generator = new SquareToneGenerator(SAMPLE_RATE, TONE_HZ, AMPLITUDE);
        monoBuf = new float[1024];
        stereoBuf = new float[2048];
        startMs = System.currentTimeMillis();
        lastLogMs = startMs;
        System.out.println("[audioPoc] device created: mono=" + mono
                + " rate=" + SAMPLE_RATE
                + " latency=" + device.getLatency() + " samples ("
                + String.format("%.1f", device.getLatency() * 1000.0 / SAMPLE_RATE) + " ms)"
                + " underrunTest=" + config.underrunTest
                + " runSeconds=" + config.runSeconds);
    }

    @Override
    public void render() {
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.2f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        long now = System.currentTimeMillis();
        long elapsedSec = (now - startMs) / 1000;

        // Underrun test: skip 5 consecutive frames of writes at t=10s and t=30s.
        if (config.underrunTest) {
            if (!underrun10Fired && elapsedSec >= 10) {
                underrun10Fired = true;
                underrunSkipRemaining = 5;
                System.out.println("[audioPoc] UNDERRUN TEST: skipping 5 frames of writes (t=10s)");
            }
            if (!underrun30Fired && elapsedSec >= 30) {
                underrun30Fired = true;
                underrunSkipRemaining = 5;
                System.out.println("[audioPoc] UNDERRUN TEST: skipping 5 frames of writes (t=30s)");
            }
        }

        int n = generator.nextFrameSampleCount();
        if (underrunSkipRemaining > 0) {
            underrunSkipRemaining--;
            skippedWritesTotal++;
            // Keep the generator's frame accumulator honest but drop the audio.
        } else {
            generator.fill(monoBuf, n);
            long t0 = System.nanoTime();
            if (config.stereo) {
                for (int i = 0; i < n; i++) {
                    stereoBuf[2 * i] = monoBuf[i];
                    stereoBuf[2 * i + 1] = monoBuf[i];
                }
                device.writeSamples(stereoBuf, 0, n * 2);
            } else {
                device.writeSamples(monoBuf, 0, n);
            }
            long dt = System.nanoTime() - t0;
            if (writeCount < writeNanos.length) {
                writeNanos[writeCount++] = dt;
            }
        }

        if (now - lastLogMs >= 1000) {
            long p99 = percentileNanos(writeNanos, writeCount, 0.99);
            long max = percentileNanos(writeNanos, writeCount, 1.0);
            System.out.println(String.format(
                    "[audioPoc] t=%3ds fps=%d writes=%d p99=%.3fms max=%.3fms latency=%d skippedTotal=%d",
                    elapsedSec, Gdx.graphics.getFramesPerSecond(), writeCount,
                    p99 / 1_000_000.0, max / 1_000_000.0,
                    device.getLatency(), skippedWritesTotal));
            writeCount = 0;
            lastLogMs = now;
        }

        if (config.runSeconds > 0 && elapsedSec >= config.runSeconds) {
            System.out.println("[audioPoc] soak complete (" + config.runSeconds + "s) — exiting");
            Gdx.app.exit();
        }
    }

    @Override
    public void dispose() {
        if (device != null) {
            device.dispose();
            device = null;
        }
        System.out.println("[audioPoc] device disposed");
    }
}
