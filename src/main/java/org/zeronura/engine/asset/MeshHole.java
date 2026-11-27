package org.zeronura.engine.asset;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a rectangular hole clean through a mesh.
 *
 * <p>A boolean subtraction, but deliberately <b>not</b> a general one. General CSG is a BSP tree, a
 * robustness problem and a library; what is actually wanted here is a window in a wall, and a window is
 * an axis-aligned <b>prism</b> through a flat panel. Restricting the cutter to that shape makes it a
 * page of triangle clipping that is exact, has no tolerance to tune and cannot produce a degenerate
 * solid — where general CSG on two coincident planes is where such libraries famously fall over, and two
 * coincident planes is precisely what a window sitting in a wall gives you.
 *
 * <p>The prism runs along <b>Z</b> and the rectangle is given in XY, which is the frame every wall in
 * this project is authored in: a wall spans X, stands in Y and is thin in Z. Z is untouched, so the cut
 * goes right through however thick the thing is and however its faces are angled.
 *
 * <h2>It leaves no reveal, and that is the caller's problem rather than an omission</h2>
 * Subtracting a prism removes material; it does not add the four inner faces of the hole. Adding them
 * would need a surface normal and a colour that the cutter has no business inventing — and for the case
 * this exists for they would never be seen anyway, because the window's own frame is deeper than the
 * wall and covers the cut edge. A caller cutting a hole nothing stands in will see through the wall's
 * thickness at a grazing angle.
 *
 * <p>GL-free, and it keeps the vertices it does not have to touch: a triangle clear of the rectangle
 * passes through with its original indices, so the sharing in the source mesh survives everywhere except
 * across the few triangles the hole actually crosses.
 */
public final class MeshHole {

    /** How many floats describe one vertex here: position, normal, colour. */
    private static final int STRIDE = 9;

    /**
     * A mesh as {@code VillageModel} and the other decomposers hold one — parallel arrays and a triangle
     * list, with no GL object and no vertex layout in sight.
     */
    public record Geometry(float[] positions, float[] normals, float[] colors, int[] indices) {
    }

    private MeshHole() {
    }

    /**
     * Everything of {@code mesh} outside the rectangle {@code [minX, maxX] x [minY, maxY]}, extruded
     * through the whole of Z.
     *
     * <p>Each triangle is clipped against the rectangle's four edges in turn, keeping what falls
     * <i>outside</i> each one and passing what falls inside to the next. Whatever survives all four is
     * inside the rectangle on every axis, which is inside the hole, and is dropped. Every clip produces
     * convex pieces, so fanning them into triangles is exact.
     */
    public static Geometry cut(Geometry mesh, float minX, float maxX, float minY, float maxY) {
        List<float[]> added = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int originalVertices = mesh.positions().length / 3;

        for (int t = 0; t < mesh.indices().length; t += 3) {
            int a = mesh.indices()[t];
            int b = mesh.indices()[t + 1];
            int c = mesh.indices()[t + 2];

            // The two cheap answers first, and they cover almost every triangle in a real wall: measured
            // on this kit's plaster, a window crosses four triangles of a hundred and twenty-four.
            if (outsideRect(mesh, a, b, c, minX, maxX, minY, maxY)) {
                indices.add(a);
                indices.add(b);
                indices.add(c);
                continue;
            }
            if (insideRect(mesh, a, b, c, minX, maxX, minY, maxY)) {
                continue;
            }

            List<float[]> polygon = new ArrayList<>(3);
            polygon.add(vertex(mesh, a));
            polygon.add(vertex(mesh, b));
            polygon.add(vertex(mesh, c));

            polygon = clip(polygon, 0, minX, true, added, indices, originalVertices);
            polygon = clip(polygon, 0, maxX, false, added, indices, originalVertices);
            polygon = clip(polygon, 1, minY, true, added, indices, originalVertices);
            polygon = clip(polygon, 1, maxY, false, added, indices, originalVertices);
            // Anything left is inside the rectangle on all four sides: the hole itself. Dropped.
        }

        float[] positions = new float[(originalVertices + added.size()) * 3];
        float[] normals = new float[(originalVertices + added.size()) * 3];
        float[] colors = new float[(originalVertices + added.size()) * 3];
        System.arraycopy(mesh.positions(), 0, positions, 0, mesh.positions().length);
        System.arraycopy(mesh.normals(), 0, normals, 0, mesh.normals().length);
        System.arraycopy(mesh.colors(), 0, colors, 0, mesh.colors().length);
        for (int i = 0; i < added.size(); i++) {
            float[] v = added.get(i);
            int at = (originalVertices + i) * 3;
            positions[at] = v[0];
            positions[at + 1] = v[1];
            positions[at + 2] = v[2];
            normals[at] = v[3];
            normals[at + 1] = v[4];
            normals[at + 2] = v[5];
            colors[at] = v[6];
            colors[at + 1] = v[7];
            colors[at + 2] = v[8];
        }

        int[] out = new int[indices.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = indices.get(i);
        }
        return new Geometry(positions, normals, colors, out);
    }

