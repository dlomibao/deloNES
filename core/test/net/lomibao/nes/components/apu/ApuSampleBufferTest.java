package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase E1 — {@link ApuSampleBuffer}: FIFO order, wrap, the
 * overwrite-oldest overflow policy (dropped counted), and clear().
 */
class ApuSampleBufferTest {

    @Test
    void fifoOrder_andAvailable() {
        ApuSampleBuffer b = new ApuSampleBuffer(8);
        assertEquals(0, b.available());
        b.write(1f);
        b.write(2f);
        b.write(3f);
        assertEquals(3, b.available());
        float[] dst = new float[8];
        assertEquals(3, b.read(dst, 8));
        assertEquals(1f, dst[0]);
        assertEquals(2f, dst[1]);
        assertEquals(3f, dst[2]);
        assertEquals(0, b.available());
        assertEquals(0, b.read(dst, 8), "read on empty returns 0");
    }

    @Test
    void wrapAround_preservesOrderAcrossTheSeam() {
        ApuSampleBuffer b = new ApuSampleBuffer(4);
        float[] dst = new float[4];
        // Advance head to the middle, then wrap the tail past the end.
        b.write(1f);
        b.write(2f);
        b.write(3f);
        assertEquals(2, b.read(dst, 2)); // head now at index 2
        b.write(4f);
        b.write(5f); // tail wraps
        b.write(6f);
        assertEquals(4, b.available());
        assertEquals(4, b.read(dst, 4));
        assertEquals(3f, dst[0]);
        assertEquals(4f, dst[1]);
        assertEquals(5f, dst[2]);
        assertEquals(6f, dst[3]);
    }

    @Test
    void overflow_overwritesOldest_andCountsDropped() {
        ApuSampleBuffer b = new ApuSampleBuffer(4);
        for (int i = 1; i <= 6; i++) {
            b.write(i);
        }
        assertEquals(4, b.available(), "capacity is the ceiling");
        assertEquals(2, b.dropped(), "two oldest overwritten");
        float[] dst = new float[4];
        assertEquals(4, b.read(dst, 4));
        // The NEWEST four survive.
        assertEquals(3f, dst[0]);
        assertEquals(4f, dst[1]);
        assertEquals(5f, dst[2]);
        assertEquals(6f, dst[3]);
    }

    @Test
    void read_honorsMaxLenAndDstLength() {
        ApuSampleBuffer b = new ApuSampleBuffer(8);
        for (int i = 1; i <= 5; i++) {
            b.write(i);
        }
        float[] small = new float[2];
        assertEquals(2, b.read(small, 8), "clamped by dst.length");
        assertEquals(1f, small[0]);
        assertEquals(2f, small[1]);
        float[] dst = new float[8];
        assertEquals(1, b.read(dst, 1), "clamped by maxLen");
        assertEquals(3f, dst[0]);
        assertEquals(2, b.available());
    }

    @Test
    void clear_emptiesAndZeroesDropped() {
        ApuSampleBuffer b = new ApuSampleBuffer(2);
        b.write(1f);
        b.write(2f);
        b.write(3f); // drops one
        assertEquals(1, b.dropped());
        b.clear();
        assertEquals(0, b.available());
        assertEquals(0, b.dropped());
        b.write(9f);
        float[] dst = new float[2];
        assertEquals(1, b.read(dst, 2));
        assertEquals(9f, dst[0]);
    }

    @Test
    void invalidCapacity_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new ApuSampleBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new ApuSampleBuffer(-1));
    }
}
