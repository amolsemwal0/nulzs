package org.zeronura.engine.nodes.geometry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mesh primitives, with vertices and faces in exactly Blender's order (checked against Blender's own output),
 * so index-driven fields (Index, Random Value by ID) pick the same elements in the engine as in Blender.
 */
final class MeshPrimitives {

    private static final AtomicInteger ANONYMOUS = new AtomicInteger();

    private MeshPrimitives() {
    }

    /** A fresh name for an attribute a node keeps for its own output field (a primitive's UV Map, a Top selection). */
    static String anonymous(String what) {
        return ".anon." + what + "." + ANONYMOUS.incrementAndGet();
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("GeometryNodeMeshGrid", call -> {
            MeshGeometry mesh = grid(call.i("Vertices X"), call.i("Vertices Y"), call.f("Size X"), call.f("Size Y"));
            outputWithUv(call, "Mesh", mesh, gridUvs(mesh, call.f("Size X"), call.f("Size Y")));
        });
        map.put("GeometryNodeMeshLine", MeshPrimitives::line);
        map.put("GeometryNodeMeshCircle", call -> call.out("Mesh", GeometrySet.of(flat(circle(call.i("Vertices"),
                call.f("Radius"), call.prop("fill_type", "NONE"))))));
        map.put("GeometryNodeMeshCube", MeshPrimitives::cube);
        map.put("GeometryNodeMeshCylinder", call -> cone(call, call.f("Radius"), call.f("Radius"), call.f("Depth"), false));
        map.put("GeometryNodeMeshCone", call -> cone(call, call.f("Radius Top"), call.f("Radius Bottom"), call.f("Depth"), true));
        map.put("GeometryNodeMeshUVSphere", call -> uvSphere(call, call.i("Segments"), call.i("Rings"), call.f("Radius")));
        map.put("GeometryNodeMeshIcoSphere", call -> {
            MeshGeometry mesh = icoSphere(call.f("Radius"), call.i("Subdivisions"));
            outputWithUv(call, "Mesh", mesh, sphericalUvs(mesh));
        });
        map.put("GeometryNodePoints", MeshPrimitives::points);
    }

    /** Every face flat-shaded, as Blender makes all its mesh primitives. */
    static MeshGeometry flat(MeshGeometry mesh) {
        if (mesh.faceCount() == 0) {
            return mesh;
        }
        return (MeshGeometry) GeometryOps.withAttribute(mesh, "sharp_face", Domain.FACE,
                Column.constant(DataType.BOOL, true, mesh.faceCount()));
    }

    /** Sets a primitive's mesh output and its UV Map output, a field reading the UVs stored on it. */
    private static void outputWithUv(NodeCall call, String output, MeshGeometry mesh, float[] cornerUvs) {
        mesh = flat(mesh);
        if (call.outputSocket("UV Map") != null && cornerUvs != null) {
            String name = anonymous("uv");
            mesh = (MeshGeometry) GeometryOps.withAttribute(mesh, name, Domain.CORNER, Column.ofFloats(DataType.FLOAT2, cornerUvs));
            call.out("UV Map", Inputs.named(name, DataType.VECTOR));
        }
        call.out(output, GeometrySet.of(mesh));
    }

    // --- Grid and line ---------------------------------------------------------------------------------------

