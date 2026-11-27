package org.zeronura.engine.nodes.tex;

/**
 * The Math, Vector Math, Map Range and Clamp nodes' operations, with Blender's "safe" variants (division by
 * zero gives zero, the square root of a negative gives zero, and so on).
 */
public final class NodeMath {

    private NodeMath() {
    }

    public static float safeDivide(float a, float b) {
        return b != 0f ? a / b : 0f;
    }

    public static float safeMod(float a, float b) {
        return b != 0f ? a % b : 0f;
    }

    public static float flooredMod(float a, float b) {
        return b != 0f ? a - (float) Math.floor(a / b) * b : 0f;
    }

    public static float safePow(float a, float b) {
        if (a < 0f && b != (float) Math.floor(b)) {
            return 0f;
        }
        return (float) Math.pow(a, b);
    }

    public static float safeLog(float a, float b) {
        if (a <= 0f || b <= 0f) {
            return 0f;
        }
        return safeDivide((float) Math.log(a), (float) Math.log(b));
    }

    public static float wrap(float value, float max, float min) {
        float range = max - min;
        return range != 0f ? value - range * (float) Math.floor((value - min) / range) : min;
    }

    public static float pingpong(float a, float b) {
        if (b == 0f) {
            return 0f;
        }
        float x = (a - b) / (b * 2f);
        return Math.abs((x - (float) Math.floor(x)) * b * 2f - b);
    }

    public static float smoothMin(float a, float b, float c) {
        if (c != 0f) {
            float h = Math.max(c - Math.abs(a - b), 0f) / c;
            return Math.min(a, b) - h * h * h * c * (1f / 6f);
        }
        return Math.min(a, b);
    }

    public static float math(String op, float a, float b, float c) {
        return switch (op) {
            case "ADD" -> a + b;
            case "SUBTRACT" -> a - b;
            case "MULTIPLY" -> a * b;
            case "DIVIDE" -> safeDivide(a, b);
            case "MULTIPLY_ADD" -> a * b + c;
            case "POWER" -> safePow(a, b);
            case "LOGARITHM" -> safeLog(a, b);
            case "SQRT" -> a > 0f ? (float) Math.sqrt(a) : 0f;
            case "INVERSE_SQRT" -> a > 0f ? 1f / (float) Math.sqrt(a) : 0f;
            case "ABSOLUTE" -> Math.abs(a);
            case "EXPONENT" -> (float) Math.exp(a);
            case "MINIMUM" -> Math.min(a, b);
            case "MAXIMUM" -> Math.max(a, b);
            case "LESS_THAN" -> a < b ? 1f : 0f;
            case "GREATER_THAN" -> a > b ? 1f : 0f;
            case "SIGN" -> Math.signum(a);
            case "COMPARE" -> Math.abs(a - b) <= Math.max(c, 1e-5f) ? 1f : 0f;
            case "SMOOTH_MIN" -> smoothMin(a, b, c);
            case "SMOOTH_MAX" -> -smoothMin(-a, -b, c);
            case "ROUND" -> (float) Math.floor(a + 0.5f);
            case "FLOOR" -> (float) Math.floor(a);
            case "CEIL" -> (float) Math.ceil(a);
            case "TRUNC" -> a >= 0f ? (float) Math.floor(a) : (float) Math.ceil(a);
            case "FRACT" -> a - (float) Math.floor(a);
            case "MODULO" -> safeMod(a, b);
            case "FLOORED_MODULO" -> flooredMod(a, b);
            case "WRAP" -> wrap(a, b, c);
            case "SNAP" -> (float) Math.floor(safeDivide(a, b)) * b;
            case "PINGPONG" -> pingpong(a, b);
            case "SINE" -> (float) Math.sin(a);
            case "COSINE" -> (float) Math.cos(a);
            case "TANGENT" -> (float) Math.tan(a);
            case "ARCSINE" -> (float) Math.asin(Math.clamp(a, -1f, 1f));
            case "ARCCOSINE" -> (float) Math.acos(Math.clamp(a, -1f, 1f));
            case "ARCTANGENT" -> (float) Math.atan(a);
            case "ARCTAN2" -> (float) Math.atan2(a, b);
            case "SINH" -> (float) Math.sinh(a);
            case "COSH" -> (float) Math.cosh(a);
            case "TANH" -> (float) Math.tanh(a);
            case "RADIANS" -> (float) Math.toRadians(a);
            case "DEGREES" -> (float) Math.toDegrees(a);
            default -> 0f;
        };
    }

