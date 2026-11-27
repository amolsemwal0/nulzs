package org.zeronura.engine.nodes.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.zeronura.engine.core.OffscreenCapture;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.nodes.NodePackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Bakes a node material into pictures, as Blender's Bake does: the material is drawn across the unit square of UV space
 * (where UV, Object and Generated coordinates all run 0 to 1) and what each point of it shows is written out, its own
 * colour unlit ({@link Pass#COLOR}) or its shading normal in tangent space ({@link Pass#NORMAL}, OpenGL's way up: green
 * is towards v = 1, the top of the picture). A material built to repeat at the square's edges — 4D noise round a torus,
 * say — bakes to a picture that tiles.
 *
 * <p>Drawn at {@link #SUPERSAMPLE} times the size and averaged down, so fine detail doesn't alias. Needs the GL context;
 * {@link #main} makes one of its own to bake from the command line:
 *
 * <pre>{@code
 * NodeBaker <material.nodes.json> [--size 1024] [--out folder/prefix] [--material Name]
 * }</pre>
 * writes {@code prefix_albedo.png} and {@code prefix_normal.png}; without {@code --out}, where the Shader Tool's Bake...
 * last wrote them (the material's {@code bake} prefix, in its project's assets), else a {@code pictures} folder beside it.
 */
public final class NodeBaker {

    /** What a bake writes. */
    public enum Pass {
        /** The surface's own colour, unlit: what a BSDF's Base Color (or an Emission's colour) is there. */
        COLOR(1, "albedo"),
        /** The shading normal, such as a Bump node makes, as a tangent-space normal map holds it. */
        NORMAL(2, "normal");

        final int mode;
        /** What the picture is called after the prefix: {@code _albedo}, {@code _normal}. */
        public final String suffix;

        Pass(int mode, String suffix) {
            this.mode = mode;
            this.suffix = suffix;
        }
    }

    /** Each picture's pixel is the average of this many by this many drawn. */
    public static final int SUPERSAMPLE = 2;

    private NodeBaker() {
    }

    /**
     * One pass of a material, {@code size} pixels square: RGBA, a byte a channel, the top row first (v = 1 at the top,
     * as Blender lays an image over UV space). The colour keeps the material's alpha (a Principled BSDF's Alpha, say), so
     * a surface with see-through parts bakes to a picture with them; anything else, and the normal, is opaque.
     */
    public static byte[] bake(NodeMaterial material, Pass pass, int size) {
        if (size < 1 || size > 8192) {
            throw new IllegalArgumentException("a baked picture is 1 to 8192 pixels across, not " + size);
        }
        int full = size * SUPERSAMPLE;
        NodeMesh square = unitSquare();
        // Looking straight down on the square (it lies flat, u along +x and v along -z in the engine's axes), its edges
        // the picture's: v up the picture.
        Vector3f eye = new Vector3f(0.5f, 10f, -0.5f);
        Matrix4f view = new Matrix4f().lookAt(eye, new Vector3f(0.5f, 0f, -0.5f), new Vector3f(0f, 0f, -1f));
        Matrix4f projection = new Matrix4f().ortho(-0.5f, 0.5f, -0.5f, 0.5f, 1f, 20f);
        Vector3f texspaceLocation = new Vector3f(0.5f, 0.5f, 0f);
        Vector3f texspaceSize = new Vector3f(0.5f, 0.5f, 0f);
        try (OffscreenCapture capture = new OffscreenCapture(full, full)) {
            Runnable draw = () -> material.drawBaked(square, view, projection, eye, pass.mode, texspaceLocation,
                    texspaceSize);
            // Drawn without blending, so the shader's alpha is what lands in the picture.
            byte[] drawn = pass == Pass.COLOR ? capture.asDrawn(Color.BLACK, draw) : capture.opaque(Color.BLACK, draw);
            return shrink(drawn, full, SUPERSAMPLE, pass == Pass.NORMAL);
        } finally {
            square.delete();
        }
    }

    /** Bakes every pass to {@code prefix_albedo.png} and {@code prefix_normal.png}, and returns the files written. */
    public static List<Path> bakeAll(NodeMaterial material, int size, Path prefix) {
        List<Path> written = new ArrayList<>();
        for (Pass pass : Pass.values()) {
            Path file = prefix.resolveSibling(prefix.getFileName() + "_" + pass.suffix + ".png");
            written.add(OffscreenCapture.writePng(file, size, size, bake(material, pass, size)));
        }
        return written;
    }

    /** The square from (0, 0) to (1, 1) in UV space, flat and facing up, its tangent along u. */
    private static NodeMesh unitSquare() {
        float[] vertices = new float[4 * NodeMesh.BASE_FLOATS];
        float[][] corners = {{0f, 0f}, {1f, 0f}, {1f, 1f}, {0f, 1f}};
        for (int i = 0; i < 4; i++) {
            float u = corners[i][0];
            float v = corners[i][1];
            float[] vertex = {u, 0f, -v, 0f, 1f, 0f, u, v, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 1f};
            System.arraycopy(vertex, 0, vertices, i * NodeMesh.BASE_FLOATS, NodeMesh.BASE_FLOATS);
        }
        return new NodeMesh(vertices, new int[]{0, 1, 2, 0, 2, 3}, 0, new float[]{0.5f, 0f, -0.5f});
    }

    /** Averages each {@code factor} by {@code factor} block of a picture into one pixel; normals are made unit again. */
    static byte[] shrink(byte[] picture, int size, int factor, boolean normals) {
        int out = size / factor;
        byte[] result = new byte[out * out * 4];
        float share = 1f / (factor * factor);
        for (int y = 0; y < out; y++) {
            for (int x = 0; x < out; x++) {
                float[] sum = new float[4];
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        int at = ((y * factor + dy) * size + x * factor + dx) * 4;
                        for (int c = 0; c < 4; c++) {
                            sum[c] += (picture[at + c] & 0xFF) * share;
                        }
                    }
                }
                if (normals) {
                    float nx = sum[0] / 127.5f - 1f;
                    float ny = sum[1] / 127.5f - 1f;
                    float nz = sum[2] / 127.5f - 1f;
                    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (length > 1e-5f) {
                        sum[0] = (nx / length + 1f) * 127.5f;
                        sum[1] = (ny / length + 1f) * 127.5f;
                        sum[2] = (nz / length + 1f) * 127.5f;
                    }
                }
                int at = (y * out + x) * 4;
                for (int c = 0; c < 4; c++) {
                    result[at + c] = (byte) Math.clamp(Math.round(sum[c]), 0, 255);
                }
            }
        }
        return result;
    }

    /**
     * Where the Shader Tool last baked a material saved in a project's {@code assets/materials}: its {@code bake} prefix,
     * in those assets; null if it has none.
     */
    @SuppressWarnings("unchecked")
    static Path savedPrefix(Path file, String material) {
        try {
            Map<String, Object> json = Json.parseObject(Files.readString(file));
            Map<String, Object> materials = Json.object(json, "materials");
            Object def = materials == null ? null : materials.get(material);
            Map<String, Object> bake = def instanceof Map<?, ?> m ? Json.object((Map<String, Object>) m, "bake") : null;
            String prefix = bake == null ? "" : Json.string(bake, "prefix", "");
            Path folder = file.toAbsolutePath().getParent();
            if (prefix.isBlank() || folder == null || folder.getParent() == null) {
                return null;
            }
            return folder.getParent().resolve(prefix);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Bakes a node package's material from the command line; see the class note. */
    public static void main(String[] args) {
        Path file = null;
        Path out = null;
        String name = null;
        int size = 1024;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--size" -> size = Integer.parseInt(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--material" -> name = args[++i];
                default -> file = Path.of(args[i]);
            }
        }
        if (file == null) {
            System.err.println("usage: NodeBaker <material.nodes.json> [--size 1024] [--out folder/prefix] [--material Name]");
            System.exit(2);
        }
        NodePackage pkg = NodePackage.load(file);
        if (name == null) {
            if (pkg.materials().isEmpty()) {
                throw new IllegalArgumentException(file + " has no material in it");
            }
            name = pkg.materials().keySet().iterator().next();
        }
        if (out == null) {
            out = savedPrefix(file, name);
        }
        if (out == null) {
            String base = file.getFileName().toString().replaceFirst("\\.nodes\\.json$", "");
            out = file.toAbsolutePath().resolveSibling("pictures").resolve(base);
        }
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW did not start");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(64, 64, "NodeBaker", NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("no GL context to bake in");
        }
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        NodeMaterials materials = new NodeMaterials(pkg, false);
        try {
            NodeMaterial material = materials.get(name);
            for (String problem : material.problems) {
                System.err.println("[NodeBaker] " + name + ": " + problem);
            }
            for (Path written : bakeAll(material, size, out)) {
                System.out.println("[NodeBaker] wrote " + written);
            }
        } finally {
            materials.delete();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
}
