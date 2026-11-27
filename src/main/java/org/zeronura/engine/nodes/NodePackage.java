package org.zeronura.engine.nodes;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.nodes.geometry.Attributes;
import org.zeronura.engine.nodes.geometry.Column;
import org.zeronura.engine.nodes.geometry.CurveGeometry;
import org.zeronura.engine.nodes.geometry.DataType;
import org.zeronura.engine.nodes.geometry.Domain;
import org.zeronura.engine.nodes.geometry.EvalEnv;
import org.zeronura.engine.nodes.geometry.Field;
import org.zeronura.engine.nodes.geometry.GeometryEvaluator;
import org.zeronura.engine.nodes.geometry.GeometryOps;
import org.zeronura.engine.nodes.geometry.GeometrySet;
import org.zeronura.engine.nodes.geometry.ImagePixels;
import org.zeronura.engine.nodes.geometry.InstanceGeometry;
import org.zeronura.engine.nodes.geometry.Inputs;
import org.zeronura.engine.nodes.geometry.MeshGeometry;
import org.zeronura.engine.nodes.geometry.NodeResolver;
import org.zeronura.engine.nodes.geometry.Transforms;
import org.zeronura.engine.nodes.geometry.UnsupportedNodeException;
import org.zeronura.engine.nodes.tex.ColorMath;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A node package written by {@code tools/blender_nodes/node_export.py}: node trees, materials, objects, meshes and
 * images exported from a .blend file, ready for the engine's node system.
 *
 * <pre>{@code
 * NodePackage kit = NodePackage.load(assets.resolve("blender/hedge/hedge.nodes.json"));
 * GeometrySet hedge = kit.evaluate("GN living hedge", Map.of("Cluster Count", 12));   // re-run its geometry nodes
 * NodeMaterial leaves = materials.get(kit, "Botany emerald canopy");                  // its shader nodes as GLSL
 * }</pre>
 *
 * <p>Everything stays in Blender's space (Z up); {@link org.zeronura.engine.nodes.render.BlenderSpace} converts
 * when meshes are built for drawing. When an object's geometry tree uses a node the engine does not implement,
 * {@link #evaluate} falls back to the bake Blender wrote alongside it and records why in {@link #problems()}.
 */
public final class NodePackage implements NodeResolver {

    public record MaterialDef(String name, String tree, float[] diffuseColor, float metallic, float roughness,
                              boolean backfaceCulling, String renderMethod, String blendMethod, float alphaThreshold) {

        /** Whether the material blends (Blender's "Blended" render method) rather than writing depth. */
        public boolean blended() {
            return "BLENDED".equals(renderMethod) || "BLEND".equals(blendMethod);
        }
    }

    public record ImageDef(String name, Path file, String colorspace, String alphaMode) {
        public boolean linear() {
            return colorspace != null && (colorspace.contains("Non-Color") || colorspace.contains("Linear")
                    || colorspace.equals("Raw") || colorspace.contains("Data"));
        }
    }

    public record Modifier(String name, String type, boolean enabled, String tree, Map<String, Object> inputs,
                           Map<String, String> attributeInputs) {
    }

    public record BakePart(String mesh, float[] matrix) {
    }

    public record ObjectDef(String name, String type, float[] matrix, String parent, boolean visible,
                            List<String> materials, List<Modifier> modifiers, String mesh, Map<String, Object> curves,
                            String instanceCollection, List<BakePart> bake) {
    }

    private final Path folder;
    private final String source;
    private final String blenderVersion;
    private final float fps;
    private final List<String> roots;
    private final Map<String, NodeTree> trees = new LinkedHashMap<>();
    private final Map<String, MaterialDef> materials = new LinkedHashMap<>();
    private final Map<String, ImageDef> images = new LinkedHashMap<>();
    private final Map<String, ObjectDef> objects = new LinkedHashMap<>();
    private final Map<String, CollectionContents> collections = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> meshJson = new LinkedHashMap<>();
    private final Map<String, MeshGeometry> meshes = new ConcurrentHashMap<>();
    private final Map<String, ImagePixels> pixels = new ConcurrentHashMap<>();
    private final Map<String, GeometrySet> evaluated = new ConcurrentHashMap<>();
    private final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    private final GeometryEvaluator evaluator = new GeometryEvaluator(this);

    private NodePackage(Path file, Map<String, Object> json) {
        this.folder = file.toAbsolutePath().getParent();
        if (!"zeronura-node-package".equals(json.get("format"))) {
            throw new IllegalArgumentException(file + " is not a node package (export it with tools/blender_nodes/node_export.py)");
        }
        this.source = Json.string(json, "source", file.getFileName().toString());
        this.blenderVersion = Json.string(json, "blender", "");
        this.fps = json.get("fps") instanceof Number n ? n.floatValue() : 24f;
        List<String> rootList = new ArrayList<>();
        for (Object root : Json.list(json, "roots")) {
            rootList.add(String.valueOf(root));
        }
        this.roots = List.copyOf(rootList);
        Map<String, Object> treeJson = Json.object(json, "trees");
        if (treeJson != null) {
            for (Map.Entry<String, Object> entry : treeJson.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> map) {
                    trees.put(entry.getKey(), NodeTree.fromJson(entry.getKey(), cast(map)));
                }
            }
        }
        Map<String, Object> materialJson = Json.object(json, "materials");
        if (materialJson != null) {
            for (Map.Entry<String, Object> entry : materialJson.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> raw) {
                    Map<String, Object> m = cast(raw);
                    materials.put(entry.getKey(), new MaterialDef(entry.getKey(), Json.string(m, "tree", null),
                            NodeValues.toVector(NodeValues.fromJson(m.get("diffuse_color")), 4),
                            number(m, "metallic", 0f), number(m, "roughness", 0.5f), Json.bool(m, "backface_culling", false),
                            Json.string(m, "render_method", "DITHERED"), Json.string(m, "blend_method", "OPAQUE"),
                            number(m, "alpha_threshold", 0.5f)));
                }
            }
        }
        Map<String, Object> imageJson = Json.object(json, "images");
        if (imageJson != null) {
            for (Map.Entry<String, Object> entry : imageJson.entrySet()) {
                Map<String, Object> m = cast((Map<?, ?>) entry.getValue());
                String fileName = Json.string(m, "file", null);
                images.put(entry.getKey(), new ImageDef(entry.getKey(), fileName == null ? null : folder.resolve(fileName),
                        Json.string(m, "colorspace", "sRGB"), Json.string(m, "alpha_mode", "STRAIGHT")));
            }
        }
        Map<String, Object> meshes = Json.object(json, "meshes");
        if (meshes != null) {
            for (Map.Entry<String, Object> entry : meshes.entrySet()) {
                meshJson.put(entry.getKey(), cast((Map<?, ?>) entry.getValue()));
            }
        }
        Map<String, Object> collectionJson = Json.object(json, "collections");
        if (collectionJson != null) {
            for (Map.Entry<String, Object> entry : collectionJson.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> raw) {
                    Map<String, Object> m = cast(raw);
                    collections.put(entry.getKey(), new CollectionContents(strings(Json.list(m, "objects")),
                            strings(Json.list(m, "children")), NodeValues.toVector(NodeValues.fromJson(m.get("offset")), 3)));
                }
            }
        }
        Map<String, Object> objectJson = Json.object(json, "objects");
        if (objectJson != null) {
            for (Map.Entry<String, Object> entry : objectJson.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> raw) {
                    objects.put(entry.getKey(), parseObject(entry.getKey(), cast(raw)));
                }
            }
        }
        for (Object warning : Json.list(json, "warnings")) {
            problems.add("exporter: " + warning);
        }
    }

    /**
     * A package from JSON already read, as if loaded from {@code file}: images are found beside it. For a tool that
     * builds packages itself, such as the editor's Shader Tool.
     */
    public static NodePackage fromJson(Path file, Map<String, Object> json) {
        return new NodePackage(file, json);
    }

    public static NodePackage load(Path file) {
        try {
            return new NodePackage(file, Json.parseObject(Files.readString(file)));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read node package " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static float number(Map<String, Object> m, String key, float fallback) {
        return m.get(key) instanceof Number n ? n.floatValue() : fallback;
    }

    private static List<String> strings(List<Object> list) {
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(o == null ? null : String.valueOf(o));
        }
        return out;
    }

    private ObjectDef parseObject(String name, Map<String, Object> m) {
        List<Modifier> modifiers = new ArrayList<>();
        for (Map<String, Object> mod : Json.objects(m, "modifiers")) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            Map<String, String> attributeInputs = new LinkedHashMap<>();
            Map<String, Object> raw = Json.object(mod, "inputs");
            if (raw != null) {
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    Map<String, Object> slot = cast((Map<?, ?>) entry.getValue());
                    if (slot.get("attribute") instanceof String attribute && !attribute.isEmpty()) {
                        attributeInputs.put(entry.getKey(), attribute);
                    } else if (slot.containsKey("value")) {
                        inputs.put(entry.getKey(), NodeValues.fromJson(slot.get("value")));
                    }
                }
            }
            modifiers.add(new Modifier(Json.string(mod, "name", ""), Json.string(mod, "type", ""),
                    Json.bool(mod, "enabled", true), Json.string(mod, "tree", null), inputs, attributeInputs));
        }
        List<BakePart> bake = new ArrayList<>();
        for (Map<String, Object> part : Json.objects(m, "bake")) {
            bake.add(new BakePart(Json.string(part, "mesh"), NodeValues.toVector(NodeValues.fromJson(part.get("matrix")), 16)));
        }
        return new ObjectDef(name, Json.string(m, "type", "MESH"),
                NodeValues.toVector(NodeValues.fromJson(m.get("matrix")), 16), Json.string(m, "parent", null),
                Json.bool(m, "visible", true), strings(Json.list(m, "materials")), modifiers, Json.string(m, "mesh", null),
                Json.object(m, "curves"), Json.string(m, "instance_collection", null), bake);
    }

    // --- What the package holds ------------------------------------------------------------------------------

    public Path folder() {
        return folder;
    }

    public String source() {
        return source;
    }

    public String blenderVersion() {
        return blenderVersion;
    }

    public float fps() {
        return fps;
    }

    /** The objects the export was asked for (their children and referenced objects are in {@link #objects()}). */
    public List<String> roots() {
        return roots;
    }

    public Map<String, ObjectDef> objects() {
        return Collections.unmodifiableMap(objects);
    }

    public ObjectDef object(String name) {
        return objects.get(name);
    }

    public Map<String, NodeTree> trees() {
        return Collections.unmodifiableMap(trees);
    }

    public Map<String, MaterialDef> materials() {
        return Collections.unmodifiableMap(materials);
    }

    public MaterialDef material(String name) {
        return materials.get(name);
    }

    public Map<String, ImageDef> images() {
        return Collections.unmodifiableMap(images);
    }

    /** Things that did not come across: unsupported nodes (with the bake used instead), missing images. */
    public List<String> problems() {
        synchronized (problems) {
            return List.copyOf(problems);
        }
    }

    public GeometryEvaluator evaluator() {
        return evaluator;
    }

    // --- NodeResolver ------------------------------------------------------------------------------------------

    @Override
    public NodeTree tree(String key) {
        NodeTree tree = trees.get(key);
        if (tree == null) {
            for (NodeTree candidate : trees.values()) {
                if (candidate.name.equals(key)) {
                    return candidate;
                }
            }
        }
        return tree;
    }

    @Override
    public GeometrySet objectGeometry(String name, EvalEnv env) {
        return evaluated.computeIfAbsent(name, n -> {
            if (!objects.containsKey(n)) {
                return GeometrySet.EMPTY;
            }
            return evaluate(n, Map.of(), env);
        });
    }

    @Override
    public float[] objectMatrix(String name) {
        ObjectDef object = objects.get(name);
        return object == null ? Transforms.identity() : object.matrix().clone();
    }

    @Override
    public CollectionContents collection(String name) {
        return collections.get(name);
    }

    @Override
    public ImagePixels image(String name) {
        ImageDef image = images.get(name);
        if (image == null || image.file() == null) {
            return null;
        }
        return pixels.computeIfAbsent(name, n -> loadPixels(image));
    }

    private ImagePixels loadPixels(ImageDef image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            ByteBuffer data = STBImage.stbi_load(image.file().toString(), w, h, channels, 4);
            if (data == null) {
                problems.add("image " + image.name() + ": " + STBImage.stbi_failure_reason());
                return null;
            }
            int width = w.get(0), height = h.get(0);
            float[] rgba = new float[width * height * 4];
            boolean linear = image.linear();
            for (int y = 0; y < height; y++) {
                int src = (height - 1 - y) * width * 4;
                for (int x = 0; x < width * 4; x++) {
                    float v = (data.get(src + x) & 0xFF) / 255f;
                    rgba[y * width * 4 + x] = (x % 4 == 3 || linear) ? v : ColorMath.srgbToLinear(v);
                }
            }
            STBImage.stbi_image_free(data);
            return new ImagePixels(width, height, rgba, channels.get(0) == 4);
        }
    }

    // --- Geometry ----------------------------------------------------------------------------------------------

    /** What a stored mesh renders with by default: its UV map and colour attribute, and its texture space. */
    public record MeshInfo(String uvMap, String colorAttribute, float[] texspaceLocation, float[] texspaceSize) {
    }

    public MeshInfo meshInfo(String key) {
        Map<String, Object> json = meshJson.get(key);
        if (json == null) {
            return new MeshInfo(null, null, null, null);
        }
        return new MeshInfo(Json.string(json, "render_uv", Json.string(json, "active_uv", null)),
                Json.string(json, "render_color", Json.string(json, "active_color", null)),
                json.get("texspace_location") == null ? null : NodeValues.toVector(NodeValues.fromJson(json.get("texspace_location")), 3),
                json.get("texspace_size") == null ? null : NodeValues.toVector(NodeValues.fromJson(json.get("texspace_size")), 3));
    }

    /** A mesh stored in the package (an object's base mesh or a bake part), in Blender's space. */
    public MeshGeometry mesh(String key) {
        return meshes.computeIfAbsent(key, k -> {
            Map<String, Object> json = meshJson.get(k);
            if (json == null) {
                return MeshGeometry.empty();
            }
            return decodeMesh(json);
        });
    }

    private static ByteBuffer bytes(Object base64) {
        byte[] raw = base64 == null ? new byte[0] : Base64.getDecoder().decode(base64.toString());
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static float[] floats(Object base64) {
        FloatBuffer buffer = bytes(base64).asFloatBuffer();
        float[] out = new float[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private static int[] ints(Object base64) {
        IntBuffer buffer = bytes(base64).asIntBuffer();
        int[] out = new int[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    static MeshGeometry decodeMesh(Map<String, Object> json) {
        float[] positions = floats(json.get("position"));
        int[] edgeVerts = ints(json.get("edge_verts"));
        int[] offsets = ints(json.get("face_offsets"));
        int[] cornerVerts = ints(json.get("corner_verts"));
        int[] cornerEdges = ints(json.get("corner_edges"));
        if (offsets.length == 0) {
            offsets = new int[]{0};
        }
        MeshGeometry.Topology topology = new MeshGeometry.Topology(positions.length / 3, edgeVerts, offsets, cornerVerts,
                cornerEdges);
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, positions));
        for (Map<String, Object> a : Json.objects(json, "attributes")) {
            DataType type = DataType.ofAttribute(Json.string(a, "type", "FLOAT"));
            Domain domain = Domain.parse(Json.string(a, "domain", "POINT"), Domain.POINT);
            Column column;
            if (type.integer) {
                column = Column.ofInts(type, ints(a.get("data")));
            } else if ("INT32_2D".equals(a.get("type"))) {
                int[] values = ints(a.get("data"));
                float[] f = new float[values.length];
                for (int k = 0; k < f.length; k++) {
                    f[k] = values[k];
                }
                column = Column.ofFloats(DataType.FLOAT2, f);
            } else {
                column = Column.ofFloats(type, floats(a.get("data")));
            }
            attributes.put(Json.string(a, "name"), domain, column);
        }
        if (json.get("corner_normals") != null) {
            attributes.put(".corner_normals", Domain.CORNER, Column.ofFloats(DataType.VECTOR, floats(json.get("corner_normals"))));
        }
        if (Json.bool(json, "custom_normals", false)) {
            attributes.put(".custom_normals", Domain.POINT, Column.constant(DataType.BOOL, true, positions.length / 3));
        }
        String uv = Json.string(json, "render_uv", Json.string(json, "active_uv", null));
        if (uv != null && attributes.has(uv) && !uv.equals("UVMap")) {
            attributes.put(".render_uv", Domain.CORNER, attributes.column(uv));
        }
        List<String> materials = strings(Json.list(json, "materials"));
        return new MeshGeometry(topology, attributes, materials);
    }

    private CurveGeometry decodeCurves(Map<String, Object> json) {
        List<Map<String, Object>> splines = Json.objects(json, "splines");
        int count = splines.size();
        int[] sizes = new int[count];
        int total = 0;
        for (int c = 0; c < count; c++) {
            sizes[c] = Json.list(splines.get(c), "points").size();
            total += sizes[c];
        }
        float[] positions = new float[total * 3];
        float[] left = new float[total * 3];
        float[] right = new float[total * 3];
        float[] radius = new float[total];
        float[] tilt = new float[total];
        int[] types = new int[count];
        int[] cyclic = new int[count];
        int[] resolution = new int[count];
        boolean anyBezier = false;
        int at = 0;
        for (int c = 0; c < count; c++) {
            Map<String, Object> s = splines.get(c);
            String type = Json.string(s, "type", "POLY");
            types[c] = switch (type) {
                case "BEZIER" -> CurveGeometry.BEZIER;
                case "NURBS" -> CurveGeometry.NURBS;
                default -> CurveGeometry.POLY;
            };
            anyBezier |= types[c] == CurveGeometry.BEZIER;
            cyclic[c] = Json.bool(s, "cyclic", false) ? 1 : 0;
            resolution[c] = Json.integer(s, "resolution", 12);
            List<Object> pts = Json.list(s, "points");
            List<Object> hl = Json.list(s, "handles_left");
            List<Object> hr = Json.list(s, "handles_right");
            List<Object> radii = Json.list(s, "radius");
            List<Object> tilts = Json.list(s, "tilt");
            for (int k = 0; k < pts.size(); k++) {
                float[] p = NodeValues.toVector(NodeValues.fromJson(pts.get(k)), 3);
                System.arraycopy(p, 0, positions, 3 * at, 3);
                float[] l = k < hl.size() ? NodeValues.toVector(NodeValues.fromJson(hl.get(k)), 3) : p;
                float[] r = k < hr.size() ? NodeValues.toVector(NodeValues.fromJson(hr.get(k)), 3) : p;
                System.arraycopy(l, 0, left, 3 * at, 3);
                System.arraycopy(r, 0, right, 3 * at, 3);
                radius[at] = k < radii.size() ? ((Number) radii.get(k)).floatValue() : 1f;
                tilt[at] = k < tilts.size() ? ((Number) tilts.get(k)).floatValue() : 0f;
                at++;
            }
        }
        int[] offsets = new int[count + 1];
        for (int c = 0; c < count; c++) {
            offsets[c + 1] = offsets[c] + sizes[c];
        }
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, positions));
        attributes.put("radius", Domain.POINT, Column.ofFloats(DataType.FLOAT, radius));
        attributes.put("tilt", Domain.POINT, Column.ofFloats(DataType.FLOAT, tilt));
        if (anyBezier) {
            attributes.put("handle_left", Domain.POINT, Column.ofFloats(DataType.VECTOR, left));
            attributes.put("handle_right", Domain.POINT, Column.ofFloats(DataType.VECTOR, right));
        }
        attributes.put("curve_type", Domain.CURVE, Column.ofInts(DataType.INT, types));
        attributes.put("cyclic", Domain.CURVE, Column.ofInts(DataType.BOOL, cyclic));
        attributes.put("resolution", Domain.CURVE, Column.ofInts(DataType.INT, resolution));
        return new CurveGeometry(offsets, attributes, strings(Json.list(json, "materials")));
    }

    /** An object's geometry before its geometry-nodes modifiers: its mesh or curves, in its local space. */
    public GeometrySet baseGeometry(String name) {
        ObjectDef object = objects.get(name);
        if (object == null) {
            return GeometrySet.EMPTY;
        }
        if (object.mesh() != null) {
            MeshGeometry mesh = mesh(object.mesh());
            if (isProcedural(name) && !mesh.attributes().has(".custom_normals")) {
                // Blender's normals describe the mesh before the trees run; the engine recomputes them after,
                // from the same sharp edges and faces, so a tree that moves vertices shades as Blender shows it.
                Attributes attributes = mesh.attributes().copy();
                attributes.remove(".corner_normals");
                mesh = mesh.withAttributes(attributes);
            }
            return GeometrySet.of(withObjectMaterials(mesh, object)).withName(name);
        }
        if (object.curves() != null) {
            return GeometrySet.of(decodeCurves(object.curves())).withName(name);
        }
        return GeometrySet.EMPTY;
    }

    /** Material slots on an object override its mesh's own, as Blender renders them. */
    private static MeshGeometry withObjectMaterials(MeshGeometry mesh, ObjectDef object) {
        if (object.materials().isEmpty()) {
            return mesh;
        }
        List<String> slots = new ArrayList<>(mesh.materials);
        for (int k = 0; k < object.materials().size(); k++) {
            String material = object.materials().get(k);
            if (material == null) {
                continue;
            }
            while (slots.size() <= k) {
                slots.add("");
            }
            slots.set(k, material);
        }
        return mesh.withMaterials(slots);
    }

    /** Blender's own evaluation of an object, as exported: its meshes placed as instances. */
    public GeometrySet bake(String name) {
        ObjectDef object = objects.get(name);
        if (object == null || object.bake().isEmpty()) {
            return GeometrySet.EMPTY;
        }
        List<GeometrySet> references = new ArrayList<>();
        Map<String, Integer> handles = new LinkedHashMap<>();
        int[] handleOf = new int[object.bake().size()];
        float[] transforms = new float[object.bake().size() * 16];
        for (int k = 0; k < object.bake().size(); k++) {
            BakePart part = object.bake().get(k);
            Integer handle = handles.get(part.mesh());
            if (handle == null) {
                handle = references.size();
                handles.put(part.mesh(), handle);
                references.add(GeometrySet.of(mesh(part.mesh())));
            }
            handleOf[k] = handle;
            System.arraycopy(part.matrix(), 0, transforms, 16 * k, 16);
        }
        return GeometrySet.of(InstanceGeometry.of(references, handleOf, transforms)).withName(name);
    }

    /** The geometry-nodes trees an object's modifiers run, in order, with the inputs set on each. */
    public List<Modifier> nodeModifiers(String name) {
        ObjectDef object = objects.get(name);
        List<Modifier> out = new ArrayList<>();
        if (object != null) {
            for (Modifier modifier : object.modifiers()) {
                if ("NODES".equals(modifier.type()) && modifier.enabled() && modifier.tree() != null) {
                    out.add(modifier);
                }
            }
        }
        return out;
    }

    /** Whether an object's geometry comes from node trees (rather than being a plain mesh). */
    public boolean isProcedural(String name) {
        return !nodeModifiers(name).isEmpty();
    }

    /**
     * An object's geometry with its geometry-nodes modifiers run by the engine. {@code overrides} replace modifier
     * inputs, keyed by the input's name as Blender shows it on the modifier (or its identifier). Falls back to
     * Blender's bake when a tree uses a node the engine does not implement.
     */
    public GeometrySet evaluate(String name, Map<String, Object> overrides, EvalEnv env) {
        try {
            return evaluateStrict(name, overrides, env);
        } catch (UnsupportedNodeException | IllegalStateException unsupported) {
            problems.add(name + ": " + unsupported.getMessage() + " (using Blender's bake)");
            return bake(name);
        }
    }

    /** As {@link #evaluate}, but a tree the engine cannot run throws instead of falling back to the bake. */
    public GeometrySet evaluateStrict(String name, Map<String, Object> overrides, EvalEnv env) {
        ObjectDef object = objects.get(name);
        if (object == null) {
            throw new IllegalArgumentException("no object \"" + name + "\" in " + source);
        }
        GeometrySet geometry = baseGeometry(name);
        List<Modifier> modifiers = nodeModifiers(name);
        EvalEnv self = (env == null ? EvalEnv.DEFAULT : env).withSelf(name, object.matrix());
        for (int index = 0; index < modifiers.size(); index++) {
            Modifier modifier = modifiers.get(index);
            NodeTree tree = tree(modifier.tree());
            if (tree == null) {
                throw new IllegalStateException("tree \"" + modifier.tree() + "\" is missing from the package");
            }
            Map<String, Object> inputs = modifierInputs(tree, modifier, index == modifiers.size() - 1 ? overrides : Map.of());
            boolean geometrySet = false;
            for (NodeTree.InterfaceSocket socket : tree.inputs()) {
                if (socket.type() == SocketType.GEOMETRY && !geometrySet) {
                    inputs.put(socket.id(), geometry);
                    geometrySet = true;
                }
            }
            Map<String, Object> outputs = evaluator.run(tree, inputs, self);
            geometry = GeometryEvaluator.geometryOutput(tree, outputs).withName(name);
        }
        return geometry;
    }

    public GeometrySet evaluate(String name, Map<String, Object> overrides) {
        return evaluate(name, overrides, EvalEnv.DEFAULT);
    }

    public GeometrySet evaluate(String name) {
        return evaluate(name, Map.of(), EvalEnv.DEFAULT);
    }

    private Map<String, Object> modifierInputs(NodeTree tree, Modifier modifier, Map<String, Object> overrides) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (NodeTree.InterfaceSocket socket : tree.inputs()) {
            String id = socket.id();
            Object value = null;
            if (overrides.containsKey(socket.name())) {
                value = overrides.get(socket.name());
            } else if (overrides.containsKey(id)) {
                value = overrides.get(id);
            } else if (modifier.attributeInputs().containsKey(id)) {
                DataType type = DataType.of(socket.type());
                value = Inputs.named(modifier.attributeInputs().get(id), type == null ? DataType.FLOAT : type);
            } else if (modifier.inputs().containsKey(id)) {
                value = modifier.inputs().get(id);
            }
            if (value == null) {
                continue;
            }
            if (socket.type().isFieldType() && !(value instanceof Field)) {
                DataType type = DataType.of(socket.type());
                value = Field.constant(type, value instanceof Integer i ? (Object) i.floatValue() : value);
            }
            inputs.put(id, value);
        }
        return inputs;
    }

    /** Evaluates a package's geometry tree directly, without an object: a procedural generator called from code. */
    public GeometrySet generate(String treeName, Map<String, Object> inputs, EvalEnv env) {
        NodeTree tree = tree(treeName);
        if (tree == null) {
            throw new IllegalArgumentException("no tree \"" + treeName + "\" in " + source);
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            NodeTree.InterfaceSocket socket = tree.input(entry.getKey());
            Object value = entry.getValue();
            if (socket != null && socket.type().isFieldType() && !(value instanceof Field)) {
                value = Field.constant(DataType.of(socket.type()), value);
            }
            converted.put(entry.getKey(), value);
        }
        return GeometryEvaluator.geometryOutput(tree, evaluator.run(tree, converted, env == null ? EvalEnv.DEFAULT : env));
    }

    /** Every geometry and shader node type the package uses that the engine does not implement. */
    public Map<String, List<String>> unsupportedNodes() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (NodeTree tree : trees.values()) {
            for (Node node : tree.nodes()) {
                boolean supported = tree.kind == NodeTree.Kind.GEOMETRY ? GeometryEvaluator.supports(node.type)
                        : org.zeronura.engine.nodes.shader.ShaderCompiler.supports(node.type);
                if (!supported && !node.muted) {
                    out.computeIfAbsent(node.type, k -> new ArrayList<>()).add(tree.name);
                }
            }
        }
        return out;
    }

    /** Realized geometry for tests and tools: every instance turned into real geometry. */
    public static GeometrySet realize(GeometrySet geometry) {
        return GeometryOps.realize(geometry);
    }
}