    /**
     * Vector Math. Writes the vector result to {@code out[0..2]} and the scalar result (Dot Product, Distance,
     * Length) to {@code out[3]}.
     */
    public static void vectorMath(String op, float[] a, float[] b, float[] c, float scale, float[] out) {
        out[3] = 0f;
        switch (op) {
            case "ADD" -> set(out, a[0] + b[0], a[1] + b[1], a[2] + b[2]);
            case "SUBTRACT" -> set(out, a[0] - b[0], a[1] - b[1], a[2] - b[2]);
            case "MULTIPLY" -> set(out, a[0] * b[0], a[1] * b[1], a[2] * b[2]);
            case "DIVIDE" -> set(out, safeDivide(a[0], b[0]), safeDivide(a[1], b[1]), safeDivide(a[2], b[2]));
            case "MULTIPLY_ADD" -> set(out, a[0] * b[0] + c[0], a[1] * b[1] + c[1], a[2] * b[2] + c[2]);
            case "CROSS_PRODUCT" -> set(out, a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
            case "PROJECT" -> {
                float len2 = dot(b, b);
                float s = len2 != 0f ? dot(a, b) / len2 : 0f;
                set(out, b[0] * s, b[1] * s, b[2] * s);
            }
            case "REFLECT" -> {
                float[] n = normalized(b);
                float d = 2f * dot(n, a);
                set(out, a[0] - d * n[0], a[1] - d * n[1], a[2] - d * n[2]);
            }
            case "REFRACT" -> {
                float[] n = normalized(b);
                float dotNi = dot(n, a);
                float k = 1f - scale * scale * (1f - dotNi * dotNi);
                if (k < 0f) {
                    set(out, 0f, 0f, 0f);
                } else {
                    float s = scale * dotNi + (float) Math.sqrt(k);
                    set(out, scale * a[0] - s * n[0], scale * a[1] - s * n[1], scale * a[2] - s * n[2]);
                }
            }
            case "FACEFORWARD" -> {
                float sign = dot(c, b) < 0f ? 1f : -1f;
                set(out, a[0] * sign, a[1] * sign, a[2] * sign);
            }
            case "DOT_PRODUCT" -> {
                set(out, 0f, 0f, 0f);
                out[3] = dot(a, b);
            }
            case "DISTANCE" -> {
                set(out, 0f, 0f, 0f);
                float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
                out[3] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            case "LENGTH" -> {
                set(out, 0f, 0f, 0f);
                out[3] = (float) Math.sqrt(dot(a, a));
            }
            case "SCALE" -> set(out, a[0] * scale, a[1] * scale, a[2] * scale);
            case "NORMALIZE" -> {
                float[] n = normalized(a);
                set(out, n[0], n[1], n[2]);
            }
            case "ABSOLUTE" -> set(out, Math.abs(a[0]), Math.abs(a[1]), Math.abs(a[2]));
            case "POWER" -> set(out, safePow(a[0], b[0]), safePow(a[1], b[1]), safePow(a[2], b[2]));
            case "SIGN" -> set(out, Math.signum(a[0]), Math.signum(a[1]), Math.signum(a[2]));
            case "MINIMUM" -> set(out, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]));
            case "MAXIMUM" -> set(out, Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
            case "ROUND" -> set(out, (float) Math.floor(a[0] + 0.5f), (float) Math.floor(a[1] + 0.5f), (float) Math.floor(a[2] + 0.5f));
            case "FLOOR" -> set(out, (float) Math.floor(a[0]), (float) Math.floor(a[1]), (float) Math.floor(a[2]));
            case "CEIL" -> set(out, (float) Math.ceil(a[0]), (float) Math.ceil(a[1]), (float) Math.ceil(a[2]));
            case "FRACTION" -> set(out, a[0] - (float) Math.floor(a[0]), a[1] - (float) Math.floor(a[1]),
                    a[2] - (float) Math.floor(a[2]));
            case "MODULO" -> set(out, safeMod(a[0], b[0]), safeMod(a[1], b[1]), safeMod(a[2], b[2]));
            case "WRAP" -> set(out, wrap(a[0], b[0], c[0]), wrap(a[1], b[1], c[1]), wrap(a[2], b[2], c[2]));
            case "SNAP" -> set(out, (float) Math.floor(safeDivide(a[0], b[0])) * b[0],
                    (float) Math.floor(safeDivide(a[1], b[1])) * b[1], (float) Math.floor(safeDivide(a[2], b[2])) * b[2]);
            case "SINE" -> set(out, (float) Math.sin(a[0]), (float) Math.sin(a[1]), (float) Math.sin(a[2]));
            case "COSINE" -> set(out, (float) Math.cos(a[0]), (float) Math.cos(a[1]), (float) Math.cos(a[2]));
            case "TANGENT" -> set(out, (float) Math.tan(a[0]), (float) Math.tan(a[1]), (float) Math.tan(a[2]));
            default -> set(out, 0f, 0f, 0f);
        }
    }

    public static boolean vectorMathGivesValue(String op) {
        return op.equals("DOT_PRODUCT") || op.equals("DISTANCE") || op.equals("LENGTH");
    }

    private static void set(float[] out, float x, float y, float z) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
    }

