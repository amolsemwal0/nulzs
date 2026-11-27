package org.zeronura.engine.nodes.geometry;

/** What one kind of geometry node does: read its inputs from the call, set its outputs on it. */
@FunctionalInterface
public interface NodeImpl {
    void execute(NodeCall call);
}
