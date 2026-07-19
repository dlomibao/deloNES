package net.lomibao.nes.client;

import com.badlogic.gdx.Gdx;
import net.lomibao.nes.components.apu.ApuSampleBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.webaudio.AudioBuffer;
import org.teavm.jso.webaudio.AudioContext;
import org.teavm.jso.webaudio.GainNode;
import org.teavm.jso.webaudio.ScriptProcessorNode;

/**
 * Web audio sink (Phase E3, plan class inventory
 * {@code client.WebAudioOut}) — the POC-W ScriptProcessorNode mechanism
 * (D11) productionized from {@link WebAudioTonePoc}, which stays as-is
 * behind its {@code ?audioPoc=1} flag.
 *
 * <p>Chain: {@code new AudioContext()} →
 * {@code createScriptProcessor(2048, 0, 1)} → {@code GainNode} →
 * {@code destination}. <b>Never {@code AudioContext.create()}</b>: that
 * deprecated jso-apis factory's {@code @JSBody} is
 * {@code return new Context();} — {@code Context} is not a browser
 * global, so it ReferenceErrors at runtime (POC-W finding). The class
 * is {@code @JSClass}-mapped, so the plain constructor emits the
 * correct {@code new AudioContext()}.
 *
 * <p>The {@code onaudioprocess} callback pulls straight from the
 * <em>core-owned</em> {@link ApuSampleBuffer} — no second ring. Safe
 * because TeaVM has no real threads: the rAF producer
 * ({@code runFrame()} filling the ring via the APU mixer) and the audio
 * callback interleave on the one main thread. Starved callbacks
 * zero-fill (tab-background drains to silence — accepted, not fought).
 *
 * <p>Autoplay: the context starts {@code "suspended"};
 * capture-phase {@code mousedown}/{@code click}/{@code keydown}
 * listeners on the document call {@code resume()} — capture phase
 * because index.html's canvas handler calls {@code stopPropagation()},
 * which would swallow a bubble-phase listener. The resume is gated on
 * the context <b>state</b>, never a boolean latch (MANDATORY per the
 * Phase 0 review: the JSO {@code resume()} binding returns void, so a
 * rejected promise is invisible and a one-shot flag would block all
 * retries; repeat resume on a running context is a spec'd no-op).
 *
 * <p>D18: {@link #mute()} / {@link #unmute()} drive the GainNode;
 * ROM swap and tab-background mute + clear the ring. Sample rate is
 * {@code ctx.sampleRate} fed into the core via
 * {@code APU.setSampleRate} (D12) — see {@code HtmlLauncher}.
 */
final class WebAudioOut {

    private static final String TAG = "audio";

    /**
     * SPN buffer (D17 strawman): 2048 samples ≈ 43 ms at 48 kHz — must
     * cover worst-case main-thread occupancy (a ~8-9 ms runFrame()).
     * TUNABLE pending the user's POC-W soak numbers
     * (docs/apu-poc-findings.md).
     */
    static final int SPN_BUFFER_SIZE = 2048;

    /**
     * SPN input-channel count. 0 matches the POC wiring; if the user's
     * browser session shows silence with {@code callbacks=0} in the
     * per-second log, this is the historic Chromium/WebKit
     * zero-input-channel SPN quirk — flip to 1 (ignore the input)
     * before concluding binding failure (POC findings "Fallback notes").
     */
    static final int SPN_INPUT_CHANNELS = 0;

    private final AudioContext ctx;
    private final GainNode gain;
    /** Strong reference — a GC'd SPN stops firing in some engines. */
    private final ScriptProcessorNode spn;

    /** Scratch handed to copyToChannel each callback (no per-callback allocation). */
    private final float[] callbackBuf = new float[SPN_BUFFER_SIZE];

    /** The core ring the SPN drains — rebound on every ROM install. */
    private ApuSampleBuffer source;

    // Per-second stats (manual-verification aid; mirrors the POC log line).
    private long lastLogMs;
    private String lastState;
    private int callbacksThisSecond;
    private int starvedSamplesThisSecond;
    private int pulledThisSecond;

    /**
     * Build the chain, or return {@code null} (logged) when WebAudio is
     * unavailable — the emulator then runs silent, never broken.
     */
    static WebAudioOut createOrNull() {
        try {
            return new WebAudioOut();
        } catch (Throwable t) {
            Gdx.app.error(TAG, "WebAudio unavailable — running silent: " + t.getMessage(), t);
            return null;
        }
    }

    private WebAudioOut() {
        ctx = new AudioContext();
        lastState = ctx.getState();
        Gdx.app.log(TAG, "AudioContext created, state=" + lastState
                + " sampleRate=" + ctx.getSampleRate());
        spn = ctx.createScriptProcessor(SPN_BUFFER_SIZE, SPN_INPUT_CHANNELS, 1);
        gain = ctx.createGain();
        gain.getGain().setValue(1.0f);
        spn.setOnAudioProcess(evt -> onAudioProcess(evt.getOutputBuffer()));
        spn.connect(gain);
        gain.connect(ctx.getDestination());
        installGestureResumeHooks();
        installVisibilityHook();
        lastLogMs = System.currentTimeMillis();
    }

