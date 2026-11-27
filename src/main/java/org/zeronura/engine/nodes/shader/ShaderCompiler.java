package org.zeronura.engine.nodes.shader;

import org.zeronura.engine.nodes.ColorRamp;
import org.zeronura.engine.nodes.CurveMapping;
import org.zeronura.engine.nodes.Node;
import org.zeronura.engine.nodes.NodePackage;
import org.zeronura.engine.nodes.NodeTree;
import org.zeronura.engine.nodes.NodeValues;
import org.zeronura.engine.nodes.Socket;
import org.zeronura.engine.nodes.SocketType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a material's Blender shader node tree into GLSL for the engine's renderer.
 *
 * <p>The tree is walked from its Material Output: every node a value is needed from emits a few lines into the
 * fragment shader, once. Reroutes and muted nodes are followed through, node groups are inlined, colour ramps and
 * curve widgets become exact GLSL functions, and procedural textures call {@code node_lib.glsl}, which mirrors the
 * Java implementations the tests check against Blender.
 *
 * <p>Shading closures (BSDFs) are lit by the engine's own lighting, cel bands included, so a Blender material
 * sits in a game scene like everything else in it. A closure feeding Shader to RGB is lit smoothly instead, so a
 * toon setup's own colour ramp makes the bands, as it does in Blender.
 *
 * <p>Value and RGB nodes become uniforms named after the node (its label when it has one), which the game can change
 * at run time through {@link NodeMaterial#set}.
 */
public final class ShaderCompiler {

    /** Colour blend modes in the order {@code nb_blend} numbers them. */
    static final List<String> BLEND_MODES = List.of("MIX", "ADD", "MULTIPLY", "SUBTRACT", "SCREEN", "DIVIDE",
            "DIFFERENCE", "EXCLUSION", "DARKEN", "LIGHTEN", "OVERLAY", "DODGE", "BURN", "HUE", "SATURATION", "VALUE",
            "COLOR", "SOFT_LIGHT", "LINEAR_LIGHT");

    private static final Set<String> SUPPORTED = Set.of(
            "ShaderNodeOutputMaterial", "NodeGroupInput", "NodeGroupOutput", "NodeReroute", "NodeFrame",
            "ShaderNodeGroup", "ShaderNodeMath", "ShaderNodeVectorMath", "ShaderNodeMix", "ShaderNodeMixRGB",
            "ShaderNodeMapRange", "ShaderNodeClamp", "ShaderNodeCombineXYZ", "ShaderNodeSeparateXYZ",
            "ShaderNodeCombineColor", "ShaderNodeSeparateColor", "ShaderNodeValue", "ShaderNodeRGB", "ShaderNodeInvert",
            "ShaderNodeGamma", "ShaderNodeBrightContrast", "ShaderNodeHueSaturation", "ShaderNodeRGBToBW",
            "ShaderNodeBlackbody", "ShaderNodeWavelength", "ShaderNodeValToRGB", "ShaderNodeFloatCurve",
            "ShaderNodeRGBCurve", "ShaderNodeVectorCurve", "ShaderNodeMapping", "ShaderNodeVectorRotate",
            "ShaderNodeNormal", "ShaderNodeTexCoord", "ShaderNodeNewGeometry", "ShaderNodeUVMap", "ShaderNodeAttribute",
            "ShaderNodeVertexColor", "ShaderNodeObjectInfo", "ShaderNodeCameraData", "ShaderNodeLightPath",
            "ShaderNodeFresnel", "ShaderNodeLayerWeight", "ShaderNodeBump", "ShaderNodeNormalMap", "ShaderNodeTangent",
            "ShaderNodeVectorTransform", "ShaderNodeWireframe", "ShaderNodeAmbientOcclusion", "ShaderNodeTexNoise",
            "ShaderNodeTexVoronoi", "ShaderNodeTexWhiteNoise", "ShaderNodeTexGradient", "ShaderNodeTexChecker",
            "ShaderNodeTexWave", "ShaderNodeTexMagic", "ShaderNodeTexBrick", "ShaderNodeTexImage",
            "ShaderNodeBsdfPrincipled", "ShaderNodeBsdfDiffuse", "ShaderNodeBsdfAnisotropic", "ShaderNodeBsdfMetallic",
            "ShaderNodeBsdfGlass", "ShaderNodeBsdfRefraction", "ShaderNodeBsdfToon", "ShaderNodeBsdfTranslucent",
            "ShaderNodeBsdfTransparent", "ShaderNodeBsdfSheen", "ShaderNodeSubsurfaceScattering", "ShaderNodeEmission",
            "ShaderNodeHoldout", "ShaderNodeMixShader", "ShaderNodeAddShader", "ShaderNodeShaderToRGB",
            "ShaderNodeEeveeSpecular", "ShaderNodeDisplacement", "ShaderNodeVectorDisplacement",
            "GeometryNodeInputSceneTime", "FunctionNodeInputBool", "FunctionNodeInputInt", "FunctionNodeInputVector",
            "GeometryNodeMenuSwitch", "ShaderNodeBsdfHairPrincipled", "ShaderNodeBsdfHair", "ShaderNodeParticleInfo",
            "ShaderNodeHairInfo", "ShaderNodePointInfo");

    public static boolean supports(String type) {
        return SUPPORTED.contains(type);
    }

    /** An image the material samples, bound to a sampler uniform. */
    public record ImageBinding(String uniform, String image, String extension, boolean closest) {
    }

    /** A Value or RGB node the game can change at run time, by the node's label or name. */
    public record Parameter(String name, String uniform, boolean color, float[] value) {
    }

    /** The shader source and everything the material needs to draw with it. */
    public record Result(String vertexSource, String fragmentSource, List<ImageBinding> images, List<String> attributes,
                         List<Parameter> parameters, List<String> problems) {
    }

    private ShaderCompiler() {
    }

    /** Compiles a material's tree. {@code cel} lights BSDFs with the engine's cel bands rather than smoothly. */
    public static Result compile(NodePackage pkg, NodePackage.MaterialDef material, boolean cel) {
        Builder builder = new Builder(pkg, cel);
        NodeTree tree = material.tree() == null ? null : pkg.tree(material.tree());
        String surface;
        if (tree == null) {
            float[] c = material.diffuseColor();
            surface = "nb_bsdf_principled(" + vec4(c) + ", " + f(material.metallic()) + ", " + f(material.roughness())
                    + ", 1.0, nb_N, vec4(0.0), 0.0, 0.0, 0.0, 0.0, 0.0, vec4(1.0), 0.5, " + cel + ")";
        } else {
            surface = builder.surface(tree);
        }
        String fragment = template("/shaders/nodes/node_material.frag")
                .replace("// @NODE_LIBRARY@", template("/shaders/nodes/node_lib.glsl"))
                .replace("// @NODE_UNIFORMS@", builder.uniforms.toString())
                .replace("// @NODE_FUNCTIONS@", builder.functions.toString())
                .replace("// @NODE_BODY@", builder.body + "    surface = " + surface + ";\n");
        return new Result(template("/shaders/nodes/node_material.vert"), fragment, List.copyOf(builder.images),
                List.copyOf(builder.attributes), List.copyOf(builder.parameters), List.copyOf(builder.problems));
    }

    static String template(String resource) {
        try (InputStream in = ShaderCompiler.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing shader resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- GLSL literals ---------------------------------------------------------------------------------------

    static String f(float value) {
        if (!Float.isFinite(value)) {
            value = Float.isNaN(value) ? 0f : Math.copySign(1e30f, value);
        }
        String s = Float.toString(value);
        return s.contains(".") || s.contains("E") ? s : s + ".0";
    }

    static String vec3(float[] v) {
        return "vec3(" + f(v[0]) + ", " + f(v[1]) + ", " + f(v[2]) + ")";
    }

    static String vec4(float[] v) {
        return "vec4(" + f(v[0]) + ", " + f(v[1]) + ", " + f(v[2]) + ", " + f(v.length > 3 ? v[3] : 1f) + ")";
    }

    // --- Types -----------------------------------------------------------------------------------------------

    enum GType {
        FLOAT("float"), VEC3("vec3"), VEC4("vec4"), CLOSURE("Closure");

        final String glsl;

        GType(String glsl) {
            this.glsl = glsl;
        }

        static GType of(SocketType type) {
            return switch (type) {
                case VECTOR, ROTATION -> VEC3;
                case RGBA -> VEC4;
                case SHADER -> CLOSURE;
                default -> FLOAT;
            };
        }
    }

    static String convert(String expr, GType from, GType to) {
        if (from == to) {
            return expr;
        }
        return switch (to) {
            case FLOAT -> switch (from) {
                case VEC3 -> "dot(" + expr + ", vec3(1.0 / 3.0))";
                case VEC4 -> "nb_luminance((" + expr + ").rgb)";
                default -> "0.0";
            };
            case VEC3 -> switch (from) {
                case FLOAT -> "vec3(" + expr + ")";
                case VEC4 -> "(" + expr + ").rgb";
                default -> "vec3(0.0)";
            };
            case VEC4 -> switch (from) {
                case FLOAT -> "vec4(vec3(" + expr + "), 1.0)";
                case VEC3 -> "vec4(" + expr + ", 1.0)";
                case CLOSURE -> "nb_shader_to_rgb(" + expr + ")";
                default -> "vec4(0.0, 0.0, 0.0, 1.0)";
            };
            case CLOSURE -> switch (from) {
                case VEC4 -> "nb_emission(" + expr + ", 1.0)";
                case VEC3 -> "nb_emission(vec4(" + expr + ", 1.0), 1.0)";
                case FLOAT -> "nb_emission(vec4(vec3(" + expr + "), 1.0), 1.0)";
                default -> "nb_closure_none()";
            };
        };
    }

    // --- The walk --------------------------------------------------------------------------------------------

    /** A tree being walked: the material's own, or a group's, inlined where its group node stands. */
    private record Frame(NodeTree tree, Node groupNode, Frame parent, int id) {
    }

    private static final class Builder {
        final NodePackage pkg;
        final boolean cel;
        final StringBuilder body = new StringBuilder();
        final StringBuilder uniforms = new StringBuilder();
        final StringBuilder functions = new StringBuilder();
        final Map<String, String[]> done = new HashMap<>();
        final List<ImageBinding> images = new ArrayList<>();
        final Map<String, String> imageUniforms = new LinkedHashMap<>();
        final List<String> attributes = new ArrayList<>();
        final List<Parameter> parameters = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        int counter;
        int frames;

        Builder(NodePackage pkg, boolean cel) {
            this.pkg = pkg;
            this.cel = cel;
        }

        String fresh(String hint) {
            String clean = hint.replaceAll("[^A-Za-z0-9]", "");
            if (clean.length() > 24) {
                clean = clean.substring(0, 24);
            }
            return "n" + (counter++) + "_" + clean;
        }

        void line(String text) {
            body.append("    ").append(text).append('\n');
        }

        String surface(NodeTree tree) {
            Frame root = new Frame(tree, null, null, frames++);
            Node output = null;
            for (Node node : tree.nodesOfType("ShaderNodeOutputMaterial")) {
                if (node.prop("target", "ALL").equals("CYCLES")) {
                    continue;
                }
                if (output == null || node.propBool("is_active_output", false)) {
                    output = node;
                }
            }
            if (output == null) {
                problems.add(tree.name + ": no Material Output");
                return "nb_closure_none()";
            }
            return input(root, output, "Surface", GType.CLOSURE, cel);
        }

        // --- Inputs ------------------------------------------------------------------------------------------

        Socket socket(Node node, String idOrName) {
            Socket s = node.input(idOrName);
            return s != null ? s : node.inputNamed(idOrName);
        }

        String input(Frame frame, Node node, String idOrName, GType want, boolean banded) {
            Socket s = socket(node, idOrName);
            if (s == null) {
                return defaultOf(want);
            }
            return input(frame, s, want, banded);
        }

        String input(Frame frame, Socket s, GType want, boolean banded) {
            Socket source = NodeTree.source(s);
            if (source == null) {
                return literal(s, want);
            }
            if (!source.output) {
                return literal(source, want);
            }
            if (source.node.type.equals("NodeGroupInput")) {
                if (frame.parent != null) {
                    return input(frame.parent, frame.groupNode, source.id, want, banded);
                }
                NodeTree.InterfaceSocket iface = frame.tree.input(source.id);
                return literalValue(iface == null ? null : iface.defaultValue(), GType.of(source.type), want);
            }
            String[] outputs = outputs(frame, source.node, banded);
            int index = source.node.outputs().indexOf(source);
            String expr = index >= 0 && index < outputs.length && outputs[index] != null ? outputs[index]
                    : defaultOf(GType.of(source.type));
            return convert(expr, GType.of(source.type), want);
        }

        /** An unconnected input: its own value, or what Blender uses when a hidden vector input is left empty. */
        String literal(Socket s, GType want) {
            if (s.type == SocketType.VECTOR) {
                String type = s.node.type;
                switch (s.name) {
                    case "Normal", "Coat Normal", "Clear Coat Normal" -> {
                        return convert("nb_N", GType.VEC3, want);
                    }
                    case "Tangent" -> {
                        return convert("nb_T", GType.VEC3, want);
                    }
                    case "Vector" -> {
                        if (type.startsWith("ShaderNodeTex")) {
                            return convert(type.equals("ShaderNodeTexImage") ? "vec3(v_uv, 0.0)" : "nb_generated",
                                    GType.VEC3, want);
                        }
                    }
                    default -> {
                    }
                }
            }
            return literalValue(s.value, GType.of(s.type), want);
        }

        String literalValue(Object value, GType from, GType want) {
            String expr = switch (from) {
                case FLOAT -> f(NodeValues.toFloat(value));
                case VEC3 -> vec3(NodeValues.toVector(value, 3));
                case VEC4 -> vec4(NodeValues.toVector(value, 4));
                case CLOSURE -> "nb_closure_none()";
            };
            return convert(expr, from, want);
        }

        static String defaultOf(GType type) {
            return switch (type) {
                case FLOAT -> "0.0";
                case VEC3 -> "vec3(0.0)";
                case VEC4 -> "vec4(0.0, 0.0, 0.0, 1.0)";
                case CLOSURE -> "nb_closure_none()";
            };
        }

        // --- Nodes -------------------------------------------------------------------------------------------

        /** The node's outputs as GLSL variables, generating its code the first time it is needed. */
        String[] outputs(Frame frame, Node node, boolean banded) {
            String key = frame.id + "/" + node.name + "/" + banded;
            String[] existing = done.get(key);
            if (existing != null) {
                return existing;
            }
            String[] result = new String[node.outputs().size()];
            done.put(key, result);
            new NodeGen(this, frame, node, banded, result).emit();
            return result;
        }

        String image(String name, String extension, boolean closest) {
            String key = name + "|" + extension + "|" + closest;
            String uniform = imageUniforms.get(key);
            if (uniform == null) {
                uniform = "nb_image" + imageUniforms.size();
                imageUniforms.put(key, uniform);
                images.add(new ImageBinding(uniform, name, extension, closest));
                uniforms.append("uniform sampler2D ").append(uniform).append(";\n");
            }
            return uniform;
        }

        /** A vertex attribute by name: the default UV and colour have their own channels, others take a slot. */
        String attribute(String name) {
            int slot = attributes.indexOf(name);
            if (slot < 0) {
                if (attributes.size() >= 6) {
                    problems.add("more than 6 attributes read; \"" + name + "\" reads as zero");
                    return "vec4(0.0)";
                }
                attributes.add(name);
                slot = attributes.size() - 1;
            }
            return "v_attr" + slot;
        }

        String parameter(Node node, boolean color, float[] value) {
            String name = node.label.isEmpty() ? node.name : node.label;
            for (Parameter p : parameters) {
                if (p.name().equals(name)) {
                    return p.uniform();
                }
            }
            String uniform = "p_" + parameters.size() + "_" + name.replaceAll("[^A-Za-z0-9]", "");
            parameters.add(new Parameter(name, uniform, color, value));
            uniforms.append("uniform ").append(color ? "vec4 " : "float ").append(uniform).append(";\n");
            return uniform;
        }

        void unsupported(Node node) {
            String message = "shader node " + node.type + " (\"" + node.name + "\") is not supported; its outputs read as zero";
            if (!problems.contains(message)) {
                problems.add(message);
            }
        }

        String rampFunction(ColorRamp ramp) {
            String name = "nb_ramp" + (counter++);
            StringBuilder fn = new StringBuilder("vec4 " + name + "(float t) {\n");
            List<ColorRamp.Stop> stops = ramp.stops;
            if (stops.isEmpty()) {
                fn.append("    return vec4(0.0, 0.0, 0.0, 1.0);\n");
            } else if (ramp.isPiecewiseLinear()) {
                if (ramp.interpolation == ColorRamp.Interpolation.CONSTANT) {
                    for (int k = 1; k < stops.size(); k++) {
                        fn.append("    if (t < ").append(f(stops.get(k).position())).append(") return ")
                                .append(vec4(stops.get(k - 1).color())).append(";\n");
                    }
                } else {
                    fn.append("    if (t <= ").append(f(stops.get(0).position())).append(") return ")
                            .append(vec4(stops.get(0).color())).append(";\n");
                    for (int k = 1; k < stops.size(); k++) {
                        ColorRamp.Stop a = stops.get(k - 1), b = stops.get(k);
                        if (b.position() <= a.position()) {
                            continue;
                        }
                        fn.append("    if (t < ").append(f(b.position())).append(") {\n");
                        fn.append("        float x = (t - ").append(f(a.position())).append(") / ")
                                .append(f(b.position() - a.position())).append(";\n");
                        if (ramp.interpolation == ColorRamp.Interpolation.EASE) {
                            fn.append("        x = x * x * (3.0 - 2.0 * x);\n");
                        }
                        fn.append("        return mix(").append(vec4(a.color())).append(", ").append(vec4(b.color()))
                                .append(", x);\n    }\n");
                    }
                }
                fn.append("    return ").append(vec4(stops.get(stops.size() - 1).color())).append(";\n");
            } else {
                int n = 256;
                StringBuilder table = new StringBuilder();
                float[] c = new float[4];
                for (int k = 0; k < n; k++) {
                    ramp.evaluate(k / (float) (n - 1), c);
                    table.append(k > 0 ? ", " : "").append(vec4(c));
                }
                fn.append("    const vec4 table[").append(n).append("] = vec4[").append(n).append("](").append(table).append(");\n");
                fn.append("    float x = clamp(t, 0.0, 1.0) * ").append(f(n - 1)).append(";\n");
                fn.append("    int i = min(int(x), ").append(n - 2).append(");\n");
                fn.append("    return mix(table[i], table[i + 1], x - float(i));\n");
            }
            fn.append("}\n\n");
            functions.append(fn);
            return name;
        }

        String curveFunction(CurveMapping mapping, int curve) {
            String name = "nb_curve" + (counter++);
            CurveMapping.Curve c = mapping.curves.get(curve);
            int n = c.values().length;
            StringBuilder fn = new StringBuilder("float " + name + "(float x) {\n");
            if (n < 2) {
                functions.append(fn.append("    return x;\n}\n\n"));
                return name;
            }
            StringBuilder table = new StringBuilder();
            for (int k = 0; k < n; k++) {
                table.append(k > 0 ? ", " : "").append(f(c.values()[k]));
            }
            fn.append("    const float table[").append(n).append("] = float[").append(n).append("](").append(table).append(");\n");
            fn.append("    float lo = ").append(f(c.min())).append(", hi = ").append(f(c.max())).append(";\n");
            if (mapping.extrapolate) {
                fn.append("    if (x <= lo) return table[0] + (x - lo) * ").append(f(c.slopeIn())).append(";\n");
                fn.append("    if (x >= hi) return table[").append(n - 1).append("] + (x - hi) * ").append(f(c.slopeOut())).append(";\n");
            } else {
                fn.append("    if (x <= lo) return table[0];\n");
                fn.append("    if (x >= hi) return table[").append(n - 1).append("];\n");
            }
            fn.append("    float s = (x - lo) / (hi - lo) * ").append(f(n - 1)).append(";\n");
            fn.append("    int i = min(int(s), ").append(n - 2).append(");\n");
            fn.append("    return mix(table[i], table[i + 1], s - float(i));\n}\n\n");
            functions.append(fn);
            return name;
        }
    }

    /** Emits the GLSL for one node. */
    private static final class NodeGen {
        final Builder b;
        final Frame frame;
        final Node node;
        final boolean banded;
        final String[] out;

        NodeGen(Builder b, Frame frame, Node node, boolean banded, String[] out) {
            this.b = b;
            this.frame = frame;
            this.node = node;
            this.banded = banded;
            this.out = out;
        }

        String in(String idOrName, GType type) {
            return b.input(frame, node, idOrName, type, banded);
        }

        String flt(String id) {
            return in(id, GType.FLOAT);
        }

        String v3(String id) {
            return in(id, GType.VEC3);
        }

        String v4(String id) {
            return in(id, GType.VEC4);
        }

        String cl(String id) {
            return in(id, GType.CLOSURE);
        }

        void set(String outputIdOrName, GType type, String expr) {
            Socket socket = node.output(outputIdOrName);
            if (socket == null) {
                socket = node.outputNamed(outputIdOrName);
            }
            if (socket == null) {
                return;
            }
            GType target = GType.of(socket.type);
            String name = b.fresh(node.name + "_" + socket.id);
            b.line(target.glsl + " " + name + " = " + convert(expr, type, target) + ";");
            out[node.outputs().indexOf(socket)] = name;
        }

        /** Declares a temporary and returns its name. */
        String let(GType type, String expr) {
            String name = b.fresh(node.name);
            b.line(type.glsl + " " + name + " = " + expr + ";");
            return name;
        }

        String prop(String key, String fallback) {
            return node.prop(key, fallback);
        }

        void zeroOutputs() {
            for (Socket s : node.outputs()) {
                set(s.id, GType.of(s.type), Builder.defaultOf(GType.of(s.type)));
            }
        }

        void emit() {
            switch (node.type) {
                case "ShaderNodeGroup" -> group();
                case "ShaderNodeMath" -> math();
                case "ShaderNodeVectorMath" -> vectorMath();
                case "ShaderNodeMix" -> mix();
                case "ShaderNodeMixRGB" -> {
                    String fac = "clamp(" + flt("Fac") + ", 0.0, 1.0)";
                    String c2 = let(GType.VEC4, v4("Color2"));
                    if (node.propBool("use_alpha", false)) {
                        fac = "(" + fac + " * " + c2 + ".a)";
                    }
                    String r = let(GType.VEC4, "nb_blend(" + Math.max(0, BLEND_MODES.indexOf(prop("blend_type", "MIX"))) + ", "
                            + fac + ", " + v4("Color1") + ", " + c2 + ")");
                    if (node.propBool("use_clamp", false)) {
                        b.line(r + ".rgb = clamp(" + r + ".rgb, 0.0, 1.0);");
                    }
                    set("Color", GType.VEC4, r);
                }
                case "ShaderNodeMapRange" -> mapRange();
                case "ShaderNodeClamp" -> {
                    String v = flt("Value"), lo = let(GType.FLOAT, flt("Min")), hi = let(GType.FLOAT, flt("Max"));
                    set("Result", GType.FLOAT, "RANGE".equals(prop("clamp_type", "MINMAX"))
                            ? "(" + lo + " > " + hi + " ? clamp(" + v + ", " + hi + ", " + lo + ") : clamp(" + v + ", " + lo + ", " + hi + "))"
                            : "min(max(" + v + ", " + lo + "), " + hi + ")");
                }
                case "ShaderNodeCombineXYZ" -> set("Vector", GType.VEC3, "vec3(" + flt("X") + ", " + flt("Y") + ", " + flt("Z") + ")");
                case "ShaderNodeSeparateXYZ" -> {
                    String v = let(GType.VEC3, v3("Vector"));
                    set("X", GType.FLOAT, v + ".x");
                    set("Y", GType.FLOAT, v + ".y");
                    set("Z", GType.FLOAT, v + ".z");
                }
                case "ShaderNodeCombineColor" -> {
                    String c = "vec3(" + flt("Red") + ", " + flt("Green") + ", " + flt("Blue") + ")";
                    String mode = prop("mode", "RGB");
                    String rgb = mode.equals("HSV") ? "nb_hsv_to_rgb(" + c + ")" : mode.equals("HSL") ? "nb_hsl_to_rgb(" + c + ")" : c;
                    set("Color", GType.VEC4, "vec4(" + rgb + ", 1.0)");
                }
                case "ShaderNodeSeparateColor" -> {
                    String c = let(GType.VEC4, v4("Color"));
                    String mode = prop("mode", "RGB");
                    String v = let(GType.VEC3, mode.equals("HSV") ? "nb_rgb_to_hsv(" + c + ".rgb)"
                            : mode.equals("HSL") ? "nb_rgb_to_hsl(" + c + ".rgb)" : c + ".rgb");
                    set("Red", GType.FLOAT, v + ".x");
                    set("Green", GType.FLOAT, v + ".y");
                    set("Blue", GType.FLOAT, v + ".z");
                    set("Alpha", GType.FLOAT, c + ".a");
                }
                case "ShaderNodeValue" -> set("Value", GType.FLOAT,
                        b.parameter(node, false, new float[]{NodeValues.toFloat(node.outputs().get(0).value)}));
                case "ShaderNodeRGB" -> set("Color", GType.VEC4,
                        b.parameter(node, true, NodeValues.toVector(node.outputs().get(0).value, 4)));
                case "FunctionNodeInputBool" -> set("Boolean", GType.FLOAT, node.propBool("boolean", false) ? "1.0" : "0.0");
                case "FunctionNodeInputInt" -> set("Integer", GType.FLOAT, f(node.propInt("integer", 0)));
                case "FunctionNodeInputVector" -> set("Vector", GType.VEC3, vec3(NodeValues.toVector(node.props.get("vector"), 3)));
                case "ShaderNodeInvert" -> {
                    String c = let(GType.VEC4, v4("Color"));
                    set("Color", GType.VEC4, "vec4(mix(" + c + ".rgb, 1.0 - " + c + ".rgb, " + flt("Fac") + "), " + c + ".a)");
                }
                case "ShaderNodeGamma" -> {
                    String c = let(GType.VEC4, v4("Color"));
                    String g = let(GType.FLOAT, flt("Gamma"));
                    set("Color", GType.VEC4, "vec4(mix(" + c + ".rgb, pow(max(" + c + ".rgb, vec3(1e-30)), vec3(" + g
                            + ")), vec3(greaterThan(" + c + ".rgb, vec3(0.0)))), " + c + ".a)");
                }
                case "ShaderNodeBrightContrast" -> {
                    String c = let(GType.VEC4, v4("Color"));
                    String contrast = let(GType.FLOAT, flt("Contrast"));
                    set("Color", GType.VEC4, "vec4(max((1.0 + " + contrast + ") * " + c + ".rgb + (" + flt("Bright") + " - "
                            + contrast + " * 0.5), 0.0), " + c + ".a)");
                }
                case "ShaderNodeHueSaturation" -> set("Color", GType.VEC4, "nb_hue_saturation(" + flt("Hue") + ", "
                        + flt("Saturation") + ", " + flt("Value") + ", " + flt("Fac") + ", " + v4("Color") + ")");
                case "ShaderNodeRGBToBW" -> set("Val", GType.FLOAT, "nb_luminance((" + v4("Color") + ").rgb)");
                case "ShaderNodeBlackbody" -> set("Color", GType.VEC4, "nb_blackbody(" + flt("Temperature") + ")");
                case "ShaderNodeWavelength" -> set("Color", GType.VEC4, "nb_wavelength(" + flt("Wavelength") + ")");
                case "ShaderNodeValToRGB" -> {
                    if (node.ramp == null) {
                        set("Color", GType.VEC4, "vec4(vec3(" + flt("Fac") + "), 1.0)");
                        return;
                    }
                    String c = let(GType.VEC4, b.rampFunction(node.ramp) + "(" + flt("Fac") + ")");
                    set("Color", GType.VEC4, c);
                    set("Alpha", GType.FLOAT, c + ".a");
                }
                case "ShaderNodeFloatCurve" -> {
                    String v = let(GType.FLOAT, flt("Value"));
                    String mapped = node.curves == null || node.curves.curves.isEmpty() ? v
                            : b.curveFunction(node.curves, 0) + "(" + v + ")";
                    if (node.curves != null && node.curves.clip) {
                        mapped = "clamp(" + mapped + ", " + f(node.curves.clipMinY) + ", " + f(node.curves.clipMaxY) + ")";
                    }
                    set("Value", GType.FLOAT, "mix(" + v + ", " + mapped + ", " + flt("Factor") + ")");
                }
                case "ShaderNodeRGBCurve" -> rgbCurve();
                case "ShaderNodeVectorCurve" -> vectorCurve();
                case "ShaderNodeMapping" -> {
                    int type = switch (prop("vector_type", "POINT")) {
                        case "TEXTURE" -> 1;
                        case "VECTOR" -> 2;
                        case "NORMAL" -> 3;
                        default -> 0;
                    };
                    String location = type >= 2 ? "vec3(0.0)" : v3("Location");
                    set("Vector", GType.VEC3, "nb_mapping(" + v3("Vector") + ", " + location + ", " + v3("Rotation") + ", "
                            + v3("Scale") + ", " + type + ")");
                }
                case "ShaderNodeVectorRotate" -> vectorRotate();
                case "ShaderNodeNormal" -> {
                    float[] own = NodeValues.toVector(node.outputs().get(0).value, 3);
                    set("Normal", GType.VEC3, vec3(own));
                    set("Dot", GType.FLOAT, "dot(" + vec3(own) + ", normalize(" + v3("Normal") + "))");
                }
                case "ShaderNodeTexCoord" -> {
                    set("Generated", GType.VEC3, "nb_generated");
                    set("Normal", GType.VEC3, "nb_Nobj");
                    set("UV", GType.VEC3, "vec3(v_uv, 0.0)");
                    set("Object", GType.VEC3, "nb_Pobj");
                    set("Camera", GType.VEC3, "(view * vec4(v_worldPos, 1.0)).xyz");
                    String clip = let(GType.VEC4, "projection * view * vec4(v_worldPos, 1.0)");
                    set("Window", GType.VEC3, "vec3(" + clip + ".xy / " + clip + ".w * 0.5 + 0.5, 0.0)");
                    set("Reflection", GType.VEC3, "reflect(-nb_I, nb_N)");
                }
                case "ShaderNodeNewGeometry" -> {
                    set("Position", GType.VEC3, "nb_P");
                    set("Normal", GType.VEC3, "nb_N");
                    set("Tangent", GType.VEC3, "nb_T");
                    set("True Normal", GType.VEC3, "nb_Ng");
                    set("Incoming", GType.VEC3, "nb_I");
                    set("Parametric", GType.VEC3, "vec3(v_uv, 0.0)");
                    set("Backfacing", GType.FLOAT, "nb_backfacing");
                    set("Pointiness", GType.FLOAT, "0.5");
                    set("Random Per Island", GType.FLOAT, "v_instance.x");
                }
                case "ShaderNodeUVMap" -> {
                    String name = prop("uv_map", "");
                    set("UV", GType.VEC3, name.isEmpty() ? "vec3(v_uv, 0.0)" : "vec3(" + b.attribute(name) + ".xy, 0.0)");
                }
                case "ShaderNodeAttribute" -> attribute();
                case "ShaderNodeVertexColor" -> {
                    String name = prop("layer_name", "");
                    String c = name.isEmpty() ? "v_color" : b.attribute(name);
                    set("Color", GType.VEC4, "vec4(" + c + ".rgb, 1.0)");
                    set("Alpha", GType.FLOAT, c + ".a");
                }
                case "ShaderNodeObjectInfo" -> {
                    set("Location", GType.VEC3, "nb_bl(instanced == 1 ? vec3(0.0) : model[3].xyz)");
                    set("Color", GType.VEC4, "vec4(1.0)");
                    set("Alpha", GType.FLOAT, "1.0");
                    set("Object Index", GType.FLOAT, "0.0");
                    set("Material Index", GType.FLOAT, "0.0");
                    set("Random", GType.FLOAT, "v_instance.x");
                }
                case "ShaderNodeCameraData" -> {
                    String v = let(GType.VEC3, "(view * vec4(v_worldPos, 1.0)).xyz");
                    set("View Vector", GType.VEC3, "normalize(" + v + ")");
                    set("View Z Depth", GType.FLOAT, "-" + v + ".z");
                    set("View Distance", GType.FLOAT, "length(" + v + ")");
                }
                case "ShaderNodeLightPath" -> {
                    for (Socket s : node.outputs()) {
                        set(s.id, GType.FLOAT, switch (s.id) {
                            case "Is Camera Ray" -> "1.0";
                            case "Ray Length" -> "distance(v_worldPos, viewPos)";
                            default -> "0.0";
                        });
                    }
                }
                case "ShaderNodeFresnel" -> set("Fac", GType.FLOAT, "nb_fresnel(" + flt("IOR") + ", " + v3("Normal") + ")");
                case "ShaderNodeLayerWeight" -> {
                    String r = let(GType.VEC3, "vec3(nb_layer_weight(" + flt("Blend") + ", " + v3("Normal") + "), 0.0)");
                    set("Fresnel", GType.FLOAT, r + ".x");
                    set("Facing", GType.FLOAT, r + ".y");
                }
                case "ShaderNodeBump" -> set("Normal", GType.VEC3, "nb_bump(" + flt("Strength") + ", " + flt("Distance") + ", "
                        + flt("Height") + ", " + v3("Normal") + ", " + (node.propBool("invert", false) ? "-1.0" : "1.0") + ")");
                case "ShaderNodeNormalMap" -> normalMap();
                case "ShaderNodeTangent" -> set("Tangent", GType.VEC3, "nb_T");
                case "ShaderNodeVectorTransform" -> vectorTransform();
                case "ShaderNodeWireframe" -> set("Fac", GType.FLOAT, "0.0");
                case "ShaderNodeAmbientOcclusion" -> {
                    set("Color", GType.VEC4, v4("Color"));
                    set("AO", GType.FLOAT, "1.0");
                }
                case "GeometryNodeInputSceneTime" -> {
                    set("Seconds", GType.FLOAT, "time");
                    set("Frame", GType.FLOAT, "frame");
                }
                case "ShaderNodeTexNoise" -> noise();
                case "ShaderNodeTexVoronoi" -> voronoi();
                case "ShaderNodeTexWhiteNoise" -> {
                    int dims = dims(prop("noise_dimensions", "3D"));
                    String r = let(GType.VEC4, "nb_white_noise(" + coord(dims, "1.0") + ", " + dims + ")");
                    set("Value", GType.FLOAT, r + ".x");
                    set("Color", GType.VEC4, "vec4(" + r + ".yzw, 1.0)");
                }
                case "ShaderNodeTexGradient" -> {
                    int type = List.of("LINEAR", "QUADRATIC", "EASING", "DIAGONAL", "RADIAL", "QUADRATIC_SPHERE", "SPHERICAL")
                            .indexOf(prop("gradient_type", "LINEAR"));
                    String r = let(GType.FLOAT, "nb_gradient(" + v3("Vector") + ", " + Math.max(type, 0) + ")");
                    set("Fac", GType.FLOAT, r);
                    set("Color", GType.VEC4, "vec4(vec3(" + r + "), 1.0)");
                }
                case "ShaderNodeTexChecker" -> {
                    String r = let(GType.FLOAT, "nb_checker(" + v3("Vector") + " * " + flt("Scale") + ")");
                    set("Fac", GType.FLOAT, r);
                    set("Color", GType.VEC4, "(" + r + " == 1.0 ? " + v4("Color1") + " : " + v4("Color2") + ")");
                }
                case "ShaderNodeTexWave" -> wave();
                case "ShaderNodeTexMagic" -> {
                    String r = let(GType.VEC4, "nb_magic(" + v3("Vector") + " * " + flt("Scale") + ", "
                            + node.propInt("turbulence_depth", 2) + ", " + flt("Distortion") + ")");
                    set("Fac", GType.FLOAT, r + ".x");
                    set("Color", GType.VEC4, "vec4(" + r + ".yzw, 1.0)");
                }
                case "ShaderNodeTexBrick" -> brick();
                case "ShaderNodeTexImage" -> image();
                case "ShaderNodeBsdfPrincipled" -> set("BSDF", GType.CLOSURE, "nb_bsdf_principled(" + v4("Base Color") + ", "
                        + flt("Metallic") + ", " + flt("Roughness") + ", " + flt("Alpha") + ", " + v3("Normal") + ", "
                        + v4("Emission Color") + ", " + flt("Emission Strength") + ", " + flt("Transmission Weight") + ", "
                        + flt("Coat Weight") + ", " + flt("Coat Roughness") + ", " + flt("Sheen Weight") + ", "
                        + v4("Sheen Tint") + ", " + flt("Specular IOR Level") + ", " + banded + ")");
                case "ShaderNodeBsdfDiffuse", "ShaderNodeSubsurfaceScattering" -> set(node.outputs().get(0).id, GType.CLOSURE,
                        "nb_bsdf_diffuse(" + v4("Color") + ", " + v3("Normal") + ", " + banded + ")");
                case "ShaderNodeBsdfAnisotropic", "ShaderNodeBsdfSheen" -> set("BSDF", GType.CLOSURE,
                        "nb_bsdf_glossy(" + v4("Color") + ", " + flt("Roughness") + ", " + v3("Normal") + ", " + banded + ")");
                case "ShaderNodeBsdfMetallic" -> set("BSDF", GType.CLOSURE,
                        "nb_bsdf_glossy(" + v4("Base Color") + ", " + flt("Roughness") + ", " + v3("Normal") + ", " + banded + ")");
                case "ShaderNodeBsdfGlass", "ShaderNodeBsdfRefraction" -> set("BSDF", GType.CLOSURE, "nb_bsdf_glass("
                        + v4("Color") + ", " + flt("Roughness") + ", " + flt("IOR") + ", " + v3("Normal") + ", " + banded + ")");
                case "ShaderNodeBsdfToon" -> set("BSDF", GType.CLOSURE, "nb_bsdf_toon(" + v4("Color") + ", " + flt("Size") + ", "
                        + flt("Smooth") + ", " + v3("Normal") + ", " + "GLOSSY".equals(prop("component", "DIFFUSE")) + ")");
                case "ShaderNodeBsdfTranslucent" -> set("BSDF", GType.CLOSURE,
                        "nb_bsdf_translucent(" + v4("Color") + ", " + v3("Normal") + ")");
                case "ShaderNodeBsdfTransparent" -> set("BSDF", GType.CLOSURE, "nb_transparent(" + v4("Color") + ")");
                case "ShaderNodeEmission" -> set("Emission", GType.CLOSURE, "nb_emission(" + v4("Color") + ", " + flt("Strength") + ")");
                case "ShaderNodeHoldout" -> set("Holdout", GType.CLOSURE, "nb_transparent(vec4(1.0))");
                case "ShaderNodeEeveeSpecular" -> set("BSDF", GType.CLOSURE, "nb_bsdf_principled(" + v4("Base Color")
                        + ", 0.0, " + flt("Roughness") + ", 1.0 - " + flt("Transparency") + ", " + v3("Normal") + ", "
                        + v4("Emissive Color") + ", 1.0, 0.0, " + flt("Clear Coat") + ", " + flt("Clear Coat Roughness")
                        + ", 0.0, vec4(1.0), nb_luminance((" + v4("Specular") + ").rgb) * 2.0, " + banded + ")");
                case "ShaderNodeMixShader" -> set("Shader", GType.CLOSURE, "nb_mix_closure(" + flt("Fac") + ", " + cl("Shader")
                        + ", " + cl("Shader_001") + ")");
                case "ShaderNodeAddShader" -> set("Shader", GType.CLOSURE, "nb_add_closure(" + cl("Shader") + ", "
                        + cl("Shader_001") + ")");
                case "ShaderNodeShaderToRGB" -> {
                    // The closure feeding it is lit smoothly: the toon ramp after it makes the bands, as in Blender.
                    String c = let(GType.VEC4, "nb_shader_to_rgb(" + b.input(frame, node, "Shader", GType.CLOSURE, false) + ")");
                    set("Color", GType.VEC4, c);
                    set("Alpha", GType.FLOAT, c + ".a");
                }
                case "ShaderNodeDisplacement", "ShaderNodeVectorDisplacement" -> set("Displacement", GType.VEC3, "vec3(0.0)");
                case "GeometryNodeMenuSwitch" -> menuSwitch();
                case "ShaderNodeParticleInfo", "ShaderNodeHairInfo", "ShaderNodePointInfo" -> zeroOutputs();
                case "ShaderNodeBsdfHairPrincipled", "ShaderNodeBsdfHair" -> set("BSDF", GType.CLOSURE,
                        "nb_bsdf_diffuse(" + (node.input("Color") != null ? v4("Color") : "vec4(0.5)") + ", nb_N, " + banded + ")");
                default -> {
                    b.unsupported(node);
                    zeroOutputs();
                }
            }
        }

        // --- Individual nodes -------------------------------------------------------------------------------

        void group() {
            NodeTree tree = node.groupTree == null ? null : b.pkg.tree(node.groupTree);
            if (tree == null) {
                b.unsupported(node);
                zeroOutputs();
                return;
            }
            Frame inner = new Frame(tree, node, frame, b.frames++);
            Node output = tree.outputNode("NodeGroupOutput");
            for (Socket s : node.outputs()) {
                GType type = GType.of(s.type);
                String expr = output == null ? Builder.defaultOf(type) : b.input(inner, output, s.id, type, banded);
                set(s.id, type, expr);
            }
        }

        void math() {
            String a = let(GType.FLOAT, flt("Value"));
            String bb = let(GType.FLOAT, flt("Value_001"));
            String c = let(GType.FLOAT, flt("Value_002"));
            String expr = switch (prop("operation", "ADD")) {
                case "ADD" -> a + " + " + bb;
                case "SUBTRACT" -> a + " - " + bb;
                case "MULTIPLY" -> a + " * " + bb;
                case "DIVIDE" -> "nb_safe_divide(" + a + ", " + bb + ")";
                case "MULTIPLY_ADD" -> a + " * " + bb + " + " + c;
                case "POWER" -> "nb_pow(" + a + ", " + bb + ")";
                case "LOGARITHM" -> "nb_safe_log(" + a + ", " + bb + ")";
                case "SQRT" -> "(" + a + " > 0.0 ? sqrt(" + a + ") : 0.0)";
                case "INVERSE_SQRT" -> "(" + a + " > 0.0 ? inversesqrt(" + a + ") : 0.0)";
                case "ABSOLUTE" -> "abs(" + a + ")";
                case "EXPONENT" -> "exp(" + a + ")";
                case "MINIMUM" -> "min(" + a + ", " + bb + ")";
                case "MAXIMUM" -> "max(" + a + ", " + bb + ")";
                case "LESS_THAN" -> "(" + a + " < " + bb + " ? 1.0 : 0.0)";
                case "GREATER_THAN" -> "(" + a + " > " + bb + " ? 1.0 : 0.0)";
                case "SIGN" -> "sign(" + a + ")";
                case "COMPARE" -> "(abs(" + a + " - " + bb + ") <= max(" + c + ", 1e-5) ? 1.0 : 0.0)";
                case "SMOOTH_MIN" -> "nb_smoothmin(" + a + ", " + bb + ", " + c + ")";
                case "SMOOTH_MAX" -> "-nb_smoothmin(-" + a + ", -" + bb + ", " + c + ")";
                case "ROUND" -> "floor(" + a + " + 0.5)";
                case "FLOOR" -> "floor(" + a + ")";
                case "CEIL" -> "ceil(" + a + ")";
                case "TRUNC" -> "trunc(" + a + ")";
                case "FRACT" -> "(" + a + " - floor(" + a + "))";
                case "MODULO" -> "nb_fmod(" + a + ", " + bb + ")";
                case "FLOORED_MODULO" -> "nb_floored_mod(" + a + ", " + bb + ")";
                case "WRAP" -> "nb_wrapf(" + a + ", " + bb + ", " + c + ")";
                case "SNAP" -> "floor(nb_safe_divide(" + a + ", " + bb + ")) * " + bb;
                case "PINGPONG" -> "nb_pingpong(" + a + ", " + bb + ")";
                case "SINE" -> "sin(" + a + ")";
                case "COSINE" -> "cos(" + a + ")";
                case "TANGENT" -> "tan(" + a + ")";
                case "ARCSINE" -> "asin(clamp(" + a + ", -1.0, 1.0))";
                case "ARCCOSINE" -> "acos(clamp(" + a + ", -1.0, 1.0))";
                case "ARCTANGENT" -> "atan(" + a + ")";
                case "ARCTAN2" -> "atan(" + a + ", " + bb + ")";
                case "SINH" -> "sinh(" + a + ")";
                case "COSH" -> "cosh(" + a + ")";
                case "TANH" -> "tanh(" + a + ")";
                case "RADIANS" -> "radians(" + a + ")";
                case "DEGREES" -> "degrees(" + a + ")";
                default -> "0.0";
            };
            if (node.propBool("use_clamp", false)) {
                expr = "clamp(" + expr + ", 0.0, 1.0)";
            }
            set("Value", GType.FLOAT, expr);
        }

        void vectorMath() {
            String op = prop("operation", "ADD");
            String a = let(GType.VEC3, v3("Vector"));
            String bb = let(GType.VEC3, v3("Vector_001"));
            String c = let(GType.VEC3, v3("Vector_002"));
            String s = let(GType.FLOAT, flt("Scale"));
            String vec = switch (op) {
                case "ADD" -> a + " + " + bb;
                case "SUBTRACT" -> a + " - " + bb;
                case "MULTIPLY" -> a + " * " + bb;
                case "DIVIDE" -> "nb_safe_divide(" + a + ", " + bb + ")";
                case "MULTIPLY_ADD" -> a + " * " + bb + " + " + c;
                case "CROSS_PRODUCT" -> "cross(" + a + ", " + bb + ")";
                case "PROJECT" -> "(dot(" + bb + ", " + bb + ") != 0.0 ? " + bb + " * (dot(" + a + ", " + bb + ") / dot(" + bb + ", " + bb + ")) : vec3(0.0))";
                case "REFLECT" -> a + " - 2.0 * dot((length(" + bb + ") > 0.0 ? normalize(" + bb + ") : vec3(0.0)), " + a + ") * (length(" + bb + ") > 0.0 ? normalize(" + bb + ") : vec3(0.0))";
                case "REFRACT" -> "refract(" + a + ", (length(" + bb + ") > 0.0 ? normalize(" + bb + ") : vec3(0.0)), " + s + ")";
                case "FACEFORWARD" -> "(dot(" + c + ", " + bb + ") < 0.0 ? " + a + " : -" + a + ")";
                case "SCALE" -> a + " * " + s;
                case "NORMALIZE" -> "(length(" + a + ") > 0.0 ? normalize(" + a + ") : vec3(0.0))";
                case "ABSOLUTE" -> "abs(" + a + ")";
                case "POWER" -> "vec3(nb_pow(" + a + ".x, " + bb + ".x), nb_pow(" + a + ".y, " + bb + ".y), nb_pow(" + a + ".z, " + bb + ".z))";
                case "SIGN" -> "sign(" + a + ")";
                case "MINIMUM" -> "min(" + a + ", " + bb + ")";
                case "MAXIMUM" -> "max(" + a + ", " + bb + ")";
                case "ROUND" -> "floor(" + a + " + 0.5)";
                case "FLOOR" -> "floor(" + a + ")";
                case "CEIL" -> "ceil(" + a + ")";
                case "FRACTION" -> "(" + a + " - floor(" + a + "))";
                case "MODULO" -> "vec3(nb_fmod(" + a + ".x, " + bb + ".x), nb_fmod(" + a + ".y, " + bb + ".y), nb_fmod(" + a + ".z, " + bb + ".z))";
                case "WRAP" -> "vec3(nb_wrapf(" + a + ".x, " + bb + ".x, " + c + ".x), nb_wrapf(" + a + ".y, " + bb + ".y, " + c + ".y), nb_wrapf(" + a + ".z, " + bb + ".z, " + c + ".z))";
                case "SNAP" -> "floor(nb_safe_divide(" + a + ", " + bb + ")) * " + bb;
                case "SINE" -> "sin(" + a + ")";
                case "COSINE" -> "cos(" + a + ")";
                case "TANGENT" -> "tan(" + a + ")";
                default -> "vec3(0.0)";
            };
            String value = switch (op) {
                case "DOT_PRODUCT" -> "dot(" + a + ", " + bb + ")";
                case "DISTANCE" -> "distance(" + a + ", " + bb + ")";
                case "LENGTH" -> "length(" + a + ")";
                default -> null;
            };
            set("Vector", GType.VEC3, value != null ? "vec3(0.0)" : vec);
            set("Value", GType.FLOAT, value != null ? value : "0.0");
        }

        void mix() {
            boolean clampFactor = node.propBool("clamp_factor", true);
            switch (prop("data_type", "FLOAT")) {
                case "VECTOR" -> {
                    boolean nonUniform = "NON_UNIFORM".equals(prop("factor_mode", "UNIFORM"));
                    String fac = nonUniform ? v3("Factor_Vector") : "vec3(" + flt("Factor_Float") + ")";
                    if (clampFactor) {
                        fac = "clamp(" + fac + ", 0.0, 1.0)";
                    }
                    set("Result_Vector", GType.VEC3, "mix(" + v3("A_Vector") + ", " + v3("B_Vector") + ", " + fac + ")");
                }
                case "RGBA" -> {
                    String fac = flt("Factor_Float");
                    String facVar = let(GType.FLOAT, clampFactor ? "clamp(" + fac + ", 0.0, 1.0)" : fac);
                    String a = let(GType.VEC4, v4("A_Color"));
                    String bb = let(GType.VEC4, v4("B_Color"));
                    String blend = prop("blend_type", "MIX");
                    String r = let(GType.VEC4, "nb_blend(" + Math.max(BLEND_MODES.indexOf(blend), 0) + ", " + facVar + ", "
                            + a + ", " + bb + ")");
                    if ("MIX".equals(blend)) {
                        b.line(r + ".a = mix(" + a + ".a, " + bb + ".a, " + facVar + ");");
                    }
                    if (node.propBool("clamp_result", false)) {
                        b.line(r + ".rgb = clamp(" + r + ".rgb, 0.0, 1.0);");
                    }
                    set("Result_Color", GType.VEC4, r);
                }
                default -> {
                    String fac = flt("Factor_Float");
                    if (clampFactor) {
                        fac = "clamp(" + fac + ", 0.0, 1.0)";
                    }
                    set("Result_Float", GType.FLOAT, "mix(" + flt("A_Float") + ", " + flt("B_Float") + ", " + fac + ")");
                }
            }
        }

        void mapRange() {
            int interpolation = Math.max(0, List.of("LINEAR", "STEPPED", "SMOOTHSTEP", "SMOOTHERSTEP")
                    .indexOf(prop("interpolation_type", "LINEAR")));
            boolean clamp = node.propBool("clamp", true);
            if ("FLOAT_VECTOR".equals(prop("data_type", "FLOAT"))) {
                String v = let(GType.VEC3, v3("Vector")), a = let(GType.VEC3, v3("From_Min_FLOAT3")),
                        c = let(GType.VEC3, v3("From_Max_FLOAT3")), d = let(GType.VEC3, v3("To_Min_FLOAT3")),
                        e = let(GType.VEC3, v3("To_Max_FLOAT3")), s = let(GType.VEC3, v3("Steps_FLOAT3"));
                StringBuilder r = new StringBuilder("vec3(");
                for (String axis : new String[]{"x", "y", "z"}) {
                    r.append(axis.equals("x") ? "" : ", ").append("nb_map_range(").append(v).append('.').append(axis).append(", ")
                            .append(a).append('.').append(axis).append(", ").append(c).append('.').append(axis).append(", ")
                            .append(d).append('.').append(axis).append(", ").append(e).append('.').append(axis).append(", ")
                            .append(s).append('.').append(axis).append(", ").append(interpolation).append(", ").append(clamp).append(')');
                }
                set("Vector", GType.VEC3, r.append(')').toString());
                return;
            }
            set("Result", GType.FLOAT, "nb_map_range(" + flt("Value") + ", " + flt("From Min") + ", " + flt("From Max") + ", "
                    + flt("To Min") + ", " + flt("To Max") + ", " + flt("Steps") + ", " + interpolation + ", " + clamp + ")");
        }

        void rgbCurve() {
            String c = let(GType.VEC4, v4("Color"));
            CurveMapping curves = node.curves;
            if (curves == null || curves.curves.size() < 4) {
                set("Color", GType.VEC4, c);
                return;
            }
            String combined = b.curveFunction(curves, 3);
            String[] channels = {"r", "g", "b"};
            StringBuilder mapped = new StringBuilder("vec3(");
            for (int ch = 0; ch < 3; ch++) {
                float level = curves.whiteLevel[ch] - curves.blackLevel[ch];
                String x = c + "." + channels[ch];
                if (!curves.levelsAreIdentity() && level != 0f) {
                    x = "(" + x + " - " + f(curves.blackLevel[ch]) + ") / " + f(level);
                }
                mapped.append(ch > 0 ? ", " : "").append(b.curveFunction(curves, ch)).append('(').append(combined).append('(')
                        .append(x).append("))");
            }
            set("Color", GType.VEC4, "vec4(mix(" + c + ".rgb, " + mapped + "), " + flt("Fac") + "), " + c + ".a)");
        }

        void vectorCurve() {
            String v = let(GType.VEC3, v3("Vector"));
            if (node.curves == null || node.curves.curves.size() < 3) {
                set("Vector", GType.VEC3, v);
                return;
            }
            String[] axes = {"x", "y", "z"};
            StringBuilder mapped = new StringBuilder("vec3(");
            for (int a = 0; a < 3; a++) {
                String c = b.curveFunction(node.curves, a) + "(" + v + "." + axes[a] + ")";
                if (node.curves.clip) {
                    c = "clamp(" + c + ", " + f(node.curves.clipMinY) + ", " + f(node.curves.clipMaxY) + ")";
                }
                mapped.append(a > 0 ? ", " : "").append(c);
            }
            set("Vector", GType.VEC3, "mix(" + v + ", " + mapped + "), " + flt("Fac") + ")");
        }

        void vectorRotate() {
            String type = prop("rotation_type", "AXIS_ANGLE");
            boolean invert = node.propBool("invert", false);
            String v = v3("Vector");
            String center = let(GType.VEC3, v3("Center"));
            String angle = (invert ? "-" : "") + "(" + flt("Angle") + ")";
            String matrix = switch (type) {
                case "X_AXIS" -> "nb_axis_matrix(vec3(1.0, 0.0, 0.0), " + angle + ")";
                case "Y_AXIS" -> "nb_axis_matrix(vec3(0.0, 1.0, 0.0), " + angle + ")";
                case "Z_AXIS" -> "nb_axis_matrix(vec3(0.0, 0.0, 1.0), " + angle + ")";
                case "EULER_XYZ" -> (invert ? "transpose(" : "(") + "nb_euler_matrix(" + v3("Rotation") + "))";
                default -> "nb_axis_matrix(normalize(" + v3("Axis") + " + vec3(1e-30)), " + angle + ")";
            };
            set("Vector", GType.VEC3, matrix + " * (" + v + " - " + center + ") + " + center);
        }

        void attribute() {
            String type = prop("attribute_type", "GEOMETRY");
            String name = prop("attribute_name", "");
            String a;
            if (!"GEOMETRY".equals(type) || name.isEmpty()) {
                if (!"GEOMETRY".equals(type)) {
                    b.problems.add("Attribute node \"" + node.name + "\" reads an " + type.toLowerCase(Locale.ROOT)
                            + " attribute, which reads as zero");
                }
                a = "vec4(0.0)";
            } else {
                a = b.attribute(name);
            }
            set("Color", GType.VEC4, "vec4(" + a + ".rgb, 1.0)");
            set("Vector", GType.VEC3, a + ".xyz");
            set("Fac", GType.FLOAT, "dot(" + a + ".xyz, vec3(1.0 / 3.0))");
            set("Alpha", GType.FLOAT, a + ".w");
        }

        void normalMap() {
            String space = prop("space", "TANGENT");
            String c = let(GType.VEC4, v4("Color"));
            String n = let(GType.VEC3, "(" + c + ".rgb * 2.0 - 1.0)");
            if ("DIRECTX".equals(prop("convention", "OPENGL"))) {
                b.line(n + ".y = -" + n + ".y;");
            }
            String result = switch (space) {
                case "OBJECT", "BLENDER_OBJECT" -> "normalize(nb_bl(mat3(model) * nb_gl(" + n + ")))";
                case "WORLD", "BLENDER_WORLD" -> "normalize(" + n + ")";
                default -> "normalize(nb_T * " + n + ".x + nb_B * " + n + ".y + nb_N * " + n + ".z)";
            };
            set("Normal", GType.VEC3, "normalize(mix(nb_N, " + result + ", max(" + flt("Strength") + ", 0.0)))");
        }

        void vectorTransform() {
            String type = prop("vector_type", "VECTOR");
            String from = prop("convert_from", "WORLD"), to = prop("convert_to", "OBJECT");
            boolean point = type.equals("POINT");
            String v = let(GType.VEC3, v3("Vector"));
            String world = let(GType.VEC3, switch (from) {
                case "OBJECT" -> point ? "nb_bl((model * vec4(nb_gl(" + v + "), 1.0)).xyz)" : "nb_bl(mat3(model) * nb_gl(" + v + "))";
                case "CAMERA" -> point ? "nb_bl((inverse(view) * vec4(" + v + ", 1.0)).xyz)" : "nb_bl(mat3(inverse(view)) * " + v + ")";
                default -> v;
            });
            String r = switch (to) {
                case "OBJECT" -> point ? "nb_bl((inverse(model) * vec4(nb_gl(" + world + "), 1.0)).xyz)"
                        : "nb_bl(mat3(inverse(model)) * nb_gl(" + world + "))";
                case "CAMERA" -> point ? "(view * vec4(nb_gl(" + world + "), 1.0)).xyz" : "mat3(view) * nb_gl(" + world + ")";
                default -> world;
            };
            set("Vector", GType.VEC3, type.equals("NORMAL") ? "normalize(" + r + ")" : r);
        }

        static int dims(String name) {
            return switch (name) {
                case "1D" -> 1;
                case "2D" -> 2;
                case "4D" -> 4;
                default -> 3;
            };
        }

        /** The texture coordinate as a vec4: vector (and w) times scale, laid out for the dimension count. */
        String coord(int dims, String scale) {
            String v = dims >= 2 ? let(GType.VEC3, v3("Vector")) : "vec3(0.0)";
            String w = (dims == 1 || dims == 4) && node.input("W") != null ? flt("W") : "0.0";
            return switch (dims) {
                case 1 -> "vec4(" + w + " * " + scale + ", 0.0, 0.0, 0.0)";
                case 2 -> "vec4(" + v + ".xy * " + scale + ", 0.0, 0.0)";
                case 3 -> "vec4(" + v + " * " + scale + ", 0.0)";
                default -> "vec4(" + v + " * " + scale + ", " + w + " * " + scale + ")";
            };
        }

        void noise() {
            int dims = dims(prop("noise_dimensions", "3D"));
            int type = switch (prop("noise_type", "FBM")) {
                case "MULTIFRACTAL" -> 0;
                case "HYBRID_MULTIFRACTAL" -> 2;
                case "RIDGED_MULTIFRACTAL" -> 3;
                case "HETERO_TERRAIN" -> 4;
                default -> 1;
            };
            String scale = let(GType.FLOAT, flt("Scale"));
            String r = let(GType.VEC4, "nb_noise_texture(" + coord(dims, scale) + ", " + dims + ", " + flt("Detail") + ", "
                    + flt("Roughness") + ", " + flt("Lacunarity") + ", " + flt("Offset") + ", " + flt("Gain") + ", "
                    + flt("Distortion") + ", " + type + ", " + node.propBool("normalize", true) + ")");
            set("Fac", GType.FLOAT, r + ".x");
            set("Color", GType.VEC4, "vec4(" + r + ".yzw, 1.0)");
        }

        void voronoi() {
            int dims = dims(prop("voronoi_dimensions", "3D"));
            String feature = prop("feature", "F1");
            int metric = Math.max(0, List.of("EUCLIDEAN", "MANHATTAN", "CHEBYCHEV", "MINKOWSKI").indexOf(prop("distance", "EUCLIDEAN")));
            boolean normalize = node.propBool("normalize", false);
            String scale = let(GType.FLOAT, flt("Scale"));
            String coord = let(GType.VEC4, coord(dims, scale));
            switch (feature) {
                case "DISTANCE_TO_EDGE" -> set("Distance", GType.FLOAT, "nb_voronoi_edge(" + coord + ", " + dims + ", "
                        + flt("Detail") + ", " + flt("Roughness") + ", " + flt("Lacunarity") + ", " + flt("Randomness") + ", "
                        + normalize + ")");
                case "N_SPHERE_RADIUS" -> set("Radius", GType.FLOAT, "nb_voronoi_n_sphere(" + coord + ", " + dims + ", "
                        + flt("Randomness") + ")");
                default -> {
                    int f = feature.equals("F2") ? 1 : feature.equals("SMOOTH_F1") ? 2 : 0;
                    String name = b.fresh(node.name + "voronoi");
                    b.line("NbVoronoi " + name + " = nb_voronoi_fractal(" + coord + ", " + dims + ", " + f + ", " + metric
                            + ", " + scale + ", " + flt("Detail") + ", " + flt("Roughness") + ", " + flt("Lacunarity") + ", "
                            + flt("Smoothness") + ", " + flt("Exponent") + ", " + flt("Randomness") + ", " + normalize + ");");
                    set("Distance", GType.FLOAT, name + ".distance");
                    set("Color", GType.VEC4, "vec4(" + name + ".color, 1.0)");
                    set("Position", GType.VEC3, name + ".position.xyz");
                    set("W", GType.FLOAT, name + ".position.w");
                }
            }
        }

        void wave() {
            boolean rings = "RINGS".equals(prop("wave_type", "BANDS"));
            int direction = rings ? List.of("X", "Y", "Z", "SPHERICAL").indexOf(prop("rings_direction", "X"))
                    : List.of("X", "Y", "Z", "DIAGONAL").indexOf(prop("bands_direction", "X"));
            int profile = List.of("SIN", "SAW", "TRI").indexOf(prop("wave_profile", "SIN"));
            String r = let(GType.FLOAT, "nb_wave(" + v3("Vector") + " * " + flt("Scale") + ", " + (rings ? 1 : 0) + ", "
                    + Math.max(direction, 0) + ", " + Math.max(profile, 0) + ", " + flt("Distortion") + ", " + flt("Detail")
                    + ", " + flt("Detail Scale") + ", " + flt("Detail Roughness") + ", " + flt("Phase Offset") + ")");
            set("Fac", GType.FLOAT, r);
            set("Color", GType.VEC4, "vec4(vec3(" + r + "), 1.0)");
        }

        void brick() {
            String r = let(GType.VEC3, "vec3(nb_brick((" + v3("Vector") + ").xy * " + flt("Scale") + ", " + flt("Mortar Size")
                    + ", " + flt("Mortar Smooth") + ", " + flt("Bias") + ", " + flt("Brick Width") + ", " + flt("Row Height")
                    + ", " + f(node.propFloat("offset", 0.5f)) + ", " + node.propInt("offset_frequency", 2) + ", "
                    + f(node.propFloat("squash", 1f)) + ", " + node.propInt("squash_frequency", 2) + "), 0.0)");
            String c1 = let(GType.VEC4, v4("Color1"));
            String brickColor = let(GType.VEC4, "(" + r + ".y != 1.0 ? mix(" + c1 + ", " + v4("Color2") + ", " + r + ".x) : " + c1 + ")");
            set("Color", GType.VEC4, "mix(" + brickColor + ", " + v4("Mortar") + ", " + r + ".y)");
            set("Fac", GType.FLOAT, r + ".y");
        }

        void image() {
            String imageName = node.image;
            NodePackage.ImageDef def = imageName == null ? null : b.pkg.images().get(imageName);
            if (def == null || def.file() == null) {
                set("Color", GType.VEC4, "vec4(1.0, 0.0, 1.0, 1.0)");
                set("Alpha", GType.FLOAT, "1.0");
                if (imageName != null) {
                    b.problems.add("image \"" + imageName + "\" is missing");
                }
                return;
            }
            String extension = prop("extension", "REPEAT");
            boolean closest = "Closest".equals(prop("interpolation", "Linear"));
            String sampler = b.image(imageName, extension, closest);
            String v = let(GType.VEC3, v3("Vector"));
            String projection = prop("projection", "FLAT");
            String c;
            if ("BOX".equals(projection)) {
                String blend = f(Math.max(node.propFloat("projection_blend", 0f), 1e-3f));
                String w = let(GType.VEC3, "pow(abs(nb_Nobj), vec3(1.0 / " + blend + "))");
                b.line(w + " /= max(" + w + ".x + " + w + ".y + " + w + ".z, 1e-6);");
                c = let(GType.VEC4, "texture(" + sampler + ", vec2(" + v + ".y, 1.0 - " + v + ".z)) * " + w + ".x + texture("
                        + sampler + ", vec2(" + v + ".x, 1.0 - " + v + ".z)) * " + w + ".y + texture(" + sampler + ", vec2("
                        + v + ".x, 1.0 - " + v + ".y)) * " + w + ".z");
            } else {
                String uv = switch (projection) {
                    case "SPHERE" -> "vec2(atan(" + v + ".y, " + v + ".x) / (2.0 * NB_PI) + 0.5, atan(" + v + ".z, length("
                            + v + ".xy)) / NB_PI + 0.5)";
                    case "TUBE" -> "vec2(atan(" + v + ".y, " + v + ".x) / (2.0 * NB_PI) + 0.5, " + v + ".z)";
                    default -> v + ".xy";
                };
                String coords = let(GType.VEC3, "vec3(" + uv + ", 0.0)");
                c = let(GType.VEC4, "texture(" + sampler + ", vec2(" + coords + ".x, 1.0 - " + coords + ".y))");
                if ("CLIP".equals(extension)) {
                    b.line("if (any(lessThan(" + coords + ".xy, vec2(0.0))) || any(greaterThan(" + coords + ".xy, vec2(1.0)))) "
                            + c + " = vec4(0.0);");
                }
            }
            String rgb = def.linear() ? c + ".rgb" : "nb_srgb_to_linear(" + c + ".rgb)";
            set("Color", GType.VEC4, "vec4(" + rgb + ", 1.0)");
            set("Alpha", GType.FLOAT, c + ".a");
        }

        void menuSwitch() {
            Socket menu = node.input("Menu");
            String selected = NodeValues.toText(menu == null ? null : menu.value, "");
            List<Map<String, Object>> items = node.items("enum_items");
            int chosen = 0;
            for (int k = 0; k < items.size(); k++) {
                if (selected.equals(String.valueOf(items.get(k).get("name")))) {
                    chosen = k;
                }
            }
            Socket output = node.output("Output");
            Socket item = node.input("Item_" + chosen);
            if (output != null) {
                GType type = GType.of(output.type);
                set("Output", type, item == null ? Builder.defaultOf(type) : b.input(frame, item, type, banded));
            }
        }
    }
}