    static MeshGeometry grid(int vertsX, int vertsY, float sizeX, float sizeY) {
        if (vertsX < 1 || vertsY < 1) {
            return MeshGeometry.empty();
        }
        int edgesX = vertsX - 1, edgesY = vertsY - 1;
        float dx = edgesX == 0 ? 0f : sizeX / edgesX;
        float dy = edgesY == 0 ? 0f : sizeY / edgesY;
        float shiftX = edgesX / 2f, shiftY = edgesY / 2f;
        float[] p = new float[vertsX * vertsY * 3];
        for (int x = 0; x < vertsX; x++) {
            for (int y = 0; y < vertsY; y++) {
                int v = x * vertsY + y;
                p[3 * v] = (x - shiftX) * dx;
                p[3 * v + 1] = (y - shiftY) * dy;
            }
        }
        int[] edges = new int[(edgesY * vertsX + edgesX * vertsY) * 2];
        int e = 0;
        for (int x = 0; x < vertsX; x++) {
            for (int y = 0; y < edgesY; y++) {
                edges[e++] = x * vertsY + y;
                edges[e++] = x * vertsY + y + 1;
            }
        }
        for (int y = 0; y < vertsY; y++) {
            for (int x = 0; x < edgesX; x++) {
                edges[e++] = x * vertsY + y;
                edges[e++] = (x + 1) * vertsY + y;
            }
        }
        int faces = edgesX * edgesY;
        int[] offsets = new int[faces + 1];
        int[] corners = new int[faces * 4];
        for (int x = 0; x < edgesX; x++) {
            for (int y = 0; y < edgesY; y++) {
                int f = x * edgesY + y;
                int v = x * vertsY + y;
                offsets[f] = 4 * f;
                corners[4 * f] = v;
                corners[4 * f + 1] = v + vertsY;
                corners[4 * f + 2] = v + vertsY + 1;
                corners[4 * f + 3] = v + 1;
            }
        }
        offsets[faces] = faces * 4;
        return MeshGeometry.build(p, offsets, corners, edges);
    }

    private static float[] gridUvs(MeshGeometry mesh, float sizeX, float sizeY) {
        float dx = sizeX == 0f ? 0f : 1f / sizeX;
        float dy = sizeY == 0f ? 0f : 1f / sizeY;
        float[] p = mesh.positions();
        int[] cv = mesh.topology.cornerVerts;
        float[] uv = new float[cv.length * 2];
        for (int c = 0; c < cv.length; c++) {
            uv[2 * c] = (p[3 * cv[c]] + sizeX * 0.5f) * dx;
            uv[2 * c + 1] = (p[3 * cv[c] + 1] + sizeY * 0.5f) * dy;
        }
        return uv;
    }

