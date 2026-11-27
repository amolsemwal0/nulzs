package org.zeronura.nulzs.scene;

import org.joml.Quaternionf;
import org.zeronura.nulzs.data.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scenes saved as files: the entities of a scene with their names, tags, transforms, parents and components, each
 * component's settings ({@link ComponentFields}), the links between them, and the scene's {@link SceneSettings}. This is
 * what the editor saves and what {@link FileScene} plays.
 *
 * <p>The file is JSON, written to read and to merge by hand:
 * <pre>{@code
 * {
 *   "format": 1,
 *   "settings": {"mode": "WORLD_3D", "lighting": "DAY", ...},
 *   "entities": [
 *     {"id": "k3x9q2", "name": "Door", "parent": null, "active": true, "tags": [],
 *      "position": [0, 1, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1],
 *      "components": [{"type": "org.zeronura.nulzs.graphics3d.Shape3D", "enabled": true, "fields": {...}}]}
 *   ],
 *   "editor": {...}
 * }
 * }</pre>
 * Entities are listed parents first, children in order after them. Each has an id that stays the same from save to save,
 * which is how one entity, component field or signal points at another. Rotation is a quaternion (x, y, z, w).
 */
public final class SceneFile {

    /** The extension scene files are saved with. */
    public static final String EXTENSION = ".scene";
    public static final int FORMAT = 1;

    private SceneFile() {
    }

    /** What loading a scene file made. */
    public record Loaded(Map<String, Entity> byId, Map<Entity, String> ids, SceneSettings settings,
                         Map<String, Object> editor, List<String> problems) {
    }

    // --- Files --------------------------------------------------------------------------------------------------

    public static Map<String, Object> read(Path file) throws IOException {
        return Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes a scene file whole or not at all: to a file beside it first, then moved over it. */
    public static void write(Path file, Map<String, Object> json) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temporary = folder.resolve(file.getFileName() + ".saving");
        Files.writeString(temporary, Json.pretty(json), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The engine's package before it was named after Nulzs, as scenes saved then still name its components. */
    private static final String OLD_ENGINE_PACKAGE = "org.zeronura.engine.";
    private static final String ENGINE_PACKAGE = "org.zeronura.nulzs.";

    /** A component class name as a scene file gives it, with the engine's old package name changed to today's. */
    public static String currentClassName(String saved) {
        return saved.startsWith(OLD_ENGINE_PACKAGE)
                ? ENGINE_PACKAGE + saved.substring(OLD_ENGINE_PACKAGE.length())
                : saved;
    }

    /** A scene with nothing in it but its settings, as JSON. */
    public static Map<String, Object> empty(SceneSettings settings) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", (long) FORMAT);
        json.put("settings", settings.toJson());
        json.put("entities", new ArrayList<>());
        return json;
    }

    // --- Loading ------------------------------------------------------------------------------------------------

    /**
     * Builds a saved scene into {@code scene}: puts its settings into effect, then adds its entities. A component whose
     * class isn't found becomes a {@link MissingComponent}; what could not be set is listed in {@link Loaded#problems}.
     *
     * @param loader where component classes are looked up — the game's, or an editor's for a project's scripts
     */
    public static Loaded instantiate(Map<String, Object> json, Scene scene, ClassLoader loader) {
        List<String> problems = new ArrayList<>();
        SceneSettings settings = SceneSettings.fromJson(json.get("settings"), problems);
        settings.apply(scene);
        Map<String, Entity> byId = new HashMap<>();
        Map<Entity, String> ids = new HashMap<>();
        instantiateEntities(Json.objects(json, "entities"), scene, loader, byId, ids, false, problems);
        @SuppressWarnings("unchecked")
        Map<String, Object> editor = json.get("editor") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        return new Loaded(byId, ids, settings, editor, problems);
    }

    /**
     * Adds saved entities to a scene — a whole scene's, or a few copied ones being pasted — and returns the top ones in
     * order.
     *
     * @param byId     entities by id, already in the scene and those added now; references are looked up here
     * @param ids      the id of each entity; the new ones are added
     * @param freshIds give the entities new ids instead of their saved ones, for a copy alongside the originals;
     *                 references among the copies follow them, references outside them keep pointing where they did
     */
    public static List<Entity> instantiateEntities(List<Map<String, Object>> entities, Scene scene, ClassLoader loader,
                                                   Map<String, Entity> byId, Map<Entity, String> ids, boolean freshIds,
                                                   List<String> problems) {
        Map<String, String> renamed = new HashMap<>();
        if (freshIds) {
            Set<String> taken = new HashSet<>(byId.keySet());
            for (Map<String, Object> saved : entities) {
                String id = newId(taken);
                taken.add(id);
                renamed.put(Json.string(saved, "id", ""), id);
            }
        }
        java.util.function.Function<String, String> idFor = old -> freshIds ? renamed.getOrDefault(old, old) : old;

        // Every entity first, so references and parents can point anywhere in the file.
        List<Entity> made = new ArrayList<>();
        List<Map<String, Object>> fieldsOf = new ArrayList<>();
        List<Component> components = new ArrayList<>();
        for (Map<String, Object> saved : entities) {
            Entity entity = new Entity(Json.string(saved, "name", "Entity"));
            entity.setActive(Json.bool(saved, "active", true));
            for (Object tag : list(saved.get("tags"))) {
                entity.tag(String.valueOf(tag));
            }
            float[] position = ComponentFields.floats(saved.get("position"), 3);
            entity.transform().position.set(position[0], position[1], position[2]);
            if (saved.get("rotation") instanceof List<?>) {
                float[] rotation = ComponentFields.floats(saved.get("rotation"), 4);
                entity.transform().rotation.set(rotation[0], rotation[1], rotation[2], rotation[3]);
                if (entity.transform().rotation.lengthSquared() < 1e-8f) {
                    entity.transform().rotation.identity();
                }
                entity.transform().rotation.normalize();
            }
            if (saved.get("scale") instanceof List<?>) {
                float[] scale = ComponentFields.floats(saved.get("scale"), 3);
                entity.transform().scale.set(scale[0], scale[1], scale[2]);
            }
            String id = idFor.apply(Json.string(saved, "id", ""));
            if (id.isEmpty() || byId.containsKey(id)) {
                id = newId(byId.keySet());
            }
            byId.put(id, entity);
            ids.put(entity, id);
            made.add(entity);

            for (Object item : list(saved.get("components"))) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> savedComponent = (Map<String, Object>) map;
                Component component = createComponent(savedComponent, loader, problems);
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = savedComponent.get("fields") instanceof Map<?, ?> f
                        ? (Map<String, Object>) f : Map.of();
                if (!(component instanceof MissingComponent)) {
                    ComponentFields.loadValues(component, fields, problems);
                    component.setEnabled(Json.bool(savedComponent, "enabled", true));
                    if (component instanceof StoresData stores && savedComponent.containsKey("data")) {
                        try {
                            stores.loadData(savedComponent.get("data"));
                        } catch (RuntimeException e) {
                            problems.add(component.getClass().getSimpleName() + "'s data: " + e.getMessage());
                        }
                    }
                }
                entity.add(component);
                components.add(component);
                fieldsOf.add(fields);
            }
        }

        // Then references, now that everything they can name exists.
        java.util.function.Function<String, Entity> lookup = old -> byId.get(idFor.apply(old));
        for (int i = 0; i < components.size(); i++) {
            if (!(components.get(i) instanceof MissingComponent)) {
                ComponentFields.loadReferences(components.get(i), fieldsOf.get(i), lookup, problems);
            }
        }

        // Then the hierarchy, and into the scene top down.
        List<Entity> tops = new ArrayList<>();
        for (int i = 0; i < made.size(); i++) {
            Entity entity = made.get(i);
            Object parentId = entities.get(i).get("parent");
            Entity parent = parentId instanceof String id ? lookup.apply(id) : null;
            if (parent != null && parent != entity) {
                parent.addChild(entity);
            } else {
                tops.add(entity);
            }
        }
        for (Entity top : tops) {
            scene.add(top);
        }
        return tops;
    }

