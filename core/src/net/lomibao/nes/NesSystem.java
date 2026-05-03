package net.lomibao.nes;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.APU;
import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.CPUBus;
import net.lomibao.nes.components.Cartridge;
import net.lomibao.nes.components.Controller;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.FullAddressRam;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * System orchestrator that sits above {@link CPUBus} and exposes a single
 * {@code tick()} / {@code runFrame()} entry point for hosts (LibGDX render
 * loop, headless tests, debug launchers).
 *
 * <p>This is Step 1 of the playable-gen1 plan
 * (see {@code docs/impl_plan_playable_gen1.md}). It deliberately starts as a
 * thin facade over the existing {@link CPUBus#clock()} master-tick (which
 * already drives PPU every call and CPU every 3rd call). Later steps will
 * extend this class with NMI hook polling (Step 2), DMA arbitration
 * (Step 4), frame pacing (Step 8), and a frame-rendered listener.
 *
 * <p>Wiring is done via Lombok {@code @Builder}; supply at minimum a CPU,
 * RAM, and PPU. A cartridge is optional for tests that synthesise programs
 * directly into RAM. The constructor connects every supplied component to
 * the bus.
 */
@Log4j2
@Getter
public class NesSystem {
    /**
     * NTSC frame period in seconds. The real NES runs at
     * 1.7897725 MHz / 29780.5 cycles per frame ≈ 60.0988 Hz, giving
     * ~16.6389 ms per frame. {@link #advance(double)} uses this to
     * convert real elapsed time into integer frames.
     */
    public static final double NTSC_FRAME_SECONDS = 1.0 / 60.0988;

    /**
     * Cap on how many frames a single {@link #advance(double)} call
     * will run, even if {@code deltaSeconds} is huge. Prevents the
     * "spiral of death" where a slow render frame produces a large
     * delta, which produces a longer catch-up frame, which produces an
     * even larger delta. 2 is enough for occasional double-frame
     * catchup (a missed vsync) without giving the game a permanent
     * head start.
     */
    public static final int MAX_FRAMES_PER_ADVANCE = 2;

    private final CPU6502 cpu;
    private final PPU ppu;
    private final CPUBus cpuBus;

    /** Accumulated real-time seconds awaiting conversion into frames. */
    private double frameAccumulatorSeconds = 0.0;

    /**
     * Optional listener invoked once per VBlank rising edge — i.e., once
     * per rendered frame. Hosts use this to upload the PPU framebuffer to
     * the screen, poll input, advance audio, etc. Null by default.
     */
    private Consumer<NesSystem> frameRenderedListener;

    @Builder
    private NesSystem(CPU6502 cpu,
                      Ram ram,
                      PPU ppu,
                      APU apu,
                      Cartridge cartridge,
                      Controller controller,
                      DmaController dma,
                      FullAddressRam testRam) {
        // Required components — fail fast at construction so callers don't
        // get a NullPointerException deep inside CPUBus.clock() on first tick.
        this.cpu = Objects.requireNonNull(cpu, "cpu is required");
        this.ppu = Objects.requireNonNull(ppu, "ppu is required");
        Objects.requireNonNull(ram, "ram is required (CPU bus needs $0000-$1FFF backing)");
        // apu, cartridge, controller, dma, testRam remain optional.
        this.cpuBus = CPUBus.builder()
                .cpu(cpu)
                .ram(ram)
                .ppu(ppu)
                .apu(apu)
                .cartridge(cartridge)
                .controller(controller)
                .dma(dma)
                .testRam(testRam)
                .build()
                .connect();
    }

    /**
     * Advance the system by exactly one master tick. Delegates to
     * {@link CPUBus#clock()} which advances the PPU every call and the CPU
     * every third call (the NES PPU runs at 3× CPU speed).
     *
     * <p>After the bus tick, polls the PPU's NMI latch
     * ({@link PPU#consumeNmi()}). On a rising edge this method
     * (a) invokes {@link CPU6502#nmi()} on the CPU, and
     * (b) fires the {@link #frameRenderedListener} if registered.
     * The listener fires exactly once per VBlank entry — never per tick.
     */
    public void tick() {
        cpuBus.clock();
        if (ppu.consumeNmi()) {
            cpu.nmi();
            Consumer<NesSystem> listener = frameRenderedListener;
            if (listener != null) {
                // Listener exceptions must NOT crash the emulator. A buggy
                // screenshot-save IOException or a nullable upstream state
                // bug should land in the log, not stop the master clock.
                try {
                    listener.accept(this);
                } catch (RuntimeException e) {
                    log.error("frameRenderedListener threw — emulator continues", e);
                }
            }
        }
    }

