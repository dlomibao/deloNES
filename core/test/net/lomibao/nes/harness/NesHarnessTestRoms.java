package net.lomibao.nes.harness;

/**
 * Shared synthetic-ROM builders for harness tests (test-side only).
 */
final class NesHarnessTestRoms {

    private NesHarnessTestRoms() {
    }

    /**
     * Synthetic NROM ROM whose program forever polls the controller:
     * strobe $4016 high→low, shift out all 8 buttons, store each bit at
     * $0300+buttonIndex, repeat. Runs many times per frame, so by the time
     * a frame completes, $0300-$0307 mirror the buttons held during it.
     *
     * <pre>
     * 8000: A9 01     LDA #$01
     * 8002: 8D 16 40  STA $4016     ; strobe high
     * 8005: A9 00     LDA #$00
     * 8007: 8D 16 40  STA $4016     ; strobe low — latch
     * 800A: A2 00     LDX #$00
     * 800C: AD 16 40  LDA $4016     ; loop: next button bit
     * 800F: 29 01     AND #$01
     * 8011: 9D 00 03  STA $0300,X
     * 8014: E8        INX
     * 8015: E0 08     CPX #$08
     * 8017: D0 F3     BNE $800C
     * 8019: 4C 00 80  JMP $8000
     * </pre>
     */
    static byte[] controllerPollRom() {
        int[] prog = {
                0xA9, 0x01,
                0x8D, 0x16, 0x40,
                0xA9, 0x00,
                0x8D, 0x16, 0x40,
                0xA2, 0x00,
                0xAD, 0x16, 0x40,
                0x29, 0x01,
                0x9D, 0x00, 0x03,
                0xE8,
                0xE0, 0x08,
                0xD0, 0xF3,
                0x4C, 0x00, 0x80,
        };
        return nromWithProgram(prog);
    }

    /** Wrap a program (loaded at $8000, reset vector → $8000) in a 16KB NROM image. */
    static byte[] nromWithProgram(int[] prog) {
        byte[] prg = new byte[16 * 1024];
        for (int i = 0; i < prog.length; i++) {
            prg[i] = (byte) prog[i];
        }
        // Reset vector → $8000 (16KB NROM mirrors $8000-$BFFF to $C000-$FFFF).
        prg[0x3FFC] = 0x00;
        prg[0x3FFD] = (byte) 0x80;

        byte[] rom = new byte[16 + prg.length + 8 * 1024]; // header + PRG + 8KB CHR
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1; // 1 × 16KB PRG
        rom[5] = 1; // 1 × 8KB CHR
        System.arraycopy(prg, 0, rom, 16, prg.length);
        return rom;
    }

    static NesHarness controllerPollHarness() {
        return NesHarness.fromBytes(controllerPollRom(), "controller-poll.nes");
    }
}
