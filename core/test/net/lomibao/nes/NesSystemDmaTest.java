package net.lomibao.nes;

import net.lomibao.nes.components.CPU6502;
import net.lomibao.nes.components.DmaController;
import net.lomibao.nes.components.PPU;
import net.lomibao.nes.components.Ram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OAM DMA driven through the {@link NesSystem} facade
 * (Step 4 of the playable-gen1 plan). Asserts that wiring DMA into NesSystem
 * via the builder routes $4014 writes correctly, ticks the DMA state
 * machine on every {@code tick()}, and lands the bytes in the PPU's OAM.
 */
class NesSystemDmaTest {

    @Test
    void nesSystem_routesDmaWriteToOAM() {
        DmaController dma = new DmaController();
        NesSystem sys = NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())  // 2KB internal RAM at $0000-$1FFF
                .ppu(new PPU())
                .dma(dma)
                .build();

        // Populate page $07 in RAM with a pattern. $0700 is in mirrored RAM
        // ($0000-$1FFF mirrors $0000-$07FF four times), so $0700 → ram[$0700 & $07FF]
        // = ram[$0700 - $0000] but actually the Ram class implements its own
        // mirroring; we just need the source bytes to be present at $0700.
        for (int i = 0; i < 256; i++) {
            sys.getCpuBus().write(0x0700 + i, (byte) (i ^ 0xA5));
        }

        // Trigger DMA via a CPU bus write to $4014.
        sys.getCpuBus().write(0x4014, (byte) 0x07);
        assertTrue(dma.isActive(), "DMA should be active after $4014 write");

        // Run system ticks until DMA completes.
        int safety = 0;
        while (dma.isActive() && safety++ < 2000) {
            sys.tick();
        }
        assertFalse(dma.isActive(), "DMA should complete within safety window");

        // Verify all 256 bytes landed in OAM.
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) (i ^ 0xA5), sys.getPpu().readOam(i),
                    "OAM[" + i + "] should match source pattern");
        }
    }

    @Test
    void nesSystem_withoutDma_4014WriteIsHarmless() {
        // Sanity: NesSystem without a DmaController should not blow up
        // when a write to $4014 happens (it'll log "no device found" and
        // proceed). Existing tests that don't supply DMA must not regress.
        NesSystem sys = NesSystem.builder()
                .cpu(new CPU6502())
                .ram(new Ram())
                .ppu(new PPU())
                .build();
        assertDoesNotThrow(() -> sys.getCpuBus().write(0x4014, (byte) 0x07));
        assertDoesNotThrow(sys::tick);
    }
}
