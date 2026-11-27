package org.zeronura.nulzs.nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of a tree, as Blender had it: its type ({@code bl_idname}), its settings, and its sockets.
 *
 * <p>Settings are kept by their Python names ({@code operation}, {@code data_type}, {@code domain}...) with enum
 * settings as their identifiers ({@code "MULTIPLY"}), so a node reads the way its Blender documentation does.
 */
public final class Node {

    public final NodeTree tree;
    public final String name;
    public final String type;
    public final String label;
    public final boolean muted;
    /** Settings by Python name. Lists of items (a repeat zone's state, a menu's options) are lists of maps. */
    public final Map<String, Object> props;
    /** A group node's tree, by its key in the package; null for every other node. */
    public final String groupTree;
    /** For a zone's input node, the name of its output node. */
    public final String pair;
    /** The image an Image Texture node samples, by name. */
    public final String image;
    public final ColorRamp ramp;
    public final CurveMapping curves;
    /** For a muted node, which input passes through to which output, by identifier. */
    public final List<String[]> internalLinks;

    final List<Socket> inputs = new ArrayList<>();
    final List<Socket> outputs = new ArrayList<>();
    private final Map<String, Socket> inputsById = new LinkedHashMap<>();
    private final Map<String, Socket> outputsById = new LinkedHashMap<>();

    Node(NodeTree tree, String name, String type, String label, boolean muted, Map<String, Object> props,
         String groupTree, String pair, String image, ColorRamp ramp, CurveMapping curves, List<String[]> internalLinks) {
        this.tree = tree;
        this.name = name;
        this.type = type;
        this.label = label;
        this.muted = muted;
        this.props = props;
        this.groupTree = groupTree;
        this.pair = pair;
        this.image = image;
        this.ramp = ramp;
        this.curves = curves;
        this.internalLinks = internalLinks;
    }

    void addSocket(Socket socket) {
        if (socket.output) {
            outputs.add(socket);
            outputsById.putIfAbsent(socket.id, socket);
        } else {
            inputs.add(socket);
            inputsById.putIfAbsent(socket.id, socket);
        }
    }

    public List<Socket> inputs() {
        return Collections.unmodifiableList(inputs);
    }

    public List<Socket> outputs() {
        return Collections.unmodifiableList(outputs);
    }

    /** An input by identifier, or null. */
    public Socket input(String id) {
        return inputsById.get(id);
    }

    /** An output by identifier, or null. */
    public Socket output(String id) {
        return outputsById.get(id);
    }

    /**
     * The first <i>enabled</i> input with this name, or failing that the first with it at all. For nodes whose
     * sockets are switched by a data type, where the name is shared and only one is live.
     */
    public Socket inputNamed(String name) {
        Socket fallback = null;
        for (Socket socket : inputs) {
            if (socket.name.equals(name)) {
                if (socket.enabled) {
                    return socket;
                }
                if (fallback == null) {
                    fallback = socket;
                }
            }
        }
        return fallback;
    }

    public Socket outputNamed(String name) {
        Socket fallback = null;
        for (Socket socket : outputs) {
            if (socket.name.equals(name)) {
                if (socket.enabled) {
                    return socket;
                }
                if (fallback == null) {
                    fallback = socket;
                }
            }
        }
        return fallback;
    }

    /** An enum or text setting, or {@code fallback} when the node has none by that name. */
    public String prop(String key, String fallback) {
        Object value = props.get(key);
        return value == null ? fallback : NodeValues.toText(value, fallback);
    }

    public float propFloat(String key, float fallback) {
        Object value = props.get(key);
        return value == null ? fallback : NodeValues.toFloat(value);
    }

    public int propInt(String key, int fallback) {
        Object value = props.get(key);
        return value == null ? fallback : (int) NodeValues.toFloat(value);
    }

    public boolean propBool(String key, boolean fallback) {
        Object value = props.get(key);
        return value == null ? fallback : NodeValues.toBool(value);
    }

    /** A list-of-items setting, such as {@code repeat_items} or {@code enum_items}; empty when absent. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> items(String key) {
        Object value = props.get(key);
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        return List.of();
    }

    public boolean isReroute() {
        return "NodeReroute".equals(type);
    }

    @Override
    public String toString() {
        return type + " \"" + name + "\"";
    }
}
