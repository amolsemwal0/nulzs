package org.zeronura.engine.scene;

import org.joml.Vector2f;
import org.zeronura.engine.core.Assets;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.core.EngineConfig;
import org.zeronura.engine.core.Scheduler;
import org.zeronura.engine.core.Time;
import org.zeronura.engine.debug.DebugMode;
import org.zeronura.engine.graphics2d.Camera2D;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics2d.Renderable2D;
import org.zeronura.engine.graphics2d.SpriteBatch;
import org.zeronura.engine.graphics2d.SpriteRenderer;
import org.zeronura.engine.graphics3d.World3D;
import org.zeronura.engine.gui.Gui;
import org.zeronura.engine.gui.Tweens;
import org.zeronura.engine.input.Input;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.physics2d.Physics2DWorld;
import org.zeronura.engine.render.ScreenEffects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * A level, a menu, a battle screen: a set of entities plus the systems that run them.
 *
 * <p>A new scene is <b>blank</b>. Features switch on the first time they are used, so the same class
 * serves every kind of game:
 * <ul>
 *   <li><b>2D</b> — add {@link SpriteRenderer}s or tilemaps, or draw in {@link #onDraw}. The 2D camera is
 *       {@link #camera2D()}; by default one world unit is one pixel with the origin at bottom-left.</li>
 *   <li><b>2D physics</b> — add {@code Body2D} components; {@link #physics2D()} steps them.</li>
 *   <li><b>3D</b> — call {@link #world3D()} for a lit, shadowed world with a camera, then add
 *       {@code MeshRenderer}s, {@code ModelRenderer}s, terrain and controllers.</li>
 *   <li><b>UI</b> — draw buttons, labels and panels in {@link #onGui}.</li>
 * </ul>
 *
 * <p>Drawn in that order each frame: 3D world, then 2D world, then GUI — so a 3D game gets a HUD and a 2D
 * game can have a 3D backdrop without either knowing about the other.
 *
 * <p>Subclass it and override the {@code on*} hooks. A scene instance is used once: after it exits, make
 * a new one to show it again.
 */
public class Scene {

    private Engine engine;
    private final List<Entity> entities = new ArrayList<>();
    private final List<Entity> iteration = new ArrayList<>();
    private final List<Entity> removed = new ArrayList<>();
    private final List<Renderable2D> renderables = new ArrayList<>();
    private final Tweens tweens = new Tweens();
    private final Scheduler scheduler = new Scheduler();

    private boolean started;
    private boolean overlay;
    private boolean hidesGuiBelow;
    private Color backgroundColor = new Color(0.10f, 0.12f, 0.16f, 1f);
    private Camera2D camera2D;
    private Physics2DWorld physics2D;
    private World3D world3D;
    private Boolean drawsCustom2D;

    private Entity pointerEntity;
    private float pointerLastX;
    private float pointerLastY;
    private float pointerTravel;
    private final Vector2f pointerWorld = new Vector2f();

    // --- Hooks for subclasses ---------------------------------------------------------------------------

    /** Called once when the scene first becomes active. Build the level here. */
    protected void onStart() {
    }

    /** Called every frame after components update. Read input here. */
    protected void onUpdate(float dt) {
    }

    /** Called at the fixed simulation rate, after components and 2D physics step. */
    protected void onFixedUpdate(float dt) {
    }

    /** Immediate-mode 2D drawing in world coordinates, on top of sprites and tilemaps. */
    protected void onDraw(SpriteBatch batch) {
    }

    /**
     * Extra 3D drawing after the 3D world and before 2D: rain, snow, particles, anything transparent that terrain
     * should hide. The world's depth is still in the buffer. Called only for scenes that use {@link #world3D()}.
     */
    protected void onRender3D() {
    }

    /**
     * Draws a whole frame by the scene's own means, once the screen is cleared and before any 2D or GUI layer goes over
     * it: for a game with a renderer of its own rather than a {@link #world3D()}.
     */
    protected void onRenderFrame() {
    }

    /** Screen-space UI in pixels, origin top-left, drawn over everything. */
    protected void onGui(Gui gui) {
    }

    protected void onResize(int width, int height) {
    }

    /** Called when the scene is removed from the stack. Release anything you created yourself. */
    protected void onExit() {
    }

    // --- Entities ---------------------------------------------------------------------------------------

    public Entity createEntity(String name) {
        return add(new Entity(name));
    }

    public Entity createEntity(String name, float x, float y) {
        Entity entity = new Entity(name);
        entity.transform().setPosition(x, y);
        return add(entity);
    }

    /** Adds an entity and its children. Entities added during an update start updating next frame. */
    public Entity add(Entity entity) {
        if (entity.scene == this) {
            return entity;
        }
        if (entity.scene != null) {
            throw new IllegalStateException(entity + " is already in another scene");
        }
        if (entity.isDestroyed()) {
            throw new IllegalStateException(entity + " has been destroyed");
        }
        entities.add(entity);
        entity.enterScene(this);
        for (Entity child : List.copyOf(entity.children())) {
            add(child);
        }
        return entity;
    }

    public List<Entity> entities() {
        return Collections.unmodifiableList(entities);
    }

    /** The entities with no parent, in their order: the top of the scene's hierarchy. */
    public List<Entity> roots() {
        List<Entity> roots = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity.parent() == null && !entity.isDestroyed()) {
                roots.add(entity);
            }
        }
        return roots;
    }

    /** Moves a root entity to {@code index} among the roots, for an editor's hierarchy. */
    void moveRoot(Entity entity, int index) {
        if (!entities.remove(entity)) {
            return;
        }
        List<Entity> roots = roots();
        int at = index < 0 || index >= roots.size() ? entities.size() : entities.indexOf(roots.get(index));
        entities.add(at, entity);
    }

    /** The first entity with this name, or null. */
    public Entity find(String name) {
        for (Entity entity : entities) {
            if (!entity.isDestroyed() && entity.name().equals(name)) {
                return entity;
            }
        }
        return null;
    }

    public List<Entity> findWithTag(String tag) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : entities) {
            if (!entity.isDestroyed() && entity.hasTag(tag)) {
                found.add(entity);
            }
        }
        return found;
    }

    /** Every enabled component of a type on an active entity. */
    public <T> List<T> components(Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Entity entity : entities) {
            if (!entity.isActive() || entity.isDestroyed()) {
                continue;
            }
            List<Component> list = entity.components();
            for (int i = 0; i < list.size(); i++) {
                Component component = list.get(i);
                if (component.isEnabled() && type.isInstance(component)) {
                    found.add(type.cast(component));
                }
            }
        }
        return found;
    }

    /**
     * The topmost entity whose sprite contains a world point and which has a {@link PointerHandler}.
     * Higher layers win; within a layer, whatever was added later (and so drawn later) wins.
     */
    public Entity pick(float worldX, float worldY) {
        Entity best = null;
        int bestLayer = Integer.MIN_VALUE;
        for (Entity entity : entities) {
            if (!entity.isActive() || entity.isDestroyed() || !entity.has(PointerHandler.class)) {
                continue;
            }
            SpriteRenderer sprite = entity.get(SpriteRenderer.class);
            if (sprite == null || !sprite.isEnabled() || !sprite.contains(worldX, worldY)) {
                continue;
            }
            if (sprite.layer() >= bestLayer) {
                best = entity;
                bestLayer = sprite.layer();
            }
        }
        return best;
    }

    // --- Services ---------------------------------------------------------------------------------------

    /** The engine running this scene; null before the scene is shown. */
    public Engine engine() {
        return engine;
    }

    public Input input() {
        return engine.input();
    }

    public Time time() {
        return engine.time();
    }

    public Assets assets() {
        return engine.assets();
    }

    /** Animations of any float value, updated with the scene. */
    public Tweens tweens() {
        return tweens;
    }

    /** Delayed and repeating actions, updated with the scene. */
    public Scheduler scheduler() {
        return scheduler;
    }

    /** The 2D camera. Created on first use, looking at the screen in pixel units. */
    public Camera2D camera2D() {
        if (camera2D == null) {
            int width = engine != null ? engine.width() : EngineConfig.DEFAULT_WIDTH;
            int height = engine != null ? engine.height() : EngineConfig.DEFAULT_HEIGHT;
            camera2D = new Camera2D(width, height);
            camera2D.position.set(width / 2f, height / 2f);
        }
        return camera2D;
    }

    /** The 2D physics world. Created on first use; stepped automatically at the fixed rate. */
    public Physics2DWorld physics2D() {
        if (physics2D == null) {
            physics2D = new Physics2DWorld();
        }
        return physics2D;
    }

    /** The 3D world: camera, lighting, terrain and 3D physics. Created on first use. */
    public World3D world3D() {
        if (world3D == null) {
            world3D = new World3D(this);
        }
        return world3D;
    }

    public boolean has3D() {
        return world3D != null;
    }

    public Color backgroundColor() {
        return backgroundColor;
    }

    /** The clear colour for 2D scenes. 3D scenes clear to their sky colour instead. */
    public void setBackgroundColor(Color color) {
        this.backgroundColor = color;
    }

    public boolean isOverlay() {
        return overlay;
    }

    /**
     * An overlay scene (a pause menu, a dialog) is drawn over the scene beneath it instead of clearing the
     * screen. Only the top scene updates.
     */
    public void setOverlay(boolean overlay) {
        this.overlay = overlay;
    }

    /** Whether the scenes under this one leave out their GUI while it is on top, so their buttons can't be pressed through it. */
    public boolean hidesGuiBelow() {
        return hidesGuiBelow;
    }

    public void setHidesGuiBelow(boolean hidesGuiBelow) {
        this.hidesGuiBelow = hidesGuiBelow;
    }

    /** What this scene is showing, in a line for a bug report: a place, an opponent. Null for nothing more than its name. */
    public String reportLine() {
        return null;
    }

    // --- Engine plumbing --------------------------------------------------------------------------------

    final void attach(Engine owner) {
        this.engine = owner;
        if (camera2D != null) {
            camera2D.setViewport(owner.width(), owner.height());
        }
        if (world3D != null) {
            world3D.resize(owner.width(), owner.height());
        }
    }

    final void start() {
        if (!started) {
            started = true;
            onStart();
        }
    }

    final void detach() {
        onExit();
        for (Entity entity : List.copyOf(entities)) {
            entity.exitScene();
        }
        entities.clear();
        tweens.cancelAll();
        scheduler.cancelAll();
        if (world3D != null) {
            world3D.dispose();
        }
        pointerEntity = null;
        engine = null;
    }

    final void resize(int width, int height) {
        if (camera2D != null) {
            camera2D.setViewport(width, height);
        }
        if (world3D != null) {
            world3D.resize(width, height);
        }
        onResize(width, height);
    }

    final void fixedUpdate(float dt) {
        if (world3D != null) {
            world3D.beginFixedStep();
        }
        snapshot();
        for (Entity entity : iteration) {
            if (!entity.isActive() || entity.isDestroyed()) {
                continue;
            }
            List<Component> list = entity.components();
            for (int i = 0; i < list.size(); i++) {
                Component component = list.get(i);
                if (component.isEnabled()) {
                    component.ensureStarted();
                    component.fixedUpdate(dt);
                }
            }
        }
        if (physics2D != null) {
            physics2D.step(dt);
        }
        onFixedUpdate(dt);
        removeDestroyed();
    }

    final void update(float dt) {
        tweens.update(dt);
        scheduler.update(dt);
        dispatchPointer();
        snapshot();
        for (Entity entity : iteration) {
            if (!entity.isActive() || entity.isDestroyed()) {
                continue;
            }
            List<Component> list = entity.components();
            for (int i = 0; i < list.size(); i++) {
                Component component = list.get(i);
                if (component.isEnabled()) {
                    component.ensureStarted();
                    component.update(dt);
                }
            }
        }
        onUpdate(dt);
        removeDestroyed();
    }

    final void render(float alpha, boolean clearScreen, boolean drawGui) {
        DebugMode.begin(this);
        try {
            drawFrame(alpha, clearScreen, drawGui);
        } finally {
            DebugMode.end();
        }
    }

    private void drawFrame(float alpha, boolean clearScreen, boolean drawGui) {
        // The player's picture settings work on a whole 3D frame, so a scene drawn over another keeps to the window.
        ScreenEffects effects = clearScreen && world3D != null && world3D.isVisible()
                && ScreenEffects.wanted(engine.settings()) ? engine.screenEffects() : null;
        DebugMode.begin("Clear");
        if (effects != null) {
            effects.begin(engine.width(), engine.height(), engine.settings(), world3D.skyColor());
        } else if (clearScreen) {
            Color clear = world3D != null ? world3D.skyColor() : backgroundColor;
            glClearColor(clear.r(), clear.g(), clear.b(), 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        } else {
            glClear(GL_DEPTH_BUFFER_BIT);
        }
        DebugMode.end();

        DebugMode.begin("Components before drawing");
        snapshot();
        for (Entity entity : iteration) {
            if (!entity.isActive() || entity.isDestroyed()) {
                continue;
            }
            List<Component> list = entity.components();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).isEnabled()) {
                    list.get(i).preRender(alpha);
                }
            }
        }
        DebugMode.end();

        if (world3D != null) {
            // Sprites keep out of the bloom of a picture that has one; drawn straight to the window, alpha is left be.
            engine.renderer3D().setSpritesUnbloomed(effects != null);
            DebugMode.begin("World");
            world3D.render(effects == null ? null : () -> {
                DebugMode.begin("Ambient occlusion");
                effects.occlude(world3D.camera());
                DebugMode.end();
            });
            DebugMode.end();
            DebugMode.begin("Scene's own 3D");
            onRender3D();
            DebugMode.end();
            engine.renderer3D().setSpritesUnbloomed(false);
            if (effects != null) {
                effects.sunRays(world3D.camera(), world3D.shadowsDrawn() ? engine.renderer3D().getShadowMap() : null,
                        world3D.lighting(), world3D.sunRayStrength(), world3D.sunRayGround(), world3D.sunRayFocus(),
                        world3D.sky() != null ? world3D.sky().sunMask() : null);
            }
        }
        if (effects != null) {
            DebugMode.begin("Screen effects");
            effects.finish();
            DebugMode.end();
        }
        DebugMode.begin("Scene's own drawing");
        onRenderFrame();
        DebugMode.end();
        DebugMode.begin("2D");
        render2D();
        DebugMode.end();

        if (drawGui) {
            DebugMode.begin("Interface");
            Gui gui = engine.gui();
            gui.begin(engine.width(), engine.height());
            onGui(gui);
            gui.end();
            DebugMode.end();
        }
    }

    private void render2D() {
        renderables.clear();
        for (Entity entity : entities) {
            if (!entity.isActive() || entity.isDestroyed()) {
                continue;
            }
            List<Component> list = entity.components();
            for (int i = 0; i < list.size(); i++) {
                Component component = list.get(i);
                if (component.isEnabled() && component instanceof Renderable2D renderable) {
                    renderables.add(renderable);
                }
            }
        }
        // A 3D-only scene should not build the sprite renderer just to draw nothing with it.
        if (renderables.isEmpty() && !drawsCustom2D()) {
            return;
        }
        // Stable sort: within a layer, draw order is the order entities were added.
        renderables.sort(Comparator.comparingInt(Renderable2D::layer));
        SpriteBatch batch = engine.spriteBatch();
        Camera2D camera = camera2D();
        batch.begin(camera);
        for (Renderable2D renderable : renderables) {
            renderable.draw(batch, camera);
        }
        onDraw(batch);
        batch.end();
    }

    private boolean drawsCustom2D() {
        if (drawsCustom2D == null) {
            drawsCustom2D = false;
            for (Class<?> type = getClass(); type != Scene.class; type = type.getSuperclass()) {
                try {
                    type.getDeclaredMethod("onDraw", SpriteBatch.class);
                    drawsCustom2D = true;
                    break;
                } catch (NoSuchMethodException ignored) {
                    // not overridden at this level
                }
            }
        }
        return drawsCustom2D;
    }

    private void dispatchPointer() {
        if (engine == null) {
            return;
        }
        Input input = engine.input();
        if (pointerEntity == null) {
            if (!input.isMousePressed(MouseButton.LEFT) || engine.guiWantsPointer()) {
                return;
            }
            Vector2f world = camera2D().screenToWorld(input.mouseX(), input.mouseY(), pointerWorld);
            Entity hit = pick(world.x, world.y);
            if (hit == null) {
                return;
            }
            pointerEntity = hit;
            pointerLastX = world.x;
            pointerLastY = world.y;
            pointerTravel = 0f;
            for (PointerHandler handler : hit.getAll(PointerHandler.class)) {
                handler.onPointerDown(world.x, world.y);
            }
            return;
        }

        if (pointerEntity.isDestroyed() || pointerEntity.scene != this) {
            pointerEntity = null;
            return;
        }

        Entity target = pointerEntity;
        Vector2f world = camera2D().screenToWorld(input.mouseX(), input.mouseY(), pointerWorld);
        float dx = world.x - pointerLastX;
        float dy = world.y - pointerLastY;
        if (dx != 0f || dy != 0f) {
            pointerTravel += Math.abs(input.mouseDeltaX()) + Math.abs(input.mouseDeltaY());
            for (PointerHandler handler : target.getAll(PointerHandler.class)) {
                handler.onPointerDrag(world.x, world.y, dx, dy);
            }
            pointerLastX = world.x;
            pointerLastY = world.y;
        }

        if (!input.isMouseDown(MouseButton.LEFT)) {
            pointerEntity = null;
            // A press that wandered more than a few pixels was a drag, not a click.
            boolean click = pointerTravel < 8f && pick(world.x, world.y) == target;
            for (PointerHandler handler : target.getAll(PointerHandler.class)) {
                handler.onPointerUp(world.x, world.y);
                if (click) {
                    handler.onClick();
                }
            }
        }
    }

    /** Takes out entities destroyed since the last update now, rather than at the end of the next one. */
    void flushDestroyed() {
        removeDestroyed();
    }

    private void snapshot() {
        iteration.clear();
        iteration.addAll(entities);
    }

    private void removeDestroyed() {
        removed.clear();
        for (Entity entity : entities) {
            if (entity.isDestroyed()) {
                removed.add(entity);
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        entities.removeIf(Entity::isDestroyed);
        for (Entity entity : removed) {
            entity.exitScene();
            entity.detachFromParent();
        }
    }
}
