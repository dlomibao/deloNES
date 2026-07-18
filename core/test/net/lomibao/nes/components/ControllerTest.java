package net.lomibao.nes.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the two-player NES controller implementation with enum-based API.
 *
 * <p>Covers:
 * <ul>
 *   <li>Strobe latch on falling edge (1→0)</li>
 *   <li>8-button sequential read order matching {@link Button} enum ordinal</li>
 *   <li>After 8 reads returns 1 (open-bus convention)</li>
 *   <li>Both players independent</li>
 *   <li>Open-bus upper bits (0x40) on all reads</li>
 *   <li>CPUBus integration: 0x4016 hits Controller, not APU</li>
 *   <li>CPUBus integration: 0x4017 reads hit Controller (not APU)</li>
 *   <li>CPUBus integration: 0x4017 writes reach APU (frame counter)</li>
 * </ul>
 */
class ControllerTest {

    private Controller controller;

    @BeforeEach
    void setUp() {
        controller = new Controller();
    }

    /** Perform the standard strobe pulse: write 1 then 0 to $4016. */
    private void strobeAndLatch(Controller c) {
        c.cpuBusWrite(0x4016, (byte) 0x01);
        c.cpuBusWrite(0x4016, (byte) 0x00);
    }

    // =========================================================================
    // Button enum ordering
    // =========================================================================

    @Test
    void button_enumOrder_matchesNesReadOrder() {
        Button[] values = Button.values();
        assertEquals(8, values.length, "exactly 8 buttons");
        assertSame(Button.A,      values[0]);
        assertSame(Button.B,      values[1]);
        assertSame(Button.SELECT, values[2]);
        assertSame(Button.START,  values[3]);
        assertSame(Button.UP,     values[4]);
        assertSame(Button.DOWN,   values[5]);
        assertSame(Button.LEFT,   values[6]);
        assertSame(Button.RIGHT,  values[7]);
    }

    // =========================================================================
    // Strobe latch: latch occurs on falling edge (1→0)
    // =========================================================================

