package net.lomibao.nes.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop entry point for running an arbitrary NES ROM.
 * Step 8 of the playable-gen1 plan — the shipping demo path.
 *
 * <p>Invoke via the {@code desktop:runRom} Gradle task:
 * <pre>
 *   ./gradlew desktop:runRom -Prom=path/to/file.nes
 * </pre>
 *
 * <p>If no path argument is supplied, falls back to {@code nestest.nes}
 * from {@code core/src/main/resources/} so the launcher is always
 * smoke-runnable in a fresh checkout.
 */
public class RomLauncher {

    public static void main(String[] args) {
        String romPath = (args.length > 0) ? args[0] : "../core/src/main/resources/nestest.nes";

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("deloNES — " + romPath);
        // Default window: 2x scale of NES native (256x240 → 512x480).
        config.setWindowedMode(512, 480);
        config.setResizable(true);
        // Match LibGDX vsync to the NES rate target. NesSystem.advance()
        // does the actual pacing inside render(); this just keeps the
        // delta-time history sensible.
        config.setForegroundFPS(60);
        config.useVsync(true);

        new Lwjgl3Application(new RomRenderer(romPath), config);
    }
}
