package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.Ref;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Input nodes: the fields that read the geometry being evaluated, and scene-level inputs. */
final class InputNodes {

    private InputNodes() {
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("GeometryNodeInputPosition", call -> call.out("Position", Inputs.POSITION));
        map.put("GeometryNodeInputNormal", call -> {
            call.out("Normal", Inputs.NORMAL);
            call.out("True Normal", Inputs.NORMAL);
        });
        map.put("GeometryNodeInputIndex", call -> call.out("Index", Inputs.INDEX));
        map.put("GeometryNodeInputID", call -> call.out("ID", Inputs.ID));
        map.put("GeometryNodeInputRadius", call -> call.out("Radius", Inputs.RADIUS));
        map.put("GeometryNodeInputNamedAttribute", call -> {
            DataType type = DataType.ofAttribute(call.prop("data_type", "FLOAT"));
            String name = call.string("Name");
            call.out("Attribute", Inputs.named(name, type));
            call.out("Exists", Inputs.exists(name));
        });
        map.put("GeometryNodeInputSceneTime", call -> {
            call.out("Seconds", Field.constant(DataType.FLOAT, call.env().seconds()));
            call.out("Frame", Field.constant(DataType.FLOAT, call.env().frame()));
        });
        map.put("GeometryNodeIsViewport", call -> call.out("Is Viewport", Field.constant(DataType.BOOL, call.env().viewport())));
        map.put("GeometryNodeSelfObject", call -> call.out("Self Object", new Ref(call.env().selfName(), "OBJECT")));
        map.put("GeometryNodeInputMaterial", call -> call.out("Material", call.node.props.get("material")));
        map.put("GeometryNodeInputMaterialIndex", call -> call.out("Material Index", Inputs.MATERIAL_INDEX));
        map.put("GeometryNodeInputShadeSmooth", call -> call.out("Smooth", Inputs.SHADE_SMOOTH));
        map.put("GeometryNodeInputEdgeSmooth", call -> call.out("Smooth", Inputs.EDGE_SMOOTH));
        map.put("GeometryNodeInputMeshEdgeAngle", InputNodes::edgeAngle);
        map.put("GeometryNodeInputMeshFaceArea", call -> call.out("Area", meshField(DataType.FLOAT, Domain.FACE,
                (mesh, out) -> {
                    float[] p = mesh.positions();
                    MeshGeometry.Topology t = mesh.topology;
                    for (int f = 0; f < mesh.faceCount(); f++) {
                        double nx = 0, ny = 0, nz = 0;
                        int start = t.faceOffsets[f], end = t.faceOffsets[f + 1];
                        for (int c = start; c < end; c++) {
                            int a = t.cornerVerts[c], b = t.cornerVerts[c + 1 == end ? start : c + 1];
                            nx += (double) p[3 * a + 1] * p[3 * b + 2] - (double) p[3 * a + 2] * p[3 * b + 1];
                            ny += (double) p[3 * a + 2] * p[3 * b] - (double) p[3 * a] * p[3 * b + 2];
                            nz += (double) p[3 * a] * p[3 * b + 1] - (double) p[3 * a + 1] * p[3 * b];
                        }
                        out.f[f] = (float) (0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz));
                    }
                })));
        map.put("GeometryNodeInputMeshFaceIsPlanar", InputNodes::faceIsPlanar);
        map.put("GeometryNodeInputMeshIsland", InputNodes::meshIsland);
        map.put("GeometryNodeInputMeshEdgeNeighbors", call -> call.out("Face Count", meshField(DataType.INT, Domain.EDGE,
                (mesh, out) -> {
                    int[][] edgeFaces = mesh.topology.edgeFaces();
                    for (int e = 0; e < mesh.edgeCount(); e++) {
                        out.i[e] = edgeFaces[e].length;
                    }
                })));
        map.put("GeometryNodeInputMeshFaceNeighbors", InputNodes::faceNeighbors);
        map.put("GeometryNodeInputMeshVertexNeighbors", call -> {
            call.out("Vertex Count", meshField(DataType.INT, Domain.POINT, (mesh, out) -> {
                int[][] vertEdges = mesh.topology.vertEdges();
                for (int v = 0; v < mesh.vertCount(); v++) {
                    out.i[v] = vertEdges[v].length;
                }
            }));
            call.out("Face Count", meshField(DataType.INT, Domain.POINT, (mesh, out) -> {
                int[][] vertFaces = mesh.topology.vertFaces();
                for (int v = 0; v < mesh.vertCount(); v++) {
                    out.i[v] = (int) Arrays.stream(vertFaces[v]).distinct().count();
                }
            }));
        });
        map.put("GeometryNodeInputMeshEdgeVertices", InputNodes::edgeVertices);
        map.put("GeometryNodeVertexOfCorner", call -> call.out("Vertex Index", indexLookup(call.field("Corner Index", DataType.INT),
                (mesh, i) -> i >= 0 && i < mesh.cornerCount() ? mesh.topology.cornerVerts[i] : 0)));
        map.put("GeometryNodeFaceOfCorner", call -> {
            call.out("Face Index", indexLookup(call.field("Corner Index", DataType.INT),
                    (mesh, i) -> i >= 0 && i < mesh.cornerCount() ? mesh.topology.cornerFaces()[i] : 0));
            call.out("Index in Face", indexLookup(call.field("Corner Index", DataType.INT), (mesh, i) -> {
                if (i < 0 || i >= mesh.cornerCount()) {
                    return 0;
                }
                return i - mesh.topology.faceOffsets[mesh.topology.cornerFaces()[i]];
            }));
        });
        map.put("GeometryNodeEdgesOfCorner", call -> {
            Field corner = call.field("Corner Index", DataType.INT);
            call.out("Next Edge Index", indexLookup(corner, (mesh, i) -> i >= 0 && i < mesh.cornerCount()
                    ? mesh.topology.cornerEdges[i] : 0));
            call.out("Previous Edge Index", indexLookup(corner, (mesh, i) -> {
                if (i < 0 || i >= mesh.cornerCount()) {
                    return 0;
                }
                int f = mesh.topology.cornerFaces()[i];
                int start = mesh.topology.faceOffsets[f];
                int prev = i == start ? mesh.topology.faceOffsets[f + 1] - 1 : i - 1;
                return mesh.topology.cornerEdges[prev];
            }));
        });
        map.put("GeometryNodeOffsetCornerInFace", InputNodes::offsetCornerInFace);
        map.put("GeometryNodeCornersOfFace", call -> sortedNeighbours(call, "Face Index", "Corner Index",
                (mesh, f) -> f >= 0 && f < mesh.faceCount() ? range(mesh.topology.faceOffsets[f], mesh.topology.faceOffsets[f + 1]) : new int[0],
                Domain.CORNER));
        map.put("GeometryNodeCornersOfVertex", call -> sortedNeighbours(call, "Vertex Index", "Corner Index",
                (mesh, v) -> v >= 0 && v < mesh.vertCount() ? mesh.topology.vertCorners()[v] : new int[0], Domain.CORNER));
        map.put("GeometryNodeEdgesOfVertex", call -> sortedNeighbours(call, "Vertex Index", "Edge Index",
                (mesh, v) -> v >= 0 && v < mesh.vertCount() ? mesh.topology.vertEdges()[v] : new int[0], Domain.EDGE));
        map.put("GeometryNodeCornersOfEdge", call -> sortedNeighbours(call, "Edge Index", "Corner Index", (mesh, e) -> {
            if (e < 0 || e >= mesh.edgeCount()) {
                return new int[0];
            }
            List<Integer> corners = new ArrayList<>();
            for (int c = 0; c < mesh.cornerCount(); c++) {
                if (mesh.topology.cornerEdges[c] == e) {
                    corners.add(c);
                }
            }
            return corners.stream().mapToInt(Integer::intValue).toArray();
        }, Domain.CORNER));

