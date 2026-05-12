# Desktop tests review

**Agent:** general-purpose, Opus, read-only worktree off `master`.
**Scope:** all files under `desktop/test/net/lomibao/nes/`.

## Flaky `HeadlessApplicationTest` — diagnosis and proposed fix

**Root cause: double-dispose race in `HeadlessTestSupport.runFrames()`.**

When `updatesPerSecond = -1`, `HeadlessApplication.mainLoop()` skips the
`while (running)` block entirely (`graphics.getTargetRenderInterval() <
0`) and falls straight through to:

```java
listener.pause();
listener.dispose();
```

The background thread therefore *always* calls `listener.dispose()` on
the wrapper (which forwards to the user's listener) right after
`create()` completes. Meanwhile, `HeadlessTestSupport.runFrames()` waits
on the create-latch, then manually calls `listener.dispose()` itself
(line 102). So `listener.dispose()` is invoked twice.

Whether `disposeCount` reads as 1 or 2 from the JUnit assertion is a
pure scheduling race:
- Test thread observes `disposeCount` before bg thread reaches
  `listener.dispose()` → sees 1 (pass).
- Bg thread completes `dispose()` before assertion runs → sees 2 (fail).

**Evidence:**
- Suite run 4×: FAIL/PASS/FAIL/PASS — ~50% flake rate.
- Failing run XML: `dispose() should be called once ==> expected: <1>
  but was: <2>` (file:
  `desktop/build/test-results/test/TEST-net.lomibao.nes.desktop.HeadlessApplicationTest.xml`).
- `--rerun-tasks --tests HeadlessApplicationTest` isolated run ALSO
  failed once — not a cross-test interaction; a single-test scheduler
  race inside `HeadlessTestSupport.runFrames()`.
- LibGDX 1.14.0 `gdx-backend-headless` `mainLoop()` lines 105-144
  confirm the unconditional `listener.pause()` / `listener.dispose()`
  after the loop.

**Proposed fix path:**

1. Stop double-disposing. Two viable approaches:
   - (a) Let backend dispose. Count `disposeCount` for the latch-style
     guarantee; remove the explicit `listener.dispose()` in the helper;
     wait on a second latch counted-down inside `wrapper.dispose()`.
   - (b) Don't use `updatesPerSecond = -1`. Drive lifecycle entirely
     from the test thread: construct
     `MockGraphics`/`MockInput`/`MockFiles` directly without launching
     the bg thread, set `Gdx.*` statics, run
     `create()`/`render()`/`dispose()` inline, then reset statics.
2. Reset `Gdx.*` statics (`Gdx.app`, `Gdx.gl`, `Gdx.files`, `Gdx.input`,
   `Gdx.audio`, `Gdx.graphics`, `Gdx.net`) after each helper invocation.
   `HeadlessApplication` never nulls these on `exit()`.
3. `mainLoopThread.join(timeoutMs)` before returning from `runFrames()`.
   Right now the thread leaks out and continues running after the test
   method returns — the proximate source of all observed flakes.
4. Optionally clear `HeadlessNativesLoader` cached state (in practice,
   the `Gdx.*` statics are the only meaningful cross-test surface).

## Severity: critical

### HeadlessTestSupport — double-dispose race + bg-thread leak

Causes intermittent failures of
`HeadlessApplicationTest.emptyScreen_renders3FramesWithoutError`, but
also means **every** consumer (`NesGameTest`, `EmulatorScreenTest`,
`RomSelectScreenTest`) experiences non-deterministic post-test shutdown.
The backgrounds-still-running state is what makes `Gdx.*` statics
indeterminate between tests. This is the primary state-leak vector.

Source: `desktop/test/net/lomibao/nes/desktop/HeadlessTestSupport.java:85-106`.
Backend behaviour in `gdx-backend-headless` `HeadlessApplication.mainLoop()`
lines 105-144.

### EmulatorScreenTest — DonkeyKong.nes hard dependency

Both `@Test` methods FAIL on a fresh checkout because
`/roms/DonkeyKong.nes` is hardcoded and the resource is not present
(`core/src/main/resources/roms/` only contains `nestest.nes`; `index.txt`
lists only `nestest.nes`).

`EmulatorScreen.loadROM()` raises
`RuntimeException("Failed to load ROM…")` via
`RomSource$ClasspathRomSource.open()` IOException, propagated through
`assertDoesNotThrow`. No presence/absence fallback (`assumeTrue`,
try/catch, marker).

Source: `desktop/test/net/lomibao/nes/desktop/screen/EmulatorScreenTest.java:37, 88`.

**CI break the moment desktop tests run on a fresh checkout.**

### NesGameTest — same DonkeyKong dependency + wrong assertion

`nesGame_selectRom_transitionsToEmulatorScreen` selects
`/roms/DonkeyKong.nes` (will throw on any env without that ROM). Also,
the test only asserts `wasOnSelectFirst[0]` — i.e. it asserts the game
STARTED on RomSelectScreen but never asserts the post-condition the
test name implies: that after `selectRom()` the screen IS an
`EmulatorScreen`.

Source: `desktop/test/net/lomibao/nes/desktop/NesGameTest.java:33-69, 54`.

## Severity: medium

### HeadlessTestSupport.java:104 — `app.exit()` is a no-op

`HeadlessApplication.exit()` only enqueues a runnable that flips
`running = false`, but with `updatesPerSecond = -1` the bg thread never
enters the runnable-processing loop. The helper believes it's shutting
down cleanly; it isn't. Bg thread finishes on its own schedule, holding
`Gdx.app` statics the whole time.

### HeadlessTestSupport — no `Gdx.*` static-state teardown

Successive tests rely on `new HeadlessApplication(…)`'s constructor
(lines 78-83) to overwrite the statics. If a new test starts while the
previous bg thread is still in `pause()`/`dispose()`, both threads are
writing/reading the same `Gdx.app` reference.

### EmulatorScreenTest:78-111 — `togglePauseAndReset_doNotThrow` is weak

Asserts only "no exception thrown" plus `assertFalse(ref.get().isPaused())`.
`reset()` is never asserted to have actually reset state. Could trivially
assert `screen.getFrameCount() > 0` and `screen.getCpu().getPc() != 0`
(post-reset, PC should be the reset vector, not zero). Currently passes
for wrong reasons if `reset()` no-ops or throws-and-swallows.

### NesGameTest:72-76 — `nesGame_disposesCleanly` doesn't verify disposal

Only asserts `assertDoesNotThrow`. No assertion that screen was disposed,
controller / keyboardAdapter were nulled, or `Gdx.app.exit()` was
called. Comment is misleading.

### RomSelectScreenTest:32-91 — weak assertions

Both tests only assert "no exception" plus callbacks didn't fire. Could
cheaply also assert: `selectedIndex == 0` after no key inputs, `entries`
is non-empty after `show()`. Currently could not catch a regression
where `show()` failed to populate the entries list.

### KeyboardInputAdapterTest:77-101 — `SpyController extends Controller`

Tight coupling to a specific method signature in production. If
`Controller.setButton` is made final or its signature changes, every
test in this class breaks silently (won't override; hits the
super-class). Consider an explicit `ControllerSink` interface — spy
implements it, production class implements it.

## Severity: low / nit

- `HeadlessTestSupport.java:45` — Javadoc says "must be >= 0" for
  `frames` but no validation. Negative no-ops.
- `HeadlessTestSupport.java:52-83` — wrapper forwards `resize()` but
  `HeadlessApplication.mainLoop()` never calls it. Dead path.
- `iNESHeaderValidatorTest.java:73-78` — `nullHeader_fails` and
  `tooShortHeader_fails` both produce the same error message. Test
  could assert the specific message contains "short".
- `iNESHeaderValidatorTest.java:133` — mapper-66 test re-verifies the
  encoding in the test body, duplicating logic from `makeHeader()`.
  Tautological with `makeHeader`'s implementation.
- `ControlsConfigTest.java` — excellent coverage; every default
  verified against Javadoc. Class is package-private while others are
  public — inconsistent style but not a defect.
- `EmulatorScreenTest.java:7` — `AtomicBoolean` used only on test
  thread. `boolean[1]` would do; atomic is overkill and misleading
  about thread safety.
- `iNESHeaderValidatorTest.java` — class name `iNESHeaderValidatorTest`
  (lowercase initial) matches production class. Unusual for Java but
  consistent.

## Missing coverage

- **`NesGame.selectRom(RomSource)` post-condition**: never assert
  `game.getScreen() instanceof EmulatorScreen` after `selectRom()`.
  Once DonkeyKong dependency is removed, add this.
- **`NesGame.render()` polling logic** (poll only on EmulatorScreen):
  no test distinguishes the two branches. Inject a spy
  `KeyboardInputAdapter` (or counting `KeyState`) and assert
  `isPressed` was never queried on `RomSelectScreen`.
- **`NesGame.onQuit` (Escape on RomSelectScreen)**: never tested. Wire
  it to a spy `Game` and verify.
- **`NesGame.returnToMenu()` after ROM loaded**: not tested. Once ROM
  dependency fixed, exercise `emulatorScreen.requestExit()`, assert
  `game.getScreen() instanceof RomSelectScreen` and that previous
  EmulatorScreen was disposed.
- **`KeyboardInputAdapter` — P1 vs P2 cross-talk**: each test
  exercises one player. No test verifies P1's Z key does NOT register
  P2's A button (or vice versa).
- **`KeyboardInputAdapter` — `false` propagation**: release tests
  verify the false-edge call but no test verifies on an idle frame
  (no keys pressed) that the adapter calls `setButton(player, button,
  false)` for every button.
- **`ControlsConfig.load(FileHandle)` — malformed JSON**: not tested.
  Currently throws; pin contract.
- **`EmulatorScreen.requestExit()`**: not tested. `onExit` callback
  wiring exercised only negatively.
- **`iNESHeaderValidator` — mapper number corner case**:
  `((b6 >> 4) & 0x0F) | (b7 & 0xF0)` for mapper = `0x10` (high nibble
  only) is missing.
- **Headless determinism harness**: no test verifies
  `HeadlessTestSupport.runFrames` can be called twice in succession.
  Add one — surfaces the static-Gdx leak immediately.

---

**Working tree:** clean.
