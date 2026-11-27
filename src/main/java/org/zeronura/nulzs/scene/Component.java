package org.zeronura.nulzs.scene;

import org.zeronura.nulzs.core.Engine;
import org.zeronura.nulzs.input.Input;

/**
 * A piece of behaviour or data attached to an {@link Entity}: a sprite, a physics body, a player
 * controller, a script.
 *
 * <p>Games are built by composing components rather than by subclassing entities, so "a crate that
 * burns" is a crate with a {@code Flammable} component rather than a {@code BurningCrate} class. That
 * is also the shape a visual editor needs later: an inspector lists an entity's components and their
 * public fields.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #onAttach} — added to an entity, which may not be in a scene yet</li>
 *   <li>{@link #onEnterScene} — the entity is now in a scene; register with scene systems here</li>
 *   <li>{@link #start} — once, just before the first update</li>
 *   <li>{@link #fixedUpdate} and {@link #update} — every step and every frame while enabled</li>
 *   <li>{@link #preRender} — every frame before drawing, with the interpolation factor</li>
 *   <li>{@link #onExitScene} — the entity was destroyed or its scene exited</li>
 * </ol>
 */
public abstract class Component {

    Entity entity;
    private boolean enabled = true;
    private boolean started;

    public final Entity entity() {
        return entity;
    }

    public final Transform transform() {
        return entity.transform();
    }

    /** The scene the owning entity is in, or null. */
    public final Scene scene() {
        return entity == null ? null : entity.scene;
    }

    /** The running engine, or null when not in a scene attached to one (e.g. in a unit test). */
    protected final Engine engine() {
        Scene scene = scene();
        return scene == null ? null : scene.engine();
    }

    protected final Input input() {
        Engine engine = engine();
        return engine == null ? null : engine.input();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    protected void onAttach() {
    }

    protected void onEnterScene(Scene scene) {
    }

    protected void onExitScene(Scene scene) {
    }

    protected void start() {
    }

    protected void fixedUpdate(float dt) {
    }

    protected void update(float dt) {
    }

    /**
     * Called every frame before drawing. {@code alpha} is how far between the last two fixed steps this
     * frame falls; interpolate anything moved in {@code fixedUpdate} with it to avoid visible stepping on
     * displays faster than the fixed rate. Cameras that follow physics bodies position themselves here.
     */
    protected void preRender(float alpha) {
    }

    final void ensureStarted() {
        if (!started) {
            started = true;
            start();
        }
    }
}
