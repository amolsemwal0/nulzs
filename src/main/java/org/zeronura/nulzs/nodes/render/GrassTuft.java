package org.zeronura.nulzs.nodes.render;

import org.zeronura.nulzs.nodes.geometry.GeometryOps;
import org.zeronura.nulzs.nodes.geometry.GeometrySet;
import org.zeronura.nulzs.nodes.geometry.MeshGeometry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tuft of ground cover made by a geometry node tree (one drawn in the Geometry Nodes Tool), as the engine's
 * {@link org.zeronura.nulzs.render.GrassRenderer} takes one: positions, normals, UVs and triangles, in the engine's
 * axes, and the tuft's height and reach. The tree's origin is where it stands on the ground: its height is how far it
 * rises above it, and anything below (a stone's buried half) stays below.
 *
 * <p>The normals are softened toward a dome over the tuft: each points mostly up and partly out from the tuft's
 * middle, the way painted (anime) grass is lit — a clump reads as one rounded mass in light or shade, rather than as
 * dozens of flat blades each catching the sun its own way, which flickers as they sway. {@code upward} sets how much:
 * 0 keeps the blades' own normals, 1 makes them all point up.
 *
 * <p>The UVs are turned over (v becomes 1 - v) into the way a texture is read here, its picture's top row first: a
 * baked material's picture (the Shader Tool's Bake...) has v = 1 at its top, as Blender draws UV space.
 */
public record GrassTuft(float[] positions, float[] normals, float[] uvs, int[] indices, float height, float radius,
                        int vertices, int triangles, List<String> problems) {

    public boolean ok() {
        return problems.isEmpty() && indices.length > 0;
    }

    /** Runs the tree in a Geometry Nodes Tool file, its inputs by name or id (the rest at their defaults). */
    public static GrassTuft fromTree(Path file, Map<String, Object> inputs, float upward) {
        GeometryTrees.Result result = GeometryTrees.run(file, inputs);
        if (!result.ok()) {
            return empty(result.problems());
        }
        return fromGeometry(result.geometry(), upward);
    }

    public static GrassTuft fromGeometry(GeometrySet geometry, float upward) {
        GeometrySet real = GeometryOps.realize(geometry);
        MeshGeometry mesh = real.mesh;
        if (mesh == null || mesh.faceCount() == 0) {
            return empty(List.of("The tree made no faces"));
        }
        List<RenderMeshes.Data> parts = RenderMeshes.build(mesh, Map.of(), null, null, null);
        int vertexCount = 0;
        int indexCount = 0;
        int stride = NodeMesh.BASE_FLOATS;
        for (RenderMeshes.Data part : parts) {
            vertexCount += part.vertices().length / (stride + 4 * part.attributeCount());
            indexCount += part.indices().length;
        }
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] indices = new int[indexCount];
        int vertex = 0;
        int index = 0;
        for (RenderMeshes.Data part : parts) {
            int partStride = stride + 4 * part.attributeCount();
            int partVertices = part.vertices().length / partStride;
            float[] v = part.vertices();
            for (int i = 0; i < partVertices; i++) {
                int at = i * partStride;
                System.arraycopy(v, at, positions, (vertex + i) * 3, 3);
                System.arraycopy(v, at + 3, normals, (vertex + i) * 3, 3);
                uvs[(vertex + i) * 2] = v[at + 6];
                uvs[(vertex + i) * 2 + 1] = 1f - v[at + 7];
            }
            for (int k : part.indices()) {
                indices[index++] = vertex + k;
            }
            vertex += partVertices;
        }

        // The origin is the ground: the height is what rises above it, the reach how far out from it the tuft goes.
        float maxY = 0f;
        float radius = 0f;
        for (int i = 0; i < vertexCount; i++) {
            maxY = Math.max(maxY, positions[i * 3 + 1]);
            radius = Math.max(radius, (float) Math.hypot(positions[i * 3], positions[i * 3 + 2]));
        }
        float height = Math.max(1e-4f, maxY);

        float keep = 1f - Math.clamp(upward, 0f, 1f);
        for (int i = 0; i < vertexCount; i++) {
            float x = positions[i * 3];
            float z = positions[i * 3 + 2];
            float out = (float) Math.hypot(x, z);
            // The dome: up, and out from the middle as much as the vertex is out (a blade's tip more than its root).
            float domeX = out > 1e-5f ? x / out * Math.min(1f, out / Math.max(radius, 1e-4f)) : 0f;
            float domeZ = out > 1e-5f ? z / out * Math.min(1f, out / Math.max(radius, 1e-4f)) : 0f;
            float nx = normals[i * 3] * keep + domeX * 0.45f * (1f - keep);
            float ny = normals[i * 3 + 1] * keep + (1f - keep);
            float nz = normals[i * 3 + 2] * keep + domeZ * 0.45f * (1f - keep);
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-6f) {
                nx = 0f;
                ny = 1f;
                nz = 0f;
                length = 1f;
            }
            normals[i * 3] = nx / length;
            normals[i * 3 + 1] = ny / length;
            normals[i * 3 + 2] = nz / length;
        }
        return new GrassTuft(positions, normals, uvs, indices, height, radius, vertexCount, indexCount / 3, List.of());
    }

    private static GrassTuft empty(List<String> problems) {
        List<String> said = problems.isEmpty() ? List.of("The tree made nothing") : new ArrayList<>(problems);
        return new GrassTuft(new float[0], new float[0], new float[0], new int[0], 0f, 0f, 0, 0, said);
    }
}
