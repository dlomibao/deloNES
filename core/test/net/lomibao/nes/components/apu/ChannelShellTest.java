package net.lomibao.nes.components.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A2 — the length-counter-only channel shells and the state they
 * carry for A4 reset semantics (noise LFSR, triangle phase) and Phase B
 * (sweep complement flag).
 */
class ChannelShellTest {

    @Test
    void pulseShells_recordSweepComplementSplit() {
        assertTrue(new PulseChannel(true).isOnesComplementSweep(),
                "pulse 1 uses one's-complement sweep negate");
        assertFalse(new PulseChannel(false).isOnesComplementSweep(),
                "pulse 2 uses two's-complement sweep negate");
        assertNotNull(new PulseChannel(true).lengthCounter());
    }

    @Test
    void noise_lfsrStartsAtOne_andResetsToOne() {
        NoiseChannel n = new NoiseChannel();
        assertEquals(1, n.lfsr(), "power-up LFSR is effectively 1 (§1.9)");
        n.lfsr = 0x4ACE; // Phase B will clock it; simulate drift
        n.resetLfsr();
        assertEquals(1, n.lfsr(), "reset pins the LFSR back to 1");
        assertNotNull(n.lengthCounter());
    }

    @Test
    void triangle_phaseStartsAtZero_andResetsToZero() {
        TriangleChannel t = new TriangleChannel();
        assertEquals(0, t.sequencePhase(), "power-up sequence phase is 0");
        t.sequencePhase = 17;
        t.resetPhase();
        assertEquals(0, t.sequencePhase(), "reset pins the phase to 0 (§1.9)");
        assertNotNull(t.lengthCounter());
    }
}
