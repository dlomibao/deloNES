package net.lomibao.nes.harness;

/**
 * Final outcome of a blargg $6000-protocol ROM run
 * (docs/apu-plan.md, Phase A0 — fixtures tier, no JUnit types).
 */
public final class BlarggResult {

    private final int code;
    private final String message;

    BlarggResult(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /** Final result code read from $6000 — 0 = pass, 1-$7F = failure code. */
    public int code() {
        return code;
    }

    /**
     * The ROM's own diagnostic text — zero-terminated ASCII read from
     * $6004, leading/trailing whitespace trimmed.
     */
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "BlarggResult{code=" + code + ", message='" + message + "'}";
    }
}
