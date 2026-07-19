# NES APU — Research & Emulation Survey for deloNES

A survey of how the NES APU (the audio half of the Ricoh 2A03) works and how reference
implementations built theirs, written to ground the upcoming deloNES APU plan. Companion
to `docs/olcnes_review.md` (§8 recorded a first-pass APU verdict) and
`docs/bugzmanov_nes_ebook_review.md`. Style follows those docs: per-component analysis
with **what to copy / what to avoid** verdicts specific to this codebase.

Sources surveyed (2026-07):

- **NESdev Wiki** — APU, APU_registers, APU_Pulse, APU_Triangle, APU_Noise, APU_DMC,
  APU_Frame_Counter, APU_Length_Counter, APU_Sweep, APU_Envelope, APU_Mixer, DMA,
  CPU_power_up_state
- **olcNES Part #7** — `olc2A03.h/.cpp`, `Bus.cpp`, `olcNes_Sounds1.cpp`
- **bugzmanov NES ebook** — chapter 9 status re-verified
- **Mesen2** — `Core/NES/APU/*` (NesApu, ApuFrameCounter, ApuTimer, channel classes,
  DeltaModulationChannel), `NesCpu` DMC-DMA path, `NesSoundMixer`
- **blargg's Nes_Snd_Emu / Blip_Buffer** — band-limited synthesis design
- **christopherpow/nes-test-roms** — `apu_test`, `apu_reset`, `blargg_apu_2005.07.30`,
  `apu_mixer`, `dmc_tests`, `dmc_dma_during_read4`, `sprdma_and_dmc_dma`
- **deloNES sources** — `APU.java`, `CPUBus.java`, `NesSystem.java`, `DmaController.java`,
  `CPU6502.java`, `docs/headless-harness-plan.md`

---

## 1. The hardware model (NESdev distillation)

### 1.1 Clock domains

- NTSC CPU = **1.789773 MHz**. 1 **APU cycle** = 2 CPU cycles.
- **Pulse, noise, and DMC timers clock every APU cycle** (every 2nd CPU cycle).
- **The triangle timer clocks every CPU cycle** — the one channel on the fast clock.
- The frame sequencer is counted in APU cycles, which is why its event table has `.5`
  entries: those events land on the odd CPU cycle of an APU cycle. **Practical
  consequence: track APU time in CPU cycles** and use the doubled (integer) tables
  below — then every event lands on an integer and the half-cycle subtlety disappears.

### 1.2 Frame counter ($4017, write-only: `MI-- ----`)

Generates **quarter-frame** clocks (envelopes + triangle linear counter, ~240 Hz) and
**half-frame** clocks (length counters + sweeps, ~120 Hz).

**Mode 0 — 4-step** (bit 7 clear), NTSC, CPU-cycle table:

| CPU cycle | APU cycle | Quarter | Half | Frame IRQ flag |
|---|---|---|---|---|
| 7457 | 3728.5 | ✔ | | |
| 14913 | 7456.5 | ✔ | ✔ | |
| 22371 | 11185.5 | ✔ | | |
| 29828 | 14914 | | | **set** (if inhibit clear) |
| 29829 | 14914.5 | ✔ | ✔ | **set** |
| 29830 → 0 | 14915 → 0 | | | **set**, sequencer wraps |

The frame IRQ flag is set on **three consecutive CPU cycles (29828/29829/29830)** — a
$4015 read that clears it on one of those cycles sees it re-set on the next. Period
29830 CPU cycles ≈ 60 Hz.

**Mode 1 — 5-step** (bit 7 set): quarter at 7457, quarter+half at 14913, quarter at
22371, **nothing at 29829** (dead step), quarter+half at 37281, wrap at 37282.
**No IRQ is ever set in mode 1.**

**$4017 write quirks (test ROMs check all three):**

1. The sequencer reset is **delayed 3 or 4 CPU cycles** after the write depending on
   write-cycle parity (during an APU cycle → 3; between APU cycles → 4).
2. If **bit 7 is set**, a quarter-frame AND half-frame clock fire immediately (at the
   delayed reset point). Games use this in NMI handlers to force-clock length counters.
3. **Bit 6 (IRQ inhibit) set → the frame IRQ flag is cleared immediately.** Clearing
   bit 6 does not set it.

### 1.3 Pulse channels ($4000–$4007)

- `$4000/$4004 DDLC VVVV` — duty, length-halt/envelope-loop, constant-volume flag,
  volume/envelope period. `$4001/$4005` sweep. `$4002/$4003` timer low + length/timer-hi.
- **Duty sequences** (8-step, playback order): duty 0 `01000000` (12.5%),
  1 `01100000` (25%), 2 `01111000` (50%), 3 `10011111` (25% negated).
