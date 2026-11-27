package org.zeronura.nulzs.studio;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.zeronura.nulzs.core.Engine;
import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.scene.SavesOwnFiles;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.scene.SceneFile;
import org.zeronura.nulzs.scene.SceneHost;
import org.zeronura.nulzs.scene.SceneSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scene open in the editor: a live {@link Scene} built from its file, the ids its entities are saved under, what is
 * selected, and its undo history.
 *
 * <p>Undo works on whole snapshots: after each finished change — a drag let go, a field typed and kept — the scene is
 * written out as JSON and pushed, and undoing builds the scene again from the one before. That keeps every kind of edit
 * undoable without each one knowing how to reverse itself, at the cost of rebuilding the scene, which for scenes made
 * in an editor is quick.
 */
final class SceneDocument {

    private static final int UNDO_LIMIT = 200;
    static final String CLIPBOARD_KEY = "gameengine-entities";

    private final Engine engine;
    private final ComponentCatalog catalog;

    Scene scene;
    Path file;
    SceneSettings settings = new SceneSettings();
    Map<Entity, String> ids = new HashMap<>();
    Map<String, Entity> byId = new HashMap<>();
    Map<String, Object> editorData = new LinkedHashMap<>();
    List<String> problems = List.of();
    final List<Entity> selection = new ArrayList<>();
    /** An object or a tree on the selected map's map, picked in the Scene view; see {@link #mapPick()}. */
    private MapPick mapPick;
    /** Goes up whenever the scene is rebuilt or changed, so panels can drop what they worked out from it. */
    int revision;

    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private final ArrayDeque<String> redo = new ArrayDeque<>();
    private String current;
    private String saved;
    private boolean pending;
    /** While playing, changes are the game's, not edits: nothing is recorded, and Stop puts the scene back. */
    boolean recording = true;

    SceneDocument(Engine engine, ComponentCatalog catalog, Path file, Map<String, Object> json) {
        this.engine = engine;
        this.catalog = catalog;
        this.file = file;
        build(json);
        current = snapshot();
        saved = file == null ? null : current;
    }

    // --- Building -----------------------------------------------------------------------------------------------

    /** Throws the live scene away and builds it again from JSON, keeping the selection where the ids still exist. */
    void build(Map<String, Object> json) {
        List<String> selected = new ArrayList<>();
        for (Entity entity : selection) {
            String id = ids.get(entity);
            if (id != null) {
                selected.add(id);
            }
        }
        dispose();
        scene = new Scene();
        SceneHost.attach(scene, engine);
        SceneHost.start(scene);
        SceneFile.Loaded loaded = SceneFile.instantiate(json, scene, catalog.loader());
        ids = new HashMap<>(loaded.ids());
        byId = new HashMap<>(loaded.byId());
        settings = loaded.settings();
        editorData = loaded.editor();
        problems = loaded.problems();
        selection.clear();
        for (String id : selected) {
            Entity entity = byId.get(id);
            if (entity != null) {
                selection.add(entity);
            }
        }
        revision++;
    }

    void dispose() {
        if (scene != null) {
            SceneHost.detach(scene);
            scene = null;
        }
    }

    /** The scene as JSON, as it would be saved. */
    Map<String, Object> toJson() {
        Map<String, Object> json = SceneFile.serialize(scene, ids, settings, editorData);
        syncIds();
        return json;
    }

    private void syncIds() {
        ids.keySet().removeIf(Entity::isDestroyed);
        byId.clear();
        ids.forEach((entity, id) -> byId.put(id, entity));
    }

    String snapshot() {
        return Json.compact(toJson());
    }

    // --- Changes and undo ---------------------------------------------------------------------------------------

    /** Something was edited. It becomes an undo step once the pointer and keyboard let go of it; see {@link #settle}. */
    void changed() {
        revision++;
        if (recording) {
            pending = true;
        }
    }

