package org.zeronura.engine.worldmap;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.MeshBuilder3D;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The road pieces a kit doesn't have, built to match its straight piece: the same asphalt and paint, the same
 * thickness and lines. Junction hubs shaped to the roads meeting at them, straights cut short to fill a gap, and the
 * roundabout. {@link #install} registers them, and the kit's own pieces under the {@link RoadBuilder} ids, with a
 * {@link PrefabCatalog}.
 *
 * <p>Pieces are built in their own frame like the kit's: the road running along +z, the surface at
 * {@value #SURFACE} m with the paint on top.
 */
public final class RoadMeshes {

    /**
     * What a game's road kit looks like: which models are its pieces, and its colours for the pieces made here. A
     * piece left null is made here too (the crosswalk), as are the sidewalk and corner tiles' absence: no sidewalks.
     */
    public record Style(String straight, String corner, String end, String crosswalk, String sidewalk, String cornerTile,
                        Color asphalt, Color yellow, Color white, Color curb, Color island) {
    }

    static final float SURFACE = 0.14f;
    static final float PAINT = 0.16f;
    /** The white edge line, as far in from the road's edge as the kit's: from 3.46 to 3.62 m off the middle. */
    static final float EDGE_LINE_IN = 0.38f;
    static final float LINE_WIDTH = 0.16f;

    private RoadMeshes() {
    }

    /**
     * Registers the road pieces with a catalogue: the kit's under their road ids, and the ones made here on demand.
     * Country lanes are laid with the same pieces.
     */
    public static void install(PrefabCatalog catalog, Style style) {
        for (String lane : new String[]{"", "lane/"}) {
            catalog.alias(RoadBuilder.PREFIX + lane + "straight", style.straight());
            catalog.alias(RoadBuilder.PREFIX + lane + "corner", style.corner());
            catalog.alias(RoadBuilder.PREFIX + lane + "end", style.end());
        }
        if (style.crosswalk() != null) {
            catalog.alias(RoadBuilder.CROSSWALK, style.crosswalk());
        }
        if (style.sidewalk() != null) {
            catalog.alias(RoadBuilder.SIDEWALK, style.sidewalk());
        }
        if (style.cornerTile() != null) {
            catalog.alias(RoadBuilder.CORNER_TILE, style.cornerTile());
        }
        catalog.generate(RoadBuilder.PREFIX, id -> build(id, style));
    }

    /** The mesh for a made road piece's id, or null for an id it doesn't make. */
    static MeshBuilder3D build(String id, Style style) {
        id = RoadBuilder.withoutLane(id);
        try {
            if (id.equals(RoadBuilder.ROUNDABOUT)) {
                return roundabout(style);
            }
            if (id.equals(RoadBuilder.CROSSWALK)) {
                return crosswalk(style);
            }
            if (id.startsWith(RoadBuilder.FILL)) {
                return fill(Float.parseFloat(id.substring(RoadBuilder.FILL.length())), style);
            }
            if (id.startsWith(RoadBuilder.HUB)) {
                String[] parts = id.substring(RoadBuilder.HUB.length()).split(",");
                int[] relative = new int[parts.length];
                for (int k = 0; k < parts.length; k++) {
                    relative[k] = Integer.parseInt(parts[k].trim());
                }
                return hub(relative, style);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** A straight cut to {@code length}: asphalt, the white edge lines and a solid double yellow line. */
    static MeshBuilder3D fill(float length, Style style) {
        MeshBuilder3D mesh = new MeshBuilder3D();
        float w = RoadBuilder.HALF_WIDTH;
        mesh.box(0f, SURFACE * 0.5f, 0f, 2f * w, SURFACE, length, style.asphalt());
        float edge = w - EDGE_LINE_IN - LINE_WIDTH * 0.5f;
        flat(mesh, -edge, 0f, LINE_WIDTH, length, style.white());
        flat(mesh, edge, 0f, LINE_WIDTH, length, style.white());
        flat(mesh, -0.1f, 0f, 0.12f, length, style.yellow());
        flat(mesh, 0.1f, 0f, 0.12f, length, style.yellow());
        return mesh;
    }

    /**
     * A straight with a zebra crossing, for a kit without one: stripes running with the road between a white stop line
     * at each side, as the City kit's crosswalk is painted.
     */
    static MeshBuilder3D crosswalk(Style style) {
        MeshBuilder3D mesh = new MeshBuilder3D();
        float w = RoadBuilder.HALF_WIDTH;
        mesh.box(0f, SURFACE * 0.5f, 0f, 2f * w, SURFACE, RoadBuilder.PIECE_LENGTH, style.asphalt());
        float across = w - 0.45f;
        flat(mesh, 0f, -2.93f, 2f * across, 0.16f, style.white());
        flat(mesh, 0f, 2.93f, 2f * across, 0.16f, style.white());
        int stripes = 9;
        float stripe = 0.42f;
        float pitch = (2f * across - stripe) / (stripes - 1);
        for (int k = 0; k < stripes; k++) {
            flat(mesh, -across + stripe * 0.5f + k * pitch, 0f, stripe, 5.1f, style.white());
        }
        return mesh;
    }

    /** A painted strip lying on the surface, centred at (x, z), {@code width} across x and {@code length} along z. */
    private static void flat(MeshBuilder3D mesh, float x, float z, float width, float length, Color color) {
        float x0 = x - width * 0.5f, x1 = x + width * 0.5f;
        float z0 = z - length * 0.5f, z1 = z + length * 0.5f;
        quad(mesh, x0, z0, x1, z0, x1, z1, x0, z1, PAINT, color);
    }

    private static void quad(MeshBuilder3D mesh, float ax, float az, float bx, float bz, float cx, float cz, float dx,
                             float dz, float y, Color color) {
        int a = mesh.vertex(ax, y, az, 0f, 1f, 0f, color);
        int b = mesh.vertex(bx, y, bz, 0f, 1f, 0f, color);
        int c = mesh.vertex(cx, y, cz, 0f, 1f, 0f, color);
        int d = mesh.vertex(dx, y, dz, 0f, 1f, 0f, color);
        mesh.triangleFacing(a, b, c, 0f, 1f, 0f);
        mesh.triangleFacing(a, c, d, 0f, 1f, 0f);
    }

    /**
     * A junction for roads leaving at these angles from the first (degrees, the yaw convention: 0 is +z, 90 is +x).
     * Its asphalt is the outline round the ends of the roads, each pulled back by {@link RoadBuilder#reaches} — a square
     * for a T or a crossroads — with the white edge line along every side no road leaves through.
     */
    static MeshBuilder3D hub(int[] relative, Style style) {
        float w = RoadBuilder.HALF_WIDTH;
        float[] reach = RoadBuilder.reaches(relative);
        List<float[]> points = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        for (int k = 0; k < relative.length; k++) {
            double angle = Math.toRadians(relative[k]);
            float dx = (float) Math.sin(angle);
            float dz = (float) Math.cos(angle);
            float px = dz;     // the road's own +x, at right angles to it
            float pz = -dx;
            points.add(new float[]{dx * reach[k] + px * w, dz * reach[k] + pz * w});
            owners.add(k);
            points.add(new float[]{dx * reach[k] - px * w, dz * reach[k] - pz * w});
            owners.add(k);
        }
        // Points shared by two roads (the corners of a square junction) belong to both.
        List<float[]> hull = hull(points);
        List<Set<Integer>> belongs = new ArrayList<>();
        for (float[] corner : hull) {
            Set<Integer> roads = new HashSet<>();
            for (int k = 0; k < points.size(); k++) {
                if (Math.abs(points.get(k)[0] - corner[0]) < 0.01f && Math.abs(points.get(k)[1] - corner[1]) < 0.01f) {
                    roads.add(owners.get(k));
                }
            }
            belongs.add(roads);
        }

        MeshBuilder3D mesh = new MeshBuilder3D();
        int centre = mesh.vertex(0f, SURFACE, 0f, 0f, 1f, 0f, style.asphalt());
        int n = hull.size();
        int[] ring = new int[n];
        for (int k = 0; k < n; k++) {
            ring[k] = mesh.vertex(hull.get(k)[0], SURFACE, hull.get(k)[1], 0f, 1f, 0f, style.asphalt());
        }
        for (int k = 0; k < n; k++) {
            mesh.triangleFacing(centre, ring[k], ring[(k + 1) % n], 0f, 1f, 0f);
            // A skirt down to the base, so a junction on a slight slope shows no gap under its edge.
            float[] p = hull.get(k);
            float[] q = hull.get((k + 1) % n);
            float ex = q[0] - p[0];
            float ez = q[1] - p[1];
            float length = (float) Math.hypot(ex, ez);
            if (length < 1e-4f) {
                continue;
            }
            float nx = ez / length;
            float nz = -ex / length;
            int a = mesh.vertex(p[0], SURFACE, p[1], nx, 0f, nz, style.asphalt());
            int b = mesh.vertex(q[0], SURFACE, q[1], nx, 0f, nz, style.asphalt());
            int c = mesh.vertex(q[0], 0f, q[1], nx, 0f, nz, style.asphalt());
            int d = mesh.vertex(p[0], 0f, p[1], nx, 0f, nz, style.asphalt());
            mesh.triangleFacing(a, b, c, nx, 0f, nz);
            mesh.triangleFacing(a, c, d, nx, 0f, nz);
        }
        // The edge line along each side between two different roads' ends.
        for (int k = 0; k < n; k++) {
            Set<Integer> shared = new HashSet<>(belongs.get(k));
            shared.retainAll(belongs.get((k + 1) % n));
            if (!shared.isEmpty()) {
                continue;   // the open end of a road
            }
            float[] p = hull.get(k);
            float[] q = hull.get((k + 1) % n);
            float ex = q[0] - p[0];
            float ez = q[1] - p[1];
            float length = (float) Math.hypot(ex, ez);
            if (length < 0.5f) {
                continue;
            }
            // Inward is toward the middle of the junction.
            float ix = -ez / length;
            float iz = ex / length;
            if (ix * -p[0] + iz * -p[1] < 0f) {
                ix = -ix;
                iz = -iz;
            }
            float inner = EDGE_LINE_IN + LINE_WIDTH;
            quad(mesh, p[0] + ix * EDGE_LINE_IN, p[1] + iz * EDGE_LINE_IN, q[0] + ix * EDGE_LINE_IN, q[1] + iz * EDGE_LINE_IN,
                    q[0] + ix * inner, q[1] + iz * inner, p[0] + ix * inner, p[1] + iz * inner, PAINT, style.white());
        }
        return mesh;
    }

    /** The convex outline round a set of points, anticlockwise, without repeats. */
    public static List<float[]> hull(List<float[]> input) {
        List<float[]> points = new ArrayList<>();
        for (float[] p : input) {
            boolean seen = points.stream().anyMatch(q -> Math.abs(q[0] - p[0]) < 0.01f && Math.abs(q[1] - p[1]) < 0.01f);
            if (!seen) {
                points.add(p);
            }
        }
        points.sort((a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
        if (points.size() < 3) {
            return points;
        }
        List<float[]> lower = new ArrayList<>();
        for (float[] p : points) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.getLast(), p) <= 0f) {
                lower.removeLast();
            }
            lower.add(p);
        }
        List<float[]> upper = new ArrayList<>();
        for (int k = points.size() - 1; k >= 0; k--) {
            float[] p = points.get(k);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.getLast(), p) <= 0f) {
                upper.removeLast();
            }
            upper.add(p);
        }
        lower.removeLast();
        upper.removeLast();
        lower.addAll(upper);
        return lower;
    }

    private static float cross(float[] o, float[] a, float[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * A roundabout {@value RoadBuilder#ROUNDABOUT_RADIUS} m across the outside: a ring of asphalt with a white line round
     * its outer edge and a dashed one between its lanes, round a raised island of grass inside a curb.
     */
    static MeshBuilder3D roundabout(Style style) {
        MeshBuilder3D mesh = new MeshBuilder3D();
        float outer = RoadBuilder.ROUNDABOUT_RADIUS;
        float islandEdge = 5f;
        int segments = 64;
        ring(mesh, islandEdge, outer, SURFACE, segments, style.asphalt(), 1);
        ring(mesh, outer - EDGE_LINE_IN - LINE_WIDTH, outer - EDGE_LINE_IN, PAINT, segments, style.white(), 1);
        ring(mesh, islandEdge + EDGE_LINE_IN, islandEdge + EDGE_LINE_IN + LINE_WIDTH, PAINT, segments, style.white(), 1);
        float lane = (islandEdge + outer) * 0.5f;
        ring(mesh, lane - LINE_WIDTH * 0.5f, lane + LINE_WIDTH * 0.5f, PAINT, segments, style.white(), 2);
        // The island: a curb, then grass a little lower inside it.
        mesh.cylinder(0f, 0f, 0f, islandEdge, 0.35f, segments, style.curb(), true);
        mesh.cylinder(0f, 0f, 0f, islandEdge - 0.4f, 0.4f, segments, style.island(), true);
        return mesh;
    }

    /** A flat ring between two radii at a height; with {@code every} 2, only every other segment (a dashed line). */
    private static void ring(MeshBuilder3D mesh, float inner, float outer, float y, int segments, Color color, int every) {
        for (int k = 0; k < segments; k += every) {
            double a0 = k * Math.PI * 2 / segments;
            double a1 = (k + 1) * Math.PI * 2 / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            quad(mesh, inner * c0, inner * s0, outer * c0, outer * s0, outer * c1, outer * s1, inner * c1, inner * s1, y, color);
        }
    }
}