- 11-bit timer `t`; sequencer advances every **(t+1) APU cycles = 2(t+1) CPU cycles**;
  f = CPU / (16·(t+1)).
- **Muting** (output forced 0): sequencer bit 0, length counter 0, **t < 8**, or sweep
  **target period > $7FF**. The last two apply **continuously, even when the sweep unit
  is disabled** — the target period is recomputed every cycle from the current period.
- **Sweep**: divider period P+1 half-frames; write sets a reload flag. Target =
  period ± (period >> shift); on negate, **pulse 1 adds the one's complement
  (−change−1), pulse 2 the two's complement (−change)** — an audible per-channel
  difference. On each half-frame clock: if divider==0 && enabled && shift!=0 && not
  muting → period = target; then if divider==0 || reload → divider = P, clear reload;
  else decrement.
- **Envelope** (shared with noise): start flag, divider, decay level. Quarter-frame:
  start set → clear it, decay = 15, divider = V; else clock divider, and on divider
  wrap clock decay (decrement, or reload to 15 if loop flag set). Output = V if
  constant-volume else decay level. Decay keeps running even in constant-volume mode.
- **$4003/$4007 write side effects**: length counter loaded (if channel enabled),
  **duty sequencer phase reset** (the famous click), **envelope start flag set**.
  The timer's in-flight countdown is *not* reset.

### 1.4 Triangle ($4008–$400B)

- 32-step sequence **15,14,…,1,0,0,1,…,14,15**, output 0–15, no volume control.
- Timer clocks **every CPU cycle**; sequencer steps only when **linear counter > 0 AND
  length counter > 0**. f = CPU / (32·(t+1)).
- **Linear counter**: quarter-frame → if reload flag set, counter = R; else decrement
  if > 0. Then **if control flag (bit 7 of $4008, doubles as length-halt) is clear,
  clear the reload flag**. Writing $400B sets the reload flag.
- When halted the sequencer freezes at its current value — no click.
- **Ultrasonic**: t = 0/1 gives ~28 kHz+; hardware averages to ~7.5. Emulators commonly
  skip stepping when t < 2 to avoid aliasing garbage.

### 1.5 Noise ($400C–$400F)

- 15-bit LFSR, effectively starts at 1. Timer clock: feedback = bit0 XOR (**bit6 if
  mode flag set, else bit1**); shift right; feedback → bit 14. Mode 1 gives 93/31-step
  metallic loops.
- Output = envelope volume, silenced when LFSR bit0 == 1 or length counter == 0.
- NTSC period table (CPU cycles), index PPPP:
  `4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068`.
- $400F write: load length counter, set envelope start.

### 1.6 DMC ($4010–$4013)

- NTSC rate table (CPU cycles per output bit):
  `428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54`.
- Sample address = **$C000 + A×64**; length = **L×16 + 1** bytes; reader address wraps
  **$FFFF → $8000**.
- **Output unit**: 7-bit delta counter 0–127; per timer clock, if not silenced:
  shift-reg bit 1 → +2 (if ≤ 125), bit 0 → −2 (if ≥ 2); shift; every 8 clocks refill
  shift register from the 1-byte sample buffer (or set silence flag if empty).
- **Memory reader**: buffer empty && bytes remaining > 0 → DMA fetch (buffer ← mem,
  addr++, bytes−−). At bytes==0: loop flag → reload addr/length; else IRQ-enable →
  **set DMC IRQ flag** (level-held until cleared). The IRQ fires when the **last byte
  is fetched**, not when it finishes playing.
- **CPU stall**: each fetch stalls the CPU **1–4 cycles** (halt + dummy + optional
  alignment + the "get" read). RDY only halts on CPU **read** cycles — write cycles
  (RMW tails, interrupt pushes) delay the halt. Typical: **4 cycles** for a mid-sample
  reload fetch, **3** for a $4015-triggered start fetch. A stall overlapping a
  $2007/$4016/$4017 read causes the documented **double-read glitches** (PPU read
  buffer corruption, controller bit deletion).
- **$4011** sets the output level directly (classic PCM-by-CPU technique).

### 1.7 $4015 — status/control

- **Write `---D NT21`**: channel enables. Clearing a bit **forces that length counter
  to 0** (and reloads are blocked while disabled). DMC bit set with bytes-remaining==0
  → restart sample; bit clear → bytes = 0 (silences after the buffered byte). **Any
  write clears the DMC IRQ flag** (never the frame IRQ flag).
- **Read `IF-D NT21`**: bit7 DMC IRQ, bit6 frame IRQ, bit4 DMC bytes-remaining > 0,
  bits 3–0 length-counter > 0 (noise/tri/p2/p1). **Reading clears the frame IRQ flag**
  (not the DMC flag). Race: a read on the same cycle the flag is being set returns 1
  but does not clear it. Bit 5 is open bus.

