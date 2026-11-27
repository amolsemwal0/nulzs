package org.zeronura.engine.nodes;

/**
 * The kind of value a node socket carries, named as Blender's Python API names them ({@code socket.type}).
 *
 * <p>{@code VALUE} is Blender's float socket and {@code RGBA} its colour socket.
 */
public enum SocketType {
    VALUE, INT, BOOLEAN, VECTOR, RGBA, STRING, ROTATION, MATRIX, MENU,
    GEOMETRY, SHADER, OBJECT, COLLECTION, MATERIAL, IMAGE, TEXTURE, FONT, BUNDLE, CLOSURE, CUSTOM;

    public static SocketType parse(String name) {
        if (name == null) {
            return CUSTOM;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return CUSTOM;
        }
    }

    /** Whether values of this type can vary per element of a geometry (be a field). */
    public boolean isFieldType() {
        return switch (this) {
            case VALUE, INT, BOOLEAN, VECTOR, RGBA, ROTATION, MATRIX -> true;
            default -> false;
        };
    }
}
