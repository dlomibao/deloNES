package net.lomibao.nes.desktop.input;

/**
 * Abstracts keyboard polling so tests can inject a stub instead of
 * depending on the real LibGDX {@code Gdx.input} singleton.
 *
 * <p>Production code wires {@link GdxKeyState}; tests supply a simple
 * {@code Map<Integer, Boolean>}-backed implementation.
 */
public interface KeyState {

    /**
     * Returns {@code true} if the key identified by {@code gdxKeyCode} is
     * currently held down.
     *
     * @param gdxKeyCode a {@link com.badlogic.gdx.Input.Keys} constant
     * @return {@code true} while the key is pressed
     */
    boolean isPressed(int gdxKeyCode);
}
