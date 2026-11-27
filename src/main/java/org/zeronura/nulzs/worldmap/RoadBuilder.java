package org.zeronura.nulzs.worldmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lays a {@link RoadNetwork} as road pieces: map objects whose prefab ids start with {@value #PREFIX}, rebuilt from the
 * network whenever it changes. Every junction gets the piece its roads call for —
 *
 * <ul>
 *   <li>a dead end: the end piece, its curb across the closed side;
 *   <li>two roads in line: a straight; two at a right angle: the corner;
 *   <li>three or more, or two at any other angle: a junction piece shaped to them ({@link RoadMeshes#hub}) — a T or a
 *       crossroads when they meet square, and otherwise a hub whose edges run between the roads, each road pulled back
 *       far enough that its neighbours never overlap it;
 *   <li>a roundabout: a ring any number of roads join at any angle.
 * </ul>
 *
 * The straights in between are the kit's straight piece every {@value #PIECE_LENGTH} m, with a short piece made to fit
 * whatever is left, crosswalks across the roads at each junction, and sidewalks along both sides with the corners of
 * square junctions filled in.
 *
 * <p>A country lane's pieces, and those of a junction only lanes meet at, have their own ids under {@value #LANE}
 * ({@code road:lane/straight}, {@code road:lane/hub/0,150}), so a kit can lay lanes as something else — a dirt track —
 * and one that doesn't lays them as roads ({@link #withoutLane}). Lanes get no crosswalks or sidewalks.
 *
 * <p>Pieces sit flush with the ground (sunk by their thickness), so roads want level ground: {@link #level} grades
 * the ground under them first. Where a road, path or footpath spline lies over a road, the spline takes its place: the
 * road is laid as though cut back to the spline's edge ({@link #underSplines}).
 */
public final class RoadBuilder {

    public static final String PREFIX = "road:";
    public static final String STRAIGHT = "road:straight";
    public static final String CORNER = "road:corner";
    public static final String END = "road:end";
    public static final String CROSSWALK = "road:crosswalk";
    public static final String SIDEWALK = "road:sidewalk";
    public static final String CORNER_TILE = "road:corner_tile";
    public static final String ROUNDABOUT = "road:roundabout";
    public static final String HUB = "road:hub/";
    public static final String FILL = "road:fill/";
    /** Put in front of a piece's role, after {@value #PREFIX}, for a country lane's piece: {@code road:lane/end}. */
    public static final String LANE = "road:lane/";

    /** How long a straight piece is, and how wide a road. */
    public static final float PIECE_LENGTH = 8f;
    public static final float HALF_WIDTH = 4f;
    /** A sidewalk tile's side. */
    public static final float TILE = 4f;
    public static final float ROUNDABOUT_RADIUS = 12f;
    /** Pieces are sunk this far, so the road's surface is level with the ground beside it and with the player's feet. */
    public static final float SINK = 0.12f;
    /** How far off square two roads can meet and still count as square, in degrees. */
    private static final float SQUARE_TOLERANCE = 1.5f;

    private RoadBuilder() {
    }

    /** Whether a prefab id is one of the road pieces this lays (and so replaces on every rebuild). */
    public static boolean isRoadPiece(String prefab) {
        return prefab != null && prefab.startsWith(PREFIX);
    }

    /** Whether a road piece's id is a country lane's. */
    public static boolean isLane(String prefab) {
        return prefab.startsWith(LANE);
    }

    /** A lane piece's id as the road piece it stands for ({@code road:lane/end} is {@code road:end}); others as given. */
    public static String withoutLane(String prefab) {
        return isLane(prefab) ? PREFIX + prefab.substring(LANE.length()) : prefab;
    }

    /** A road piece's id, as a country lane's when {@code lane}. */
    static String lane(String prefab, boolean lane) {
        return lane && prefab != null && !isLane(prefab) ? LANE + prefab.substring(PREFIX.length()) : prefab;
    }

    /** How finely a road is searched for the splines lying over it. */
    private static final float COVER_STEP = 0.25f;
    /** What is left of a road beside a spline lying over it shorter than this is left out too. */
    private static final float SHORTEST_LEFT = 1f;

    /**
     * The network as it is laid where splines lie over it: wherever a road, path or footpath spline covers a road it
     * takes its place — that stretch is left out and the road either side simply stops at the spline's edge, open — while
     * the network itself stays as drawn, so taking the spline away brings the road back. A road crossing a spline is cut
     * where its square end clears the spline's edge; one running along under it goes wherever the two overlap at all.
     */
    public static RoadNetwork underSplines(RoadNetwork network, List<MapSpline> splines, float step) {
        List<SplinePath> paths = new ArrayList<>();
        List<Float> covers = new ArrayList<>();
        List<float[]> boxes = new ArrayList<>();
        for (MapSpline spline : splines) {
            if (!spline.kind.covers() || !spline.drawable()) {
                continue;
            }
            SplinePath path = SplinePath.of(spline, step);
            if (path == null) {
                continue;
            }
            float cover = spline.kind.flat() ? spline.halfReach() : spline.width * 0.5f;
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int k = 0; k < path.size(); k++) {
                box[0] = Math.min(box[0], path.x(k) - cover);
                box[1] = Math.min(box[1], path.z(k) - cover);
                box[2] = Math.max(box[2], path.x(k) + cover);
                box[3] = Math.max(box[3], path.z(k) + cover);
            }
            paths.add(path);
            covers.add(cover);
            boxes.add(box);
        }
        if (paths.isEmpty() || network.edges().isEmpty()) {
            return network;
        }
        RoadNetwork out = network.copy();
        for (RoadNetwork.Edge edge : new ArrayList<>(out.edges())) {
            RoadNetwork.Node a = out.node(edge.a());
            RoadNetwork.Node b = out.node(edge.b());
            float length = (float) Math.hypot(b.x - a.x, b.z - a.z);
            if (length < 1e-3f) {
                continue;
            }
            float fx = (b.x - a.x) / length;
            float fz = (b.z - a.z) / length;
            float reach = HALF_WIDTH + (network.sidewalks() && !edge.lane() ? TILE : 0f);
            List<float[]> under = new ArrayList<>();
            for (int k = 0; k < paths.size(); k++) {
                float[] box = boxes.get(k);
                if (Math.max(a.x, b.x) + reach < box[0] || Math.min(a.x, b.x) - reach > box[2]
                        || Math.max(a.z, b.z) + reach < box[1] || Math.min(a.z, b.z) - reach > box[3]) {
                    continue;
                }
                under.addAll(stretchesUnder(a.x, a.z, fx, fz, length, reach, paths.get(k), covers.get(k)));
            }
            if (under.isEmpty()) {
                continue;
            }
            under.sort((p, q) -> Float.compare(p[0], q[0]));
            // What is left between the stretches under splines, each a road of its own ending open where it stops.
            out.disconnect(edge);
            float cursor = 0f;
            List<float[]> left = new ArrayList<>();
            for (float[] stretch : under) {
                if (stretch[0] > cursor) {
                    left.add(new float[]{cursor, stretch[0]});
                }
                cursor = Math.max(cursor, stretch[1]);
            }
            if (cursor < length) {
                left.add(new float[]{cursor, length});
            }
            for (float[] keep : left) {
                if (keep[1] - keep[0] < SHORTEST_LEFT) {
                    continue;
                }
                RoadNetwork.Node from = keep[0] < 1e-3f ? a : openEnd(out, a, fx, fz, keep[0]);
                RoadNetwork.Node to = keep[1] > length - 1e-3f ? b : openEnd(out, a, fx, fz, keep[1]);
                out.connect(from.id, to.id, edge.lane());
            }
        }
        out.removeLoose();
        return out;
    }

    private static RoadNetwork.Node openEnd(RoadNetwork network, RoadNetwork.Node a, float fx, float fz, float along) {
        RoadNetwork.Node node = network.addNode(a.x + fx * along, a.z + fz * along);
        node.capped = false;
        return node;
    }

    /**
     * The stretches of a straight road, as {from, to} metres from its start, a spline lies over: where the road's middle
     * is nearer the spline's line than the spline reaches plus as much of the road's own half width as lies along the
     * spline, so a crossing road stops square at the spline's edge and one alongside goes wherever they overlap.
     */
    private static List<float[]> stretchesUnder(float ax, float az, float fx, float fz, float length, float reach,
                                                SplinePath path, float cover) {
        int steps = Math.max(2, (int) Math.ceil(length / COVER_STEP));
        boolean[] in = new boolean[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float along = length * i / steps;
            in[i] = underSpline(ax + fx * along, az + fz * along, fx, fz, reach, path, cover);
        }
        List<float[]> out = new ArrayList<>();
        int i = 0;
        while (i <= steps) {
            if (!in[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 <= steps && in[j + 1]) {
                j++;
            }
            float from = i == 0 ? 0f : edgeOf(ax, az, fx, fz, reach, path, cover, length * (i - 1) / steps, length * i / steps);
            float to = j == steps ? length : edgeOf(ax, az, fx, fz, reach, path, cover, length * (j + 1) / steps, length * j / steps);
            out.add(new float[]{from, to});
            i = j + 1;
        }
        return out;
    }

    private static float edgeOf(float ax, float az, float fx, float fz, float reach, SplinePath path, float cover,
                                float out, float in) {
        for (int k = 0; k < 14; k++) {
            float middle = (out + in) * 0.5f;
            if (underSpline(ax + fx * middle, az + fz * middle, fx, fz, reach, path, cover)) {
                in = middle;
            } else {
                out = middle;
            }
        }
        return in;
    }

    private static boolean underSpline(float x, float z, float fx, float fz, float reach, SplinePath path, float cover) {
        SplinePath.Hit hit = path.nearest(x, z);
        if (hit == null) {
            return false;
        }
        float[] direction = path.directionAt(Math.clamp(hit.along(), path.start(), Math.max(path.start(), path.end() - 0.01f)));
        float alongIt = Math.abs(fx * direction[0] + fz * direction[1]);
        if (hit.distance() > cover + reach * alongIt) {
            return false;
        }
        if (!path.closed()) {
            if (hit.along() <= path.start() + 1e-3f) {
                float[] at = path.pointAt(path.start());
                return (x - at[0]) * direction[0] + (z - at[1]) * direction[1] >= -1e-3f;
            }
            if (hit.along() >= path.end() - 1e-3f) {
                float[] at = path.pointAt(path.end());
                return (x - at[0]) * direction[0] + (z - at[1]) * direction[1] <= 1e-3f;
            }
        }
        return true;
    }

    /**
     * Takes the map's old road pieces up and lays its network again, each piece tilted to the slope of the ground
     * under it (a road climbing a hill climbs with it) and sat just on the highest of that ground, so no grass shows
     * through. Returns how many pieces were laid.
     */
    public static int rebuild(WorldMap map) {
        map.objects().removeIf(object -> isRoadPiece(object.prefab));
        List<MapObject> pieces = pieces(underSplines(map.roads(), map.splines(), Math.min(SplineMeshes.STEP, map.cellSize())));
        for (MapObject piece : pieces) {
            tilt(map, piece);
            float above = highestAbovePlane(map, piece);
            piece.offsetY = above - SINK;
        }
        map.objects().addAll(pieces);
        return pieces.size();
    }

    /**
     * Tilts a piece to the ground under it: the plane that best fits the ground over its whole footprint (least
     * squares), so a crease where two roads' grades meet tilts it no more than it must.
     */
    static void tilt(WorldMap map, MapObject piece) {
        float[] half = footprint(piece.prefab);
        float cos = (float) Math.cos(piece.yaw);
        float sin = (float) Math.sin(piece.yaw);
        float step = Math.max(0.5f, map.cellSize() * 0.5f);
        double su = 0, sv = 0, suu = 0, svv = 0, sh = 0, suh = 0, svh = 0;
        int n = 0;
        for (float u = -half[0]; u <= half[0] + 1e-3f; u += step) {
            for (float v = -half[1]; v <= half[1] + 1e-3f; v += step) {
                if (half[2] > 0f && u * u + v * v > half[2] * half[2]) {
                    continue;
                }
                float h = groundAt(map, piece, cos, sin, u, v);
                su += u;
                sv += v;
                suu += u * u;
                svv += v * v;
                sh += h;
                suh += u * h;
                svh += v * h;
                n++;
            }
        }
        // The samples lie on a grid round the middle, so each slope is fitted on its own.
        double alongX = (suu - su * su / n) < 1e-6 ? 0 : (suh - su * sh / n) / (suu - su * su / n);
        double alongZ = (svv - sv * sv / n) < 1e-6 ? 0 : (svh - sv * sh / n) / (svv - sv * sv / n);
        piece.pitch = (float) Math.atan(alongZ);
        piece.roll = (float) Math.atan(alongX);
    }

    /** The ground at a point in a piece's own axes, turned by its yaw as the renderer turns them. */
    private static float groundAt(WorldMap map, MapObject piece, float cos, float sin, float u, float v) {
        float x = Math.clamp(piece.x + u * cos + v * sin, 0f, map.worldWidth());
        float z = Math.clamp(piece.z - u * sin + v * cos, 0f, map.worldDepth());
        return map.heightAt(x, z);
    }

    /**
     * How far above the plane a tilted piece lies in (through the ground at its middle) the ground anywhere under it
     * reaches; 0 or more, so the piece is raised just enough to cover it.
     */
    static float highestAbovePlane(WorldMap map, MapObject piece) {
        float[] half = footprint(piece.prefab);
        float cos = (float) Math.cos(piece.yaw);
        float sin = (float) Math.sin(piece.yaw);
        float slopeZ = (float) Math.tan(piece.pitch);
        float slopeX = (float) Math.tan(piece.roll);
        float middle = map.heightAt(piece.x, piece.z);
        float highest = 0f;
        float step = Math.max(0.5f, map.cellSize() * 0.5f);
        for (float u = -half[0]; u <= half[0] + 1e-3f; u += step) {
            for (float v = -half[1]; v <= half[1] + 1e-3f; v += step) {
                if (half[2] > 0f && u * u + v * v > half[2] * half[2]) {
                    continue;
                }
                float x = piece.x + u * cos + v * sin;
                float z = piece.z - u * sin + v * cos;
                if (map.contains(x, z)) {
                    highest = Math.max(highest, map.heightAt(x, z) - (middle + u * slopeX + v * slopeZ));
                }
            }
        }
        return highest;
    }

    /** How far a piece reaches from its middle: {half across its x, half along its z, radius or 0 for a rectangle}. */
    static float[] footprint(String prefab) {
        prefab = withoutLane(prefab);
        if (prefab.equals(SIDEWALK) || prefab.equals(CORNER_TILE)) {
            return new float[]{TILE * 0.5f, TILE * 0.5f, 0f};
        }
        if (prefab.equals(ROUNDABOUT)) {
            return new float[]{ROUNDABOUT_RADIUS, ROUNDABOUT_RADIUS, ROUNDABOUT_RADIUS};
        }
        if (prefab.startsWith(FILL)) {
            try {
                return new float[]{HALF_WIDTH, Float.parseFloat(prefab.substring(FILL.length())) * 0.5f, 0f};
            } catch (NumberFormatException e) {
                return new float[]{HALF_WIDTH, HALF_WIDTH, 0f};
            }
        }
        if (prefab.startsWith(HUB)) {
            String[] parts = prefab.substring(HUB.length()).split(",");
            int[] relative = new int[parts.length];
            for (int k = 0; k < parts.length; k++) {
                relative[k] = Integer.parseInt(parts[k].trim());
            }
            float far = 0f;
            for (float reach : reaches(relative)) {
                far = Math.max(far, (float) Math.hypot(reach, HALF_WIDTH));
            }
            return new float[]{far, far, far};
        }
        return new float[]{HALF_WIDTH, HALF_WIDTH, 0f};
    }

    // --- Junctions --------------------------------------------------------------------------------------

    /** A road leaving a junction: which way (as a yaw, radians), and how far from the junction its straights start. */
    record Arm(RoadNetwork.Edge edge, float yaw, float reach) {
    }

    /** What a junction is laid as. */
    record Junction(String prefab, float yaw, List<Arm> arms, boolean square) {
        Arm arm(RoadNetwork.Edge edge) {
            for (Arm arm : arms) {
                if (arm.edge().equals(edge)) {
                    return arm;
                }
            }
            return null;
        }
    }

    /** The yaw that turns a piece's +z toward a direction. */
    public static float yawOf(float dx, float dz) {
        return (float) Math.atan2(dx, dz);
    }

    static Junction junction(RoadNetwork network, RoadNetwork.Node node) {
        Junction junction = shape(network, node);
        // A junction is laid as road where any street meets it, and as lane where only lanes do.
        boolean lane = !node.roundabout && !junction.arms().isEmpty();
        for (Arm arm : junction.arms()) {
            lane &= arm.edge().lane();
        }
        return lane ? new Junction(lane(junction.prefab(), true), junction.yaw(), junction.arms(), junction.square()) : junction;
    }

    private static Junction shape(RoadNetwork network, RoadNetwork.Node node) {
        List<RoadNetwork.Edge> edges = network.edgesOf(node.id);
        List<float[]> directions = new ArrayList<>();
        for (RoadNetwork.Edge edge : edges) {
            RoadNetwork.Node other = network.node(edge.other(node.id));
            directions.add(new float[]{yawOf(other.x - node.x, other.z - node.z)});
        }
        // Sorted by direction, so neighbours in the list are neighbours round the junction.
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < edges.size(); k++) {
            order.add(k);
        }
        order.sort((p, q) -> Float.compare(directions.get(p)[0], directions.get(q)[0]));
        List<RoadNetwork.Edge> sortedEdges = new ArrayList<>();
        List<Float> yaws = new ArrayList<>();
        for (int k : order) {
            sortedEdges.add(edges.get(k));
            yaws.add(directions.get(k)[0]);
        }
        int degree = sortedEdges.size();

        if (node.roundabout) {
            float reach = (float) Math.sqrt(ROUNDABOUT_RADIUS * ROUNDABOUT_RADIUS - HALF_WIDTH * HALF_WIDTH);
            List<Arm> arms = new ArrayList<>();
            for (int k = 0; k < degree; k++) {
                arms.add(new Arm(sortedEdges.get(k), yaws.get(k), reach));
            }
            return new Junction(ROUNDABOUT, 0f, arms, false);
        }
        if (degree == 0) {
            return new Junction(null, 0f, List.of(), false);
        }
        if (degree == 1 && !node.capped) {
            // An open end: no piece, the straights running right up to it.
            return new Junction(null, yaws.getFirst(), List.of(new Arm(sortedEdges.getFirst(), yaws.getFirst(), 0f)), false);
        }
        if (degree == 1) {
            return new Junction(END, yaws.getFirst(), List.of(new Arm(sortedEdges.getFirst(), yaws.getFirst(), HALF_WIDTH)), true);
        }

        float first = yaws.getFirst();
        int[] relative = new int[degree];
        for (int k = 0; k < degree; k++) {
            relative[k] = Math.floorMod(Math.round((float) Math.toDegrees(yaws.get(k) - first)), 360);
        }
        boolean square = true;
        for (int degrees : relative) {
            float off = degrees % 90;
            square &= Math.min(off, 90 - off) <= SQUARE_TOLERANCE;
        }
        if (degree == 2 && square) {
            int between = relative[1];
            List<Arm> arms = List.of(new Arm(sortedEdges.get(0), yaws.get(0), HALF_WIDTH),
                    new Arm(sortedEdges.get(1), yaws.get(1), HALF_WIDTH));
            if (Math.abs(between - 180) <= SQUARE_TOLERANCE) {
                return new Junction(STRAIGHT, first, arms, true);
            }
            // The corner joins its -x and +z sides: +z goes to the road whose other road lies 90 degrees clockwise of it.
            float yaw = Math.abs(between - 270) <= SQUARE_TOLERANCE ? first : yaws.get(1);
            return new Junction(CORNER, yaw, arms, true);
        }
        // Squared up to exact right angles, so a T or a crossroads is always the same shape.
        if (square) {
            for (int k = 0; k < degree; k++) {
                relative[k] = Math.round(relative[k] / 90f) * 90 % 360;
            }
        }
        float[] reaches = reaches(relative);
        List<Arm> arms = new ArrayList<>();
        for (int k = 0; k < degree; k++) {
            arms.add(new Arm(sortedEdges.get(k), yaws.get(k), reaches[k]));
        }
        return new Junction(hubId(relative), first, arms, square);
    }

    /** {@code road:hub/0,90,180}: a junction piece for roads at these angles from the first, in degrees. */
    static String hubId(int[] relative) {
        StringBuilder id = new StringBuilder(HUB);
        for (int k = 0; k < relative.length; k++) {
            if (k > 0) {
                id.append(',');
            }
            id.append(relative[k]);
        }
        return id.toString();
    }

    /**
     * How far from a junction each road's straights must start so that it and its neighbours don't overlap: where
     * the near edges of two roads meeting at an angle cross, never less than half a road's width.
     */
    public static float[] reaches(int[] relative) {
        int n = relative.length;
        float[] reach = new float[n];
        for (int k = 0; k < n; k++) {
            int previous = Math.floorMod(relative[k] - relative[(k + n - 1) % n], 360);
            int next = Math.floorMod(relative[(k + 1) % n] - relative[k], 360);
            if (n == 1) {
                previous = next = 360;
            }
            float tightest = Math.min(previous == 0 ? 360 : previous, next == 0 ? 360 : next);
            float needed = tightest >= 180f ? 0f : HALF_WIDTH / (float) Math.tan(Math.toRadians(tightest) / 2.0);
            reach[k] = Math.max(HALF_WIDTH, needed + 0.25f * (tightest < 89f ? 1f : 0f));
        }
        return reach;
    }

    // --- Laying ---------------------------------------------------------------------------------------

    /** Every piece the network is laid as. */
    public static List<MapObject> pieces(RoadNetwork network) {
        List<MapObject> out = new ArrayList<>();
        java.util.Map<Integer, Junction> junctions = new java.util.HashMap<>();
        for (RoadNetwork.Node node : network.nodes()) {
            Junction junction = junction(network, node);
            junctions.put(node.id, junction);
            if (junction.prefab() != null) {
                out.add(piece(junction.prefab(), node.x, node.z, junction.yaw()));
            }
            if (network.sidewalks() && junction.square() && !node.roundabout && anyStreet(junction)) {
                squareSidewalks(out, node, junction);
            }
        }
        for (RoadNetwork.Edge edge : network.edges()) {
            RoadNetwork.Node a = network.node(edge.a());
            RoadNetwork.Node b = network.node(edge.b());
            Junction ja = junctions.get(a.id);
            Junction jb = junctions.get(b.id);
            Arm armA = ja.arm(edge);
            Arm armB = jb.arm(edge);
            if (armA == null || armB == null) {
                continue;
            }
            stretch(out, network, edge, a, b, ja, jb, armA.reach(), armB.reach());
        }
        return out;
    }

    /** Whether a street (not a country lane) leaves a junction, so it wants sidewalks round it. */
    private static boolean anyStreet(Junction junction) {
        for (Arm arm : junction.arms()) {
            if (!arm.edge().lane()) {
                return true;
            }
        }
        return false;
    }

    private static MapObject piece(String prefab, float x, float z, float yaw) {
        MapObject object = new MapObject(prefab, x, z, yaw, 1f);
        object.offsetY = -SINK;
        return object;
    }

    /** The straight road between two junctions, with its crosswalks and sidewalks. */
    private static void stretch(List<MapObject> out, RoadNetwork network, RoadNetwork.Edge edge, RoadNetwork.Node a, RoadNetwork.Node b,
                                Junction ja, Junction jb, float reachA, float reachB) {
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float length = (float) Math.hypot(dx, dz);
        if (length < 1e-3f) {
            return;
        }
        float fx = dx / length;
        float fz = dz / length;
        float yaw = yawOf(fx, fz);
        float start = reachA;
        float end = length - reachB;
        float middle = end - start;
        if (middle > 0.05f) {
            int full = (int) Math.floor(middle / PIECE_LENGTH + 1e-3f);
            float rest = middle - full * PIECE_LENGTH;
            boolean crossA = crosswalks(network, a);
            boolean crossB = crosswalks(network, b);
            for (int k = 0; k < full; k++) {
                float along = start + PIECE_LENGTH * (k + 0.5f);
                boolean crosswalk = !edge.lane() && (k == 0 && crossA || k == full - 1 && crossB);
                out.add(piece(lane(crosswalk ? CROSSWALK : STRAIGHT, edge.lane()), a.x + fx * along, a.z + fz * along, yaw));
            }
            if (rest > 0.05f) {
                float along = end - rest * 0.5f;
                out.add(piece(lane(fillId(rest), edge.lane()), a.x + fx * along, a.z + fz * along, yaw));
            }
        }
        if (network.sidewalks() && !edge.lane()) {
            float sideStart = ja.square() ? 2f * HALF_WIDTH : reachA == 0f ? 0f : reachA + HALF_WIDTH;
            float sideEnd = length - (jb.square() ? 2f * HALF_WIDTH : reachB == 0f ? 0f : reachB + HALF_WIDTH);
            int tiles = (int) Math.floor((sideEnd - sideStart) / TILE + 1e-3f);
            for (int k = 0; k < tiles; k++) {
                float along = sideStart + TILE * (k + 0.5f);
                for (int side = -1; side <= 1; side += 2) {
                    // To the right of the road (+x of its yaw) and the left, each tile's curb facing the road.
                    float rx = fz * side;
                    float rz = -fx * side;
                    float lateral = HALF_WIDTH + TILE * 0.5f;
                    out.add(piece(SIDEWALK, a.x + fx * along + rx * lateral, a.z + fz * along + rz * lateral,
                            yawOf(-rx, -rz)));
                }
            }
        }
    }

    private static boolean crosswalks(RoadNetwork network, RoadNetwork.Node node) {
        return node.crosswalks && (node.roundabout || network.degree(node.id) >= 3);
    }

    /** {@code road:fill/3.5}: a straight cut to a length, to the tenth of a metre. */
    static String fillId(float length) {
        return FILL + String.format(Locale.ROOT, "%.1f", Math.round(length * 10f) / 10f);
    }

    /**
     * Sidewalks round a square junction: along each side no road leaves from, facing in, and in each corner — a plain
     * tile where two roads turn the corner or none do, the sidewalk carried on where one does.
     */
    private static void squareSidewalks(List<MapObject> out, RoadNetwork.Node node, Junction junction) {
        float yaw = junction.yaw();
        // The junction's own axes: f along its +z, r along its +x.
        float fx = (float) Math.sin(yaw);
        float fz = (float) Math.cos(yaw);
        float rx = (float) Math.cos(yaw);
        float rz = (float) -Math.sin(yaw);
        boolean[] open = new boolean[4];   // +z, +x, -z, -x
        boolean[] street = new boolean[4]; // open, and a street rather than a country lane
        for (Arm arm : junction.arms()) {
            int side = Math.floorMod(Math.round((float) Math.toDegrees(arm.yaw() - yaw) / 90f), 4);
            open[side] = true;
            street[side] = !arm.edge().lane();
        }
        float near = HALF_WIDTH * 0.5f;              // 2: the middle of a tile beside the road
        float far = HALF_WIDTH + TILE * 0.5f;        // 6: the middle of the ring of tiles round the square
        // Each side: its outward direction in junction axes, and the two tiles along it.
        float[][] sides = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        for (int s = 0; s < 4; s++) {
            // A closed side is walked along by the sidewalks of the streets beside it, or by those round a dead end.
            int before = (s + 3) % 4;
            int after = (s + 1) % 4;
            boolean walked = street[before] || street[after] || !open[before] && !open[after];
            if (open[s] || !walked) {
                continue;
            }
            float ox = sides[s][0];
            float oz = sides[s][1];
            for (int t = -1; t <= 1; t += 2) {
                float lx = ox * far + oz * near * t;
                float lz = oz * far + ox * near * t;
                addLocal(out, SIDEWALK, node, rx, rz, fx, fz, lx, lz, -ox, -oz);
            }
        }
        // Corners, between side s and the next one round.
        for (int s = 0; s < 4; s++) {
            int n = (s + 1) % 4;
            float lx = (sides[s][0] + sides[n][0]) * far;
            float lz = (sides[s][1] + sides[n][1]) * far;
            if (open[s] == open[n]) {
                if (!open[s] || street[s] || street[n]) {
                    addLocal(out, CORNER_TILE, node, rx, rz, fx, fz, lx, lz, 0f, 1f);
                }
            } else if (street[open[s] ? s : n]) {
                // The sidewalk of the road leaving through the open side carries on here, facing that road.
                int closed = open[s] ? n : s;
                addLocal(out, SIDEWALK, node, rx, rz, fx, fz, lx, lz, -sides[closed][0], -sides[closed][1]);
            }
        }
    }

    /** Adds a piece at a point in a junction's axes, facing a direction in them. */
    private static void addLocal(List<MapObject> out, String prefab, RoadNetwork.Node node, float rx, float rz,
                                 float fx, float fz, float lx, float lz, float faceX, float faceZ) {
        float x = node.x + rx * lx + fx * lz;
        float z = node.z + rz * lx + fz * lz;
        float wx = rx * faceX + fx * faceZ;
        float wz = rz * faceX + fz * faceZ;
        out.add(piece(prefab, x, z, yawOf(wx, wz)));
    }

    // --- Ground -----------------------------------------------------------------------------------------

    /**
     * Grades the ground under every road: level across it, sloping evenly from one junction's height to the next along
     * it, and eased back into the land on either side. Returns the cells changed as {minX, minZ, maxX, maxZ}, or null.
     */
    public static int[] level(WorldMap map, RoadNetwork network) {
        if (network.isEmpty()) {
            return null;
        }
        java.util.Map<Integer, Float> heights = new java.util.HashMap<>();
        for (RoadNetwork.Node node : network.nodes()) {
            heights.put(node.id, map.heightAt(node.x, node.z));
        }
        float streetFlat = HALF_WIDTH + TILE + 0.5f;  // the road and its sidewalks
        float laneFlat = HALF_WIDTH + 1f;             // a country lane and its verges
        int columns = map.width() + 1;
        float cell = map.cellSize();
        float[] ground = map.heights();
        float[] target = new float[ground.length];
        float[] weight = new float[ground.length];
        float[] nearest = new float[ground.length];
        java.util.Arrays.fill(nearest, Float.MAX_VALUE);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = -1, maxZ = -1;
        for (RoadNetwork.Node node : network.nodes()) {
            float radius = (node.roundabout ? ROUNDABOUT_RADIUS : HALF_WIDTH * 1.5f) + TILE;
            float[] slope = junctionSlope(network, node, heights);
            int[] box = cells(map, node.x - radius - 4f, node.z - radius - 4f, node.x + radius + 4f, node.z + radius + 4f);
            for (int j = box[1]; j <= box[3]; j++) {
                for (int i = box[0]; i <= box[2]; i++) {
                    float px = i * cell - node.x;
                    float pz = j * cell - node.z;
                    float d = (float) Math.hypot(px, pz);
                    // Just short of full, so a road through the junction decides the ground along itself: it starts
                    // at the junction's height anyway, and sloping away from there leaves no step where the
                    // junction's patch ends.
                    float w = 0.999f * ease(d, radius, radius + 4f);
                    float h = heights.get(node.id) + slope[0] * px + slope[1] * pz;
                    blendIn(i + j * columns, h, w, d, target, weight, nearest);
                }
            }
            minX = Math.min(minX, box[0]);
            minZ = Math.min(minZ, box[1]);
            maxX = Math.max(maxX, box[2]);
            maxZ = Math.max(maxZ, box[3]);
        }
        for (RoadNetwork.Edge edge : network.edges()) {
            RoadNetwork.Node a = network.node(edge.a());
            RoadNetwork.Node b = network.node(edge.b());
            float dx = b.x - a.x;
            float dz = b.z - a.z;
            float lengthSquared = dx * dx + dz * dz;
            if (lengthSquared < 1e-6f) {
                continue;
            }
            float flat = edge.lane() ? laneFlat : streetFlat;
            float blend = flat + 4f;                   // then back to the land
            int[] box = cells(map, Math.min(a.x, b.x) - blend, Math.min(a.z, b.z) - blend,
                    Math.max(a.x, b.x) + blend, Math.max(a.z, b.z) + blend);
            for (int j = box[1]; j <= box[3]; j++) {
                for (int i = box[0]; i <= box[2]; i++) {
                    float px = i * cell;
                    float pz = j * cell;
                    float t = Math.clamp(((px - a.x) * dx + (pz - a.z) * dz) / lengthSquared, 0f, 1f);
                    float d = (float) Math.hypot(px - (a.x + dx * t), pz - (a.z + dz * t));
                    float w = ease(d, flat, blend);
                    float h = heights.get(a.id) + (heights.get(b.id) - heights.get(a.id)) * t;
                    blendIn(i + j * columns, h, w, d, target, weight, nearest);
                }
            }
            minX = Math.min(minX, box[0]);
            minZ = Math.min(minZ, box[1]);
            maxX = Math.max(maxX, box[2]);
            maxZ = Math.max(maxZ, box[3]);
        }
        for (int k = 0; k < ground.length; k++) {
            if (weight[k] > 0f) {
                ground[k] += (target[k] - ground[k]) * weight[k];
            }
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    /**
     * How the ground round a junction slopes, as its rise per metre along x and along z: level where three or more
     * roads meet, or at a roundabout; where a road just runs through or bends on a hillside, climbing along the road
     * as its two halves do, so the junction's piece lies on the slope with them rather than on a shelf.
     */
    static float[] junctionSlope(RoadNetwork network, RoadNetwork.Node node, java.util.Map<Integer, Float> heights) {
        List<RoadNetwork.Edge> edges = network.edgesOf(node.id);
        if (node.roundabout || edges.size() != 2) {
            return new float[]{0f, 0f};
        }
        float[][] d = new float[2][];
        float[] s = new float[2];
        for (int k = 0; k < 2; k++) {
            RoadNetwork.Node other = network.node(edges.get(k).other(node.id));
            float dx = other.x - node.x;
            float dz = other.z - node.z;
            float length = (float) Math.hypot(dx, dz);
            if (length < 1e-3f) {
                return new float[]{0f, 0f};
            }
            d[k] = new float[]{dx / length, dz / length};
            s[k] = (heights.get(other.id) - heights.get(node.id)) / length;
        }
        // Along the line the road runs through the bend (from the second road's end to the first's), by the climb
        // the two halves make on average; level across it.
        float tx = d[0][0] - d[1][0];
        float tz = d[0][1] - d[1][1];
        float length = (float) Math.hypot(tx, tz);
        if (length < 1e-3f) {
            return new float[]{0f, 0f};
        }
        tx /= length;
        tz /= length;
        // How far each road's run along that line falls short of its own length, round the bend; kept from blowing up
        // in a hairpin.
        float along = Math.max(0.5f, d[0][0] * tx + d[0][1] * tz);
        float climb = (s[0] - s[1]) * 0.5f / along;
        return new float[]{tx * climb, tz * climb};
    }

    /**
     * Keeps the strongest pull on a ground point: the road it is most under decides its height, and of two roads it
     * is fully under (where they meet), the one whose middle it is nearer — so each road's middle follows its own grade.
     */
    private static void blendIn(int index, float height, float w, float distance, float[] target, float[] weight,
                                float[] nearest) {
        if (w > weight[index] || w == weight[index] && distance < nearest[index]) {
            weight[index] = w;
            target[index] = height;
            nearest[index] = distance;
        }
    }

    /** 1 within {@code inner}, easing to 0 at {@code outer}. */
    private static float ease(float distance, float inner, float outer) {
        if (distance <= inner) {
            return 1f;
        }
        if (distance >= outer) {
            return 0f;
        }
        float t = 1f - (distance - inner) / (outer - inner);
        return t * t * (3f - 2f * t);
    }

    private static int[] cells(WorldMap map, float x0, float z0, float x1, float z1) {
        float cell = map.cellSize();
        return new int[]{
                Math.max(0, (int) Math.floor(x0 / cell)), Math.max(0, (int) Math.floor(z0 / cell)),
                Math.min(map.width(), (int) Math.ceil(x1 / cell)), Math.min(map.depth(), (int) Math.ceil(z1 / cell))};
    }
}
