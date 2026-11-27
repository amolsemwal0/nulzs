package org.zeronura.nulzs.nodes;

/**
 * A connection from an output socket to an input socket. When the two sockets' types differ, the value is
 * converted on the way, as Blender converts it (a float into a vector repeats it, a colour into a float takes
 * its luminance, and so on).
 */
public record Link(Socket from, Socket to, int order) {

    public Node fromNode() {
        return from.node;
    }

    public Node toNode() {
        return to.node;
    }
}
