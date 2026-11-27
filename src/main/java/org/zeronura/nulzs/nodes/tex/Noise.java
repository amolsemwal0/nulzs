package org.zeronura.nulzs.nodes.tex;

/**
 * Blender's Noise Texture: improved Perlin noise in one to four dimensions over {@link Hash}, with its five
 * fractal sums (fBm, multifractal, hybrid and ridged multifractal, hetero terrain) and its distortion.
 *
 * <p>Positions are passed as float arrays of the noise's dimension so one implementation serves all four; the
 * shader side ({@code node_lib.glsl}) mirrors this file function for function.
 */
public final class Noise {

    public static final int MULTIFRACTAL = 0;
    public static final int FBM = 1;
    public static final int HYBRID_MULTIFRACTAL = 2;
    public static final int RIDGED_MULTIFRACTAL = 3;
    public static final int HETERO_TERRAIN = 4;

    private Noise() {
    }

    public static int type(String name) {
        return switch (name) {
            case "MULTIFRACTAL" -> MULTIFRACTAL;
            case "HYBRID_MULTIFRACTAL" -> HYBRID_MULTIFRACTAL;
            case "RIDGED_MULTIFRACTAL" -> RIDGED_MULTIFRACTAL;
            case "HETERO_TERRAIN" -> HETERO_TERRAIN;
            default -> FBM;
        };
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float negateIf(float value, int condition) {
        return condition != 0 ? -value : value;
    }

    private static float grad1(int hash, float x) {
        int h = hash & 15;
        float g = 1 + (h & 7);
        return negateIf(g, h & 8) * x;
    }

    private static float grad2(int hash, float x, float y) {
        int h = hash & 7;
        float u = h < 4 ? x : y;
        float v = 2f * (h < 4 ? y : x);
        return negateIf(u, h & 1) + negateIf(v, h & 2);
    }

    private static float grad3(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float vt = (h == 12 || h == 14) ? x : z;
        float v = h < 4 ? y : vt;
        return negateIf(u, h & 1) + negateIf(v, h & 2);
    }

    private static float grad4(int hash, float x, float y, float z, float w) {
        int h = hash & 31;
        float u = h < 24 ? x : y;
        float v = h < 16 ? y : z;
        float s = h < 8 ? z : w;
        return negateIf(u, h & 1) + negateIf(v, h & 2) + negateIf(s, h & 4);
    }

    private static float mix(float v0, float v1, float x) {
        return (1f - x) * v0 + x * v1;
    }

    private static float biMix(float v0, float v1, float v2, float v3, float x, float y) {
        float x1 = 1f - x;
        return (1f - y) * (v0 * x1 + v1 * x) + y * (v2 * x1 + v3 * x);
    }

    private static float triMix(float v0, float v1, float v2, float v3, float v4, float v5, float v6, float v7,
                                float x, float y, float z) {
        float x1 = 1f - x;
        float y1 = 1f - y;
        float z1 = 1f - z;
        return z1 * (y1 * (v0 * x1 + v1 * x) + y * (v2 * x1 + v3 * x))
                + z * (y1 * (v4 * x1 + v5 * x) + y * (v6 * x1 + v7 * x));
    }

    static float perlin1(float x) {
        int ix = (int) Math.floor(x);
        float fx = x - ix;
        float u = fade(fx);
        return mix(grad1(Hash.hash(ix), fx), grad1(Hash.hash(ix + 1), fx - 1f), u);
    }

    static float perlin2(float x, float y) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        float fx = x - ix;
        float fy = y - iy;
        float u = fade(fx);
        float v = fade(fy);
        return biMix(grad2(Hash.hash(ix, iy), fx, fy), grad2(Hash.hash(ix + 1, iy), fx - 1f, fy),
                grad2(Hash.hash(ix, iy + 1), fx, fy - 1f), grad2(Hash.hash(ix + 1, iy + 1), fx - 1f, fy - 1f), u, v);
    }