    /** Records a waiting change as one undo step. Call when no drag or typing is in progress. */
    void settle() {
        if (!pending || !recording) {
            return;
        }
        pending = false;
        String now = snapshot();
        if (!now.equals(current)) {
            undo.push(current);
            if (undo.size() > UNDO_LIMIT) {
                undo.removeLast();
            }
            redo.clear();
            current = now;
        }
    }

    boolean canUndo() {
        return !undo.isEmpty() || pending;
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    void undo() {
        settle();
        if (undo.isEmpty()) {
            return;
        }
        redo.push(current);
        current = undo.pop();
        build(Json.parseObject(current));
    }

    void redo() {
        settle();
        if (redo.isEmpty()) {
            return;
        }
        undo.push(current);
        current = redo.pop();
        build(Json.parseObject(current));
    }

    /** The scene as last recorded, for putting it back after playing. */
    String recorded() {
        settle();
        return current;
    }

    /** Puts the scene back as a recorded snapshot had it, without making that an undo step. */
    void restore(String snapshot) {
        pending = false;
        build(Json.parseObject(snapshot));
        current = snapshot;
    }

    boolean dirty() {
        return pending || !current.equals(saved);
    }

    String name() {
        if (file == null) {
            return "Untitled";
        }
        String name = file.getFileName().toString();
        return name.endsWith(SceneFile.EXTENSION) ? name.substring(0, name.length() - SceneFile.EXTENSION.length()) : name;
    }

    void save(Path to) throws IOException {
        settle();
        saveOwnFiles();
        SceneFile.write(to, toJson());
        file = to;
        current = snapshot();
        saved = current;
    }

    /** Saves what the scene's components keep in files of their own — a map's — before the scene that names them. */
    private void saveOwnFiles() throws IOException {
        for (Entity entity : scene.entities()) {
            if (entity.isDestroyed()) {
                continue;
            }
            for (Component component : entity.components()) {
                if (component instanceof SavesOwnFiles files) {
                    files.saveOwnFiles();
                }
            }
        }
    }

    // --- Ids ----------------------------------------------------------------------------------------------------

    String idOf(Entity entity) {
        String id = ids.get(entity);
        if (id == null) {
            id = SceneFile.newId(byId.keySet());
            ids.put(entity, id);
            byId.put(id, entity);
        }
        return id;
    }

    /** Every entity in the scene, parents before their children, in hierarchy order. */
    List<Entity> entitiesInOrder() {
        List<Entity> out = new ArrayList<>();
        for (Entity root : scene.roots()) {
            addTree(root, out);
        }
        return out;
    }

    private static void addTree(Entity entity, List<Entity> out) {
        if (entity.isDestroyed()) {
            return;
        }
        out.add(entity);
        for (Entity child : entity.children()) {
            addTree(child, out);
        }
    }

    // --- Selection ----------------------------------------------------------------------------------------------

    Entity primary() {
        selection.removeIf(Entity::isDestroyed);
        return selection.isEmpty() ? null : selection.getLast();
    }

    boolean isSelected(Entity entity) {
        return selection.contains(entity);
    }

    void select(Entity entity) {
        mapPick = null;
        selection.clear();
        if (entity != null) {
            selection.add(entity);
        }
    }

    void toggle(Entity entity) {
        mapPick = null;
        if (!selection.remove(entity)) {
            selection.add(entity);
        }
    }

    /**
     * Selects an object or a tree standing on the map an entity shows: the entity is what is selected, and the pick says
     * which thing on its map. Nothing is picked if it isn't on that map.
     */
    void pickOnMap(Entity entity, Object item) {
        select(entity);
        mapPick = MapPick.of(this, entity, item);
    }

    /**
     * The object or tree picked on a map, while it is: while its map's entity is the one thing selected and it is still on
     * the map. Selecting anything else lets it go.
     */
    MapPick mapPick() {
        if (mapPick == null) {
            return null;
        }
        Entity entity = mapPick.entity(this);
        org.zeronura.nulzs.graphics3d.WorldMap3D shown = entity == null ? null
                : entity.get(org.zeronura.nulzs.graphics3d.WorldMap3D.class);
        if (shown == null || !shown.isEnabled()) {
            mapPick = null;
            return null;
        }
        if (!shown.ready()) {
            // A scene built again after an undo loads its map when it is first drawn: the pick waits for it.
            return null;
        }
        if (mapPick.item(this) == null) {
            mapPick = null;
        }
        return mapPick;
    }

    /** The selected entities that aren't under another selected one: what moves, copies and deletes as a group. */
    List<Entity> selectedTops() {
        Set<Entity> chosen = new HashSet<>(selection);
        List<Entity> tops = new ArrayList<>();
        for (Entity entity : entitiesInOrder()) {
            if (!chosen.contains(entity)) {
                continue;
            }
            boolean underAnother = false;
            for (Entity up = entity.parent(); up != null; up = up.parent()) {
                if (chosen.contains(up)) {
                    underAnother = true;
                    break;
                }
            }
            if (!underAnother) {
                tops.add(entity);
            }
        }
        return tops;
    }

    // --- Editing ------------------------------------------------------------------------------------------------

    /** A new empty entity under {@code parent} (or at the top), named so it doesn't clash with its siblings. */
    Entity createEntity(String name, Entity parent) {
        Entity entity = new Entity(uniqueName(name, parent));
        if (parent != null) {
            parent.addChild(entity);
        } else {
            scene.add(entity);
        }
        idOf(entity);
        changed();
        return entity;
    }

    String uniqueName(String base, Entity parent) {
        Set<String> taken = new HashSet<>();
        for (Entity sibling : parent == null ? scene.roots() : parent.children()) {
            taken.add(sibling.name());
        }
        if (!taken.contains(base)) {
            return base;
        }
        String stem = base.replaceAll(" \\(\\d+\\)$", "");
        for (int i = 1; ; i++) {
            String candidate = stem + " (" + i + ")";
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    <T extends Component> T addComponent(Entity entity, T component) {
        entity.add(component);
        changed();
        return component;
    }

    void delete(List<Entity> entities) {
        if (entities.isEmpty()) {
            return;
        }
        for (Entity entity : entities) {
            entity.destroy();
        }
        SceneHost.flushDestroyed(scene);
        selection.removeIf(Entity::isDestroyed);
        syncIds();
        changed();
    }

    /** Copies of the entities beside them, selected in their place. */
    List<Entity> duplicate(List<Entity> entities) {
        List<Entity> tops = topsOf(entities);
        if (tops.isEmpty()) {
            return List.of();
        }
        List<Object> json = SceneFile.serializeEntities(tops, ids);
        syncIds();
        List<Entity> copies = instantiate(json, true);
        for (int i = 0; i < copies.size() && i < tops.size(); i++) {
            Entity copy = copies.get(i);
            copy.setName(uniqueName(tops.get(i).name(), copy.parent()));
            copy.setSiblingIndex(tops.get(i).siblingIndex() + 1);
        }
        selection.clear();
        selection.addAll(copies);
        changed();
        return copies;
    }

    /** The entities as text for the clipboard. */
    String copy(List<Entity> entities) {
        List<Entity> tops = topsOf(entities);
        List<Object> json = SceneFile.serializeEntities(tops, ids);
        syncIds();
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(CLIPBOARD_KEY, json);
        return Json.compact(wrapper);
    }

    /** Entities from clipboard text, put under {@code parent} (or at the top); empty if the text isn't entities. */
    List<Entity> paste(String text, Entity parent) {
        List<Map<String, Object>> entries;
        try {
            Map<String, Object> wrapper = Json.parseObject(text);
            entries = Json.objects(wrapper, CLIPBOARD_KEY);
        } catch (RuntimeException e) {
            return List.of();
        }
        Set<Object> copiedIds = new HashSet<>();
        for (Map<String, Object> entry : entries) {
            copiedIds.add(entry.get("id"));
        }
        List<Object> json = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            if (!copiedIds.contains(copy.get("parent"))) {
                copy.put("parent", parent == null ? null : idOf(parent));
            }
            json.add(copy);
        }
        List<Entity> made = instantiate(json, true);
        for (Entity entity : made) {
            entity.setName(uniqueName(entity.name(), entity.parent()));
        }
        selection.clear();
        selection.addAll(made);
        changed();
        return made;
    }

    @SuppressWarnings("unchecked")
    private List<Entity> instantiate(List<Object> json, boolean freshIds) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : json) {
            maps.add((Map<String, Object>) item);
        }
        List<String> found = new ArrayList<>();
        // The new tops are those whose parent isn't one of the new ones; instantiateEntities parents the rest.
        Set<Entity> before = new HashSet<>(ids.keySet());
        SceneFile.instantiateEntities(maps, scene, catalog.loader(), byId, ids, freshIds, found);
        List<Entity> made = new ArrayList<>();
        for (Entity entity : entitiesInOrder()) {
            if (!before.contains(entity) && (entity.parent() == null || before.contains(entity.parent()))) {
                made.add(entity);
            }
        }
        if (!found.isEmpty()) {
            problems = found;
        }
        return made;
    }

