package org.zeronura.nulzs.scene;

import org.zeronura.nulzs.core.Engine;
import org.zeronura.nulzs.graphics2d.Camera2D;
import org.zeronura.nulzs.graphics2d.Renderable2D;
import org.zeronura.nulzs.graphics2d.SpriteBatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs a scene that is not on the engine's scene stack: what an editor does with the scene it is editing, which it
 * shows in a viewport of its own, steps only while its Play button is down, and throws away and rebuilds on undo.
 *
 * <p>A scene on the stack is driven by {@link SceneManager}; one held here is driven by whoever holds it, through the
 * same steps the stack takes: {@link #attach}, then {@link #update} and {@link #fixedUpdate} while it runs,
 * {@link #preRender} and its own drawing each frame, and {@link #detach} at the end. Nothing here draws the scene's
 * GUI or clears the screen; the host decides where on the window the scene goes.
 */
public final class SceneHost {

    private static final List<Renderable2D> RENDERABLES = new ArrayList<>();

    private SceneHost() {
    }

    /** Gives the scene its engine, as the stack does when a scene is put on it. Call once, before anything else. */
    public static void attach(Scene scene, Engine engine) {
        scene.attach(engine);
    }

    /** Runs the scene's {@code onStart} if it has not run yet. */
    public static void start(Scene scene) {
        scene.start();
    }

    /** One frame of the scene's logic: tweens, components' {@code update}, then its own {@code onUpdate}. */
    public static void update(Scene scene, float dt) {
        scene.update(dt);
    }

    /** One fixed step: components' {@code fixedUpdate}, 2D physics, then the scene's {@code onFixedUpdate}. */
    public static void fixedUpdate(Scene scene, float dt) {
        scene.fixedUpdate(dt);
    }

    /** Takes destroyed entities out now. */
    public static void flushDestroyed(Scene scene) {
        scene.flushDestroyed();
    }

    /** Every enabled component's {@code preRender} on active entities, as the stack runs it before drawing. */
    public static void preRender(Scene scene, float alpha) {
        for (Entity entity : List.copyOf(scene.entities())) {
            if (!entity.isActive() || entity.isDestroyed()) {
                continue;
            }
            List<Component> components = entity.components();
            for (int i = 0; i < components.size(); i++) {
                Component component = components.get(i);
                if (component.isEnabled()) {
                    component.preRender(alpha);
                }
            }
        }
    }

    /**
     * Draws the scene's 2D layer — its sprites, tilemaps and {@code onDraw} — through {@code camera} into whatever
     * viewport is set, without clearing it.
     */
    public static void render2D(Scene scene, Camera2D camera) {
        RENDERABLES.clear();
        for (Entity entity : scene.entities()) {
            if (!entity.isActive() || entity.isDestroyed()) {
                continue;
            }
            for (Component component : entity.components()) {
                if (component.isEnabled() && component instanceof Renderable2D renderable) {
                    RENDERABLES.add(renderable);
                }
            }
        }
        RENDERABLES.sort(Comparator.comparingInt(Renderable2D::layer));
        SpriteBatch batch = scene.engine().spriteBatch();
        batch.begin(camera);
        for (Renderable2D renderable : RENDERABLES) {
            renderable.draw(batch, camera);
        }
        scene.onDraw(batch);
        batch.end();
        RENDERABLES.clear();
    }

    /** The scene's {@code onRender3D}: extra 3D drawing after its world. */
    public static void render3DExtras(Scene scene) {
        scene.onRender3D();
    }

    /** Ends the scene as the stack does when it is taken off: {@code onExit}, every entity leaves, its world is freed. */
    public static void detach(Scene scene) {
        if (scene.engine() != null) {
            scene.detach();
        }
    }
}
