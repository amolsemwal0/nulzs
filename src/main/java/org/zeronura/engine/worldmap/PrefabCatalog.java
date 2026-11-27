package org.zeronura.engine.worldmap;

import org.zeronura.engine.graphics3d.MeshBuilder3D;
import org.zeronura.engine.render.MeshData;
import org.zeronura.engine.render.StaticMesh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The props a map can place, by id. Maps store only prefab ids, so improving a prefab's mesh updates every
 * map that uses it.
 *
 * <p>Prefabs own GPU meshes: create the catalog once the GL context exists, share it between scenes, and
 * {@link #delete} it when the game closes.
 */
public final class PrefabCatalog {

    // Draw distance grows with a prefab's size: a tuft of grass is lost to the eye long before a house.
    private static final float DRAW_DISTANCE_PER_METRE = 80f;
    private static final float MIN_DRAW_DISTANCE = 100f;
    private static final float MAX_DRAW_DISTANCE = 2000f;
    /** The category the editor's {@link Preset}s' foliage is listed under. */
    public static final String PRESETS = "Presets";

    /**
     * @param colliderWidth  collision box footprint along the prefab's own x, at scale 1; 0 for no collision
     * @param colliderHeight collision box height from the ground up
     * @param height         how far the mesh reaches above its origin
     * @param radius         how far the mesh reaches out from its vertical axis
     * @param looks          textured ways it can be drawn instead of {@code mesh}, one picked per object by
     *                       {@link PrefabLook#pick}; empty to draw {@code mesh}
     */
    public record Prefab(String id, String name, String category, StaticMesh mesh,
                         float colliderWidth, float colliderHeight, float colliderDepth, float height, float radius,
                         List<PrefabLook> looks) {

        public Prefab {
            looks = looks == null ? List.of() : List.copyOf(looks);
        }

        public Prefab(String id, String name, String category, StaticMesh mesh,
                      float colliderWidth, float colliderHeight, float colliderDepth, float height, float radius) {
            this(id, name, category, mesh, colliderWidth, colliderHeight, colliderDepth, height, radius, List.of());
        }

        /** The look an object standing at (x, z) wears, or null when this prefab has none. */
        public PrefabLook lookAt(float x, float z) {
            return looks.isEmpty() ? null : looks.get(PrefabLook.pick(looks.size(), x, z));
        }

        public boolean isSolid() {
            return colliderWidth > 0f && colliderHeight > 0f && colliderDepth > 0f;
        }

        /** Radius of the footprint, for selection and spacing. */
        public float footprintRadius() {
            return Math.max(0.75f, 0.5f * Math.max(colliderWidth, colliderDepth));
        }

        /** How far away it is still drawn at scale 1: grass and flowers only close by, trees and buildings far off. */
        public float drawDistance() {
            return Math.clamp(Math.max(height, radius * 2f) * DRAW_DISTANCE_PER_METRE, MIN_DRAW_DISTANCE, MAX_DRAW_DISTANCE);
        }
    }

    /**
     * Picks the look an object on a map wears, for a prefab whose looks stand for something about the place rather than
     * for variety: a School's hall built for the School whose grounds it stands on. See {@link #setLookPicker}.
     */
    @FunctionalInterface
    public interface LookPicker {
        /** @return an index into the prefab's looks, or -1 to pick one by where the object stands, as usual */
        int pick(WorldMap map, MapObject object, int looks);
    }

    private final Map<String, Prefab> prefabs = new LinkedHashMap<>();
    private final Map<String, LookPicker> pickers = new LinkedHashMap<>();
    /** Ids answered by another prefab — see {@link #replace} and {@link #alias}. */
    private final Map<String, String> replacedBy = new LinkedHashMap<>();
    /** Prefabs made on demand by a {@link #generate generator}: placed on maps, never listed in the asset drawer. */
    private final Map<String, Prefab> generated = new LinkedHashMap<>();
    private final Map<String, java.util.function.Function<String, MeshBuilder3D>> generators = new LinkedHashMap<>();

    public Prefab register(String id, String name, String category, MeshBuilder3D shape,
                           float colliderWidth, float colliderHeight, float colliderDepth) {
        MeshData data = shape.buildData();
        float[] size = measure(data);
        return register(new Prefab(id, name, category, data.upload(), colliderWidth, colliderHeight, colliderDepth, size[0], size[1]));
    }

    /** Adds a prefab whose mesh is already built. */
    public Prefab register(Prefab prefab) {
        if (prefabs.containsKey(prefab.id())) {
            throw new IllegalArgumentException("duplicate prefab id " + prefab.id());
        }
        prefabs.put(prefab.id(), prefab);
        return prefab;
    }

    /**
     * Gives a registered prefab textured looks, drawn in place of its flat mesh — see {@link PrefabLook}. The catalogue
     * takes the looks' meshes and frees them with its own; their textures stay the caller's.
     *
     * @return whether there was such a prefab
     */
    public boolean setLooks(String id, List<PrefabLook> looks) {
        Prefab prefab = prefabs.get(id);
        if (prefab == null) {
            return false;
        }
        prefabs.put(id, new Prefab(prefab.id(), prefab.name(), prefab.category(), prefab.mesh(), prefab.colliderWidth(),
                prefab.colliderHeight(), prefab.colliderDepth(), prefab.height(), prefab.radius(), looks));
        return true;
    }

    /**
     * Gives a registered prefab textured looks and a new flat mesh to match them, for looks shaped differently from
     * the mesh it was registered with: thumbnails and the placement ghost then show the shape the map will. The old
     * mesh is freed; the collider stays as registered.
     *
     * @return whether there was such a prefab
     */
    public boolean setLooks(String id, MeshBuilder3D flat, List<PrefabLook> looks) {
        Prefab prefab = prefabs.get(id);
        if (prefab == null) {
            return false;
        }
        MeshData data = flat.buildData();
        float[] size = measure(data);
        if (prefab.mesh() != null) {
            prefab.mesh().delete();
        }
        prefabs.put(id, new Prefab(prefab.id(), prefab.name(), prefab.category(), data.upload(), prefab.colliderWidth(),
                prefab.colliderHeight(), prefab.colliderDepth(), size[0], size[1], looks));
        return true;
    }

    /**
     * Changes a registered prefab's collision box, for one whose looks gave it a new shape: {@code width} along its own
     * x, {@code depth} along its z, at scale 1; 0 for no collision.
     *
     * @return whether there was such a prefab
     */
    public boolean setCollider(String id, float width, float height, float depth) {
        Prefab prefab = prefabs.get(id);
        if (prefab == null) {
            return false;
        }
        prefabs.put(id, new Prefab(prefab.id(), prefab.name(), prefab.category(), prefab.mesh(), width, height, depth,
                prefab.height(), prefab.radius(), prefab.looks()));
        return true;
    }

    /**
     * Has a prefab's objects on a map pick their looks by {@code picker}, rather than each by where it stands. Kept
     * across {@link #setLooks}; null puts the usual pick back.
     */
    public void setLookPicker(String id, LookPicker picker) {
        if (picker == null) {
            pickers.remove(id);
        } else {
            pickers.put(id, picker);
        }
    }

    /**
     * The look an object on a map wears: the one its prefab's {@linkplain #setLookPicker picker} chooses, or else one
     * picked by where it stands. Null for a prefab with no looks, which is drawn as its mesh.
     */
    public PrefabLook lookFor(Prefab prefab, WorldMap map, MapObject object) {
        LookPicker picker = pickers.get(prefab.id());
        if (picker != null && !prefab.looks().isEmpty()) {
            int index = picker.pick(map, object, prefab.looks().size());
            if (index >= 0 && index < prefab.looks().size()) {
                return prefab.looks().get(index);
            }
        }
        return prefab.lookAt(object.x, object.z);
    }

    /** A mesh's height above its origin and its reach from the vertical axis, as {@code {height, radius}}. */
    public static float[] measure(MeshData data) {
        ByteBuffer vertices = data.vertexData();
        float height = 0f;
        float radius = 0f;
        for (int p = 0; p + StaticMesh.STRIDE <= vertices.limit(); p += StaticMesh.STRIDE) {
            float x = vertices.getFloat(p);
            float y = vertices.getFloat(p + Float.BYTES);
            float z = vertices.getFloat(p + 2 * Float.BYTES);
            height = Math.max(height, y);
            radius = Math.max(radius, (float) Math.sqrt(x * x + z * z));
        }
        return new float[]{height, radius};
    }

    public Prefab get(String id) {
        Prefab prefab = prefabs.get(id);
        if (prefab == null && id != null && replacedBy.containsKey(id)) {
            prefab = prefabs.get(replacedBy.get(id));
        }
        if (prefab == null && id != null) {
            prefab = generated(id);
        }
        return prefab;
    }

    /**
     * Takes a registered prefab out of the catalogue and frees its meshes — for one made again, a preset changed in the
     * editor. Whatever placed it must be placed again before it is next drawn.
     *
     * @return whether there was one
     */
    public boolean remove(String id) {
        Prefab prefab = prefabs.remove(id);
        if (prefab == null) {
            return false;
        }
        free(prefab, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return true;
    }

    private static void free(Prefab prefab, java.util.Set<Object> freed) {
        if (prefab.mesh() != null && freed.add(prefab.mesh())) {
            prefab.mesh().delete();
        }
        for (PrefabLook look : prefab.looks()) {
            if (look.plain() != null && freed.add(look.plain())) {
                look.plain().delete();
            }
            for (PrefabLook.Piece piece : look.pieces()) {
                if (freed.add(piece.mesh())) {
                    piece.mesh().delete();
                }
            }
            for (PrefabLook.Swing swing : look.swings()) {
                if (swing.plain() != null && freed.add(swing.plain())) {
                    swing.plain().delete();
                }
                for (PrefabLook.Piece piece : swing.pieces()) {
                    if (freed.add(piece.mesh())) {
                        piece.mesh().delete();
                    }
                }
            }
        }
    }

    /**
     * Makes {@code id} answer the prefab {@code targetId} without removing anything: two names for one prefab, the alias
     * left out of the asset drawer. For pieces a tool places by a role ("road:straight") that a game's own model plays.
     */
    public void alias(String id, String targetId) {
        replacedBy.put(id, targetId);
    }

    /**
     * Makes prefabs on demand for every id starting with {@code prefix}: the first time a map asks for one, the
     * generator builds its mesh from the id (returning null for an id it doesn't make), and it is kept from then on.
     * Generated prefabs have no collision and are not listed in {@link #all}. For pieces with too many shapes to build
     * ahead of time, such as road junctions at every angle.
     */
    public void generate(String prefix, java.util.function.Function<String, MeshBuilder3D> generator) {
        generators.put(prefix, generator);
    }

    private Prefab generated(String id) {
        Prefab made = generated.get(id);
        if (made != null) {
            return made;
        }
        for (Map.Entry<String, java.util.function.Function<String, MeshBuilder3D>> entry : generators.entrySet()) {
            if (id.startsWith(entry.getKey())) {
                MeshBuilder3D shape = entry.getValue().apply(id);
                if (shape == null || shape.triangleCount() == 0) {
                    return null;
                }
                MeshData data = shape.buildData();
                float[] size = measure(data);
                made = new Prefab(id, id, "Generated", data.upload(), 0f, 0f, 0f, size[0], size[1]);
                generated.put(id, made);
                return made;
            }
        }
        return null;
    }

    /**
     * Swaps one prefab for another, for a game whose own model takes the place of a built-in one: {@code id} leaves the
     * catalogue and its mesh is freed, and from then on asking for {@code id} answers the prefab {@code withId}.
     *
     * <p>An alias rather than a copy, so the asset drawer lists the replacement once, under its own name, while every
     * map and piece of code still naming the old id gets the new one. Maps that already store the old id keep it:
     * nothing is rewritten, and saving such a map again saves the id it was loaded with.
     *
     * @return whether it was replaced; false, leaving {@code id} as it was, when there is no prefab {@code withId} —
     * a model file that failed to load should leave the game its built-in stand-in rather than nothing
     */
    public boolean replace(String id, String withId) {
        if (id.equals(withId) || !prefabs.containsKey(withId)) {
            return false;
        }
        Prefab old = prefabs.remove(id);
        if (old != null && old.mesh() != null) {
            old.mesh().delete();
        }
        replacedBy.put(id, withId);
        return true;
    }

    public Collection<Prefab> all() {
        return Collections.unmodifiableCollection(prefabs.values());
    }

    public List<String> categories() {
        List<String> categories = new ArrayList<>();
        for (Prefab prefab : prefabs.values()) {
            if (!categories.contains(prefab.category())) {
                categories.add(prefab.category());
            }
        }
        return categories;
    }

    public void delete() {
        // Looks share meshes between them, so each is freed once.
        java.util.Set<Object> freed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        prefabs.values().forEach(prefab -> {
            if (prefab.mesh() != null) {
                prefab.mesh().delete();
            }
            for (PrefabLook look : prefab.looks()) {
                if (look.plain() != null && freed.add(look.plain())) {
                    look.plain().delete();
                }
                for (PrefabLook.Piece piece : look.pieces()) {
                    if (freed.add(piece.mesh())) {
                        piece.mesh().delete();
                    }
                }
                for (PrefabLook.Swing swing : look.swings()) {
                    if (swing.plain() != null && freed.add(swing.plain())) {
                        swing.plain().delete();
                    }
                    for (PrefabLook.Piece piece : swing.pieces()) {
                        if (freed.add(piece.mesh())) {
                            piece.mesh().delete();
                        }
                    }
                }
            }
        });
        prefabs.clear();
        generated.values().forEach(prefab -> prefab.mesh().delete());
        generated.clear();
        replacedBy.clear();
        pickers.clear();
    }
}