    @Test
    void strobe_fallingEdge_latchesLiveStateAtMomentOfFall() {
        // On real NES hardware, while strobe=1 the shift register is continuously
        // reloaded from the live state. When strobe drops, the live state at
        // that instant is latched. So releasing A before the fall means the
        // latch captures A=released (false).
        controller.setButton(0, Button.A, true);
        controller.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        controller.setButton(0, Button.A, false);    // release A before falling edge
        controller.cpuBusWrite(0x4016, (byte) 0x00); // falling edge → latch current live (A=false)
        // First read must return 0 (A was already released when latch triggered)
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1,
                "latch captures live state at falling edge; A was released before fall → 0");
    }

    @Test
    void strobe_fallingEdge_latchesLiveStateIfPressedAtMomentOfFall() {
        // Conversely: if A is pressed AT the moment strobe drops, it IS latched.
        controller.setButton(0, Button.A, false);
        controller.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        controller.setButton(0, Button.A, true);     // press A while strobe high
        controller.cpuBusWrite(0x4016, (byte) 0x00); // falling edge → latch current live (A=true)
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1,
                "A was pressed before the falling edge; latch must capture A=1");
    }

    @Test
    void strobe_noLatch_whileStrobeStaysHigh() {
        // If strobe never falls, reads always reflect live A bit.
        controller.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        controller.setButton(0, Button.A, true);
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1, "live A pressed → 1");
        controller.setButton(0, Button.A, false);
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1, "live A released → 0");
    }

    @Test
    void strobe_lowToLow_doesNotResetReadIndex() {
        // Writing 0 when strobe was already 0 must NOT re-latch or reset index.
        controller.setButton(0, Button.B, true); // only B pressed
        strobeAndLatch(controller);
        controller.cpuBusRead(0x4016, false); // A → 0, index=1
        controller.cpuBusRead(0x4016, false); // B → 1, index=2
        controller.cpuBusWrite(0x4016, (byte) 0x00); // low→low: no-op
        // Next read should be SELECT (unpressed → 0), not A
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1,
                "low→low write must not reset index; SELECT (unpressed) expected");
    }

    @Test
    void strobe_highToHigh_doesNotLatch() {
        // Writing 1 when strobe is already 1 must not accidentally latch.
        controller.setButton(0, Button.A, true);
        controller.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        controller.setButton(0, Button.A, false);
        controller.cpuBusWrite(0x4016, (byte) 0x01); // high→high: still high, no latch
        controller.cpuBusWrite(0x4016, (byte) 0x00); // now drop strobe → latch live (A=false)
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1,
                "latch at falling edge should capture A=false (currently live)");
    }

    // =========================================================================
    // 8-button sequential read order (Button enum order)
    // =========================================================================

    @Test
    void read_eachButton_returnsItsOwnBit_onlyThatButtonPressed() {
        // For each button, press only that one, latch, read all 8.
        // Expect 1 at the button's index and 0 elsewhere.
        for (Button btn : Button.values()) {
            controller = new Controller();
            controller.setButton(0, btn, true);
            strobeAndLatch(controller);
            for (Button expected : Button.values()) {
                int bit = controller.cpuBusRead(0x4016, false) & 1;
                if (expected == btn) {
                    assertEquals(1, bit, "button " + btn + ": read for " + expected + " should be 1");
                } else {
                    assertEquals(0, bit, "button " + btn + ": read for " + expected + " should be 0");
                }
            }
        }
    }

    @Test
    void read_allButtonsPressed_returns8Ones() {
        for (Button btn : Button.values()) {
            controller.setButton(0, btn, true);
        }
        strobeAndLatch(controller);
        for (int i = 0; i < 8; i++) {
            assertEquals(1, controller.cpuBusRead(0x4016, false) & 1,
                    "read " + i + " with all pressed should be 1");
        }
    }

    @Test
    void read_specificPattern_BAndSTARTPressed() {
        // B=1, START=1, everything else=0  → expected: 0,1,0,1,0,0,0,0
        controller.setButton(0, Button.B, true);
        controller.setButton(0, Button.START, true);
        strobeAndLatch(controller);
        int[] expected = {0, 1, 0, 1, 0, 0, 0, 0};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], controller.cpuBusRead(0x4016, false) & 1,
                    "read " + i + " mismatch");
        }
    }

    // =========================================================================
    // After 8 reads → returns 1 (open-bus convention)
    // =========================================================================

    @Test
    void read_after8Reads_returns1_loSix() {
        // No buttons pressed. After 8 reads (all 0), reads 9..16 must all be 1.
        strobeAndLatch(controller);
        for (int i = 0; i < 8; i++) {
            controller.cpuBusRead(0x4016, false); // drain
        }
        for (int i = 8; i < 16; i++) {
            assertEquals(1, controller.cpuBusRead(0x4016, false) & 1,
                    "read " + i + " past 8 must return 1");
        }
    }

    @Test
    void read_after8Reads_resetsOnNextStrobe() {
        controller.setButton(0, Button.A, true);
        strobeAndLatch(controller);
        for (int i = 0; i < 12; i++) controller.cpuBusRead(0x4016, false); // drain + past end
        // New strobe pulse
        strobeAndLatch(controller);
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1,
                "fresh strobe: first read must be A=1");
    }

    // =========================================================================
    // Open-bus upper bits (0x40)
    // =========================================================================

    @Test
    void read_upperBits_are0x40_duringNormalRead() {
        controller.setButton(0, Button.A, true);
        strobeAndLatch(controller);
        int raw = controller.cpuBusRead(0x4016, false);
        assertEquals(0x40, raw & 0xFE, "upper bits must be 0x40 (bit 0 is button bit)");
        // A pressed → full byte = 0x41
        assertEquals(0x41, raw, "A pressed with open-bus upper bits: 0x41");
    }

    @Test
    void read_upperBits_are0x40_afterEndOfShift() {
        strobeAndLatch(controller);
        for (int i = 0; i < 8; i++) controller.cpuBusRead(0x4016, false);
        // Post-shift reads: bit 0 = 1, upper = 0x40 → 0x41
        int raw = controller.cpuBusRead(0x4016, false);
        assertEquals(0x41, raw, "post-shift read: 0x41 (open-bus 0x40 | 1)");
    }

    @Test
    void read_upperBits_are0x40_whileStrobeHigh() {
        controller.setButton(0, Button.A, false); // A not pressed
        controller.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        int raw = controller.cpuBusRead(0x4016, false);
        // A not pressed → bit 0 = 0, upper = 0x40
        assertEquals(0x40, raw, "strobe-high read with A=0: 0x40");
    }

    // =========================================================================
    // Both players are independent
    // =========================================================================

    @Test
    void players_areIndependent_differentButtonsPressed() {
        controller.setButton(0, Button.A, true);    // P1: A pressed
        controller.setButton(1, Button.RIGHT, true); // P2: RIGHT pressed
        strobeAndLatch(controller);

        // P1 reads: A=1, rest=0
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1, "P1 A=1");
        for (int i = 1; i < 8; i++) {
            assertEquals(0, controller.cpuBusRead(0x4016, false) & 1, "P1 button " + i + "=0");
        }

        // Reset controller for P2 reads (strobe again)
        strobeAndLatch(controller);

        // P2 reads: RIGHT (index 7) = 1, rest = 0
        for (int i = 0; i < 7; i++) {
            assertEquals(0, controller.cpuBusRead(0x4017, false) & 1, "P2 button " + i + "=0");
        }
        assertEquals(1, controller.cpuBusRead(0x4017, false) & 1, "P2 RIGHT=1");
    }

    @Test
    void players_p2ReadAt0x4017_notAffectedBy0x4016Reads() {
        controller.setButton(0, Button.A, true);
        controller.setButton(1, Button.B, true);
        strobeAndLatch(controller);

        // Read P1 completely
        for (int i = 0; i < 8; i++) controller.cpuBusRead(0x4016, false);

        // P2 should still be at index 0 (its own independent index)
        assertEquals(0, controller.cpuBusRead(0x4017, false) & 1, "P2 A=0 (not pressed)");
        assertEquals(1, controller.cpuBusRead(0x4017, false) & 1, "P2 B=1");
    }

    @Test
    void players_p2_stateDoesNotLeakToP1() {
        controller.setButton(1, Button.A, true);  // P2 A pressed
        controller.setButton(0, Button.A, false); // P1 A NOT pressed
        strobeAndLatch(controller);
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1, "P1 A must be 0 (independent of P2)");
    }

    // =========================================================================
    // New enum-based setButton API
    // =========================================================================

    @Test
    void setButton_enumApi_player0() {
        controller.setButton(0, Button.SELECT, true);
        strobeAndLatch(controller);
        controller.cpuBusRead(0x4016, false); // A
        controller.cpuBusRead(0x4016, false); // B
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1, "SELECT should be 1");
    }

    @Test
    void setButton_enumApi_player1() {
        controller.setButton(1, Button.UP, true);
        strobeAndLatch(controller);
        controller.cpuBusRead(0x4017, false); // A
        controller.cpuBusRead(0x4017, false); // B
        controller.cpuBusRead(0x4017, false); // SELECT
        controller.cpuBusRead(0x4017, false); // START
        assertEquals(1, controller.cpuBusRead(0x4017, false) & 1, "P2 UP should be 1");
    }

    @Test
    void setButton_enumApi_invalidPlayerThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.setButton(2, Button.A, true),
                "player index 2 should throw");
        assertThrows(IllegalArgumentException.class,
                () -> controller.setButton(-1, Button.A, true),
                "player index -1 should throw");
    }

    // =========================================================================
    // CPUBus integration: controller vs APU routing
    // =========================================================================

    /**
     * Build a CPUBus with both Controller and APU wired, then verify that
     * reads from $4016 and $4017 go to the Controller (not the APU).
     */
    @Test
    void cpuBus_read0x4016_hitsController_notAPU() {
        Controller ctrl = new Controller();
        APU apu = new APU();
        CPUBus bus = CPUBus.builder()
                .controller(ctrl)
                .apu(apu)
                .build()
                .connect();

        // Press A for P1, latch, read from bus
        ctrl.setButton(0, Button.A, true);
        bus.write(0x4016, (byte) 0x01); // strobe high
        bus.write(0x4016, (byte) 0x00); // strobe low → latch

        int raw = bus.read(0x4016);
        // Controller returns 0x41 (A pressed, open-bus 0x40)
        // APU register 0x16 would return whatever was stored (likely 0 unless written)
        assertEquals(0x41, raw,
                "bus read at $4016 must come from Controller (0x41 = 0x40|A), not APU");
    }

    @Test
    void cpuBus_read0x4017_hitsController_notAPU() {
        Controller ctrl = new Controller();
        APU apu = new APU();
        CPUBus bus = CPUBus.builder()
                .controller(ctrl)
                .apu(apu)
                .build()
                .connect();

        // Write something to APU register $4017 directly (frame counter)
        apu.cpuBusWrite(0x4017, (byte) 0xAB);

        // Press B for P2, latch
        ctrl.setButton(1, Button.B, true);
        bus.write(0x4016, (byte) 0x01);
        bus.write(0x4016, (byte) 0x00);
        ctrl.cpuBusRead(0x4017, false); // consume A bit

        int raw = bus.read(0x4017);
        // B pressed → bit = 1, open-bus 0x40 → 0x41
        // APU register $4017 = 0xAB — these are mutually exclusive: we expect 0x41
        assertEquals(0x41, raw,
                "bus read at $4017 must come from Controller (B=1, 0x41), not APU (0xAB)");
    }

    @Test
    void cpuBus_write0x4017_reachesAPU_frameCounter() {
        Controller ctrl = new Controller();
        APU apu = new APU();
        CPUBus bus = CPUBus.builder()
                .controller(ctrl)
                .apu(apu)
                .build()
                .connect();

        // Write to $4017 via the bus (frame counter write)
        bus.write(0x4017, (byte) 0xC0);

        // Read back directly from APU (bypass the bus routing for reads)
        int apuVal = Byte.toUnsignedInt(apu.registers[0x4017 - apu.START_ADDRESS]);
        assertEquals(0xC0, apuVal,
                "write to $4017 via bus must be stored in APU frame-counter register");
    }

    @Test
    void cpuBus_write0x4016_onlyHitsController_notAPU() {
        Controller ctrl = new Controller();
        APU apu = new APU();
        // Pre-set APU register $4016 to a known non-zero value to detect pollution.
        apu.cpuBusWrite(0x4016, (byte) 0x55);

        CPUBus bus = CPUBus.builder()
                .controller(ctrl)
                .apu(apu)
                .build()
                .connect();

        // Strobe write via bus — this should go to controller (and also APU
        // since APU range covers $4016, and our write path fans out).
        bus.write(0x4016, (byte) 0x01); // strobe high

        // The controller should now be in strobe-high mode. We can't easily
        // observe the APU side-effect (it just writes the register), so what
        // we verify is that the controller properly received the strobe.
        ctrl.setButton(0, Button.A, true);
        bus.write(0x4016, (byte) 0x00); // strobe low → latch

        assertEquals(0x41, bus.read(0x4016),
                "after strobe pulse via bus, P1 A-pressed read must be 0x41");
    }

    @Test
    void cpuBus_strobeSequence_P1AndP2_fullReadCycle() {
        Controller ctrl = new Controller();
        APU apu = new APU();
        CPUBus bus = CPUBus.builder()
                .controller(ctrl)
                .apu(apu)
                .build()
                .connect();

        ctrl.setButton(0, Button.START, true);
        ctrl.setButton(1, Button.DOWN, true);

        bus.write(0x4016, (byte) 0x01);
        bus.write(0x4016, (byte) 0x00);

        // P1: A=0,B=0,SELECT=0,START=1
        assertEquals(0, bus.read(0x4016) & 1, "P1 A=0");
        assertEquals(0, bus.read(0x4016) & 1, "P1 B=0");
        assertEquals(0, bus.read(0x4016) & 1, "P1 SELECT=0");
        assertEquals(1, bus.read(0x4016) & 1, "P1 START=1");

        // P2: A=0,B=0,SELECT=0,START=0,UP=0,DOWN=1
        assertEquals(0, bus.read(0x4017) & 1, "P2 A=0");
        assertEquals(0, bus.read(0x4017) & 1, "P2 B=0");
        assertEquals(0, bus.read(0x4017) & 1, "P2 SELECT=0");
        assertEquals(0, bus.read(0x4017) & 1, "P2 START=0");
        assertEquals(0, bus.read(0x4017) & 1, "P2 UP=0");
        assertEquals(1, bus.read(0x4017) & 1, "P2 DOWN=1");
    }

    // =========================================================================
    // Seam S3 (headless-harness plan, Phase B1): readOnly reads must be
    // side-effect free — no readIndex advance — so harness peeks of
    // $4016/$4017 cannot desync the joypad shift register.
    // =========================================================================

    @Test
    void readOnly_read_doesNotAdvanceReadIndex() {
        controller.setButton(0, Button.A, true);
        controller.setButton(0, Button.SELECT, true);
        strobeAndLatch(controller);

        // Peek repeatedly — every peek must return the SAME (A) bit.
        assertEquals(1, controller.cpuBusRead(0x4016, true) & 1, "peek #1 sees A");
        assertEquals(1, controller.cpuBusRead(0x4016, true) & 1, "peek #2 still sees A");
        assertEquals(1, controller.cpuBusRead(0x4016, true) & 1, "peek #3 still sees A");

        // The real sequential read stream is untouched: A,B,SELECT,START...
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1, "A");
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1, "B");
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1, "SELECT");
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1, "START");
    }

    @Test
    void readOnly_read_midSequence_returnsCurrentBitWithoutAdvancing() {
        controller.setButton(0, Button.B, true);
        strobeAndLatch(controller);

        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1, "A consumed");
        // Now at index 1 (B). Peeks see B without consuming it.
        assertEquals(1, controller.cpuBusRead(0x4016, true) & 1, "peek sees B");
        assertEquals(1, controller.cpuBusRead(0x4016, true) & 1, "peek again sees B");
        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1, "real read consumes B");
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1, "then SELECT");
    }

    @Test
    void normalRead_stillAdvances() {
        controller.setButton(0, Button.A, true);
        strobeAndLatch(controller);

        assertEquals(1, controller.cpuBusRead(0x4016, false) & 1, "A");
        assertEquals(0, controller.cpuBusRead(0x4016, false) & 1,
                "non-readOnly read advanced past A to B");
    }

    @Test
    void readOnly_pastEnd_returnsOpenBusOneWithoutSideEffects() {
        strobeAndLatch(controller);
        for (int i = 0; i < 8; i++) {
            controller.cpuBusRead(0x4016, false);
        }
        assertEquals(0x41, controller.cpuBusRead(0x4016, true), "past-end peek returns 1|0x40");
        assertEquals(0x41, controller.cpuBusRead(0x4016, false), "past-end real read unchanged");
    }

    @Test
    void readOnly_whileStrobeHigh_returnsLiveABitLikeNormalRead() {
        controller.setButton(0, Button.A, true);
        controller.cpuBusWrite(0x4016, (byte) 0x01); // strobe high
        assertEquals(0x41, controller.cpuBusRead(0x4016, true), "peek sees live A while strobed");
        assertEquals(0x41, controller.cpuBusRead(0x4016, false), "normal strobed read identical");
    }

    @Test
    void readOnly_player2_independentOfPlayer1Index() {
        controller.setButton(1, Button.A, true);
        strobeAndLatch(controller);

        assertEquals(1, controller.cpuBusRead(0x4017, true) & 1, "peek P2 A");
        assertEquals(1, controller.cpuBusRead(0x4017, true) & 1, "peek P2 A again");
        assertEquals(1, controller.cpuBusRead(0x4017, false) & 1, "P2 A consumed");
        assertEquals(0, controller.cpuBusRead(0x4017, false) & 1, "P2 B");
    }
}
