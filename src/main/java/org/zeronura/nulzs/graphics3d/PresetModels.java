package org.zeronura.nulzs.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.nulzs.core.Assets;
import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.nodes.geometry.GeometryOps;
import org.zeronura.nulzs.nodes.geometry.GeometrySet;
import org.zeronura.nulzs.nodes.geometry.MeshGeometry;
import org.zeronura.nulzs.nodes.render.GeometryTrees;
import org.zeronura.nulzs.nodes.render.NodeMesh;
import org.zeronura.nulzs.nodes.render.RenderMeshes;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.TexturedMesh;
import org.zeronura.nulzs.render.TexturedMeshBuilder;
import org.zeronura.nulzs.render.TexturedMeshData;
import org.zeronura.nulzs.worldmap.MapTree;
import org.zeronura.nulzs.worldmap.Preset;
import org.zeronura.nulzs.worldmap.PresetLibrary;
import org.zeronura.nulzs.worldmap.PrefabCatalog;
import org.zeronura.nulzs.worldmap.PrefabLook;
import org.zeronura.nulzs.worldmap.SplineMaterials;
import org.zeronura.nulzs.worldmap.TreeMeshes;
import org.zeronura.nulzs.worldmap.TreeShape;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What {@link Preset}s look like drawn: a tree's bark and leaves, and foliage made by its geometry node tree, each as
 * textured meshes with their materials. The editor's Preset Maker previews them with these, and a map's foliage copies
 * are drawn through prefabs made from them ({@link #syncPrefabs}): {@code preset:<name>}, one textured look each.
 *
 * <p>Foliage wears what its materials were baked to — the Shader Tool's Bake... writes {@code <prefix>_albedo.png} and
 * {@code _normal.png}, and the material file says the prefix — as the game's meadow does; a material never baked is
 * drawn a plain colour, and said so.
 */
public final class PresetModels {

    /** A surface and what it wears. */
    public record Part(TexturedMeshData mesh, FoliageMaterial material) {
    }

    /**
     * Foliage made, not yet on the GPU: each material's surface (positions, normals, UVs, triangles), what it is worn
     * with, and how big it all is. Made on any thread.
     */
    public record FoliageShape(List<Surface> surfaces, float height, float radius, int triangles, List<String> problems) {
        public boolean ok() {
            return !surfaces.isEmpty();
        }
    }

    /** One material's triangles: {@code material} is the asset path of the saved shader, or "" for none. */
    public record Surface(String material, float[] positions, float[] normals, float[] uvs, int[] indices) {
    }

    private static final Color UNBAKED = Color.rgb(0x8FA67A);
    /** What each catalogue holds of the presets: the look key each foliage prefab was built from. */
    private static final Map<PrefabCatalog, Map<String, String>> INSTALLED = new WeakHashMap<>();

    private PresetModels() {
    }

    // --- Trees --------------------------------------------------------------------------------------------------

    /** A tree's bark and leaves, in the materials a scene's trees wear. Builds the meshes; doesn't upload them. */
    public static List<Part> tree(MapTree tree, Assets assets) {
        List<Part> parts = new ArrayList<>();
        if (tree == null || !tree.drawable()) {
            return parts;
        }
        TreeShape shape = TreeShape.of(tree);
        TreeMeshes.Built built = TreeMeshes.build(tree, shape);
        SplineMaterials looks = SplineLooks.of(assets);
        FoliageMaterial bark = tree.barkTexture == null ? null : looks.material(tree.barkTexture);
        parts.add(new Part(built.bark(), bark != null ? bark : SplineLooks.plain(Color.rgb(0x6B4E36))));
        parts.add(new Part(built.leaves(), looks.leafMaterial(tree.leafTexture)));
        return parts;
    }

    // --- Foliage ------------------------------------------------------------------------------------------------

    /** Runs a foliage preset's geometry tree and lays out its surfaces, on whatever thread calls it. */
    public static FoliageShape foliage(Preset preset, Path assetsRoot) {
        if (preset.nodes == null || preset.nodes.isBlank()) {
            return empty("Pick the geometry node tree that makes it");
        }
        Path file = assetsRoot.resolve(preset.nodes.trim());
        if (!Files.isRegularFile(file)) {
            return empty("There is no " + preset.nodes);
        }
        GeometryTrees.Result result = GeometryTrees.run(file, new LinkedHashMap<>(preset.inputs));
        if (!result.ok()) {
            return empty(result.problems());
        }
        return fromGeometry(result.geometry(), preset.softLight, assetsRoot);
    }

    /** Geometry made into surfaces, one for each material: v turned over for baked pictures, light softened by {@code soft}. */
    static FoliageShape fromGeometry(GeometrySet geometry, float soft, Path assetsRoot) {
        GeometrySet real = GeometryOps.realize(geometry);
        MeshGeometry mesh = real.mesh;
        if (mesh == null || mesh.faceCount() == 0) {
            return empty("The tree made no faces");
        }
        List<RenderMeshes.Data> data = RenderMeshes.build(mesh, Map.of(), null, null, null);
        List<Surface> surfaces = new ArrayList<>();
        float height = 0f;
        float radius = 0f;
        int triangles = 0;
        for (RenderMeshes.Data part : data) {
            int stride = NodeMesh.BASE_FLOATS + 4 * part.attributeCount();
            int count = part.vertices().length / stride;
            float[] positions = new float[count * 3];
            float[] normals = new float[count * 3];
            float[] uvs = new float[count * 2];
            float[] v = part.vertices();
            for (int i = 0; i < count; i++) {
                int at = i * stride;
                System.arraycopy(v, at, positions, i * 3, 3);
                System.arraycopy(v, at + 3, normals, i * 3, 3);
                uvs[i * 2] = v[at + 6];
                uvs[i * 2 + 1] = 1f - v[at + 7];
                height = Math.max(height, positions[i * 3 + 1]);
                radius = Math.max(radius, (float) Math.hypot(positions[i * 3], positions[i * 3 + 2]));
            }
            triangles += part.indices().length / 3;
            String material = part.slot() < mesh.materials.size() && mesh.materials.get(part.slot()) != null
                    ? mesh.materials.get(part.slot()) : "";
            surfaces.add(new Surface(material, positions, normals, uvs, part.indices().clone()));
        }
        // Light softened into one rounded mass: each normal leans up and out from the middle, as the meadow's are.
        float keep = 1f - Math.clamp(soft, 0f, 1f);
        float reach = Math.max(radius, 1e-4f);
        for (Surface surface : surfaces) {
            float[] p = surface.positions();
            float[] n = surface.normals();
            for (int i = 0; i < p.length / 3; i++) {
                float x = p[i * 3];
                float z = p[i * 3 + 2];
                float out = (float) Math.hypot(x, z);
                float domeX = out > 1e-5f ? x / out * Math.min(1f, out / reach) : 0f;
                float domeZ = out > 1e-5f ? z / out * Math.min(1f, out / reach) : 0f;
                float nx = n[i * 3] * keep + domeX * 0.45f * (1f - keep);
                float ny = n[i * 3 + 1] * keep + (1f - keep);
                float nz = n[i * 3 + 2] * keep + domeZ * 0.45f * (1f - keep);
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 1e-6f) {
                    nx = 0f;
                    ny = 1f;
                    nz = 0f;
                    length = 1f;
                }
                n[i * 3] = nx / length;
                n[i * 3 + 1] = ny / length;
                n[i * 3 + 2] = nz / length;
            }
        }
        List<String> problems = new ArrayList<>();
        for (Surface surface : surfaces) {
            if (bakedPicture(assetsRoot, surface.material()) == null) {
                problems.add(surface.material().isEmpty()
                        ? "Part of it has no material: Set Material in its tree, to a baked Shader Tool material"
                        : "Not baked: " + displayName(surface.material())
                        + " (Bake... it in the Shader Tool to see its colours on the map)");
            }
        }
        return new FoliageShape(surfaces, height, radius, triangles, problems);
    }

    private static FoliageShape empty(String problem) {
        return empty(List.of(problem));
    }

    private static FoliageShape empty(List<String> problems) {
        return new FoliageShape(List.of(), 0f, 0f, 0, problems.isEmpty() ? List.of("It made nothing") : problems);
    }

    private static String displayName(String material) {
        String name = Path.of(material).getFileName().toString();
        return name.endsWith(".nodes.json") ? name.substring(0, name.length() - ".nodes.json".length()) : name;
    }

    /**
     * The picture a saved material was baked to, as an asset path ({@code tex/bush/bush_albedo.png}), or null if it was
     * never baked or the picture isn't there.
     */
    @SuppressWarnings("unchecked")
    public static String bakedPicture(Path assetsRoot, String material) {
        if (material == null || material.isBlank() || !material.endsWith(".nodes.json")) {
            return null;
        }
        try {
            Map<String, Object> json = Json.parseObject(Files.readString(assetsRoot.resolve(material)));
            Map<String, Object> materials = Json.object(json, "materials");
            if (materials == null) {
                return null;
            }
            for (Object value : materials.values()) {
                if (value instanceof Map<?, ?> m && ((Map<String, Object>) m).get("bake") instanceof Map<?, ?> bake
                        && bake.get("prefix") instanceof String prefix && !prefix.isBlank()) {
                    String picture = prefix + "_albedo.png";
                    return Files.isRegularFile(assetsRoot.resolve(picture)) ? picture : null;
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Foliage's surfaces with their materials, on the GL thread: the baked picture each wears, or a plain colour. */
    public static List<Part> parts(FoliageShape shape, Assets assets) {
        List<Part> parts = new ArrayList<>();
        Path root = assets.roots().getFirst();
        for (Surface surface : shape.surfaces()) {
            TexturedMeshBuilder builder = new TexturedMeshBuilder();
            builder.addMesh(surface.positions(), surface.normals(), surface.uvs(), surface.indices(), new Matrix4f(),
                    new Vector3f(1f, 1f, 1f));
            String picture = bakedPicture(root, surface.material());
            FoliageMaterial material = null;
            if (picture != null) {
                try {
                    String normal = picture.substring(0, picture.length() - "_albedo.png".length()) + "_normal.png";
                    material = FoliageMaterial.opaque(assets.texture(picture),
                            Files.isRegularFile(root.resolve(normal)) ? assets.texture(normal) : null, false);
                } catch (RuntimeException e) {
                    material = null;
                }
            }
            if (material == null) {
                material = SplineLooks.plain(UNBAKED);
            }
            // Foliage is open: a leaf or a petal is seen from both sides.
            material = new FoliageMaterial(material.albedo(), material.normalMap(), material.normalMapMirrored(), 0.5f, true,
                    true, false);
            parts.add(new Part(builder.buildData(), material));
        }
        return parts;
    }

    // --- Foliage on maps, as prefabs ----------------------------------------------------------------------------

    /**
     * Brings a catalogue's preset prefabs in step with a library: one {@code preset:<name>} prefab for each foliage
     * preset, made again when its look changed, and taken away when the preset is. Runs each changed preset's tree here
     * and now, on the GL thread.
     *
     * @return whether any prefab was made, made again or taken away: placed objects should be placed again
     */
    public static boolean syncPrefabs(PrefabCatalog catalog, PresetLibrary library, Assets assets) {
        if (catalog == null || library == null) {
            return false;
        }
        Map<String, String> installed;
        synchronized (INSTALLED) {
            installed = INSTALLED.computeIfAbsent(catalog, c -> new LinkedHashMap<>());
        }
        boolean changed = false;
        Map<String, String> wanted = new LinkedHashMap<>();
        for (Preset preset : library.of(Preset.Kind.FOLIAGE)) {
            wanted.put(Preset.objectId(preset.name), preset.lookKey());
        }
        for (String id : List.copyOf(installed.keySet())) {
            if (!wanted.containsKey(id)) {
                catalog.remove(id);
                installed.remove(id);
                changed = true;
            }
        }
        for (Preset preset : library.of(Preset.Kind.FOLIAGE)) {
            String id = Preset.objectId(preset.name);
            String key = wanted.get(id);
            if (key.equals(installed.get(id))) {
                continue;
            }
            installed.put(id, key);
            changed = true;
            long started = System.nanoTime();
            FoliageShape shape = foliage(preset, assets.roots().getFirst());
            catalog.remove(id);
            if (!shape.ok()) {
                System.err.println("[Presets] " + preset.name + ": " + String.join("; ", shape.problems()));
                continue;
            }
            catalog.register(prefab(id, preset, shape, assets));
            System.out.printf(java.util.Locale.ROOT, "[Presets] Made %s (%,d triangles) in %d ms%n", preset.name,
                    shape.triangles(), (System.nanoTime() - started) / 1_000_000L);
        }
        return changed;
    }

    /** A foliage preset as a prefab: its textured look, a flat mesh of it for thumbnails, solid if the preset says. */
    private static PrefabCatalog.Prefab prefab(String id, Preset preset, FoliageShape shape, Assets assets) {
        List<PrefabLook.Piece> pieces = new ArrayList<>();
        for (Part part : parts(shape, assets)) {
            if (!part.mesh().isEmpty()) {
                TexturedMesh mesh = part.mesh().upload();
                pieces.add(new PrefabLook.Piece(mesh, part.material()));
            }
        }
        MeshBuilder3D flat = new MeshBuilder3D();
        for (Surface surface : shape.surfaces()) {
            float[] p = surface.positions();
            float[] n = surface.normals();
            int base = flat.vertexCount();
            for (int i = 0; i < p.length / 3; i++) {
                flat.vertex(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], n[i * 3], n[i * 3 + 1], n[i * 3 + 2], UNBAKED);
            }
            int[] indices = surface.indices();
            for (int k = 0; k + 2 < indices.length; k += 3) {
                flat.triangle(base + indices[k], base + indices[k + 1], base + indices[k + 2]);
            }
        }
        float footprint = preset.solid ? Math.max(0.2f, shape.radius() * 1.4f) : 0f;
        float tall = preset.solid ? Math.max(0.2f, shape.height()) : 0f;
        PrefabCatalog.Prefab made = new PrefabCatalog.Prefab(id, preset.name, PrefabCatalog.PRESETS, flat.build(),
                footprint, tall, footprint, shape.height(), shape.radius(), List.of(new PrefabLook(pieces, null)));
        return made;
    }

    /** Forgets what was installed in a catalogue, for one about to be freed. */
    public static void forget(PrefabCatalog catalog) {
        synchronized (INSTALLED) {
            INSTALLED.remove(catalog);
        }
    }
}
