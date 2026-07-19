package net.lomibao.nes.components.apu;

/**
 * Core-owned mono sample ring (Phase E1, plan class inventory).
 *
 * <p>Pre-allocated {@code float[]} with plain {@code int} head/tail —
 * never {@code long} (TeaVM software-emulates long; proven hot-path
 * poison in this repo). Unsynchronized on purpose: on desktop both the
 * producer (render thread's {@code runFrame()}) and consumer (the
 * post-{@code runFrame()} drain, D16) are the render thread; on TeaVM
 * there are no real threads — the rAF producer and the
 * ScriptProcessorNode callback interleave on the one main thread.
 *
 * <p>Overflow policy (E1 test table): <b>overwrite-oldest</b>, counting
 * every dropped sample. A headless run with no consumer just rolls the
 * window forward — no allocation, no error.
 */
public final class ApuSampleBuffer {

    private final float[] ring;
    private final int capacity;
    /** Read index (consumer). */
    private int head;
    /** Write index (producer). */
    private int tail;
    /** Samples currently buffered. */
    private int count;
    /** Cumulative samples discarded by overwrite-oldest since last {@link #clear()}. */
    private int dropped;

    public ApuSampleBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new float[capacity];
    }

    /** Append one sample; when full the oldest sample is overwritten (and counted). */
    public void write(float sample) {
        if (count == capacity) {
            // Overwrite-oldest: advance the read index past the victim.
            head++;
            if (head == capacity) {
                head = 0;
            }
            count--;
            dropped++;
        }
        ring[tail] = sample;
        tail++;
        if (tail == capacity) {
            tail = 0;
        }
        count++;
    }

    /**
     * Copy up to {@code maxLen} buffered samples into {@code dst[0..n)} in
     * FIFO order and consume them. Returns the number of samples copied
     * (0 when empty).
     */
    public int read(float[] dst, int maxLen) {
        int n = count < maxLen ? count : maxLen;
        if (n > dst.length) {
            n = dst.length;
        }
        for (int i = 0; i < n; i++) {
            dst[i] = ring[head];
            head++;
            if (head == capacity) {
                head = 0;
            }
        }
        count -= n;
        return n;
    }

    /** Samples currently buffered. */
    public int available() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    /** Samples discarded by overwrite-oldest since construction or last {@link #clear()}. */
    public int dropped() {
        return dropped;
    }

    /** Empty the ring and zero the dropped counter (D18: pause-resume / reset / ROM swap). */
    public void clear() {
        head = 0;
        tail = 0;
        count = 0;
        dropped = 0;
    }
}