    static MeshGeometry lineMesh(float[] start, float[] delta, int count) {
        if (count < 1) {
            return MeshGeometry.empty();
        }
        float[] p = new float[count * 3];
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < 3; c++) {
                p[3 * i + c] = start[c] + delta[c] * i;
            }
        }
        int[] edges = new int[(count - 1) * 2];
        for (int i = 0; i < count - 1; i++) {
            edges[2 * i] = i;
            edges[2 * i + 1] = i + 1;
        }
        return MeshGeometry.build(p, new int[]{0}, new int[0], edges);
    }

    private static void line(NodeCall call) {
        String mode = call.prop("mode", "OFFSET");
        String countMode = call.prop("count_mode", "TOTAL");
        float[] start = call.v("Start Location");
        float[] offset = call.v("Offset");
        MeshGeometry mesh;
        if ("END_POINTS".equals(mode)) {
            float[] total = {offset[0] - start[0], offset[1] - start[1], offset[2] - start[2]};
            if ("RESOLUTION".equals(countMode)) {
                float resolution = Math.max(call.f("Resolution"), 0.0001f);
                float length = (float) Math.sqrt(total[0] * total[0] + total[1] * total[1] + total[2] * total[2]);
                int count = (int) (length / resolution) + 1;
                float[] dir = length > 0f ? new float[]{total[0] / length * resolution, total[1] / length * resolution,
                        total[2] / length * resolution} : new float[3];
                mesh = lineMesh(start, dir, count);
            } else {
                int count = call.i("Count");
                float[] delta = count == 1 ? new float[3]
                        : new float[]{total[0] / (count - 1), total[1] / (count - 1), total[2] / (count - 1)};
                mesh = lineMesh(start, delta, count);
            }
        } else {
            mesh = lineMesh(start, offset, call.i("Count"));
        }
        call.out("Mesh", GeometrySet.of(mesh));
    }

    // --- Circle ----------------------------------------------------------------------------------------------

    static MeshGeometry circle(int verts, float radius, String fill) {
        if (verts < 3) {
            return MeshGeometry.empty();
        }
        boolean fan = "TRIANGLE_FAN".equals(fill);
        boolean ngon = "NGON".equals(fill);
        int total = verts + (fan ? 1 : 0);
        float[] p = new float[total * 3];
        float delta = 2f * (float) (Math.PI / verts);
        for (int i = 0; i < verts; i++) {
            float angle = i * delta;
            p[3 * i] = (float) Math.cos(angle) * radius;
            p[3 * i + 1] = (float) Math.sin(angle) * radius;
        }
        int[] edges = new int[(verts + (fan ? verts : 0)) * 2];
        for (int i = 0; i < verts; i++) {
            edges[2 * i] = i;
            edges[2 * i + 1] = (i + 1) % verts;
        }
        if (fan) {
            for (int i = 0; i < verts; i++) {
                edges[2 * (verts + i)] = verts;
                edges[2 * (verts + i) + 1] = i;
            }
        }
        int[] offsets;
        int[] corners;
        if (ngon) {
            offsets = new int[]{0, verts};
            corners = new int[verts];
            for (int i = 0; i < verts; i++) {
                corners[i] = i;
            }
        } else if (fan) {
            offsets = new int[verts + 1];
            corners = new int[verts * 3];
            for (int i = 0; i < verts; i++) {
                offsets[i] = 3 * i;
                corners[3 * i] = i;
                corners[3 * i + 1] = (i + 1) % verts;
                corners[3 * i + 2] = verts;
            }
            offsets[verts] = verts * 3;
        } else {
            offsets = new int[]{0};
            corners = new int[0];
        }
        return MeshGeometry.build(p, offsets, corners, edges);
    }

    // --- Cube ------------------------------------------------------------------------------------------------

    private static void cube(NodeCall call) {
        float[] size = call.v("Size");
        int vx = call.i("Vertices X"), vy = call.i("Vertices Y"), vz = call.i("Vertices Z");
        MeshGeometry mesh = cube(size, vx, vy, vz);
        outputWithUv(call, "Mesh", mesh, boxUvs(mesh));
    }

    static MeshGeometry cube(float[] size, int vx, int vy, int vz) {
        if (vx < 1 || vy < 1 || vz < 1) {
            return MeshGeometry.empty();
        }
        int dimensions = (vx > 1 ? 1 : 0) + (vy > 1 ? 1 : 0) + (vz > 1 ? 1 : 0);
        if (dimensions == 0) {
            return lineMesh(new float[3], new float[3], 1);
        }
        if (dimensions == 1) {
            float[] start = new float[3];
            float[] delta = new float[3];
            int count;
            if (vx > 1) {
                start[0] = -size[0] / 2f;
                delta[0] = size[0] / (vx - 1);
                count = vx;
            } else if (vy > 1) {
                start[1] = -size[1] / 2f;
                delta[1] = size[1] / (vy - 1);
                count = vy;
            } else {
                start[2] = -size[2] / 2f;
                delta[2] = size[2] / (vz - 1);
                count = vz;
            }
            return lineMesh(start, delta, count);
        }
        if (dimensions == 2) {
            if (vz == 1) {
                return grid(vx, vy, size[0], size[1]);
            }
            MeshGeometry flat = vy == 1 ? grid(vx, vz, size[0], size[2]) : grid(vy, vz, size[1], size[2]);
            float[] p = flat.positions().clone();
            for (int k = 0; k < p.length; k += 3) {
                float a = p[k], b = p[k + 1];
                if (vy == 1) {
                    p[k] = a;
                    p[k + 1] = 0f;
                    p[k + 2] = b;
                } else {
                    p[k] = 0f;
                    p[k + 1] = a;
                    p[k + 2] = b;
                }
            }
            return flat.withPositions(p);
        }
        int ex = vx - 1, ey = vy - 1, ez = vz - 1;
        int ring = 2 * vx + 2 * (vy - 2);
        int layer = vx * vy;
        int total = 2 * layer + (vz - 2) * ring;
        float[] p = new float[total * 3];
        float zBottom = -size[2] / 2f, zDelta = size[2] / ez;
        float xLeft = -size[0] / 2f, xDelta = size[0] / ex;
        float yFront = -size[1] / 2f, yDelta = size[1] / ey;
        int at = 0;
        for (int z = 0; z < vz; z++) {
            float zp = zBottom + zDelta * z;
            if (z == 0 || z == ez) {
                for (int y = 0; y < vy; y++) {
                    for (int x = 0; x < vx; x++) {
                        p[3 * at] = xLeft + xDelta * x;
                        p[3 * at + 1] = yFront + yDelta * y;
                        p[3 * at + 2] = zp;
                        at++;
                    }
                }
            } else {
                for (int y = 0; y < vy; y++) {
                    float yp = yFront + yDelta * y;
                    if (y == 0 || y == ey) {
                        for (int x = 0; x < vx; x++) {
                            p[3 * at] = xLeft + xDelta * x;
                            p[3 * at + 1] = yp;
                            p[3 * at + 2] = zp;
                            at++;
                        }
                    } else {
                        p[3 * at] = xLeft;
                        p[3 * at + 1] = yp;
                        p[3 * at + 2] = zp;
                        at++;
                        p[3 * at] = xLeft + xDelta * ex;
                        p[3 * at + 1] = yp;
                        p[3 * at + 2] = zp;
                        at++;
                    }
                }
            }
        }
        CubeIndex v = new CubeIndex(vx, vy, vz);
        java.util.List<int[]> faces = new java.util.ArrayList<>();
        for (int y = 0; y < ey; y++) {
            for (int x = 0; x < ex; x++) {
                faces.add(new int[]{v.at(x, y, 0), v.at(x, y + 1, 0), v.at(x + 1, y + 1, 0), v.at(x + 1, y, 0)});
            }
        }
        for (int z = 0; z < ez; z++) {
            for (int x = 0; x < ex; x++) {
                faces.add(new int[]{v.at(x, 0, z), v.at(x + 1, 0, z), v.at(x + 1, 0, z + 1), v.at(x, 0, z + 1)});
            }
        }
        for (int y = 0; y < ey; y++) {
            for (int x = 0; x < ex; x++) {
                faces.add(new int[]{v.at(x, y, ez), v.at(x + 1, y, ez), v.at(x + 1, y + 1, ez), v.at(x, y + 1, ez)});
            }
        }
        for (int z = 0; z < ez; z++) {
            for (int x = 0; x < ex; x++) {
                faces.add(new int[]{v.at(x, ey, z), v.at(x, ey, z + 1), v.at(x + 1, ey, z + 1), v.at(x + 1, ey, z)});
            }
        }
        for (int z = 0; z < ez; z++) {
            for (int y = 0; y < ey; y++) {
                faces.add(new int[]{v.at(0, y, z), v.at(0, y, z + 1), v.at(0, y + 1, z + 1), v.at(0, y + 1, z)});
            }
        }
        for (int z = 0; z < ez; z++) {
            for (int y = 0; y < ey; y++) {
                faces.add(new int[]{v.at(ex, y, z), v.at(ex, y + 1, z), v.at(ex, y + 1, z + 1), v.at(ex, y, z + 1)});
            }
        }
        return fromFaces(p, faces);
    }

    /** The index of a boundary vertex of a subdivided cube, in Blender's numbering. */
    private record CubeIndex(int vx, int vy, int vz) {
        int at(int x, int y, int z) {
            int layer = vx * vy;
            int ring = 2 * vx + 2 * (vy - 2);
            if (z == 0) {
                return y * vx + x;
            }
            if (z == vz - 1) {
                return layer + (vz - 2) * ring + y * vx + x;
            }
            int base = layer + (z - 1) * ring;
            if (y == 0) {
                return base + x;
            }
            if (y == vy - 1) {
                return base + vx + (vy - 2) * 2 + x;
            }
            return base + vx + (y - 1) * 2 + (x == 0 ? 0 : 1);
        }
    }

    static MeshGeometry fromFaces(float[] positions, java.util.List<int[]> faces) {
        int corners = 0;
        for (int[] f : faces) {
            corners += f.length;
        }
        int[] offsets = new int[faces.size() + 1];
        int[] cv = new int[corners];
        int at = 0;
        for (int k = 0; k < faces.size(); k++) {
            offsets[k] = at;
            for (int vtx : faces.get(k)) {
                cv[at++] = vtx;
            }
        }
        offsets[faces.size()] = corners;
        return MeshGeometry.build(positions, offsets, cv, null);
    }

    /** UVs for a box: each face's corners projected onto its dominant plane, in [0, 1] over the mesh's bounds. */
    private static float[] boxUvs(MeshGeometry mesh) {
        float[] p = mesh.positions();
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int k = 0; k < p.length; k += 3) {
            for (int c = 0; c < 3; c++) {
                min[c] = Math.min(min[c], p[k + c]);
                max[c] = Math.max(max[c], p[k + c]);
            }
        }
        float[] fn = mesh.faceNormals();
        int[] cf = mesh.topology.cornerFaces();
        int[] cv = mesh.topology.cornerVerts;
        float[] uv = new float[cv.length * 2];
        for (int c = 0; c < cv.length; c++) {
            int f = cf[c];
            float ax = Math.abs(fn[3 * f]), ay = Math.abs(fn[3 * f + 1]), az = Math.abs(fn[3 * f + 2]);
            int u, w;
            if (az >= ax && az >= ay) {
                u = 0;
                w = 1;
            } else if (ax >= ay) {
                u = 1;
                w = 2;
            } else {
                u = 0;
                w = 2;
            }
            int vtx = cv[c];
            uv[2 * c] = range(p[3 * vtx + u], min[u], max[u]);
            uv[2 * c + 1] = range(p[3 * vtx + w], min[w], max[w]);
        }
        return uv;
    }

    private static float range(float x, float lo, float hi) {
        return hi > lo ? (x - lo) / (hi - lo) : 0f;
    }

    // --- Cylinder and cone ------------------------------------------------------------------------------------

    private static void cone(NodeCall call, float radiusTop, float radiusBottom, float depth, boolean cone) {
        int segments = call.i("Vertices");
        int sideSegments = Math.max(1, call.i("Side Segments"));
        String fill = call.prop("fill_type", "NGON");
        int fillSegments = "NONE".equals(fill) || !call.has("Fill Segments") ? 1 : Math.max(1, call.i("Fill Segments"));
        if (segments < 3) {
            call.out("Mesh", GeometrySet.EMPTY);
            return;
        }
        float height = depth * 0.5f;
        boolean topPoint = radiusTop == 0f;
        boolean bottomPoint = radiusBottom == 0f;
        boolean fan = "TRIANGLE_FAN".equals(fill);
        boolean ngon = "NGON".equals(fill);
        boolean topCenter = topPoint || fan;
        boolean bottomCenter = bottomPoint || fan;
        if (topPoint && bottomPoint) {
            float[] start = {0f, 0f, height};
            float[] delta = {0f, 0f, -2f * height / sideSegments};
            MeshGeometry line = lineMesh(start, delta, sideSegments + 1);
            if (cone) {
                line = GeometryOps.transform(line, translation(0f, 0f, height));
            }
            call.out("Mesh", GeometrySet.of(line));
            return;
        }
        int total = (topCenter ? 1 : 0) + (topPoint ? 0 : segments * fillSegments) + segments * (sideSegments - 1)
                + (bottomPoint ? 0 : segments * fillSegments) + (bottomCenter ? 1 : 0);
        float[] circleX = new float[segments], circleY = new float[segments];
        float angleDelta = 2f * (float) (Math.PI / segments);
        float angle = 0f;
        for (int i = 0; i < segments; i++) {
            circleX[i] = (float) Math.cos(angle);
            circleY[i] = (float) Math.sin(angle);
            angle += angleDelta;
        }
        float[] p = new float[total * 3];
        int at = 0;
        if (topCenter) {
            p[2] = height;
            at++;
        }
        int topFillStart = at;
        if (!topPoint) {
            float delta = radiusTop / fillSegments;
            for (int i = 0; i < fillSegments; i++) {
                float r = delta * (i + 1);
                for (int j = 0; j < segments; j++) {
                    p[3 * at] = circleX[j] * r;
                    p[3 * at + 1] = circleY[j] * r;
                    p[3 * at + 2] = height;
                    at++;
                }
            }
        }
        float sideRadiusDelta = (radiusBottom - radiusTop) / sideSegments;
        float heightDelta = 2f * height / sideSegments;
        for (int i = 0; i < sideSegments - 1; i++) {
            float r = radiusTop + sideRadiusDelta * (i + 1);
            float z = height - heightDelta * (i + 1);
            for (int j = 0; j < segments; j++) {
                p[3 * at] = circleX[j] * r;
                p[3 * at + 1] = circleY[j] * r;
                p[3 * at + 2] = z;
                at++;
            }
        }
        if (!bottomPoint) {
            float delta = radiusBottom / fillSegments;
            for (int i = 0; i < fillSegments; i++) {
                float r = radiusBottom - i * delta;
                for (int j = 0; j < segments; j++) {
                    p[3 * at] = circleX[j] * r;
                    p[3 * at + 1] = circleY[j] * r;
                    p[3 * at + 2] = -height;
                    at++;
                }
            }
        }
        if (bottomCenter) {
            p[3 * at + 2] = -height;
            at++;
        }
        int quadRings = (topPoint ? 0 : fillSegments - 1) + (!topPoint && !bottomPoint ? sideSegments : sideSegments - 1)
                + (bottomPoint ? 0 : fillSegments - 1);
        int firstRing = topCenter ? 1 : 0;
        int lastVert = total - 1;
        int lastRing = bottomCenter ? lastVert - segments : total - segments;
        java.util.List<int[]> faces = new java.util.ArrayList<>();
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (topCenter) {
            for (int i = 0; i < segments; i++) {
                faces.add(new int[]{firstRing + i, firstRing + (i + 1) % segments, 0});
                parts.add(topPoint ? "side" : "top");
            }
        } else if (ngon) {
            int[] f = new int[segments];
            for (int i = 0; i < segments; i++) {
                f[i] = i;
            }
            faces.add(f);
            parts.add("top");
        }
        int topFillQuads = topPoint ? 0 : fillSegments - 1;
        int bottomFillQuadsStart = quadRings - (bottomPoint ? 0 : fillSegments - 1);
        for (int ring = 0; ring < quadRings; ring++) {
            int thisRing = firstRing + ring * segments;
            int nextRing = thisRing + segments;
            String part = ring < topFillQuads ? "top" : ring >= bottomFillQuadsStart ? "bottom" : "side";
            for (int j = 0; j < segments; j++) {
                faces.add(new int[]{thisRing + j, nextRing + j, nextRing + (j + 1) % segments, thisRing + (j + 1) % segments});
                parts.add(part);
            }
        }
        if (bottomCenter) {
            for (int i = 0; i < segments; i++) {
                faces.add(new int[]{lastRing + i, lastVert, lastRing + (i + 1) % segments});
                parts.add(bottomPoint ? "side" : "bottom");
            }
        } else if (ngon) {
            int[] f = new int[segments];
            for (int i = 0; i < segments; i++) {
                f[i] = lastRing + segments - 1 - i;
            }
            faces.add(f);
            parts.add("bottom");
        }
        MeshGeometry mesh = fromFaces(p, faces);
        if (cone) {
            mesh = GeometryOps.transform(mesh, translation(0f, 0f, height));
        }
        for (String part : new String[]{"top", "side", "bottom"}) {
            String output = Character.toUpperCase(part.charAt(0)) + part.substring(1);
            if (call.outputSocket(output) == null) {
                continue;
            }
            int[] flags = new int[faces.size()];
            for (int f = 0; f < flags.length; f++) {
                flags[f] = parts.get(f).equals(part) ? 1 : 0;
            }
            String name = anonymous(part);
            mesh = (MeshGeometry) GeometryOps.withAttribute(mesh, name, Domain.FACE, Column.ofInts(DataType.BOOL, flags));
            call.out(output, Inputs.named(name, DataType.BOOL));
        }
        outputWithUv(call, "Mesh", mesh, cylinderUvs(mesh));
    }

    static float[] translation(float x, float y, float z) {
        float[] m = Transforms.identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    private static float[] cylinderUvs(MeshGeometry mesh) {
        float[] p = mesh.positions();
        float[] fn = mesh.faceNormals();
        int[] cf = mesh.topology.cornerFaces();
        int[] cv = mesh.topology.cornerVerts;
        float zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE, rMax = 0f;
        for (int k = 0; k < p.length; k += 3) {
            zMin = Math.min(zMin, p[k + 2]);
            zMax = Math.max(zMax, p[k + 2]);
            rMax = Math.max(rMax, (float) Math.hypot(p[k], p[k + 1]));
        }
        float[] uv = new float[cv.length * 2];
        for (int c = 0; c < cv.length; c++) {
            int f = cf[c];
            int v = cv[c];
            if (Math.abs(fn[3 * f + 2]) > 0.99f) {
                float s = rMax > 0f ? 0.25f / rMax : 0f;
                float cx = fn[3 * f + 2] > 0f ? 0.75f : 0.25f;
                uv[2 * c] = cx + p[3 * v] * s;
                uv[2 * c + 1] = 0.25f + p[3 * v + 1] * s;
            } else {
                float a = (float) (Math.atan2(p[3 * v + 1], p[3 * v]) / (2 * Math.PI));
                uv[2 * c] = a < 0f ? a + 1f : a;
                uv[2 * c + 1] = 0.5f + 0.5f * range(p[3 * v + 2], zMin, zMax);
            }
        }
        return uv;
    }

    // --- Spheres ---------------------------------------------------------------------------------------------

    private static void uvSphere(NodeCall call, int segments, int rings, float radius) {
        if (segments < 3 || rings < 2) {
            call.out("Mesh", GeometrySet.EMPTY);
            return;
        }
        float deltaTheta = (float) (Math.PI / rings);
        float deltaPhi = (float) (2.0 * Math.PI / segments);
        float[] cos = new float[segments + 1], sin = new float[segments + 1];
        for (int s = 1; s <= segments; s++) {
            float phi = s * deltaPhi;
            cos[s] = (float) Math.cos(phi);
            sin[s] = (float) Math.sin(phi);
        }
        int total = segments * (rings - 1) + 2;
        float[] p = new float[total * 3];
        p[2] = radius;
        int at = 1;
        for (int ring = 1; ring < rings; ring++) {
            float theta = ring * deltaTheta;
            float st = (float) Math.sin(theta), z = (float) Math.cos(theta);
            for (int s = 1; s <= segments; s++) {
                p[3 * at] = st * cos[s] * radius;
                p[3 * at + 1] = st * sin[s] * radius;
                p[3 * at + 2] = z * radius;
                at++;
            }
        }
        p[3 * at + 2] = -radius;
        int last = total - 1;
        java.util.List<int[]> faces = new java.util.ArrayList<>();
        for (int s = 0; s < segments; s++) {
            faces.add(new int[]{0, 1 + s, 1 + (s + 1) % segments});
        }
        for (int ring = 0; ring < rings - 2; ring++) {
            int a = 1 + ring * segments, b = a + segments;
            for (int s = 0; s < segments; s++) {
                faces.add(new int[]{a + s, b + s, b + (s + 1) % segments, a + (s + 1) % segments});
            }
        }
        int lastRing = 1 + (rings - 2) * segments;
        for (int s = 0; s < segments; s++) {
            faces.add(new int[]{last, lastRing + (s + 1) % segments, lastRing + s});
        }
        MeshGeometry mesh = fromFaces(p, faces);
        outputWithUv(call, "Mesh", mesh, sphericalUvs(mesh));
    }

    /** Blender's base icosahedron (subdivision level 1), vertex and face order as Blender makes it. */
    private static final float[][] ICO_VERTS = {
            {0f, 0f, -200f}, {144.72f, -105.144f, -89.443f}, {-55.277f, -170.128f, -89.443f}, {-178.885f, 0f, -89.443f},
            {-55.277f, 170.128f, -89.443f}, {144.72f, 105.144f, -89.443f}, {55.277f, -170.128f, 89.443f},
            {-144.72f, -105.144f, 89.443f}, {-144.72f, 105.144f, 89.443f}, {55.277f, 170.128f, 89.443f},
            {178.885f, 0f, 89.443f}, {0f, 0f, 200f}};
    private static final int[][] ICO_FACES = {
            {0, 1, 2}, {1, 0, 5}, {0, 2, 3}, {0, 3, 4}, {0, 4, 5}, {1, 5, 10}, {2, 1, 6}, {3, 2, 7}, {4, 3, 8}, {5, 4, 9},
            {1, 10, 6}, {2, 6, 7}, {3, 7, 8}, {4, 8, 9}, {5, 9, 10}, {6, 10, 11}, {7, 6, 11}, {8, 7, 11}, {9, 8, 11},
            {10, 9, 11}};

    /**
     * An icosphere. Level 1 is Blender's icosahedron exactly; higher levels split every triangle in four with the
     * new points pushed onto the sphere, the same surface Blender makes with its vertices in a different order.
     */
    static MeshGeometry icoSphere(float radius, int subdivisions) {
        java.util.List<float[]> verts = new java.util.ArrayList<>();
        for (float[] v : ICO_VERTS) {
            verts.add(new float[]{v[0] / 200f, v[1] / 200f, v[2] / 200f});
        }
        java.util.List<int[]> faces = new java.util.ArrayList<>();
        for (int[] f : ICO_FACES) {
            faces.add(f.clone());
        }
        for (int level = 1; level < Math.min(subdivisions, 8); level++) {
            Map<Long, Integer> mid = new java.util.HashMap<>();
            java.util.List<int[]> next = new java.util.ArrayList<>();
            for (int[] f : faces) {
                int a = midpoint(verts, mid, f[0], f[1]);
                int b = midpoint(verts, mid, f[1], f[2]);
                int c = midpoint(verts, mid, f[2], f[0]);
                next.add(new int[]{f[0], a, c});
                next.add(new int[]{a, f[1], b});
                next.add(new int[]{c, b, f[2]});
                next.add(new int[]{a, b, c});
            }
            faces = next;
        }
        float[] p = new float[verts.size() * 3];
        for (int k = 0; k < verts.size(); k++) {
            float[] v = verts.get(k);
            p[3 * k] = v[0] * radius;
            p[3 * k + 1] = v[1] * radius;
            p[3 * k + 2] = v[2] * radius;
        }
        return fromFaces(p, faces);
    }

    private static int midpoint(java.util.List<float[]> verts, Map<Long, Integer> cache, int a, int b) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        Integer found = cache.get(key);
        if (found != null) {
            return found;
        }
        float[] va = verts.get(a), vb = verts.get(b);
        float x = (va[0] + vb[0]) * 0.5f, y = (va[1] + vb[1]) * 0.5f, z = (va[2] + vb[2]) * 0.5f;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        verts.add(new float[]{x / len, y / len, z / len});
        cache.put(key, verts.size() - 1);
        return verts.size() - 1;
    }

    private static float[] sphericalUvs(MeshGeometry mesh) {
        float[] p = mesh.positions();
        int[] cv = mesh.topology.cornerVerts;
        float[] uv = new float[cv.length * 2];
        for (int c = 0; c < cv.length; c++) {
            int v = cv[c];
            float x = p[3 * v], y = p[3 * v + 1], z = p[3 * v + 2];
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            float u = (float) (Math.atan2(y, x) / (2 * Math.PI));
            uv[2 * c] = u < 0f ? u + 1f : u;
            uv[2 * c + 1] = len > 0f ? (float) (0.5 + Math.asin(Math.clamp(z / len, -1f, 1f)) / Math.PI) : 0.5f;
        }
        return uv;
    }

    // --- Points ------------------------------------------------------------------------------------------------

    private static void points(NodeCall call) {
        int count = Math.max(0, call.i("Count"));
        PointGeometry points = PointGeometry.of(new float[count * 3]);
        FieldContext context = call.context(points, Domain.POINT);
        float[] positions = context.evalAs(call.field("Position"), DataType.VECTOR).f.clone();
        Column radius = context.evalAs(call.field("Radius"), DataType.FLOAT);
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, positions));
        attributes.put("radius", Domain.POINT, radius.copy());
        call.out("Geometry", GeometrySet.of(new PointGeometry(count, attributes)));
    }
}
