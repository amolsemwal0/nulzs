package org.zeronura.engine.nodes.geometry;

import org.zeronura.engine.nodes.Ref;
import org.zeronura.engine.nodes.tex.Noise;
import org.zeronura.engine.nodes.tex.Textures;
import org.zeronura.engine.nodes.tex.Voronoi;

import java.util.Map;

/** Procedural textures evaluated per element on the CPU, for geometry trees. */
final class TextureNodes {

    private TextureNodes() {
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("ShaderNodeTexNoise", TextureNodes::noise);
        map.put("ShaderNodeTexVoronoi", TextureNodes::voronoi);
        map.put("ShaderNodeTexWhiteNoise", TextureNodes::whiteNoise);
        map.put("ShaderNodeTexGradient", TextureNodes::gradient);
        map.put("ShaderNodeTexChecker", TextureNodes::checker);
        map.put("ShaderNodeTexWave", TextureNodes::wave);
        map.put("ShaderNodeTexMagic", TextureNodes::magic);
        map.put("ShaderNodeTexBrick", TextureNodes::brick);
        map.put("GeometryNodeImageTexture", TextureNodes::image);
        map.put("GeometryNodeInputImage", call -> call.out("Image",
                call.node.image == null ? null : new Ref(call.node.image, "IMAGE")));
        map.put("GeometryNodeImageInfo", TextureNodes::imageInfo);
    }

    private static int dims(String name) {
        return switch (name) {
            case "1D" -> 1;
            case "2D" -> 2;
            case "4D" -> 4;
            default -> 3;
        };
    }

    /** The texture coordinate for one element: the vector (and w) times the scale, in the node's dimensions. */
    private static void coordinate(int dims, Column vector, Column w, Column scale, int k, float[] out) {
        float s = scale.getFloat(k);
        switch (dims) {
            case 1 -> out[0] = w.getFloat(k) * s;
            case 2 -> {
                out[0] = vector.f[3 * k] * s;
                out[1] = vector.f[3 * k + 1] * s;
            }
            case 3 -> {
                out[0] = vector.f[3 * k] * s;
                out[1] = vector.f[3 * k + 1] * s;
                out[2] = vector.f[3 * k + 2] * s;
            }
            default -> {
                out[0] = vector.f[3 * k] * s;
                out[1] = vector.f[3 * k + 1] * s;
                out[2] = vector.f[3 * k + 2] * s;
                out[3] = w.getFloat(k) * s;
            }
        }
    }

    private static void noise(NodeCall call) {
        int dims = dims(call.prop("noise_dimensions", "3D"));
        int type = Noise.type(call.prop("noise_type", "FBM"));
        boolean normalize = call.propBool("normalize", true);
        Field[] parts = Field.multi(new DataType[]{DataType.FLOAT, DataType.COLOR}, (in, out, n) -> {
            float[] p = new float[dims];
            float[] r = new float[4];
            for (int k = 0; k < n; k++) {
                coordinate(dims, in[0], in[1], in[2], k, p);
                Noise.texture(p, in[3].getFloat(k), in[4].getFloat(k), in[5].getFloat(k), in[6].getFloat(k),
                        in[7].getFloat(k), in[8].getFloat(k), type, normalize, true, r);
                out[0].f[k] = r[0];
                out[1].f[4 * k] = r[1];
                out[1].f[4 * k + 1] = r[2];
                out[1].f[4 * k + 2] = r[3];
                out[1].f[4 * k + 3] = 1f;
            }
        }, call.field("Vector", DataType.VECTOR), call.field("W", DataType.FLOAT), call.field("Scale", DataType.FLOAT),
                call.field("Detail", DataType.FLOAT), call.field("Roughness", DataType.FLOAT),
                call.field("Lacunarity", DataType.FLOAT), call.field("Offset", DataType.FLOAT),
                call.field("Gain", DataType.FLOAT), call.field("Distortion", DataType.FLOAT));
        call.out("Fac", parts[0]);
        call.out("Color", parts[1]);
    }

