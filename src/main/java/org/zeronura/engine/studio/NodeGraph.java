package org.zeronura.engine.studio;

import org.zeronura.engine.data.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A node tree being made in a node tool: its nodes, where they sit, and the links between them. It is kept as nodes
 * Blender would export, so it saves as a node package ({@code .nodes.json}) the engine reads exactly as it reads one
 * exported from Blender, and a package exported from Blender opens here to be changed.
 *
 * <ul>
 *   <li>A {@link Kind#SHADER} graph is a material: the Shader Tool's, saved as the package's one material.</li>
 *   <li>A {@link Kind#GEOMETRY} graph is a geometry node tree: the Geometry Nodes Tool's, saved as the package's one
 *   tree with its {@linkplain #interfaceItems interface} — the inputs it takes, which its Group Input nodes hand out,
 *   and the geometry its Group Output gives back. A socket may take many links ({@link Socket#multiInput}, as Join
 *   Geometry's does), and a zone's two nodes are {@linkplain Node#pair paired}.</li>
 * </ul>
 */
final class NodeGraph {

    enum Kind { SHADER, GEOMETRY }

    static final class Socket {
        final Node node;
        String id;
        String name;
        String type;
        final boolean output;
        final boolean hideValue;
        /** Takes any number of links, in order: Join Geometry's Geometry. */
        final boolean multiInput;
        Object value;
        boolean enabled;

        Socket(Node node, Map<String, Object> json, boolean output) {
            this.node = node;
            this.id = Json.string(json, "id", "");
            this.name = Json.string(json, "name", id);
            this.type = Json.string(json, "type", "VALUE");
            this.output = output;
            this.hideValue = Json.bool(json, "hide_value", false);
            this.multiInput = Json.bool(json, "multi_input", false);
            this.value = copy(json.get("value"));
            this.enabled = Json.bool(json, "enabled", true);
        }
    }

    static final class Node {
        String name;
        final String type;
        String label;
        float x;
        float y;
        final Map<String, Object> props;
        final List<Socket> inputs = new ArrayList<>();
        final List<Socket> outputs = new ArrayList<>();
        /** A Color Ramp's stops; a curve node's sampled curves; an Image Texture's picture, by asset path. */
        Map<String, Object> ramp;
        Map<String, Object> curves;
        String image = "";
        boolean imageIsData;
        /** A zone's input node: the name of its output node. */
        String pair;
        final NodeCatalog.Entry entry;
        /** What a node of a type the catalog doesn't know had, kept to be saved as it came. */
        final Map<String, Object> foreign;

        @SuppressWarnings("unchecked")
        Node(Map<String, Object> json, NodeCatalog.Entry entry) {
            this.entry = entry;
            this.name = Json.string(json, "name", entry != null ? entry.label() : "Node");
            this.type = Json.string(json, "type", entry != null ? entry.type() : "");
            this.label = Json.string(json, "label", "");
            Map<String, Object> rawProps = Json.object(json, "props");
            this.props = rawProps == null ? new LinkedHashMap<>() : (Map<String, Object>) copy(rawProps);
            for (Map<String, Object> s : Json.objects(json, "inputs")) {
                inputs.add(new Socket(this, s, false));
            }
            for (Map<String, Object> s : Json.objects(json, "outputs")) {
                outputs.add(new Socket(this, s, true));
            }
            ramp = json.get("ramp") instanceof Map<?, ?> r ? (Map<String, Object>) copy(r) : null;
            curves = json.get("curves") instanceof Map<?, ?> c ? (Map<String, Object>) copy(c) : null;
            pair = Json.string(json, "pair", null);
            if (json.get("location") instanceof List<?> at && at.size() >= 2) {
                x = ((Number) at.get(0)).floatValue();
                y = ((Number) at.get(1)).floatValue();
            }
            this.foreign = entry == null ? (Map<String, Object>) copy(json) : null;
        }

        Socket input(String id) {
            for (Socket socket : inputs) {
                if (socket.id.equals(id)) {
                    return socket;
                }
            }
            return null;
        }

        Socket output(String id) {
            for (Socket socket : outputs) {
                if (socket.id.equals(id)) {
                    return socket;
                }
            }
            return null;
        }

        String title() {
            if (label != null && !label.isEmpty()) {
                return label;
            }
            if (entry != null) {
                return entry.label();
            }
            return switch (type) {
                case "NodeGroupInput" -> "Group Input";
                case "NodeGroupOutput" -> "Group Output";
                case "NodeReroute" -> "Reroute";
                default -> type.replaceFirst("^(ShaderNode|GeometryNode|FunctionNode|Node)", "");
            };
        }

        /** The node the tree's result comes out of: a material's Material Output, a geometry tree's Group Output. */
        boolean isOutput() {
            return type.equals("ShaderNodeOutputMaterial") || type.equals("NodeGroupOutput");
        }

        boolean isGroupInput() {
            return type.equals("NodeGroupInput");
        }
    }

    record Link(Node from, String fromSocket, Node to, String toSocket) {
    }

    /**
     * One socket of a geometry tree's interface: an input it takes (its Group Input nodes' outputs, and the settings a
     * scene shows for it), or an output it gives (its Group Output's inputs). {@code type} is a socket type as Blender
     * names it ({@code GEOMETRY}, {@code VALUE}, {@code INT}, {@code VECTOR}...).
     */
    static final class InterfaceItem {
        final boolean input;
        String id;
        String name;
        String type;
        Object defaultValue;
        Float min;
        Float max;

        InterfaceItem(boolean input, String id, String name, String type, Object defaultValue, Float min, Float max) {
            this.input = input;
            this.id = id;
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }
    }

    /** The socket types an interface input can be, as offered in the Geometry Nodes Tool: Blender's names. */
    static final List<String> INTERFACE_TYPES = List.of("VALUE", "INT", "BOOLEAN", "VECTOR", "RGBA", "ROTATION",
            "STRING", "MATERIAL", "GEOMETRY");

    final Kind kind;
    final List<Node> nodes = new ArrayList<>();
    final List<Link> links = new ArrayList<>();
    /** A geometry tree's inputs and outputs, in order. Empty for a material. */
    final List<InterfaceItem> interfaceItems = new ArrayList<>();
    /** The material's name, or the geometry tree's. */
    String materialName = "Material";
    /**
     * Where Bake... last wrote the material's pictures, as a path in the project's assets without its {@code _albedo.png}
     * ending; empty until it is baked. Kept in the material's file, so the next bake goes to the same place.
     */
    String bakePrefix = "";
    /** How many pixels across a bake makes each picture. */
    int bakeSize = 1024;
    /** Goes up with every change, so the preview knows to compile (or evaluate) again. */
    int revision;

    NodeGraph() {
        this(Kind.SHADER);
    }

    NodeGraph(Kind kind) {
        this.kind = kind;
    }

    void changed() {
        revision++;
    }

    // --- Editing ----------------------------------------------------------------------------------------------

    /** A new node from the catalog at (x, y), named so it doesn't clash; a zone adds its output node too, paired. */
    @SuppressWarnings("unchecked")
    Node add(NodeCatalog.Entry entry, float x, float y) {
        Map<String, Object> json = (Map<String, Object>) copy(entry.template());
        json.put("name", uniqueName(entry.isZone() ? entry.label().replace(" Zone", "") + " Input" : entry.label()));
        Node node = new Node(json, entry.isZone() ? zoneHalf(entry, false) : entry);
        node.x = x;
        node.y = y;
        nodes.add(node);
        applySettings(node);
        if (entry.isZone()) {
            Map<String, Object> outJson = (Map<String, Object>) copy(entry.zoneOutput().template());
            outJson.put("name", uniqueName(entry.zoneOutput().label()));
            Node output = new Node(outJson, entry.zoneOutput());
            output.x = x + 360f;
            output.y = y;
            nodes.add(output);
            applySettings(output);
            node.pair = output.name;
            // As Blender adds a zone: the geometry runs through it, from the input node's first geometry to the output's.
            Socket from = firstOfType(node.outputs, "GEOMETRY");
            Socket to = firstOfType(output.inputs, "GEOMETRY");
            if (from != null && to != null) {
                connect(node, from.id, output, to.id);
            }
        }
        if (node.isGroupInput()) {
            syncInterface();
        }
        changed();
        return node;
    }

    private static Socket firstOfType(List<Socket> sockets, String type) {
        for (Socket socket : sockets) {
            if (socket.enabled && socket.type.equals(type)) {
                return socket;
            }
        }
        return null;
    }

    /** The catalog entry a zone's input node (or, with {@code output}, its output node) reads its settings from. */
    private static NodeCatalog.Entry zoneHalf(NodeCatalog.Entry zone, boolean output) {
        return output ? zone.zoneOutput() : new NodeCatalog.Entry(zone.id() + "#input", zone.type(),
                zone.label().replace(" Zone", "") + " Input", zone.category(), zone.template(), zone.settings(), zone.fixed(),
                null);
    }

    /** A copy of a node beside it, unlinked. Not a zone's half: the copy would have no pair. */
    @SuppressWarnings("unchecked")
    Node duplicate(Node original) {
        if (original.pair != null || pairOf(original) != null) {
            return original;
        }
        Map<String, Object> json = nodeJson(original, Path.of("."), Path.of("."), new LinkedHashMap<>());
        json.put("name", uniqueName(original.entry != null ? original.entry.label() : original.name));
        Node node = new Node(json, original.entry);
        node.image = original.image;
        node.imageIsData = original.imageIsData;
        node.x = original.x + 40f;
        node.y = original.y + 40f;
        nodes.add(node);
        changed();
        return node;
    }

    private String uniqueName(String base) {
        Set<String> taken = new HashSet<>();
        for (Node node : nodes) {
            taken.add(node.name);
        }
        if (!taken.contains(base)) {
            return base;
        }
        for (int i = 1; ; i++) {
            String candidate = String.format("%s.%03d", base, i);
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** The node that names this one as its pair: a zone output's input node. */
    Node pairOf(Node output) {
        for (Node node : nodes) {
            if (output.name.equals(node.pair)) {
                return node;
            }
        }
        return null;
    }

    /** The link into an input: its only one, or a multi-input's last. */
    Link linkInto(Node to, String input) {
        Link found = null;
        for (Link link : links) {
            if (link.to() == to && link.toSocket().equals(input)) {
                found = link;
            }
        }
        return found;
    }

    boolean linkedFrom(Node from, String output) {
        for (Link link : links) {
            if (link.from() == from && link.fromSocket().equals(output)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Links an output to an input: replacing what fed the input, or for a multi-input socket adding to what feeds it.
     * Refused if it would make a loop.
     */
    boolean connect(Node from, String output, Node to, String input) {
        if (from == to || reaches(to, from)) {
            return false;
        }
        Socket target = to.input(input);
        if (target != null && target.multiInput) {
            links.removeIf(link -> link.to() == to && link.toSocket().equals(input) && link.from() == from
                    && link.fromSocket().equals(output));
        } else {
            links.removeIf(link -> link.to() == to && link.toSocket().equals(input));
        }
        links.add(new Link(from, output, to, input));
        changed();
        return true;
    }

    /** Whether {@code start} feeds, through any links (and from a zone's input to its output), into {@code target}. */
    private boolean reaches(Node start, Node target) {
        List<Node> queue = new ArrayList<>(List.of(start));
        Set<Node> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Node node = queue.removeLast();
            if (node == target) {
                return true;
            }
            if (!seen.add(node)) {
                continue;
            }
            for (Link link : links) {
                if (link.from() == node) {
                    queue.add(link.to());
                }
            }
        }
        return false;
    }

    /** Takes every link off an input. */
    void disconnect(Node to, String input) {
        if (links.removeIf(link -> link.to() == to && link.toSocket().equals(input))) {
            changed();
        }
    }

    /** Takes one link off an input: a multi-input's last, or the only one. */
    void disconnect(Link link) {
        if (links.remove(link)) {
            changed();
        }
    }

    void remove(Node node) {
        List<Node> going = new ArrayList<>(List.of(node));
        // A zone goes as a whole.
        if (node.pair != null) {
            for (Node other : nodes) {
                if (other.name.equals(node.pair)) {
                    going.add(other);
                }
            }
        }
        Node input = pairOf(node);
        if (input != null) {
            going.add(input);
        }
        nodes.removeAll(going);
        links.removeIf(link -> going.contains(link.from()) || going.contains(link.to()));
        changed();
    }

    /**
     * Shows and hides a node's sockets for its dropdown settings, as Blender does: each setting changed from its first
     * value turns on or off the sockets that choice turns on or off.
     */
    void applySettings(Node node) {
        if (node.entry == null) {
            return;
        }
        Map<String, Object> template = node.entry.template();
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = template.get("props") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Boolean> base = new HashMap<>();
        for (Map<String, Object> s : Json.objects(template, "inputs")) {
            base.put("i:" + s.get("id"), Json.bool(s, "enabled", true));
        }
        for (Map<String, Object> s : Json.objects(template, "outputs")) {
            base.put("o:" + s.get("id"), Json.bool(s, "enabled", true));
        }
        Map<String, Boolean> shown = new HashMap<>(base);
        for (NodeCatalog.Setting setting : node.entry.settings().values()) {
            Object now = node.props.get(setting.name());
            Object first = defaults.get(setting.name());
            if (now == null || now.equals(first)) {
                continue;
            }
            Set<String> withNow = setting.inputs().get(String.valueOf(now));
            Set<String> withFirst = setting.inputs().get(String.valueOf(first));
            if (withNow != null && withFirst != null) {
                for (Socket socket : node.inputs) {
                    if (withNow.contains(socket.id) != withFirst.contains(socket.id)) {
                        shown.put("i:" + socket.id, withNow.contains(socket.id));
                    }
                }
            }
            Set<String> outNow = setting.outputs().get(String.valueOf(now));
            Set<String> outFirst = setting.outputs().get(String.valueOf(first));
            if (outNow != null && outFirst != null) {
                for (Socket socket : node.outputs) {
                    if (outNow.contains(socket.id) != outFirst.contains(socket.id)) {
                        shown.put("o:" + socket.id, outNow.contains(socket.id));
                    }
                }
            }
        }
        for (Socket socket : node.inputs) {
            socket.enabled = shown.getOrDefault("i:" + socket.id, socket.enabled);
        }
        for (Socket socket : node.outputs) {
            socket.enabled = shown.getOrDefault("o:" + socket.id, socket.enabled);
        }
        // A link into or out of a socket that has gone is dropped, as Blender drops it.
        links.removeIf(link -> (link.to() == node && node.input(link.toSocket()) != null && !node.input(link.toSocket()).enabled)
                || (link.from() == node && node.output(link.fromSocket()) != null && !node.output(link.fromSocket()).enabled));
    }

    // --- A geometry tree's interface --------------------------------------------------------------------------

    /** Adds an input to the tree's interface, after the others, with an id no other socket has. */
    InterfaceItem addInput(String name, String type) {
        InterfaceItem item = new InterfaceItem(true, freeSocketId(), uniqueInputName(name), type, defaultFor(type), null,
                null);
        // Inputs come before the outputs, as Blender lists them.
        int at = 0;
        for (int i = 0; i < interfaceItems.size(); i++) {
            if (interfaceItems.get(i).input) {
                at = i + 1;
            }
        }
        interfaceItems.add(at, item);
        syncInterface();
        changed();
        return item;
    }

    void removeInterfaceItem(InterfaceItem item) {
        if (interfaceItems.remove(item)) {
            syncInterface();
            changed();
        }
    }

    /** Moves an input up or down the list. */
    void moveInput(InterfaceItem item, int by) {
        int at = interfaceItems.indexOf(item);
        int to = at + by;
        if (at < 0 || to < 0 || to >= interfaceItems.size() || interfaceItems.get(to).input != item.input) {
            return;
        }
        interfaceItems.remove(at);
        interfaceItems.add(to, item);
        syncInterface();
        changed();
    }

    /** Changes an input's type, its default going back to that type's. */
    void retype(InterfaceItem item, String type) {
        if (item.type.equals(type)) {
            return;
        }
        item.type = type;
        item.defaultValue = defaultFor(type);
        item.min = null;
        item.max = null;
        syncInterface();
        changed();
    }

    private String uniqueInputName(String base) {
        Set<String> taken = new HashSet<>();
        for (InterfaceItem item : interfaceItems) {
            if (item.input) {
                taken.add(item.name);
            }
        }
        if (!taken.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            if (!taken.contains(base + " " + i)) {
                return base + " " + i;
            }
        }
    }

    private String freeSocketId() {
        Set<String> taken = new HashSet<>();
        for (InterfaceItem item : interfaceItems) {
            taken.add(item.id);
        }
        for (int i = 0; ; i++) {
            if (!taken.contains("Socket_" + i)) {
                return "Socket_" + i;
            }
        }
    }

    static Object defaultFor(String type) {
        return switch (type) {
            case "VALUE" -> 0.0;
            case "INT" -> 0L;
            case "BOOLEAN" -> false;
            case "VECTOR", "ROTATION" -> new ArrayList<>(List.of(0.0, 0.0, 0.0));
            case "RGBA" -> new ArrayList<>(List.of(0.8, 0.8, 0.8, 1.0));
            case "STRING", "MATERIAL" -> "";
            default -> null;
        };
    }

    /**
     * Gives every Group Input node one output for each interface input, and the Group Output one input for each
     * interface output, keeping the links of the ones still there and their names and types up to date.
     */
    void syncInterface() {
        if (kind != Kind.GEOMETRY) {
            return;
        }
        for (Node node : nodes) {
            if (node.isGroupInput()) {
                sync(node, node.outputs, true);
            } else if (node.type.equals("NodeGroupOutput")) {
                sync(node, node.inputs, false);
            }
        }
    }

    private void sync(Node node, List<Socket> sockets, boolean inputs) {
        Map<String, Socket> had = new HashMap<>();
        for (Socket socket : sockets) {
            had.put(socket.id, socket);
        }
        sockets.clear();
        for (InterfaceItem item : interfaceItems) {
            if (item.input != inputs) {
                continue;
            }
            Socket socket = had.get(item.id);
            if (socket == null) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("id", item.id);
                json.put("name", item.name);
                json.put("type", item.type);
                json.put("enabled", true);
                socket = new Socket(node, json, inputs);
            }
            socket.name = item.name;
            socket.type = item.type;
            socket.value = inputs ? copy(item.defaultValue) : null;
            sockets.add(socket);
        }
        Set<String> ids = new HashSet<>();
        for (Socket socket : sockets) {
            ids.add(socket.id);
        }
        links.removeIf(link -> inputs ? link.from() == node && !ids.contains(link.fromSocket())
                : link.to() == node && !ids.contains(link.toSocket()));
    }

    // --- Files ------------------------------------------------------------------------------------------------

    /** A graph to start from: a Principled BSDF into the Material Output. */
    static NodeGraph starter(NodeCatalog catalog, String name) {
        NodeGraph graph = new NodeGraph();
        graph.materialName = name;
        NodeCatalog.Entry output = catalog.byId("ShaderNodeOutputMaterial");
        NodeCatalog.Entry principled = catalog.byId("ShaderNodeBsdfPrincipled");
        if (output != null && principled != null) {
            Node bsdf = graph.add(principled, 0f, 0f);
            Node out = graph.add(output, 340f, 0f);
            graph.connect(bsdf, "BSDF", out, "Surface");
        }
        graph.revision = 0;
        return graph;
    }

    /**
     * A geometry tree to start from: a cube into the Group Output, so the preview shows something straight away, and a
     * Group Input for the inputs it is given.
     */
    static NodeGraph geometryStarter(NodeCatalog catalog, String name) {
        NodeGraph graph = new NodeGraph(Kind.GEOMETRY);
        graph.materialName = name;
        graph.interfaceItems.add(new InterfaceItem(false, "Socket_0", "Geometry", "GEOMETRY", null, null, null));
        Node output = graph.groupNode("NodeGroupOutput", "Group Output", 320f, 0f);
        Node input = graph.groupNode("NodeGroupInput", "Group Input", -420f, 0f);
        NodeCatalog.Entry cube = catalog.byId("GeometryNodeMeshCube");
        if (cube != null) {
            Node made = graph.add(cube, -60f, 0f);
            graph.connect(made, "Mesh", output, "Socket_0");
        }
        graph.syncInterface();
        input.y = 0f;
        graph.revision = 0;
        return graph;
    }

    /** A Group Input or Output node: its sockets are the interface's, filled in by {@link #syncInterface}. */
    Node groupNode(String type, String name, float x, float y) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", uniqueName(name));
        json.put("type", type);
        json.put("props", new LinkedHashMap<>());
        json.put("inputs", new ArrayList<>());
        json.put("outputs", new ArrayList<>());
        Node node = new Node(json, null);
        node.x = x;
        node.y = y;
        nodes.add(node);
        syncInterface();
        changed();
        return node;
    }

    /** The tree's name in the package: its own name, which a geometry tree is looked up by. */
    String treeKey() {
        return kind == Kind.GEOMETRY ? materialName : "Material: " + materialName;
    }

    /**
     * The graph as a node package, ready to save as {@code file} or to compile.
     *
     * @param assets the project's assets, which the Image Texture nodes' pictures are named from
     */
    Map<String, Object> toPackage(Path file, Path assets) {
        Path folder = file.toAbsolutePath().getParent();
        Map<String, Object> images = new LinkedHashMap<>();
        List<Object> nodeList = new ArrayList<>();
        for (Node node : nodes) {
            nodeList.add(nodeJson(node, folder, assets, images));
        }
        List<Object> linkList = new ArrayList<>();
        Map<String, Integer> orderInto = new HashMap<>();
        for (Link link : links) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("from_node", link.from().name);
            json.put("from_socket", link.fromSocket());
            json.put("to_node", link.to().name);
            json.put("to_socket", link.toSocket());
            Socket target = link.to().input(link.toSocket());
            if (target != null && target.multiInput) {
                // Blender's order: the newest link first, so the first made is the highest.
                String key = link.to().name + "\u0000" + link.toSocket();
                int order = orderInto.merge(key, 1, Integer::sum) - 1;
                json.put("order", (long) order);
            }
            linkList.add(json);
        }
        String treeKey = treeKey();
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("name", kind == Kind.GEOMETRY ? materialName : "Shader Nodetree");
        tree.put("type", kind == Kind.GEOMETRY ? "GEOMETRY" : "SHADER");
        tree.put("interface", interfaceJson());
        tree.put("nodes", nodeList);
        tree.put("links", linkList);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", "zeronura-node-package");
        json.put("version", 1L);
        json.put("blender", kind == Kind.GEOMETRY ? "Nulzs Game Engine Geometry Nodes Tool" : "Nulzs Game Engine Shader Tool");
        json.put("source", file.getFileName().toString());
        json.put("fps", 24.0);
        json.put("roots", new ArrayList<>());
        json.put("objects", new LinkedHashMap<>());
        json.put("collections", new LinkedHashMap<>());
        if (kind == Kind.SHADER) {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("name", materialName);
            material.put("diffuse_color", List.of(0.8, 0.8, 0.8, 1.0));
            material.put("metallic", 0.0);
            material.put("roughness", 0.5);
            material.put("backface_culling", false);
            material.put("render_method", "DITHERED");
            material.put("blend_method", "OPAQUE");
            material.put("alpha_threshold", 0.5);
            material.put("shadow", true);
            material.put("tree", treeKey);
            if (!bakePrefix.isBlank()) {
                Map<String, Object> bake = new LinkedHashMap<>();
                bake.put("prefix", bakePrefix);
                bake.put("size", (long) bakeSize);
                material.put("bake", bake);
            }
            json.put("materials", new LinkedHashMap<>(Map.of(materialName, material)));
        } else {
            json.put("materials", new LinkedHashMap<>());
            // What a Geometry Nodes Tool file is: one tree, run as a generator.
            json.put("geometry", new LinkedHashMap<>(Map.of("tree", treeKey)));
        }
        json.put("trees", new LinkedHashMap<>(Map.of(treeKey, tree)));
        json.put("images", images);
        json.put("meshes", new LinkedHashMap<>());
        return json;
    }

    private List<Object> interfaceJson() {
        List<Object> out = new ArrayList<>();
        for (InterfaceItem item : interfaceItems) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("in_out", item.input ? "INPUT" : "OUTPUT");
            json.put("name", item.name);
            json.put("id", item.id);
            json.put("socket", socketIdname(item.type));
            json.put("type", item.type);
            if (item.input && item.defaultValue != null) {
                json.put("default", copy(item.defaultValue instanceof String s && item.type.equals("MATERIAL")
                        ? (s.isBlank() ? null : Map.of("ref", s, "kind", "MATERIAL")) : item.defaultValue));
            }
            if (item.min != null) {
                json.put("min", (double) item.min);
            }
            if (item.max != null) {
                json.put("max", (double) item.max);
            }
            json.put("subtype", "NONE");
            json.put("default_attribute_name", "");
            json.put("attribute_domain", "POINT");
            json.put("hide_value", false);
            json.put("default_input", "VALUE");
            out.add(json);
        }
        return out;
    }

    /** Blender's socket class for a socket type, as an interface item names it. */
    static String socketIdname(String type) {
        return switch (type) {
            case "GEOMETRY" -> "NodeSocketGeometry";
            case "INT" -> "NodeSocketInt";
            case "BOOLEAN" -> "NodeSocketBool";
            case "VECTOR" -> "NodeSocketVector";
            case "RGBA" -> "NodeSocketColor";
            case "ROTATION" -> "NodeSocketRotation";
            case "MATRIX" -> "NodeSocketMatrix";
            case "STRING" -> "NodeSocketString";
            case "MATERIAL" -> "NodeSocketMaterial";
            case "MENU" -> "NodeSocketMenu";
            default -> "NodeSocketFloat";
        };
    }

    private Map<String, Object> nodeJson(Node node, Path folder, Path assets, Map<String, Object> images) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = node.foreign != null ? (Map<String, Object>) copy(node.foreign) : new LinkedHashMap<>();
        json.put("name", node.name);
        json.put("type", node.type);
        if (node.label != null && !node.label.isEmpty()) {
            json.put("label", node.label);
        }
        json.put("props", copy(node.props));
        List<Object> inputs = new ArrayList<>();
        for (Socket socket : node.inputs) {
            inputs.add(socketJson(socket, linkInto(node, socket.id) != null));
        }
        List<Object> outputs = new ArrayList<>();
        for (Socket socket : node.outputs) {
            outputs.add(socketJson(socket, linkedFrom(node, socket.id)));
        }
        json.put("inputs", inputs);
        json.put("outputs", outputs);
        if (node.ramp != null) {
            json.put("ramp", copy(node.ramp));
        }
        if (node.curves != null) {
            json.put("curves", copy(node.curves));
        }
        if (node.pair != null) {
            json.put("pair", node.pair);
        } else {
            json.remove("pair");
        }
        if (node.type.equals("ShaderNodeTexImage") && node.image != null && !node.image.isBlank()) {
            String key = Path.of(node.image).getFileName().toString();
            Map<String, Object> image = new LinkedHashMap<>();
            Path picture = assets.resolve(node.image).toAbsolutePath().normalize();
            image.put("file", folder.normalize().relativize(picture).toString().replace('\\', '/'));
            image.put("colorspace", node.imageIsData ? "Non-Color" : "sRGB");
            image.put("alpha_mode", "STRAIGHT");
            image.put("asset", node.image);
            images.put(key, image);
            json.put("image", key);
        }
        json.put("location", List.of((double) Math.round(node.x), (double) Math.round(node.y)));
        return json;
    }

    private static Map<String, Object> socketJson(Socket socket, boolean linked) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", socket.id);
        json.put("name", socket.name);
        json.put("type", socket.type);
        json.put("enabled", socket.enabled);
        if (linked) {
            json.put("linked", true);
        }
        if (socket.value != null) {
            json.put("value", copy(socket.value));
        }
        if (socket.hideValue) {
            json.put("hide_value", true);
        }
        if (socket.multiInput) {
            json.put("multi_input", true);
        }
        return json;
    }

    /** Whether a node package holds a Geometry Nodes Tool tree (or any geometry tree but no material) rather than a material. */
    static boolean isGeometry(Map<String, Object> json) {
        if (json.get("geometry") instanceof Map<?, ?>) {
            return true;
        }
        Map<String, Object> materials = Json.object(json, "materials");
        if (materials != null && !materials.isEmpty()) {
            return false;
        }
        Map<String, Object> trees = Json.object(json, "trees");
        if (trees != null) {
            for (Object tree : trees.values()) {
                if (tree instanceof Map<?, ?> m && "GEOMETRY".equals(m.get("type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The first material's graph in a node package, laid out afresh if its nodes don't say where they sit (Blender's
     * exports don't).
     */
    @SuppressWarnings("unchecked")
    static NodeGraph fromPackage(Map<String, Object> json, NodeCatalog catalog, Path file, Path assets) {
        NodeGraph graph = new NodeGraph();
        Map<String, Object> materials = Json.object(json, "materials");
        Map<String, Object> trees = Json.object(json, "trees");
        if (materials == null || materials.isEmpty() || trees == null) {
            throw new IllegalArgumentException("it has no material in it");
        }
        Map.Entry<String, Object> first = materials.entrySet().iterator().next();
        graph.materialName = first.getKey();
        Map<String, Object> bake = Json.object((Map<String, Object>) first.getValue(), "bake");
        if (bake != null) {
            graph.bakePrefix = Json.string(bake, "prefix", "");
            graph.bakeSize = Math.clamp(Json.integer(bake, "size", 1024), 16, 8192);
        }
        String treeKey = Json.string((Map<String, Object>) first.getValue(), "tree", "");
        Map<String, Object> tree = (Map<String, Object>) trees.get(treeKey);
        if (tree == null) {
            throw new IllegalArgumentException("its material has no node tree");
        }
        graph.readTree(tree, Json.object(json, "images"), catalog, file, assets);
        return graph;
    }

    /**
     * A geometry tree's graph in a node package: the one a Geometry Nodes Tool file names, or the first geometry tree.
     */
    @SuppressWarnings("unchecked")
    static NodeGraph geometryFromPackage(Map<String, Object> json, NodeCatalog catalog, Path file, Path assets) {
        Map<String, Object> trees = Json.object(json, "trees");
        if (trees == null || trees.isEmpty()) {
            throw new IllegalArgumentException("it has no node tree in it");
        }
        Map<String, Object> marker = Json.object(json, "geometry");
        String key = marker == null ? null : Json.string(marker, "tree", null);
        Map<String, Object> tree = key == null ? null : (Map<String, Object>) trees.get(key);
        if (tree == null) {
            for (Map.Entry<String, Object> entry : trees.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> m && "GEOMETRY".equals(m.get("type"))) {
                    key = entry.getKey();
                    tree = (Map<String, Object>) m;
                    break;
                }
            }
        }
        if (tree == null) {
            throw new IllegalArgumentException("it has no geometry node tree in it");
        }
        NodeGraph graph = new NodeGraph(Kind.GEOMETRY);
        graph.materialName = Json.string(tree, "name", key);
        for (Map<String, Object> item : Json.objects(tree, "interface")) {
            if (!item.containsKey("in_out")) {
                continue;
            }
            Object value = item.get("default");
            if (value instanceof Map<?, ?> ref && ref.get("ref") instanceof String name) {
                value = name;
            }
            String type = Json.string(item, "type", "VALUE");
            graph.interfaceItems.add(new InterfaceItem("INPUT".equals(item.get("in_out")), Json.string(item, "id", ""),
                    Json.string(item, "name", ""), type, value == null ? defaultFor(type) : copy(value),
                    item.get("min") instanceof Number n ? n.floatValue() : null,
                    item.get("max") instanceof Number n ? n.floatValue() : null));
        }
        graph.readTree(tree, Json.object(json, "images"), catalog, file, assets);
        graph.syncInterface();
        graph.revision = 0;
        return graph;
    }

    @SuppressWarnings("unchecked")
    private void readTree(Map<String, Object> tree, Map<String, Object> images, NodeCatalog catalog, Path file, Path assets) {
        Path folder = file.toAbsolutePath().getParent();
        Map<String, Node> byName = new HashMap<>();
        boolean placed = true;
        for (Map<String, Object> nodeJson : Json.objects(tree, "nodes")) {
            Map<String, Object> props = Json.object(nodeJson, "props");
            NodeCatalog.Entry entry = catalog.entryFor(Json.string(nodeJson, "type", ""), props);
            Node node = new Node(nodeJson, entry);
            placed &= nodeJson.get("location") instanceof List<?>;
            if (nodeJson.get("image") instanceof String key && images != null && images.get(key) instanceof Map<?, ?> image) {
                Object asset = ((Map<String, Object>) image).get("asset");
                Object relative = ((Map<String, Object>) image).get("file");
                if (asset instanceof String a) {
                    node.image = a;
                } else if (relative instanceof String r) {
                    Path picture = folder.resolve(r).normalize();
                    node.image = picture.startsWith(assets) ? assets.relativize(picture).toString().replace('\\', '/') : picture.toString();
                }
                node.imageIsData = String.valueOf(((Map<String, Object>) image).get("colorspace")).contains("Non-Color");
            }
            nodes.add(node);
            byName.put(node.name, node);
        }
        // Each multi-input's links in the order they were made: Blender lists the newest first.
        List<Map<String, Object>> linkJsons = new ArrayList<>(Json.objects(tree, "links"));
        linkJsons.sort((a, b) -> Integer.compare(Json.integer(a, "order", 0), Json.integer(b, "order", 0)));
        for (Map<String, Object> link : linkJsons) {
            Node from = byName.get(Json.string(link, "from_node", ""));
            Node to = byName.get(Json.string(link, "to_node", ""));
            if (from != null && to != null) {
                links.add(new Link(from, Json.string(link, "from_socket", ""), to, Json.string(link, "to_socket", "")));
            }
        }
        if (!placed) {
            arrange();
        }
        revision = 0;
    }

    /** Lays the nodes out in columns by how far each is from the output, the output on the right. */
    void arrange() {
        Map<Node, Integer> depth = new HashMap<>();
        for (Node node : nodes) {
            depth.put(node, 0);
        }
        // Each node sits a column left of the furthest-left node it feeds.
        for (int pass = 0; pass < nodes.size(); pass++) {
            boolean moved = false;
            for (Link link : links) {
                int wanted = depth.get(link.to()) + 1;
                if (depth.get(link.from()) < wanted) {
                    depth.put(link.from(), wanted);
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        Map<Integer, Float> columnY = new HashMap<>();
        for (Node node : nodes) {
            int d = depth.get(node);
            float y = columnY.getOrDefault(d, 0f);
            node.x = -d * 280f;
            node.y = y;
            columnY.put(d, y + 60f + 24f * (node.inputs.size() + node.outputs.size()) * 0.6f + 40f);
        }
        changed();
    }

    /** A deep copy of a JSON value, so a node never shares a list or map with the catalog's template. */
    @SuppressWarnings("unchecked")
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(copy(item));
            }
            return out;
        }
        return value;
    }
}
