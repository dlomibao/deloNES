package net.lomibao.nes;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import net.lomibao.nes.desktop.NesGame;

// Please note that on macOS your application needs to be started with the -XstartOnFirstThread JVM argument
public class DesktopLauncher {
	public static void main (String[] arg) {
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		config.setForegroundFPS(60);
		// APU Phase E2 (D17): device buffer capacity for AudioDevice
		// streaming = bufferSize x bufferCount = 4096 samples ~ 93 ms at
		// 44.1 kHz — the plan's 1024x4 strawman (LibGDX defaults 512x9
		// were flagged load-bearing in libgdx #4859). TUNABLE: the POC-D
		// listening-test tables in docs/apu-poc-findings.md are still
		// unfilled; revisit these numbers when the user records them.
		config.setAudioConfig(16, 1024, 4);
		config.setTitle("deloNES");
		config.setWindowedMode(640, 600);
		new Lwjgl3Application(new NesGame(), config);
	}
}
