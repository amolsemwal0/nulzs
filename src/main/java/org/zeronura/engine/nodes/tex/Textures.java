package org.zeronura.engine.nodes.tex;

/**
 * Blender's smaller procedural textures: White Noise, Gradient, Checker, Wave, Magic and Brick, each as the
 * node computes it after multiplying its vector by Scale (where it has one).
 */
public final class Textures {

    private Textures() {
    }

    // --- White noise ---------------------------------------------------------------------------------

    /** Value into {@code out[0]}, colour into {@code out[1..3]}. {@code p} holds the node's 1-4 components. */
    public static void whiteNoise(float[] p, float[] out) {
        switch (p.length) {
            case 1 -> {
                out[0] = Hash.floatToFloat(p[0]);
                out[1] = out[0];
                out[2] = Hash.vec2ToFloat(p[0], 1f);
                out[3] = Hash.vec2ToFloat(p[0], 2f);
            }
            case 2 -> {
                out[0] = Hash.vec2ToFloat(p[0], p[1]);
                out[1] = out[0];
                out[2] = Hash.vec3ToFloat(p[0], p[1], 1f);
                out[3] = Hash.vec3ToFloat(p[0], p[1], 2f);
            }
            case 3 -> {
                out[0] = Hash.vec3ToFloat(p[0], p[1], p[2]);
                out[1] = out[0];
                out[2] = Hash.vec4ToFloat(p[0], p[1], p[2], 1f);
                out[3] = Hash.vec4ToFloat(p[0], p[1], p[2], 2f);
            }
            default -> {
                out[0] = Hash.vec4ToFloat(p[0], p[1], p[2], p[3]);
                out[1] = out[0];
                out[2] = Hash.vec4ToFloat(p[2], p[0], p[3], p[1]);
                out[3] = Hash.vec4ToFloat(p[3], p[2], p[1], p[0]);
            }
        }
    }

    // --- Gradient ------------------------------------------------------------------------------------

    public static float gradient(String type, float x, float y, float z) {
        float value = switch (type) {
            case "QUADRATIC" -> {
                float r = Math.max(x, 0f);
                yield r * r;
            }
            case "EASING" -> {
                float r = Math.clamp(x, 0f, 1f);
                float t = r * r;
                yield 3f * t - 2f * t * r;
            }
            case "DIAGONAL" -> (x + y) * 0.5f;
            case "RADIAL" -> (float) (Math.atan2(y, x) / (2.0 * Math.PI) + 0.5);
            case "QUADRATIC_SPHERE" -> {
                float r = Math.max(0.999999f - (float) Math.sqrt(x * x + y * y + z * z), 0f);
                yield r * r;
            }
            case "SPHERICAL" -> Math.max(0.999999f - (float) Math.sqrt(x * x + y * y + z * z), 0f);
            default -> x;
        };
        return Math.clamp(value, 0f, 1f);
    }

    // --- Checker -------------------------------------------------------------------------------------

    public static float checker(float x, float y, float z) {
        x = (x + 0.000001f) * 0.999999f;
        y = (y + 0.000001f) * 0.999999f;
        z = (z + 0.000001f) * 0.999999f;
        int xi = Math.abs((int) Math.floor(x));
        int yi = Math.abs((int) Math.floor(y));
        int zi = Math.abs((int) Math.floor(z));
        int sameParity = (xi % 2 == yi % 2) ? 1 : 0;
        return sameParity == zi % 2 ? 1f : 0f;
    }

    // --- Wave ----------------------------------------------------------------------------------------

    public static float wave(String type, String bands, String rings, String profile, float x, float y, float z,
                             float distortion, float detail, float detailScale, float detailRoughness, float phase) {
        x = (x + 0.000001f) * 0.999999f;
        y = (y + 0.000001f) * 0.999999f;
        z = (z + 0.000001f) * 0.999999f;
        float n;
        if ("RINGS".equals(type)) {
            float rx = x, ry = y, rz = z;
            switch (rings) {
                case "X" -> rx = 0f;
                case "Y" -> ry = 0f;
                case "Z" -> rz = 0f;
                default -> {
                }
            }
            n = (float) Math.sqrt(rx * rx + ry * ry + rz * rz) * 20f;
        } else {
            n = switch (bands) {
                case "Y" -> y * 20f;
                case "Z" -> z * 20f;
                case "DIAGONAL" -> (x + y + z) * 10f;
                default -> x * 20f;
            };
        }
        n += phase;
        if (distortion != 0f) {
            n += distortion * (Noise.fractal3(x * detailScale, y * detailScale, z * detailScale, detail,
                    detailRoughness) * 2f - 1f);
        }
        switch (profile) {
            case "SAW" -> {
                n /= (float) (2.0 * Math.PI);
                return n - (float) Math.floor(n);
            }
            case "TRI" -> {
                n /= (float) (2.0 * Math.PI);
                return Math.abs(n - (float) Math.floor(n + 0.5f)) * 2f;
            }
            default -> {
                return 0.5f + 0.5f * (float) Math.sin(n - Math.PI / 2.0);
            }
        }
    }

