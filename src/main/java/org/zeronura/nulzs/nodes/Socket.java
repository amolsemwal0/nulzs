package org.zeronura.nulzs.nodes;

import java.util.ArrayList;
import java.util.List;

/**
 * One input or output of a {@link Node}.
 *
 * <p>Sockets are found by their {@link #id identifier}, which is unique on a side of a node; names repeat
 * (a Mix node has three inputs called "A", one per data type, and only one of them is {@link #enabled}).
 */
public final class Socket {

    public final Node node;
    public final String id;
    public final String name;
    public final SocketType type;
    public final boolean enabled;
    public final boolean output;
    /** The value set on the socket in Blender: a Double, Long, Boolean, String, float[] or {@link Ref}. */
    public final Object value;
    /** Every link into this input, in the order a multi-input socket stacks them; empty for outputs. */
    final List<Link> links = new ArrayList<>();

    Socket(Node node, String id, String name, SocketType type, boolean enabled, boolean output, Object value) {
        this.node = node;
        this.id = id;
        this.name = name;
        this.type = type;
        this.enabled = enabled;
        this.output = output;
        this.value = value;
    }

    public boolean isLinked() {
        return !links.isEmpty();
    }

    public List<Link> links() {
        return links;
    }

    /** The one link into this input, or null. Multi-input sockets use {@link #links()}. */
    public Link link() {
        return links.isEmpty() ? null : links.get(0);
    }

    public float floatValue() {
        return NodeValues.toFloat(value);
    }

    public int intValue() {
        return (int) NodeValues.toFloat(value);
    }

    public boolean boolValue() {
        return NodeValues.toBool(value);
    }

    /** The value as a 3- or 4-vector (a lone number is repeated), into a fresh array of {@code size}. */
    public float[] vectorValue(int size) {
        return NodeValues.toVector(value, size);
    }

    @Override
    public String toString() {
        return node.name + (output ? " > " : " < ") + id;
    }
}
