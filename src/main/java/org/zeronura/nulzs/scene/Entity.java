package org.zeronura.nulzs.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A named thing in a scene: a transform plus a list of {@link Component}s, optionally parented to another
 * entity.
 *
 * <p>An entity does nothing by itself. What it looks like, how it moves and what it collides with all
 * come from its components, so the same class is a player, a tile layer, a camera rig or a menu card.
 */
public final class Entity {

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final long id = NEXT_ID.getAndIncrement();
    private final Transform transform = new Transform();
    private final List<Component> components = new ArrayList<>();
    private final List<Entity> children = new ArrayList<>();
    private final Set<String> tags = new HashSet<>();

    private String name;
    private boolean active = true;
    private boolean destroyed;
    private Entity parent;
    Scene scene;

    public Entity(String name) {
        this.name = name;
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Transform transform() {
        return transform;
    }

    public Scene scene() {
        return scene;
    }

    // --- Components -------------------------------------------------------------------------------------

    /** Attaches a component and returns it, so creation reads {@code var body = e.add(new Body2D())}. */
    public <T extends Component> T add(T component) {
        if (component.entity != null) {
            throw new IllegalStateException(component.getClass().getSimpleName()
                    + " is already attached to entity '" + component.entity.name + "'");
        }
        component.entity = this;
        components.add(component);
        component.onAttach();
        if (scene != null) {
            component.onEnterScene(scene);
        }
        return component;
    }

    /** The first component of the given type (or implementing the given interface), or null. */
    public <T> T get(Class<T> type) {
        for (Component component : components) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
        }
        return null;
    }

    /** Like {@link #get} but fails loudly, for components an entity cannot work without. */
    public <T> T require(Class<T> type) {
        T found = get(type);
        if (found == null) {
            throw new IllegalStateException("Entity '" + name + "' needs a " + type.getSimpleName());
        }
        return found;
    }

    public <T> List<T> getAll(Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component component : components) {
            if (type.isInstance(component)) {
                found.add(type.cast(component));
            }
        }
        return found;
    }

    public boolean has(Class<?> type) {
        return get(type) != null;
    }

    public void remove(Component component) {
        if (components.remove(component)) {
            if (scene != null) {
                component.onExitScene(scene);
            }
            component.entity = null;
        }
    }

    public List<Component> components() {
        return Collections.unmodifiableList(components);
    }

    // --- Hierarchy --------------------------------------------------------------------------------------

    /**
     * Parents {@code child} to this entity, so it moves with it. The child joins this entity's scene if
     * it is not in one already.
     */
    public Entity addChild(Entity child) {
        if (child == this) {
            throw new IllegalArgumentException("an entity cannot be its own child");
        }
        for (Entity ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == child) {
                throw new IllegalArgumentException("'" + child.name + "' is an ancestor of '" + name + "'");
            }
        }
        if (child.parent != null) {
            child.parent.children.remove(child);
        }
        child.parent = this;
        child.transform.parent = transform;
        children.add(child);
        if (scene != null && child.scene == null) {
            scene.add(child);
        }
        return child;
    }

    /** Detaches this entity from its parent, keeping its local transform. */
    public void detachFromParent() {
        if (parent != null) {
            parent.children.remove(this);
            parent = null;
            transform.parent = null;
        }
    }

    public Entity parent() {
        return parent;
    }

    /**
     * Moves this entity to {@code index} among its siblings — its parent's children, or the scene's root entities — so
     * an editor's hierarchy can be put in order. An index past the end puts it last.
     */
    public void setSiblingIndex(int index) {
        if (parent != null) {
            parent.children.remove(this);
            parent.children.add(Math.clamp(index, 0, parent.children.size()), this);
        } else if (scene != null) {
            scene.moveRoot(this, index);
        }
    }

    /** Where this entity stands among its siblings, as {@link #setSiblingIndex} counts. */
    public int siblingIndex() {
        if (parent != null) {
            return parent.children.indexOf(this);
        }
        return scene == null ? 0 : scene.roots().indexOf(this);
    }

    public List<Entity> children() {
        return Collections.unmodifiableList(children);
    }

    // --- State ------------------------------------------------------------------------------------------

    /** Whether this entity and all its ancestors are active. Inactive entities neither update nor draw. */
    public boolean isActive() {
        return active && (parent == null || parent.isActive());
    }

    /** Whether this entity itself is switched on, whatever its parents are. */
    public boolean isActiveSelf() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** Removes this entity and its children from the scene at the end of the current frame. */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        for (Entity child : children) {
            child.destroy();
        }
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public Entity tag(String... newTags) {
        Collections.addAll(tags, newTags);
        return this;
    }

    public Entity untag(String... oldTags) {
        for (String tag : oldTags) {
            tags.remove(tag);
        }
        return this;
    }

    public void clearTags() {
        tags.clear();
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public Set<String> tags() {
        return Collections.unmodifiableSet(tags);
    }

    void enterScene(Scene newScene) {
        scene = newScene;
        // Copied: a component registering itself may add further components.
        for (Component component : List.copyOf(components)) {
            component.onEnterScene(newScene);
        }
    }

    void exitScene() {
        Scene old = scene;
        if (old == null) {
            return;
        }
        for (Component component : List.copyOf(components)) {
            component.onExitScene(old);
        }
        scene = null;
    }

    @Override
    public String toString() {
        return "Entity[" + id + " '" + name + "']";
    }
}
