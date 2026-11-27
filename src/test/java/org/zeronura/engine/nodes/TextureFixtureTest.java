package org.zeronura.engine.nodes;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.nodes.tex.Noise;
import org.zeronura.engine.nodes.tex.RandomValue;
import org.zeronura.engine.nodes.tex.Textures;
import org.zeronura.engine.nodes.tex.Voronoi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's procedural textures and hashes against Blender's own output for the same inputs.
 *
 * <p>The reference ({@code nodes/textures.json}) is made by {@code tools/blender_nodes/make_fixtures.py}: a grid of
 * points with each texture case evaluated by Blender's geometry nodes. Every case must agree at every point.
 */
class TextureFixtureTest {

    private static final float TOLERANCE = 2e-4f;

    @SuppressWarnings("unchecked")
    @Test
    void everyTextureMatchesBlender() throws IOException {
        Map<String, Object> fixture;
        try (InputStream in = TextureFixtureTest.class.getResourceAsStream("/nodes/textures.json")) {
            fixture = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        float[] positions = floats((List<Object>) fixture.get("positions"));
        int count = positions.length / 3;
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Map<String, Object> c : Json.objects(fixture, "cases")) {
            String type = (String) c.get("type");
            Map<String, Object> props = (Map<String, Object>) c.get("props");
            Map<String, Object> inputs = (Map<String, Object>) c.get("inputs");
            Map<String, Object> outputs = (Map<String, Object>) c.get("outputs");
            for (Map.Entry<String, Object> output : outputs.entrySet()) {
                float[] expected = floats((List<Object>) output.getValue());
                int width = expected.length / count;
                float worst = 0f;
                int worstAt = -1;
                float[] got = new float[4];
                for (int i = 0; i < count; i++) {
                    float x = positions[3 * i];
                    float y = positions[3 * i + 1];
                    float z = x * 0.37f + y;
                    float w = y * -1.3f + x;
                    evaluate(type, props, inputs, output.getKey(), i, x, y, z, w, got);
                    for (int k = 0; k < width; k++) {
                        float e = expected[i * width + k];
                        float d = Math.abs(e - got[k]);
                        float scale = Math.max(1f, Math.abs(e));
                        if (d / scale > worst || Float.isNaN(got[k])) {
                            worst = Float.isNaN(got[k]) ? Float.POSITIVE_INFINITY : d / scale;
                            worstAt = i;
                        }
                    }
                }
                checked++;
                if (worst > TOLERANCE) {
                    float[] at = new float[4];
                    float x = positions[3 * worstAt];
                    float y = positions[3 * worstAt + 1];
                    evaluate(type, props, inputs, output.getKey(), worstAt, x, y, x * 0.37f + y, y * -1.3f + x, at);
                    failures.add(type + " " + props + " " + output.getKey() + ": off by " + worst + " at point "
                            + worstAt + " (blender " + slice(expected, worstAt * width, width) + ", engine "
                            + slice(at, 0, width) + ")");
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " of " + checked + " outputs differ from Blender:\n"
                + String.join("\n", failures));
    }

    private static String slice(float[] values, int from, int width) {
        StringBuilder out = new StringBuilder("[");
        for (int k = 0; k < width; k++) {
            out.append(k > 0 ? ", " : "").append(values[from + k]);
        }
        return out.append(']').toString();
    }

    private static float f(Map<String, Object> inputs, String key, float fallback) {
        return inputs.get(key) instanceof Number n ? n.floatValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static float[] v(Map<String, Object> inputs, String key) {
        return floats((List<Object>) inputs.get(key));
    }

    private static float[] position(String dims, float x, float y, float z, float w, float scale) {
        return switch (dims) {
            case "1D" -> new float[]{w * scale};
            case "2D" -> new float[]{x * scale, y * scale};
            case "3D" -> new float[]{x * scale, y * scale, z * scale};
            default -> new float[]{x * scale, y * scale, z * scale, w * scale};
        };
    }

    private static void evaluate(String type, Map<String, Object> props, Map<String, Object> inputs, String output,
                                 int index, float x, float y, float z, float w, float[] out) {
        switch (type) {
            case "ShaderNodeTexNoise" -> {
                float scale = f(inputs, "Scale", 5f);
                float[] p = position((String) props.get("noise_dimensions"), x, y, z, w, scale);
                float[] r = new float[4];
                Noise.texture(p, f(inputs, "Detail", 2f), f(inputs, "Roughness", 0.5f), f(inputs, "Lacunarity", 2f),
                        f(inputs, "Offset", 0f), f(inputs, "Gain", 1f), f(inputs, "Distortion", 0f),
                        Noise.type((String) props.get("noise_type")), Boolean.TRUE.equals(props.get("normalize")),
                        true, r);
                if (output.equals("Fac")) {
                    out[0] = r[0];
                } else {
                    out[0] = r[1];
                    out[1] = r[2];
                    out[2] = r[3];
                    out[3] = 1f;
                }
            }
            case "ShaderNodeTexVoronoi" -> {
                String dims = (String) props.get("voronoi_dimensions");
                float scale = f(inputs, "Scale", 5f);
                float[] p = position(dims, x, y, z, w, scale);
                Voronoi.Params params = new Voronoi.Params().set(p.length, scale, f(inputs, "Detail", 0f),
                        f(inputs, "Roughness", 0.5f), f(inputs, "Lacunarity", 2f), f(inputs, "Smoothness", 1f),
                        f(inputs, "Exponent", 0.5f), f(inputs, "Randomness", 1f),
                        Boolean.TRUE.equals(props.get("normalize")), Voronoi.feature((String) props.get("feature")),
                        Voronoi.metric((String) props.get("distance")));
                if (params.feature == Voronoi.DISTANCE_TO_EDGE) {
                    out[0] = Voronoi.fractalDistanceToEdge(params, p);
                } else if (params.feature == Voronoi.N_SPHERE_RADIUS) {
                    out[0] = Voronoi.nSphereRadius(params, p);
                } else {
                    Voronoi.Output o = new Voronoi.Output();
                    Voronoi.fractal(params, p, o);
                    switch (output) {
                        case "Distance" -> out[0] = o.distance;
                        case "Color" -> {
                            out[0] = o.color[0];
                            out[1] = o.color[1];
                            out[2] = o.color[2];
                            out[3] = 1f;
                        }
                        case "Position" -> {
                            out[0] = o.position[0];
                            out[1] = o.position[1];
                            out[2] = o.position[2];
                        }
                        default -> out[0] = o.position[3];
                    }
                }
            }
            case "ShaderNodeTexWhiteNoise" -> {
                float[] p = position((String) props.get("noise_dimensions"), x, y, z, w, 1f);
                float[] r = new float[4];
                Textures.whiteNoise(p, r);
                if (output.equals("Value")) {
                    out[0] = r[0];
                } else {
                    out[0] = r[1];
                    out[1] = r[2];
                    out[2] = r[3];
                    out[3] = 1f;
                }
            }
            case "ShaderNodeTexGradient" -> out[0] = Textures.gradient((String) props.get("gradient_type"), x, y, z);
            case "ShaderNodeTexChecker" -> {
                float s = f(inputs, "Scale", 5f);
                float fac = Textures.checker(x * s, y * s, z * s);
                if (output.equals("Fac")) {
                    out[0] = fac;
                } else {
                    float[] c = fac == 1f ? v(inputs, "Color1") : v(inputs, "Color2");
                    System.arraycopy(c, 0, out, 0, 4);
                }
            }
            case "ShaderNodeTexWave" -> {
                float s = f(inputs, "Scale", 5f);
                float fac = Textures.wave((String) props.get("wave_type"),
                        (String) props.getOrDefault("bands_direction", "X"),
                        (String) props.getOrDefault("rings_direction", "X"), (String) props.get("wave_profile"),
                        x * s, y * s, z * s, f(inputs, "Distortion", 0f), f(inputs, "Detail", 2f),
                        f(inputs, "Detail Scale", 1f), f(inputs, "Detail Roughness", 0.5f),
                        f(inputs, "Phase Offset", 0f));
                out[0] = out[1] = out[2] = fac;
                out[3] = 1f;
            }
            case "ShaderNodeTexMagic" -> {
                float s = f(inputs, "Scale", 5f);
                float[] r = new float[4];
                Textures.magic(x * s, y * s, z * s, ((Number) props.get("turbulence_depth")).intValue(),
                        f(inputs, "Distortion", 1f), r);
                if (output.equals("Fac")) {
                    out[0] = r[0];
                } else {
                    out[0] = r[1];
                    out[1] = r[2];
                    out[2] = r[3];
                    out[3] = 1f;
                }
            }
            case "ShaderNodeTexBrick" -> {
                float s = f(inputs, "Scale", 5f);
                float[] r = new float[2];
                Textures.brick(x * s, y * s, f(inputs, "Mortar Size", 0.02f), f(inputs, "Mortar Smooth", 0.1f),
                        f(inputs, "Bias", 0f), f(inputs, "Brick Width", 0.5f), f(inputs, "Row Height", 0.25f),
                        ((Number) props.get("offset")).floatValue(), ((Number) props.get("offset_frequency")).intValue(),
                        ((Number) props.get("squash")).floatValue(), ((Number) props.get("squash_frequency")).intValue(),
                        r);
                if (output.equals("Fac")) {
                    out[0] = r[1];
                } else {
                    float[] c1 = v(inputs, "Color1");
                    float[] c2 = v(inputs, "Color2");
                    float[] mortar = v(inputs, "Mortar");
                    float tint = r[0];
                    float[] brick = c1.clone();
                    if (r[1] != 1f) {
                        for (int k = 0; k < 4; k++) {
                            brick[k] = (1f - tint) * c1[k] + tint * c2[k];
                        }
                    }
                    for (int k = 0; k < 4; k++) {
                        out[k] = brick[k] + (mortar[k] - brick[k]) * r[1];
                    }
                }
            }
            case "FunctionNodeRandomValue" -> {
                int seed = (int) f(inputs, "Seed", 0f);
                switch ((String) props.get("data_type")) {
                    case "FLOAT" -> out[0] = RandomValue.floatValue(f(inputs, "Min", 0f), f(inputs, "Max", 1f), index, seed);
                    case "INT" -> out[0] = RandomValue.intValue((int) f(inputs, "Min", 0f), (int) f(inputs, "Max", 100f),
                            index, seed);
                    case "FLOAT_VECTOR" -> RandomValue.vectorValue(v(inputs, "Min"), v(inputs, "Max"), index, seed, out);
                    default -> out[0] = RandomValue.boolValue(f(inputs, "Probability", 0.5f), index, seed) ? 1f : 0f;
                }
            }
            case "FunctionNodeHashValue" -> {
                int seed = (int) f(inputs, "Seed", 0f);
                out[0] = switch ((String) props.get("data_type")) {
                    case "INT" -> RandomValue.hashInt(index, seed);
                    case "FLOAT" -> RandomValue.hashFloat(w, seed);
                    default -> RandomValue.hashVector(x, y, z, seed);
                };
            }
            default -> throw new IllegalArgumentException(type);
        }
    }

    private static float[] floats(List<Object> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) list.get(i)).floatValue();
        }
        return out;
    }
}
