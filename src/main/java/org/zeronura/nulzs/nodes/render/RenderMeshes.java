package org.zeronura.nulzs.nodes.render;

import org.zeronura.nulzs.nodes.geometry.Attributes;
import org.zeronura.nulzs.nodes.geometry.Column;
import org.zeronura.nulzs.nodes.geometry.DataType;
import org.zeronura.nulzs.nodes.geometry.Domain;
import org.zeronura.nulzs.nodes.geometry.MeshGeometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a node-system mesh (Blender space, n-gons, attributes per domain) into triangle lists ready for the GPU:
 * one per material slot, each corner's shading normal, UV, colour and requested attributes split out, identical
 * corners shared, tangents worked out from the UVs, and everything converted to the engine's Y-up space.
 * Pure CPU work, so it can run off the GL thread.
 */
public final class RenderMeshes {

    /** One material slot's triangles: interleaved vertices as {@link NodeMesh} lays them out, and indices. */
    public record Data(int slot, float[] vertices, int[] indices, int attributeCount, float[] texspaceLocation,
                       float[] texspaceSize, float[] centre) {
    }

    private RenderMeshes() {
    }

    /** The UV map a mesh renders with: the named one, else UVMap, else the first 2D corner attribute. */
    static Column uvs(MeshGeometry mesh, String preferred) {
        Attributes attributes = mesh.attributes();
        for (String name : new String[]{preferred, ".render_uv", "UVMap"}) {
            if (name != null && attributes.has(name)) {
                Attributes.Attribute a = attributes.get(name);
                return mesh.adapt(a.data(), a.domain(), Domain.CORNER).convert(DataType.FLOAT2);
            }
        }
        for (Attributes.Attribute a : attributes.all()) {
            if (a.domain() == Domain.CORNER && a.data().type == DataType.FLOAT2 && !a.name().startsWith(".")) {
                return a.data();
            }
        }
        return null;
    }

    static Column colors(MeshGeometry mesh, String preferred) {
        Attributes attributes = mesh.attributes();
        if (preferred != null && attributes.has(preferred)) {
            Attributes.Attribute a = attributes.get(preferred);
            return mesh.adapt(a.data(), a.domain(), Domain.CORNER).convert(DataType.COLOR);
        }
        for (Attributes.Attribute a : attributes.all()) {
            if (a.data().type == DataType.COLOR && !a.name().startsWith(".")) {
                return mesh.adapt(a.data(), a.domain(), Domain.CORNER);
            }
        }
        return null;
    }