        CurveInputs.register(map);
    }

    static int[] range(int from, int to) {
        int[] out = new int[Math.max(0, to - from)];
        for (int k = 0; k < out.length; k++) {
            out[k] = from + k;
        }
        return out;
    }

    // --- Helpers ---------------------------------------------------------------------------------------------

    interface MeshFill {
        void fill(MeshGeometry mesh, Column out);
    }

    /** A field computed on one native mesh domain and adapted to whichever domain it is evaluated on. */
    static Field meshField(DataType type, Domain native_, MeshFill fill) {
        return new Field.Input(type) {
            @Override
            protected Column compute(FieldContext context) {
                MeshGeometry mesh = context.mesh();
                if (mesh == null) {
                    return Column.create(type, context.size);
                }
                Column values = Column.create(type, mesh.domainSize(native_));
                fill.fill(mesh, values);
                return mesh.adapt(values, native_, context.domain).convert(type);
            }
        };
    }

    interface IndexMap {
        int map(MeshGeometry mesh, int index);
    }

    /** A field mapping an index field through the mesh's topology. */
    static Field indexLookup(Field index, IndexMap map) {
        return new Field.Input(DataType.INT) {
            @Override
            protected Column compute(FieldContext context) {
                Column in = context.evalAs(index, DataType.INT);
                Column out = Column.create(DataType.INT, context.size);
                MeshGeometry mesh = context.mesh();
                if (mesh == null) {
                    return out;
                }
                for (int k = 0; k < context.size; k++) {
                    out.i[k] = map.map(mesh, in.i[k]);
                }
                return out;
            }
        };
    }

    interface Neighbours {
        int[] of(MeshGeometry mesh, int index);
    }

    /**
     * The Corners of Face family: for an index, its neighbouring elements sorted by a weight (evaluated on the
     * neighbours' domain), the one at Sort Index (wrapping), and how many there are.
     */
    private static void sortedNeighbours(NodeCall call, String indexName, String outName, Neighbours neighbours, Domain weightDomain) {
        Field index = call.field(indexName, DataType.INT);
        Field weights = call.field("Weights", DataType.FLOAT);
        Field sortIndex = call.field("Sort Index", DataType.INT);
        Field[] parts = new Field[2];
        DataType[] types = {DataType.INT, DataType.INT};
        Field.Multi shared = new Field.Multi(types, null, new Field[0]) {
            @Override
            Column[] compute(FieldContext context) {
                Column[] out = {Column.create(DataType.INT, context.size), Column.create(DataType.INT, context.size)};
                MeshGeometry mesh = context.mesh();
                if (mesh == null) {
                    return out;
                }
                Column idx = context.evalAs(index, DataType.INT);
                Column sort = context.evalAs(sortIndex, DataType.INT);
                Column w = weights.isConstant() ? null : context.on(weightDomain).evalAs(weights, DataType.FLOAT);
                for (int k = 0; k < context.size; k++) {
                    int[] items = neighbours.of(mesh, idx.i[k]);
                    out[1].i[k] = items.length;
                    if (items.length == 0) {
                        continue;
                    }
                    Integer[] order = new Integer[items.length];
                    for (int j = 0; j < items.length; j++) {
                        order[j] = items[j];
                    }
                    if (w != null) {
                        Arrays.sort(order, (a, b) -> Float.compare(w.f[a], w.f[b]));
                    }
                    out[0].i[k] = order[Math.floorMod(sort.i[k], items.length)];
                }
                return out;
            }
        };
        parts[0] = new Field.MultiOutput(shared, 0);
        parts[1] = new Field.MultiOutput(shared, 1);
        call.out(outName, parts[0]);
        call.out("Total", parts[1]);
    }

    // --- Mesh topology -------------------------------------------------------------------------------------

    private static void edgeAngle(NodeCall call) {
        Field[] parts = new Field[2];
        for (int s = 0; s < 2; s++) {
            boolean signed = s == 1;
            parts[s] = meshField(DataType.FLOAT, Domain.EDGE, (mesh, out) -> {
                int[][] edgeFaces = mesh.topology.edgeFaces();
                float[] fn = mesh.faceNormals();
                float[] p = mesh.positions();
                for (int e = 0; e < mesh.edgeCount(); e++) {
                    int[] faces = edgeFaces[e];
                    if (faces.length != 2) {
                        continue;
                    }
                    int f1 = faces[0], f2 = faces[1];
                    double dot = fn[3 * f1] * fn[3 * f2] + fn[3 * f1 + 1] * fn[3 * f2 + 1] + fn[3 * f1 + 2] * fn[3 * f2 + 2];
                    float angle = (float) Math.acos(Math.max(-1, Math.min(1, dot)));
                    if (signed) {
                        float[] c1 = faceCenter(mesh, f1, p), c2 = faceCenter(mesh, f2, p);
                        float concavity = (c2[0] - c1[0]) * fn[3 * f1] + (c2[1] - c1[1]) * fn[3 * f1 + 1]
                                + (c2[2] - c1[2]) * fn[3 * f1 + 2];
                        if (concavity > 0f) {
                            angle = -angle;
                        }
                    }
                    out.f[e] = angle;
                }
            });
        }
        call.out("Unsigned Angle", parts[0]);
        call.out("Signed Angle", parts[1]);
    }

    static float[] faceCenter(MeshGeometry mesh, int f, float[] p) {
        MeshGeometry.Topology t = mesh.topology;
        float[] c = new float[3];
        int n = t.faceSize(f);
        for (int k = t.faceOffsets[f]; k < t.faceOffsets[f + 1]; k++) {
            int v = t.cornerVerts[k];
            c[0] += p[3 * v];
            c[1] += p[3 * v + 1];
            c[2] += p[3 * v + 2];
        }
        c[0] /= n;
        c[1] /= n;
        c[2] /= n;
        return c;
    }

    private static void faceIsPlanar(NodeCall call) {
        Field threshold = call.field("Threshold", DataType.FLOAT);
        call.out("Planar", new Field.Input(DataType.BOOL) {
            @Override
            protected Column compute(FieldContext context) {
                MeshGeometry mesh = context.mesh();
                if (mesh == null) {
                    return Column.create(DataType.BOOL, context.size);
                }
                Column limit = context.on(Domain.FACE).evalAs(threshold, DataType.FLOAT);
                Column planar = Column.create(DataType.BOOL, mesh.faceCount());
                float[] fn = mesh.faceNormals();
                float[] p = mesh.positions();
                MeshGeometry.Topology t = mesh.topology;
                for (int f = 0; f < mesh.faceCount(); f++) {
                    float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
                    for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                        int v = t.cornerVerts[c];
                        float d = p[3 * v] * fn[3 * f] + p[3 * v + 1] * fn[3 * f + 1] + p[3 * v + 2] * fn[3 * f + 2];
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                    }
                    planar.i[f] = max - min <= limit.f[f] ? 1 : 0;
                }
                return mesh.adapt(planar, Domain.FACE, context.domain);
            }
        });
    }

    private static void meshIsland(NodeCall call) {
        call.out("Island Index", meshField(DataType.INT, Domain.POINT, (mesh, out) -> {
            int[] islands = islands(mesh);
            System.arraycopy(islands, 0, out.i, 0, islands.length);
        }));
        call.out("Island Count", new Field.Input(DataType.INT) {
            @Override
            protected Column compute(FieldContext context) {
                MeshGeometry mesh = context.mesh();
                int count = 0;
                if (mesh != null) {
                    for (int island : islands(mesh)) {
                        count = Math.max(count, island + 1);
                    }
                }
                return Column.constant(DataType.INT, count, context.size);
            }
        });
    }

    /** Each vertex's island, numbered in order of each island's lowest vertex. */
    static int[] islands(MeshGeometry mesh) {
        int n = mesh.vertCount();
        int[] parent = new int[n];
        for (int v = 0; v < n; v++) {
            parent[v] = v;
        }
        int[] edges = mesh.topology.edgeVerts;
        for (int e = 0; e < edges.length; e += 2) {
            int a = find(parent, edges[e]), b = find(parent, edges[e + 1]);
            if (a != b) {
                parent[Math.max(a, b)] = Math.min(a, b);
            }
        }
        int[] ids = new int[n];
        int[] remap = new int[n];
        Arrays.fill(remap, -1);
        int next = 0;
        for (int v = 0; v < n; v++) {
            int root = find(parent, v);
            if (remap[root] < 0) {
                remap[root] = next++;
            }
            ids[v] = remap[root];
        }
        return ids;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void faceNeighbors(NodeCall call) {
        call.out("Vertex Count", meshField(DataType.INT, Domain.FACE, (mesh, out) -> {
            for (int f = 0; f < mesh.faceCount(); f++) {
                out.i[f] = mesh.topology.faceSize(f);
            }
        }));
        call.out("Face Count", meshField(DataType.INT, Domain.FACE, (mesh, out) -> {
            int[][] edgeFaces = mesh.topology.edgeFaces();
            MeshGeometry.Topology t = mesh.topology;
            for (int f = 0; f < mesh.faceCount(); f++) {
                java.util.Set<Integer> neighbours = new java.util.HashSet<>();
                for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                    for (int other : edgeFaces[t.cornerEdges[c]]) {
                        if (other != f) {
                            neighbours.add(other);
                        }
                    }
                }
                out.i[f] = neighbours.size();
            }
        }));
    }

    private static void edgeVertices(NodeCall call) {
        for (int end = 0; end < 2; end++) {
            int which = end;
            call.out("Vertex Index " + (end + 1), meshField(DataType.INT, Domain.EDGE, (mesh, out) -> {
                for (int e = 0; e < mesh.edgeCount(); e++) {
                    out.i[e] = mesh.topology.edgeVerts[2 * e + which];
                }
            }));
            call.out("Position " + (end + 1), meshField(DataType.VECTOR, Domain.EDGE, (mesh, out) -> {
                float[] p = mesh.positions();
                for (int e = 0; e < mesh.edgeCount(); e++) {
                    int v = mesh.topology.edgeVerts[2 * e + which];
                    out.setVector(e, p[3 * v], p[3 * v + 1], p[3 * v + 2]);
                }
            }));
        }
    }

    private static void offsetCornerInFace(NodeCall call) {
        Field corner = call.field("Corner Index", DataType.INT);
        Field offset = call.field("Offset", DataType.INT);
        call.out("Corner Index", new Field.Input(DataType.INT) {
            @Override
            protected Column compute(FieldContext context) {
                Column c = context.evalAs(corner, DataType.INT);
                Column o = context.evalAs(offset, DataType.INT);
                Column out = Column.create(DataType.INT, context.size);
                MeshGeometry mesh = context.mesh();
                if (mesh == null) {
                    return out;
                }
                for (int k = 0; k < context.size; k++) {
                    int ci = c.i[k];
                    if (ci < 0 || ci >= mesh.cornerCount()) {
                        continue;
                    }
                    int f = mesh.topology.cornerFaces()[ci];
                    int start = mesh.topology.faceOffsets[f];
                    int size = mesh.topology.faceSize(f);
                    out.i[k] = start + Math.floorMod(ci - start + o.i[k], size);
                }
                return out;
            }
        });
    }
}