    /**
     * Splits a polygon on one edge of the rectangle: the part <b>outside</b> is emitted and the part
     * inside is returned to be clipped against the next edge.
     *
     * <p>{@code insideIsGreater} says which side of {@code limit} is inside the rectangle, so the four
     * calls read as the four edges rather than as two pairs of sign flips.
     */
    private static List<float[]> clip(List<float[]> polygon, int axis, float limit,
                                      boolean insideIsGreater, List<float[]> added,
                                      List<Integer> indices, int originalVertices) {
        if (polygon.size() < 3) {
            return List.of();
        }
        List<float[]> inside = new ArrayList<>();
        List<float[]> outside = new ArrayList<>();
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            float[] p = polygon.get(i);
            float[] q = polygon.get((i + 1) % n);
            float dp = insideIsGreater ? p[axis] - limit : limit - p[axis];
            float dq = insideIsGreater ? q[axis] - limit : limit - q[axis];
            if (dp >= 0f) {
                inside.add(p);
            } else {
                outside.add(p);
            }
            if (dp >= 0f != dq >= 0f) {
                // The crossing point belongs to both halves, and is computed once so the two pieces share
                // it exactly — a second evaluation could round differently and leave a crack.
                float[] crossing = lerp(p, q, dp / (dp - dq));
                inside.add(crossing);
                outside.add(crossing);
            }
        }
        emit(outside, added, indices, originalVertices);
        return inside;
    }

    /** Fans a convex polygon into triangles. */
    private static void emit(List<float[]> polygon, List<float[]> added, List<Integer> indices,
                             int originalVertices) {
        if (polygon.size() < 3) {
            return;
        }
        int base = originalVertices + added.size();
        added.addAll(polygon);
        for (int i = 1; i + 1 < polygon.size(); i++) {
            indices.add(base);
            indices.add(base + i);
            indices.add(base + i + 1);
        }
    }

    private static float[] lerp(float[] p, float[] q, float t) {
        float[] out = new float[STRIDE];
        for (int i = 0; i < STRIDE; i++) {
            out[i] = p[i] + (q[i] - p[i]) * t;
        }
        return out;
    }

    private static float[] vertex(Geometry mesh, int index) {
        return new float[]{
                mesh.positions()[index * 3], mesh.positions()[index * 3 + 1],
                mesh.positions()[index * 3 + 2],
                mesh.normals()[index * 3], mesh.normals()[index * 3 + 1], mesh.normals()[index * 3 + 2],
                mesh.colors()[index * 3], mesh.colors()[index * 3 + 1], mesh.colors()[index * 3 + 2]};
    }

    private static boolean outsideRect(Geometry mesh, int a, int b, int c,
                                       float minX, float maxX, float minY, float maxY) {
        return mesh.positions()[a * 3] <= minX && mesh.positions()[b * 3] <= minX
                && mesh.positions()[c * 3] <= minX
                || mesh.positions()[a * 3] >= maxX && mesh.positions()[b * 3] >= maxX
                && mesh.positions()[c * 3] >= maxX
                || mesh.positions()[a * 3 + 1] <= minY && mesh.positions()[b * 3 + 1] <= minY
                && mesh.positions()[c * 3 + 1] <= minY
                || mesh.positions()[a * 3 + 1] >= maxY && mesh.positions()[b * 3 + 1] >= maxY
                && mesh.positions()[c * 3 + 1] >= maxY;
    }

    private static boolean insideRect(Geometry mesh, int a, int b, int c,
                                      float minX, float maxX, float minY, float maxY) {
        for (int index : new int[]{a, b, c}) {
            float x = mesh.positions()[index * 3];
            float y = mesh.positions()[index * 3 + 1];
            if (x < minX || x > maxX || y < minY || y > maxY) {
                return false;
            }
        }
        return true;
    }
}
