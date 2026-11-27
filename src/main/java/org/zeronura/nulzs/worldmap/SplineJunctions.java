package org.zeronura.nulzs.worldmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Where splines meet, and what that takes out of each — so they join as one thing instead of piling up on one another —
 * and where a spline is laid over another, what it takes the place of.
 *
 * <h2>Junctions</h2>
 * A junction is any point several splines that {@linkplain MapSpline.Kind#joins join} start at, end at or run through,
 * each line leaving it an arm.
 * <ul>
 *   <li>Two arms join in a mitre: both are cut on the line halfway between them, so a road drawn as two splines meeting
 *       end to end, at any angle, looks like one road turning a corner, its picture running on round it.
 *   <li>Three or more roads or paths make a junction. The two arms most nearly in line (the wider, then the newer, first)
 *       are the road running through; every other arm is a road leading off it, cut back to the through road's edge on
 *       a slant that lies along that edge, whatever the angle. Where they meet, the through road is laid as a crossing
 *       piece: a road whose picture belongs to a set ({@code road_straight_ew} beside {@code road_cross} and
 *       {@code road_t_nes}) meeting square gets the set's own crossroads or T, every arm's picture lined up to it; any
 *       other gets the plain middle of its own picture — no lines, no verge — across the mouth of each road leading off,
 *       so nothing runs across where they meet. The through road's footpath on that side opens to let the road in, its
 *       railing or wall opens as wide as the road and its footpaths, and the side road's own footpaths and edges start
 *       at the through road's outer edge, so the corners close up square.
 *   <li>Footpaths meet the same way, without the crossing piece.
 *   <li>Walls meeting are one wall: each is cut on the line between the corners its faces make with its neighbours',
 *       a cap covers the middle at one height, their tops are eased to that height near it, and the pictures down their
 *       faces are slid (and, between two junctions, stretched a little) so each runs on round every corner into the
 *       next — nothing shows where one ends and the next begins.
 *   <li>Railings meeting share one post.
 * </ul>
 *
 * <h2>Laid over</h2>
 * A road, path or footpath takes the place of whatever was drawn before it where it lies over it — another road, a
 * footpath, a wall, a railing — unless the two join there: the earlier one is cut back to the later one's edge, on the
 * slant of that edge. (The road network's roads make way for splines the same way: {@link RoadBuilder#rebuild}.)
 *
 * <p>Plain data: it reads the splines and their lines and says what to leave out and what to add; {@link SplineMeshes}
 * lays the rest.
 */
public final class SplineJunctions {

    /** The parts of a spline laid separately, each cut back on its own. */
    public enum Part {
        SURFACE, LEFT_FOOTPATH, RIGHT_FOOTPATH, LEFT_EDGE, RIGHT_EDGE
    }

    /**
     * How a piece of a part ends where something was cut out beside it.
     *
     * @param frame  the slant it is cut on, or null for square (or as the line already is there)
     * @param joined whether it meets other splines there, joined to them: it shows no end face and puts up no post
     * @param ground the ground height at the junction its top is eased to, so all its arms meet level; NaN for none
     */
    public record End(SplinePath.Frame frame, boolean joined, float ground) {
        public static final End OPEN = new End(null, false, Float.NaN);

        static End joined(SplinePath.Frame frame, float ground) {
            return new End(frame, true, ground);
        }

        static End cut(SplinePath.Frame frame) {
            return new End(frame, false, Float.NaN);
        }

        public boolean pinned() {
            return !Float.isNaN(ground);
        }
    }

    /**
     * A stretch along a spline a part is not laid on, from {@code lo} to {@code hi} metres along it, and how the pieces
     * either side of it end. A cut of no length only says how they end: a mitre.
     */
    public record Cut(float lo, End loEnd, float hi, End hiEnd) {
    }

    /** Where a surface's picture is along a piece of it: at {@code along * stretch + shift} metres instead of at along. */
    public record Run(float stretch, float shift) {
        public static final Run NATURAL = new Run(1f, 0f);

        public float at(float along) {
            return along * stretch + shift;
        }
    }

    /** One piece's runs: its strip (a road's surface, a footpath's top) and a wall's left and right faces. */
    public record Runs(Run strip, Run left, Run right) {
        public static final Runs NATURAL = new Runs(Run.NATURAL, Run.NATURAL, Run.NATURAL);
    }

    /** A stretch of a part as it is laid: its line, how each end ends, and how its pictures run. */
    public record Piece(SplinePath path, End start, End end, Runs runs) {
    }

    /** A stretch of a part's line, before it is cut from the line. */
    record Range(float from, End fromEnd, float to, End toEnd) {
    }

    /** Something a junction adds, laid with the spline it is given to. */
    public sealed interface Patch permits Cap, Post, Crossing, Fillet {
    }

    public record Corner(float x, float z) {
    }

    /**
     * The top of walls' meeting place: a flat face over the corners, {@code height} above {@code ground}, and where a
     * lower wall meets it a riser down to that wall's top. Side k runs from corner k to the next.
     */
    public record Cap(List<Corner> corners, float ground, float height, List<Float> sideHeights) implements Patch {
    }

    /** The one post railings meeting share. */
    public record Post(float x, float z, float dirX, float dirZ, float ground, float height, float size) implements Patch {
    }

    /**
     * The through road where others lead off it, from {@code from} to {@code to} along the spline it is given to.
     * Laid in the picture set's own crossroads ({@code cross}) or T when {@code family} and there is one: the picture's
     * across ({@code x}) is {@code 0.5 + lateral * offset / (2 * halfAcross)}, its along ({@code y})
     * {@code 0.5 + longitudinal * (along - at) / (2 * halfAlong)}, a T's closed side away from the road leading off.
     * Otherwise in the road's own picture, running on as {@code run} says, with its plain middle taking the place of
     * everything on the side the other roads lead off ({@code bandSide} +1 right, -1 left, 0 both).
     */
    public record Crossing(float from, SplinePath.Frame fromFrame, float to, SplinePath.Frame toFrame, boolean family,
                           boolean cross, float at, float halfAlong, float halfAcross, int longitudinal, int lateral,
                           int bandSide, Run run) implements Patch {
    }

    /**
     * A corner rounded where a road leads off a through road laid with its own picture: each road's verge on that side
     * (a path's grass, a road's edge line) bends round the corner, halfway each, into the other's, and the plain picture
     * fills between the bend and the roads, so the corner is one curve rather than two verges cut off square. Given to
     * the newer of the two roads, laid just above both. {@link SplineMeshes} lays it once it knows where the picture's
     * verges are; the bend's size follows from them.
     *
     * @param x       the junction
     * @param z       the junction
     * @param through the through road's side of the corner
     * @param branch  the side of the road leading off
     * @param family  whether the junction is the shape of its picture set's crossroads ({@code cross}) or T, as the
     *                {@link Crossing} it rounds: where the set has that piece, the piece's own corners stand instead
     */
    public record Fillet(float x, float z, Bend through, Bend branch, boolean family, boolean cross) implements Patch {
    }

    /**
     * One road's side of a {@link Fillet}: its line leaving the junction, taken as straight; the way across it to the
     * corner; and how its picture lies along it.
     *
     * @param dirX   the way it leaves the junction, a unit vector
     * @param sideX  the way across it toward the corner, a unit vector
     * @param half   half its width
     * @param along  how far along its spline the junction is
     * @param sense  +1 where its spline runs on away from the junction, -1 where it runs back
     * @param right  whether the corner is on its spline's right
     * @param clear  how far out from the junction, along its middle, its own verge on the corner's side is missing —
     *               crossed by the other road, or cut back to its edge — so the bend runs on at least that far
     * @param clearX the line it is missing to lies along this, a unit vector
     * @param run    how its picture runs there
     */
    public record Bend(float dirX, float dirZ, float sideX, float sideZ, float half, float along, int sense, boolean right,
                       float clear, float clearX, float clearZ, Run run) {

        Bend withRun(Run run) {
            return new Bend(dirX, dirZ, sideX, sideZ, half, along, sense, right, clear, clearX, clearZ, run);
        }
    }

    /**
     * What junctions and later splines do to one spline: what they take out of each of its parts, how its surface's
     * pictures run, what is added to it, and its layer — how many splines lie under it, lifting its flat surfaces that
     * many hairs above theirs. Equal to another exactly when it is laid the same.
     */
    public record Trims(Map<Part, List<Cut>> cuts, Map<Part, List<Runs>> runs, List<Patch> patches, int layer) {

        public static final Trims NONE = new Trims(Map.of(), Map.of(), List.of(), 0);

        public boolean isEmpty() {
            return cuts.isEmpty() && runs.isEmpty() && patches.isEmpty() && layer == 0;
        }

        /** The stretches of a line a part is laid along: the whole line where nothing is cut from that part. */
        public List<Piece> pieces(SplinePath path, Part part) {
            List<Cut> list = cuts.get(part);
            List<Runs> runList = runs.get(part);
            if (list == null || list.isEmpty()) {
                return List.of(new Piece(path, End.OPEN, End.OPEN, runsAt(runList, 0)));
            }
            List<Range> ranges = ranges(path, list);
            List<Piece> out = new ArrayList<>();
            for (int k = 0; k < ranges.size(); k++) {
                Range range = ranges.get(k);
                SplinePath piece = path.slice(range.from(), range.fromEnd().frame(), range.to(), range.toEnd().frame());
                if (piece != null) {
                    out.add(new Piece(piece, range.fromEnd(), range.toEnd(), runsAt(runList, k)));
                }
            }
            return out;
        }

        private static Runs runsAt(List<Runs> list, int index) {
            return list == null || index >= list.size() ? Runs.NATURAL : list.get(index);
        }
    }

    /** How near two splines' points must be to be one junction: a point put down on another sits right on it. */
    static final float SAME_POINT = 0.05f;
    /** A road leading off at less than this sine of its angle (about 14 degrees) is left as it is: too shallow to cut. */
    static final float SHALLOWEST = 0.25f;
    /** The shortest piece laid. */
    private static final float MIN_PIECE = 0.05f;
    /** How far out a junction's corner may lie, as a multiple of its widest arm's half width: very sharp ones stop short. */
    private static final float CORNER_REACH = 8f;
    /** A road leading off within this sine of square (about 8 degrees) can take its picture set's crossroads or T. */
    private static final float SQUARE_SINE = 0.14f;
    /** How far apart along a line it is tested for lying under a later spline. */
    private static final float COVER_STEP = 0.25f;
    /** No spline is lifted more than this many layers. */
    private static final int MAX_LAYER = 12;

    /**
     * The stretches of a line left between its cuts, each with how it ends; those shorter than {@link #MIN_PIECE} are
     * left out. {@link Trims#pieces} lays them, and the runs are worked out for them, so both agree piece by piece.
     */
    static List<Range> ranges(SplinePath path, List<Cut> list) {
        float length = path.end();
        if (list == null || list.isEmpty()) {
            return List.of(new Range(0f, End.OPEN, length, End.OPEN));
        }
        List<Cut> laid = new ArrayList<>();
        for (Cut cut : list) {
            if (path.closed() && cut.lo() < 0f) {
                laid.add(new Cut(cut.lo() + length, cut.loEnd(), length, End.OPEN));
                laid.add(new Cut(0f, End.OPEN, cut.hi(), cut.hiEnd()));
            } else if (path.closed() && cut.hi() > length) {
                laid.add(new Cut(cut.lo(), cut.loEnd(), length, End.OPEN));
                laid.add(new Cut(0f, End.OPEN, cut.hi() - length, cut.hiEnd()));
            } else {
                laid.add(new Cut(Math.clamp(cut.lo(), 0f, length), cut.loEnd(), Math.clamp(cut.hi(), 0f, length),
                        cut.hiEnd()));
            }
        }
        laid.sort(Comparator.comparingDouble(Cut::lo));
        List<Range> out = new ArrayList<>();
        float cursor = 0f;
        End cursorEnd = End.OPEN;
        for (Cut cut : laid) {
            if (cut.lo() > cursor + MIN_PIECE) {
                out.add(new Range(cursor, cursorEnd, cut.lo(), cut.loEnd()));
            }
            if (cut.hi() > cursor) {
                cursor = cut.hi();
                cursorEnd = cut.hiEnd();
            } else if (cut.hi() == cursor && !cut.hiEnd().equals(End.OPEN)) {
                cursorEnd = cut.hiEnd();
            }
        }
        if (cursor < length - MIN_PIECE) {
            out.add(new Range(cursor, cursorEnd, length, End.OPEN));
        }
        return out;
    }

    /**
     * One line leaving a junction.
     *
     * @param index which spline it is on the map, oldest first
     * @param along how far along its spline the junction is
     * @param sense +1 where the arm runs on along its spline from there, -1 where it runs back
     * @param bx    the way it leaves, as a unit vector: toward a point a couple of metres along it
     */
    private record Arm(MapSpline spline, int index, SplinePath path, float along, int sense, float bx, float bz) {

        /** The spline's own way to its right where the arm leaves the junction. */
        float[] splineRight() {
            float fx = bx * sense;
            float fz = bz * sense;
            return new float[]{-fz, fx};
        }

        float half() {
            return spline.width * 0.5f;
        }

        /** Which side of the spline is on the arm's right (+1) or left (-1), as its own right (+1) or left (-1). */
        int splineSide(int armSide) {
            return armSide * sense;
        }

        /** The width of the footpath on one side of the arm (+1 its right), or 0. */
        float footpath(int armSide) {
            int side = splineSide(armSide);
            boolean has = side > 0 ? spline.footpaths.right() : spline.footpaths.left();
            return has ? spline.footpathWidth : 0f;
        }

        boolean sameLine(Arm other) {
            return spline == other.spline && Math.abs(along - other.along) < SAME_POINT
                    || spline == other.spline && path.closed() && Math.abs(Math.abs(along - other.along) - path.end()) < SAME_POINT;
        }

        /** How far out along the arm a point (relative to the junction) lies. */
        float out(float[] point) {
            return point[0] * bx + point[1] * bz;
        }

        /** How far along its spline a point {@code t} out along the arm is. */
        float alongOut(float t) {
            return along + sense * t;
        }
    }

    private SplineJunctions() {
    }

    /**
     * What every junction on a map, and every spline laid over another, does to its splines; splines nothing touches
     * are left out.
     *
     * @param paths each spline's line, as it is laid ({@link SplineMeshes#STEP} apart), or null for one too short
     */
    public static Map<MapSpline, Trims> of(WorldMap map, Function<MapSpline, SplinePath> paths) {
        return new Layout(map, paths).run();
    }

    /** The arms leaving one of a spline's points: one at an open line's end, two anywhere else. */
    private static void addArms(List<Arm> arms, MapSpline spline, int index, SplinePath path, int pointIndex) {
        int distinct = distinctIndex(spline, pointIndex);
        if (distinct < 0 || distinct >= path.points()) {
            return;
        }
        for (Arm arm : arms) {
            if (arm.spline == spline && Math.abs(arm.along - path.pointAlong(distinct)) < SAME_POINT) {
                return; // the same point listed twice (a loop's last point on its first)
            }
        }
        float along = path.pointAlong(distinct);
        boolean first = distinct == 0;
        boolean last = !path.closed() && distinct == path.points() - 1;
        for (int sense : new int[]{1, -1}) {
            if (!path.closed() && (first && sense < 0 || last && sense > 0)) {
                continue;
            }
            float from = path.closed() && first && sense < 0 ? path.end() : along;
            float[] b = leaving(path, from, sense);
            if (b != null) {
                arms.add(new Arm(spline, index, path, from, sense, b[0], b[1]));
            }
        }
    }

    /** The way a line leaves a point {@code along} it, running on ({@code sense} +1) or back: toward 2 m away. */
    private static float[] leaving(SplinePath path, float along, int sense) {
        float reach = Math.min(2f, path.length() * 0.5f);
        float to = along + sense * reach;
        if (path.closed()) {
            to = ((to % path.end()) + path.end()) % path.end();
        }
        float[] here = path.pointAt(along);
        float[] there = path.pointAt(Math.clamp(to, 0f, path.end()));
        float dx = there[0] - here[0];
        float dz = there[1] - here[1];
        float length = (float) Math.hypot(dx, dz);
        return length < 1e-4f ? null : new float[]{dx / length, dz / length};
    }

    /** A point's index among those {@link SplinePath} keeps: any lying on the one before it are dropped. */
    private static int distinctIndex(MapSpline spline, int pointIndex) {
        List<MapSpline.Point> points = spline.points();
        int kept = -1;
        MapSpline.Point previous = null;
        for (int k = 0; k <= pointIndex && k < points.size(); k++) {
            MapSpline.Point point = points.get(k);
            if (previous == null || Math.hypot(point.x - previous.x, point.z - previous.z) > 0.05) {
                kept++;
                previous = point;
            }
        }
        if (spline.closed && kept > 0 && pointIndex == points.size() - 1) {
            MapSpline.Point head = points.getFirst();
            MapSpline.Point tail = points.get(pointIndex);
            if (Math.hypot(head.x - tail.x, head.z - tail.z) <= 0.05) {
                return 0;
            }
        }
        return kept;
    }

    /** A cut lying along (cx, cz), turned to point to a spline's right, or null when it runs along the spline. */
    private static SplinePath.Frame slant(float cx, float cz, float[] right) {
        float length = (float) Math.hypot(cx, cz);
        if (length < 1e-5f) {
            return null;
        }
        cx /= length;
        cz /= length;
        float dot = cx * right[0] + cz * right[1];
        if (Math.abs(dot) < 0.2f) {
            return null;
        }
        float sign = Math.signum(dot);
        return new SplinePath.Frame(cx * sign, cz * sign, 1f / Math.abs(dot));
    }

    /** The line two arms are mitred on: halfway between them, or square across where they run straight on. */
    private static float[] mitreLine(Arm a, Arm b) {
        float cx = a.bx + b.bx;
        float cz = a.bz + b.bz;
        float length = (float) Math.hypot(cx, cz);
        if (length < 1e-3f) {
            return new float[]{-a.bz, a.bx};
        }
        return new float[]{cx / length, cz / length};
    }

    /** The part of an arm cut away from its start: {@code distance} metres from the junction. */
    private static Cut from(Arm arm, float distance, End end) {
        return arm.sense > 0 ? new Cut(arm.along, End.OPEN, arm.along + distance, end)
                : new Cut(arm.along - distance, end, arm.along, End.OPEN);
    }

    /**
     * Where a line through the junction parallel to a branch, {@code offset} to the branch's right, crosses the through
     * road's line, in metres along it from the junction.
     */
    private static float gapAt(float offset, float cross) {
        return -offset / cross;
    }

    /**
     * The opening a branch makes in one through arm: the stretch of {@code gap} (metres along the through road from the
     * junction, toward the second arm) on this arm's side, as a cut along its spline, its far end slanted along the
     * branch; null when none of it is on this arm's side.
     */
    private static Cut opening(Arm arm, int towardD, float[] gap, SplinePath.Frame slant) {
        float u0 = gap[0] * towardD;
        float u1 = gap[1] * towardD;
        float near = Math.max(0f, Math.min(u0, u1));
        float far = Math.max(u0, u1);
        if (far <= 0f) {
            return null;
        }
        End nearEnd = End.cut(near > 0f ? slant : null);
        End farEnd = End.cut(slant);
        return arm.sense > 0 ? new Cut(arm.along + near, nearEnd, arm.along + far, farEnd)
                : new Cut(arm.along - far, farEnd, arm.along - near, nearEnd);
    }

    /**
     * Cuts that overlap made one, keeping how whichever reaches furthest out each way ends. Two mitres at the same place
     * — the two arms of a line running through it — become one, ending both ways.
     */
    static List<Cut> merge(List<Cut> list) {
        List<Cut> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingDouble(Cut::lo).thenComparingDouble(Cut::hi));
        List<Cut> out = new ArrayList<>();
        for (Cut cut : sorted) {
            if (!out.isEmpty()) {
                Cut last = out.getLast();
                boolean bothMitres = cut.lo() == cut.hi() && last.lo() == last.hi() && Math.abs(cut.lo() - last.lo()) < 1e-4f;
                if (bothMitres) {
                    out.set(out.size() - 1, new Cut(last.lo(), last.loEnd().equals(End.OPEN) ? cut.loEnd() : last.loEnd(),
                            last.hi(), last.hiEnd().equals(End.OPEN) ? cut.hiEnd() : last.hiEnd()));
                    continue;
                }
                boolean touching = cut.lo() < last.hi() || cut.lo() == last.hi() && (cut.hi() > cut.lo() || last.hi() > last.lo());
                if (touching && !(cut.lo() == cut.hi() || last.lo() == last.hi())) {
                    out.set(out.size() - 1, cut.hi() > last.hi() ? new Cut(last.lo(), last.loEnd(), cut.hi(), cut.hiEnd())
                            : last);
                    continue;
                }
            }
            out.add(cut);
        }
        return out;
    }

    /** A lookup of a map's splines' lines for {@link #of}, each worked out the first time it is asked for. */
    public static Function<MapSpline, SplinePath> pathsOf(WorldMap map) {
        Map<MapSpline, SplinePath> made = new HashMap<>();
        return spline -> made.computeIfAbsent(spline,
                s -> SplinePath.of(s, Math.min(SplineMeshes.STEP, map.cellSize())));
    }

    // --- Working it out -----------------------------------------------------------------------------------

    /**
     * One end of a surface piece, for pictures to run on across: the piece of {@code spline} that starts ({@code start})
     * or ends {@code at} metres along it, on a side (-1 a wall's left face, +1 its right, 0 a road's surface), its picture
     * read {@code eval} metres along.
     */
    private record FaceEnd(MapSpline spline, int side, float at, boolean start, float eval) {
    }

    /** Two pieces whose pictures run on into one another. */
    private record Link(FaceEnd a, FaceEnd b) {
    }

    /** A piece whose picture must start a fresh repeat (be whole, {@code target} 0) at one end. */
    private record Anchor(FaceEnd end, float target) {
    }

    /** A crossing piece waiting to learn how the piece of road beside it runs. */
    private record PendingCrossing(MapSpline spline, Crossing crossing, FaceEnd beside) {
    }

    private record PendingFillet(MapSpline spline, Fillet fillet, FaceEnd through, FaceEnd branch) {
    }

    private static final class Layout {
        final WorldMap map;
        final Function<MapSpline, SplinePath> paths;
        final List<MapSpline> splines;
        final Map<MapSpline, Integer> order = new IdentityHashMap<>();
        final Map<MapSpline, Map<Part, List<Cut>>> cuts = new IdentityHashMap<>();
        final Map<MapSpline, List<Patch>> patches = new IdentityHashMap<>();
        final List<Link> links = new ArrayList<>();
        final List<Anchor> anchors = new ArrayList<>();
        final List<PendingCrossing> crossings = new ArrayList<>();
        final List<PendingFillet> fillets = new ArrayList<>();
        /** For each spline, the splines it joins in junctions and how far along it each junction is. */
        final Map<MapSpline, Map<MapSpline, List<Float>>> joined = new IdentityHashMap<>();

        Layout(WorldMap map, Function<MapSpline, SplinePath> paths) {
            this.map = map;
            this.paths = paths;
            this.splines = map.splines();
        }

        SplinePath path(MapSpline spline) {
            return spline.drawable() ? paths.apply(spline) : null;
        }

        Map<MapSpline, Trims> run() {
            for (int s = 0; s < splines.size(); s++) {
                order.put(splines.get(s), s);
            }
            junctions();
            overlaps();
            Map<MapSpline, Map<Part, List<Cut>>> merged = new IdentityHashMap<>();
            cuts.forEach((spline, parts) -> {
                Map<Part, List<Cut>> one = new EnumMap<>(Part.class);
                parts.forEach((part, list) -> one.put(part, merge(list)));
                merged.put(spline, one);
            });
            Solver solver = new Solver(merged);
            Map<MapSpline, List<Runs>> runs = solver.solve();
            for (PendingCrossing pending : crossings) {
                Crossing c = pending.crossing();
                Run run = solver.runOf(pending.beside());
                patchesOf(pending.spline()).add(new Crossing(c.from(), c.fromFrame(), c.to(), c.toFrame(), c.family(),
                        c.cross(), c.at(), c.halfAlong(), c.halfAcross(), c.longitudinal(), c.lateral(), c.bandSide(), run));
            }
            for (PendingFillet pending : fillets) {
                Fillet f = pending.fillet();
                patchesOf(pending.spline()).add(new Fillet(f.x(), f.z(), f.through().withRun(solver.runOf(pending.through())),
                        f.branch().withRun(solver.runOf(pending.branch())), f.family(), f.cross()));
            }
            int[] layers = layers();
            Map<MapSpline, Trims> out = new IdentityHashMap<>();
            for (int s = 0; s < splines.size(); s++) {
                MapSpline spline = splines.get(s);
                List<Runs> surface = runs.get(spline);
                Trims trims = new Trims(merged.getOrDefault(spline, Map.of()),
                        surface == null ? Map.of() : Map.of(Part.SURFACE, surface),
                        List.copyOf(patches.getOrDefault(spline, List.of())), layers[s]);
                if (!trims.isEmpty()) {
                    out.put(spline, trims);
                }
            }
            return out;
        }

        void add(MapSpline spline, Part part, Cut cut) {
            cuts.computeIfAbsent(spline, s -> new EnumMap<>(Part.class)).computeIfAbsent(part, p -> new ArrayList<>()).add(cut);
        }

        List<Patch> patchesOf(MapSpline spline) {
            return patches.computeIfAbsent(spline, s -> new ArrayList<>());
        }

        float groundAt(float x, float z) {
            return map.heightAt(Math.clamp(x, 0f, map.worldWidth()), Math.clamp(z, 0f, map.worldDepth()));
        }

        // --- Junctions -------------------------------------------------------------------------------------

        void junctions() {
            // The points of every spline, by where they are, so the ones that share a place are found together.
            Map<Long, List<int[]>> places = new LinkedHashMap<>();
            for (int s = 0; s < splines.size(); s++) {
                List<MapSpline.Point> points = splines.get(s).points();
                for (int p = 0; p < points.size(); p++) {
                    MapSpline.Point point = points.get(p);
                    long key = ((long) Math.round(point.x / SAME_POINT) << 32) ^ (Math.round(point.z / SAME_POINT) & 0xFFFFFFFFL);
                    places.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{s, p});
                }
            }
            for (List<int[]> place : places.values()) {
                if (place.size() < 2) {
                    continue;
                }
                List<Arm> arms = new ArrayList<>();
                for (int[] at : place) {
                    MapSpline spline = splines.get(at[0]);
                    SplinePath path = path(spline);
                    if (path != null) {
                        addArms(arms, spline, at[0], path, at[1]);
                    }
                }
                MapSpline.Point point = splines.get(place.getFirst()[0]).points().get(place.getFirst()[1]);
                join(arms, point.x, point.z);
            }
        }

        /** Works out what the arms meeting at one place take out of their splines, and what they add. */
        void join(List<Arm> arms, float x, float z) {
            float ground = groundAt(x, z);
            List<Arm> roads = new ArrayList<>();
            List<Arm> footpaths = new ArrayList<>();
            List<Arm> walls = new ArrayList<>();
            List<Arm> railings = new ArrayList<>();
            for (Arm arm : arms) {
                switch (arm.spline.kind) {
                    case ROAD, PATH -> roads.add(arm);
                    case FOOTPATH -> footpaths.add(arm);
                    case WALL -> walls.add(arm);
                    case RAILING -> railings.add(arm);
                    case MODELS -> {
                    }
                }
            }
            if (roads.size() == 2) {
                mitre(roads.get(0), roads.get(1), true, ground, true);
            } else if (roads.size() >= 3) {
                roads(roads, ground, x, z);
            }
            if (footpaths.size() == 2) {
                mitre(footpaths.get(0), footpaths.get(1), false, ground, true);
            } else if (footpaths.size() >= 3) {
                footpaths(footpaths, ground);
            }
            if (walls.size() >= 2 && !oneLine(walls)) {
                walls(walls, x, z, ground);
            }
            if (railings.size() >= 2 && !oneLine(railings)) {
                railings(railings, x, z, ground);
            }
            for (List<Arm> group : List.of(roads, footpaths, walls, railings)) {
                for (Arm a : group) {
                    for (Arm b : group) {
                        if (a.spline != b.spline) {
                            joined.computeIfAbsent(a.spline, s -> new IdentityHashMap<>())
                                    .computeIfAbsent(b.spline, s -> new ArrayList<>()).add(a.along);
                        }
                    }
                }
            }
        }

        /** Two arms that are one line running through a point of its own: nothing to join. */
        static boolean oneLine(List<Arm> arms) {
            return arms.size() == 2 && arms.get(0).sameLine(arms.get(1));
        }

        /** The end of the surface piece an arm leaves the junction along, {@code t} out from it. */
        static FaceEnd stripEnd(Arm arm, float t, float eval) {
            return new FaceEnd(arm.spline, 0, arm.alongOut(t), arm.sense > 0, eval);
        }

        /**
         * Joins two arms in a mitre, every part (or just the surface) of both cut on the line halfway between them, and
         * their pictures run on round it — unless they are one line running through a point of its own, which needs
         * nothing. A road's edges are left one post or one end face between them.
         */
        void mitre(Arm a, Arm b, boolean allParts, float ground, boolean link) {
            if (a.sameLine(b)) {
                return;
            }
            float[] line = mitreLine(a, b);
            for (Arm arm : new Arm[]{a, b}) {
                SplinePath.Frame frame = slant(line[0], line[1], arm.splineRight());
                if (frame == null) {
                    continue;
                }
                for (Part part : allParts ? Part.values() : new Part[]{Part.SURFACE}) {
                    boolean edge = part == Part.LEFT_EDGE || part == Part.RIGHT_EDGE;
                    End end = edge && arm == a ? new End(frame, false, ground) : End.joined(frame, ground);
                    add(arm.spline, part, arm.sense > 0 ? new Cut(arm.along, End.OPEN, arm.along, end)
                            : new Cut(arm.along, end, arm.along, End.OPEN));
                }
            }
            if (link) {
                links.add(new Link(stripEnd(a, 0f, a.along), stripEnd(b, 0f, b.along)));
            }
        }

        /** The two arms most nearly in line — the wider, then the newer, first — or null. */
        static Arm[] throughPair(List<Arm> arms) {
            float widest = 0f;
            for (Arm arm : arms) {
                widest = Math.max(widest, arm.half());
            }
            Arm[] best = null;
            float bestScore = -Float.MAX_VALUE;
            int bestAge = -1;
            for (int i = 0; i < arms.size(); i++) {
                for (int j = i + 1; j < arms.size(); j++) {
                    Arm a = arms.get(i);
                    Arm b = arms.get(j);
                    float opposite = -(a.bx * b.bx + a.bz * b.bz);
                    float score = opposite + 0.3f * Math.min(a.half(), b.half()) / Math.max(1e-3f, widest);
                    int age = Math.max(a.index, b.index);
                    if (score > bestScore + 0.02f || score > bestScore - 0.02f && age > bestAge) {
                        bestScore = Math.max(score, bestScore);
                        bestAge = age;
                        best = new Arm[]{a, b};
                    }
                }
            }
            return best;
        }

        /** Three or more roads meeting: one runs through, the rest lead off it, and the through road crosses them. */
        void roads(List<Arm> arms, float ground, float x, float z) {
            Arm[] pair = throughPair(arms);
            if (pair == null) {
                return;
            }
            Arm first = pair[0];
            Arm second = pair[1];
            mitre(first, second, true, ground, false);
            // The through road's direction, from the first arm's side to the second's, and its right.
            float dx = second.bx - first.bx;
            float dz = second.bz - first.bz;
            float length = (float) Math.hypot(dx, dz);
            if (length < 1e-3f) {
                return;
            }
            dx /= length;
            dz /= length;
            float lx = -dz;
            float lz = dx;
            Arm[] through = {first, second};
            float throughHalf = Math.max(first.half(), second.half());
            // How far along the through road (from the first arm's side to the second's) the roads leading off reach
            // over it, and the line each extreme lies along.
            float lo = 0f;
            float hi = 0f;
            float[] loEdge = null;
            float[] hiEdge = null;
            List<Arm> branches = new ArrayList<>();
            List<Integer> sides = new ArrayList<>();
            List<Float> branchCuts = new ArrayList<>();
            for (Arm branch : arms) {
                if (branch == first || branch == second) {
                    continue;
                }
                float cross = dx * branch.bz - dz * branch.bx;
                float sine = Math.abs(cross);
                if (sine < SHALLOWEST) {
                    continue;
                }
                int side = branch.bx * lx + branch.bz * lz >= 0f ? 1 : -1;
                // How far out the through road reaches on the branch's side: its surface, then its footpath there.
                float half = 0f;
                float footpath = 0f;
                for (Arm arm : through) {
                    int armSide = side * (dot(armRight(arm), lx, lz) >= 0f ? 1 : -1);
                    half = Math.max(half, arm.half());
                    footpath = Math.max(footpath, arm.footpath(armSide));
                }
                // The branch, cut back to the through road's edge, its footpaths and edges to its outer edge.
                SplinePath.Frame alongEdge = slant(dx, dz, branch.splineRight());
                if (alongEdge == null) {
                    continue;
                }
                float surface = half / sine;
                float outer = (half + footpath) / sine;
                add(branch.spline, Part.SURFACE, from(branch, surface, End.joined(alongEdge, Float.NaN)));
                End footpathEnd = footpath > 0f ? End.joined(alongEdge, ground) : End.cut(alongEdge);
                add(branch.spline, Part.LEFT_FOOTPATH, from(branch, outer, footpathEnd));
                add(branch.spline, Part.RIGHT_FOOTPATH, from(branch, outer, footpathEnd));
                add(branch.spline, Part.LEFT_EDGE, from(branch, outer, End.cut(alongEdge)));
                add(branch.spline, Part.RIGHT_EDGE, from(branch, outer, End.cut(alongEdge)));
                branches.add(branch);
                sides.add(side);
                branchCuts.add(surface);
                // The through road opens on that side: its footpath as wide as the branch's road, its edge as wide as
                // the branch's road and footpaths. Each opening edge lies along the branch.
                float branchHalf = branch.half();
                float[] footpathGap = {gapAt(branchHalf, cross), gapAt(-branchHalf, cross)};
                float[] edgeGap = {gapAt(branchHalf + branch.footpath(1), cross), gapAt(-branchHalf - branch.footpath(-1), cross)};
                for (Arm arm : through) {
                    int towardD = arm.bx * dx + arm.bz * dz >= 0f ? 1 : -1;
                    int splineSide = side * (dot(arm.splineRight(), lx, lz) >= 0f ? 1 : -1);
                    SplinePath.Frame alongBranch = slant(branch.bx, branch.bz, arm.splineRight());
                    if (alongBranch == null) {
                        continue;
                    }
                    Cut footpathCut = opening(arm, towardD, footpathGap, alongBranch);
                    Cut edgeCut = opening(arm, towardD, edgeGap, alongBranch);
                    if (footpathCut != null) {
                        add(arm.spline, splineSide > 0 ? Part.RIGHT_FOOTPATH : Part.LEFT_FOOTPATH, footpathCut);
                    }
                    if (edgeCut != null) {
                        add(arm.spline, splineSide > 0 ? Part.RIGHT_EDGE : Part.LEFT_EDGE, edgeCut);
                    }
                }
                // The mouth: where the branch's road lies over the through road, from its middle out to its edge on
                // the branch's side, measured along the through road. Its ends lie along the branch's edges.
                float bl = branch.bx * lx + branch.bz * lz;
                float bd = branch.bx * dx + branch.bz * dz;
                float rbx = -branch.bz;
                float rbz = branch.bx;
                float rbl = rbx * lx + rbz * lz;
                float rbd = rbx * dx + rbz * dz;
                for (float w : new float[]{branchHalf, -branchHalf}) {
                    for (float lateral : new float[]{0f, side * throughHalf}) {
                        float t = (lateral - w * rbl) / bl;
                        float along = t * bd + w * rbd;
                        if (along < lo) {
                            lo = along;
                            loEdge = new float[]{branch.bx, branch.bz};
                        }
                        if (along > hi) {
                            hi = along;
                            hiEdge = new float[]{branch.bx, branch.bz};
                        }
                    }
                }
            }
            if (branches.isEmpty()) {
                links.add(new Link(stripEnd(first, 0f, first.along), stripEnd(second, 0f, second.along)));
                return;
            }
            boolean family = familyShape(first, second, branches, sides, throughHalf, dx, dz);
            boolean both = sides.contains(1) && sides.contains(-1);
            int branchSide = both ? 0 : sides.getFirst();
            float[] mitre = mitreLine(first, second);
            float[] reach = new float[2];
            float[][] ends = new float[2][];
            for (int k = 0; k < 2; k++) {
                Arm arm = through[k];
                int toward = arm.bx * dx + arm.bz * dz >= 0f ? 1 : -1;
                float t = Math.max(0f, toward > 0 ? hi : -lo);
                float[] edge = toward > 0 ? hiEdge : loEdge;
                reach[k] = t;
                ends[k] = edge;
                if (t < MIN_PIECE) {
                    continue;
                }
                SplinePath.Frame outerFrame = edge == null ? null : slant(edge[0], edge[1], arm.splineRight());
                add(arm.spline, Part.SURFACE, from(arm, t, End.joined(outerFrame, Float.NaN)));
                SplinePath.Frame middle = first.spline == second.spline ? null : slant(mitre[0], mitre[1], arm.splineRight());
                float outerAlong = arm.alongOut(t);
                int along = toward * arm.sense;
                Crossing crossing = arm.sense > 0
                        ? new Crossing(arm.along, middle, outerAlong, outerFrame, family, both, arm.along, (hi - lo) * 0.5f,
                        throughHalf, along, (branchSide >= 0 ? 1 : -1) * along, branchSide * along, Run.NATURAL)
                        : new Crossing(outerAlong, outerFrame, arm.along, middle, family, both, arm.along, (hi - lo) * 0.5f,
                        throughHalf, along, (branchSide >= 0 ? 1 : -1) * along, branchSide * along, Run.NATURAL);
                crossings.add(new PendingCrossing(arm.spline, crossing, stripEnd(arm, t, arm.along)));
            }
            if (family) {
                // Every road's picture starts afresh where it meets the crossroads or T, as the set's pieces fit.
                for (int k = 0; k < 2; k++) {
                    anchors.add(new Anchor(stripEnd(through[k], reach[k], through[k].alongOut(reach[k])), 0f));
                }
                for (int k = 0; k < branches.size(); k++) {
                    Arm branch = branches.get(k);
                    anchors.add(new Anchor(stripEnd(branch, branchCuts.get(k), branch.alongOut(branchCuts.get(k))), 0f));
                }
            } else {
                // The through road's picture runs on across the crossing, from one side's piece to the other's.
                links.add(new Link(stripEnd(first, reach[0], first.along), stripEnd(second, reach[1], second.along)));
            }
            fillets(arms, through, reach, ends, branches, branchCuts, dx, dz, x, z, family, both);
        }

        /**
         * Rounds the corners either side of each road leading off a through road, between it and the through arm on that
         * side (where it is laid in its picture set's own crossroads or T, those corners stand instead) — unless a footpath, a wall or a railing stands at the corner, another road leaves
         * inside it, or the two wear different pictures.
         *
         * @param reach how far out along each through arm its crossing reaches, along its middle
         * @param ends  the line each arm's crossing ends on, or null for square
         * @param cuts  how far out along each branch it is cut back
         */
        void fillets(List<Arm> arms, Arm[] through, float[] reach, float[][] ends, List<Arm> branches, List<Float> cuts,
                     float dx, float dz, float x, float z, boolean family, boolean cross) {
            for (int i = 0; i < branches.size(); i++) {
                Arm branch = branches.get(i);
                for (int corner = -1; corner <= 1; corner += 2) {
                    // The way across the branch to this corner, and the through arm on that side.
                    float[] n = armRight(branch);
                    n[0] *= corner;
                    n[1] *= corner;
                    int k = dot(n, through[0].bx, through[0].bz) >= dot(n, through[1].bx, through[1].bz) ? 0 : 1;
                    Arm arm = through[k];
                    if (dot(n, arm.bx, arm.bz) < 0.05f) {
                        continue;
                    }
                    float[] right = armRight(arm);
                    int armSide = dot(right, branch.bx, branch.bz) >= 0f ? 1 : -1;
                    if (!openCorner(arm, armSide) || !openCorner(branch, corner) || !samePicture(arm, branch)
                            || leavesBetween(arms, arm, branch)) {
                        continue;
                    }
                    float[] end = ends[k];
                    Bend side = new Bend(arm.bx, arm.bz, right[0] * armSide, right[1] * armSide, arm.half(), arm.along,
                            arm.sense, arm.splineSide(armSide) > 0, reach[k],
                            end == null ? right[0] * armSide : end[0], end == null ? right[1] * armSide : end[1], Run.NATURAL);
                    Bend leading = new Bend(branch.bx, branch.bz, n[0], n[1], branch.half(), branch.along, branch.sense,
                            branch.splineSide(corner) > 0, cuts.get(i), dx, dz, Run.NATURAL);
                    MapSpline owner = arm.index >= branch.index ? arm.spline : branch.spline;
                    fillets.add(new PendingFillet(owner, new Fillet(x, z, side, leading, family, cross), stripEnd(arm, reach[k], arm.along),
                            stripEnd(branch, cuts.get(i), branch.alongOut(cuts.get(i)))));
                }
            }
        }

        /** Whether nothing but the road's own verge is at its edge on one side (+1 the arm's right): no footpath, no wall or railing. */
        static boolean openCorner(Arm arm, int armSide) {
            if (arm.footpath(armSide) > 0f) {
                return false;
            }
            MapSpline spline = arm.spline;
            boolean edge = arm.splineSide(armSide) > 0 ? spline.edges.right() : spline.edges.left();
            return !edge || spline.edgeKind == MapSpline.Kind.MODELS;
        }

        /** Whether two roads wear the same picture, fitted to their width, so one's verge can bend into the other's. */
        static boolean samePicture(Arm a, Arm b) {
            return a.spline.kind == b.spline.kind && Objects.equals(a.spline.texture, b.spline.texture)
                    && a.spline.turned == b.spline.turned && a.spline.textureSize <= 0f && b.spline.textureSize <= 0f;
        }

        /** Whether any other arm leaves the junction between two, inside the corner they make. */
        static boolean leavesBetween(List<Arm> arms, Arm a, Arm b) {
            float limit = angleFrom(a, b.bx, b.bz);
            for (Arm other : arms) {
                if (other == a || other == b) {
                    continue;
                }
                float angle = angleFrom(a, other.bx, other.bz);
                if (angle * limit > 0f && Math.abs(angle) < Math.abs(limit)) {
                    return true;
                }
            }
            return false;
        }

        /** The angle from an arm round to a direction, either way, from -pi to pi. */
        static float angleFrom(Arm arm, float x, float z) {
            return (float) Math.atan2(arm.bx * z - arm.bz * x, arm.bx * x + arm.bz * z);
        }

        /**
         * Whether a junction can take a picture set's crossroads or T: a straight road with one road leading off, or two
         * in line on opposite sides, all square to it, all about as wide, all in the same picture fitted to their width.
         */
        static boolean familyShape(Arm first, Arm second, List<Arm> branches, List<Integer> sides, float throughHalf,
                                   float dx, float dz) {
            if (branches.size() > 2 || first.bx * second.bx + first.bz * second.bz > -0.995f) {
                return false;
            }
            if (branches.size() == 2 && (sides.get(0).equals(sides.get(1))
                    || branches.get(0).bx * branches.get(1).bx + branches.get(0).bz * branches.get(1).bz > -0.99f)) {
                return false;
            }
            List<Arm> all = new ArrayList<>(branches);
            all.add(first);
            all.add(second);
            for (Arm arm : all) {
                if (Math.abs(arm.half() - throughHalf) > 0.12f * throughHalf || arm.spline.textureSize > 0f
                        || arm.spline.turned != first.spline.turned
                        || !Objects.equals(arm.spline.texture, first.spline.texture) || arm.spline.kind != first.spline.kind) {
                    return false;
                }
            }
            for (Arm branch : branches) {
                if (Math.abs(branch.bx * dx + branch.bz * dz) > SQUARE_SINE) {
                    return false;
                }
            }
            return true;
        }

        /** Three or more footpaths meeting: one runs through, the rest stop at its edge, level with it. */
        void footpaths(List<Arm> arms, float ground) {
            Arm[] pair = throughPair(arms);
            if (pair == null) {
                return;
            }
            Arm first = pair[0];
            Arm second = pair[1];
            mitre(first, second, false, ground, true);
            float dx = second.bx - first.bx;
            float dz = second.bz - first.bz;
            float length = (float) Math.hypot(dx, dz);
            if (length < 1e-3f) {
                return;
            }
            dx /= length;
            dz /= length;
            float half = Math.max(first.half(), second.half());
            for (Arm branch : arms) {
                if (branch == first || branch == second) {
                    continue;
                }
                float sine = Math.abs(dx * branch.bz - dz * branch.bx);
                SplinePath.Frame alongEdge = slant(dx, dz, branch.splineRight());
                if (sine < SHALLOWEST || alongEdge == null) {
                    continue;
                }
                add(branch.spline, Part.SURFACE, from(branch, half / sine, End.joined(alongEdge, ground)));
            }
        }

        /**
         * Walls meeting, made one wall: each arm is cut on the line between the corner its faces make with the
         * neighbouring arms' faces either side, a cap covers what that leaves in the middle, and each face's picture runs
         * on round its corner into the next face.
         */
        void walls(List<Arm> arms, float x, float z, float ground) {
            List<Arm> sorted = new ArrayList<>(arms);
            sorted.sort(Comparator.comparingDouble(arm -> Math.atan2(arm.bz, arm.bx)));
            int n = sorted.size();
            float reach = 0f;
            for (Arm arm : sorted) {
                reach = Math.max(reach, arm.half());
            }
            reach *= CORNER_REACH;
            // Corner i is where arm i's face toward the next arm round meets that arm's face toward it.
            float[][] corners = new float[n][];
            for (int i = 0; i < n; i++) {
                corners[i] = corner(sorted.get(i), sorted.get((i + 1) % n), reach);
            }
            float[] cutAt = new float[n];
            for (int i = 0; i < n; i++) {
                Arm arm = sorted.get(i);
                float[] previous = corners[(i - 1 + n) % n];
                float[] next = corners[i];
                float tp = arm.out(previous);
                float tn = arm.out(next);
                float sp = previous[0] * -arm.bz + previous[1] * arm.bx;
                float sn = next[0] * -arm.bz + next[1] * arm.bx;
                float t = Math.abs(sn - sp) < 1e-4f ? (tp + tn) * 0.5f : tp - sp * (tn - tp) / (sn - sp);
                cutAt[i] = Math.max(0f, t);
                SplinePath.Frame frame = slant(next[0] - previous[0], next[1] - previous[1], arm.splineRight());
                add(arm.spline, Part.SURFACE, from(arm, cutAt[i], End.joined(frame, ground)));
            }
            for (int i = 0; i < n; i++) {
                Arm a = sorted.get(i);
                Arm b = sorted.get((i + 1) % n);
                float[] c = corners[i];
                // a's face toward b is its spline's right where a runs on along it; b's face toward a its left.
                int sideA = a.sense > 0 ? 1 : -1;
                int sideB = b.sense > 0 ? -1 : 1;
                links.add(new Link(
                        new FaceEnd(a.spline, sideA, a.alongOut(cutAt[i]), a.sense > 0, a.alongOut(a.out(c))),
                        new FaceEnd(b.spline, sideB, b.alongOut(cutAt[(i + 1) % n]), b.sense > 0, b.alongOut(b.out(c)))));
            }
            if (n < 3) {
                return;
            }
            List<Corner> cap = new ArrayList<>();
            List<Float> sideHeights = new ArrayList<>();
            float height = 0f;
            Arm owner = sorted.getFirst();
            for (int i = 0; i < n; i++) {
                cap.add(new Corner(x + corners[i][0], z + corners[i][1]));
                // Side i runs from corner i to the next, across the end of the arm after it.
                sideHeights.add(sorted.get((i + 1) % n).spline.height);
                height = Math.max(height, sorted.get(i).spline.height);
                if (sorted.get(i).index < owner.index) {
                    owner = sorted.get(i);
                }
            }
            patchesOf(owner.spline).add(new Cap(List.copyOf(cap), ground, height, List.copyOf(sideHeights)));
        }

        /**
         * Where arm {@code a}'s face toward {@code b} (the next round from it) meets {@code b}'s face toward it,
         * relative to the junction; no further out than {@code reach}.
         */
        static float[] corner(Arm a, Arm b, float reach) {
            float nx = -a.bz;
            float nz = a.bx;
            float mx = b.bz;
            float mz = -b.bx;
            float ha = a.half();
            float hb = b.half();
            float det = nx * mz - nz * mx;
            float px;
            float pz;
            if (Math.abs(det) < 1e-4f) {
                // In line: the faces run straight on (or the arms lie on one another).
                float h = a.bx * b.bx + a.bz * b.bz < 0f ? (ha + hb) * 0.5f : ha;
                px = nx * h;
                pz = nz * h;
            } else {
                px = (ha * mz - nz * hb) / det;
                pz = (nx * hb - mx * ha) / det;
            }
            float distance = (float) Math.hypot(px, pz);
            if (distance > reach) {
                px *= reach / distance;
                pz *= reach / distance;
            }
            return new float[]{px, pz};
        }

        /** Railings meeting: each stops at the junction, and they share one post there. */
        void railings(List<Arm> arms, float x, float z, float ground) {
            float height = 0f;
            float size = SplineMeshes.POST;
            Arm owner = arms.getFirst();
            for (Arm arm : arms) {
                add(arm.spline, Part.SURFACE, from(arm, 0f, End.joined(null, ground)));
                height = Math.max(height, arm.spline.height);
                size = Math.max(size, arm.spline.width);
                if (arm.index < owner.index) {
                    owner = arm;
                }
            }
            patchesOf(owner.spline).add(new Post(x, z, owner.bx, owner.bz, ground, height, size));
        }

        // --- Laid over ---------------------------------------------------------------------------------------

        /**
         * Every spline a later road, path or footpath lies over is cut back to its edge, part by part, except where the
         * two join in a junction.
         */
        void overlaps() {
            int n = splines.size();
            float[][] boxes = new float[n][];
            for (int s = 0; s < n; s++) {
                MapSpline spline = splines.get(s);
                SplinePath path = spline.kind == MapSpline.Kind.MODELS ? null : path(spline);
                if (path != null) {
                    boxes[s] = bounds(path, reach(spline));
                }
            }
            for (int b = 1; b < n; b++) {
                MapSpline top = splines.get(b);
                if (boxes[b] == null || !top.kind.covers()) {
                    continue;
                }
                SplinePath topPath = path(top);
                float cover = top.kind.flat() ? top.halfReach() : top.width * 0.5f;
                for (int a = 0; a < b; a++) {
                    MapSpline under = splines.get(a);
                    if (boxes[a] == null || !overlap(boxes[a], boxes[b])) {
                        continue;
                    }
                    SplinePath underPath = path(under);
                    List<Float> junctions = joined.getOrDefault(under, Map.of()).getOrDefault(top, List.of());
                    for (Map.Entry<Part, Float> part : parts(under).entrySet()) {
                        float offset = part.getValue();
                        for (float[] stretch : covered(underPath, offset, topPath, cover)) {
                            if (nearAny(junctions, stretch[0] - 2f, stretch[1] + 2f)) {
                                continue;
                            }
                            float[] lo = cutAcross(underPath, offset, stretch[0], topPath, cover, stretch[0] > 0f);
                            float[] hi = cutAcross(underPath, offset, stretch[1], topPath, cover,
                                    stretch[1] < underPath.end());
                            if (lo[0] < hi[0]) {
                                add(under, part.getKey(), new Cut(lo[0], end(lo), hi[0], end(hi)));
                            }
                        }
                    }
                }
            }
        }

        private static End end(float[] cut) {
            return Float.isNaN(cut[1]) ? End.OPEN : End.cut(new SplinePath.Frame(cut[1], cut[2], cut[3]));
        }

        private static boolean nearAny(List<Float> alongs, float lo, float hi) {
            for (float along : alongs) {
                if (along >= lo && along <= hi) {
                    return true;
                }
            }
            return false;
        }

        /** The parts a spline is laid in, each by how far to the right of its line its middle lies. */
        static Map<Part, Float> parts(MapSpline spline) {
            Map<Part, Float> out = new EnumMap<>(Part.class);
            out.put(Part.SURFACE, 0f);
            if (!spline.kind.flat()) {
                return out;
            }
            float half = spline.width * 0.5f;
            float outer = half;
            if (spline.footpaths != MapSpline.Sides.NONE) {
                float middle = half + spline.footpathWidth * 0.5f;
                if (spline.footpaths.left()) {
                    out.put(Part.LEFT_FOOTPATH, -middle);
                }
                if (spline.footpaths.right()) {
                    out.put(Part.RIGHT_FOOTPATH, middle);
                }
                outer += spline.footpathWidth;
            }
            if (spline.edges != MapSpline.Sides.NONE && spline.edgeKind != MapSpline.Kind.MODELS) {
                float thickness = spline.edgeKind == MapSpline.Kind.WALL ? SplineMeshes.EDGE_WALL : SplineMeshes.POST;
                if (spline.edges.left()) {
                    out.put(Part.LEFT_EDGE, -(spline.footpaths.left() ? outer : half) + thickness * 0.5f);
                }
                if (spline.edges.right()) {
                    out.put(Part.RIGHT_EDGE, (spline.footpaths.right() ? outer : half) - thickness * 0.5f);
                }
            }
            return out;
        }

        /** How far a spline reaches either side of its line, for a box round it. */
        static float reach(MapSpline spline) {
            float edges = spline.kind.flat() && spline.edges != MapSpline.Sides.NONE ? SplineMeshes.EDGE_WALL : 0f;
            return spline.halfReach() + edges + 0.5f;
        }

        static float[] bounds(SplinePath path, float margin) {
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int k = 0; k < path.size(); k++) {
                box[0] = Math.min(box[0], path.x(k) - margin);
                box[1] = Math.min(box[1], path.z(k) - margin);
                box[2] = Math.max(box[2], path.x(k) + margin);
                box[3] = Math.max(box[3], path.z(k) + margin);
            }
            return box;
        }

        static boolean overlap(float[] a, float[] b) {
            return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
        }

        /** The point {@code offset} to the right of a line {@code along} it. */
        static float[] beside(SplinePath path, float along, float offset) {
            float[] point = path.pointAt(along);
            float[] direction = path.directionAt(along);
            return new float[]{point[0] - direction[1] * offset, point[1] + direction[0] * offset};
        }

        /** Whether a point lies on a later spline: within its reach of its line, and not past either end. */
        static boolean under(SplinePath top, float cover, float[] point) {
            SplinePath.Hit hit = top.nearest(point[0], point[1]);
            if (hit == null || hit.distance() > cover) {
                return false;
            }
            if (!top.closed()) {
                if (hit.along() <= top.start() + 1e-3f) {
                    float[] at = top.pointAt(top.start());
                    float[] d = top.directionAt(top.start());
                    return (point[0] - at[0]) * d[0] + (point[1] - at[1]) * d[1] >= -1e-3f;
                }
                if (hit.along() >= top.end() - 1e-3f) {
                    float[] at = top.pointAt(top.end());
                    float[] d = top.directionAt(Math.max(top.start(), top.end() - 0.01f));
                    return (point[0] - at[0]) * d[0] + (point[1] - at[1]) * d[1] <= 1e-3f;
                }
            }
            return true;
        }

        /** The stretches along a line whose {@code offset} lies on a later spline, as {from, to}. */
        static List<float[]> covered(SplinePath path, float offset, SplinePath top, float cover) {
            float length = path.end();
            int steps = Math.max(2, (int) Math.ceil(length / COVER_STEP));
            boolean[] in = new boolean[steps + 1];
            boolean any = false;
            for (int i = 0; i <= steps; i++) {
                in[i] = under(top, cover, beside(path, length * i / steps, offset));
                any |= in[i];
            }
            List<float[]> out = new ArrayList<>();
            if (!any) {
                return out;
            }
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
                float from = i == 0 ? 0f : edge(path, offset, top, cover, length * (i - 1) / steps, length * i / steps);
                float to = j == steps ? length : edge(path, offset, top, cover, length * (j + 1) / steps, length * j / steps);
                if (to - from > MIN_PIECE) {
                    out.add(new float[]{from, to});
                }
                i = j + 1;
            }
            return out;
        }

        /** Where between {@code out} (not under) and {@code in} (under) a line's offset goes under a later spline. */
        static float edge(SplinePath path, float offset, SplinePath top, float cover, float out, float in) {
            for (int k = 0; k < 14; k++) {
                float middle = (out + in) * 0.5f;
                if (under(top, cover, beside(path, middle, offset))) {
                    in = middle;
                } else {
                    out = middle;
                }
            }
            return in;
        }

        /**
         * The cut across a line where its offset goes under a later spline {@code along} it: lying along that spline's
         * edge (or across its end), placed where that edge crosses the line itself, as {along, frame's rightX, rightZ,
         * miter} — the frame NaN for square. Not slanted at all ({@code slanted} false) at the line's own end.
         */
        static float[] cutAcross(SplinePath path, float offset, float along, SplinePath top, float cover, boolean slanted) {
            if (!slanted) {
                return new float[]{along, Float.NaN, Float.NaN, Float.NaN};
            }
            float[] point = beside(path, along, offset);
            SplinePath.Hit hit = top.nearest(point[0], point[1]);
            float ex;
            float ez;
            boolean atEnd = !top.closed() && (hit.along() <= top.start() + 1e-3f || hit.along() >= top.end() - 1e-3f)
                    && hit.distance() < cover - 0.02f;
            float[] d = top.directionAt(Math.clamp(hit.along(), top.start(), Math.max(top.start(), top.end() - 0.01f)));
            if (atEnd) {
                ex = -d[1];
                ez = d[0];
            } else {
                ex = d[0];
                ez = d[1];
            }
            float[] direction = path.directionAt(along);
            float[] right = {-direction[1], direction[0]};
            SplinePath.Frame frame = slant(ex, ez, right);
            if (frame == null) {
                return new float[]{along, Float.NaN, Float.NaN, Float.NaN};
            }
            // The edge through the offset point, followed back to the line itself.
            float er = ex * right[0] + ez * right[1];
            float lambda = -offset / er;
            float at = along + lambda * (ex * direction[0] + ez * direction[1]);
            return new float[]{at, frame.rightX(), frame.rightZ(), frame.miter()};
        }

        /**
         * How many hairs each spline's flat surfaces are lifted: one more than the highest road or path drawn before it
         * that it may lie over, so the later one is always on top.
         */
        int[] layers() {
            int n = splines.size();
            int[] layer = new int[n];
            float[][] boxes = new float[n][];
            for (int s = 0; s < n; s++) {
                MapSpline spline = splines.get(s);
                SplinePath path = spline.kind.flat() ? path(spline) : null;
                if (path == null) {
                    continue;
                }
                boxes[s] = bounds(path, reach(spline));
                int highest = -1;
                for (int t = 0; t < s; t++) {
                    if (boxes[t] != null && overlap(boxes[s], boxes[t])) {
                        highest = Math.max(highest, layer[t]);
                    }
                }
                layer[s] = Math.min(MAX_LAYER, highest + 1);
            }
            return layer;
        }

        // --- Pictures running on -----------------------------------------------------------------------------

        /** One side of one piece of a spline's surface, and how its picture runs. */
        private static final class Face {
            final int facing;
            final float size;
            /** What holds each end: a {@link Bond}, a {@link Pin} or nothing. */
            final Object[] ends = new Object[2];
            float stretch = 1f;
            float shift;
            boolean fixed;

            Face(int facing, float size) {
                this.facing = facing;
                this.size = size;
            }

            /** How far across its picture's repeats the face is {@code along} its spline. */
            float picture(float along) {
                return facing * (along * stretch + shift) / size;
            }

            Run run() {
                return new Run(stretch, shift);
            }
        }

        /** An end held to another face's end: read here at {@code self}, there at {@code there}. */
        private record Bond(Face other, int otherEnd, float self, float there) {
        }

        /** An end held to a whole repeat. */
        private record Pin(float self, float target) {
        }

        /**
         * Slides each surface piece's picture (and stretches it a little when both its ends are held) so it runs on
         * into the pieces it meets at corners and mitres, starts whole against a crossroads, and closes round a loop.
         */
        private final class Solver {
            final Map<MapSpline, Map<Part, List<Cut>>> merged;
            final Map<MapSpline, List<Range>> ranges = new IdentityHashMap<>();
            final Map<MapSpline, Map<Integer, Face>> faces = new IdentityHashMap<>();
            final List<Face> all = new ArrayList<>();

            Solver(Map<MapSpline, Map<Part, List<Cut>>> merged) {
                this.merged = merged;
            }

            List<Range> rangesOf(MapSpline spline) {
                return ranges.computeIfAbsent(spline, s -> {
                    SplinePath path = path(s);
                    return path == null ? List.of() : SplineJunctions.ranges(path,
                            merged.getOrDefault(s, Map.of()).get(Part.SURFACE));
                });
            }

            /** The face and which of its ends a face end names, or null when no piece ends there. */
            Object[] resolve(FaceEnd end) {
                int[] sides = sides(end.spline());
                if (sides.length == 0 || (end.side() == 0) != (sides.length == 1)) {
                    return null;
                }
                List<Range> list = rangesOf(end.spline());
                SplinePath path = path(end.spline());
                int best = -1;
                float bestDistance = 0.25f;
                for (int k = 0; k < list.size(); k++) {
                    float at = end.start() ? list.get(k).from() : list.get(k).to();
                    float distance = Math.abs(at - end.at());
                    if (path != null && path.closed()) {
                        distance = Math.min(distance, Math.abs(Math.abs(at - end.at()) - path.end()));
                    }
                    if (distance <= bestDistance) {
                        bestDistance = distance;
                        best = k;
                    }
                }
                if (best < 0) {
                    return null;
                }
                int piece = best;
                Face face = faces.computeIfAbsent(end.spline(), s -> new HashMap<>()).computeIfAbsent(piece * 3 + end.side() + 1,
                        key -> {
                            Face made = new Face(end.side() == 0 ? 1 : end.side(), pictureSize(end.spline()));
                            all.add(made);
                            return made;
                        });
                return new Object[]{face, end.start() ? 0 : 1};
            }

            Run runOf(FaceEnd end) {
                Object[] found = resolve(end);
                return found == null ? Run.NATURAL : ((Face) found[0]).run();
            }

            Map<MapSpline, List<Runs>> solve() {
                // A loop closes on itself: its picture must come round whole.
                for (MapSpline spline : splines) {
                    SplinePath path = spline.closed ? path(spline) : null;
                    if (path == null || !path.closed() || sides(spline).length == 0) {
                        continue;
                    }
                    List<Range> list = rangesOf(spline);
                    if (list.isEmpty() || list.getFirst().from() > 1e-3f || list.getLast().to() < path.end() - 1e-3f) {
                        continue;
                    }
                    for (int side : sides(spline)) {
                        links.add(new Link(new FaceEnd(spline, side, path.end(), false, path.end()),
                                new FaceEnd(spline, side, 0f, true, 0f)));
                    }
                }
                for (Link link : links) {
                    Object[] a = resolve(link.a());
                    Object[] b = resolve(link.b());
                    if (a == null || b == null) {
                        continue;
                    }
                    Face fa = (Face) a[0];
                    Face fb = (Face) b[0];
                    int ea = (int) a[1];
                    int eb = (int) b[1];
                    if (fa.ends[ea] != null || fb.ends[eb] != null || fa == fb && ea == eb) {
                        continue;
                    }
                    fa.ends[ea] = new Bond(fb, eb, link.a().eval(), link.b().eval());
                    fb.ends[eb] = new Bond(fa, ea, link.b().eval(), link.a().eval());
                }
                for (Anchor anchor : anchors) {
                    Object[] found = resolve(anchor.end());
                    if (found != null && ((Face) found[0]).ends[(int) found[1]] == null) {
                        ((Face) found[0]).ends[(int) found[1]] = new Pin(anchor.end().eval(), anchor.target());
                    }
                }
                Deque<Face> queue = new ArrayDeque<>();
                for (Face face : all) {
                    if (face.ends[0] instanceof Pin || face.ends[1] instanceof Pin) {
                        settle(face);
                        queue.add(face);
                    }
                }
                spread(queue);
                for (Face face : all) {
                    if (!face.fixed) {
                        Face root = chainStart(face);
                        settle(root);
                        queue.add(root);
                        spread(queue);
                    }
                }
                Map<MapSpline, List<Runs>> out = new IdentityHashMap<>();
                faces.forEach((spline, byKey) -> {
                    List<Range> list = rangesOf(spline);
                    List<Runs> runs = new ArrayList<>();
                    boolean any = false;
                    for (int k = 0; k < list.size(); k++) {
                        Run strip = runAt(byKey, k, 0);
                        Run left = runAt(byKey, k, -1);
                        Run right = runAt(byKey, k, 1);
                        any |= !strip.equals(Run.NATURAL) || !left.equals(Run.NATURAL) || !right.equals(Run.NATURAL);
                        runs.add(new Runs(strip, left, right));
                    }
                    if (any) {
                        out.put(spline, List.copyOf(runs));
                    }
                });
                return out;
            }

            Run runAt(Map<Integer, Face> byKey, int piece, int side) {
                Face face = byKey.get(piece * 3 + side + 1);
                return face == null ? Run.NATURAL : face.run();
            }

            /** Where a chain of faces held end to end starts: a face with a free end, or this one on a loop. */
            Face chainStart(Face face) {
                Face current = face;
                Face previous = null;
                for (int guard = 0; guard < 100_000; guard++) {
                    if (!(current.ends[0] instanceof Bond) || !(current.ends[1] instanceof Bond)) {
                        return current;
                    }
                    Bond a = (Bond) current.ends[0];
                    Bond b = (Bond) current.ends[1];
                    Face next = a.other() != previous ? a.other() : b.other();
                    if (next == face || next == current) {
                        return face;
                    }
                    previous = current;
                    current = next;
                }
                return face;
            }

            void spread(Deque<Face> queue) {
                while (!queue.isEmpty()) {
                    Face face = queue.poll();
                    for (Object end : face.ends) {
                        if (end instanceof Bond bond && !bond.other().fixed) {
                            settle(bond.other());
                            queue.add(bond.other());
                        }
                    }
                }
            }

            /** Fixes how a face's picture runs from whatever already holds its ends. */
            void settle(Face face) {
                face.fixed = true;
                if (face.ends[0] instanceof Bond a && a.other() == face && face.ends[1] instanceof Bond) {
                    // A whole loop: as many whole repeats round it as fit.
                    float length = Math.abs(a.there() - a.self());
                    if (length > 1e-3f) {
                        float repeats = Math.max(1f, Math.round(length / face.size));
                        face.stretch = repeats * face.size / length;
                    }
                    face.shift = 0f;
                    return;
                }
                float[] at = new float[2];
                float[] target = new float[2];
                int count = 0;
                for (Object end : face.ends) {
                    if (end instanceof Pin pin) {
                        at[count] = pin.self();
                        target[count++] = pin.target();
                    } else if (end instanceof Bond bond && bond.other() != face && bond.other().fixed) {
                        at[count] = bond.self();
                        target[count++] = bond.other().picture(bond.there());
                    }
                }
                face.stretch = 1f;
                face.shift = 0f;
                if (count == 0) {
                    return;
                }
                float w0 = face.facing * target[0] * face.size;
                if (count == 2) {
                    float w1 = face.facing * target[1] * face.size;
                    float span = at[1] - at[0];
                    if (Math.abs(span) > face.size * 0.5f) {
                        float m = Math.round((span - (w1 - w0)) / face.size);
                        float stretch = (w1 - w0 + m * face.size) / span;
                        if (stretch > 0.6f && stretch < 1.6f) {
                            face.stretch = stretch;
                        }
                    }
                }
                face.shift = w0 - at[0] * face.stretch;
                face.shift -= face.size * Math.round(face.shift / face.size);
            }
        }

        /** The sides of a spline's surface whose pictures are run on: a wall's two faces, a road's one surface. */
        static int[] sides(MapSpline spline) {
            return switch (spline.kind) {
                case WALL -> new int[]{-1, 1};
                case ROAD, PATH, FOOTPATH -> new int[]{0};
                default -> new int[0];
            };
        }

        /** How long one repeat of a spline's surface picture is along it. */
        static float pictureSize(MapSpline spline) {
            return spline.kind == MapSpline.Kind.WALL ? SplineMeshes.wallPictureSize(spline)
                    : SplineMeshes.surfacePictureSize(spline);
        }
    }

    /** The arm's own right: the way to its right looking out along it. */
    private static float[] armRight(Arm arm) {
        return new float[]{-arm.bz, arm.bx};
    }

    private static float dot(float[] v, float x, float z) {
        return v[0] * x + v[1] * z;
    }
}
