package net.lomibao.nes.desktop.input;

import com.badlogic.gdx.Gdx;

/**
 * Production {@link KeyState} that delegates to the real LibGDX input backend.
 *
 * <p>Instantiate this only inside a running LibGDX application (after
 * {@code ApplicationListener.create()} has been called); the {@code Gdx.input}
 * singleton is not available before that point.
 */
public class GdxKeyState implements KeyState {

    @Override
    public boolean isPressed(int gdxKeyCode) {
        return Gdx.input.isKeyPressed(gdxKeyCode);
    }
}
