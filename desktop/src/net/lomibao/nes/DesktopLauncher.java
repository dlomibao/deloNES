package net.lomibao.nes;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import net.lomibao.nes.desktop.NesGame;

// Please note that on macOS your application needs to be started with the -XstartOnFirstThread JVM argument
public class DesktopLauncher {
	public static void main (String[] arg) {
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		config.setForegroundFPS(60);
		config.setTitle("deloNES");
		config.setWindowedMode(640, 600);
		new Lwjgl3Application(new NesGame(), config);
	}

	// NesEmulator is still wired into the HTML/GWT backend
	// (html/src/net/lomibao/nes/client/HtmlLauncher.java references it as the
	// ApplicationListener). The desktop entry point uses NesGame instead, but
	// NesEmulator must remain on the classpath until the html module migrates
	// to NesGame. See docs/review-2026-05-12/reports/web-deployment.md.

}