    private static void voronoi(NodeCall call) {
        int dims = dims(call.prop("voronoi_dimensions", "3D"));
        int feature = Voronoi.feature(call.prop("feature", "F1"));
        int metric = Voronoi.metric(call.prop("distance", "EUCLIDEAN"));
        boolean normalize = call.propBool("normalize", false);
        Field[] parts = Field.multi(new DataType[]{DataType.FLOAT, DataType.COLOR, DataType.VECTOR, DataType.FLOAT,
                DataType.FLOAT}, (in, out, n) -> {
            float[] p = new float[dims];
            Voronoi.Params params = new Voronoi.Params();
            Voronoi.Output o = new Voronoi.Output();
            for (int k = 0; k < n; k++) {
                coordinate(dims, in[0], in[1], in[2], k, p);
                params.set(dims, in[2].getFloat(k), in[3].getFloat(k), in[4].getFloat(k), in[5].getFloat(k),
                        in[6].getFloat(k), in[7].getFloat(k), in[8].getFloat(k), normalize, feature, metric);
                if (feature == Voronoi.DISTANCE_TO_EDGE) {
                    out[0].f[k] = Voronoi.fractalDistanceToEdge(params, p);
                } else if (feature == Voronoi.N_SPHERE_RADIUS) {
                    out[4].f[k] = Voronoi.nSphereRadius(params, p);
                } else {
                    Voronoi.fractal(params, p, o);
                    out[0].f[k] = o.distance;
                    out[1].f[4 * k] = o.color[0];
                    out[1].f[4 * k + 1] = o.color[1];
                    out[1].f[4 * k + 2] = o.color[2];
                    out[1].f[4 * k + 3] = 1f;
                    out[2].setVector(k, o.position[0], o.position[1], o.position[2]);
                    out[3].f[k] = o.position[3];
                }
            }
        }, call.field("Vector", DataType.VECTOR), call.field("W", DataType.FLOAT), call.field("Scale", DataType.FLOAT),
                call.field("Detail", DataType.FLOAT), call.field("Roughness", DataType.FLOAT),
                call.field("Lacunarity", DataType.FLOAT), call.field("Smoothness", DataType.FLOAT),
                call.field("Exponent", DataType.FLOAT), call.field("Randomness", DataType.FLOAT));
        call.out("Distance", parts[0]);
        call.out("Color", parts[1]);
        call.out("Position", parts[2]);
        call.out("W", parts[3]);
        call.out("Radius", parts[4]);
    }

    private static void whiteNoise(NodeCall call) {
        int dims = dims(call.prop("noise_dimensions", "3D"));
        Field[] parts = Field.multi(new DataType[]{DataType.FLOAT, DataType.COLOR}, (in, out, n) -> {
            float[] p = new float[dims];
            float[] r = new float[4];
            Column one = Column.constant(DataType.FLOAT, 1f, n);
            for (int k = 0; k < n; k++) {
                coordinate(dims, in[0], in[1], one, k, p);
                Textures.whiteNoise(p, r);
                out[0].f[k] = r[0];
                out[1].f[4 * k] = r[1];
                out[1].f[4 * k + 1] = r[2];
                out[1].f[4 * k + 2] = r[3];
                out[1].f[4 * k + 3] = 1f;
            }
        }, call.field("Vector", DataType.VECTOR), call.field("W", DataType.FLOAT));
        call.out("Value", parts[0]);
        call.out("Color", parts[1]);
    }

    private static void colorAndFac(NodeCall call, Field[] parts) {
        call.out("Color", parts[0]);
        call.out("Fac", parts[1]);
    }

