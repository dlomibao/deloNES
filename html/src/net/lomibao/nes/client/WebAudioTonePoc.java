package net.lomibao.nes.client;

import com.badlogic.gdx.Gdx;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.webaudio.AudioBuffer;
import org.teavm.jso.webaudio.AudioContext;
import org.teavm.jso.webaudio.GainNode;
import org.teavm.jso.webaudio.ScriptProcessorNode;

/**
 * Phase 0 APU POC-W (derisk code — docs/apu-plan.md, "Phase 0 / 0-W").
 * Streams a 440 Hz square wave through the browser WebAudio API via the
 * TeaVM JSO bindings ({@code org.teavm.jso.webaudio}), layered on top of
 * the live emulator build so the 60 FPS main-thread contention question
 * is answered for real.
 *
 * <p><b>Flag:</b> only constructed when the page URL contains the query
 * parameter {@code audioPoc} (e.g. {@code ?audioPoc=1}) — see
 * {@link #createIfEnabled()}. Flag off ⇒ zero WebAudio objects, zero
 * event listeners, nothing changes.
 *
 * <p>Chain: {@code AudioContext.create()} →
 * {@code createScriptProcessor(2048, 0, 1)} → {@code GainNode} →
 * {@code destination}. The {@code onaudioprocess} callback pulls from an
 * unsynchronized Java {@code float[]} ring — safe because TeaVM has no
 * real threads: the rAF producer and the audio callback interleave on
 * the one main thread. Ring indices are plain {@code int}s (no
 * {@code long} — TeaVM hot-path rule).
 *
 * <p>Autoplay: the context starts {@code "suspended"};
 * {@link #installGestureResumeHooks()} registers <b>capture-phase</b>
 * {@code mousedown}/{@code click} listeners on the document so the
 * existing gesture surfaces (canvas mousedown, ROM-picker click — both
 * wired in index.html) trigger {@code resume()}. Capture phase is
 * required because index.html's canvas handler calls
 * {@code stopPropagation()}, which would swallow a bubble-phase
 * document listener.
 *
 * <p>Instrumentation (per the plan): logs {@code ctx.sampleRate},
 * {@code getState()} transitions, and — once per second — ring
 * occupancy min/max, callback count, starved callbacks/samples, and
 * dropped (overflowed) samples.
 *
 * <p>This class may be deleted/absorbed by Phase E; do not build on it.
 * In-POC fallback if SPN misbehaves: AudioBufferSourceNode queue (plan
 * D11); kill-pivot: AudioWorklet.
 */
final class WebAudioTonePoc {

    private static final String TAG = "audioPoc";
    private static final int SPN_BUFFER_SIZE = 2048;
    /** Power-of-two ring: ~10 frames @48k headroom; occupancy is what we watch. */
    private static final int RING_SIZE = 8192;
    private static final int RING_MASK = RING_SIZE - 1;
    private static final double NES_FRAME_RATE = 60.0988;
    private static final double TONE_HZ = 440.0;
    private static final float AMPLITUDE = 0.15f;

    private final AudioContext ctx;
    private final GainNode gain;
    /** Strong reference — a GC'd SPN stops firing in some engines. */
    private final ScriptProcessorNode spn;

    // Unsynchronized SPSC ring (single-threaded TeaVM).
    private final float[] ring = new float[RING_SIZE];
    private int head; // read index (consumer: onaudioprocess)
    private int tail; // write index (producer: render loop)
    private int occupancy;

    /** Scratch buffer handed to copyToChannel each callback. */
    private final float[] callbackBuf = new float[SPN_BUFFER_SIZE];

    // Square synthesis at ctx.getSampleRate().
    private final double samplesPerFrame;
    private final double phaseInc;
    private double frameAccumulator;
    private double phase;

    // Per-second stats.
    private long lastLogMs;
    private int occMin = Integer.MAX_VALUE;
    private int occMax;
    private int callbacksThisSecond;
    private int starvedCallbacksThisSecond;
    private int starvedSamplesThisSecond;
    private int droppedSamplesThisSecond;
    private int producedThisSecond;
    private boolean resumed;
    private String lastState;

    /**
     * Constructs the probe only when the URL query string contains
     * {@code audioPoc}; returns {@code null} (and allocates nothing)
     * otherwise.
     */
    static WebAudioTonePoc createIfEnabled() {
        String search;
        try {
            search = Window.current().getLocation().getSearch();
        } catch (Throwable t) {
            return null;
        }
        if (search == null || !search.contains("audioPoc")) {
            return null;
        }
        try {
            return new WebAudioTonePoc();
        } catch (Throwable t) {
            Gdx.app.error(TAG, "POC-W setup FAILED: " + t.getMessage(), t);
            return null;
        }
    }