    static float perlin3(float x, float y, float z) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        float fx = x - ix;
        float fy = y - iy;
        float fz = z - iz;
        float u = fade(fx);
        float v = fade(fy);
        float w = fade(fz);
        return triMix(grad3(Hash.hash(ix, iy, iz), fx, fy, fz),
                grad3(Hash.hash(ix + 1, iy, iz), fx - 1f, fy, fz),
                grad3(Hash.hash(ix, iy + 1, iz), fx, fy - 1f, fz),
                grad3(Hash.hash(ix + 1, iy + 1, iz), fx - 1f, fy - 1f, fz),
                grad3(Hash.hash(ix, iy, iz + 1), fx, fy, fz - 1f),
                grad3(Hash.hash(ix + 1, iy, iz + 1), fx - 1f, fy, fz - 1f),
                grad3(Hash.hash(ix, iy + 1, iz + 1), fx, fy - 1f, fz - 1f),
                grad3(Hash.hash(ix + 1, iy + 1, iz + 1), fx - 1f, fy - 1f, fz - 1f), u, v, w);
    }

    static float perlin4(float x, float y, float z, float w) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        int iw = (int) Math.floor(w);
        float fx = x - ix;
        float fy = y - iy;
        float fz = z - iz;
        float fw = w - iw;
        float u = fade(fx);
        float v = fade(fy);
        float t = fade(fz);
        float s = fade(fw);
        float low = triMix(grad4(Hash.hash(ix, iy, iz, iw), fx, fy, fz, fw),
                grad4(Hash.hash(ix + 1, iy, iz, iw), fx - 1f, fy, fz, fw),
                grad4(Hash.hash(ix, iy + 1, iz, iw), fx, fy - 1f, fz, fw),
                grad4(Hash.hash(ix + 1, iy + 1, iz, iw), fx - 1f, fy - 1f, fz, fw),
                grad4(Hash.hash(ix, iy, iz + 1, iw), fx, fy, fz - 1f, fw),
                grad4(Hash.hash(ix + 1, iy, iz + 1, iw), fx - 1f, fy, fz - 1f, fw),
                grad4(Hash.hash(ix, iy + 1, iz + 1, iw), fx, fy - 1f, fz - 1f, fw),
                grad4(Hash.hash(ix + 1, iy + 1, iz + 1, iw), fx - 1f, fy - 1f, fz - 1f, fw), u, v, t);
        float high = triMix(grad4(Hash.hash(ix, iy, iz, iw + 1), fx, fy, fz, fw - 1f),
                grad4(Hash.hash(ix + 1, iy, iz, iw + 1), fx - 1f, fy, fz, fw - 1f),
                grad4(Hash.hash(ix, iy + 1, iz, iw + 1), fx, fy - 1f, fz, fw - 1f),
                grad4(Hash.hash(ix + 1, iy + 1, iz, iw + 1), fx - 1f, fy - 1f, fz, fw - 1f),
                grad4(Hash.hash(ix, iy, iz + 1, iw + 1), fx, fy, fz - 1f, fw - 1f),
                grad4(Hash.hash(ix + 1, iy, iz + 1, iw + 1), fx - 1f, fy, fz - 1f, fw - 1f),
                grad4(Hash.hash(ix, iy + 1, iz + 1, iw + 1), fx, fy - 1f, fz - 1f, fw - 1f),
                grad4(Hash.hash(ix + 1, iy + 1, iz + 1, iw + 1), fx - 1f, fy - 1f, fz - 1f, fw - 1f), u, v, t);
        return mix(low, high, s);
    }

    /** Repeats every 100000 units, as Blender does, so huge coordinates stay precise. */
    private static float wrap(float x) {
        return x % 100000f;
    }

    /** Signed noise, roughly in [-1, 1], at a position of 1 to 4 components. */
    public static float signed(float[] p) {
        return switch (p.length) {
            case 1 -> 0.25f * perlin1(wrap(p[0]));
            case 2 -> 0.6616f * perlin2(wrap(p[0]), wrap(p[1]));
            case 3 -> 0.9820f * perlin3(wrap(p[0]), wrap(p[1]), wrap(p[2]));
            default -> 0.8344f * perlin4(wrap(p[0]), wrap(p[1]), wrap(p[2]), wrap(p[3]));
        };
    }

    private static float signedScaled(float[] p, float scale, float[] scratch) {
        for (int i = 0; i < p.length; i++) {
            scratch[i] = p[i] * scale;
        }
        return signed(scratch);
    }

    public static float fbm(float[] p, float detail, float roughness, float lacunarity, boolean normalize) {
        float[] scratch = new float[p.length];
        float fscale = 1f;
        float amp = 1f;
        float maxamp = 0f;
        float sum = 0f;
        for (int i = 0; i <= (int) detail; i++) {
            float t = signedScaled(p, fscale, scratch);
            sum += t * amp;
            maxamp += amp;
            amp *= roughness;
            fscale *= lacunarity;
        }
        float rmd = detail - (float) Math.floor(detail);
        if (rmd != 0f) {
            float t = signedScaled(p, fscale, scratch);
            float sum2 = sum + t * amp;
            return normalize ? mix(0.5f * sum / maxamp + 0.5f, 0.5f * sum2 / (maxamp + amp) + 0.5f, rmd)
                    : mix(sum, sum2, rmd);
        }
        return normalize ? 0.5f * sum / maxamp + 0.5f : sum;
    }

    public static float multiFractal(float[] co, float detail, float roughness, float lacunarity) {
        float[] p = co.clone();
        float value = 1f;
        float pwr = 1f;
        for (int i = 0; i <= (int) detail; i++) {
            value *= pwr * signed(p) + 1f;
            pwr *= roughness;
            scale(p, lacunarity);
        }
        float rmd = detail - (float) Math.floor(detail);
        if (rmd != 0f) {
            value *= rmd * pwr * signed(p) + 1f;
        }
        return value;
    }

    public static float heteroTerrain(float[] co, float detail, float roughness, float lacunarity, float offset) {
        float[] p = co.clone();
        float pwr = roughness;
        float value = offset + signed(p);
        scale(p, lacunarity);
        for (int i = 1; i <= (int) detail; i++) {
            float increment = (signed(p) + offset) * pwr * value;
            value += increment;
            pwr *= roughness;
            scale(p, lacunarity);
        }
        float rmd = detail - (float) Math.floor(detail);
        if (rmd != 0f) {
            float increment = (signed(p) + offset) * pwr * value;
            value += rmd * increment;
        }
        return value;
    }

    public static float hybridMultiFractal(float[] co, float detail, float roughness, float lacunarity, float offset,
                                           float gain) {
        float[] p = co.clone();
        float pwr = 1f;
        float value = 0f;
        float weight = 1f;
        for (int i = 0; weight > 0.001f && i <= (int) detail; i++) {
            if (weight > 1f) {
                weight = 1f;
            }
            float signal = (signed(p) + offset) * pwr;
            pwr *= roughness;
            value += weight * signal;
            weight *= gain * signal;
            scale(p, lacunarity);
        }
        float rmd = detail - (float) Math.floor(detail);
        if (rmd != 0f && weight > 0.001f) {
            if (weight > 1f) {
                weight = 1f;
            }
            float signal = (signed(p) + offset) * pwr;
            value += rmd * weight * signal;
        }
        return value;
    }

    public static float ridgedMultiFractal(float[] co, float detail, float roughness, float lacunarity, float offset,
                                           float gain) {
        float[] p = co.clone();
        float pwr = roughness;
        float signal = offset - Math.abs(signed(p));
        signal *= signal;
        float value = signal;
        float weight;
        for (int i = 1; i <= (int) detail; i++) {
            scale(p, lacunarity);
            weight = Math.clamp(signal * gain, 0f, 1f);
            signal = offset - Math.abs(signed(p));
            signal *= signal;
            signal *= weight;
            value += signal * pwr;
            pwr *= roughness;
        }
        return value;
    }

    private static void scale(float[] p, float s) {
        for (int i = 0; i < p.length; i++) {
            p[i] *= s;
        }
    }

    public static float select(float[] p, float detail, float roughness, float lacunarity, float offset, float gain,
                               int type, boolean normalize) {
        return switch (type) {
            case MULTIFRACTAL -> multiFractal(p, detail, roughness, lacunarity);
            case HYBRID_MULTIFRACTAL -> hybridMultiFractal(p, detail, roughness, lacunarity, offset, gain);
            case RIDGED_MULTIFRACTAL -> ridgedMultiFractal(p, detail, roughness, lacunarity, offset, gain);
            case HETERO_TERRAIN -> heteroTerrain(p, detail, roughness, lacunarity, offset);
            default -> fbm(p, detail, roughness, lacunarity, normalize);
        };
    }

    private static void offsetFor(int dims, float seed, float[] out) {
        switch (dims) {
            case 1 -> out[0] = Hash.randomFloatOffset(seed);
            case 2 -> Hash.randomVec2Offset(seed, out);
            case 3 -> Hash.randomVec3Offset(seed, out);
            default -> Hash.randomVec4Offset(seed, out);
        }
    }

    /**
     * The Noise Texture node. {@code position} is already multiplied by Scale and holds as many components as
     * the node's dimensions. Writes Fac to {@code out[0]} and Color to {@code out[1..3]} when {@code color}.
     */
    public static void texture(float[] position, float detail, float roughness, float lacunarity, float offset,
                               float gain, float distortion, int type, boolean normalize, boolean color, float[] out) {
        int dims = position.length;
        detail = Math.clamp(detail, 0f, 15f);
        roughness = Math.max(roughness, 0f);
        float[] p = position.clone();
        float[] off = new float[dims];
        float[] tmp = new float[dims];
        if (distortion != 0f) {
            float[] shift = new float[dims];
            for (int axis = 0; axis < dims; axis++) {
                offsetFor(dims, axis, off);
                for (int i = 0; i < dims; i++) {
                    tmp[i] = p[i] + off[i];
                }
                shift[axis] = signed(tmp) * distortion;
            }
            for (int i = 0; i < dims; i++) {
                p[i] += shift[i];
            }
        }
        out[0] = select(p, detail, roughness, lacunarity, offset, gain, type, normalize);
        if (color) {
            for (int c = 0; c < 2; c++) {
                offsetFor(dims, dims + c, off);
                for (int i = 0; i < dims; i++) {
                    tmp[i] = p[i] + off[i];
                }
                out[2 + c] = select(tmp, detail, roughness, lacunarity, offset, gain, type, normalize);
            }
            out[1] = out[0];
        }
    }

    /** Blender's normalised fBm with a lacunarity of 2, which the Wave Texture distorts with. */
    public static float fractal3(float x, float y, float z, float detail, float roughness) {
        return fbm(new float[]{x, y, z}, Math.clamp(detail, 0f, 15f), Math.max(roughness, 0f), 2f, true);
    }
}