    public static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static float[] normalized(float[] a) {
        float len = (float) Math.sqrt(dot(a, a));
        return len != 0f ? new float[]{a[0] / len, a[1] / len, a[2] / len} : new float[]{0f, 0f, 0f};
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        if (x < edge0) {
            return 0f;
        }
        if (x >= edge1) {
            return 1f;
        }
        float t = (x - edge0) / (edge1 - edge0);
        return (3f - 2f * t) * (t * t);
    }

    public static float smootherstep(float edge0, float edge1, float x) {
        x = Math.clamp(safeDivide(x - edge0, edge1 - edge0), 0f, 1f);
        return x * x * x * (x * (x * 6f - 15f) + 10f);
    }

    public static float mapRange(String interpolation, float value, float fromMin, float fromMax, float toMin,
                                 float toMax, float steps, boolean clamp) {
        float factor;
        float result;
        switch (interpolation) {
            case "STEPPED" -> {
                factor = safeDivide(value - fromMin, fromMax - fromMin);
                factor = steps > 0f ? (float) Math.floor(factor * (steps + 1f)) / steps : 0f;
                result = toMin + factor * (toMax - toMin);
            }
            case "SMOOTHSTEP" -> {
                factor = fromMin > fromMax ? 1f - smoothstep(fromMax, fromMin, value) : smoothstep(fromMin, fromMax, value);
                return toMin + factor * (toMax - toMin);
            }
            case "SMOOTHERSTEP" -> {
                factor = fromMin > fromMax ? 1f - smootherstep(fromMax, fromMin, value) : smootherstep(fromMin, fromMax, value);
                return toMin + factor * (toMax - toMin);
            }
            default -> {
                factor = safeDivide(value - fromMin, fromMax - fromMin);
                result = toMin + factor * (toMax - toMin);
            }
        }
        if (clamp) {
            result = toMin > toMax ? Math.clamp(result, toMax, toMin) : Math.clamp(result, toMin, toMax);
        }
        return result;
    }

    public static float clamp(String type, float value, float min, float max) {
        if ("RANGE".equals(type) && min > max) {
            return Math.clamp(value, max, min);
        }
        return Math.min(Math.max(value, min), max);
    }

