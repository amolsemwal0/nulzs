package org.zeronura.engine.nodes.geometry;

import org.zeronura.engine.nodes.tex.ColorMath;

import java.util.List;

/**
 * A run of values of one {@link DataType}: an attribute's data, or a field evaluated over a domain.
 *
 * <p>Columns are treated as immutable once they are part of a geometry. Building one fills it in place; after
 * that every change makes a new column, so geometries can share columns freely.
 */
public final class Column {

    public final DataType type;
    public final int size;
    /** Float-backed types: {@code size * type.width} values. Null for integer types. */
    public final float[] f;
    /** Integer and boolean types: {@code size} values. Null for float types. */
    public final int[] i;

    private Column(DataType type, int size, float[] f, int[] i) {
        this.type = type;
        this.size = size;
        this.f = f;
        this.i = i;
    }

    public static Column create(DataType type, int size) {
        return type.integer ? new Column(type, size, null, new int[size])
                : new Column(type, size, new float[size * type.width], null);
    }

    public static Column ofFloats(DataType type, float[] data) {
        if (type.integer) {
            throw new IllegalArgumentException(type + " is not float-backed");
        }
        return new Column(type, data.length / type.width, data, null);
    }

    public static Column ofInts(DataType type, int[] data) {
        if (!type.integer) {
            throw new IllegalArgumentException(type + " is not int-backed");
        }
        return new Column(type, data.length, null, data);
    }

    /** {@code size} copies of one value (a Number, Boolean or float[]), converted to {@code type}. */
    public static Column constant(DataType type, Object value, int size) {
        Column single = single(type, value);
        Column out = create(type, size);
        if (type.integer) {
            java.util.Arrays.fill(out.i, single.i[0]);
        } else {
            int w = type.width;
            for (int e = 0; e < size; e++) {
                System.arraycopy(single.f, 0, out.f, e * w, w);
            }
        }
        return out;
    }

    /** One value as a column of one. */
    public static Column single(DataType type, Object value) {
        Column out = create(type, 1);
        if (value instanceof Column c) {
            return c.size == 0 ? out : c.slice(0, 1).convert(type);
        }
        switch (type) {
            case INT -> out.i[0] = value instanceof Number n ? (int) n.doubleValue() : value instanceof Boolean b ? (b ? 1 : 0)
                    : value instanceof float[] v ? (int) average(v) : 0;
            case BOOL -> out.i[0] = (value instanceof Boolean b ? b : value instanceof Number n ? n.doubleValue() > 0
                    : value instanceof float[] v && average(v) > 0) ? 1 : 0;
            case FLOAT -> out.f[0] = value instanceof Number n ? n.floatValue() : value instanceof Boolean b ? (b ? 1f : 0f)
                    : value instanceof float[] v ? (v.length == 4 ? ColorMath.luminance(v[0], v[1], v[2]) : average(v)) : 0f;
            case ROTATION -> {
                if (value instanceof float[] v && v.length == 4) {
                    System.arraycopy(v, 0, out.f, 0, 4);
                } else if (value instanceof float[] v && v.length == 3) {
                    Transforms.eulerToQuat(v[0], v[1], v[2], out.f, 0);
                } else {
                    out.f[0] = 1f;
                }
            }
            case MATRIX -> {
                if (value instanceof float[] v && v.length == 16) {
                    System.arraycopy(v, 0, out.f, 0, 16);
                } else {
                    Transforms.identity(out.f, 0);
                }
            }
            default -> {
                int w = type.width;
                if (value instanceof float[] v) {
                    for (int k = 0; k < w; k++) {
                        out.f[k] = k < v.length ? v[k] : (k == 3 ? 1f : 0f);
                    }
                } else {
                    float s = value instanceof Number n ? n.floatValue() : value instanceof Boolean b ? (b ? 1f : 0f) : 0f;
                    for (int k = 0; k < w; k++) {
                        out.f[k] = (type == DataType.COLOR && k == 3) ? 1f : s;
                    }
                }
            }
        }
        return out;
    }

    private static float average(float[] v) {
        if (v.length == 0) {
            return 0f;
        }
        int n = Math.min(v.length, 3);
        float sum = 0f;
        for (int k = 0; k < n; k++) {
            sum += v[k];
        }
        return sum / n;
    }

    // --- Access ------------------------------------------------------------------------------------------

    public float getFloat(int index) {
        return switch (type) {
            case FLOAT -> f[index];
            case INT -> i[index];
            case BOOL -> i[index] != 0 ? 1f : 0f;
            case VECTOR -> (f[3 * index] + f[3 * index + 1] + f[3 * index + 2]) / 3f;
            case FLOAT2 -> (f[2 * index] + f[2 * index + 1]) / 2f;
            case COLOR -> ColorMath.luminance(f[4 * index], f[4 * index + 1], f[4 * index + 2]);
            default -> f[index * type.width];
        };
    }

    public int getInt(int index) {
        return switch (type) {
            case INT, BOOL -> i[index];
            default -> (int) getFloat(index);
        };
    }

    public boolean getBool(int index) {
        return switch (type) {
            case INT, BOOL -> i[index] > 0;
            case VECTOR -> f[3 * index] != 0f || f[3 * index + 1] != 0f || f[3 * index + 2] != 0f;
            case FLOAT2 -> f[2 * index] != 0f || f[2 * index + 1] != 0f;
            default -> getFloat(index) > 0f;
        };
    }

