package net.lomibao.nes.harness;

import net.lomibao.nes.components.Button;
import net.lomibao.nes.components.PPU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A3 migration proof — the {@code TempPcTrace.bootToGameplay()}
 * throwaway workflow as a few lines of supported harness API.
 *
 * <p>Drives Micro Mages from power-on through its menus into gameplay:
 * START at frame ~1650 opens the menu, A at ~1800 joins a player, START at
 * ~1900 starts the game; gameplay is stable by frame ~3300 (timings proven
 * in the 2026-07 sprite-pipeline diagnostic sessions). It then asserts the
 * player sprite is present in OAM during gameplay — which doubles as a
 * regression test for the BRK fix (before it, the game crashed to a wedged
 * state with an empty OAM instead of reaching gameplay).
 *
 * <p>Skips (via {@code TestAbortedException}, D8/D12) when the commercial
 * ROM is not present locally; CI is unaffected.
 */
class MicroMagesBootIT {

    /** OAM Y >= $EF means the sprite row is offscreen — the "not live" convention. */
    private static int liveOamSprites(PPU ppu) {
        int live = 0;
        for (int i = 0; i < 64; i++) {
            if ((ppu.readOam(i * 4) & 0xFF) < 0xEF) {
                live++;
            }
        }
        return live;
    }

    @Test
    void bootToGameplay_playerSpritePresentInOam() {
        NesHarness h = NesHarness.fromRealRom(TestRoms.MICRO_MAGES);
        h.play(InputTimeline.builder()
                .press(Button.START).atFrame(1650).holdFrames(20) // title → menu
                .press(Button.A).atFrame(1800).holdFrames(20)     // join player 1
                .press(Button.START).atFrame(1900).holdFrames(20) // menu → game
                .build());
        h.runToFrame(3300); // gameplay stable well before here

        // The player sprite must be in OAM during gameplay — and stay there.
        assertTrue(liveOamSprites(h.ppu()) > 0,
                "expected live sprites in OAM during gameplay at frame " + h.frame());
        h.runFrames(60);
        assertTrue(liveOamSprites(h.ppu()) > 0,
                "expected live sprites to persist through gameplay at frame " + h.frame());
    }
}
