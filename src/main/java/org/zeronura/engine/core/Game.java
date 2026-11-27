package org.zeronura.engine.core;

/**
 * The entry point of a game built on the engine — what a project's {@code gameClass} names.
 *
 * <p>Deliberately tiny: a game's only obligation is to put a first scene on the stack. Everything else —
 * 2D, 3D, UI, physics — is reached through the {@link Engine} and the scene, so a blank project and a
 * finished open-world game implement exactly the same interface.
 *
 * <pre>{@code
 * public final class MyGame implements Game {
 *     public void start(Engine engine) {
 *         engine.scenes().set(new MainScene());
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations need a public no-argument constructor so the launcher can create them by name.
 */
@FunctionalInterface
public interface Game {

    /** Called once the window and GL context exist. Set the first scene here. */
    void start(Engine engine);

    /**
     * Called once a frame, before the scenes update, for as long as the game runs: for what outlives any one scene,
     * such as the online service the game ships on.
     */
    default void update(Engine engine, float dt) {
    }

    /** Called when the game is closed or swapped for another, after its scenes have exited. */
    default void stop(Engine engine) {
    }
}
