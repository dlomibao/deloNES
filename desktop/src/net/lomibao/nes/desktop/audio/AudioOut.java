package net.lomibao.nes.desktop.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import net.lomibao.nes.components.apu.ApuSampleBuffer;

/**
 * Desktop audio sink (Phase E2, plan class inventory
 * {@code desktop.audio.AudioOut}). Thin wrapper over
 * {@code Gdx.audio.newAudioDevice(sampleRate, mono)} — mono per D15.
 *
 * <p>Lifecycle is owned by {@code EmulatorScreen}: created in
 * {@code show()}, drained immediately after {@code runFrame()} in
 * {@code render()} (D16 — the same frame boundary the harness and
 * recorder use, never {@code frameRenderedListener}), disposed in
 * {@code dispose()}. Pause semantics per D18: the host simply stops
 * calling {@link #drain} and lets the device play out; OpenAL's
 * auto-recovery restarts the source on the next write.
 *
 * <p>{@code writeSamples} blocks only when the device's buffer pool is
 * full; with one frame's worth of samples per call and the POC-D-tuned
 * {@code setAudioConfig} capacity (~90-100 ms) it almost never does
 * (research doc §1, LWJGL3 {@code OpenALAudioDevice} blocking
 * semantics).
 */
public final class AudioOut {

    /** Per-drain copy chunk — comfortably above one frame (734) of samples. */
    static final int CHUNK_SAMPLES = 2048;

    private final AudioDevice device;
    private final float[] chunk = new float[CHUNK_SAMPLES];

    /** Wrap an existing device (test seam — {@link AudioDevice} is an interface). */
    AudioOut(AudioDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        this.device = device;
    }

    /**
     * Open the platform device at {@code sampleRate} Hz mono. Throws
     * {@code GdxRuntimeException} when no audio device is available
     * (headless/CI) — callers treat that as "run silent".
     */
    public static AudioOut open(int sampleRate) {
        return new AudioOut(Gdx.audio.newAudioDevice(sampleRate, true));
    }

    /**
     * Push everything currently buffered in the core ring to the device
     * (called once per frame, right after {@code runFrame()} — D16).
     * Returns the number of samples written.
     */
    public int drain(ApuSampleBuffer buffer) {
        int total = 0;
        int n;
        while ((n = buffer.read(chunk, CHUNK_SAMPLES)) > 0) {
            device.writeSamples(chunk, 0, n);
            total += n;
        }
        return total;
    }

    /** Release the native device (OpenAL source + buffers). */
    public void dispose() {
        device.dispose();
    }
}
