package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.SocketType;

/**
 * The type of an attribute or field value. Floats, vectors, colours, rotations (quaternions, w first) and
 * matrices (4x4, column-major) are stored as floats; integers and booleans as ints.
 */
public enum DataType {
    FLOAT(1, false), INT(1, true), BOOL(1, true), VECTOR(3, false), FLOAT2(2, false), COLOR(4, false),
    ROTATION(4, false), MATRIX(16, false);

    public final int width;
    public final boolean integer;

    DataType(int width, boolean integer) {
        this.width = width;
        this.integer = integer;
    }

    /** The type a socket of this kind carries, or null when it carries no field value. */
    public static DataType of(SocketType socket) {
        return switch (socket) {
            case VALUE -> FLOAT;
            case INT -> INT;
            case BOOLEAN -> BOOL;
            case VECTOR -> VECTOR;
            case RGBA -> COLOR;
            case ROTATION -> ROTATION;
            case MATRIX -> MATRIX;
            default -> null;
        };
    }

    /** An attribute type as Blender names it ({@code FLOAT_VECTOR}, {@code BYTE_COLOR}...). */
    public static DataType ofAttribute(String name) {
        return switch (name) {
            case "INT", "INT8" -> INT;
            case "BOOLEAN" -> BOOL;
            case "FLOAT_VECTOR" -> VECTOR;
            case "FLOAT2", "INT32_2D" -> FLOAT2;
            case "FLOAT_COLOR", "BYTE_COLOR" -> COLOR;
            case "QUATERNION" -> ROTATION;
            case "FLOAT4X4" -> MATRIX;
            default -> FLOAT;
        };
    }
}
