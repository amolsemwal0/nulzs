package org.zeronura.nulzs.nodes.render;

import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.nodes.NodePackage;
import org.zeronura.nulzs.nodes.NodeTree;
import org.zeronura.nulzs.nodes.Ref;
import org.zeronura.nulzs.nodes.SocketType;
import org.zeronura.nulzs.nodes.geometry.CurveGeometry;
import org.zeronura.nulzs.nodes.geometry.GeometryOps;
import org.zeronura.nulzs.nodes.geometry.GeometrySet;
import org.zeronura.nulzs.nodes.geometry.MeshGeometry;
import org.zeronura.nulzs.nodes.geometry.PointGeometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the geometry node tree in a node package — one made in the editor's Geometry Nodes Tool, or the first geometry
 * tree of one exported from Blender — as a generator: its interface's inputs in, the geometry its Group Output gives
 * out. Pure CPU work, so it can run off the GL thread; {@link BlenderModel#build} then uploads the result.
 *
 * <pre>{@code
 * GeometryTrees.Result rocks = GeometryTrees.run(file, Map.of("Count", 40, "Seed", 7));
 * BlenderModel model = BlenderModel.build(new AssetMaterials(assets), rocks.geometry(), null, null, null);
 * }</pre>
 *
 * A tree's Set Material may name a saved Shader Tool material by its asset path ({@code materials/Stone.nodes.json});
 * {@link org.zeronura.nulzs.graphics3d.AssetMaterials} resolves names so.
 */
public final class GeometryTrees {

    /**
     * What a run made, and what it was: the geometry, the tree it came from, how much of each kind there is, its bounds
     * in the engine's axes (Y up), how long it took, and anything that went wrong (then the geometry is empty).
     */
    public record Result(GeometrySet geometry, NodeTree tree, int vertices, int faces, int points, int curves,
                         int instances, float[] boundsMin, float[] boundsMax, long micros, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private GeometryTrees() {
    }

    /** Reads a node package file and runs its geometry tree. */
    public static Result run(Path file, Map<String, Object> inputs) {
        try {
            return run(file, Json.parseObject(Files.readString(file)), inputs);
        } catch (IOException | RuntimeException e) {
            return failed(null, "Could not read " + file.getFileName() + ": " + e.getMessage(), 0L);
        }
    }

    /**
     * Runs the geometry tree of a node package held as JSON (saved at, or as if at, {@code file}). {@code inputs} are
     * by interface name or identifier; any not given take the tree's defaults.
     */
    public static Result run(Path file, Map<String, Object> json, Map<String, Object> inputs) {
        long started = System.nanoTime();
        NodeTree tree = null;
        try {
            NodePackage pkg = NodePackage.fromJson(file, json);
            String key = treeKey(json);
            if (key == null) {
                return failed(null, "No geometry node tree in " + file.getFileName(), 0L);
            }
            tree = pkg.tree(key);
            if (tree == null) {
                return failed(null, "No tree called " + key + " in " + file.getFileName(), 0L);
            }
            Map<String, Object> given = new LinkedHashMap<>();
            for (NodeTree.InterfaceSocket socket : tree.inputs()) {
                Object raw = inputs == null ? null : inputs.containsKey(socket.id()) ? inputs.get(socket.id())
                        : inputs.get(socket.name());
                Object value = raw == null ? null : value(socket.type(), raw);
                if (value != null) {
                    given.put(socket.id(), value);
                }
            }
            GeometrySet geometry = pkg.generate(key, given, null);
            List<String> problems = new ArrayList<>(pkg.problems());
            for (Map.Entry<String, List<String>> missing : pkg.unsupportedNodes().entrySet()) {
                problems.add("The engine doesn't run " + missing.getKey() + " yet");
            }
            return measure(geometry, tree, System.nanoTime() - started, problems);
        } catch (RuntimeException | StackOverflowError e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return failed(tree, message, (System.nanoTime() - started) / 1000L);
        }
    }

    /** The key of the package's geometry tree: the one a Geometry Nodes Tool file names, or its first; null if none. */
    @SuppressWarnings("unchecked")
    public static String treeKey(Map<String, Object> json) {
        Map<String, Object> trees = Json.object(json, "trees");
        if (trees == null) {
            return null;
        }
        Map<String, Object> marker = Json.object(json, "geometry");
        String named = marker == null ? null : Json.string(marker, "tree", null);
        if (named != null && trees.containsKey(named)) {
            return named;
        }
        for (Map.Entry<String, Object> entry : trees.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> m && "GEOMETRY".equals(((Map<String, Object>) m).get("type"))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * A value for an interface input of a type, from what a scene or the editor keeps (numbers, booleans, lists of
     * numbers, text): what the evaluator takes. A material is named by its asset path.
     */
    public static Object value(SocketType type, Object raw) {
        return switch (type) {
            case VALUE -> raw instanceof Number n ? (Object) n.floatValue() : null;
            case INT -> raw instanceof Number n ? (Object) (int) Math.round(n.doubleValue()) : null;
            case BOOLEAN -> raw instanceof Boolean b ? b : null;
            case VECTOR, ROTATION -> floats(raw, 3);
            case RGBA -> floats(raw, 4);
            case STRING -> raw instanceof String s ? s : null;
            case MATERIAL -> raw instanceof String s && !s.isBlank() ? new Ref(s, "MATERIAL")
                    : raw instanceof Ref r ? r : null;
            default -> null;
        };
    }

    private static float[] floats(Object raw, int count) {
        if (raw instanceof float[] f) {
            return f;
        }
        if (raw instanceof List<?> list) {
            float[] out = new float[count];
            for (int i = 0; i < count; i++) {
                out[i] = i < list.size() && list.get(i) instanceof Number n ? n.floatValue() : (i == 3 ? 1f : 0f);
            }
            return out;
        }
        return null;
    }

    private static Result failed(NodeTree tree, String message, long micros) {
        return new Result(GeometrySet.EMPTY, tree, 0, 0, 0, 0, 0, new float[3], new float[3], micros, List.of(message));
    }

    private static Result measure(GeometrySet geometry, NodeTree tree, long nanos, List<String> problems) {
        int instances = geometry.instances == null ? 0 : geometry.instances.count();
        GeometrySet real = GeometryOps.realize(geometry);
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        MeshGeometry mesh = real.mesh;
        CurveGeometry curves = real.curves;
        PointGeometry points = real.points;
        if (mesh != null) {
            grow(mesh.positions(), min, max);
        }
        if (curves != null) {
            grow(curves.positions(), min, max);
        }
        if (points != null) {
            grow(points.positions(), min, max);
        }
        if (min[0] > max[0]) {
            min = new float[3];
            max = new float[3];
        }
        // Blender's axes (Z up) to the engine's (Y up): x, z, -y.
        float[] engineMin = {min[0], min[2], -max[1]};
        float[] engineMax = {max[0], max[2], -min[1]};
        return new Result(geometry, tree, mesh == null ? 0 : mesh.positions().length / 3, mesh == null ? 0 : mesh.faceCount(),
                points == null ? 0 : points.positions().length / 3, curves == null ? 0 : curves.curveCount(), instances,
                engineMin, engineMax, nanos / 1000L, List.copyOf(problems));
    }

    private static void grow(float[] positions, float[] min, float[] max) {
        for (int i = 0; i + 2 < positions.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                min[k] = Math.min(min[k], positions[i + k]);
                max[k] = Math.max(max[k], positions[i + k]);
            }
        }
    }
}
