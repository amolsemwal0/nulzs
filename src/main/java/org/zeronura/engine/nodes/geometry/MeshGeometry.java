package org.zeronura.engine.nodes.geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A mesh as Blender's geometry nodes see it: vertices (the point domain), edges, faces and face corners, each
 * with attributes. Positions are the {@code position} point attribute; {@code sharp_face},
 * {@code sharp_edge} and {@code material_index} are ordinary attributes too, as in Blender.
 *
 * <p>The connectivity lives in a {@link Topology}, shared between meshes that differ only in attributes, so
 * moving vertices (Set Position) keeps every adjacency table already built.
 */
public final class MeshGeometry extends GeometryComponent {

    /** Vertices, edges, faces and corners, and the adjacency built from them on demand. */
    public static final class Topology {
        public final int vertCount;
        public final int[] edgeVerts;
        public final int[] faceOffsets;
        public final int[] cornerVerts;
        public final int[] cornerEdges;

        private int[] cornerFaces;
        private int[][] vertCorners;
        private int[][] vertEdges;
        private int[][] edgeFaces;
        private int[][] vertFaces;

        public Topology(int vertCount, int[] edgeVerts, int[] faceOffsets, int[] cornerVerts, int[] cornerEdges) {
            this.vertCount = vertCount;
            this.edgeVerts = edgeVerts;
            this.faceOffsets = faceOffsets;
            this.cornerVerts = cornerVerts;
            this.cornerEdges = cornerEdges;
        }

        public int edgeCount() {
            return edgeVerts.length / 2;
        }

        public int faceCount() {
            return faceOffsets.length - 1;
        }

        public int cornerCount() {
            return cornerVerts.length;
        }

        public int faceStart(int face) {
            return faceOffsets[face];
        }

        public int faceSize(int face) {
            return faceOffsets[face + 1] - faceOffsets[face];
        }

        public synchronized int[] cornerFaces() {
            if (cornerFaces == null) {
                int[] out = new int[cornerCount()];
                for (int f = 0; f < faceCount(); f++) {
                    for (int c = faceOffsets[f]; c < faceOffsets[f + 1]; c++) {
                        out[c] = f;
                    }
                }
                cornerFaces = out;
            }
            return cornerFaces;
        }

        public synchronized int[][] vertCorners() {
            if (vertCorners == null) {
                vertCorners = group(vertCount, cornerVerts, null, 1);
            }
            return vertCorners;
        }

        public synchronized int[][] vertEdges() {
            if (vertEdges == null) {
                vertEdges = group(vertCount, edgeVerts, null, 2);
            }
            return vertEdges;
        }

        public synchronized int[][] edgeFaces() {
            if (edgeFaces == null) {
                int[] cf = cornerFaces();
                edgeFaces = group(edgeCount(), cornerEdges, cf, 1);
            }
            return edgeFaces;
        }

        public synchronized int[][] vertFaces() {
            if (vertFaces == null) {
                int[] cf = cornerFaces();
                vertFaces = group(vertCount, cornerVerts, cf, 1);
            }
            return vertFaces;
        }

        /** For each key, the indices (or mapped values) of the elements referring to it. */
        private static int[][] group(int keys, int[] refs, int[] mapped, int stride) {
            int[] counts = new int[keys];
            for (int ref : refs) {
                counts[ref]++;
            }
            int[][] out = new int[keys][];
            for (int k = 0; k < keys; k++) {
                out[k] = new int[counts[k]];
            }
            Arrays.fill(counts, 0);
            for (int e = 0; e < refs.length; e++) {
                int key = refs[e];
                out[key][counts[key]++] = mapped != null ? mapped[e] : e / stride;
            }
            return out;
        }
    }

    public final Topology topology;
    /** Material slot names, by material index. A null entry is an empty slot. */
    public final List<String> materials;

    private float[] faceNormals;
    private float[] vertNormals;
    private float[] cornerNormals;

    public MeshGeometry(Topology topology, Attributes attributes, List<String> materials) {
        super(attributes);
        this.topology = topology;
        this.materials = List.copyOf(materials == null ? List.of() : nonNull(materials));
    }