    /** The element at {@code index} as a vector of {@code out.length} (3 or 4) components, converting. */
    public float[] getVector(int index, float[] out) {
        switch (type) {
            case VECTOR -> {
                out[0] = f[3 * index];
                out[1] = f[3 * index + 1];
                out[2] = f[3 * index + 2];
                if (out.length > 3) {
                    out[3] = 1f;
                }
            }
            case COLOR, ROTATION -> {
                int n = Math.min(out.length, 4);
                System.arraycopy(f, 4 * index, out, 0, n);
            }
            case FLOAT2 -> {
                out[0] = f[2 * index];
                out[1] = f[2 * index + 1];
                out[2] = 0f;
                if (out.length > 3) {
                    out[3] = 1f;
                }
            }
            default -> {
                float s = getFloat(index);
                out[0] = out[1] = out[2] = s;
                if (out.length > 3) {
                    out[3] = 1f;
                }
            }
        }
        return out;
    }

    public void setFloat(int index, float value) {
        f[index] = value;
    }

    public void setInt(int index, int value) {
        i[index] = value;
    }

    public void setVector(int index, float x, float y, float z) {
        f[3 * index] = x;
        f[3 * index + 1] = y;
        f[3 * index + 2] = z;
    }

    // --- Whole-column operations -------------------------------------------------------------------------

    public Column copy() {
        return type.integer ? new Column(type, size, null, i.clone()) : new Column(type, size, f.clone(), null);
    }

    public Column slice(int from, int count) {
        Column out = create(type, count);
        if (type.integer) {
            System.arraycopy(i, from, out.i, 0, count);
        } else {
            System.arraycopy(f, from * type.width, out.f, 0, count * type.width);
        }
        return out;
    }

    /** A new column holding the elements at {@code indices}, in that order. */
    public Column gather(int[] indices) {
        return gather(indices, indices.length);
    }

    public Column gather(int[] indices, int count) {
        Column out = create(type, count);
        if (type.integer) {
            for (int e = 0; e < count; e++) {
                out.i[e] = i[indices[e]];
            }
        } else {
            int w = type.width;
            for (int e = 0; e < count; e++) {
                System.arraycopy(f, indices[e] * w, out.f, e * w, w);
            }
        }
        return out;
    }

    /** Copies element {@code from} of {@code source} (same type) into element {@code to} of this column. */
    public void copyElement(Column source, int from, int to) {
        if (type.integer) {
            i[to] = source.i[from];
        } else {
            System.arraycopy(source.f, from * type.width, f, to * type.width, type.width);
        }
    }

    public static Column concat(DataType type, List<Column> parts) {
        int total = 0;
        for (Column part : parts) {
            total += part.size;
        }
        Column out = create(type, total);
        int at = 0;
        for (Column part : parts) {
            Column p = part.convert(type);
            if (type.integer) {
                System.arraycopy(p.i, 0, out.i, at, p.size);
            } else {
                System.arraycopy(p.f, 0, out.f, at * type.width, p.size * type.width);
            }
            at += p.size;
        }
        return out;
    }

    /** This column as another type, converting each value as Blender's implicit conversions do. */
    public Column convert(DataType to) {
        if (to == type) {
            return this;
        }
        Column out = create(to, size);
        float[] v = new float[4];
        for (int e = 0; e < size; e++) {
            switch (to) {
                case FLOAT -> out.f[e] = getFloat(e);
                case INT -> out.i[e] = getInt(e);
                case BOOL -> out.i[e] = getBool(e) ? 1 : 0;
                case VECTOR -> {
                    if (type == DataType.ROTATION) {
                        Transforms.quatToEuler(f, 4 * e, out.f, 3 * e);
                    } else {
                        getVector(e, v);
                        out.f[3 * e] = v[0];
                        out.f[3 * e + 1] = v[1];
                        out.f[3 * e + 2] = v[2];
                    }
                }
                case FLOAT2 -> {
                    getVector(e, v);
                    out.f[2 * e] = v[0];
                    out.f[2 * e + 1] = v[1];
                }
                case COLOR -> {
                    if (type == DataType.FLOAT || type == DataType.INT || type == DataType.BOOL) {
                        float s = getFloat(e);
                        out.f[4 * e] = out.f[4 * e + 1] = out.f[4 * e + 2] = s;
                        out.f[4 * e + 3] = 1f;
                    } else {
                        getVector(e, v);
                        out.f[4 * e] = v[0];
                        out.f[4 * e + 1] = v[1];
                        out.f[4 * e + 2] = v[2];
                        out.f[4 * e + 3] = type == DataType.COLOR ? f[4 * e + 3] : 1f;
                    }
                }
                case ROTATION -> {
                    if (type == DataType.VECTOR) {
                        Transforms.eulerToQuat(f[3 * e], f[3 * e + 1], f[3 * e + 2], out.f, 4 * e);
                    } else if (type == DataType.MATRIX) {
                        Transforms.matrixToQuat(f, 16 * e, out.f, 4 * e);
                    } else {
                        out.f[4 * e] = 1f;
                    }
                }
                case MATRIX -> {
                    if (type == DataType.ROTATION) {
                        Transforms.quatToMatrix(f, 4 * e, out.f, 16 * e);
                    } else {
                        Transforms.identity(out.f, 16 * e);
                    }
                }
            }
        }
        return out;
    }

    /** The first value, as the plain Java value a node setting would hold. */
    public Object firstValue() {
        if (size == 0) {
            return null;
        }
        return switch (type) {
            case FLOAT -> f[0];
            case INT -> i[0];
            case BOOL -> i[0] != 0;
            default -> java.util.Arrays.copyOf(f, type.width);
        };
    }

    @Override
    public String toString() {
        return type + "[" + size + "]";
    }
}
