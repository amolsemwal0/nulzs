package org.zeronura.engine.worldmap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.zeronura.engine.asset.AnimationClip;
import org.zeronura.engine.asset.AssetLoader;
import org.zeronura.engine.asset.NodeAnimChannel;
import org.zeronura.engine.asset.TexturedPart;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.MeshBuilder3D;
import org.zeronura.engine.render.FoliageMaterial;
import org.zeronura.engine.render.StaticMesh;
import org.zeronura.engine.render.Texture;
import org.zeronura.engine.render.TexturedMeshBuilder;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load_from_memory;

/**
 * Props made from model files that keep their textures. {@link ModelPrefabs} draws every material in its base colour,
 * which suits a model coloured by its materials; a model whose colour is in its pictures — wood grain, linen, brick,
 * their base colour left white — loads white that way. Here such a model is a {@link PrefabLook}: each textured
 * material a textured mesh, the rest in flat colours, as a building's looks are.
 *
 * <p>Reading a model ({@link #read}) is GL-free, so its size and parts can be measured away from the game. Building its
 * look ({@link #build}) uploads it. The pictures are shared: models made from one set of materials embed the same
 * pictures over and over, and each is uploaded once, however many models carry it. {@link #delete} frees them.
 *
 * <p>A part whose material is two-sided is built from both sides ({@link #sided}): props are drawn with their backs
 * culled, and such a model leans on being seen from both — a roof that is one sheet, a face wound the wrong way round.
 */
public final class TexturedModels {

    /**
     * A model file's parts, GL-free, and how far they reach: {minX, maxX, minY, maxY, minZ, maxZ} as authored; and the
     * nodes that swing on hinges, whose parts are built apart from the rest ({@link #read(Path, String)}).
     */
    public record Model(List<TexturedPart> parts, float[] bounds, List<Hinge> hinges) {

        public Model(List<TexturedPart> parts, float[] bounds) {
            this(parts, bounds, List.of());
        }

        /** How far the parts whose names pass {@code which} reach, as {@link #bounds}; null when no part does. */
        public float[] bounds(Predicate<String> which) {
            return TexturedModels.bounds(parts.stream().filter(part -> which.test(part.name())).toList());
        }
    }

    /**
     * A node a clip of the model turns about an upright axis through its origin — a door on its hinge — and how: GL-free.
     *
     * @param node   the node's name, which its parts carry ({@link TexturedPart#node})
     * @param origin where the hinge stands, as the model was made
     * @param times  seconds into the clip, from its first key
     * @param angles how far the node has turned about +y at each of {@code times}, in radians, from how it was made
     */
    public record Hinge(String node, Vector3f origin, float[] times, float[] angles) {
    }

    /**
     * A model built to draw: {@code look} to draw it with its textures, and {@code flat} the same shape in flat colours
     * (each picture by its average colour), for thumbnails and wherever one flat mesh is wanted.
     */
    public record Built(PrefabLook look, MeshBuilder3D flat) {
    }

    /** Each picture's material, by its encoded bytes: a buffer's equality is its content's. */
    private final Map<ByteBuffer, FoliageMaterial> materials = new HashMap<>();
    private final Map<ByteBuffer, Color> averages = new HashMap<>();

    /** Reads a model file's parts. GL-free. */
    public static Model read(Path file) {
        return read(file, null);
    }

    /**
     * Reads a model file's parts, and the nodes its clip {@code swingClip} turns about an upright axis — a building's
     * doors, in the clip that opens them — to be built apart, so they can swing. GL-free.
     *
     * @param swingClip the clip's name, or null for no hinges
     */
    public static Model read(Path file, String swingClip) {
        List<TexturedPart> parts = AssetLoader.loadTexturedParts(file.toString());
        float[] bounds = bounds(parts);
        if (bounds == null) {
            throw new IllegalArgumentException(file + " has no triangles");
        }
        return new Model(parts, bounds, swingClip == null ? List.of() : hinges(file, swingClip, parts));
    }

