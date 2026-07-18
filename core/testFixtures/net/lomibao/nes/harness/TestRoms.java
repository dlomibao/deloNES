package net.lomibao.nes.harness;

import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ROM/resource loading helpers for the headless harness fixtures tier
 * (docs/headless-harness-plan.md, Phase A0).
 *
 * <p>Two sources are supported:
 * <ul>
 *   <li><b>Classpath resources</b> — free/homebrew content committed to the
 *       repo (nestest.nes, opcodes.csv), loaded via
 *       {@link #resourceBytes(String)}.</li>
 *   <li><b>Real (commercial) ROMs</b> — never committed; looked up under
 *       {@code ~/projects/deloNES/core/src/main/resources/roms/} following
 *       the {@code CartridgeNes2Test} convention. When the file is absent,
 *       {@link #realRomBytesOrSkip(String)} throws
 *       {@link TestAbortedException} — the framework-neutral opentest4j
 *       exception JUnit 5 natively treats as a skip (decision D8/D12), so
 *       call sites need no {@code assumeTrue} boilerplate.</li>
 * </ul>
 */
public final class TestRoms {

    /** Canonical file name of the Micro Mages dump used by real-ROM ITs. */
    public static final String MICRO_MAGES = "Micro Mages (World) (Aftermarket) (Unl).nes";

    /** Classpath resource path of the opcode metadata CSV CPU6502 needs. */
    public static final String OPCODE_CSV_RESOURCE = "/opcodes/opcodes.csv";

    /** Real-ROM directory, relative to {@code user.home} (CartridgeNes2Test convention). */
    private static final String REAL_ROM_DIR = "projects/deloNES/core/src/main/resources/roms";

    private TestRoms() {
        // static helpers only
    }

    /**
     * Read a classpath resource fully into a byte[].
     *
     * @param resourcePath absolute resource path, e.g. {@code "/nestest.nes"}
     * @throws IllegalArgumentException if the resource does not exist
     */
    public static byte[] resourceBytes(String resourcePath) {
        try (InputStream in = TestRoms.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException(
                        "Classpath resource not found: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading resource " + resourcePath, e);
        }
    }

    /**
     * Open a fresh stream over the {@code opcodes.csv} resource — the stream
     * {@code CPU6502}/{@code RomLoader} consume (and close) during construction.
     */
    public static InputStream opcodeCsv() {
        InputStream in = TestRoms.class.getResourceAsStream(OPCODE_CSV_RESOURCE);
        if (in == null) {
            throw new IllegalStateException(
                    "opcodes.csv classpath resource missing: " + OPCODE_CSV_RESOURCE);
        }
        return in;
    }

    /**
     * sha-256 of a ROM image as 64 lowercase hex chars — the movie header's
     * {@code rom-sha256} pin (Phase D1/D3: the fixtures tier computes the
     * digest; the core {@code Movie} model just carries the string).
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM lacks SHA-256", e);
        }
    }

    /**
     * Path a real (uncommitted) ROM is expected at:
     * {@code ~/projects/deloNES/core/src/main/resources/roms/<fileName>}.
     */
    public static Path realRomPath(String fileName) {
        return Paths.get(System.getProperty("user.home"), REAL_ROM_DIR, fileName);
    }

    /**
     * Read a real ROM's bytes, or skip the calling test when the file is
     * absent (throws {@link TestAbortedException}, which JUnit 5 reports as
     * a skip). Commercial ROMs are never committed — D12.
     */
    public static byte[] realRomBytesOrSkip(String fileName) {
        Path rom = realRomPath(fileName);
        if (!Files.exists(rom)) {
            throw new TestAbortedException(
                    "Real ROM not present locally — skipping: " + rom);
        }
        try {
            return Files.readAllBytes(rom);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading real ROM " + rom, e);
        }
    }
}