    private List<Entity> topsOf(List<Entity> entities) {
        Set<Entity> chosen = new HashSet<>(entities);
        List<Entity> tops = new ArrayList<>();
        for (Entity entity : entitiesInOrder()) {
            if (!chosen.contains(entity)) {
                continue;
            }
            boolean under = false;
            for (Entity up = entity.parent(); up != null; up = up.parent()) {
                under |= chosen.contains(up);
            }
            if (!under) {
                tops.add(entity);
            }
        }
        return tops;
    }

    /** Whether {@code ancestor} is {@code entity} or above it. */
    static boolean isAncestor(Entity ancestor, Entity entity) {
        for (Entity e = entity; e != null; e = e.parent()) {
            if (e == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves an entity under {@code parent} (null for the top) at {@code index} among its new siblings, keeping where it
     * is in the world. Refused if the parent is the entity itself or under it.
     */
    boolean reparent(Entity entity, Entity parent, int index) {
        if (parent != null && isAncestor(entity, parent)) {
            return false;
        }
        Matrix4f world = entity.transform().worldMatrix(new Matrix4f());
        if (parent != entity.parent()) {
            if (parent == null) {
                entity.detachFromParent();
            } else {
                parent.addChild(entity);
            }
            Matrix4f parentWorld = parent == null ? new Matrix4f() : parent.transform().worldMatrix(new Matrix4f());
            Matrix4f local = parentWorld.invert().mul(world);
            setFromMatrix(entity, local);
        }
        entity.setSiblingIndex(index);
        changed();
        return true;
    }

    /** Sets an entity's position, rotation and scale from a local matrix. */
    static void setFromMatrix(Entity entity, Matrix4f local) {
        Vector3f position = local.getTranslation(new Vector3f());
        Vector3f scale = local.getScale(new Vector3f());
        Quaternionf rotation = local.getUnnormalizedRotation(new Quaternionf()).normalize();
        entity.transform().position.set(position);
        entity.transform().rotation.set(rotation);
        entity.transform().scale.set(scale);
    }

    /** Sets an entity's world position, whatever its parent. */
    static void setWorldPosition(Entity entity, Vector3f world) {
        if (entity.parent() == null) {
            entity.transform().position.set(world);
            return;
        }
        Matrix4f parentWorld = entity.parent().transform().worldMatrix(new Matrix4f());
        parentWorld.invert().transformPosition(world, entity.transform().position);
    }

    /** Applies the settings to the live scene after one was changed. */
    void applySettings() {
        settings.apply(scene);
        changed();
    }

    /** The project-wide uniqueness of a name, for a new scene file. */
    static Map<String, Object> emptyScene(boolean twoD) {
        SceneSettings settings = new SceneSettings();
        if (twoD) {
            settings.mode = SceneSettings.Mode.FLAT_2D;
        }
        return SceneFile.empty(settings);
    }
}
