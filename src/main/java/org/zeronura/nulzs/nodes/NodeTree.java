package org.zeronura.nulzs.nodes;

import org.zeronura.nulzs.data.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node tree as exported: a material's shader tree, a geometry-nodes modifier's tree, or a group either uses.
 *
 * <p>The tree only describes; {@link org.zeronura.nulzs.nodes.shader.ShaderCompiler} turns a shader tree into
 * GLSL and {@link org.zeronura.nulzs.nodes.geometry.GeometryEvaluator} runs a geometry tree.
 */
public final class NodeTree {

    public enum Kind { GEOMETRY, SHADER, OTHER }

    /** One socket of the tree's interface: what a group node, or a modifier, shows as its inputs and outputs. */
    public record InterfaceSocket(boolean input, String name, String id, SocketType type, Object defaultValue,
                                  Float min, Float max, String subtype, String defaultAttribute,
                                  String domain, String defaultInput) {
    }

    public final String key;
    public final String name;
    public final Kind kind;
    private final List<InterfaceSocket> inputs = new ArrayList<>();
    private final List<InterfaceSocket> outputs = new ArrayList<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Link> links = new ArrayList<>();

    private NodeTree(String key, String name, Kind kind) {
        this.key = key;
        this.name = name;
        this.kind = kind;
    }

    @SuppressWarnings("unchecked")
    static NodeTree fromJson(String key, Map<String, Object> json) {
        Kind kind = switch (Json.string(json, "type", "")) {
            case "GEOMETRY" -> Kind.GEOMETRY;
            case "SHADER" -> Kind.SHADER;
            default -> Kind.OTHER;
        };
        NodeTree tree = new NodeTree(key, Json.string(json, "name", key), kind);
        for (Map<String, Object> item : Json.objects(json, "interface")) {
            boolean input = "INPUT".equals(item.get("in_out"));
            InterfaceSocket socket = new InterfaceSocket(input, Json.string(item, "name", ""),
                    Json.string(item, "id", ""), SocketType.parse(Json.string(item, "type", "CUSTOM")),
                    NodeValues.fromJson(item.get("default")), number(item.get("min")), number(item.get("max")),
                    Json.string(item, "subtype", "NONE"), Json.string(item, "default_attribute_name", ""),
                    Json.string(item, "attribute_domain", "POINT"), Json.string(item, "default_input", "VALUE"));
            (input ? tree.inputs : tree.outputs).add(socket);
        }
        for (Map<String, Object> nodeJson : Json.objects(json, "nodes")) {
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> rawProps = Json.object(nodeJson, "props");
            if (rawProps != null) {
                for (Map.Entry<String, Object> entry : rawProps.entrySet()) {
                    props.put(entry.getKey(), NodeValues.fromJson(entry.getValue()));
                }
            }
            List<String[]> internal = new ArrayList<>();
            for (Object pair : Json.list(nodeJson, "internal_links")) {
                List<Object> ids = (List<Object>) pair;
                internal.add(new String[]{String.valueOf(ids.get(0)), String.valueOf(ids.get(1))});
            }
            Node node = new Node(tree, Json.string(nodeJson, "name"), Json.string(nodeJson, "type"),
                    Json.string(nodeJson, "label", ""), Json.bool(nodeJson, "mute", false), props,
                    Json.string(nodeJson, "tree", null), Json.string(nodeJson, "pair", null),
                    Json.string(nodeJson, "image", null), ColorRamp.fromJson(Json.object(nodeJson, "ramp")),
                    CurveMapping.fromJson(Json.object(nodeJson, "curves")), internal);
            for (Map<String, Object> s : Json.objects(nodeJson, "inputs")) {
                node.addSocket(socket(node, s, false));
            }
            for (Map<String, Object> s : Json.objects(nodeJson, "outputs")) {
                node.addSocket(socket(node, s, true));
            }
            tree.nodes.put(node.name, node);
        }
        for (Map<String, Object> linkJson : Json.objects(json, "links")) {
            Node from = tree.nodes.get(Json.string(linkJson, "from_node"));
            Node to = tree.nodes.get(Json.string(linkJson, "to_node"));
            if (from == null || to == null) {
                continue;
            }
            Socket fromSocket = from.output(Json.string(linkJson, "from_socket"));
            Socket toSocket = to.input(Json.string(linkJson, "to_socket"));
            if (fromSocket == null || toSocket == null) {
                continue;
            }
            Link link = new Link(fromSocket, toSocket, Json.integer(linkJson, "order", 0));
            toSocket.links.add(link);
            tree.links.add(link);
        }
        for (Node node : tree.nodes.values()) {
            for (Socket input : node.inputs) {
                if (input.links.size() > 1) {
                    // A multi-input socket lists the newest link first in Blender's own order.
                    input.links.sort(Comparator.comparingInt(Link::order).reversed());
                }
            }
        }
        return tree;
    }