    /** The nodes a clip of the file turns, each with parts of its own; none when it has no such clip. GL-free. */
    static List<Hinge> hinges(Path file, String clipName, List<TexturedPart> parts) {
        AnimationClip clip = AssetLoader.loadAnimations(file.toString()).stream()
                .filter(candidate -> candidate.name.equals(clipName)).findFirst().orElse(null);
        if (clip == null) {
            return List.of();
        }
        Map<String, Matrix4f> nodes = AssetLoader.loadNodeTransforms(file.toString());
        double ticksPerSecond = clip.ticksPerSecond > 0.0 ? clip.ticksPerSecond : DEFAULT_TICKS_PER_SECOND;
        List<Hinge> hinges = new ArrayList<>();
        for (NodeAnimChannel channel : clip.channels()) {
            Matrix4f made = nodes.get(channel.targetNodeName);
            List<NodeAnimChannel.RotationKey> keys = channel.rotationKeys;
            if (made == null || keys.isEmpty() || parts.stream().noneMatch(part -> part.node().equals(channel.targetNodeName))) {
                continue;
            }
            float shut = yaw(made.transformDirection(new Vector3f(1f, 0f, 0f)));
            float[] times = new float[keys.size()];
            float[] angles = new float[keys.size()];
            double first = keys.getFirst().timeTicks();
            for (int k = 0; k < keys.size(); k++) {
                times[k] = (float) ((keys.get(k).timeTicks() - first) / ticksPerSecond);
                float angle = yaw(keys.get(k).value().transform(new Vector3f(1f, 0f, 0f))) - shut;
                // On round from the key before, never the long way back past a half turn.
                float before = k == 0 ? 0f : angles[k - 1];
                angles[k] = before + (float) Math.IEEEremainder(angle - before, 2.0 * Math.PI);
            }
            hinges.add(new Hinge(channel.targetNodeName, made.getTranslation(new Vector3f()), times, angles));
        }
        return hinges;
    }

    /** The clock a file whose clips do not say theirs runs at, in ticks a second: Assimp's own guess. */
    private static final double DEFAULT_TICKS_PER_SECOND = 25.0;

    /** How far about +y a direction that started along +x has been turned, in radians. */
    private static float yaw(Vector3f turned) {
        return (float) Math.atan2(-turned.z, turned.x);
    }

    /** How far some parts reach, {minX, maxX, minY, maxY, minZ, maxZ}; null for no vertices at all. */
    static float[] bounds(List<TexturedPart> parts) {
        float[] bounds = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        for (TexturedPart part : parts) {
            float[] positions = part.positions();
            for (int v = 0; v + 2 < positions.length; v += 3) {
                for (int axis = 0; axis < 3; axis++) {
                    bounds[axis * 2] = Math.min(bounds[axis * 2], positions[v + axis]);
                    bounds[axis * 2 + 1] = Math.max(bounds[axis * 2 + 1], positions[v + axis]);
                }
            }
        }
        return bounds[0] > bounds[1] ? null : bounds;
    }

    /**
     * Builds a model's look under a transform — the scale and offset that stand it where its prefab wants it. Parts
     * sharing a picture are drawn as one mesh. The parts of each {@linkplain Model#hinges hinge} are built apart, as the
     * look's swings; the flat shape has them shut. The transform must not turn or mirror the model, or the swings would
     * turn the wrong way. Must run on the GL thread.
     */
    public Built build(Model model, Matrix4f transform) {
        MeshBuilder3D flat = new MeshBuilder3D();
        Set<String> hung = new HashSet<>();
        model.hinges().forEach(hinge -> hung.add(hinge.node()));
        Surfaces body = surfaces(model.parts().stream().filter(part -> !hung.contains(part.node())).toList(), transform, flat);
        List<PrefabLook.Swing> swings = new ArrayList<>();
        for (Hinge hinge : model.hinges()) {
            Surfaces leaf = surfaces(model.parts().stream().filter(part -> part.node().equals(hinge.node())).toList(),
                    transform, flat);
            swings.add(new PrefabLook.Swing(leaf.pieces(), leaf.plain(), transform.transformPosition(new Vector3f(hinge.origin())),
                    hinge.times(), hinge.angles()));
        }
        return new Built(new PrefabLook(body.pieces(), body.plain(), swings), flat);
    }