    private static Component createComponent(Map<String, Object> saved, ClassLoader loader, List<String> problems) {
        String type = currentClassName(Json.string(saved, "type", ""));
        try {
            Class<?> c = Class.forName(type, true, loader);
            if (ComponentFields.isCreatable(c)) {
                return ComponentFields.create(c);
            }
            problems.add(type + " can't be made from a scene: it needs to be a public component with a public constructor taking nothing");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            problems.add("Missing script " + type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            problems.add("Could not make " + type + ": " + e);
        }
        return new MissingComponent(type, saved);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    // --- Saving -------------------------------------------------------------------------------------------------

    /**
     * A whole scene as JSON.
     *
     * @param ids each entity's id; entities without one are given one, and it is added
     */
    public static Map<String, Object> serialize(Scene scene, Map<Entity, String> ids, SceneSettings settings,
                                                Map<String, Object> editor) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", (long) FORMAT);
        json.put("settings", settings.toJson());
        json.put("entities", serializeEntities(scene.roots(), ids));
        if (editor != null && !editor.isEmpty()) {
            json.put("editor", editor);
        }
        return json;
    }

    /** Some entities and everything under them, as the {@code entities} list of a scene file. */
    public static List<Object> serializeEntities(List<Entity> tops, Map<Entity, String> ids) {
        List<Object> out = new ArrayList<>();
        for (Entity top : tops) {
            serialize(top, ids, out);
        }
        return out;
    }

    private static void serialize(Entity entity, Map<Entity, String> ids, List<Object> out) {
        if (entity.isDestroyed()) {
            return;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", idOf(entity, ids));
        json.put("name", entity.name());
        json.put("parent", entity.parent() == null ? null : idOf(entity.parent(), ids));
        json.put("active", entity.isActiveSelf());
        json.put("tags", new ArrayList<>(new java.util.TreeSet<>(entity.tags())));
        Transform transform = entity.transform();
        json.put("position", ComponentFields.vector(transform.position));
        Quaternionf q = transform.rotation;
        json.put("rotation", List.of(ComponentFields.decimal(q.x), ComponentFields.decimal(q.y),
                ComponentFields.decimal(q.z), ComponentFields.decimal(q.w)));
        json.put("scale", ComponentFields.vector(transform.scale));
        List<Object> components = new ArrayList<>();
        for (Component component : entity.components()) {
            if (component instanceof MissingComponent missing) {
                components.add(missing.saved());
                continue;
            }
            if (!ComponentFields.isCreatable(component.getClass())) {
                continue;
            }
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("type", component.getClass().getName());
            saved.put("enabled", component.isEnabled());
            saved.put("fields", ComponentFields.save(component, e -> e.isDestroyed() ? null : idOf(e, ids)));
            if (component instanceof StoresData stores) {
                saved.put("data", stores.saveData());
            }
            components.add(saved);
        }
        json.put("components", components);
        out.add(json);
        for (Entity child : entity.children()) {
            serialize(child, ids, out);
        }
    }

    private static String idOf(Entity entity, Map<Entity, String> ids) {
        return ids.computeIfAbsent(entity, e -> newId(new HashSet<>(ids.values())));
    }

    /** A short id not in {@code taken}. */
    public static String newId(java.util.Collection<String> taken) {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        while (true) {
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                id.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
            }
            if (!taken.contains(id.toString())) {
                return id.toString();
            }
        }
    }
}
