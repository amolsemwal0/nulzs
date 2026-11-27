package org.zeronura.nulzs.nodes.geometry;

/** Where an attribute lives on a geometry: Blender's attribute domains. */
public enum Domain {
    POINT, EDGE, FACE, CORNER, CURVE, INSTANCE, LAYER;

    public static Domain parse(String name, Domain fallback) {
        if (name == null) {
            return fallback;
        }
        return switch (name) {
            case "POINT" -> POINT;
            case "EDGE" -> EDGE;
            case "FACE" -> FACE;
            case "CORNER" -> CORNER;
            case "CURVE" -> CURVE;
            case "INSTANCE" -> INSTANCE;
            case "LAYER" -> LAYER;
            default -> fallback;
        };
    }
}