    private static List<String> nonNull(List<String> list) {
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            out.add(s == null ? "" : s);
        }
        return out;
    }

    /** A mesh from positions and faces (as offsets into a corner list), with edges worked out from the faces. */
    public static MeshGeometry build(float[] positions, int[] faceOffsets, int[] cornerVerts, int[] looseEdges) {
        int verts = positions.length / 3;
        EdgeTable table = new EdgeTable(verts);
        if (looseEdges != null) {
            for (int e = 0; e < looseEdges.length; e += 2) {
                table.add(looseEdges[e], looseEdges[e + 1]);
            }
        }
        int[] cornerEdges = new int[cornerVerts.length];
        int faces = faceOffsets.length - 1;
        for (int f = 0; f < faces; f++) {
            int start = faceOffsets[f];
            int end = faceOffsets[f + 1];
            for (int c = start; c < end; c++) {
                int next = c + 1 == end ? start : c + 1;
                cornerEdges[c] = table.add(cornerVerts[c], cornerVerts[next]);
            }
        }
        Topology topology = new Topology(verts, table.edges(), faceOffsets, cornerVerts, cornerEdges);
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, positions));
        return new MeshGeometry(topology, attributes, List.of());
    }

    /** Edges keyed by their two vertices, numbered in the order they are first met. */
    public static final class EdgeTable {
        private final java.util.HashMap<Long, Integer> index = new java.util.HashMap<>();
        private int[] edges = new int[64];
        private int count;

        public EdgeTable(int vertCount) {
        }

        public int add(int a, int b) {
            long key = a < b ? ((long) a << 32) | (b & 0xffffffffL) : ((long) b << 32) | (a & 0xffffffffL);
            Integer found = index.get(key);
            if (found != null) {
                return found;
            }
            if (2 * count + 2 > edges.length) {
                edges = Arrays.copyOf(edges, edges.length * 2);
            }
            edges[2 * count] = a;
            edges[2 * count + 1] = b;
            index.put(key, count);
            return count++;
        }

        public int[] edges() {
            return Arrays.copyOf(edges, 2 * count);
        }
    }

    public static MeshGeometry empty() {
        return build(new float[0], new int[]{0}, new int[0], null);
    }

    public int vertCount() {
        return topology.vertCount;
    }

    public int edgeCount() {
        return topology.edgeCount();
    }

    public int faceCount() {
        return topology.faceCount();
    }

    public int cornerCount() {
        return topology.cornerCount();
    }

    public float[] positions() {
        return attributes.column("position").f;
    }

    public MeshGeometry withPositions(float[] positions) {
        Attributes out = attributes.copy();
        out.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, positions));
        return new MeshGeometry(topology, out, materials);
    }

    public MeshGeometry withMaterials(List<String> slots) {
        return new MeshGeometry(topology, attributes, slots);
    }

    @Override
    public MeshGeometry withAttributes(Attributes attributes) {
        return new MeshGeometry(topology, attributes, materials);
    }

    @Override
    public int domainSize(Domain domain) {
        return switch (domain) {
            case POINT -> topology.vertCount;
            case EDGE -> topology.edgeCount();
            case FACE -> topology.faceCount();
            case CORNER -> topology.cornerCount();
            default -> 0;
        };
    }

    @Override
    public Domain defaultDomain() {
        return Domain.POINT;
    }

    @Override
    public boolean hasDomain(Domain domain) {
        return domain == Domain.POINT || domain == Domain.EDGE || domain == Domain.FACE || domain == Domain.CORNER;
    }

    public boolean isEmpty() {
        return topology.vertCount == 0;
    }

    // --- Normals -----------------------------------------------------------------------------------------

    /** Newell's normal of each face. */
    public synchronized float[] faceNormals() {
        if (faceNormals == null) {
            float[] p = positions();
            int faces = faceCount();
            float[] out = new float[faces * 3];
            for (int f = 0; f < faces; f++) {
                int start = topology.faceOffsets[f];
                int end = topology.faceOffsets[f + 1];
                double nx = 0, ny = 0, nz = 0;
                for (int c = start; c < end; c++) {
                    int a = topology.cornerVerts[c];
                    int b = topology.cornerVerts[c + 1 == end ? start : c + 1];
                    double ax = p[3 * a], ay = p[3 * a + 1], az = p[3 * a + 2];
                    double bx = p[3 * b], by = p[3 * b + 1], bz = p[3 * b + 2];
                    nx += (ay - by) * (az + bz);
                    ny += (az - bz) * (ax + bx);
                    nz += (ax - bx) * (ay + by);
                }
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-35) {
                    out[3 * f] = (float) (nx / len);
                    out[3 * f + 1] = (float) (ny / len);
                    out[3 * f + 2] = (float) (nz / len);
                } else {
                    out[3 * f + 2] = 1f;
                }
            }
            faceNormals = out;
        }
        return faceNormals;
    }

    private double cornerAngle(int corner, float[] p) {
        int face = topology.cornerFaces()[corner];
        int start = topology.faceOffsets[face];
        int end = topology.faceOffsets[face + 1];
        int prev = topology.cornerVerts[corner == start ? end - 1 : corner - 1];
        int next = topology.cornerVerts[corner + 1 == end ? start : corner + 1];
        int v = topology.cornerVerts[corner];
        double ax = p[3 * prev] - p[3 * v], ay = p[3 * prev + 1] - p[3 * v + 1], az = p[3 * prev + 2] - p[3 * v + 2];
        double bx = p[3 * next] - p[3 * v], by = p[3 * next + 1] - p[3 * v + 1], bz = p[3 * next + 2] - p[3 * v + 2];
        double la = Math.sqrt(ax * ax + ay * ay + az * az);
        double lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-20 || lb < 1e-20) {
            return 0;
        }
        double cos = (ax * bx + ay * by + az * bz) / (la * lb);
        return Math.acos(Math.max(-1, Math.min(1, cos)));
    }

    /** Vertex normals: each face's normal weighted by its corner's angle, as Blender computes them. */
    public synchronized float[] vertNormals() {
        if (vertNormals == null) {
            float[] p = positions();
            float[] fn = faceNormals();
            int[] cf = topology.cornerFaces();
            double[] acc = new double[vertCount() * 3];
            for (int c = 0; c < cornerCount(); c++) {
                double w = cornerAngle(c, p);
                int v = topology.cornerVerts[c];
                int f = cf[c];
                acc[3 * v] += fn[3 * f] * w;
                acc[3 * v + 1] += fn[3 * f + 1] * w;
                acc[3 * v + 2] += fn[3 * f + 2] * w;
            }
            float[] out = new float[vertCount() * 3];
            for (int v = 0; v < vertCount(); v++) {
                double x = acc[3 * v], y = acc[3 * v + 1], z = acc[3 * v + 2];
                double len = Math.sqrt(x * x + y * y + z * z);
                if (len > 1e-35) {
                    out[3 * v] = (float) (x / len);
                    out[3 * v + 1] = (float) (y / len);
                    out[3 * v + 2] = (float) (z / len);
                } else {
                    double px = p[3 * v], py = p[3 * v + 1], pz = p[3 * v + 2];
                    double pl = Math.sqrt(px * px + py * py + pz * pz);
                    if (pl > 1e-35) {
                        out[3 * v] = (float) (px / pl);
                        out[3 * v + 1] = (float) (py / pl);
                        out[3 * v + 2] = (float) (pz / pl);
                    } else {
                        out[3 * v + 2] = 1f;
                    }
                }
            }
            vertNormals = out;
        }
        return vertNormals;
    }

    private boolean[] sharpEdges() {
        int edges = edgeCount();
        boolean[] sharp = new boolean[edges];
        Column sharpEdge = attributes.column("sharp_edge");
        Column sharpFace = attributes.column("sharp_face");
        int[][] edgeFaces = topology.edgeFaces();
        for (int e = 0; e < edges; e++) {
            boolean s = sharpEdge != null && sharpEdge.getBool(e);
            int[] faces = edgeFaces[e];
            if (faces.length != 2) {
                s = true;
            } else if (sharpFace != null && (sharpFace.getBool(faces[0]) || sharpFace.getBool(faces[1]))) {
                s = true;
            }
            sharp[e] = s;
        }
        return sharp;
    }

    /**
     * Normals per face corner: a flat face's own normal on every corner, and on smooth faces the angle-weighted
     * average over the fan of faces around the vertex, a fan ending at sharp edges. What a mesh renders with.
     */
    public synchronized float[] cornerNormals() {
        if (cornerNormals == null) {
            Column custom = attributes.column(".corner_normals");
            if (custom != null && custom.size == cornerCount()) {
                cornerNormals = custom.f;
                return cornerNormals;
            }
            float[] p = positions();
            float[] fn = faceNormals();
            int corners = cornerCount();
            float[] out = new float[corners * 3];
            Column sharpFace = attributes.column("sharp_face");
            boolean anySmooth = false;
            int[] cf = topology.cornerFaces();
            for (int c = 0; c < corners; c++) {
                int f = cf[c];
                out[3 * c] = fn[3 * f];
                out[3 * c + 1] = fn[3 * f + 1];
                out[3 * c + 2] = fn[3 * f + 2];
                if (sharpFace == null || !sharpFace.getBool(f)) {
                    anySmooth = true;
                }
            }
            if (anySmooth) {
                boolean[] sharp = sharpEdges();
                int[][] vertCorners = topology.vertCorners();
                // Union corners around each vertex that share a smooth edge, then average each group.
                int[] parent = new int[corners];
                for (int c = 0; c < corners; c++) {
                    parent[c] = c;
                }
                for (int c = 0; c < corners; c++) {
                    int f = cf[c];
                    if (sharpFace != null && sharpFace.getBool(f)) {
                        continue;
                    }
                    int start = topology.faceOffsets[f];
                    int end = topology.faceOffsets[f + 1];
                    int prevCorner = c == start ? end - 1 : c - 1;
                    int[] edgesHere = {topology.cornerEdges[c], topology.cornerEdges[prevCorner]};
                    int v = topology.cornerVerts[c];
                    for (int edge : edgesHere) {
                        if (sharp[edge]) {
                            continue;
                        }
                        for (int other : vertCorners[v]) {
                            if (other == c) {
                                continue;
                            }
                            int of = cf[other];
                            if (of == f) {
                                continue;
                            }
                            int os = topology.faceOffsets[of];
                            int oe = topology.faceOffsets[of + 1];
                            int oprev = other == os ? oe - 1 : other - 1;
                            if (topology.cornerEdges[other] == edge || topology.cornerEdges[oprev] == edge) {
                                union(parent, c, other);
                            }
                        }
                    }
                }
                double[] acc = new double[corners * 3];
                for (int c = 0; c < corners; c++) {
                    int f = cf[c];
                    if (sharpFace != null && sharpFace.getBool(f)) {
                        continue;
                    }
                    int root = find(parent, c);
                    double w = cornerAngle(c, p);
                    acc[3 * root] += fn[3 * f] * w;
                    acc[3 * root + 1] += fn[3 * f + 1] * w;
                    acc[3 * root + 2] += fn[3 * f + 2] * w;
                }
                for (int c = 0; c < corners; c++) {
                    int f = cf[c];
                    if (sharpFace != null && sharpFace.getBool(f)) {
                        continue;
                    }
                    int root = find(parent, c);
                    double x = acc[3 * root], y = acc[3 * root + 1], z = acc[3 * root + 2];
                    double len = Math.sqrt(x * x + y * y + z * z);
                    if (len > 1e-35) {
                        out[3 * c] = (float) (x / len);
                        out[3 * c + 1] = (float) (y / len);
                        out[3 * c + 2] = (float) (z / len);
                    }
                }
            }
            cornerNormals = out;
        }
        return cornerNormals;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    /** Edge normals: the average of the faces on either side. */
    public float[] edgeNormals() {
        float[] fn = faceNormals();
        int[][] edgeFaces = topology.edgeFaces();
        float[] vn = vertNormals();
        float[] out = new float[edgeCount() * 3];
        for (int e = 0; e < edgeCount(); e++) {
            double x = 0, y = 0, z = 0;
            if (edgeFaces[e].length == 0) {
                int a = topology.edgeVerts[2 * e], b = topology.edgeVerts[2 * e + 1];
                x = vn[3 * a] + vn[3 * b];
                y = vn[3 * a + 1] + vn[3 * b + 1];
                z = vn[3 * a + 2] + vn[3 * b + 2];
            }
            for (int f : edgeFaces[e]) {
                x += fn[3 * f];
                y += fn[3 * f + 1];
                z += fn[3 * f + 2];
            }
            double len = Math.sqrt(x * x + y * y + z * z);
            if (len > 1e-35) {
                out[3 * e] = (float) (x / len);
                out[3 * e + 1] = (float) (y / len);
                out[3 * e + 2] = (float) (z / len);
            }
        }
        return out;
    }

    // --- Domain interpolation ----------------------------------------------------------------------------

    @Override
    public Column adapt(Column column, Domain from, Domain to) {
        if (from == to) {
            return column;
        }
        Topology t = topology;
        return switch (from) {
            case POINT -> switch (to) {
                case CORNER -> column.gather(t.cornerVerts);
                case FACE -> average(column, faceCount(), f -> range(t.faceOffsets[f], t.faceOffsets[f + 1]), t.cornerVerts, true);
                case EDGE -> average(column, edgeCount(), e -> new int[]{t.edgeVerts[2 * e], t.edgeVerts[2 * e + 1]}, null, true);
                default -> super.adapt(column, from, to);
            };
            case CORNER -> switch (to) {
                case POINT -> average(column, vertCount(), v -> t.vertCorners()[v], null, false);
                case FACE -> average(column, faceCount(), f -> range(t.faceOffsets[f], t.faceOffsets[f + 1]), null, true);
                case EDGE -> adapt(adapt(column, Domain.CORNER, Domain.POINT), Domain.POINT, Domain.EDGE);
                default -> super.adapt(column, from, to);
            };
            case FACE -> switch (to) {
                case CORNER -> column.gather(t.cornerFaces());
                case POINT -> average(column, vertCount(), v -> t.vertFaces()[v], null, false);
                case EDGE -> average(column, edgeCount(), e -> t.edgeFaces()[e], null, false);
                default -> super.adapt(column, from, to);
            };
            case EDGE -> switch (to) {
                case POINT -> average(column, vertCount(), v -> t.vertEdges()[v], null, false);
                case FACE -> average(column, faceCount(), f -> range(t.faceOffsets[f], t.faceOffsets[f + 1]), t.cornerEdges, true);
                case CORNER -> {
                    int corners = cornerCount();
                    int[] cf = t.cornerFaces();
                    yield average(column, corners, c -> {
                        int f = cf[c];
                        int start = t.faceOffsets[f];
                        int prev = c == start ? t.faceOffsets[f + 1] - 1 : c - 1;
                        return new int[]{t.cornerEdges[c], t.cornerEdges[prev]};
                    }, null, true);
                }
                default -> super.adapt(column, from, to);
            };
            default -> super.adapt(column, from, to);
        };
    }

    private static int[] range(int from, int to) {
        int[] out = new int[to - from];
        for (int k = 0; k < out.length; k++) {
            out[k] = from + k;
        }
        return out;
    }

    /**
     * Each target element as the mix of the source elements {@code sources} names (through {@code map} when
     * given). Booleans are true when all sources are ({@code all}) or any is; integers round their average.
     */
    static Column average(Column column, int count, java.util.function.IntFunction<int[]> sources, int[] map, boolean all) {
        Column out = Column.create(column.type, count);
        int w = column.type.width;
        for (int e = 0; e < count; e++) {
            int[] from = sources.apply(e);
            if (from.length == 0) {
                continue;
            }
            if (column.type == DataType.BOOL) {
                boolean value = all;
                for (int s : from) {
                    boolean b = column.i[map != null ? map[s] : s] != 0;
                    value = all ? value && b : value || b;
                }
                out.i[e] = value ? 1 : 0;
            } else if (column.type == DataType.INT) {
                double sum = 0;
                for (int s : from) {
                    sum += column.i[map != null ? map[s] : s];
                }
                out.i[e] = (int) Math.round(sum / from.length);
            } else {
                for (int s : from) {
                    int src = (map != null ? map[s] : s) * w;
                    for (int k = 0; k < w; k++) {
                        out.f[e * w + k] += column.f[src + k];
                    }
                }
                float inv = 1f / from.length;
                for (int k = 0; k < w; k++) {
                    out.f[e * w + k] *= inv;
                }
                if (column.type == DataType.ROTATION) {
                    Transforms.quatNormalize(out.f, e * 4);
                }
            }
        }
        return out;
    }
}
