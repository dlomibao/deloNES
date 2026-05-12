package net.lomibao.nes.client;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.github.xpenatan.gdx.backends.teavm.TeaApplication;
import com.github.xpenatan.gdx.backends.teavm.TeaApplicationConfiguration;

/**
 * TeaVM/web entry point. Currently a placeholder — the real emulator is
 * driven by {@code NesGame} in the desktop module, which is not yet
 * portable to TeaVM (CPU6502 reflection dispatch, classpath resource
 * loading, and {@code FreeTypeFontGenerator} are all blockers).
 *
 * <p>See {@code docs/review-2026-05-12/reports/web-deployment.md} and
 * Tier C item C1 in the triage doc for the planned web-port work. Until
 * that lands, this launcher boots an empty application so the html
 * module continues to compile against TeaVM.
 */
public class HtmlLauncher {

    public static void main(String[] args) {
        TeaApplicationConfiguration config = new TeaApplicationConfiguration("canvas");
        config.width = 640;
        config.height = 480;
        config.useGL30 = true;

        new TeaApplication(new ApplicationAdapter() {
            @Override
            public void create() {
                Gdx.app.log("deloNES",
                        "Web build is a placeholder — see docs/review-2026-05-12/triage.md C1 "
                        + "(CPU6502 dispatch refactor) for the next step.");
            }
        }, config);
    }
}