### 1.8 Mixer

Nonlinear reference formulas (inputs pulse/tri/noise 0–15, dmc 0–127; output 0.0–1.0):

```
pulse_out = 95.88 / ((8128 / (pulse1 + pulse2)) + 100)          // 0 if both 0
tnd_out   = 159.79 / ((1 / (tri/8227 + noise/12241 + dmc/22638)) + 100)
output    = pulse_out + tnd_out
```

Lookup-table form (what everyone ships):

```
pulse_table[n] = 95.52  / (8128.0/n  + 100)   // n = 0..30  (31 entries), [0] = 0
tnd_table[n]   = 163.67 / (24329.0/n + 100)   // n = 0..202 (203 entries), [0] = 0
out = pulse_table[p1 + p2] + tnd_table[3*tri + 2*noise + dmc]
```

Linear approximation (audibly close, slightly loud DMC):
`0.00752·(p1+p2) + 0.00851·tri + 0.00494·noise + 0.00335·dmc`.

Post-DAC the console applies first-order filters: **high-pass ~90 Hz, high-pass
~440 Hz, low-pass ~14 kHz**.

### 1.9 Power-up / reset

- Power-up and reset act as **$4015 = $00** (all channels off).
- **$4017 = $00 at power-up — 4-step mode with frame IRQ enabled.** Games must write
  $40 or serve the IRQ; an emulator that never delivers the frame IRQ will still boot
  most games (they write $40), but `apu_reset` ROMs check this state. On reset the
  last-written $4017 value is retained/reapplied; the frame counter behaves as if
  $4017 were written ~9–12 cycles before the first instruction.
- Noise LFSR ≈ 1; triangle phase 0 (output 15, not reset on soft reset on some
  revisions); DMC $4011 &= 1 on reset. Beyond the standard pattern these vary by 2A03
  revision — model the blargg-tested subset, document the rest as unmodeled.

---

## 2. olcNES `olc2A03` (Part #7)

The header self-declares `THIS CLASS IS VERY UNFINISHED`, and the 2026-05 review pass
(`olcnes_review.md` §8) already flagged it as the weakest part of the repo. The detailed
read confirms and sharpens that verdict.

### What it actually contains

- **Pulse 1, Pulse 2, Noise only.** Triangle is an empty `case 0x4008: break;`; DMC
  ($4010–$4013) is entirely absent; **$4017 is unhandled** — no 5-step mode, no IRQ
  inhibit, no frame IRQ at all; **$4015 read returns 0** with the status bits commented
  out (many games poll this — a real correctness landmine).
- Small composable structs: `sequencer` (countdown timer + lambda-mutated shift
  register), `lengthcounter`, `envelope`, `sweeper`, plus `oscpulse` — a **20-harmonic
  sine-sum synthesizer** that generates the audible pulse output. The hardware-style
  duty sequencer *is* clocked every APU cycle but its output is discarded behind an
  unused `bUseRawMode` flag.
- Clocking: everything gated on `clock_counter % 6 == 0` inside a per-PPU-tick
  `apu.clock()` — i.e. one APU cycle per 2 CPU cycles. 4-step frame counter only, at
  `frame_clock_counter` = **3729 / 7457 / 11186 / 14916** (≈ the real table, off-by-a-bit
  and integer-only).
- Output: ad-hoc linear mixer (`(p1−0.8)·0.1 + (p2−0.8)·0.1 + 2(noise−0.5)·0.1`), a
  one-pole smoothing hack per channel, and arbitrary gates like `env.output > 2`.
- **Emulation is clocked by the audio callback**: the sound thread runs
  `while (!nes.clock()) {}` until the bus accumulates 1/44100 s of emulated time, then
  returns the latched sample. The render thread only draws.
- Assorted bugs: noise LFSR seeded `0xDBDB` and the short-mode flag ignored; a $400F
  write restarts *all three* envelopes; sweep `track()` runs every PPU clock and
  mutates the sequencer reload directly.

### Verdicts