    /** Some parts built to draw: a textured mesh per picture, and the rest in flat colours, or null for none. */
    private record Surfaces(List<PrefabLook.Piece> pieces, StaticMesh plain) {
    }

    /** Builds some parts to draw, adding them to {@code flat} as well, each picture by its average colour. */
    private Surfaces surfaces(List<TexturedPart> parts, Matrix4f transform, MeshBuilder3D flat) {
        Map<ByteBuffer, TexturedMeshBuilder> textured = new LinkedHashMap<>();
        MeshBuilder3D plain = new MeshBuilder3D();
        for (TexturedPart modelPart : parts) {
            TexturedPart part = sided(modelPart);
            Color factor = new Color(part.baseColor().x, part.baseColor().y, part.baseColor().z);
            ByteBuffer picture = part.encodedTexture();
            if (picture == null) {
                addFlat(plain, part, transform, factor);
                addFlat(flat, part, transform, factor);
                continue;
            }
            if (material(picture) == null) {
                // A picture that would not decode: the part in its base colour, as an untextured model draws it.
                addFlat(plain, part, transform, factor);
                addFlat(flat, part, transform, factor);
                continue;
            }
            textured.computeIfAbsent(picture, p -> new TexturedMeshBuilder())
                    .addMesh(part.positions(), part.normals(), part.uvs(), part.indices(), transform,
                            new Vector3f(factor.r(), factor.g(), factor.b()));
            Color average = averages.get(picture);
            addFlat(flat, part, transform, new Color(average.r() * factor.r(), average.g() * factor.g(), average.b() * factor.b()));
        }
        List<PrefabLook.Piece> pieces = new ArrayList<>();
        for (Map.Entry<ByteBuffer, TexturedMeshBuilder> entry : textured.entrySet()) {
            pieces.add(new PrefabLook.Piece(entry.getValue().build(), materials.get(entry.getKey())));
        }
        return new Surfaces(pieces, plain.triangleCount() == 0 ? null : plain.buildData().upload());
    }

    /**
     * A two-sided part as it is built to be drawn with backs culled: each triangle wound to face the way its normals
     * do, and again the other way round, facing back, its normals turned. So it shows from either side, lit on the
     * side that is seen. A one-sided part is returned as it is. GL-free.
     */
    static TexturedPart sided(TexturedPart part) {
        if (!part.twoSided()) {
            return part;
        }
        float[] positions = part.positions();
        float[] normals = part.normals();
        float[] uvs = part.uvs();
        int[] indices = part.indices();
        int vertices = part.vertexCount();
        float[] bothPositions = Arrays.copyOf(positions, positions.length * 2);
        System.arraycopy(positions, 0, bothPositions, positions.length, positions.length);
        float[] bothNormals = Arrays.copyOf(normals, normals.length * 2);
        for (int i = 0; i < normals.length; i++) {
            bothNormals[normals.length + i] = -normals[i];
        }
        float[] bothUvs = Arrays.copyOf(uvs, uvs.length * 2);
        System.arraycopy(uvs, 0, bothUvs, uvs.length, uvs.length);
        int triangles = indices.length / 3 * 3;
        int[] both = new int[triangles * 2];
        for (int k = 0; k < triangles; k += 3) {
            int a = indices[k];
            int b = indices[k + 1];
            int c = indices[k + 2];
            if (windsAgainstNormals(positions, normals, a, b, c)) {
                int swap = b;
                b = c;
                c = swap;
            }
            both[k] = a;
            both[k + 1] = b;
            both[k + 2] = c;
            both[triangles + k] = vertices + a;
            both[triangles + k + 1] = vertices + c;
            both[triangles + k + 2] = vertices + b;
        }
        return new TexturedPart(part.name(), bothPositions, bothNormals, bothUvs, both, part.baseColor(),
                part.encodedTexture(), true, part.node());
    }

