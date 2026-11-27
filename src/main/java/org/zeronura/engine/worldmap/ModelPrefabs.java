package org.zeronura.engine.worldmap;

import org.zeronura.engine.asset.AssetLoader;
import org.zeronura.engine.asset.StaticGeometry;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.MeshBuilder3D;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Props made from model files: every {@code .glb}, {@code .gltf}, {@code .obj} or {@code .fbx} under a folder becomes a
 * prefab, in a category named after the folder it sits in. Dropping {@code prefabs/Trees/big_oak.glb} into a game's
 * assets puts "Big oak" under Trees in the map editor's asset drawer.
 *
 * <p>Models are drawn untextured, each material in its base colour — the same flat style as the built-in props — and
 * stand on their lowest point. One {@value #SOLID_HEIGHT} m tall or more gets a collision box over the middle of its
 * footprint, so trees and houses block the player while grass and flowers do not. Maps store the id, which comes from
 * the file's path, so a file should keep its name once maps use it.
 *
 * <p><b>Rigged models are skipped.</b> A file with bones is a character, not scenery: it is posed by an
 * {@code Animator} through {@code ModelRenderer}, where a prop is a single static mesh that the world draws
 * instanced and never poses. Importing one as a prop would place it frozen in its bind pose, so a folder holding
 * both — which is the ordinary way a game keeps its models — yields only the scenery in it.
 *
 * <p>A model that carries no usable colour of its own can be given one at registration, along with a scale, through
 * {@link #register(PrefabCatalog, String, String, String, Path, Color, float)}: a mesh exported with no material at
 * all loads as white, and one exported at the wrong unit scale loads too small to see.
 */
public final class ModelPrefabs {

    public static final List<String> EXTENSIONS = List.of(".glb", ".gltf", ".obj", ".fbx");

    private static final float SOLID_HEIGHT = 1.5f;
    /** The share of a model's footprint its collision box covers: a tree's trunk, not its whole crown. */
    private static final float COLLIDER_SHARE = 0.6f;

    private ModelPrefabs() {
    }

    /**
     * Registers every model file under a folder, skipping ids already registered and files that fail to load.
     *
     * @return how many prefabs were added; 0 if the folder does not exist
     */
    public static int registerFolder(PrefabCatalog catalog, Path folder) {
        return registerFolder(catalog, folder, file -> false);
    }

    /**
     * Registers every model file under a folder but those {@code skip} passes — files a game imports its own way, such
     * as models that keep their textures ({@link TexturedModels}).
     *
     * @return how many prefabs were added; 0 if the folder does not exist
     */
    public static int registerFolder(PrefabCatalog catalog, Path folder, java.util.function.Predicate<Path> skip) {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile).filter(ModelPrefabs::isModel).filter(skip.negate()).sorted().toList();
        } catch (IOException e) {
            System.err.println("[ModelPrefabs] Could not read " + folder + ": " + e.getMessage());
            return 0;
        }
        int added = 0;
        for (Path file : files) {
            String id = idFor(folder, file);
            if (catalog.get(id) != null) {
                continue;
            }
            try {
                if (!AssetLoader.isPlainMesh(file.toString())) {
                    continue;   // A character, posed through ModelRenderer — see the note on this class.
                }
                if (register(catalog, id, nameFor(file), categoryFor(folder, file), file, null, 1f)) {
                    added++;
                }
            } catch (RuntimeException e) {
                System.err.println("[ModelPrefabs] Skipping " + file + ": " + e.getMessage());
            }
        }
        return added;
    }

    /**
     * Registers one model file as a prefab under a chosen name and category.
     *
     * @param tint  one colour for the whole model, or null to keep each material's own colour. For a model exported
     *              with no material, whose parts all load white.
     * @param scale multiplies the model, for one exported at the wrong unit scale. Collision and the extents maps
     *              measure with it.
     * @return whether it was added; false if the file holds no triangles
     */
    public static boolean register(PrefabCatalog catalog, String id, String name, String category, Path file,
                                   Color tint, float scale) {
        List<AssetLoader.ColoredPart> parts = AssetLoader.loadColoredStaticParts(file.toString());
        MeshBuilder3D shape = new MeshBuilder3D();
        float halfX = 0f;
        float halfY = 0f;
        float halfZ = 0f;
        for (AssetLoader.ColoredPart part : parts) {
            StaticGeometry geometry = part.geometry();
            halfX = Math.max(halfX, geometry.halfExtentX() * scale);
            halfY = Math.max(halfY, geometry.halfExtentY() * scale);
            halfZ = Math.max(halfZ, geometry.halfExtentZ() * scale);
            Color color = tint != null ? tint : new Color(part.color().x, part.color().y, part.color().z);
            float[] positions = geometry.positions();
            float[] normals = geometry.normals();
            int base = shape.vertexCount();
            for (int v = 0; v + 2 < positions.length; v += 3) {
                // The loader centres the model on its bounds; lift it to stand on its lowest point.
                shape.vertex(positions[v] * scale, (positions[v + 1] + geometry.halfExtentY()) * scale, positions[v + 2] * scale,
                        normals[v], normals[v + 1], normals[v + 2], color);
            }
            int[] indices = geometry.indices();
            for (int k = 0; k + 2 < indices.length; k += 3) {
                shape.triangle(base + indices[k], base + indices[k + 1], base + indices[k + 2]);
            }
        }
        if (shape.triangleCount() == 0) {
            System.err.println("[ModelPrefabs] " + file + " has no triangles");
            return false;
        }
        float height = 2f * halfY;
        boolean solid = height >= SOLID_HEIGHT;
        catalog.register(id, name, category, shape,
                solid ? 2f * halfX * COLLIDER_SHARE : 0f, solid ? height : 0f, solid ? 2f * halfZ * COLLIDER_SHARE : 0f);
        return true;
    }

    public static boolean isModel(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /** {@code model:trees/big_oak} for {@code <folder>/Trees/Big Oak.glb}. */
    public static String idFor(Path folder, Path file) {
        String relative = folder.relativize(file).toString().replace('\\', '/');
        return "model:" + stripExtension(relative).toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /** The name of the folder a file sits in, or "Imported" for one directly in the folder. */
    public static String categoryFor(Path folder, Path file) {
        Path parent = folder.relativize(file).getParent();
        return parent == null ? "Imported" : pretty(parent.getFileName().toString());
    }

    /** {@code big_oak-tree.glb} reads "Big oak tree". */
    public static String nameFor(Path file) {
        return pretty(stripExtension(file.getFileName().toString()));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String pretty(String text) {
        String words = text.replaceAll("[_-]+", " ").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return words.isEmpty() ? text : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
