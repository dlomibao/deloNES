package net.lomibao.nes.desktop.audio;

import com.badlogic.gdx.audio.AudioDevice;
import net.lomibao.nes.components.apu.ApuSampleBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E2 — headless host-side smoke ({@code AudioOut} ring
 * accounting; plan E2/E3 test tier). {@link AudioDevice} is an
 * interface, so the drain path is exercised against a recording fake —
 * no OpenAL device needed. The audible half is manual (Phase E gate
 * checklist).
 */
class AudioOutTest {

    /** Recording fake — captures every writeSamples call verbatim. */
    private static final class FakeDevice implements AudioDevice {
        final List<float[]> writes = new ArrayList<float[]>();
        boolean disposed;

        @Override
        public boolean isMono() {
            return true;
        }

        @Override
        public void writeSamples(short[] samples, int offset, int numSamples) {
            throw new UnsupportedOperationException("float path only");
        }

        @Override
        public void writeSamples(float[] samples, int offset, int numSamples) {
            float[] copy = new float[numSamples];
            System.arraycopy(samples, offset, copy, 0, numSamples);
            writes.add(copy);
        }

        @Override
        public int getLatency() {
            return 4096;
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public void setVolume(float volume) {
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        int totalSamples() {
            int n = 0;
            for (float[] w : writes) {
                n += w.length;
            }
            return n;
        }
    }

    @Test
    void drain_writesExactlyTheBufferedSamples_inOrder() {
        FakeDevice device = new FakeDevice();
        AudioOut out = new AudioOut(device);
        ApuSampleBuffer ring = new ApuSampleBuffer(4096);
        for (int i = 0; i < 734; i++) {
            ring.write(i * 0.001f);
        }
        assertEquals(734, out.drain(ring));
        assertEquals(0, ring.available(), "drain must empty the ring");
        assertEquals(734, device.totalSamples());
        // Order preserved through the chunked copy.
        float[] first = device.writes.get(0);
        assertEquals(0f, first[0]);
        assertEquals(0.001f, first[1], 1e-9f);
    }

    @Test
    void drain_chunksWritesAboveChunkSize() {
        FakeDevice device = new FakeDevice();
        AudioOut out = new AudioOut(device);
        ApuSampleBuffer ring = new ApuSampleBuffer(8192);
        int n = AudioOut.CHUNK_SAMPLES * 2 + 100;
        for (int i = 0; i < n; i++) {
            ring.write(1f);
        }
        assertEquals(n, out.drain(ring));
        assertEquals(3, device.writes.size(), "2 full chunks + remainder");
        assertEquals(AudioOut.CHUNK_SAMPLES, device.writes.get(0).length);
        assertEquals(AudioOut.CHUNK_SAMPLES, device.writes.get(1).length);
        assertEquals(100, device.writes.get(2).length);
    }

    @Test
    void drain_onEmptyRing_writesNothing() {
        FakeDevice device = new FakeDevice();
        AudioOut out = new AudioOut(device);
        assertEquals(0, out.drain(new ApuSampleBuffer(64)));
        assertTrue(device.writes.isEmpty(), "no zero-length device writes");
    }

    @Test
    void dispose_releasesDevice_andNullDeviceRejected() {
        FakeDevice device = new FakeDevice();
        new AudioOut(device).dispose();
        assertTrue(device.disposed);
        assertThrows(IllegalArgumentException.class, () -> new AudioOut(null));
    }
}