    /** The Vector Rotate node: {@code out = rotate(vector - center) + center}. */
    public static void vectorRotate(String type, boolean invert, float[] vector, float[] center, float[] axis,
                                    float angle, float[] euler, float[] out) {
        float x = vector[0] - center[0], y = vector[1] - center[1], z = vector[2] - center[2];
        double[] m;
        switch (type) {
            case "X_AXIS" -> m = axisMatrix(1, 0, 0, invert ? -angle : angle);
            case "Y_AXIS" -> m = axisMatrix(0, 1, 0, invert ? -angle : angle);
            case "Z_AXIS" -> m = axisMatrix(0, 0, 1, invert ? -angle : angle);
            case "EULER_XYZ" -> {
                m = eulerMatrix(euler[0], euler[1], euler[2]);
                if (invert) {
                    m = transpose(m);
                }
            }
            default -> {
                float[] n = normalized(axis);
                if (n[0] == 0f && n[1] == 0f && n[2] == 0f) {
                    System.arraycopy(vector, 0, out, 0, 3);
                    return;
                }
                m = axisMatrix(n[0], n[1], n[2], invert ? -angle : angle);
            }
        }
        out[0] = (float) (m[0] * x + m[3] * y + m[6] * z) + center[0];
        out[1] = (float) (m[1] * x + m[4] * y + m[7] * z) + center[1];
        out[2] = (float) (m[2] * x + m[5] * y + m[8] * z) + center[2];
    }

    /** Rotation about a unit axis, as a column-major 3x3. */
    public static double[] axisMatrix(double x, double y, double z, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), t = 1 - c;
        return new double[]{
                t * x * x + c, t * x * y + s * z, t * x * z - s * y,
                t * x * y - s * z, t * y * y + c, t * y * z + s * x,
                t * x * z + s * y, t * y * z - s * x, t * z * z + c};
    }

    /** Euler XYZ (X first) as a column-major 3x3: Rz * Ry * Rx. */
    public static double[] eulerMatrix(double ex, double ey, double ez) {
        double ci = Math.cos(ex), cj = Math.cos(ey), ch = Math.cos(ez);
        double si = Math.sin(ex), sj = Math.sin(ey), sh = Math.sin(ez);
        double cc = ci * ch, cs = ci * sh, sc = si * ch, ss = si * sh;
        return new double[]{
                cj * ch, cj * sh, -sj,
                sj * sc - cs, sj * ss + cc, cj * si,
                sj * cc + ss, sj * cs - sc, cj * ci};
    }

    public static double[] transpose(double[] m) {
        return new double[]{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    /** The Mapping node. */
    public static void mapping(String type, float[] v, float[] location, float[] rotation, float[] scale, float[] out) {
        double[] r = eulerMatrix(rotation[0], rotation[1], rotation[2]);
        switch (type) {
            case "TEXTURE" -> {
                double[] t = transpose(r);
                float x = v[0] - location[0], y = v[1] - location[1], z = v[2] - location[2];
                out[0] = safeDivide((float) (t[0] * x + t[3] * y + t[6] * z), scale[0]);
                out[1] = safeDivide((float) (t[1] * x + t[4] * y + t[7] * z), scale[1]);
                out[2] = safeDivide((float) (t[2] * x + t[5] * y + t[8] * z), scale[2]);
            }
            case "VECTOR" -> {
                float x = v[0] * scale[0], y = v[1] * scale[1], z = v[2] * scale[2];
                out[0] = (float) (r[0] * x + r[3] * y + r[6] * z);
                out[1] = (float) (r[1] * x + r[4] * y + r[7] * z);
                out[2] = (float) (r[2] * x + r[5] * y + r[8] * z);
            }
            case "NORMAL" -> {
                float x = safeDivide(v[0], scale[0]), y = safeDivide(v[1], scale[1]), z = safeDivide(v[2], scale[2]);
                float[] n = {(float) (r[0] * x + r[3] * y + r[6] * z), (float) (r[1] * x + r[4] * y + r[7] * z),
                        (float) (r[2] * x + r[5] * y + r[8] * z)};
                float[] nn = normalized(n);
                System.arraycopy(nn, 0, out, 0, 3);
            }
            default -> {
                float x = v[0] * scale[0], y = v[1] * scale[1], z = v[2] * scale[2];
                out[0] = (float) (r[0] * x + r[3] * y + r[6] * z) + location[0];
                out[1] = (float) (r[1] * x + r[4] * y + r[7] * z) + location[1];
                out[2] = (float) (r[2] * x + r[5] * y + r[8] * z) + location[2];
            }
        }
    }
}
