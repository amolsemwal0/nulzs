package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.Node;

/** A geometry tree used a node the engine does not implement, so it cannot be evaluated here. */
public final class UnsupportedNodeException extends RuntimeException {

    public final String nodeType;

    public UnsupportedNodeException(Node node) {
        super("geometry node " + node.type + " (\"" + node.name + "\" in " + node.tree.name + ") is not supported");
        this.nodeType = node.type;
    }

    public UnsupportedNodeException(Node node, String detail) {
        super("geometry node " + node.type + " (\"" + node.name + "\" in " + node.tree.name + "): " + detail);
        this.nodeType = node.type;
    }
}