    /**
     * Builds the triangle lists. {@code attributeNames} are what each slot's material reads, by slot index (null
     * for a slot that reads none); {@code texspace} is Blender's texture space (location, size) when known, else the
     * mesh's bounds are used, as Blender's automatic texture space is.
     */
    public static List<Data> build(MeshGeometry mesh, Map<Integer, List<String>> attributeNames, String uvName,
                                   String colorName, float[][] texspace) {
        MeshGeometry.Topology t = mesh.topology;
        int corners = mesh.cornerCount();
        float[] p = mesh.positions();
        float[] normals = mesh.cornerNormals();
        Column uv = uvs(mesh, uvName);
        Column color = colors(mesh, colorName);
        Column materialIndex = mesh.read("material_index", Domain.FACE, DataType.INT);
        int[] cornerFaces = t.cornerFaces();
        float[][] space = texspace != null ? texspace : bounds(p);

        Map<Integer, List<int[]>> trianglesBySlot = new HashMap<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            int slot = materialIndex == null ? 0 : Math.max(0, materialIndex.i[f]);
            List<int[]> list = trianglesBySlot.computeIfAbsent(slot, k -> new ArrayList<>());
            list.addAll(org.zeronura.nulzs.nodes.geometry.MeshTriangles.of(mesh, f));
        }
        List<Data> out = new ArrayList<>();
        for (Map.Entry<Integer, List<int[]>> entry : trianglesBySlot.entrySet()) {
            int slot = entry.getKey();
            List<String> names = attributeNames.getOrDefault(slot, List.of());
            Column[] extra = new Column[names.size()];
            for (int k = 0; k < names.size(); k++) {
                Attributes.Attribute a = mesh.attributes().get(names.get(k));
                extra[k] = a == null ? null : mesh.adapt(a.data(), a.domain(), Domain.CORNER).convert(DataType.COLOR);
            }
            int floats = NodeMesh.BASE_FLOATS + 4 * names.size();
            Map<CornerKey, Integer> shared = new HashMap<>();
            float[] vertices = new float[Math.max(16, entry.getValue().size() * 3 * floats)];
            int[] indices = new int[entry.getValue().size() * 3];
            int vertexCount = 0;
            int at = 0;
            float[] scratch = new float[floats];
            for (int[] tri : entry.getValue()) {
                for (int c : tri) {
                    int v = t.cornerVerts[c];
                    BlenderSpace.point(p, 3 * v, scratch, 0);
                    BlenderSpace.point(normals, 3 * c, scratch, 3);
                    if (uv != null) {
                        scratch[6] = uv.f[2 * c];
                        scratch[7] = uv.f[2 * c + 1];
                    } else {
                        scratch[6] = scratch[7] = 0f;
                    }
                    if (color != null) {
                        System.arraycopy(color.f, 4 * c, scratch, 8, 4);
                    } else {
                        scratch[8] = scratch[9] = scratch[10] = scratch[11] = 1f;
                    }
                    scratch[12] = scratch[13] = scratch[14] = scratch[15] = 0f;
                    for (int k = 0; k < extra.length; k++) {
                        if (extra[k] != null) {
                            System.arraycopy(extra[k].f, 4 * c, scratch, NodeMesh.BASE_FLOATS + 4 * k, 4);
                        } else {
                            Arrays.fill(scratch, NodeMesh.BASE_FLOATS + 4 * k, NodeMesh.BASE_FLOATS + 4 * k + 4, 0f);
                        }
                    }
                    CornerKey key = new CornerKey(v, scratch.clone());
                    Integer index = shared.get(key);
                    if (index == null) {
                        index = vertexCount++;
                        shared.put(key, index);
                        System.arraycopy(scratch, 0, vertices, index * floats, floats);
                    }
                    indices[at++] = index;
                }
            }
            vertices = Arrays.copyOf(vertices, vertexCount * floats);
            tangents(vertices, indices, floats);
            float[] centre = centre(vertices, floats);
            out.add(new Data(slot, vertices, indices, names.size(), space[0], space[1], centre));
        }
        out.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return out;
    }

    private record CornerKey(int vertex, float[] values) {
        @Override
        public boolean equals(Object o) {
            return o instanceof CornerKey k && k.vertex == vertex && Arrays.equals(k.values, values);
        }

        @Override
        public int hashCode() {
            return 31 * vertex + Arrays.hashCode(values);
        }
    }

    /** Blender's automatic texture space: the centre and half-size of the bounds (in Blender space). */
    static float[][] bounds(float[] p) {
        if (p.length == 0) {
            return new float[][]{{0f, 0f, 0f}, {1f, 1f, 1f}};
        }
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int k = 0; k < p.length; k += 3) {
            for (int a = 0; a < 3; a++) {
                min[a] = Math.min(min[a], p[k + a]);
                max[a] = Math.max(max[a], p[k + a]);
            }
        }
        float[] location = new float[3], size = new float[3];
        for (int a = 0; a < 3; a++) {
            location[a] = (min[a] + max[a]) * 0.5f;
            size[a] = (max[a] - min[a]) * 0.5f;
            if (size[a] <= 0f) {
                size[a] = 1f;
            }
        }
        return new float[][]{location, size};
    }

    private static float[] centre(float[] vertices, int floats) {
        int n = vertices.length / floats;
        float[] c = new float[3];
        for (int v = 0; v < n; v++) {
            for (int a = 0; a < 3; a++) {
                c[a] += vertices[v * floats + a] / n;
            }
        }
        return c;
    }

    /** Per-vertex tangents from the UV layout (accumulated over triangles), with the bitangent's sign in w. */
    private static void tangents(float[] v, int[] indices, int floats) {
        int n = v.length / floats;
        float[] tan = new float[n * 3];
        float[] bit = new float[n * 3];
        for (int k = 0; k < indices.length; k += 3) {
            int a = indices[k], b = indices[k + 1], c = indices[k + 2];
            float e1x = v[b * floats] - v[a * floats], e1y = v[b * floats + 1] - v[a * floats + 1], e1z = v[b * floats + 2] - v[a * floats + 2];
            float e2x = v[c * floats] - v[a * floats], e2y = v[c * floats + 1] - v[a * floats + 1], e2z = v[c * floats + 2] - v[a * floats + 2];
            float du1 = v[b * floats + 6] - v[a * floats + 6], dv1 = v[b * floats + 7] - v[a * floats + 7];
            float du2 = v[c * floats + 6] - v[a * floats + 6], dv2 = v[c * floats + 7] - v[a * floats + 7];
            float det = du1 * dv2 - du2 * dv1;
            if (Math.abs(det) < 1e-12f) {
                continue;
            }
            float r = 1f / det;
            float tx = (e1x * dv2 - e2x * dv1) * r, ty = (e1y * dv2 - e2y * dv1) * r, tz = (e1z * dv2 - e2z * dv1) * r;
            float bx = (e2x * du1 - e1x * du2) * r, by = (e2y * du1 - e1y * du2) * r, bz = (e2z * du1 - e1z * du2) * r;
            for (int i : new int[]{a, b, c}) {
                tan[3 * i] += tx;
                tan[3 * i + 1] += ty;
                tan[3 * i + 2] += tz;
                bit[3 * i] += bx;
                bit[3 * i + 1] += by;
                bit[3 * i + 2] += bz;
            }
        }
        for (int i = 0; i < n; i++) {
            float nx = v[i * floats + 3], ny = v[i * floats + 4], nz = v[i * floats + 5];
            float tx = tan[3 * i], ty = tan[3 * i + 1], tz = tan[3 * i + 2];
            float d = nx * tx + ny * ty + nz * tz;
            tx -= nx * d;
            ty -= ny * d;
            tz -= nz * d;
            float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (len > 1e-12f) {
                tx /= len;
                ty /= len;
                tz /= len;
            }
            float cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
            float sign = cx * bit[3 * i] + cy * bit[3 * i + 1] + cz * bit[3 * i + 2] < 0f ? -1f : 1f;
            v[i * floats + 12] = tx;
            v[i * floats + 13] = ty;
            v[i * floats + 14] = tz;
            v[i * floats + 15] = sign;
        }
    }
}