    private static Float number(Object value) {
        return value instanceof Number n ? n.floatValue() : null;
    }

    private static Socket socket(Node node, Map<String, Object> json, boolean output) {
        return new Socket(node, Json.string(json, "id"), Json.string(json, "name", ""),
                SocketType.parse(Json.string(json, "type", "CUSTOM")), Json.bool(json, "enabled", true), output,
                NodeValues.fromJson(json.get("value")));
    }

    public List<InterfaceSocket> inputs() {
        return Collections.unmodifiableList(inputs);
    }

    public List<InterfaceSocket> outputs() {
        return Collections.unmodifiableList(outputs);
    }

    public InterfaceSocket input(String idOrName) {
        for (InterfaceSocket socket : inputs) {
            if (socket.id().equals(idOrName)) {
                return socket;
            }
        }
        for (InterfaceSocket socket : inputs) {
            if (socket.name().equals(idOrName)) {
                return socket;
            }
        }
        return null;
    }

    public java.util.Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Node node(String name) {
        return nodes.get(name);
    }

    public List<Link> links() {
        return Collections.unmodifiableList(links);
    }

    /** Nodes of a type, in the order Blender listed them. */
    public List<Node> nodesOfType(String type) {
        List<Node> out = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (node.type.equals(type)) {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * Where an input's value really comes from: the output at the far end of its link, followed through reroutes
     * and muted nodes. Returns null when nothing is connected (the input's own value applies), or an
     * <i>input</i> socket when the trail ends at a muted node's unconnected input, whose value then applies.
     */
    public static Socket source(Socket input) {
        return source(input.link());
    }

    public static Socket source(Link link) {
        if (link == null) {
            return null;
        }
        Socket out = link.from();
        for (int guard = 0; guard < 4096; guard++) {
            Node node = out.node;
            if (node.isReroute()) {
                Socket in = node.inputs.isEmpty() ? null : node.inputs.get(0);
                if (in == null || in.link() == null) {
                    return null;
                }
                out = in.link().from();
                continue;
            }
            if (node.muted) {
                String inputId = null;
                for (String[] pair : node.internalLinks) {
                    if (pair[1].equals(out.id)) {
                        inputId = pair[0];
                        break;
                    }
                }
                if (inputId == null) {
                    return null;
                }
                Socket in = node.input(inputId);
                if (in == null) {
                    return null;
                }
                if (in.link() == null) {
                    return in;
                }
                out = in.link().from();
                continue;
            }
            return out;
        }
        throw new IllegalStateException("reroute loop in " + input(link));
    }

    private static String input(Link link) {
        return link.to().toString();
    }

    /** The node that sends the tree its result: the active Group Output, or the active Material Output. */
    public Node outputNode(String type) {
        Node found = null;
        for (Node node : nodes.values()) {
            if (node.type.equals(type)) {
                if (node.propBool("is_active_output", false)) {
                    return node;
                }
                if (found == null) {
                    found = node;
                }
            }
        }
        return found;
    }

    @Override
    public String toString() {
        return kind + " tree \"" + name + "\"";
    }
}
