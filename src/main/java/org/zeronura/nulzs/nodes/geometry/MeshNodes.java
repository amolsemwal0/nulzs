package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.tex.BlenderRandom;
import org.zeronura.nulzs.nodes.tex.Hash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Mesh operations: distribute points, merge, triangulate, subdivide, extrude, split, convert. */
final class MeshNodes {

    private MeshNodes() {
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("GeometryNodeDistributePointsOnFaces", MeshNodes::distribute);
        map.put("GeometryNodeMergeByDistance", MeshNodes::mergeByDistance);
        map.put("GeometryNodeTriangulate", MeshNodes::triangulate);
        map.put("GeometryNodeFlipFaces", call -> {
            GeometrySet g = call.geometry("Mesh");
            if (g.mesh == null) {
                call.out("Mesh", g);
                return;
            }
            boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), g.mesh, Domain.FACE);
            call.out("Mesh", g.withMesh(GeometryOps.flipFaces(g.mesh, sel)));
        });
        map.put("GeometryNodeSubdivideMesh", call -> subdivide(call, false));
        map.put("GeometryNodeSubdivisionSurface", call -> subdivide(call, true));
        map.put("GeometryNodeExtrudeMesh", MeshNodes::extrude);
        map.put("GeometryNodeSplitEdges", MeshNodes::splitEdges);
        map.put("GeometryNodeMeshToPoints", MeshNodes::meshToPoints);
        map.put("GeometryNodePointsToVertices", call -> {
            GeometrySet g = call.geometry("Points");
            if (g.points == null) {
                call.out("Mesh", GeometrySet.EMPTY);
                return;
            }
            boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), g.points, Domain.POINT);
            PointGeometry kept = GeometryOps.pointsSubset(g.points, sel);
            MeshGeometry mesh = MeshGeometry.build(kept.positions().clone(), new int[]{0}, new int[0], null);
            Attributes attributes = mesh.attributes().copy();
            for (Attributes.Attribute a : kept.attributes().all()) {
                if (!a.name().equals("position") && !a.name().equals("radius")) {
                    attributes.put(a.name(), Domain.POINT, a.data());
                }
            }
            call.out("Mesh", GeometrySet.of(mesh.withAttributes(attributes)));
        });
        map.put("GeometryNodeScaleElements", MeshNodes::scaleElements);
    }

    // --- Distribute points on faces ------------------------------------------------------------------------

    /** Blender's vec_to_quat with -Z tracking the vector and +Y up: the rotation distributed points get. */
    static void normalToRotation(float nx, float ny, float nz, float[] q, int o) {
        q[o] = 1f;
        q[o + 1] = q[o + 2] = q[o + 3] = 0f;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) {
            return;
        }
        float tx = nx, ty = ny, tz = nz;
        float[] nor = {-ty, tx, 0f};
        float co = tz / len;
        float nl = (float) Math.sqrt(nor[0] * nor[0] + nor[1] * nor[1]);
        if (nl > 0f) {
            nor[0] /= nl;
            nor[1] /= nl;
        }
        float angle = (float) Math.acos(Math.clamp(co, -1f, 1f));
        float si = (float) Math.sin(angle * 0.5);
        q[o] = (float) Math.cos(angle * 0.5);
        q[o + 1] = nor[0] * si;
        q[o + 2] = nor[1] * si;
        q[o + 3] = nor[2] * si;
        double[] mat = new double[9];
        Transforms.quatToMat3(q, o, mat);
        float twist = (float) (-0.5 * Math.atan2(-mat[6], -mat[7]));
        float c2 = (float) Math.cos(twist);
        float s2 = (float) Math.sin(twist) / len;
        float[] q2 = {c2, tx * s2, ty * s2, tz * s2};
        float[] result = new float[4];
        Transforms.quatMul(q2, 0, q, o, result, 0);
        System.arraycopy(result, 0, q, o, 4);
        Transforms.quatNormalize(q, o);
    }

    private static void distribute(NodeCall call) {
        GeometrySet input = call.geometry("Mesh");
        MeshGeometry mesh = input.mesh;
        if (mesh == null || mesh.faceCount() == 0) {
            call.out("Points", GeometrySet.EMPTY);
            return;
        }
        boolean poisson = "POISSON".equals(call.prop("distribute_method", "RANDOM"));
        boolean legacyNormal = call.propBool("use_legacy_normal", false);
        int seed = call.i("Seed");
        FieldContext corners = call.context(mesh, Domain.CORNER);
        Column selection = corners.evalAs(call.field("Selection", DataType.BOOL), DataType.BOOL);
        Column factorColumn = corners.evalAs(call.field("Density Factor", DataType.FLOAT), DataType.FLOAT);
        float[] factors = new float[mesh.cornerCount()];
        for (int c = 0; c < factors.length; c++) {
            factors[c] = selection.i[c] != 0 ? factorColumn.f[c] : 0f;
        }
        List<int[]> tris = new ArrayList<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            tris.addAll(Triangulation.face(mesh, f));
        }
        float density = poisson ? call.f("Density Max") : call.f("Density");
        float[] pos = mesh.positions();
        int[] cv = mesh.topology.cornerVerts;
        List<float[]> bary = new ArrayList<>();
        List<Integer> triOf = new ArrayList<>();
        float[] w = new float[3];
        for (int t = 0; t < tris.size(); t++) {
            int[] tri = tris.get(t);
            int a = cv[tri[0]], b = cv[tri[1]], c = cv[tri[2]];
            float area = triangleArea(pos, a, b, c);
            float factor = poisson ? 1f : (factors[tri[0]] + factors[tri[1]] + factors[tri[2]]) / 3f;
            // Blender spreads the node's seed by a fixed multiplier before hashing it with the triangle index.
            BlenderRandom rng = new BlenderRandom(Hash.hash(t, seed * 5383843));
            int amount = rng.roundProbabilistic(area * density * factor);
            for (int i = 0; i < amount; i++) {
                rng.barycentric(w);
                bary.add(w.clone());
                triOf.add(t);
            }
        }
        int n = bary.size();
        float[] positions = new float[n * 3];
        for (int i = 0; i < n; i++) {
            int[] tri = tris.get(triOf.get(i));
            float[] b = bary.get(i);
            for (int k = 0; k < 3; k++) {
                positions[3 * i + k] = pos[3 * cv[tri[0]] + k] * b[0] + pos[3 * cv[tri[1]] + k] * b[1] + pos[3 * cv[tri[2]] + k] * b[2];
            }
        }
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);
        if (poisson) {
            float minDistance = call.f("Distance Min");
            if (minDistance > 0f) {
                KdTree tree = new KdTree(positions);
                for (int i = 0; i < n; i++) {
                    if (!keep[i]) {
                        continue;
                    }
                    int self = i;
                    tree.within(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2], minDistance, j -> {
                        if (j != self) {
                            keep[j] = false;
                        }
                    });
                }
            }
            for (int i = 0; i < n; i++) {
                if (!keep[i]) {
                    continue;
                }
                int[] tri = tris.get(triOf.get(i));
                float[] b = bary.get(i);
                float probability = Math.max(0f, factors[tri[0]]) * b[0] + Math.max(0f, factors[tri[1]]) * b[1]
                        + Math.max(0f, factors[tri[2]]) * b[2];
                if (Hash.vec3ToFloat(b[0], b[1], b[2]) > probability) {
                    keep[i] = false;
                }
            }
        }
        int[] kept = poisson ? swapRemoved(keep) : GeometryOps.indices(keep);
        int count = kept.length;
        float[] outPositions = new float[count * 3];
        int[] ids = new int[count];
        float[] normals = new float[count * 3];
        float[] rotations = new float[count * 4];
        float[] faceNormals = mesh.faceNormals();
        float[] cornerNormals = legacyNormal ? null : mesh.cornerNormals();
        int[] cornerFaces = mesh.topology.cornerFaces();
        for (int j = 0; j < count; j++) {
            int i = kept[j];
            System.arraycopy(positions, 3 * i, outPositions, 3 * j, 3);
            float[] b = bary.get(i);
            int[] tri = tris.get(triOf.get(i));
            ids[j] = Hash.hash(Hash.hashFloat(b[0], b[1], b[2]), triOf.get(i));
            float nx, ny, nz;
            if (legacyNormal) {
                int f = cornerFaces[tri[0]];
                nx = faceNormals[3 * f];
                ny = faceNormals[3 * f + 1];
                nz = faceNormals[3 * f + 2];
            } else {
                nx = cornerNormals[3 * tri[0]] * b[0] + cornerNormals[3 * tri[1]] * b[1] + cornerNormals[3 * tri[2]] * b[2];
                ny = cornerNormals[3 * tri[0] + 1] * b[0] + cornerNormals[3 * tri[1] + 1] * b[1] + cornerNormals[3 * tri[2] + 1] * b[2];
                nz = cornerNormals[3 * tri[0] + 2] * b[0] + cornerNormals[3 * tri[1] + 2] * b[1] + cornerNormals[3 * tri[2] + 2] * b[2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 0f) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                }
            }
            normals[3 * j] = nx;
            normals[3 * j + 1] = ny;
            normals[3 * j + 2] = nz;
            normalToRotation(nx, ny, nz, rotations, 4 * j);
        }
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, outPositions));
        attributes.put("id", Domain.POINT, Column.ofInts(DataType.INT, ids));
        for (Attributes.Attribute a : mesh.attributes().all()) {
            String name = a.name();
            if (name.equals("position") || name.equals("id") || name.equals("sharp_face") || name.equals("sharp_edge")
                    || name.equals("material_index") || name.equals(".corner_normals")) {
                continue;
            }
            Column corner = mesh.adapt(a.data(), a.domain(), Domain.CORNER);
            if (corner.size != mesh.cornerCount()) {
                continue;
            }
            Column out = Column.create(a.data().type, count);
            for (int j = 0; j < count; j++) {
                int i = kept[j];
                int[] tri = tris.get(triOf.get(i));
                float[] b = bary.get(i);
                if (corner.type.integer) {
                    int best = b[0] >= b[1] && b[0] >= b[2] ? 0 : b[1] >= b[2] ? 1 : 2;
                    out.i[j] = corner.i[tri[best]];
                } else {
                    int width = corner.type.width;
                    for (int k = 0; k < width; k++) {
                        out.f[j * width + k] = corner.f[tri[0] * width + k] * b[0] + corner.f[tri[1] * width + k] * b[1]
                                + corner.f[tri[2] * width + k] * b[2];
                    }
                }
            }
            attributes.put(name, Domain.POINT, out);
        }
        String normalName = MeshPrimitives.anonymous("normal");
        String rotationName = MeshPrimitives.anonymous("rotation");
        attributes.put(normalName, Domain.POINT, Column.ofFloats(DataType.VECTOR, normals));
        attributes.put(rotationName, Domain.POINT, Column.ofFloats(DataType.ROTATION, rotations));
        call.out("Points", GeometrySet.of(new PointGeometry(count, attributes)));
        call.out("Normal", Inputs.named(normalName, DataType.VECTOR));
        call.out("Rotation", Inputs.named(rotationName, DataType.ROTATION));
    }

    /**
     * The survivors of Poisson elimination in Blender's order: it walks from the end, moving the last point into
     * each removed slot, so the order is not simply the sampled one.
     */
    private static int[] swapRemoved(boolean[] keep) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) {
            order.add(i);
        }
        for (int i = keep.length - 1; i >= 0; i--) {
            if (!keep[i]) {
                int last = order.remove(order.size() - 1);
                if (i < order.size()) {
                    order.set(i, last);
                }
            }
        }
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    private static float triangleArea(float[] p, int a, int b, int c) {
        float e1x = p[3 * b] - p[3 * a], e1y = p[3 * b + 1] - p[3 * a + 1], e1z = p[3 * b + 2] - p[3 * a + 2];
        float e2x = p[3 * c] - p[3 * a], e2y = p[3 * c + 1] - p[3 * a + 1], e2z = p[3 * c + 2] - p[3 * a + 2];
        float x = e1y * e2z - e1z * e2y, y = e1z * e2x - e1x * e2z, z = e1x * e2y - e1y * e2x;
        return 0.5f * (float) Math.sqrt(x * x + y * y + z * z);
    }

    // --- Merge by distance ---------------------------------------------------------------------------------

    private static void mergeByDistance(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        float distance = call.f("Distance");
        boolean connected = call.has("Mode") && call.string("Mode").toLowerCase().startsWith("conn");
        GeometrySet out = geometry;
        if (geometry.points != null && geometry.points.count() > 0) {
            PointGeometry points = geometry.points;
            boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), points, Domain.POINT);
            int[] target = weldTargets(points.positions(), sel, distance);
            boolean[] keep = new boolean[points.count()];
            for (int k = 0; k < keep.length; k++) {
                keep[k] = target[k] == k;
            }
            out = out.withPoints(GeometryOps.pointsSubset(points, keep));
        }
        if (geometry.mesh != null && geometry.mesh.vertCount() > 0) {
            MeshGeometry mesh = geometry.mesh;
            boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), mesh, Domain.POINT);
            int[] target = connected ? connectedTargets(mesh, sel, distance) : weldTargets(mesh.positions(), sel, distance);
            out = out.withMesh(weld(mesh, target));
        }
        call.out("Geometry", out);
    }

    /** For each point, the lowest-indexed selected point within the distance of it (itself if none). */
    private static int[] weldTargets(float[] positions, boolean[] selection, float distance) {
        int n = positions.length / 3;
        int[] target = new int[n];
        for (int k = 0; k < n; k++) {
            target[k] = k;
        }
        KdTree tree = new KdTree(positions);
        for (int k = 0; k < n; k++) {
            if (!selection[k] || target[k] != k) {
                continue;
            }
            int self = k;
            tree.within(positions[3 * k], positions[3 * k + 1], positions[3 * k + 2], distance, j -> {
                if (j > self && selection[j] && target[j] == j) {
                    target[j] = self;
                }
            });
        }
        return target;
    }

    private static int[] connectedTargets(MeshGeometry mesh, boolean[] selection, float distance) {
        int n = mesh.vertCount();
        int[] parent = new int[n];
        for (int k = 0; k < n; k++) {
            parent[k] = k;
        }
        float[] p = mesh.positions();
        int[] ev = mesh.topology.edgeVerts;
        float d2 = distance * distance;
        for (int e = 0; e < mesh.edgeCount(); e++) {
            int a = ev[2 * e], b = ev[2 * e + 1];
            if (!selection[a] || !selection[b]) {
                continue;
            }
            float dx = p[3 * a] - p[3 * b], dy = p[3 * a + 1] - p[3 * b + 1], dz = p[3 * a + 2] - p[3 * b + 2];
            if (dx * dx + dy * dy + dz * dz <= d2) {
                int ra = find(parent, a), rb = find(parent, b);
                parent[Math.max(ra, rb)] = Math.min(ra, rb);
            }
        }
        for (int k = 0; k < n; k++) {
            parent[k] = find(parent, k);
        }
        return parent;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    /** Collapses each vertex onto its target, dropping faces and edges that collapse to nothing. */
    static MeshGeometry weld(MeshGeometry mesh, int[] target) {
        int n = mesh.vertCount();
        int[] newIndex = new int[n];
        int count = 0;
        for (int v = 0; v < n; v++) {
            newIndex[v] = target[v] == v ? count++ : -1;
        }
        if (count == n) {
            return mesh;
        }
        int[] vertMap = new int[count];
        for (int v = 0; v < n; v++) {
            if (target[v] == v) {
                vertMap[newIndex[v]] = v;
            }
        }
        MeshGeometry.Topology t = mesh.topology;
        List<Integer> faceSources = new ArrayList<>();
        List<Integer> cornerSources = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        List<Integer> cornerVerts = new ArrayList<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            List<Integer> verts = new ArrayList<>();
            List<Integer> sources = new ArrayList<>();
            for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                int v = newIndex[target[t.cornerVerts[c]]];
                if (!verts.isEmpty() && verts.get(verts.size() - 1) == v) {
                    continue;
                }
                verts.add(v);
                sources.add(c);
            }
            while (verts.size() > 1 && verts.get(0).equals(verts.get(verts.size() - 1))) {
                verts.remove(verts.size() - 1);
                sources.remove(sources.size() - 1);
            }
            if (verts.size() < 3) {
                continue;
            }
            offsets.add(cornerVerts.size());
            cornerVerts.addAll(verts);
            cornerSources.addAll(sources);
            faceSources.add(f);
        }
        offsets.add(cornerVerts.size());
        List<Integer> loose = new ArrayList<>();
        int[][] edgeFaces = t.edgeFaces();
        for (int e = 0; e < mesh.edgeCount(); e++) {
            if (edgeFaces[e].length == 0) {
                int a = newIndex[target[t.edgeVerts[2 * e]]], b = newIndex[target[t.edgeVerts[2 * e + 1]]];
                if (a != b) {
                    loose.add(a);
                    loose.add(b);
                }
            }
        }
        // Merged vertices meet at the average of their group, as Blender's weld places them.
        float[] source = mesh.positions();
        float[] positions = new float[count * 3];
        int[] members = new int[count];
        for (int v = 0; v < n; v++) {
            int into = newIndex[target[v]];
            members[into]++;
            for (int a = 0; a < 3; a++) {
                positions[3 * into + a] += source[3 * v + a];
            }
        }
        for (int k = 0; k < count; k++) {
            for (int a = 0; a < 3; a++) {
                positions[3 * k + a] /= members[k];
            }
        }
        MeshGeometry out = MeshGeometry.build(positions, offsets.stream().mapToInt(Integer::intValue).toArray(),
                cornerVerts.stream().mapToInt(Integer::intValue).toArray(), loose.stream().mapToInt(Integer::intValue).toArray());
        int[] faceMap = faceSources.stream().mapToInt(Integer::intValue).toArray();
        int[] cornerMap = cornerSources.stream().mapToInt(Integer::intValue).toArray();
        Attributes attributes = out.attributes().copy();
        for (Attributes.Attribute a : mesh.attributes().all()) {
            switch (a.domain()) {
                case POINT -> {
                    if (!a.name().equals("position")) {
                        attributes.put(a.name(), Domain.POINT, a.data().gather(vertMap));
                    }
                }
                case FACE -> attributes.put(a.name(), Domain.FACE, a.data().gather(faceMap));
                case CORNER -> {
                    if (!a.name().equals(".corner_normals")) {
                        attributes.put(a.name(), Domain.CORNER, a.data().gather(cornerMap));
                    }
                }
                default -> {
                }
            }
        }
        return new MeshGeometry(out.topology, attributes, mesh.materials);
    }

    // --- Triangulate ---------------------------------------------------------------------------------------

    private static void triangulate(NodeCall call) {
        GeometrySet geometry = call.geometry("Mesh");
        MeshGeometry mesh = geometry.mesh;
        if (mesh == null) {
            call.out("Mesh", geometry);
            return;
        }
        boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), mesh, Domain.FACE);
        String quadMethod = call.has("Quad Method") ? call.string("Quad Method") : "Shortest Diagonal";
        MeshGeometry.Topology t = mesh.topology;
        float[] p = mesh.positions();
        List<Integer> faceSources = new ArrayList<>();
        List<Integer> cornerSources = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        List<Integer> verts = new ArrayList<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            int size = t.faceSize(f);
            int start = t.faceOffsets[f];
            List<int[]> tris;
            if (!sel[f] || size == 3) {
                tris = List.of(range(start, size));
            } else if (size == 4) {
                // Blender splits along 0-2 or 1-3 and orders the corners as below; a tie takes 1-3.
                boolean split02 = switch (quadMethod) {
                    case "Fixed" -> true;
                    case "Fixed Alternate" -> false;
                    case "Longest Diagonal" -> diagonal(p, t, start, 0, 2) > diagonal(p, t, start, 1, 3);
                    default -> diagonal(p, t, start, 0, 2) < diagonal(p, t, start, 1, 3);
                };
                tris = split02 ? List.of(new int[]{start + 2, start, start + 1}, new int[]{start, start + 2, start + 3})
                        : List.of(new int[]{start + 1, start + 3, start}, new int[]{start + 3, start + 1, start + 2});
            } else {
                tris = Triangulation.face(mesh, f);
            }
            for (int[] tri : tris) {
                offsets.add(verts.size());
                for (int c : tri) {
                    verts.add(t.cornerVerts[c]);
                    cornerSources.add(c);
                }
                faceSources.add(f);
            }
        }
        offsets.add(verts.size());
        MeshGeometry rebuilt = rebuild(mesh, offsets, verts, faceSources, cornerSources);
        call.out("Mesh", geometry.withMesh(rebuilt));
    }

    private static float diagonal(float[] p, MeshGeometry.Topology t, int start, int a, int b) {
        int va = t.cornerVerts[start + a], vb = t.cornerVerts[start + b];
        float dx = p[3 * va] - p[3 * vb], dy = p[3 * va + 1] - p[3 * vb + 1], dz = p[3 * va + 2] - p[3 * vb + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static int[] range(int start, int size) {
        int[] out = new int[size];
        for (int k = 0; k < size; k++) {
            out[k] = start + k;
        }
        return out;
    }

    /**
     * A mesh with the same vertices and new faces, face and corner attributes carried from their sources. The
     * original edges keep their order and new ones follow, as Blender appends them.
     */
    static MeshGeometry rebuild(MeshGeometry mesh, List<Integer> offsets, List<Integer> verts, List<Integer> faceSources,
                                List<Integer> cornerSources) {
        MeshGeometry out = MeshGeometry.build(mesh.positions(), offsets.stream().mapToInt(Integer::intValue).toArray(),
                verts.stream().mapToInt(Integer::intValue).toArray(), mesh.topology.edgeVerts);
        int[] faceMap = faceSources.stream().mapToInt(Integer::intValue).toArray();
        int[] cornerMap = cornerSources.stream().mapToInt(Integer::intValue).toArray();
        Attributes attributes = out.attributes().copy();
        for (Attributes.Attribute a : mesh.attributes().all()) {
            switch (a.domain()) {
                case POINT -> attributes.put(a.name(), Domain.POINT, a.data());
                case EDGE -> {
                    Column extended = Column.create(a.data().type, out.edgeCount());
                    for (int e = 0; e < Math.min(a.data().size, extended.size); e++) {
                        extended.copyElement(a.data(), e, e);
                    }
                    attributes.put(a.name(), Domain.EDGE, extended);
                }
                case FACE -> attributes.put(a.name(), Domain.FACE, a.data().gather(faceMap));
                case CORNER -> {
                    if (!a.name().equals(".corner_normals")) {
                        attributes.put(a.name(), Domain.CORNER, a.data().gather(cornerMap));
                    }
                }
                default -> {
                }
            }
        }
        return new MeshGeometry(out.topology, attributes, mesh.materials);
    }

    private static int[] looseEdges(MeshGeometry mesh) {
        int[][] edgeFaces = mesh.topology.edgeFaces();
        List<Integer> loose = new ArrayList<>();
        for (int e = 0; e < mesh.edgeCount(); e++) {
            if (edgeFaces[e].length == 0) {
                loose.add(mesh.topology.edgeVerts[2 * e]);
                loose.add(mesh.topology.edgeVerts[2 * e + 1]);
            }
        }
        return loose.stream().mapToInt(Integer::intValue).toArray();
    }

    // --- Subdivision ---------------------------------------------------------------------------------------

    private static void subdivide(NodeCall call, boolean smooth) {
        GeometrySet geometry = call.geometry("Mesh");
        MeshGeometry mesh = geometry.mesh;
        int levels = Math.min(call.i("Level"), 6);
        if (mesh == null || levels <= 0) {
            call.out("Mesh", geometry);
            return;
        }
        for (int level = 0; level < levels; level++) {
            mesh = subdivideOnce(mesh, smooth);
        }
        call.out("Mesh", geometry.withMesh(mesh));
    }

    /**
     * One level of subdivision: every n-gon becomes n quads around a new face point, with new points at edge
     * midpoints. {@code smooth} moves points by the Catmull-Clark rules (boundaries kept as smooth curves).
     * New vertices come after the originals: edge points in edge order, then face points in face order.
     */
    static MeshGeometry subdivideOnce(MeshGeometry mesh, boolean smooth) {
        MeshGeometry.Topology t = mesh.topology;
        int verts = mesh.vertCount(), edges = mesh.edgeCount(), faces = mesh.faceCount();
        float[] p = mesh.positions();
        float[] facePoints = new float[faces * 3];
        for (int f = 0; f < faces; f++) {
            float[] c = InputNodes.faceCenter(mesh, f, p);
            System.arraycopy(c, 0, facePoints, 3 * f, 3);
        }
        int[][] edgeFaces = t.edgeFaces();
        float[] edgePoints = new float[edges * 3];
        for (int e = 0; e < edges; e++) {
            int a = t.edgeVerts[2 * e], b = t.edgeVerts[2 * e + 1];
            for (int k = 0; k < 3; k++) {
                float mid = (p[3 * a + k] + p[3 * b + k]) * 0.5f;
                if (smooth && edgeFaces[e].length == 2) {
                    float fa = facePoints[3 * edgeFaces[e][0] + k], fb = facePoints[3 * edgeFaces[e][1] + k];
                    edgePoints[3 * e + k] = (mid * 2f + fa + fb) / 4f;
                } else {
                    edgePoints[3 * e + k] = mid;
                }
            }
        }
        float[] moved = p.clone();
        if (smooth) {
            int[][] vertEdges = t.vertEdges();
            int[][] vertFaces = t.vertFaces();
            for (int v = 0; v < verts; v++) {
                int boundaryEdges = 0;
                float[] boundarySum = new float[3];
                for (int e : vertEdges[v]) {
                    if (edgeFaces[e].length < 2) {
                        boundaryEdges++;
                        int other = t.edgeVerts[2 * e] == v ? t.edgeVerts[2 * e + 1] : t.edgeVerts[2 * e];
                        for (int k = 0; k < 3; k++) {
                            boundarySum[k] += p[3 * other + k];
                        }
                    }
                }
                if (boundaryEdges >= 2) {
                    for (int k = 0; k < 3; k++) {
                        moved[3 * v + k] = (6f * p[3 * v + k] + boundarySum[k] * (2f / boundaryEdges)) / 8f;
                    }
                    continue;
                }
                int valence = vertEdges[v].length;
                int[] adjacentFaces = Arrays.stream(vertFaces[v]).distinct().toArray();
                if (valence < 3 || adjacentFaces.length == 0) {
                    continue;
                }
                float[] fAvg = new float[3], rAvg = new float[3];
                for (int f : adjacentFaces) {
                    for (int k = 0; k < 3; k++) {
                        fAvg[k] += facePoints[3 * f + k] / adjacentFaces.length;
                    }
                }
                for (int e : vertEdges[v]) {
                    int a = t.edgeVerts[2 * e], b = t.edgeVerts[2 * e + 1];
                    for (int k = 0; k < 3; k++) {
                        rAvg[k] += (p[3 * a + k] + p[3 * b + k]) * 0.5f / valence;
                    }
                }
                for (int k = 0; k < 3; k++) {
                    moved[3 * v + k] = (fAvg[k] + 2f * rAvg[k] + (valence - 3) * p[3 * v + k]) / valence;
                }
            }
        }
        int total = verts + edges + faces;
        float[] positions = new float[total * 3];
        System.arraycopy(moved, 0, positions, 0, verts * 3);
        System.arraycopy(edgePoints, 0, positions, verts * 3, edges * 3);
        System.arraycopy(facePoints, 0, positions, (verts + edges) * 3, faces * 3);
        int corners = t.cornerCount();
        int[] offsets = new int[corners + 1];
        int[] cornerVerts = new int[corners * 4];
        int[] faceSource = new int[corners];
        int[][] cornerWeights = new int[corners * 4][];
        int q = 0;
        for (int f = 0; f < faces; f++) {
            int start = t.faceOffsets[f], end = t.faceOffsets[f + 1];
            for (int c = start; c < end; c++) {
                int prev = c == start ? end - 1 : c - 1;
                offsets[q] = 4 * q;
                cornerVerts[4 * q] = t.cornerVerts[c];
                cornerVerts[4 * q + 1] = verts + t.cornerEdges[c];
                cornerVerts[4 * q + 2] = verts + edges + f;
                cornerVerts[4 * q + 3] = verts + t.cornerEdges[prev];
                cornerWeights[4 * q] = new int[]{c};
                cornerWeights[4 * q + 1] = new int[]{c, c + 1 == end ? start : c + 1};
                cornerWeights[4 * q + 2] = range(start, end - start);
                cornerWeights[4 * q + 3] = new int[]{prev, c};
                faceSource[q] = f;
                q++;
            }
        }
        offsets[corners] = corners * 4;
        MeshGeometry out = MeshGeometry.build(positions, offsets, cornerVerts, null);
        Attributes attributes = out.attributes().copy();
        for (Attributes.Attribute a : mesh.attributes().all()) {
            Column data = a.data();
            switch (a.domain()) {
                case POINT -> {
                    if (a.name().equals("position")) {
                        continue;
                    }
                    Column edgeValues = mesh.adapt(data, Domain.POINT, Domain.EDGE);
                    Column faceValues = mesh.adapt(data, Domain.POINT, Domain.FACE);
                    attributes.put(a.name(), Domain.POINT, Column.concat(data.type, List.of(data, edgeValues, faceValues)));
                }
                case FACE -> attributes.put(a.name(), Domain.FACE, data.gather(faceSource));
                case CORNER -> {
                    if (a.name().equals(".corner_normals")) {
                        continue;
                    }
                    Column values = Column.create(data.type, corners * 4);
                    for (int c = 0; c < corners * 4; c++) {
                        averageInto(data, cornerWeights[c], values, c);
                    }
                    attributes.put(a.name(), Domain.CORNER, values);
                }
                default -> {
                }
            }
        }
        return new MeshGeometry(out.topology, attributes, mesh.materials);
    }

    private static void averageInto(Column source, int[] from, Column out, int at) {
        if (source.type.integer) {
            out.i[at] = source.i[from[0]];
            return;
        }
        int w = source.type.width;
        for (int s : from) {
            for (int k = 0; k < w; k++) {
                out.f[at * w + k] += source.f[s * w + k] / from.length;
            }
        }
    }

    // --- Extrude -------------------------------------------------------------------------------------------

    private static void extrude(NodeCall call) {
        GeometrySet geometry = call.geometry("Mesh");
        MeshGeometry mesh = geometry.mesh;
        if (mesh == null) {
            call.out("Mesh", geometry);
            return;
        }
        String mode = call.prop("mode", "FACES");
        Field offsetField = call.field("Offset", DataType.VECTOR);
        Field scaleField = call.field("Offset Scale", DataType.FLOAT);
        boolean individual = call.has("Individual") && call.b("Individual");
        Domain domain = switch (mode) {
            case "VERTICES" -> Domain.POINT;
            case "EDGES" -> Domain.EDGE;
            default -> Domain.FACE;
        };
        FieldContext context = call.context(mesh, domain);
        boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), mesh, domain);
        Column offsets = context.evalAs(offsetField, DataType.VECTOR);
        Column scales = context.evalAs(scaleField, DataType.FLOAT);
        float[] offset = new float[offsets.f.length];
        for (int k = 0; k < offset.length; k++) {
            offset[k] = offsets.f[k] * scales.f[k / 3];
        }
        Extrusion result = switch (mode) {
            case "VERTICES" -> extrudeVertices(mesh, sel, offset);
            case "EDGES" -> extrudeEdges(mesh, sel, offset);
            default -> extrudeFaces(mesh, sel, offset, individual);
        };
        String topName = MeshPrimitives.anonymous("top");
        String sideName = MeshPrimitives.anonymous("side");
        MeshGeometry out = (MeshGeometry) GeometryOps.withAttribute(result.mesh, topName, result.topDomain, result.top);
        out = (MeshGeometry) GeometryOps.withAttribute(out, sideName, Domain.FACE, result.side);
        call.out("Mesh", geometry.withMesh(out));
        call.out("Top", Inputs.named(topName, DataType.BOOL));
        call.out("Side", Inputs.named(sideName, DataType.BOOL));
    }

    private record Extrusion(MeshGeometry mesh, Domain topDomain, Column top, Column side) {
    }

    /** Assembles a mesh from the original plus new vertices and faces, carrying point, face and corner data. */
    private static MeshGeometry assemble(MeshGeometry mesh, float[] positions, int[] vertSource, List<int[]> faces,
                                         List<Integer> faceSource, List<int[]> cornerSource, int[] extraEdges) {
        int corners = 0;
        for (int[] f : faces) {
            corners += f.length;
        }
        int[] offsets = new int[faces.size() + 1];
        int[] cv = new int[corners];
        int at = 0;
        for (int k = 0; k < faces.size(); k++) {
            offsets[k] = at;
            for (int v : faces.get(k)) {
                cv[at++] = v;
            }
        }
        offsets[faces.size()] = corners;
        MeshGeometry out = MeshGeometry.build(positions, offsets, cv, extraEdges);
        Attributes attributes = out.attributes().copy();
        int[] faceMap = faceSource.stream().mapToInt(Integer::intValue).toArray();
        int[] cornerMap = new int[corners];
        at = 0;
        for (int[] sources : cornerSource) {
            for (int s : sources) {
                cornerMap[at++] = Math.max(s, 0);
            }
        }
        for (Attributes.Attribute a : mesh.attributes().all()) {
            switch (a.domain()) {
                case POINT -> {
                    if (!a.name().equals("position")) {
                        attributes.put(a.name(), Domain.POINT, a.data().gather(vertSource));
                    }
                }
                case FACE -> attributes.put(a.name(), Domain.FACE, a.data().gather(faceMap));
                case CORNER -> {
                    if (!a.name().equals(".corner_normals")) {
                        attributes.put(a.name(), Domain.CORNER, a.data().gather(cornerMap));
                    }
                }
                default -> {
                }
            }
        }
        return new MeshGeometry(out.topology, attributes, mesh.materials);
    }

    private static List<int[]> facesOf(MeshGeometry mesh, List<Integer> faceSource, List<int[]> cornerSource) {
        MeshGeometry.Topology t = mesh.topology;
        List<int[]> faces = new ArrayList<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            int[] verts = new int[t.faceSize(f)];
            int[] corners = new int[verts.length];
            for (int k = 0; k < verts.length; k++) {
                verts[k] = t.cornerVerts[t.faceOffsets[f] + k];
                corners[k] = t.faceOffsets[f] + k;
            }
            faces.add(verts);
            faceSource.add(f);
            cornerSource.add(corners);
        }
        return faces;
    }

    private static Extrusion extrudeVertices(MeshGeometry mesh, boolean[] sel, float[] offset) {
        int[] selected = GeometryOps.indices(sel);
        int verts = mesh.vertCount();
        float[] p = mesh.positions();
        float[] positions = Arrays.copyOf(p, (verts + selected.length) * 3);
        int[] vertSource = new int[verts + selected.length];
        for (int v = 0; v < verts; v++) {
            vertSource[v] = v;
        }
        List<Integer> edges = new ArrayList<>();
        for (int k = 0; k < mesh.edgeCount(); k++) {
            edges.add(mesh.topology.edgeVerts[2 * k]);
            edges.add(mesh.topology.edgeVerts[2 * k + 1]);
        }
        for (int k = 0; k < selected.length; k++) {
            int v = selected[k];
            int nv = verts + k;
            vertSource[nv] = v;
            for (int a = 0; a < 3; a++) {
                positions[3 * nv + a] = p[3 * v + a] + offset[3 * v + a];
            }
            edges.add(v);
            edges.add(nv);
        }
        List<Integer> faceSource = new ArrayList<>();
        List<int[]> cornerSource = new ArrayList<>();
        List<int[]> faces = facesOf(mesh, faceSource, cornerSource);
        MeshGeometry out = assemble(mesh, positions, vertSource, faces, faceSource, cornerSource,
                edges.stream().mapToInt(Integer::intValue).toArray());
        int[] top = new int[out.vertCount()];
        for (int k = verts; k < top.length; k++) {
            top[k] = 1;
        }
        return new Extrusion(out, Domain.POINT, Column.ofInts(DataType.BOOL, top),
                Column.create(DataType.BOOL, out.faceCount()));
    }

    private static Extrusion extrudeEdges(MeshGeometry mesh, boolean[] sel, float[] edgeOffsets) {
        MeshGeometry.Topology t = mesh.topology;
        int verts = mesh.vertCount();
        float[] p = mesh.positions();
        int[] dup = new int[verts];
        Arrays.fill(dup, -1);
        List<Integer> vertSource = new ArrayList<>();
        for (int v = 0; v < verts; v++) {
            vertSource.add(v);
        }
        float[] vertOffset = new float[verts * 3];
        int[] counts = new int[verts];
        for (int e = 0; e < mesh.edgeCount(); e++) {
            if (!sel[e]) {
                continue;
            }
            for (int end = 0; end < 2; end++) {
                int v = t.edgeVerts[2 * e + end];
                counts[v]++;
                for (int a = 0; a < 3; a++) {
                    vertOffset[3 * v + a] += edgeOffsets[3 * e + a];
                }
                if (dup[v] < 0) {
                    dup[v] = vertSource.size();
                    vertSource.add(v);
                }
            }
        }
        float[] positions = Arrays.copyOf(p, vertSource.size() * 3);
        for (int v = 0; v < verts; v++) {
            if (dup[v] >= 0) {
                for (int a = 0; a < 3; a++) {
                    positions[3 * dup[v] + a] = p[3 * v + a] + vertOffset[3 * v + a] / counts[v];
                }
            }
        }
        List<Integer> faceSource = new ArrayList<>();
        List<int[]> cornerSource = new ArrayList<>();
        List<int[]> faces = facesOf(mesh, faceSource, cornerSource);
        int originalFaces = faces.size();
        int[][] edgeFaces = t.edgeFaces();
        for (int e = 0; e < mesh.edgeCount(); e++) {
            if (!sel[e]) {
                continue;
            }
            int a = t.edgeVerts[2 * e], b = t.edgeVerts[2 * e + 1];
            faces.add(new int[]{a, b, dup[b], dup[a]});
            faceSource.add(edgeFaces[e].length > 0 ? edgeFaces[e][0] : 0);
            cornerSource.add(new int[]{0, 0, 0, 0});
        }
        int[] vs = vertSource.stream().mapToInt(Integer::intValue).toArray();
        MeshGeometry out = assemble(mesh, positions, vs, faces, faceSource, cornerSource, looseEdges(mesh));
        int[] side = new int[out.faceCount()];
        for (int f = originalFaces; f < side.length; f++) {
            side[f] = 1;
        }
        int[] top = new int[out.edgeCount()];
        return new Extrusion(out, Domain.EDGE, Column.ofInts(DataType.BOOL, top), Column.ofInts(DataType.BOOL, side));
    }

    /**
     * Extrudes faces, as a connected region or each on its own. Edges come out in Blender's order: the original
     * ones (those inside a region moved to the new vertices), then one edge from each original vertex to its copy,
     * then the copies of the region's boundary edges, face by face. A vertex used only by extruded faces moves
     * instead of being copied, so no stray vertex is left behind.
     */
    private static Extrusion extrudeFaces(MeshGeometry mesh, boolean[] sel, float[] faceOffsets, boolean individual) {
        MeshGeometry.Topology t = mesh.topology;
        int verts = mesh.vertCount();
        float[] p = mesh.positions();
        float[] moved = p.clone();
        List<Integer> vertSource = new ArrayList<>();
        for (int v = 0; v < verts; v++) {
            vertSource.add(v);
        }
        List<float[]> newPositions = new ArrayList<>();
        List<Integer> faceSource = new ArrayList<>();
        List<int[]> cornerSource = new ArrayList<>();
        List<int[]> faces = facesOf(mesh, faceSource, cornerSource);
        List<Boolean> isTop = new ArrayList<>();
        List<Boolean> isSide = new ArrayList<>();
        for (int f = 0; f < faces.size(); f++) {
            isTop.add(false);
            isSide.add(false);
        }
        int[] edges = t.edgeVerts.clone();
        List<Integer> connect = new ArrayList<>();
        List<Integer> duplicated = new ArrayList<>();
        int[][] edgeFaces = t.edgeFaces();
        if (individual) {
            for (int f = 0; f < mesh.faceCount(); f++) {
                if (!sel[f]) {
                    continue;
                }
                int start = t.faceOffsets[f], n = t.faceSize(f);
                int[] top = new int[n];
                for (int k = 0; k < n; k++) {
                    int v = t.cornerVerts[start + k];
                    top[k] = verts + newPositions.size();
                    vertSource.add(v);
                    newPositions.add(new float[]{p[3 * v] + faceOffsets[3 * f], p[3 * v + 1] + faceOffsets[3 * f + 1],
                            p[3 * v + 2] + faceOffsets[3 * f + 2]});
                    connect.add(v);
                    connect.add(top[k]);
                }
                for (int k = 0; k < n; k++) {
                    duplicated.add(top[k]);
                    duplicated.add(top[(k + 1) % n]);
                }
                for (int k = 0; k < n; k++) {
                    int a = t.cornerVerts[start + k], b = t.cornerVerts[start + (k + 1) % n];
                    faces.add(new int[]{top[(k + 1) % n], top[k], a, b});
                    faceSource.add(f);
                    cornerSource.add(new int[]{start + (k + 1) % n, start + k, start + k, start + (k + 1) % n});
                    isTop.add(false);
                    isSide.add(true);
                }
                faces.set(f, top);
                isTop.set(f, true);
            }
        } else {
            int[][] vertFaces = t.vertFaces();
            int[][] vertEdges = t.vertEdges();
            int[] target = new int[verts];
            Arrays.fill(target, -1);
            float[] sum = new float[verts * 3];
            int[] count = new int[verts];
            for (int f = 0; f < mesh.faceCount(); f++) {
                if (!sel[f]) {
                    continue;
                }
                for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                    int v = t.cornerVerts[c];
                    count[v]++;
                    for (int a = 0; a < 3; a++) {
                        sum[3 * v + a] += faceOffsets[3 * f + a];
                    }
                }
            }
            for (int f = 0; f < mesh.faceCount(); f++) {
                if (!sel[f]) {
                    continue;
                }
                for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                    int v = t.cornerVerts[c];
                    if (target[v] >= 0) {
                        continue;
                    }
                    boolean inside = true;
                    for (int g : vertFaces[v]) {
                        inside &= sel[g];
                    }
                    for (int e : vertEdges[v]) {
                        boolean interior = edgeFaces[e].length > 0;
                        for (int g : edgeFaces[e]) {
                            interior &= sel[g];
                        }
                        inside &= interior;
                    }
                    if (inside) {
                        target[v] = v;
                    } else {
                        target[v] = verts + newPositions.size();
                        vertSource.add(v);
                        newPositions.add(new float[3]);
                        connect.add(v);
                        connect.add(target[v]);
                    }
                }
            }
            for (int v = 0; v < verts; v++) {
                if (target[v] < 0) {
                    continue;
                }
                float[] np = target[v] == v ? null : newPositions.get(target[v] - verts);
                for (int a = 0; a < 3; a++) {
                    float value = p[3 * v + a] + sum[3 * v + a] / count[v];
                    if (np == null) {
                        moved[3 * v + a] = value;
                    } else {
                        np[a] = value;
                    }
                }
            }
            for (int e = 0; e < mesh.edgeCount(); e++) {
                boolean interior = edgeFaces[e].length > 0;
                for (int g : edgeFaces[e]) {
                    interior &= sel[g];
                }
                if (interior) {
                    edges[2 * e] = target[edges[2 * e]];
                    edges[2 * e + 1] = target[edges[2 * e + 1]];
                }
            }
            for (int f = 0; f < mesh.faceCount(); f++) {
                if (!sel[f]) {
                    continue;
                }
                int start = t.faceOffsets[f], n = t.faceSize(f);
                int[] top = new int[n];
                for (int k = 0; k < n; k++) {
                    top[k] = target[t.cornerVerts[start + k]];
                }
                for (int k = 0; k < n; k++) {
                    int e = t.cornerEdges[start + k];
                    int selectedNeighbours = 0;
                    for (int g : edgeFaces[e]) {
                        if (sel[g]) {
                            selectedNeighbours++;
                        }
                    }
                    if (selectedNeighbours == edgeFaces[e].length) {
                        continue;
                    }
                    int a = t.cornerVerts[start + k], b = t.cornerVerts[start + (k + 1) % n];
                    duplicated.add(target[a]);
                    duplicated.add(target[b]);
                    // Blender starts each side face at the extruded copy of the edge's far end.
                    faces.add(new int[]{target[b], target[a], a, b});
                    faceSource.add(f);
                    cornerSource.add(new int[]{start + (k + 1) % n, start + k, start + k, start + (k + 1) % n});
                    isTop.add(false);
                    isSide.add(true);
                }
                faces.set(f, top);
                isTop.set(f, true);
            }
        }
        float[] positions = Arrays.copyOf(moved, (verts + newPositions.size()) * 3);
        for (int k = 0; k < newPositions.size(); k++) {
            System.arraycopy(newPositions.get(k), 0, positions, 3 * (verts + k), 3);
        }
        int[] allEdges = new int[edges.length + connect.size() + duplicated.size()];
        System.arraycopy(edges, 0, allEdges, 0, edges.length);
        int at = edges.length;
        for (int v : connect) {
            allEdges[at++] = v;
        }
        for (int v : duplicated) {
            allEdges[at++] = v;
        }
        int[] vs = vertSource.stream().mapToInt(Integer::intValue).toArray();
        MeshGeometry out = assemble(mesh, positions, vs, faces, faceSource, cornerSource, allEdges);
        int[] top = new int[out.faceCount()], side = new int[out.faceCount()];
        for (int f = 0; f < top.length; f++) {
            top[f] = isTop.get(f) ? 1 : 0;
            side[f] = isSide.get(f) ? 1 : 0;
        }
        return new Extrusion(out, Domain.FACE, Column.ofInts(DataType.BOOL, top), Column.ofInts(DataType.BOOL, side));
    }

    // --- Split edges ---------------------------------------------------------------------------------------

    private static void splitEdges(NodeCall call) {
        GeometrySet geometry = call.geometry("Mesh");
        MeshGeometry mesh = geometry.mesh;
        if (mesh == null) {
            call.out("Mesh", geometry);
            return;
        }
        boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), mesh, Domain.EDGE);
        MeshGeometry.Topology t = mesh.topology;
        int corners = mesh.cornerCount();
        int[] parent = new int[corners];
        for (int c = 0; c < corners; c++) {
            parent[c] = c;
        }
        int[] cf = t.cornerFaces();
        int[][] vertCorners = t.vertCorners();
        for (int c = 0; c < corners; c++) {
            int f = cf[c];
            int start = t.faceOffsets[f], end = t.faceOffsets[f + 1];
            int prev = c == start ? end - 1 : c - 1;
            for (int e : new int[]{t.cornerEdges[c], t.cornerEdges[prev]}) {
                if (sel[e]) {
                    continue;
                }
                for (int other : vertCorners[t.cornerVerts[c]]) {
                    int of = cf[other];
                    int os = t.faceOffsets[of], oe = t.faceOffsets[of + 1];
                    int oprev = other == os ? oe - 1 : other - 1;
                    if (of != f && (t.cornerEdges[other] == e || t.cornerEdges[oprev] == e)) {
                        int ra = find(parent, c), rb = find(parent, other);
                        parent[Math.max(ra, rb)] = Math.min(ra, rb);
                    }
                }
            }
        }
        int[] newVert = new int[corners];
        List<Integer> vertSource = new ArrayList<>();
        int[] rootVert = new int[corners];
        Arrays.fill(rootVert, -1);
        boolean[] usedOriginal = new boolean[mesh.vertCount()];
        for (int c = 0; c < corners; c++) {
            int root = find(parent, c);
            if (rootVert[root] < 0) {
                rootVert[root] = vertSource.size();
                vertSource.add(t.cornerVerts[c]);
                usedOriginal[t.cornerVerts[c]] = true;
            }
            newVert[c] = rootVert[root];
        }
        for (int v = 0; v < mesh.vertCount(); v++) {
            if (!usedOriginal[v]) {
                vertSource.add(v);
            }
        }
        int[] vs = vertSource.stream().mapToInt(Integer::intValue).toArray();
        float[] positions = mesh.attributes().column("position").gather(vs).f;
        List<Integer> offsets = new ArrayList<>();
        List<Integer> faceSource = new ArrayList<>();
        List<Integer> cornerSource = new ArrayList<>();
        List<Integer> verts = new ArrayList<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            offsets.add(verts.size());
            for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                verts.add(newVert[c]);
                cornerSource.add(c);
            }
            faceSource.add(f);
        }
        offsets.add(verts.size());
        MeshGeometry out = MeshGeometry.build(positions, offsets.stream().mapToInt(Integer::intValue).toArray(),
                verts.stream().mapToInt(Integer::intValue).toArray(), null);
        Attributes attributes = out.attributes().copy();
        int[] faceMap = faceSource.stream().mapToInt(Integer::intValue).toArray();
        int[] cornerMap = cornerSource.stream().mapToInt(Integer::intValue).toArray();
        for (Attributes.Attribute a : mesh.attributes().all()) {
            switch (a.domain()) {
                case POINT -> {
                    if (!a.name().equals("position")) {
                        attributes.put(a.name(), Domain.POINT, a.data().gather(vs));
                    }
                }
                case FACE -> attributes.put(a.name(), Domain.FACE, a.data().gather(faceMap));
                case CORNER -> {
                    if (!a.name().equals(".corner_normals")) {
                        attributes.put(a.name(), Domain.CORNER, a.data().gather(cornerMap));
                    }
                }
                default -> {
                }
            }
        }
        call.out("Mesh", geometry.withMesh(new MeshGeometry(out.topology, attributes, mesh.materials)));
    }

    // --- Conversions ---------------------------------------------------------------------------------------

    private static void meshToPoints(NodeCall call) {
        GeometrySet geometry = call.geometry("Mesh");
        MeshGeometry mesh = geometry.mesh;
        if (mesh == null) {
            call.out("Points", GeometrySet.EMPTY);
            return;
        }
        Domain domain = switch (call.prop("mode", "VERTICES")) {
            case "EDGES" -> Domain.EDGE;
            case "FACES" -> Domain.FACE;
            case "CORNERS" -> Domain.CORNER;
            default -> Domain.POINT;
        };
        FieldContext context = call.context(mesh, domain);
        boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), mesh, domain);
        int[] kept = GeometryOps.indices(sel);
        Column positions = context.evalAs(call.field("Position", DataType.VECTOR), DataType.VECTOR).gather(kept);
        Column radius = context.evalAs(call.field("Radius", DataType.FLOAT), DataType.FLOAT).gather(kept);
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, positions);
        attributes.put("radius", Domain.POINT, radius);
        for (Attributes.Attribute a : mesh.attributes().all()) {
            if (a.name().equals("position") || a.name().equals(".corner_normals")) {
                continue;
            }
            attributes.put(a.name(), Domain.POINT, mesh.adapt(a.data(), a.domain(), domain).gather(kept));
        }
        call.out("Points", GeometrySet.of(new PointGeometry(kept.length, attributes)));
    }

    private static void scaleElements(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        MeshGeometry mesh = geometry.mesh;
        if (mesh == null) {
            call.out("Geometry", geometry);
            return;
        }
        Domain domain = "EDGE".equals(call.prop("domain", "FACE")) ? Domain.EDGE : Domain.FACE;
        FieldContext context = call.context(mesh, domain);
        boolean[] sel = call.selection(call.field("Selection", DataType.BOOL), mesh, domain);
        Column scale = context.evalAs(call.field("Scale", DataType.FLOAT), DataType.FLOAT);
        Field centerField = call.linked("Center") ? call.field("Center", DataType.VECTOR) : Inputs.POSITION;
        Column center = context.evalAs(centerField, DataType.VECTOR);
        boolean singleAxis = call.has("Scale Mode") && call.string("Scale Mode").toLowerCase().startsWith("single");
        float[] axis = CurveNodes.normalized(call.field("Axis", DataType.VECTOR).singleVector());
        MeshGeometry.Topology t = mesh.topology;
        int n = context.size;
        int[] parent = new int[mesh.vertCount()];
        for (int v = 0; v < parent.length; v++) {
            parent[v] = v;
        }
        List<int[]> elementVerts = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            int[] vs = domain == Domain.FACE ? Arrays.copyOfRange(t.cornerVerts, t.faceOffsets[e], t.faceOffsets[e + 1])
                    : new int[]{t.edgeVerts[2 * e], t.edgeVerts[2 * e + 1]};
            elementVerts.add(vs);
            if (sel[e]) {
                for (int k = 1; k < vs.length; k++) {
                    int ra = find(parent, vs[0]), rb = find(parent, vs[k]);
                    parent[Math.max(ra, rb)] = Math.min(ra, rb);
                }
            }
        }
        Map<Integer, float[]> islands = new java.util.HashMap<>();
        for (int e = 0; e < n; e++) {
            if (!sel[e]) {
                continue;
            }
            float[] acc = islands.computeIfAbsent(find(parent, elementVerts.get(e)[0]), k -> new float[5]);
            for (int a = 0; a < 3; a++) {
                acc[a] += center.f[3 * e + a];
            }
            acc[3] += scale.f[e];
            acc[4]++;
        }
        float[] p = mesh.positions().clone();
        boolean[] moved = new boolean[mesh.vertCount()];
        for (int e = 0; e < n; e++) {
            if (!sel[e]) {
                continue;
            }
            for (int v : elementVerts.get(e)) {
                if (moved[v]) {
                    continue;
                }
                moved[v] = true;
                float[] acc = islands.get(find(parent, v));
                float s = acc[3] / acc[4];
                float[] c = {acc[0] / acc[4], acc[1] / acc[4], acc[2] / acc[4]};
                float[] d = {p[3 * v] - c[0], p[3 * v + 1] - c[1], p[3 * v + 2] - c[2]};
                if (singleAxis) {
                    float along = CurveNodes.dot(d, axis) * (s - 1f);
                    for (int a = 0; a < 3; a++) {
                        p[3 * v + a] += axis[a] * along;
                    }
                } else {
                    for (int a = 0; a < 3; a++) {
                        p[3 * v + a] = c[a] + d[a] * s;
                    }
                }
            }
        }
        call.out("Geometry", geometry.withMesh(mesh.withPositions(p)));
    }
}