    private static void gradient(NodeCall call) {
        String type = call.prop("gradient_type", "LINEAR");
        colorAndFac(call, Field.multi(new DataType[]{DataType.COLOR, DataType.FLOAT}, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                float f = Textures.gradient(type, in[0].f[3 * k], in[0].f[3 * k + 1], in[0].f[3 * k + 2]);
                out[0].f[4 * k] = out[0].f[4 * k + 1] = out[0].f[4 * k + 2] = f;
                out[0].f[4 * k + 3] = 1f;
                out[1].f[k] = f;
            }
        }, call.field("Vector", DataType.VECTOR)));
    }

    private static void checker(NodeCall call) {
        colorAndFac(call, Field.multi(new DataType[]{DataType.COLOR, DataType.FLOAT}, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                float s = in[3].getFloat(k);
                float f = Textures.checker(in[0].f[3 * k] * s, in[0].f[3 * k + 1] * s, in[0].f[3 * k + 2] * s);
                Column c = f == 1f ? in[1] : in[2];
                System.arraycopy(c.f, 4 * k, out[0].f, 4 * k, 4);
                out[1].f[k] = f;
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Color1", DataType.COLOR), call.field("Color2", DataType.COLOR),
                call.field("Scale", DataType.FLOAT)));
    }

    private static void wave(NodeCall call) {
        String type = call.prop("wave_type", "BANDS");
        String bands = call.prop("bands_direction", "X");
        String rings = call.prop("rings_direction", "X");
        String profile = call.prop("wave_profile", "SIN");
        colorAndFac(call, Field.multi(new DataType[]{DataType.COLOR, DataType.FLOAT}, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                float s = in[1].getFloat(k);
                float f = Textures.wave(type, bands, rings, profile, in[0].f[3 * k] * s, in[0].f[3 * k + 1] * s,
                        in[0].f[3 * k + 2] * s, in[2].getFloat(k), in[3].getFloat(k), in[4].getFloat(k),
                        in[5].getFloat(k), in[6].getFloat(k));
                out[0].f[4 * k] = out[0].f[4 * k + 1] = out[0].f[4 * k + 2] = f;
                out[0].f[4 * k + 3] = 1f;
                out[1].f[k] = f;
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Scale", DataType.FLOAT), call.field("Distortion", DataType.FLOAT),
                call.field("Detail", DataType.FLOAT), call.field("Detail Scale", DataType.FLOAT),
                call.field("Detail Roughness", DataType.FLOAT), call.field("Phase Offset", DataType.FLOAT)));
    }

    private static void magic(NodeCall call) {
        int depth = call.propInt("turbulence_depth", 2);
        colorAndFac(call, Field.multi(new DataType[]{DataType.COLOR, DataType.FLOAT}, (in, out, n) -> {
            float[] r = new float[4];
            for (int k = 0; k < n; k++) {
                float s = in[1].getFloat(k);
                Textures.magic(in[0].f[3 * k] * s, in[0].f[3 * k + 1] * s, in[0].f[3 * k + 2] * s, depth,
                        in[2].getFloat(k), r);
                out[0].f[4 * k] = r[1];
                out[0].f[4 * k + 1] = r[2];
                out[0].f[4 * k + 2] = r[3];
                out[0].f[4 * k + 3] = 1f;
                out[1].f[k] = r[0];
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Scale", DataType.FLOAT), call.field("Distortion", DataType.FLOAT)));
    }

    private static void brick(NodeCall call) {
        float offset = call.propFloat("offset", 0.5f);
        int offsetFrequency = call.propInt("offset_frequency", 2);
        float squash = call.propFloat("squash", 1f);
        int squashFrequency = call.propInt("squash_frequency", 2);
        colorAndFac(call, Field.multi(new DataType[]{DataType.COLOR, DataType.FLOAT}, (in, out, n) -> {
            float[] r = new float[2];
            float[] c1 = new float[4], c2 = new float[4], mortar = new float[4];
            for (int k = 0; k < n; k++) {
                float s = in[4].getFloat(k);
                Textures.brick(in[0].f[3 * k] * s, in[0].f[3 * k + 1] * s, in[5].getFloat(k), in[6].getFloat(k),
                        in[7].getFloat(k), in[8].getFloat(k), in[9].getFloat(k), offset, offsetFrequency, squash,
                        squashFrequency, r);
                in[1].getVector(k, c1);
                in[2].getVector(k, c2);
                in[3].getVector(k, mortar);
                float tint = r[0];
                for (int ch = 0; ch < 4; ch++) {
                    float b = r[1] != 1f ? (1f - tint) * c1[ch] + tint * c2[ch] : c1[ch];
                    out[0].f[4 * k + ch] = b + (mortar[ch] - b) * r[1];
                }
                out[1].f[k] = r[1];
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Color1", DataType.COLOR), call.field("Color2", DataType.COLOR),
                call.field("Mortar", DataType.COLOR), call.field("Scale", DataType.FLOAT), call.field("Mortar Size", DataType.FLOAT),
                call.field("Mortar Smooth", DataType.FLOAT), call.field("Bias", DataType.FLOAT),
                call.field("Brick Width", DataType.FLOAT), call.field("Row Height", DataType.FLOAT)));
    }

    private static ImagePixels imageOf(NodeCall call) {
        Object value = call.raw("Image");
        String name = value instanceof Ref ref ? ref.name() : value instanceof String s ? s : null;
        return name == null ? null : call.resolver().image(name);
    }

    private static void image(NodeCall call) {
        ImagePixels pixels = imageOf(call);
        String extension = call.prop("extension", "REPEAT");
        boolean closest = "Closest".equals(call.prop("interpolation", "Linear"));
        Field[] parts = Field.multi(new DataType[]{DataType.COLOR, DataType.FLOAT}, (in, out, n) -> {
            float[] c = new float[4];
            for (int k = 0; k < n; k++) {
                if (pixels == null) {
                    c[0] = 1f;
                    c[1] = 0f;
                    c[2] = 1f;
                    c[3] = 1f;
                } else {
                    pixels.sample(in[0].f[3 * k], in[0].f[3 * k + 1], extension, closest, c);
                }
                System.arraycopy(c, 0, out[0].f, 4 * k, 4);
                out[1].f[k] = c[3];
            }
        }, call.field("Vector", DataType.VECTOR));
        call.out("Color", parts[0]);
        call.out("Alpha", parts[1]);
    }

    private static void imageInfo(NodeCall call) {
        ImagePixels pixels = imageOf(call);
        call.out("Width", Field.constant(DataType.INT, pixels == null ? 0 : pixels.width()));
        call.out("Height", Field.constant(DataType.INT, pixels == null ? 0 : pixels.height()));
        call.out("Has Alpha", Field.constant(DataType.BOOL, pixels != null && pixels.hasAlpha()));
        call.out("Frame Count", Field.constant(DataType.INT, 1));
        call.out("FPS", Field.constant(DataType.FLOAT, 0f));
    }
}