    /**
     * Register (or unregister with {@code null}) a listener fired on every
     * VBlank rising edge. Replaces any previously-registered listener.
     */
    public void setFrameRenderedListener(Consumer<NesSystem> listener) {
        this.frameRenderedListener = listener;
    }

    /**
     * Run master ticks until the PPU signals frame complete, then consume
     * (clear) the flag and return. After this call, {@code ppu.isFrameComplete()}
     * is false and the PPU is positioned at the start of the next frame.
     *
     * <p>A safety cap of 3× the nominal frame length (341 × 262 × 3 = 268,026
     * ticks) prevents an infinite loop if PPU state is wedged. If the cap
     * trips, this method throws {@link IllegalStateException} so a hung PPU
     * surfaces as a loud test failure rather than a silent timeout.
     *
     * @throws IllegalStateException if the PPU does not signal frame complete
     *         within the safety window
     */
    public void runFrame() {
        long maxTicks = 341L * 262L * 3L; // 3× safety margin
        long deadline = cpuBus.getMasterClockCount() + maxTicks;
        while (!ppu.isFrameComplete()) {
            tick();
            if (cpuBus.getMasterClockCount() >= deadline) {
                throw new IllegalStateException(
                        "PPU did not signal frame complete within " + maxTicks +
                        " master ticks — rendering state may be wedged");
            }
        }
        // Consume the flag so a subsequent call has a fresh frame to wait on.
        ppu.clearFrameComplete();
    }

    /**
     * Time-driven frame pacing entry point — call from a host loop
     * (LibGDX {@code render()}, headless replay, etc.) once per host
     * tick with the real elapsed seconds since the last call. Runs as
     * many full frames as the accumulated time covers, capped at
     * {@link #MAX_FRAMES_PER_ADVANCE}, and carries fractional
     * accumulation across calls so the long-run rate matches NTSC.
     *
     * <p>If the host stalls (e.g. GC pause produces a 500 ms delta),
     * the cap drops the excess accumulation rather than running 30
     * catch-up frames in one tick — the game effectively skips those
     * lost frames instead of slowing down the host trying to catch up.
     *
     * @param deltaSeconds real time elapsed since the last call
     * @return number of frames actually run on this call (0..{@link #MAX_FRAMES_PER_ADVANCE})
     */
    public int advance(double deltaSeconds) {
        if (deltaSeconds < 0) deltaSeconds = 0;
        frameAccumulatorSeconds += deltaSeconds;
        int framesRun = 0;
        while (frameAccumulatorSeconds >= NTSC_FRAME_SECONDS && framesRun < MAX_FRAMES_PER_ADVANCE) {
            runFrame();
            frameAccumulatorSeconds -= NTSC_FRAME_SECONDS;
            framesRun++;
        }
        // If we're still over the cap's worth of time, drop the excess
        // to prevent runaway accumulation. Keep up to one frame's worth
        // for normal carry-over.
        if (frameAccumulatorSeconds > NTSC_FRAME_SECONDS) {
            frameAccumulatorSeconds = 0.0;
        }
        return framesRun;
    }

    /**
     * Reset the system to power-on state. Resets the CPU and zeroes the
     * master-clock count via {@link CPUBus#reset()}. Also clears any
     * accumulated frame-pacing time from {@link #advance(double)}.
     */
    public void reset() {
        cpuBus.reset();
        frameAccumulatorSeconds = 0.0;
    }

    /**
     * Convenience for callers that want the master-clock count without
     * reaching through {@link #getCpuBus()}.
     */
    public long getMasterClockCount() {
        return cpuBus.getMasterClockCount();
    }
}
