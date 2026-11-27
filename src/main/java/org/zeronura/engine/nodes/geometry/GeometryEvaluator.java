package org.zeronura.engine.nodes.geometry;

import org.zeronura.engine.nodes.Link;
import org.zeronura.engine.nodes.Node;
import org.zeronura.engine.nodes.NodeTree;
import org.zeronura.engine.nodes.NodeValues;
import org.zeronura.engine.nodes.Ref;
import org.zeronura.engine.nodes.Socket;
import org.zeronura.engine.nodes.SocketType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runs geometry node trees on the CPU, as Blender's geometry nodes would: demand-driven from the Group Output, a
 * node at a time, fields kept lazy until a node evaluates them on a geometry.
 *
 * <p>Repeat zones run their interior once per iteration; simulation zones show their first frame (their input
 * state passes through, as on Blender's start frame); group nodes run their tree with the group node's inputs.
 *
 * <p>A tree that uses a node the engine does not implement throws {@link UnsupportedNodeException};
 * {@link org.zeronura.engine.nodes.NodePackage} then falls back to Blender's own bake of the object.
 */
public final class GeometryEvaluator {

    /** Deep trees recurse deeply; evaluation runs on a thread with a stack big enough for any real tree. */
    private static final long STACK_BYTES = 256L << 20;

    final NodeResolver resolver;
    private final Map<String, NodeImpl> impls;

    public GeometryEvaluator(NodeResolver resolver) {
        this.resolver = resolver == null ? NodeResolver.NONE : resolver;
        this.impls = GeoNodes.registry();
    }

    /** Whether the engine implements a node type (group, zone and interface nodes included). */
    public static boolean supports(String type) {
        return GeoNodes.registry().containsKey(type) || BUILT_IN.contains(type);
    }

    /** Every node type the engine runs, sorted: what the editor's Geometry Nodes Tool may offer. */
    public static java.util.SortedSet<String> supportedTypes() {
        java.util.SortedSet<String> types = new java.util.TreeSet<>(GeoNodes.registry().keySet());
        types.addAll(BUILT_IN);
        return types;
    }

    /** Writes {@link #supportedTypes}, one a line, to the file named (or prints them): for make_geometry_catalog.py. */
    public static void main(String[] args) throws java.io.IOException {
        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Path.of(args[0]), supportedTypes());
        } else {
            supportedTypes().forEach(System.out::println);
        }
    }

    private static final java.util.Set<String> BUILT_IN = java.util.Set.of(
            "NodeGroupInput", "NodeGroupOutput", "NodeReroute", "GeometryNodeGroup", "NodeFrame",
            "GeometryNodeRepeatInput", "GeometryNodeRepeatOutput", "GeometryNodeSimulationInput",
            "GeometryNodeSimulationOutput", "GeometryNodeBake", "NodeImplicitConversion");

    /**
     * Runs a tree. {@code inputs} are keyed by interface identifier or name; a value may be a {@link Field}, a
     * {@link GeometrySet}, a number, boolean, float[] (vector or colour), String or {@link Ref}. Returns the
     * tree's outputs keyed by interface identifier.
     */
    public Map<String, Object> run(NodeTree tree, Map<String, Object> inputs, EvalEnv env) {
        AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(null, () -> {
            EvalEnv.CURRENT.set(env);
            try {
                result.set(runHere(tree, inputs, env));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                EvalEnv.CURRENT.remove();
            }
        }, "geometry-nodes", STACK_BYTES);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while evaluating " + tree, interrupted);
        }
        Throwable error = failure.get();
        if (error instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return result.get();
    }

    /** The first geometry output of a run. */
    public static GeometrySet geometryOutput(NodeTree tree, Map<String, Object> outputs) {
        for (NodeTree.InterfaceSocket socket : tree.outputs()) {
            if (socket.type() == SocketType.GEOMETRY && outputs.get(socket.id()) instanceof GeometrySet g) {
                return g;
            }
        }
        return GeometrySet.EMPTY;
    }

    Map<String, Object> runHere(NodeTree tree, Map<String, Object> inputs, EvalEnv env) {
        Map<String, Supplier<Object>> suppliers = new HashMap<>();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            NodeTree.InterfaceSocket socket = tree.input(entry.getKey());
            if (socket != null) {
                Object value = entry.getValue();
                suppliers.put(socket.id(), () -> value);
            }
        }
        return new TreeRun(tree, suppliers, env, 0).outputs();
    }

    // --- Scopes ----------------------------------------------------------------------------------------------

    /**
     * Where node results are cached. The root scope lives for a whole tree run; each iteration of a zone gets a
     * child scope, so nodes inside the zone are evaluated afresh while nodes outside it are not.
     */
    static final class Scope {
        final Scope parent;
        final Node zoneInput;
        final Map<String, Object> zoneValues;
        final Map<Node, Map<String, Object>> results = new IdentityHashMap<>();

        Scope(Scope parent, Node zoneInput, Map<String, Object> zoneValues) {
            this.parent = parent;
            this.zoneInput = zoneInput;
            this.zoneValues = zoneValues;
        }
    }

    // --- One tree --------------------------------------------------------------------------------------------

    final class TreeRun {
        final NodeTree tree;
        final Map<String, Supplier<Object>> groupInputs;
        final Map<String, Object> groupInputCache = new HashMap<>();
        final EvalEnv env;
        final int depth;
        final Scope root = new Scope(null, null, Map.of());
        private final Map<Node, Map<Node, Boolean>> dependsCache = new IdentityHashMap<>();

        TreeRun(NodeTree tree, Map<String, Supplier<Object>> groupInputs, EvalEnv env, int depth) {
            if (depth > 64) {
                throw new IllegalStateException("node groups nest more than 64 deep (a group calling itself?)");
            }
            this.tree = tree;
            this.groupInputs = groupInputs;
            this.env = env;
            this.depth = depth;
        }

        GeometryEvaluator evaluator() {
            return GeometryEvaluator.this;
        }

        Map<String, Object> outputs() {
            Map<String, Object> out = new LinkedHashMap<>();
            Node output = tree.outputNode("NodeGroupOutput");
            for (NodeTree.InterfaceSocket socket : tree.outputs()) {
                Object value = null;
                if (output != null) {
                    Socket input = output.input(socket.id());
                    if (input != null) {
                        value = inputValue(input, root);
                    }
                }
                if (value == null) {
                    value = defaultValue(socket.type(), socket.defaultValue());
                }
                out.put(socket.id(), value);
            }
            return out;
        }

        /** The value arriving at an input socket, converted to its type. */
        Object inputValue(Socket input, Scope scope) {
            if (input.links().size() > 1) {
                List<Object> values = inputValues(input, scope);
                return values.isEmpty() ? defaultValue(input.type, input.value) : values.get(0);
            }
            Link link = input.link();
            if (link == null) {
                return unlinkedValue(input);
            }
            Socket source = NodeTree.source(link);
            if (source == null) {
                return unlinkedValue(input);
            }
            if (!source.output) {
                return convert(inputValueOrDefault(source, scope), typeOf(source), typeOf(input));
            }
            return convert(outputValue(source, scope), typeOf(source), typeOf(input));
        }

        private Object inputValueOrDefault(Socket mutedInput, Scope scope) {
            return mutedInput.isLinked() ? inputValue(mutedInput, scope) : unlinkedValue(mutedInput);
        }

        List<Object> inputValues(Socket input, Scope scope) {
            List<Object> values = new ArrayList<>();
            for (Link link : input.links()) {
                Socket source = NodeTree.source(link);
                if (source == null) {
                    continue;
                }
                Object value = source.output ? outputValue(source, scope) : inputValueOrDefault(source, scope);
                values.add(convert(value, typeOf(source), typeOf(input)));
            }
            return values;
        }

        private Object unlinkedValue(Socket input) {
            Field implicit = ImplicitInputs.forSocket(input);
            if (implicit != null) {
                return implicit;
            }
            return defaultValue(input.type, input.value);
        }

        Object outputValue(Socket output, Scope scope) {
            Node node = output.node;
            Scope owner = scopeFor(node, scope);
            if (owner.zoneInput == node) {
                Object value = owner.zoneValues.get(output.id);
                return value != null ? value : defaultValue(output.type, null);
            }
            Map<String, Object> results = owner.results.get(node);
            if (results == null) {
                results = execute(node, owner);
                owner.results.put(node, results);
            }
            Object value = results.get(output.id);
            return value != null ? value : defaultValue(output.type, null);
        }

        private Scope scopeFor(Node node, Scope scope) {
            for (Scope s = scope; s != null; s = s.parent) {
                if (s.zoneInput != null && (s.zoneInput == node || dependsOn(node, s.zoneInput))) {
                    return s;
                }
            }
            return root;
        }

        /** Whether a node's result changes with a zone input's values (it sits inside that zone). */
        private boolean dependsOn(Node node, Node zoneInput) {
            if (node == zoneInput) {
                return true;
            }
            if (zoneInput.name.equals(pairedInputName(node))) {
                return false;
            }
            Map<Node, Boolean> cache = dependsCache.computeIfAbsent(node, n -> new IdentityHashMap<>());
            Boolean known = cache.get(zoneInput);
            if (known != null) {
                return known;
            }
            cache.put(zoneInput, false);
            boolean result = false;
            outer:
            for (Socket input : node.inputs()) {
                for (Link link : input.links()) {
                    Socket source = NodeTree.source(link);
                    if (source != null && dependsOn(source.node, zoneInput)) {
                        result = true;
                        break outer;
                    }
                }
            }
            cache.put(zoneInput, result);
            return result;
        }

        /** For a zone output node, the name of its input node. */
        private String pairedInputName(Node node) {
            if (!node.type.endsWith("Output") || node.type.equals("NodeGroupOutput")) {
                return null;
            }
            for (Node candidate : tree.nodes()) {
                if (node.name.equals(candidate.pair)) {
                    return candidate.name;
                }
            }
            return null;
        }

        private Node pairedInput(Node output) {
            for (Node candidate : tree.nodes()) {
                if (output.name.equals(candidate.pair)) {
                    return candidate;
                }
            }
            return null;
        }

        private Map<String, Object> execute(Node node, Scope scope) {
            switch (node.type) {
                case "NodeGroupInput" -> {
                    return groupInputNode(node);
                }
                case "GeometryNodeGroup" -> {
                    return groupNode(node, scope);
                }
                case "GeometryNodeRepeatOutput" -> {
                    return repeatZone(node, scope);
                }
                case "GeometryNodeSimulationOutput", "GeometryNodeBake" -> {
                    return passThrough(node, scope, node.type.equals("GeometryNodeSimulationOutput")
                            ? pairedInput(node) : null);
                }
                case "GeometryNodeSimulationInput" -> {
                    Map<String, Object> out = new HashMap<>();
                    for (Socket socket : node.outputs()) {
                        out.put(socket.id, socket.id.equals("Delta Time") ? Field.constant(DataType.FLOAT, 0f)
                                : defaultValue(socket.type, null));
                    }
                    Node output = node.pair != null ? tree.node(node.pair) : null;
                    if (output != null) {
                        Map<String, Object> state = passThrough(output, scope, node);
                        for (Map.Entry<String, Object> e : state.entrySet()) {
                            if (node.output(e.getKey()) != null) {
                                out.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                    return out;
                }
                default -> {
                }
            }
            NodeImpl impl = impls.get(node.type);
            if (impl == null) {
                throw new UnsupportedNodeException(node);
            }
            NodeCall call = new NodeCall(node, this, scope);
            impl.execute(call);
            call.fillDefaults();
            return call.outputs;
        }

        private Map<String, Object> groupInputNode(Node node) {
            Map<String, Object> out = new HashMap<>();
            for (Socket socket : node.outputs()) {
                Object value = groupInputCache.get(socket.id);
                if (value == null) {
                    Supplier<Object> supplier = groupInputs.get(socket.id);
                    NodeTree.InterfaceSocket iface = tree.input(socket.id);
                    if (supplier != null) {
                        value = supplier.get();
                    }
                    if (value == null && iface != null) {
                        value = interfaceDefault(iface);
                    }
                    if (value == null) {
                        value = defaultValue(socket.type, socket.value);
                    }
                    value = convert(value, valueType(value, socket.type), socket.type);
                    groupInputCache.put(socket.id, value);
                }
                out.put(socket.id, value);
            }
            return out;
        }

        private Map<String, Object> groupNode(Node node, Scope scope) {
            NodeTree group = node.groupTree == null ? null : resolver.tree(node.groupTree);
            if (group == null) {
                throw new UnsupportedNodeException(node, "its group tree \"" + node.groupTree + "\" is missing");
            }
            Map<String, Supplier<Object>> suppliers = new HashMap<>();
            for (Socket input : node.inputs()) {
                Map<String, Object> memo = new HashMap<>(1);
                suppliers.put(input.id, () -> memo.computeIfAbsent("v", k -> {
                    if (!input.isLinked() && ImplicitInputs.forSocket(input) == null) {
                        NodeTree.InterfaceSocket iface = group.input(input.id);
                        if (iface != null && !"VALUE".equals(iface.defaultInput())) {
                            Object implicit = interfaceDefault(iface);
                            if (implicit != null) {
                                return implicit;
                            }
                        }
                    }
                    return inputValue(input, scope);
                }));
            }
            return new TreeRun(group, suppliers, env, depth + 1).outputs();
        }

        private Map<String, Object> repeatZone(Node output, Scope scope) {
            Node input = pairedInput(output);
            if (input == null) {
                throw new UnsupportedNodeException(output, "repeat zone without its input node");
            }
            Socket iterationsSocket = input.input("Iterations");
            int iterations = iterationsSocket == null ? 0 : asField(inputValue(iterationsSocket, scope)).singleInt();
            Map<String, Object> state = new HashMap<>();
            for (Socket socket : input.inputs()) {
                if (!socket.id.equals("Iterations") && socket.type != SocketType.CUSTOM) {
                    state.put(socket.id, inputValue(socket, scope));
                }
            }
            for (int iteration = 0; iteration < iterations; iteration++) {
                Map<String, Object> zoneValues = new HashMap<>(state);
                zoneValues.put("Iteration", Field.constant(DataType.INT, iteration));
                Scope inner = new Scope(scope, input, zoneValues);
                Map<String, Object> next = new HashMap<>();
                for (Socket socket : output.inputs()) {
                    if (socket.type != SocketType.CUSTOM && !socket.id.equals("Skip")) {
                        next.put(socket.id, inputValue(socket, inner));
                    }
                }
                state = next;
            }
            Map<String, Object> out = new HashMap<>();
            for (Socket socket : output.outputs()) {
                Object value = state.get(socket.id);
                out.put(socket.id, value != null ? value : defaultValue(socket.type, null));
            }
            return out;
        }

        /** A zone or bake that passes its state through unchanged: each output takes the matching input. */
        private Map<String, Object> passThrough(Node node, Scope scope, Node stateFrom) {
            Node from = stateFrom != null ? stateFrom : node;
            Map<String, Object> out = new HashMap<>();
            for (Socket socket : node.outputs()) {
                Socket input = from.input(socket.id);
                out.put(socket.id, input != null ? inputValue(input, scope) : defaultValue(socket.type, null));
            }
            return out;
        }

        private Object interfaceDefault(NodeTree.InterfaceSocket iface) {
            Field implicit = ImplicitInputs.forInterface(iface.defaultInput());
            if (implicit != null) {
                return implicit;
            }
            return defaultValue(iface.type(), iface.defaultValue());
        }
    }

    private static SocketType valueType(Object value, SocketType fallback) {
        if (value instanceof Field f) {
            return switch (f.type) {
                case FLOAT -> SocketType.VALUE;
                case INT -> SocketType.INT;
                case BOOL -> SocketType.BOOLEAN;
                case VECTOR, FLOAT2 -> SocketType.VECTOR;
                case COLOR -> SocketType.RGBA;
                case ROTATION -> SocketType.ROTATION;
                case MATRIX -> SocketType.MATRIX;
            };
        }
        return fallback;
    }

    static Field asField(Object value) {
        if (value instanceof Field f) {
            return f;
        }
        return Field.constant(DataType.FLOAT, value);
    }

    /** A value carried from a socket of one type into a socket of another. */
    /**
     * The sockets whose type follows their node's data type, as Blender retypes them when the type is changed: a Store
     * Named Attribute set to Vector takes a vector, a Sample Index set to Color gives a colour. A file (and the node
     * catalog the editor adds from) keeps such a socket under one id with the type it first had, so its value is
     * converted by this type instead; otherwise a vector stored as a 2D Vector would arrive as its average.
     */
    private static final java.util.Set<String> FOLLOWS_DATA_TYPE = java.util.Set.of(
            "GeometryNodeStoreNamedAttribute:Value",
            "GeometryNodeSampleIndex:Value",
            "GeometryNodeSampleNearestSurface:Value",
            "GeometryNodeFieldAtIndex:Value",
            "GeometryNodeFieldOnDomain:Value",
            "GeometryNodeBlurAttribute:Value",
            "GeometryNodeAccumulateField:Value",
            "GeometryNodeRaycast:Attribute",
            "FunctionNodeRandomValue:Min",
            "FunctionNodeRandomValue:Max",
            "FunctionNodeRandomValue:Value");

    /** A socket's type as the evaluator treats it: see {@link #FOLLOWS_DATA_TYPE}. */
    static SocketType typeOf(Socket socket) {
        if (socket.node == null || !FOLLOWS_DATA_TYPE.contains(socket.node.type + ":" + socket.id)) {
            return socket.type;
        }
        String dataType = socket.node.prop("data_type", null);
        if (dataType == null) {
            return socket.type;
        }
        return switch (dataType) {
            case "FLOAT" -> SocketType.VALUE;
            case "INT", "INT8" -> SocketType.INT;
            case "BOOLEAN" -> socket.id.equals("Value") ? SocketType.BOOLEAN : socket.type;
            case "FLOAT_VECTOR", "FLOAT2", "FLOAT4" -> SocketType.VECTOR;
            case "FLOAT_COLOR", "BYTE_COLOR" -> SocketType.RGBA;
            case "QUATERNION" -> SocketType.ROTATION;
            case "FLOAT4X4" -> SocketType.MATRIX;
            default -> socket.type;
        };
    }

    static Object convert(Object value, SocketType from, SocketType to) {
        if (to.isFieldType()) {
            DataType target = DataType.of(to);
            if (value instanceof Field f) {
                return f.as(target);
            }
            if (value instanceof GeometrySet || value == null) {
                return defaultValue(to, null);
            }
            if (from == SocketType.ROTATION && value instanceof float[] euler && target != DataType.ROTATION) {
                return Field.constant(DataType.ROTATION, euler).as(target);
            }
            return Field.constant(target, value);
        }
        if (to == SocketType.GEOMETRY) {
            return value instanceof GeometrySet ? value : GeometrySet.EMPTY;
        }
        if (to == SocketType.STRING || to == SocketType.MENU) {
            if (value instanceof Field f) {
                Object single = f.single().firstValue();
                return single == null ? "" : single.toString();
            }
            return value == null ? "" : NodeValues.toText(value, "");
        }
        return value;
    }

    /** A socket's value when nothing sets it. */
    static Object defaultValue(SocketType type, Object stored) {
        if (type.isFieldType()) {
            DataType data = DataType.of(type);
            return stored == null ? Field.constant(data, data == DataType.ROTATION || data == DataType.MATRIX
                    ? null : 0f) : Field.constant(data, stored);
        }
        return switch (type) {
            case GEOMETRY -> GeometrySet.EMPTY;
            case STRING, MENU -> stored == null ? "" : NodeValues.toText(stored, "");
            default -> stored;
        };
    }
}