    /** Whether a triangle's corners go round the other way from the way its normals face. */
    private static boolean windsAgainstNormals(float[] p, float[] n, int a, int b, int c) {
        float ux = p[b * 3] - p[a * 3];
        float uy = p[b * 3 + 1] - p[a * 3 + 1];
        float uz = p[b * 3 + 2] - p[a * 3 + 2];
        float vx = p[c * 3] - p[a * 3];
        float vy = p[c * 3 + 1] - p[a * 3 + 1];
        float vz = p[c * 3 + 2] - p[a * 3 + 2];
        float nx = n[a * 3] + n[b * 3] + n[c * 3];
        float ny = n[a * 3 + 1] + n[b * 3 + 1] + n[c * 3 + 1];
        float nz = n[a * 3 + 2] + n[b * 3 + 2] + n[c * 3 + 2];
        return (uy * vz - uz * vy) * nx + (uz * vx - ux * vz) * ny + (ux * vy - uy * vx) * nz < 0f;
    }

    /** A picture's material, uploading it the first time it is asked for; null when it will not decode. */
    private FoliageMaterial material(ByteBuffer picture) {
        if (materials.containsKey(picture)) {
            return materials.get(picture);
        }
        FoliageMaterial material = null;
        try {
            Color average = averageOf(picture);
            if (average != null) {
                material = FoliageMaterial.opaque(Texture.fromMemory(picture.duplicate()), null, false);
                averages.put(picture, average);
            }
        } catch (RuntimeException e) {
            System.err.println("[TexturedModels] A picture would not load: " + e.getMessage());
        }
        materials.put(picture, material);
        return material;
    }

    /** The average colour of an encoded picture, or null when it will not decode. GL-free. */
    static Color averageOf(ByteBuffer picture) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = stbi_load_from_memory(picture.duplicate(), w, h, channels, 4);
            if (pixels == null) {
                return null;
            }
            try {
                long r = 0;
                long g = 0;
                long b = 0;
                int count = w.get(0) * h.get(0);
                for (int i = 0; i < count; i++) {
                    r += pixels.get(i * 4) & 0xFF;
                    g += pixels.get(i * 4 + 1) & 0xFF;
                    b += pixels.get(i * 4 + 2) & 0xFF;
                }
                float scale = 1f / (255f * Math.max(1, count));
                return new Color(r * scale, g * scale, b * scale);
            } finally {
                stbi_image_free(pixels);
            }
        }
    }

    private static void addFlat(MeshBuilder3D mesh, TexturedPart part, Matrix4f transform, Color color) {
        float[] positions = part.positions();
        float[] normals = part.normals();
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        int base = mesh.vertexCount();
        for (int v = 0; v + 2 < positions.length; v += 3) {
            transform.transformPosition(position.set(positions[v], positions[v + 1], positions[v + 2]));
            transform.transformDirection(normal.set(normals[v], normals[v + 1], normals[v + 2]));
            if (normal.lengthSquared() > 1e-12f) {
                normal.normalize();
            }
            mesh.vertex(position.x, position.y, position.z, normal.x, normal.y, normal.z, color);
        }
        int[] indices = part.indices();
        for (int k = 0; k + 2 < indices.length; k += 3) {
            mesh.triangle(base + indices[k], base + indices[k + 1], base + indices[k + 2]);
        }
    }

    /** Frees every picture uploaded; the meshes are their prefabs', freed with the catalogue. */
    public void delete() {
        for (FoliageMaterial material : materials.values()) {
            if (material != null) {
                material.albedo().delete();
            }
        }
        materials.clear();
        averages.clear();
    }
}