| olcNES thing | Verdict for deloNES |
|---|---|
| Struct decomposition: sequencer / envelope / length counter / sweeper as small units | **Copy the shape** — it maps directly onto the `AudioChannel`-style decomposition sketched in `olcnes_review.md` §8, and each unit is unit-testable against §1 of this doc |
| Length table `{10,254,20,2,…,32,30}` | **Copy** (it's the hardware table; also in §1.3) |
| Frame-counter cadence 3729/7457/11186/14916 @ CPU/2 | **Copy the idea, not the numbers** — use the CPU-cycle table in §1.2 (7457/14913/22371/29828-30) so blargg timing tests can ever pass; olc's integer-only 4-step version can't |
| `oscpulse` 20-harmonic sine synthesis | **Do not copy.** It sidesteps aliasing but abandons the hardware model (no duty-phase reset click, wrong interaction with sweep muting, needs `double` time). §4's delta/blip approach gets both accuracy and quality |
| Audio-callback-driven emulation (`while(!clock()){}` on the sound thread) | **Do not copy.** It inverts control of the master clock and is irreconcilable with deloNES's `runFrame()` hosts, the headless harness, and TAS determinism (emulation speed coupled to the host audio device) |
| $4015 read stub, missing frame IRQ / 5-step / triangle / DMC | **Anti-checklist** — these are exactly the items blargg's `apu_test` suite fails first; treat olc's omissions as our phase list |
| Per-PPU-tick `apu.clock()` gated by `% 6` | **Avoid** — deloNES should hook the APU at CPU rate (see §6/§7); running a mostly-empty method 3× too often is TeaVM hot-path poison |
| Linear hand-tuned mixer | **Avoid**; use the nonlinear lookup tables (§1.8) — same cost, correct balance |

deloNES has already copied from olcNES elsewhere (master-tick `% 3` divider, DMA state
machine, loopy pipeline). The APU is the first subsystem where the calibrated answer is
"copy the decomposition, copy almost none of the behavior."

---

## 3. bugzmanov NES ebook

Re-verified 2026-07: the TOC lists Chapter 9 "Emulating APU" but the page is **still a
"coming soon" placeholder**, and the companion repo contains **zero APU code** (code
folders stop at ch8). This matches the gap already recorded in
`bugzmanov_nes_ebook_review.md`. **Nothing usable for APU work** — the book's value to
this project (PPU/scrolling/joypad pedagogy) is already banked.

---

## 4. Production references: Mesen2 and blargg

### 4.1 Mesen2 (`Core/NES/APU/`)

The most instructive reference for architecture. Key decisions:

**Lazy catch-up clocking, not per-cycle synthesis.** `NesApu::ProcessCpuClock()` runs
every CPU cycle but normally only increments `_currentCycle`. Real work (`Run()`)
happens only when: (a) an APU register is read/written, (b) the frame counter is about
to hit a step edge or has a pending $4017 write, (c) the DMC needs a fetch/clock, or
(d) the audio frame ends. `Run()` advances the frame counter through the elapsed span
(the frame counter reports how far it can go before the next quarter/half event), runs
each channel to the target cycle, then applies pending length-counter reloads (ordering
matters for the reload-while-clocking edge case). Channels use a composed `ApuTimer`:
`while (timer.Run(target)) { step waveform }` — pure integer catch-up.

**Delta-based output.** A channel only tells the mixer anything when its output
*changes*: `AddDelta(channel, cycleStamp, newOutput − lastOutput)`. At `EndFrame()` the
mixer walks the sorted timestamps, evaluates the **nonlinear mix at each transition**,
and feeds amplitude deltas into **blip_buf** (band-limited resampling to a fixed output
rate). Nonlinear accuracy *and* alias-free output, at cost proportional to transitions
(thousands/frame) instead of CPU cycles (~29,780/frame) or output samples.

**Frame counter.** 6-entry CPU-cycle step tables exactly as §1.2 (NTSC 4-step
`7457, 14913, 22371, 29828, 29829, 29830`; 5-step `…, 29829, 37281, 37282`), a
`_writeDelayCounter` for the 3/4-cycle $4017 delay, and the 3-cycle IRQ window.

**DMC stall lives in the CPU, not the APU.** `StartDmcTransfer()` sets halt/dummy-read
flags; the CPU's DMA processor aligns to get/put cycles via `CycleCount & 1` and
reproduces the $4015/$4016/$4017 double-read glitch by reissuing the internal register
read (`0x4000 | (addr & 0x1F)`). Enable-via-$4015 schedules the first fetch with a
parity-dependent 2/3-cycle delay; disable takes effect one APU cycle late and a DMA
cancelled in that window still halts the CPU 1 cycle.

### 4.2 blargg's Nes_Snd_Emu / Blip_Buffer

The original source of the catch-up pattern (`run_until(time)` per oscillator, register
accesses synthesize-to-now first, `end_frame()` closes the timeframe). Also notable:
**IRQ prediction** (`earliest_irq()` + change callback) so a scheduling CPU loop need
not poll — deloNES's per-tick polling makes this unnecessary for us.

**Blip_Buffer in one paragraph** (enough to decide whether to port it): a naive
resampler quantizes waveform edges to the output sample grid, folding ultrasonic energy
back into the audible band — high pulse/triangle notes acquire inharmonic whistles and
pitch error. Blip instead precomputes a windowed-sinc **band-limited step kernel**
(~16–32 samples wide) at ~32 sub-sample phases. `add_delta(clockTime, amplitudeDelta)`
converts the clock time to a fixed-point output-sample position, uses the fractional
part to pick a kernel phase, and adds `delta × kernel` into an accumulation buffer —
O(kernel width) per *transition*. Reading samples runs an integrator with a slight leak
(which doubles as the DC-block/high-pass). It is ~300 lines of integer math with no
FP in the hot path — **directly portable to Java/TeaVM**.

**Quality ladder** for deloNES to choose from (all keep the same channel-side
`addDelta(cycle, delta)` interface, so upgrading is a mixer-only change):

1. *Nearest-sample / box-average per output sample* — simplest, audible aliasing on
   high notes; fine for "does audio work" bring-up.
2. *Linear interpolation of edge positions* — noticeably better, may be acceptable.
3. *blip-style band-limited steps* — transparent; what Mesen/blargg/FCEUX-family ship.

### Verdicts

| Pattern | Verdict for deloNES |
|---|---|
| Catch-up (`Run()`-on-demand) clocking | **Adopt, in a simplified form.** Full Mesen laziness is an optimization; the *contract* to adopt is "state is exact at every register access and IRQ edge." A first implementation may simply clock every CPU turn (correct by construction, ~1 method call per CPU cycle); keep the door open by driving everything off a cycle counter rather than mutable "elapsed" state. See §7 |
| 6-entry frame-counter tables + $4017 write-delay counter | **Copy exactly** — this is the shape blargg's timing tests demand |
| `ApuTimer` integer countdown composed into each channel | **Copy** — it is the Java-friendly version of olc's `sequencer` without the lambda hack |
| Delta output + evaluate nonlinear mix at transition timestamps | **Copy the interface now** even if the first mixer is a box filter — it is what makes blip a drop-in later |
| DMC stall modeled on the CPU/DMA side | **Copy** — deloNES already has the right home for it (`DmaController` / the CPU-turn arbitration in `CPUBus.clock()`), see §6 |
| blip_buf port | **Defer but plan for** — mixer-only upgrade behind the delta interface |
| Mesen's $4015/$4016/$4017 double-read glitch modeling | **Defer** — only `dmc_dma_during_read4` ROMs and a handful of games care; record as explicit non-goal for v1 |

---

## 5. Test ROM inventory (christopherpow/nes-test-roms)

The decisive finding: **`apu_test` + `apu_reset` give 14 fully headless ROMs using
blargg's $6000 protocol** (status at $6000: $80 running, $81 needs-reset, <$80 result,
0 = pass; magic $DE $B0 $61 at $6001–$6003; zero-terminated text at $6004). They need
only CPU + APU + RAM at $6000–$7FFF — a perfect fit for a `NestestTest`-style JUnit
harness (`assumeTrue` skip-if-absent per the repo's real-ROM policy). The older 2005
suite and the DMC-DMA tests report on screen only → second tier via the harness's
framebuffer assertions.

### 5.1 `apu_test/rom_singles/` (blargg 2011 — the backbone)

| ROM | Validates | Accuracy needed |
|---|---|---|
| `1-len_ctr.nes` | Length counter load/halt/$4015-clear on all 4 channels | register-level |
| `2-len_table.nes` | All 32 length-table entries | register-level |
| `3-irq_flag.nes` | Frame IRQ flag set in 4-step, cleared by $4015 read and $4017 bit 6, never set in 5-step | register-level + basic frame counter |
| `4-jitter.nes` | $4017 write-parity jitter (0–3 cycle divider phase) | cycle-exact frame counter |
| `5-len_timing.nes` | Exact CPU cycle of length clocks, both modes | cycle-exact |
| `6-irq_flag_timing.nes` | Flag sets exactly 29831 cycles after $4017=$00 | cycle-exact |
| `7-dmc_basics.nes` | DMC start/restart, addr+length reload, DMC IRQ, loop flag | functional DMC (no stall timing) |
| `8-dmc_rates.nes` | All 16 DMC rate periods | functional DMC |

### 5.2 `apu_reset/` (power-up/reset state; all $6000-protocol)

`4015_cleared`, `4017_written` ($00 at power, retained at reset), `4017_timing`
(frame counter offset 9–12 cycles at boot), `irq_flag_cleared`, `len_ctrs_enabled`,
`works_immediately`. Note **$81 = "press reset"**: the harness must support a mid-test
`reset()` plus a ~100 ms (several frames) delay.

### 5.3 `blargg_apu_2005.07.30/` (screen + beep-count reporting only)

`01.len_ctr` … `11.len_reload_timing`. Mostly superseded by `apu_test`; unique extra
coverage worth framebuffer-hash automation: **`08.irq_timing`** (when the handler runs
vs. the $4017 write), **`09.reset_timing`**, **`10.len_halt_timing`**,
**`11.len_reload_timing`** (reload written at the clocking instant). `pal_apu_tests/`
is the PAL rebuild — skip (deloNES is NTSC-only).

### 5.4 DMC/DMA interaction tier (screen-reporting, needs PPU and cycle-exact stalls)

- `dmc_tests/`: `status.nes`, `status_irq.nes`, `buffer_retained.nes`, `latency.nes`.
- `dmc_dma_during_read4/`: `dma_2007_read`, `double_2007_read`, `read_write_2007`,
  `dma_2007_write`, `dma_4016_read` — the double-read glitches (§4.1).
- `sprdma_and_dmc_dma/` (+`_512`) — OAM-DMA vs DMC-DMA collision cycle counts.

### 5.5 Audible-only (defer until audio output exists; verify by ear)

`apu_mixer/{square,triangle,noise,dmc}.nes` (DMC-DAC cancellation of each channel —
validates the nonlinear mixer), `volume_tests/volumes.nes`. Not automatable.

**Gap to note:** there is **no automated pass/fail ROM for envelope or sweep** —
those must be covered by unit tests written directly against §1.3 of this doc.

### 5.6 Recommended progression (matched to implementation phases)

1. **Phase 1 — length counters + $4015 + frame-counter skeleton:**
   `1-len_ctr`, `2-len_table`, `3-irq_flag`, then `apu_reset/{4015_cleared,
   len_ctrs_enabled, irq_flag_cleared, works_immediately}`.
2. **Phase 2 — envelope + sweep + all four tone channels:** unit tests only (no ROM
   exists); audible spot-check later via `apu_mixer`.
3. **Phase 3 — cycle-exact frame counter + frame IRQ:** `4-jitter`, `5-len_timing`,
   `6-irq_flag_timing`, `apu_reset/{4017_timing,4017_written}`; optional
   framebuffer-hash tier: 2005 suite `08/09/10/11`.
4. **Phase 4 — DMC + DMA timing:** `7-dmc_basics`, `8-dmc_rates`, then `dmc_tests/*`,
   then `dmc_dma_during_read4/*` and `sprdma_and_dmc_dma/*` (endgame; explicitly
   optional).

---

## 6. deloNES-specific integration notes

What the codebase offers today, and where the APU must plug in:

- **`components/APU.java` is a 63-line passive register stub**: a 32-byte array over
  $4000–$401F, writes stored, reads echoed back. No clock, no side effects. Everything
  in §1 is greenfield. One subtlety already wired in `CPUBus.write()`: a $4017 write
  goes to **both** the controller *and* the APU (comment: "$4017 also = APU frame
  counter") — correct and keep; but note `CPUBus.read()` routes $4015 reads to the APU
  and $4016/$4017 reads to the controller only, which is right ($4015 is the only
  readable APU register).
- **Clock cadence** (`CPUBus.clock()`): PPU every master tick, CPU every 3rd via an
  int `phase` counter (TeaVM-motivated; avoid adding `%` ops on the hot path). **The
  natural APU hook is inside the `phase == 0` branch** — one `apu.clock()` per CPU
  cycle, with the APU internally halving for APU-cycle units (and not halving for the
  triangle timer). Do *not* clock the APU every master tick (olc's mistake, 3× waste).
- **IRQ delivery pattern exists**: `NesSystem.tick()` polls
  `cart.mapperIrqPending() && cpu.irq()` and clears on success — a level-held line
  retried while the I flag masks it, exactly matching `CPU6502.irq()`'s documented
  contract. **The APU frame IRQ and DMC IRQ should ride the same pattern**:
  `apu.irqAsserted()` = (frameIrqFlag && !inhibit) || dmcIrqFlag, polled in
  `NesSystem.tick()`; the flag is *not* cleared by the CPU taking the interrupt (only
  by $4015 read / $4017 bit 6 / $4015 write per §1.7), so unlike the mapper there is
  no `irqClear()` call on success — the level stays asserted until software clears the
  flag, which is precisely the hardware behavior and what `3-irq_flag` tests.
- **`DmaController` is OAM-DMA only** but already models the get/put phase idea
  (even/odd master ticks) and owns the "steal the CPU turn" arbitration slot in
  `CPUBus.clock()`. DMC DMA can either extend this class or sit beside it; the
  arbitration point (`phase == 0` branch) is shared, and the OAM+DMC collision rules
  (§5.4) only matter if both are active — DMC wins, OAM pauses. A v1 DMC can use a
  flat 4-cycle stall through the same slot; the alignment/dummy-read refinement and
  the double-read glitch are later work.
- **Hosts have no audio path at all.** Desktop runs one `runFrame()` per LibGDX render
  (vsync-paced); web runs one per rAF. LibGDX offers `AudioDevice.writeSamples()` on
  desktop; the web (TeaVM) build needs a WebAudio bridge. The APU must therefore
  expose samples as data (ring buffer / `float[]` per frame), never own a device —
  consistent with the olcnes_review verdict ("Bus returns sample-ready; audio thread
  pulls") and required for headless determinism.
- **TAS/determinism constraints** (`headless-harness-plan.md`): core must stay free of
  wall-clock and `Random`; movies pin `emu-version` and desync loudly on timing
  changes. Two direct implications:
  1. **DMC stalls change CPU instruction timing** → adding the DMC (or even the frame
     IRQ, which changes interrupt timing for games that don't set $40) is a
     movie-invalidating change. Bump `MovieFormat.EMU_VERSION` when the APU lands, and
     land the timing-affecting pieces (frame IRQ, DMC stalls) in as few version bumps
     as possible — ideally one.
  2. **Audio generation must be a pure function of the tick stream** — sample count per
     frame derived from cycle counts, no host-rate feedback into emulation (rules out
     olc's audio-thread-driven clocking a second time). Downsampling state (fractional
     resample position) must be excluded from determinism proofs or made exactly
     reproducible; the D3 framebuffer-hash proofs are unaffected either way if audio is
     observation-only.
- **Nestest CYC baseline**: `NestestTest`'s 8992/8992 match is the regression gate on
  the bus/CPU path. nestest writes $4015/$4017 during its init and reads $4015 in the
  official-opcode section; today's stub echoes stored bytes back. A real $4015 read
  (status bits, mostly 0 early on) and the APU clock hook must not perturb CPU cycle
  counts — the APU adds **no** CPU cycles except via DMC stalls, and nestest plays no
  DMC samples, so the baseline should hold; treat any nestest diff after the APU lands
  as a bus-wiring bug. (Frame IRQ: nestest.nes runs with I set / $4017=$40 patterns in
  the automated path — but verify on first integration rather than assuming.)
- **Power-up state**: deloNES pins "zero RAM" boot determinism. APU power-up per §1.9
  is naturally all-zero except the noise LFSR (=1) and frame-counter phase — pin those
  in code and in the movie-format `loader` tag semantics.

---

## 7. Recommended architecture for deloNES

### Cycle model

- **CPU-cycle-driven, eager, integer-only.** `APU.clock()` called once per CPU cycle
  from the `phase == 0` branch of `CPUBus.clock()`. Internally: a `cpuCycle` counter;
  pulse/noise/DMC timers decremented on odd/even parity (one APU cycle per 2 calls);
  triangle timer every call; frame counter compared against the 6-entry CPU-cycle
  table (§1.2) with a small pending-$4017-write delay counter (3/4 by parity).
- Mesen-style lazy catch-up is a **recorded optimization path, not v1**: the eager
  version is simpler, TeaVM-friendly (no long math needed — wrap the frame counter at
  29830/37282), and trivially correct at register-access boundaries. If web profiling
  shows `apu.clock()` hurting, the catch-up refactor is contained inside the APU.
- Class shape (per olcnes_review §8, confirmed by Mesen's decomposition):
  `APU` owns `PulseChannel ×2` (constructor flag for the sweep complement difference),
  `TriangleChannel`, `NoiseChannel`, `DmcChannel`, `FrameCounter`; shared small units
  `Envelope`, `LengthCounter`, `Sweep`, `Divider/ApuTimer`. Every unit gets direct
  unit tests against §1 (this covers the envelope/sweep ROM gap, §5.5).

### Clocking integration point

- `CPUBus.clock()` `phase == 0` branch: `apu.clock()` **every** CPU turn, including
  DMA-stall turns (the APU never stops; only the CPU does). Order: APU clock can run
  before or after `cpu.clock()` — pick one, document it, and hold it constant (it's
  part of the movie determinism contract).

### IRQ delivery

- Level-held flags polled in `NesSystem.tick()` alongside the mapper IRQ:
  `if (apu != null && apu.irqAsserted()) cpu.irq();` — no clear-on-taken (software
  clears via $4015 read / $4017 bit 6 / $4015 write). DMC IRQ and frame IRQ are
  independent flags OR'd into one line, individually visible in $4015 bits 6/7.

### DMC DMA / CPU stall

- v1: when the DMC sample buffer needs a byte, request a stall through the existing
  CPU-turn arbitration (same slot as `DmaController`) — flat 4-cycle stall, byte
  fetched via `cpuBus.read()`. OAM/DMC collision: DMC first (rarely co-occurs).
- v2 (only if chasing `dmc_dma_during_read4`): get/put alignment, 3-vs-4 cycle
  variants, halt-only-on-read-cycles, double-read glitch. Explicit non-goal for v1.

### Mixer / output

- **Nonlinear lookup tables** (`pulse_table[31]`, `tnd_table[203]`, §1.8) from day one.
- **Channel→mixer interface is delta-with-cycle-timestamp** (`addDelta(cycle, delta)`
  or equivalently "mixer samples channel outputs at transition times"), even though the
  v1 downsampler is a simple per-output-sample box average at ~44.1 kHz
  (29780.5 CPU cycles/frame ÷ 735 samples/frame ≈ 40.5 cycles/sample, fixed-point
  accumulator). This makes a Java blip_buf port (~300 lines, integer math, TeaVM-safe)
  a drop-in mixer upgrade later. Add the 90 Hz HP + 14 kHz LP first-order IIRs on the
  output stream (cheap, and the HP doubles as DC-blocker for the nonlinear DAC offset).
- **APU produces samples into a buffer; hosts pull.** Desktop: LibGDX `AudioDevice`
  fed once per frame with light buffer-fullness-based pacing. Web: WebAudio bridge
  (later phase). Headless: samples discarded or asserted on. Emulation speed is never
  derived from the audio device.

### Testing strategy

1. Unit tests per hardware unit (envelope, sweep incl. one's/two's-complement split,
   length table, linear counter, LFSR both modes, duty sequences, DMC delta clamp,
   frame-counter event table incl. $4017 delay + immediate-clock, $4015 semantics
   incl. read-clears-frame-IRQ and the window re-assertion semantics (see the corrected §1.7)).
2. Blargg harness: `NestestTest`-style headless runner for the $6000 protocol (needs
   nothing new — RAM at $6000 is already addressable via cartridge PRG-RAM or a test
   ram; support the $81-reset handshake). Progression per §5.6; gate CI on the Phase
   1 set immediately, add ROMs as phases land. Skip-if-absent per repo policy
   (these ROMs are freely redistributable — they can be checked into
   `core/src/main/resources/roms/` like nestest if desired).
3. `NestestTest` 8992/8992 stays green at every phase (bus-regression gate).
4. Determinism: extend the harness D3 hash proofs to include an FNV-1a hash of the
   audio sample stream once audio lands.

---

## 8. Open decisions for the planner

1. **Phasing/PR split** — adopt the 4-phase order of §5.6 as the implementation plan?
   Where does "audible output on desktop" land relative to correctness phases (audio
   output needs only Phase 1–2 channel state; it can ship early for morale or late for
   rigor)?
2. **`EMU_VERSION` bump strategy** — one bump covering frame-IRQ + DMC-stall timing
   changes together (fewest movie invalidations), or per-phase bumps? Interacts with
   whether any movies exist before the APU lands.
3. **$4015/$4017 bus routing** — keep the current "controller AND apu both see $4017
   writes" special case, or refactor $4000–$401F routing now that the APU becomes
   active? (Also: should $4014/$4016/$4017 open-bus/read behavior be tightened while
   in there?)
4. **DMC stall mechanism** — extend `DmaController` into a general DMA/stall
   arbiter, or a separate `DmcDma` sharing the CPU-turn slot? And is the v1 flat
   4-cycle stall acceptable, or is get/put alignment wanted immediately (only the
   `dmc_dma_during_read4` tier cares)?
5. **Mixer v1 quality rung** — box-average (simplest), linear-interp, or immediate
   blip_buf port? (Recommendation: box-average v1 behind the delta interface;
   blip port as a self-contained later PR.)
6. **Desktop audio pacing** — deloNES paces by vsync/rAF, not audio. Accept drift
   (~0.1–1% rate mismatch → occasional buffer under/overrun, handled by
   drop/stretch), or add buffer-fullness feedback into frame pacing? (Never into
   emulation content — determinism.)
7. **Test-ROM residency** — check blargg APU ROMs into `core/src/main/resources`
   (they are freely redistributable, like nestest) vs. the skip-if-absent real-ROM
   convention. Affects whether CI actually runs the suite.
8. **Frame-IRQ power-up default** — model $4017=$00 (IRQ enabled) at power-up
   faithfully from day one (required by `apu_reset` ROMs, may surprise games until
   IRQ delivery is correct), or stage it behind Phase 3?
9. **Catch-up optimization trigger** — pin a web-profile threshold (e.g. `runFrame`
   ms regression budget) at which the Mesen-style lazy `Run()` refactor is done, so
   the eager v1 doesn't silently become a web-performance regression.
10. **Non-goals to ratify** — PAL timing, $4015/$4016/$4017 double-read glitches,
    OAM+DMC DMA collision cycle-exactness, revision-dependent power-up variance,
    `apu_mixer` audible validation. Record as explicit v1 non-goals so review passes
    don't relitigate them.

---

*End of survey.*