    // --- Magic ---------------------------------------------------------------------------------------

    public static void magic(float px, float py, float pz, int depth, float distortion, float[] out) {
        float twoPi = (float) (2.0 * Math.PI);
        px = px - twoPi * (float) Math.floor(px / twoPi);
        py = py - twoPi * (float) Math.floor(py / twoPi);
        pz = pz - twoPi * (float) Math.floor(pz / twoPi);
        float x = (float) Math.sin((px + py + pz) * 5f);
        float y = (float) Math.cos((-px + py - pz) * 5f);
        float z = (float) -Math.cos((-px - py + pz) * 5f);
        if (depth > 0) {
            x *= distortion;
            y *= distortion;
            z *= distortion;
            y = (float) -Math.cos(x - y + z);
            y *= distortion;
            if (depth > 1) {
                x = (float) Math.cos(x - y - z);
                x *= distortion;
                if (depth > 2) {
                    z = (float) Math.sin(-x - y - z);
                    z *= distortion;
                    if (depth > 3) {
                        x = (float) -Math.cos(-x + y - z);
                        x *= distortion;
                        if (depth > 4) {
                            y = (float) -Math.sin(-x + y + z);
                            y *= distortion;
                            if (depth > 5) {
                                y = (float) -Math.cos(-x + y + z);
                                y *= distortion;
                                if (depth > 6) {
                                    x = (float) Math.cos(x + y + z);
                                    x *= distortion;
                                    if (depth > 7) {
                                        z = (float) Math.sin(x + y - z);
                                        z *= distortion;
                                        if (depth > 8) {
                                            x = (float) -Math.cos(-x - y + z);
                                            x *= distortion;
                                            if (depth > 9) {
                                                y = (float) -Math.sin(x - y + z);
                                                y *= distortion;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (distortion != 0f) {
            distortion *= 2f;
            x /= distortion;
            y /= distortion;
            z /= distortion;
        }
        out[1] = 0.5f - x;
        out[2] = 0.5f - y;
        out[3] = 0.5f - z;
        out[0] = (out[1] + out[2] + out[3]) * (1f / 3f);
    }

    // --- Brick ---------------------------------------------------------------------------------------

    private static float brickNoise(int n) {
        n = (n + 1013) & 0x7fffffff;
        n = (n >>> 13) ^ n;
        int nn = (n * (n * n * 60493 + 19990303) + 1376312589) & 0x7fffffff;
        return 0.5f * ((float) nn / 1073741824f);
    }

    /** Tint into {@code out[0]} and mortar into {@code out[1]}. */
    public static void brick(float px, float py, float mortarSize, float mortarSmooth, float bias, float brickWidth,
                             float rowHeight, float offsetAmount, int offsetFrequency, float squashAmount,
                             int squashFrequency, float[] out) {
        float offset = 0f;
        int rownum = (int) Math.floor(py / rowHeight);
        if (offsetFrequency != 0 && squashFrequency != 0) {
            brickWidth *= (rownum % squashFrequency) != 0 ? 1f : squashAmount;
            offset = (rownum % offsetFrequency) != 0 ? 0f : brickWidth * offsetAmount;
        }
        int bricknum = (int) Math.floor((px + offset) / brickWidth);
        float x = (px + offset) - brickWidth * bricknum;
        float y = py - rowHeight * rownum;
        float tint = Math.clamp(brickNoise((rownum << 16) + (bricknum & 0xFFFF)) + bias, 0f, 1f);
        float minDist = Math.min(Math.min(x, y), Math.min(brickWidth - x, rowHeight - y));
        float mortar;
        if (minDist >= mortarSize) {
            mortar = 0f;
        } else if (mortarSmooth == 0f) {
            mortar = 1f;
        } else {
            minDist = 1f - minDist / mortarSize;
            if (minDist < mortarSmooth) {
                float t = minDist / mortarSmooth;
                mortar = t * t * (3f - 2f * t);
            } else {
                mortar = 1f;
            }
        }
        out[0] = tint;
        out[1] = mortar;
    }
}