    private WebAudioTonePoc() {
        // NOT AudioContext.create(): that deprecated factory's @JSBody is
        // "return new Context();" in jso-apis 0.14 — `Context` is not a
        // browser global, so it ReferenceErrors at runtime. The class is
        // @JSClass-mapped, so the plain constructor emits the correct
        // `new AudioContext()` (verified in the generated classes.js).
        ctx = new AudioContext();
        lastState = ctx.getState();
        Gdx.app.log(TAG, "AudioContext created, state=" + lastState
                + " sampleRate=" + ctx.getSampleRate());

        float sampleRate = ctx.getSampleRate();
        samplesPerFrame = sampleRate / NES_FRAME_RATE;
        phaseInc = TONE_HZ / sampleRate;

        spn = ctx.createScriptProcessor(SPN_BUFFER_SIZE, 0, 1);
        gain = ctx.createGain();
        gain.getGain().setValue(1.0f);
        spn.setOnAudioProcess(evt -> onAudioProcess(evt.getOutputBuffer()));
        spn.connect(gain);
        gain.connect(ctx.getDestination());
        Gdx.app.log(TAG, "chain built: SPN(" + SPN_BUFFER_SIZE
                + ", 0 in, 1 out) -> Gain -> destination; ring=" + RING_SIZE);

        installGestureResumeHooks();
        lastLogMs = System.currentTimeMillis();
    }

    /**
     * Capture-phase listeners on the document so the canvas-mousedown /
     * ROM-picker-click gestures reach us despite the canvas handler's
     * stopPropagation(). First gesture calls resume(); later ones no-op.
     */
    private void installGestureResumeHooks() {
        HTMLDocument doc = Window.current().getDocument();
        doc.addEventListener("mousedown", e -> resumeFromGesture("mousedown"), true);
        doc.addEventListener("click", e -> resumeFromGesture("click"), true);
        doc.addEventListener("keydown", e -> resumeFromGesture("keydown"), true);
        Gdx.app.log(TAG, "gesture resume hooks installed (mousedown/click/keydown, capture)");
    }

    private void resumeFromGesture(String gesture) {
        if (resumed) {
            return;
        }
        resumed = true;
        String before = ctx.getState();
        ctx.resume();
        Gdx.app.log(TAG, "resume() called from '" + gesture
                + "' gesture; state before=" + before + " now=" + ctx.getState());
    }

    /** SPN pull: drain the ring into the output channel; zero-fill on starvation. */
    private void onAudioProcess(AudioBuffer out) {
        int len = out.getLength();
        if (len > callbackBuf.length) {
            len = callbackBuf.length;
        }
        noteOccupancy();
        int starved = 0;
        for (int i = 0; i < len; i++) {
            if (occupancy > 0) {
                callbackBuf[i] = ring[head];
                head = (head + 1) & RING_MASK;
                occupancy--;
            } else {
                callbackBuf[i] = 0f;
                starved++;
            }
        }
        out.copyToChannel(callbackBuf, 0);
        callbacksThisSecond++;
        if (starved > 0) {
            starvedCallbacksThisSecond++;
            starvedSamplesThisSecond += starved;
        }
        noteOccupancy();
    }

    /**
     * Called once per rAF render tick from {@code WebLauncher.render()}.
     * Produces one video frame's worth of the square into the ring
     * (fractional accumulator at ctx.sampleRate — e.g. 798/799 @48k)
     * and emits the per-second stats line.
     */
    void onFrame() {
        frameAccumulator += samplesPerFrame;
        int n = (int) frameAccumulator;
        frameAccumulator -= n;
        for (int i = 0; i < n; i++) {
            float s = phase < 0.5 ? AMPLITUDE : -AMPLITUDE;
            phase += phaseInc;
            if (phase >= 1.0) {
                phase -= 1.0;
            }
            if (occupancy < RING_SIZE) {
                ring[tail] = s;
                tail = (tail + 1) & RING_MASK;
                occupancy++;
            } else {
                droppedSamplesThisSecond++;
            }
        }
        producedThisSecond += n;
        noteOccupancy();

        String state = ctx.getState();
        if (!state.equals(lastState)) {
            Gdx.app.log(TAG, "state transition: " + lastState + " -> " + state);
            lastState = state;
        }

        long now = System.currentTimeMillis();
        if (now - lastLogMs >= 1000) {
            Gdx.app.log(TAG, "state=" + state
                    + " sampleRate=" + ctx.getSampleRate()
                    + " occ[min=" + (occMin == Integer.MAX_VALUE ? 0 : occMin)
                    + " max=" + occMax + "]"
                    + " callbacks=" + callbacksThisSecond
                    + " starvedCb=" + starvedCallbacksThisSecond
                    + " starvedSamples=" + starvedSamplesThisSecond
                    + " dropped=" + droppedSamplesThisSecond
                    + " produced=" + producedThisSecond);
            occMin = Integer.MAX_VALUE;
            occMax = 0;
            callbacksThisSecond = 0;
            starvedCallbacksThisSecond = 0;
            starvedSamplesThisSecond = 0;
            droppedSamplesThisSecond = 0;
            producedThisSecond = 0;
            lastLogMs = now;
        }
    }

    private void noteOccupancy() {
        if (occupancy < occMin) {
            occMin = occupancy;
        }
        if (occupancy > occMax) {
            occMax = occupancy;
        }
    }
}