    /** Browser-chosen output rate — fed into {@code APU.setSampleRate} (D12). */
    int sampleRate() {
        return (int) ctx.getSampleRate();
    }

    /**
     * Adopt the (new) core ring as the SPN's source — called after every
     * ROM install. Clears it first so no pre-bind samples burst out, and
     * unmutes (the mute set by a preceding {@link #detach()}).
     */
    void setSource(ApuSampleBuffer ring) {
        this.source = ring;
        if (ring != null) {
            ring.clear();
        }
        unmute();
    }

    /** D18 ROM-swap hook: gain-mute and drop the old ring reference. */
    void detach() {
        mute();
        if (source != null) {
            source.clear();
        }
        source = null;
    }

    void mute() {
        gain.getGain().setValue(0f);
    }

    void unmute() {
        gain.getGain().setValue(1.0f);
    }

    /**
     * Capture-phase listeners so the existing gesture surfaces (canvas
     * mousedown, ROM-picker click) reach us despite the canvas handler's
     * stopPropagation(). POC-proven pattern.
     */
    private void installGestureResumeHooks() {
        HTMLDocument doc = Window.current().getDocument();
        doc.addEventListener("mousedown", e -> resumeFromGesture("mousedown"), true);
        doc.addEventListener("click", e -> resumeFromGesture("click"), true);
        doc.addEventListener("keydown", e -> resumeFromGesture("keydown"), true);
        Gdx.app.log(TAG, "gesture resume hooks installed (mousedown/click/keydown, capture)");
    }

    /**
     * D18 tab-background: gain-mute + ring clear on hide; clear + unmute
     * on return so playback restarts clean (while hidden, rAF stops and
     * the SPN zero-fills anyway — this just guarantees no stale burst).
     */
    private void installVisibilityHook() {
        HTMLDocument doc = Window.current().getDocument();
        doc.addEventListener("visibilitychange", e -> {
            boolean hidden = isDocumentHidden();
            if (hidden) {
                mute();
            } else {
                unmute();
            }
            if (source != null) {
                source.clear();
            }
            Gdx.app.log(TAG, "visibilitychange: hidden=" + hidden + " — ring cleared");
        });
    }

    /**
     * {@code document.hidden} — the 2016-era jso-apis HTMLDocument has no
     * Page Visibility binding, so this is the plan-sanctioned custom
     * {@code @JSBody} (class inventory: "client.WebAudioOut (+ any
     * custom @JSBody bindings)").
     */
    @JSBody(script = "return document.hidden === true;")
    private static native boolean isDocumentHidden();

    private void resumeFromGesture(String gesture) {
        // State-gated, never a boolean latch (see class Javadoc). Literal
        // "running" on purpose: the JSO state constants are unreliable
        // (STATE_CLOSE is "close" vs the spec's "closed"); getState()
        // returns the raw DOM ctx.state (POC round-2 finding).
        if ("running".equals(ctx.getState())) {
            return;
        }
        String before = ctx.getState();
        ctx.resume();
        Gdx.app.log(TAG, "resume() from '" + gesture + "' gesture; state before="
                + before + " now=" + ctx.getState());
    }

    /** SPN pull: drain the core ring into the output channel; zero-fill on starvation. */
    private void onAudioProcess(AudioBuffer out) {
        int len = out.getLength();
        if (len > callbackBuf.length) {
            len = callbackBuf.length;
        }
        ApuSampleBuffer ring = source;
        int got = ring != null ? ring.read(callbackBuf, len) : 0;
        for (int i = got; i < len; i++) {
            callbackBuf[i] = 0f;
        }
        out.copyToChannel(callbackBuf, 0);
        callbacksThisSecond++;
        pulledThisSecond += got;
        starvedSamplesThisSecond += len - got;
    }

    /**
     * Per-frame accounting pump — called from the render loop right
     * after {@code renderEmulatorFrame()} (D16; the SPN pulls on its own
     * callback, so the "drain" here is stats + state logging only).
     */
    void onFrame() {
        String state = ctx.getState();
        if (!state.equals(lastState)) {
            Gdx.app.log(TAG, "state transition: " + lastState + " -> " + state);
            lastState = state;
        }
        long now = System.currentTimeMillis();
        if (now - lastLogMs >= 1000) {
            ApuSampleBuffer ring = source;
            Gdx.app.log(TAG, "state=" + state
                    + " sampleRate=" + ctx.getSampleRate()
                    + " ringAvail=" + (ring == null ? -1 : ring.available())
                    + " ringDropped=" + (ring == null ? -1 : ring.dropped())
                    + " callbacks=" + callbacksThisSecond
                    + " pulled=" + pulledThisSecond
                    + " starvedSamples=" + starvedSamplesThisSecond);
            callbacksThisSecond = 0;
            pulledThisSecond = 0;
            starvedSamplesThisSecond = 0;
            lastLogMs = now;
        }
    }
}
