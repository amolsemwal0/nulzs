package org.zeronura.nulzs.worldmap;

import org.joml.Vector3f;
import org.zeronura.nulzs.physics.AABB;
import org.zeronura.nulzs.render.TexturedMeshBuilder;
import org.zeronura.nulzs.render.TexturedMeshData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays a {@link MapSpline} on its map: the textured surfaces that run along it, a mesh's worth per texture, and the
 * collision boxes of whatever on it stands up out of the ground. Plain data with no GL; {@link MapTerrain} uploads and
 * draws what this builds.
 *
 * <ul>
 *   <li>A road or a path is a strip lying on the ground, a little above it, its texture's picture laid across the whole
 *       width (or one every {@link MapSpline#textureSize} metres) and repeating along it — a picture of a road with its
 *       lines makes a road with its lines however it bends. It follows the ground across as well as along, so it never
 *       floats over a hollow or sinks into a bump; {@link #level} grades the ground under it first, as roads want.
 *   <li>A footpath is a slab raised off the ground, level across, with a light curb down each side.
 *   <li>A wall is a slab as tall as its height and as thick as its width, sunk into the ground so no gap shows under it
 *       on a slope, its texture fitted to its height.
 *   <li>A railing is posts every {@value #POST_SPACING} m with a handrail and a middle rail between them.
 * </ul>
 *
 * Footpaths beside a road are footpaths laid along its sides, and its edges railings or walls along the outer side of
 * all that, offset from the one line. Where splines meet, and where a later one lies over an earlier one, each part is
 * laid only along the stretches {@link SplineJunctions} leaves it, its pictures running on as it says, with what it adds
 * where they meet: a crossing piece in a road, a cap where walls meet, the one post railings share. A wall's top wears
 * its picture square to the world, so tops meeting at any angle run on into one another. Models along a spline are the
 * map's own objects, laid by {@link SplineModels}.
 */
public final class SplineMeshes {

    /** The texture key of the plain surface curbs are drawn in, tinted {@link #CURB}. */
    public static final String PLAIN = "";
    /** How far apart along a spline its surfaces follow the ground: about a cell of most maps. */
    public static final float STEP = 1f;
    /** How far a road's surface lies above the ground under it, so the ground never shows through. */
    public static final float LIFT = 0.05f;
    /**
     * How much higher a spline's flat surfaces lie for each one drawn before it that it may lie over
     * ({@link SplineJunctions.Trims#layer}), so the later one is always on top and two that overlap never flicker.
     */
    public static final float LAYER = 0.004f;
    /** How far above its own road a rounded corner lies, in layers: over both roads it joins, whichever was first. */
    static final int FILLET_LAYERS = 2;
    /** A verge wider than this share of a road's picture isn't one (a lane, say): its corners aren't rounded. */
    static final float WIDEST_VERGE = 0.35f;
    /** How far a rounded corner's bend reaches past its verge, as a share of the narrower road's width. */
    static final float BEND = 0.35f;
    /** How far a wall or a footpath goes down past the lowest ground under it. */
    static final float SINK = 0.35f;
    public static final float POST_SPACING = 2f;
    static final float POST = 0.12f;
    static final float RAIL_WIDTH = 0.07f;
    static final float RAIL_HEIGHT = 0.07f;
    static final float EDGE_WALL = 0.3f;
    /** Walls and railings follow the ground's lie along their line over this distance either side, not each bump. */
    static final float SMOOTHING = 1.5f;
    /** How far from a junction the top of a wall, a railing or a footpath meeting it is eased to the junction's height. */
    static final float PIN_BLEND = 2f;
    static final Vector3f WHITE = new Vector3f(1f, 1f, 1f);
    static final Vector3f CURB = new Vector3f(0.86f, 0.86f, 0.83f);
    private static final Vector3f UP = new Vector3f(0f, 1f, 0f);

    /**
     * A spline as laid: its surfaces by texture ({@link #PLAIN} for the curbs), and the boxes that collide.
     *
     * @param meshes the surfaces, keyed by the albedo they wear
     */
    public record Built(Map<String, TexturedMeshData> meshes, List<AABB> colliders) {
    }

    private SplineMeshes() {
    }

    /**
     * Builds what a spline is laid as, or nothing when it has too few points.
     *
     * @param lift     how far above the ground its flat surfaces lie, so two crossing splines can be told apart
     * @param defaults the texture a spline of each kind wears where it names none
     */
    public static Built build(WorldMap map, MapSpline spline, float lift, Function<MapSpline.Kind, String> defaults) {
        SplinePath path = spline.drawable() ? SplinePath.of(spline, Math.min(STEP, map.cellSize())) : null;
        return build(map, spline, path, SplineJunctions.Trims.NONE, lift, SplineTextures.defaults(defaults));
    }

    /** {@link #build(WorldMap, MapSpline, SplinePath, SplineJunctions.Trims, float, SplineTextures)} knowing only defaults. */
    public static Built build(WorldMap map, MapSpline spline, SplinePath path, SplineJunctions.Trims trims, float lift,
                              Function<MapSpline.Kind, String> defaults) {
        return build(map, spline, path, trims, lift, SplineTextures.defaults(defaults));
    }

    /**
     * Builds what a spline is laid as along its line, less what junctions and later splines take out of it, with what
     * its junctions add.
     *
     * @param path     its line, sampled {@link #STEP} apart, or null when it has too few points
     * @param trims    what the junctions it is part of, and the splines laid over it, do to each of its parts
     * @param textures the textures there are, and what each kind wears where a spline names none
     */
    public static Built build(WorldMap map, MapSpline spline, SplinePath path, SplineJunctions.Trims trims, float lift,
                              SplineTextures textures) {
        Parts parts = new Parts(map);
        if (path == null) {
            return parts.finish();
        }
        float half = spline.width * 0.5f;
        String main = spline.texture != null ? spline.texture : textures.defaultTexture(spline.kind);
        switch (spline.kind) {
            case ROAD, PATH -> {
                Look look = new Look(main, spline.turned, spline.textureSize);
                for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.SURFACE)) {
                    parts.strip(piece.path(), -half, half, lift, look, piece.runs().strip());
                }
                for (SplineJunctions.Patch patch : trims.patches()) {
                    if (patch instanceof SplineJunctions.Crossing crossing) {
                        parts.crossing(path, crossing, -half, half, lift, look, textures);
                    } else if (patch instanceof SplineJunctions.Fillet fillet) {
                        parts.fillet(fillet, lift + FILLET_LAYERS * LAYER, look, textures);
                    }
                }
                float outer = half;
                if (spline.footpaths != MapSpline.Sides.NONE) {
                    String texture = spline.footpathTexture != null ? spline.footpathTexture
                            : textures.defaultTexture(MapSpline.Kind.FOOTPATH);
                    Look footpath = new Look(texture, false, 0f);
                    float raise = MapSpline.Kind.FOOTPATH.height;
                    if (spline.footpaths.left()) {
                        for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.LEFT_FOOTPATH)) {
                            parts.slab(piece, -half - spline.footpathWidth, -half, raise, footpath, Look.plain(), true, false);
                        }
                    }
                    if (spline.footpaths.right()) {
                        for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.RIGHT_FOOTPATH)) {
                            parts.slab(piece, half, half + spline.footpathWidth, raise, footpath, Look.plain(), true, false);
                        }
                    }
                    outer += spline.footpathWidth;
                }
                if (spline.edges != MapSpline.Sides.NONE && spline.edgeKind != MapSpline.Kind.MODELS) {
                    String texture = spline.edgeTexture != null ? spline.edgeTexture : textures.defaultTexture(spline.edgeKind);
                    float thickness = spline.edgeKind == MapSpline.Kind.WALL ? EDGE_WALL : POST;
                    // On the outer edge of a footpath, or on the road's own edge.
                    if (spline.edges.left()) {
                        float edge = spline.footpaths.left() ? outer : half;
                        for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.LEFT_EDGE)) {
                            edge(parts, piece, spline, -edge, -edge + thickness, texture);
                        }
                    }
                    if (spline.edges.right()) {
                        float edge = spline.footpaths.right() ? outer : half;
                        for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.RIGHT_EDGE)) {
                            edge(parts, piece, spline, edge - thickness, edge, texture);
                        }
                    }
                }
            }
            case FOOTPATH -> {
                for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.SURFACE)) {
                    parts.slab(piece, -half, half, spline.height, new Look(main, spline.turned, spline.textureSize),
                            Look.plain(), true, false);
                }
            }
            case WALL -> {
                Look look = new Look(main, spline.turned, wallPictureSize(spline));
                for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.SURFACE)) {
                    parts.wall(piece, -half, half, spline.height, look);
                }
                for (SplineJunctions.Patch patch : trims.patches()) {
                    if (patch instanceof SplineJunctions.Cap cap) {
                        parts.cap(cap, look);
                    }
                }
            }
            case RAILING -> {
                Look look = new Look(main, spline.turned, spline.textureSize > 0f ? spline.textureSize : 1f);
                for (SplineJunctions.Piece piece : trims.pieces(path, SplineJunctions.Part.SURFACE)) {
                    parts.railing(piece, 0f, spline.height, Math.max(POST, spline.width), look);
                }
                for (SplineJunctions.Patch patch : trims.patches()) {
                    if (patch instanceof SplineJunctions.Post post) {
                        parts.junctionPost(post, look);
                    }
                }
            }
            case MODELS -> {
                // Its models are the map's own objects.
            }
        }
        return parts.finish();
    }

    private static void edge(Parts parts, SplineJunctions.Piece piece, MapSpline spline, float s0, float s1, String texture) {
        if (spline.edgeKind == MapSpline.Kind.WALL) {
            parts.wall(piece, s0, s1, spline.edgeHeight, new Look(texture, false, Math.max(0.5f, spline.edgeHeight)));
        } else {
            parts.railing(piece, (s0 + s1) * 0.5f, spline.edgeHeight, POST, new Look(texture, false, 1f));
        }
    }

    /** How many metres along a wall one repeat of its picture takes: as tall as the picture is fitted, or as set. */
    static float wallPictureSize(MapSpline spline) {
        return spline.textureSize > 0f ? spline.textureSize : Math.max(0.5f, spline.height);
    }

    /** How many metres along a road, path or footpath one repeat of its picture takes: as wide as it is, or as set. */
    static float surfacePictureSize(MapSpline spline) {
        return spline.textureSize > 0f ? spline.textureSize : Math.max(0.05f, spline.width);
    }

    /**
     * The picture in the same set as a road's straight piece ({@code tex/road/road_straight_ew/road_straight_ew_albedo.png})
     * named {@code member} ({@code cross}, {@code t_nes}), or null when the texture isn't one of a set.
     */
    public static String familyMember(String texture, String member) {
        if (texture == null) {
            return null;
        }
        Matcher matcher = FAMILY.matcher(texture);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1) + matcher.group(2) + "_" + member + "/" + matcher.group(2) + "_" + member + matcher.group(3);
    }

    private static final Pattern FAMILY = Pattern.compile("^(.*?)([^/]+)_straight_(?:ew|ns)/\\2_straight_(?:ew|ns)(_albedo\\.png)$");

    /**
     * How a surface wears its texture.
     *
     * @param size how many metres one picture covers, or 0 to fit the surface's width
     */
    private record Look(String texture, boolean turned, float size) {
        static Look plain() {
            return new Look(PLAIN, false, 1f);
        }

        Vector3f tint() {
            return PLAIN.equals(texture) ? CURB : WHITE;
        }
    }

    /** The surfaces built so far, by texture, and the colliders. */
    private static final class Parts {
        final WorldMap map;
        final Map<String, TexturedMeshBuilder> builders = new LinkedHashMap<>();
        final List<AABB> colliders = new ArrayList<>();

        Parts(WorldMap map) {
            this.map = map;
        }

        Built finish() {
            Map<String, TexturedMeshData> meshes = new LinkedHashMap<>();
            builders.forEach((texture, builder) -> {
                if (!builder.isEmpty()) {
                    meshes.put(texture, builder.buildData());
                }
            });
            return new Built(meshes, colliders);
        }

        TexturedMeshBuilder builder(Look look) {
            return builders.computeIfAbsent(look.texture(), key -> new TexturedMeshBuilder());
        }

        float ground(float x, float z) {
            return map.heightAt(Math.clamp(x, 0f, map.worldWidth()), Math.clamp(z, 0f, map.worldDepth()));
        }

        /** The ground along the line at an offset from it, sample by sample, eased over {@link #SMOOTHING}. */
        float[] profile(SplinePath path, float offset, boolean smooth) {
            int n = path.size();
            float[] raw = new float[n];
            for (int k = 0; k < n; k++) {
                raw[k] = ground(path.offsetX(k, offset), path.offsetZ(k, offset));
            }
            if (!smooth) {
                return raw;
            }
            float[] eased = new float[n];
            for (int k = 0; k < n; k++) {
                float sum = 0f;
                int count = 0;
                for (int i = path.sampleAt(path.along(k) - SMOOTHING); i < n && path.along(i) <= path.along(k) + SMOOTHING; i++) {
                    sum += raw[i];
                    count++;
                }
                eased[k] = count == 0 ? raw[k] : sum / count;
            }
            return eased;
        }

        /**
         * Eases the ground a piece stands on to its junctions' ground over {@link #PIN_BLEND} m from each end that meets
         * one, so every arm of a junction arrives at the same height whatever the slope.
         */
        static void pin(SplineJunctions.Piece piece, float[] ground) {
            SplinePath path = piece.path();
            int n = path.size();
            if (piece.start().pinned()) {
                float at = piece.start().ground();
                for (int k = 0; k < n; k++) {
                    float d = path.along(k) - path.along(0);
                    if (d >= PIN_BLEND) {
                        break;
                    }
                    ground[k] = at + (ground[k] - at) * smooth(d / PIN_BLEND);
                }
            }
            if (piece.end().pinned()) {
                float at = piece.end().ground();
                for (int k = n - 1; k >= 0; k--) {
                    float d = path.along(n - 1) - path.along(k);
                    if (d >= PIN_BLEND) {
                        break;
                    }
                    ground[k] = at + (ground[k] - at) * smooth(d / PIN_BLEND);
                }
            }
        }

        static float smooth(float t) {
            t = Math.clamp(t, 0f, 1f);
            return t * t * (3f - 2f * t);
        }

        /** Texture coordinates for a point {@code across} metres in from a surface's left edge and {@code along} along it. */
        static float[] uv(Look look, float across, float along, float width) {
            float size = look.size() > 0f ? look.size() : Math.max(0.05f, width);
            float u = along / size;
            float v = look.size() > 0f ? across / size : across / Math.max(0.05f, width);
            // Turned, the picture's top is toward the line's far end, so it reads the right way up and its normal map
            // lights from the right side.
            return look.turned() ? new float[]{v, -u} : new float[]{u, v};
        }

        /** The way +u runs on a surface whose length runs along {@code dir} and width along {@code right}. */
        static Vector3f tangent(Look look, SplinePath path, int k) {
            return look.turned() ? new Vector3f(path.rightX(k), 0f, path.rightZ(k))
                    : new Vector3f(path.dirX(k), 0f, path.dirZ(k));
        }

        // --- Strips ---------------------------------------------------------------------------------------

        /**
         * A surface lying on the ground from {@code s0} to {@code s1} across the line, {@code lift} above it, its picture
         * running along it as {@code run} says.
         */
        void strip(SplinePath path, float s0, float s1, float lift, Look look, SplineJunctions.Run run) {
            float width = s1 - s0;
            grid(path, columns(s0, s1, null), lift, look,
                    (k, s) -> uv(look, s - s0, run.at(path.along(k)), width), k -> tangent(look, path, k));
        }

        /** Evenly spaced offsets across from {@code s0} to {@code s1}, about a cell apart, with {@code extra} among them. */
        List<Float> columns(float s0, float s1, List<Float> extra) {
            int count = Math.clamp((int) Math.ceil((s1 - s0) / map.cellSize()), 1, 64);
            List<Float> out = new ArrayList<>();
            for (int c = 0; c <= count; c++) {
                out.add(s0 + (s1 - s0) * c / count);
            }
            if (extra != null) {
                for (float s : extra) {
                    if (s > s0 + 1e-3f && s < s1 - 1e-3f) {
                        out.add(s);
                    }
                }
                out.sort(Float::compare);
                for (int k = out.size() - 1; k > 0; k--) {
                    if (out.get(k) - out.get(k - 1) < 1e-3f) {
                        out.remove(k);
                    }
                }
            }
            return out;
        }

        /** Where a vertex's texture coordinates come from: sample {@code k} along, {@code s} across. */
        @FunctionalInterface
        interface Mapping {
            float[] uv(int k, float s);
        }

        /** A surface lying on the ground along a line, a vertex at each sample and each column across. */
        void grid(SplinePath path, List<Float> columns, float lift, Look look, Mapping mapping,
                  java.util.function.IntFunction<Vector3f> tangents) {
            TexturedMeshBuilder out = builder(look);
            int n = path.size();
            int row = columns.size();
            int first = out.vertexCount();
            Vector3f normal = new Vector3f();
            for (int k = 0; k < n; k++) {
                Vector3f tangent = tangents.apply(k);
                for (float s : columns) {
                    float x = path.offsetX(k, s);
                    float z = path.offsetZ(k, s);
                    float[] uv = mapping.uv(k, s);
                    map.normalAt(Math.clamp(x, 0f, map.worldWidth()), Math.clamp(z, 0f, map.worldDepth()), normal);
                    out.addVertex(new Vector3f(x, ground(x, z) + lift, z), normal, uv[0], uv[1], look.tint(), tangent);
                }
            }
            for (int k = 0; k + 1 < n; k++) {
                for (int c = 0; c + 1 < row; c++) {
                    int a = first + k * row + c;
                    quad(out, a, a + 1, a + 1 + row, a + row);
                }
            }
        }

        /**
         * The through road where others lead off it: its picture set's crossroads or T laid square over it, or its own
         * picture with the plain middle across the side the others lead off.
         */
        void crossing(SplinePath line, SplineJunctions.Crossing crossing, float s0, float s1, float lift, Look look,
                      SplineTextures textures) {
            SplinePath path = line.slice(crossing.from(), crossing.fromFrame(), crossing.to(), crossing.toFrame());
            if (path == null) {
                return;
            }
            String piece = crossing.family() ? familyMember(look.texture(), crossing.cross() ? "cross" : "t_nes") : null;
            if (piece != null && textures.exists(piece)) {
                Look set = new Look(piece, false, 0f);
                float across = 2f * Math.max(0.05f, crossing.halfAcross());
                float along = 2f * Math.max(0.05f, crossing.halfAlong());
                grid(path, columns(s0, s1, null), lift, set,
                        (k, s) -> new float[]{0.5f + crossing.lateral() * s / across,
                                0.5f + crossing.longitudinal() * (path.along(k) - crossing.at()) / along},
                        k -> new Vector3f(path.rightX(k) * crossing.lateral(), 0f, path.rightZ(k) * crossing.lateral()));
                return;
            }
            float width = s1 - s0;
            float acrossSize = look.size() > 0f ? look.size() : Math.max(0.05f, width);
            float alongSize = look.size() > 0f ? look.size() : Math.max(0.05f, width);
            float[] band = textures.plainBand(look.texture(), look.turned());
            List<Float> breaks = new ArrayList<>();
            if (band != null && band[1] - band[0] > 0.02f) {
                float period = band[1] - band[0];
                float end = (s1 - s0) / acrossSize;
                if (crossing.bandSide() > 0) {
                    for (float c = band[0]; c < end; c += period) {
                        breaks.add(s0 + c * acrossSize);
                    }
                } else if (crossing.bandSide() < 0) {
                    for (float c = band[1]; c > 0f; c -= period) {
                        breaks.add(s0 + c * acrossSize);
                    }
                } else {
                    for (float c = period; c < end; c += period) {
                        breaks.add(s0 + c * acrossSize);
                    }
                }
            } else {
                band = null;
            }
            float[] plain = band;
            grid(path, columns(s0, s1, breaks), lift, look, (k, s) -> {
                float c = plain == null ? (s - s0) / acrossSize : plainAcross((s - s0) / acrossSize, plain, crossing.bandSide());
                float u = crossing.run().at(path.along(k)) / alongSize;
                return look.turned() ? new float[]{c, -u} : new float[]{u, c};
            }, k -> tangent(look, path, k));
        }

        /**
         * Where across its picture a point {@code c} across a crossing reads: as it is on the side no road leads off, and
         * from the plain band, back and forth so it never jumps, on the side (or sides) they do.
         */
        static float plainAcross(float c, float[] band, int side) {
            float period = band[1] - band[0];
            if (side > 0) {
                return c <= band[0] ? c : band[0] + zigzag((c - band[0]) / period) * period;
            }
            if (side < 0) {
                return c >= band[1] ? c : band[1] - zigzag((band[1] - c) / period) * period;
            }
            return band[0] + zigzag(c / period) * period;
        }

        /** Up from 0 to 1, back down to 0, up again: a picture laid mirrored every other time, so it never jumps. */
        static float zigzag(float m) {
            float whole = (float) Math.floor(m);
            float f = m - whole;
            return ((int) whole & 1) == 0 ? f : 1f - f;
        }

        // --- Rounded corners ------------------------------------------------------------------------------

        /** Rings across a rounded corner's verge. */
        private static final int FILLET_COLUMNS = 3;
        /** About how far apart round a rounded corner its verge follows the ground, in metres. */
        private static final float FILLET_STEP = 0.25f;

        /**
         * A rounded corner ({@link SplineJunctions.Fillet}): the two roads' verges on that side bent round on one arc
         * tangent to both, each road's picture taking half of it; the plain picture beside each verge filling from the
         * arc in to the roads; and each verge run on straight from the arc as far as it is missing. Nothing when the
         * picture has no verge on those sides, or too wide a one, or the corner is too sharp for a bend to fit, or the
         * junction is laid in its picture set's own piece.
         */
        void fillet(SplineJunctions.Fillet fillet, float lift, Look look, SplineTextures textures) {
            String piece = fillet.family() ? familyMember(look.texture(), fillet.cross() ? "cross" : "t_nes") : null;
            if (piece != null && textures.exists(piece)) {
                return;
            }
            float[] verges = textures.verges(look.texture(), look.turned());
            if (verges == null) {
                return;
            }
            SplineJunctions.Bend a = fillet.through();
            SplineJunctions.Bend b = fillet.branch();
            float va = vergeWidth(a, verges);
            float vb = vergeWidth(b, verges);
            if (va <= 0f || vb <= 0f) {
                return;
            }
            float qa = a.half() - va;
            float qb = b.half() - vb;
            float cos = Math.clamp(a.dirX() * b.dirX() + a.dirZ() * b.dirZ(), -1f, 1f);
            float tanHalf = (float) Math.tan(Math.acos(cos) * 0.5);
            float widest = Math.max(va, vb);
            float radius = widest + BEND * 2f * Math.min(a.half(), b.half());
            // A sharp corner's bend would reach far along both roads: it is made tighter instead.
            radius = Math.min(radius, 1.5f * (a.half() + b.half()) * tanHalf);
            if (radius < widest * 1.15f) {
                return;
            }
            float[] centre = meet(a, qa + radius, b, qb + radius);
            float[] corner = meet(a, qa, b, qb);
            if (centre == null || corner == null || centre[0] <= 0f || centre[1] <= 0f) {
                return;
            }
            float jx = fillet.x();
            float jz = fillet.z();
            float cx = jx + a.dirX() * centre[0] + a.sideX() * (qa + radius);
            float cz = jz + a.dirZ() * centre[0] + a.sideZ() * (qa + radius);
            float kx = jx + a.dirX() * corner[0] + a.sideX() * qa;
            float kz = jz + a.dirZ() * corner[0] + a.sideZ() * qa;
            // Where the arc meets each road's verge line, as angles round its centre.
            float fromA = (float) Math.atan2(jz + a.dirZ() * centre[0] + a.sideZ() * qa - cz,
                    jx + a.dirX() * centre[0] + a.sideX() * qa - cx);
            float fromB = (float) Math.atan2(jz + b.dirZ() * centre[1] + b.sideZ() * qb - cz,
                    jx + b.dirX() * centre[1] + b.sideX() * qb - cx);
            float sweep = fromB - fromA;
            while (sweep > Math.PI) {
                sweep -= (float) (2.0 * Math.PI);
            }
            while (sweep < -Math.PI) {
                sweep += (float) (2.0 * Math.PI);
            }
            float middle = (va + vb) * 0.5f;
            bendHalf(a, va, middle, verges, jx, jz, cx, cz, radius, fromA, sweep * 0.5f, centre[0], kx, kz, lift, look);
            bendHalf(b, vb, middle, verges, jx, jz, cx, cz, radius, fromB, -sweep * 0.5f, centre[1], kx, kz, lift, look);
        }

        /** How wide a road's verge is on the corner's side, in metres: 0 for none, or one too wide to be a verge. */
        static float vergeWidth(SplineJunctions.Bend bend, float[] verges) {
            float share = bend.right() ? 1f - verges[3] : verges[0];
            return share <= 0.01f || share > WIDEST_VERGE ? 0f : share * 2f * bend.half();
        }

        /**
         * How far out along each of two roads (taken as straight from the junction) the lines {@code oa} and {@code ob}
         * across them toward the corner meet: {along a, along b}, or null when they run side by side.
         */
        static float[] meet(SplineJunctions.Bend a, float oa, SplineJunctions.Bend b, float ob) {
            float det = b.dirX() * a.dirZ() - a.dirX() * b.dirZ();
            if (Math.abs(det) < 1e-4f) {
                return null;
            }
            float rx = ob * b.sideX() - oa * a.sideX();
            float rz = ob * b.sideZ() - oa * a.sideZ();
            return new float[]{(b.dirX() * rz - rx * b.dirZ()) / det, (a.dirX() * rz - a.dirZ() * rx) / det};
        }

        /**
         * One road's half of a rounded corner: its verge round the arc from where it leaves its straight line to the
         * arc's middle, narrowing (or widening) to meet the other's there; its verge run on straight from the arc as far
         * as it is missing; and the plain beside its verge, mirrored out from the verge, filling from the arc to the
         * corner its verge line makes with the other's.
         *
         * @param verge  its verge's width; {@code middle} the width both meet at
         * @param from   the angle round the arc's centre where it leaves its line
         * @param sweep  the angle round to the arc's middle, signed
         * @param out    how far out along its line it leaves it
         * @param kx     the corner its verge line makes with the other's
         */
        void bendHalf(SplineJunctions.Bend bend, float verge, float middle, float[] verges, float jx, float jz, float cx,
                      float cz, float radius, float from, float sweep, float out, float kx, float kz, float lift, Look look) {
            TexturedMeshBuilder builder = builder(look);
            float width = 2f * bend.half();
            float edge = bend.right() ? verges[3] : verges[0];
            float outer = bend.right() ? 1f : 0f;
            float q = bend.half() - verge;
            int sense = bend.sense();
            Vector3f normal = new Vector3f();
            // Round the arc.
            int steps = Math.max(2, (int) Math.ceil(Math.abs(sweep) * radius / FILLET_STEP));
            int[][] ring = new int[steps + 1][FILLET_COLUMNS + 1];
            Vector3f[][] at = new Vector3f[steps + 1][FILLET_COLUMNS + 1];
            for (int i = 0; i <= steps; i++) {
                float f = (float) i / steps;
                float angle = from + sweep * f;
                float rx = (float) Math.cos(angle);
                float rz = (float) Math.sin(angle);
                float wide = lerp(verge, middle, f);
                float along = bend.along() + sense * (out - Math.abs(sweep * f) * (radius - wide * 0.5f));
                float u = bend.run().at(along) / width;
                float turn = Math.signum(sweep);
                Vector3f tangent = look.turned()
                        ? new Vector3f(bend.right() ? -rx : rx, 0f, bend.right() ? -rz : rz)
                        : new Vector3f(sense * turn * rz, 0f, -sense * turn * rx);
                for (int j = 0; j <= FILLET_COLUMNS; j++) {
                    float rho = radius - wide * j / FILLET_COLUMNS;
                    float x = cx + rx * rho;
                    float z = cz + rz * rho;
                    float c = lerp(edge, outer, (float) j / FILLET_COLUMNS);
                    at[i][j] = new Vector3f(x, ground(x, z) + lift, z);
                    ring[i][j] = vertex(builder, at[i][j], c, u, look, tangent, normal);
                }
            }
            for (int i = 0; i < steps; i++) {
                for (int j = 0; j < FILLET_COLUMNS; j++) {
                    triangle(builder, ring[i][j], ring[i + 1][j], ring[i + 1][j + 1], at[i][j], at[i + 1][j], at[i + 1][j + 1], UP);
                    triangle(builder, ring[i][j], ring[i + 1][j + 1], ring[i][j + 1], at[i][j], at[i + 1][j + 1], at[i][j + 1], UP);
                }
            }
            // The plain inside the arc: a fan from the corner of the verge lines, the picture mirrored out from the verge.
            float period = bend.right() ? verges[3] - verges[2] : verges[1] - verges[0];
            Vector3f straight = look.turned()
                    ? new Vector3f(-(bend.right() ? 1f : -1f) * bend.sideX(), 0f, -(bend.right() ? 1f : -1f) * bend.sideZ())
                    : new Vector3f(sense * bend.dirX(), 0f, sense * bend.dirZ());
            Vector3f apex = new Vector3f(kx, ground(kx, kz) + lift, kz);
            int apexIndex = plainVertex(builder, bend, apex, jx, jz, edge, period, look, straight, normal);
            int previous = -1;
            for (int i = 0; i <= steps; i++) {
                int index = plainVertex(builder, bend, at[i][0], jx, jz, edge, period, look, straight, normal);
                if (previous >= 0) {
                    triangle(builder, apexIndex, previous, index, apex, at[i - 1][0], at[i][0], UP);
                }
                previous = index;
            }
            // The verge run on straight from the arc, as far as it is missing.
            float slant = bend.clearX() * bend.sideX() + bend.clearZ() * bend.sideZ();
            float lean = Math.abs(slant) < 0.2f ? 0f : (bend.clearX() * bend.dirX() + bend.clearZ() * bend.dirZ()) / slant;
            float reach = bend.clear() + Math.max(q * lean, bend.half() * lean) + 0.05f;
            if (reach > out + 0.01f) {
                int count = Math.max(1, (int) Math.ceil((reach - out) / SplineMeshes.STEP));
                int[][] grid = new int[count + 1][FILLET_COLUMNS + 1];
                Vector3f[][] points = new Vector3f[count + 1][FILLET_COLUMNS + 1];
                for (int i = 0; i <= count; i++) {
                    float t = out + (reach - out) * i / count;
                    float u = bend.run().at(bend.along() + sense * t) / width;
                    for (int j = 0; j <= FILLET_COLUMNS; j++) {
                        float w = q + verge * j / FILLET_COLUMNS;
                        float x = jx + bend.dirX() * t + bend.sideX() * w;
                        float z = jz + bend.dirZ() * t + bend.sideZ() * w;
                        points[i][j] = new Vector3f(x, ground(x, z) + lift, z);
                        Vector3f tangent = look.turned()
                                ? new Vector3f((bend.right() ? 1f : -1f) * bend.sideX(), 0f, (bend.right() ? 1f : -1f) * bend.sideZ())
                                : new Vector3f(sense * bend.dirX(), 0f, sense * bend.dirZ());
                        grid[i][j] = vertex(builder, points[i][j], lerp(edge, outer, (float) j / FILLET_COLUMNS), u, look,
                                tangent, normal);
                    }
                }
                for (int i = 0; i < count; i++) {
                    for (int j = 0; j < FILLET_COLUMNS; j++) {
                        triangle(builder, grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], points[i][j], points[i + 1][j],
                                points[i + 1][j + 1], UP);
                        triangle(builder, grid[i][j], grid[i + 1][j + 1], grid[i][j + 1], points[i][j], points[i + 1][j + 1],
                                points[i][j + 1], UP);
                    }
                }
            }
        }

        /** A vertex of a road's picture: {@code c} across it and {@code u} repeats along it. */
        int vertex(TexturedMeshBuilder builder, Vector3f position, float c, float u, Look look, Vector3f tangent,
                   Vector3f normal) {
            map.normalAt(Math.clamp(position.x, 0f, map.worldWidth()), Math.clamp(position.z, 0f, map.worldDepth()), normal);
            return look.turned()
                    ? builder.addVertex(position, normal, c, -u, look.tint(), tangent)
                    : builder.addVertex(position, normal, u, c, look.tint(), tangent);
        }

        /**
         * A vertex of the plain inside a rounded corner: where it lies against the road's straight line, its picture
         * mirrored back from the verge's edge into the plain beside it.
         */
        int plainVertex(TexturedMeshBuilder builder, SplineJunctions.Bend bend, Vector3f position, float jx, float jz,
                        float edge, float period, Look look, Vector3f tangent, Vector3f normal) {
            float dx = position.x - jx;
            float dz = position.z - jz;
            float t = dx * bend.dirX() + dz * bend.dirZ();
            float w = dx * bend.sideX() + dz * bend.sideZ();
            float width = 2f * bend.half();
            float c = 0.5f + (bend.right() ? 1f : -1f) * w / width;
            if (period < 0.02f) {
                c = edge;
            } else if (bend.right() && c > edge) {
                c = edge - zigzag((c - edge) / period) * period;
            } else if (!bend.right() && c < edge) {
                c = edge + zigzag((edge - c) / period) * period;
            }
            float u = bend.run().at(bend.along() + bend.sense() * t) / width;
            return vertex(builder, position, c, u, look, tangent, normal);
        }

        // --- Slabs ----------------------------------------------------------------------------------------

        /**
         * A slab from {@code s0} to {@code s1} across a piece's line, its top {@code height} above the ground along its
         * middle and level across, sunk into the ground below: a footpath, or with {@code sides} the same texture as the
         * top, a wall.
         *
         * @param walkable  whether people walk on it: it collides in boxes a metre or so square, low enough to step
         *                  onto, rather than as one box a stretch
         * @param planarTop its top's picture laid square to the world rather than along it, so tops meeting at any angle
         *                  run on into one another
         */
        void slab(SplineJunctions.Piece piece, float s0, float s1, float height, Look top, Look sides, boolean walkable,
                  boolean planarTop) {
            SplinePath path = piece.path();
            float middle = (s0 + s1) * 0.5f;
            float[] centre = profile(path, middle, !walkable);
            pin(piece, centre);
            float[] tops = new float[path.size()];
            float[] bottoms = new float[path.size()];
            for (int k = 0; k < tops.length; k++) {
                tops[k] = centre[k] + height;
                float low = Math.min(centre[k], Math.min(ground(path.offsetX(k, s0), path.offsetZ(k, s0)),
                        ground(path.offsetX(k, s1), path.offsetZ(k, s1))));
                bottoms[k] = low - SINK;
            }
            box(path, s0, s1, tops, bottoms, top, sides, false, piece.runs(), planarTop, !piece.start().joined(),
                    !piece.end().joined());
            if (walkable) {
                walkableColliders(path, s0, s1, tops, bottoms);
            } else {
                solidColliders(path, s0, s1, tops, bottoms);
            }
        }

        /** A wall: a slab with its texture on every face, fitted to its height, its top laid square to the world. */
        void wall(SplineJunctions.Piece piece, float s0, float s1, float height, Look look) {
            slab(piece, s0, s1, height, look, look, false, true);
        }

        /**
         * A slab's faces along the line: its top, its sides, its underside when it hangs in the air, and its ends on
         * an open line where they meet nothing.
         */
        void box(SplinePath path, float s0, float s1, float[] tops, float[] bottoms, Look top, Look sides, boolean underside,
                 SplineJunctions.Runs runs, boolean planarTop, boolean capStart, boolean capEnd) {
            int n = path.size();
            float width = s1 - s0;
            // The top, level across.
            TexturedMeshBuilder out = builder(top);
            int first = out.vertexCount();
            float topSize = top.size() > 0f ? top.size() : 1f;
            for (int k = 0; k < n; k++) {
                Vector3f normal = topNormal(path, k, tops);
                Vector3f tangent = planarTop ? new Vector3f(1f, 0f, 0f) : tangent(top, path, k);
                for (float s : new float[]{s0, s1}) {
                    float x = path.offsetX(k, s);
                    float z = path.offsetZ(k, s);
                    float[] uv = planarTop ? new float[]{x / topSize, z / topSize}
                            : uv(top, s - s0, runs.strip().at(path.along(k)), width);
                    out.addVertex(new Vector3f(x, tops[k], z), normal, uv[0], uv[1], top.tint(), tangent);
                }
            }
            for (int k = 0; k + 1 < n; k++) {
                int a = first + k * 2;
                quad(out, a, a + 1, a + 3, a + 2);
            }
            // The sides, up from the bottom: the left side faces left and the right side right. Each picture runs along
            // its own side as far as that side really reaches, so it meets the side round a corner from it exactly.
            TexturedMeshBuilder side = builder(sides);
            float size = sides.size() > 0f ? sides.size() : 1f;
            for (int which = 0; which < 2; which++) {
                float s = which == 0 ? s0 : s1;
                float facing = which == 0 ? -1f : 1f;
                SplineJunctions.Run run = which == 0 ? runs.left() : runs.right();
                int start = side.vertexCount();
                for (int k = 0; k < n; k++) {
                    Vector3f normal = new Vector3f(path.rightX(k) * facing, 0f, path.rightZ(k) * facing);
                    Vector3f tangent = new Vector3f(path.dirX(k) * facing, 0f, path.dirZ(k) * facing);
                    float x = path.offsetX(k, s);
                    float z = path.offsetZ(k, s);
                    float u = run.at(path.alongAt(k, s)) / size * facing;
                    side.addVertex(new Vector3f(x, bottoms[k], z), normal, u, (tops[k] - bottoms[k]) / size, sides.tint(), tangent);
                    side.addVertex(new Vector3f(x, tops[k], z), normal, u, 0f, sides.tint(), tangent);
                }
                for (int k = 0; k + 1 < n; k++) {
                    int b = start + k * 2;
                    if (which == 0) {
                        quad(side, b, b + 1, b + 3, b + 2);
                    } else {
                        quad(side, b, b + 2, b + 3, b + 1);
                    }
                }
            }
            if (underside) {
                int start = side.vertexCount();
                Vector3f down = new Vector3f(0f, -1f, 0f);
                for (int k = 0; k < n; k++) {
                    Vector3f tangent = new Vector3f(path.dirX(k), 0f, path.dirZ(k));
                    for (float s : new float[]{s0, s1}) {
                        side.addVertex(new Vector3f(path.offsetX(k, s), bottoms[k], path.offsetZ(k, s)), down,
                                path.along(k) / size, (s - s0) / size, sides.tint(), tangent);
                    }
                }
                for (int k = 0; k + 1 < n; k++) {
                    int a = start + k * 2;
                    quad(side, a, a + 2, a + 3, a + 1);
                }
            }
            if (!path.closed()) {
                if (capStart) {
                    cap(side, path, 0, s0, s1, tops[0], bottoms[0], -1f, sides);
                }
                if (capEnd) {
                    cap(side, path, n - 1, s0, s1, tops[n - 1], bottoms[n - 1], 1f, sides);
                }
            }
        }

        /** The up of a slab's top at a sample: tilted along the line as its top climbs, level across. */
        static Vector3f topNormal(SplinePath path, int k, float[] tops) {
            int a = Math.max(0, k - 1);
            int b = Math.min(path.size() - 1, k + 1);
            float run = path.along(b) - path.along(a);
            if (run <= 1e-4f) {
                return new Vector3f(UP);
            }
            float rise = (tops[b] - tops[a]) / run;
            return new Vector3f(-path.dirX(k) * rise, 1f, -path.dirZ(k) * rise).normalize();
        }

        /** The end of a slab, facing back along the line at the start ({@code facing} -1) or on at the end. */
        void cap(TexturedMeshBuilder out, SplinePath path, int k, float s0, float s1, float top, float bottom, float facing,
                 Look look) {
            Vector3f normal = new Vector3f(path.dirX(k) * facing, 0f, path.dirZ(k) * facing);
            Vector3f tangent = new Vector3f(path.rightX(k) * -facing, 0f, path.rightZ(k) * -facing);
            float size = look.size() > 0f ? look.size() : 1f;
            float x0 = path.offsetX(k, s0);
            float z0 = path.offsetZ(k, s0);
            float x1 = path.offsetX(k, s1);
            float z1 = path.offsetZ(k, s1);
            // Across from left to right as seen from in front of it: from s0 at the start, from s1 at the end.
            float u0 = facing < 0f ? 0f : (s1 - s0) / size;
            float u1 = facing < 0f ? (s1 - s0) / size : 0f;
            float v = (top - bottom) / size;
            int a = out.addVertex(new Vector3f(x0, bottom, z0), normal, u0, v, look.tint(), tangent);
            int b = out.addVertex(new Vector3f(x1, bottom, z1), normal, u1, v, look.tint(), tangent);
            int c = out.addVertex(new Vector3f(x1, top, z1), normal, u1, 0f, look.tint(), tangent);
            int d = out.addVertex(new Vector3f(x0, top, z0), normal, u0, 0f, look.tint(), tangent);
            if (facing < 0f) {
                quad(out, a, b, c, d);
            } else {
                quad(out, a, d, c, b);
            }
        }

        /**
         * The top of walls' meeting place, level at the highest of them, laid square to the world as their tops are, with
         * a riser down to each lower wall's top.
         */
        void cap(SplineJunctions.Cap cap, Look look) {
            List<SplineJunctions.Corner> corners = cap.corners();
            int n = corners.size();
            if (n < 3) {
                return;
            }
            float top = cap.ground() + cap.height();
            float size = look.size() > 0f ? look.size() : 1f;
            TexturedMeshBuilder out = builder(look);
            float cx = 0f;
            float cz = 0f;
            float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (SplineJunctions.Corner corner : corners) {
                cx += corner.x() / n;
                cz += corner.z() / n;
                minX = Math.min(minX, corner.x());
                minZ = Math.min(minZ, corner.z());
                maxX = Math.max(maxX, corner.x());
                maxZ = Math.max(maxZ, corner.z());
            }
            Vector3f east = new Vector3f(1f, 0f, 0f);
            Vector3f middle = new Vector3f(cx, top, cz);
            int centre = out.addVertex(middle, UP, cx / size, cz / size, look.tint(), east);
            Vector3f[] points = new Vector3f[n];
            int[] index = new int[n];
            for (int k = 0; k < n; k++) {
                SplineJunctions.Corner corner = corners.get(k);
                points[k] = new Vector3f(corner.x(), top, corner.z());
                index[k] = out.addVertex(points[k], UP, corner.x() / size, corner.z() / size, look.tint(), east);
            }
            for (int k = 0; k < n; k++) {
                int next = (k + 1) % n;
                triangle(out, centre, index[k], index[next], middle, points[k], points[next], UP);
            }
            // A riser where a lower wall meets the cap: across that wall's end, from its top up to the cap's.
            for (int k = 0; k < n; k++) {
                float low = cap.ground() + cap.sideHeights().get(k);
                if (low >= top - 1e-3f) {
                    continue;
                }
                SplineJunctions.Corner a = corners.get(k);
                SplineJunctions.Corner b = corners.get((k + 1) % n);
                float ex = b.x() - a.x();
                float ez = b.z() - a.z();
                float length = (float) Math.hypot(ex, ez);
                if (length < 1e-3f) {
                    continue;
                }
                Vector3f normal = new Vector3f(ez, 0f, -ex).normalize();
                if (normal.x * ((a.x() + b.x()) * 0.5f - cx) + normal.z * ((a.z() + b.z()) * 0.5f - cz) < 0f) {
                    normal.negate();
                }
                Vector3f tangent = new Vector3f(ex / length, 0f, ez / length);
                Vector3f[] quad = {new Vector3f(a.x(), low, a.z()), new Vector3f(b.x(), low, b.z()),
                        new Vector3f(b.x(), top, b.z()), new Vector3f(a.x(), top, a.z())};
                float v = (top - low) / size;
                facing(out, normal, tangent, look.tint(), quad, new float[][]{{0f, v}, {length / size, v}, {length / size, 0f}, {0f, 0f}});
            }
            colliders.add(new AABB(minX, cap.ground() - SINK, minZ, maxX, top, maxZ));
        }

        // --- Railings -------------------------------------------------------------------------------------

        /**
         * Posts every {@link #POST_SPACING} m at {@code offset} from a piece's line, {@code height} tall, and two rails;
         * no post at an end that meets a junction, which has its own.
         */
        void railing(SplineJunctions.Piece piece, float offset, float height, float post, Look look) {
            SplinePath path = piece.path();
            float[] ground = profile(path, offset, true);
            pin(piece, ground);
            int n = path.size();
            boolean startPost = path.closed() || !piece.start().joined();
            boolean endPost = path.closed() || !piece.end().joined();
            // Rails: a handrail along the tops of the posts and one halfway down.
            for (float rail : new float[]{height - RAIL_HEIGHT, height * 0.45f}) {
                float[] tops = new float[n];
                float[] bottoms = new float[n];
                for (int k = 0; k < n; k++) {
                    bottoms[k] = ground[k] + rail;
                    tops[k] = bottoms[k] + RAIL_HEIGHT;
                }
                box(path, offset - RAIL_WIDTH * 0.5f, offset + RAIL_WIDTH * 0.5f, tops, bottoms, look, look, true,
                        SplineJunctions.Runs.NATURAL, false, startPost, endPost);
            }
            // Posts, an even number of spaces along each stretch so one stands at each end and at each corner.
            float length = path.length();
            int spaces = Math.max(1, Math.round(length / POST_SPACING));
            int posts = path.closed() ? spaces : spaces + 1;
            for (int p = 0; p < posts; p++) {
                if (p == 0 && !startPost || p == posts - 1 && !path.closed() && !endPost) {
                    continue;
                }
                float at = path.start() + length * p / spaces;
                int k = Math.min(n - 1, path.sampleAt(at));
                int next = Math.min(n - 1, k + 1);
                float span = path.along(next) - path.along(k);
                float t = span <= 1e-4f ? 0f : (at - path.along(k)) / span;
                float x = lerp(path.offsetX(k, offset), path.offsetX(next, offset), t);
                float z = lerp(path.offsetZ(k, offset), path.offsetZ(next, offset), t);
                float top = lerp(ground[k], ground[next], t) + height;
                float bottom = ground(x, z) - SINK;
                post(builder(look), x, z, path.dirX(k), path.dirZ(k), post, bottom, top, look);
            }
            float half = Math.max(post, RAIL_WIDTH) * 0.5f;
            float[] tops = new float[n];
            float[] bottoms = new float[n];
            for (int k = 0; k < n; k++) {
                tops[k] = ground[k] + height;
                bottoms[k] = ground[k] - SINK;
            }
            solidColliders(path, offset - half, offset + half, tops, bottoms);
        }

        /** The one post railings meeting at a junction share. */
        void junctionPost(SplineJunctions.Post post, Look look) {
            float top = post.ground() + post.height();
            float bottom = Math.min(post.ground(), ground(post.x(), post.z())) - SINK;
            post(builder(look), post.x(), post.z(), post.dirX(), post.dirZ(), post.size(), bottom, top, look);
            float half = post.size() * 0.5f;
            colliders.add(new AABB(post.x() - half, bottom, post.z() - half, post.x() + half, top, post.z() + half));
        }

        /** An upright square post turned to the line, its four sides and its top. */
        void post(TexturedMeshBuilder out, float x, float z, float dx, float dz, float side, float bottom, float top, Look look) {
            float h = side * 0.5f;
            float rx = -dz;
            float rz = dx;
            // Corners anticlockwise seen from above: back left, back right, front right, front left.
            float[][] corners = {
                    {x - dx * h - rx * h, z - dz * h - rz * h},
                    {x - dx * h + rx * h, z - dz * h + rz * h},
                    {x + dx * h + rx * h, z + dz * h + rz * h},
                    {x + dx * h - rx * h, z + dz * h - rz * h}};
            float height = top - bottom;
            for (int k = 0; k < 4; k++) {
                float[] a = corners[k];
                float[] b = corners[(k + 1) % 4];
                // Each side's outward normal: perpendicular to it, away from the middle.
                float ex = b[0] - a[0];
                float ez = b[1] - a[1];
                Vector3f normal = new Vector3f(ez, 0f, -ex).normalize();
                if (normal.x * ((a[0] + b[0]) * 0.5f - x) + normal.z * ((a[1] + b[1]) * 0.5f - z) < 0f) {
                    normal.negate();
                }
                Vector3f tangent = new Vector3f(ex, 0f, ez).normalize();
                Vector3f[] quad = {new Vector3f(a[0], bottom, a[1]), new Vector3f(b[0], bottom, b[1]),
                        new Vector3f(b[0], top, b[1]), new Vector3f(a[0], top, a[1])};
                float[][] uvs = {{0f, height}, {side, height}, {side, 0f}, {0f, 0f}};
                facing(out, normal, tangent, look.tint(), quad, uvs);
            }
            Vector3f tangent = new Vector3f(dx, 0f, dz);
            Vector3f[] lid = new Vector3f[4];
            float[][] uvs = new float[4][];
            for (int k = 0; k < 4; k++) {
                lid[k] = new Vector3f(corners[k][0], top, corners[k][1]);
                uvs[k] = new float[]{k == 1 || k == 2 ? side : 0f, k >= 2 ? side : 0f};
            }
            facing(out, UP, tangent, look.tint(), lid, uvs);
        }

        // --- Colliders ------------------------------------------------------------------------------------

        /** Boxes a sample apart covering a slab from bottom to top: a wall or a railing. */
        void solidColliders(SplinePath path, float s0, float s1, float[] tops, float[] bottoms) {
            for (int k = 0; k + 1 < path.size(); k++) {
                colliders.add(cover(path, k, k + 1, s0, s1, Math.min(bottoms[k], bottoms[k + 1]),
                        Math.max(tops[k], tops[k + 1])));
            }
        }

        /** Boxes about a metre square over a footpath, each as high as its top, so people step up onto it. */
        void walkableColliders(SplinePath path, float s0, float s1, float[] tops, float[] bottoms) {
            int across = Math.max(1, (int) Math.ceil((s1 - s0) / 1f));
            for (int k = 0; k + 1 < path.size(); k++) {
                float bottom = Math.min(bottoms[k], bottoms[k + 1]);
                float top = Math.max(tops[k], tops[k + 1]);
                for (int c = 0; c < across; c++) {
                    float a = s0 + (s1 - s0) * c / across;
                    float b = s0 + (s1 - s0) * (c + 1) / across;
                    colliders.add(cover(path, k, k + 1, a, b, bottom, top));
                }
            }
        }

        static AABB cover(SplinePath path, int k0, int k1, float s0, float s1, float bottom, float top) {
            float minX = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            for (int k : new int[]{k0, k1}) {
                for (float s : new float[]{s0, s1}) {
                    float x = path.offsetX(k, s);
                    float z = path.offsetZ(k, s);
                    minX = Math.min(minX, x);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxZ = Math.max(maxZ, z);
                }
            }
            return new AABB(minX, bottom, minZ, maxX, top, maxZ);
        }
    }

    /** Two triangles over corners given anticlockwise as seen from the side they face. */
    static void quad(TexturedMeshBuilder out, int a, int b, int c, int d) {
        out.addTriangle(a, b, c);
        out.addTriangle(a, c, d);
    }

    /** One triangle facing {@code normal}, whichever way round its corners were given. */
    private static void triangle(TexturedMeshBuilder out, int ia, int ib, int ic, Vector3f a, Vector3f b, Vector3f c,
                                 Vector3f normal) {
        Vector3f made = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        if (made.lengthSquared() < 1e-12f) {
            return;
        }
        if (made.dot(normal) >= 0f) {
            out.addTriangle(ia, ib, ic);
        } else {
            out.addTriangle(ia, ic, ib);
        }
    }

    /** A flat quad facing {@code normal}, whichever way round its corners were given. */
    private static void facing(TexturedMeshBuilder out, Vector3f normal, Vector3f tangent, Vector3f tint, Vector3f[] corners,
                               float[][] uvs) {
        int[] index = new int[4];
        for (int k = 0; k < 4; k++) {
            index[k] = out.addVertex(corners[k], normal, uvs[k][0], uvs[k][1], tint, tangent);
        }
        Vector3f made = new Vector3f(corners[1]).sub(corners[0]).cross(new Vector3f(corners[2]).sub(corners[0]));
        if (made.dot(normal) >= 0f) {
            quad(out, index[0], index[1], index[2], index[3]);
        } else {
            quad(out, index[0], index[3], index[2], index[1]);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // --- The ground under it ----------------------------------------------------------------------------

    /**
     * Grades the ground under a spline: level across it, and along it climbing from the ground's height at each of its
     * points to the next on a smooth curve that never rises above or dips below the two (a monotone cubic), with banks
     * no steeper than {@value #BANK_SLOPE} up or down to the land beside it, out to {@value #BANK_REACH} m. The ground at its points is left at its own height,
     * so grading again after an edit changes nothing that wasn't moved; more points follow the land more closely.
     * Returns the cells changed as {minX, minZ, maxX, maxZ}, or null when there is nothing to grade.
     */
    public static int[] level(WorldMap map, MapSpline spline) {
        if (spline.kind == MapSpline.Kind.MODELS) {
            return null; // its models level their own ground (SplineModels)
        }
        SplinePath path = spline.drawable() ? SplinePath.of(spline, Math.min(STEP, map.cellSize())) : null;
        if (path == null) {
            return null;
        }
        int n = path.size();
        float[] profile = grade(map, path);
        float flat = spline.halfReach() + 0.5f;
        float reach = flat + BANK_REACH;
        float cell = map.cellSize();
        // The vertices the grading can reach, in a box round the whole line, nearest first along it.
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            minX = Math.min(minX, path.x(k));
            minZ = Math.min(minZ, path.z(k));
            maxX = Math.max(maxX, path.x(k));
            maxZ = Math.max(maxZ, path.z(k));
        }
        int i0 = Math.max(0, (int) Math.floor((minX - reach) / cell));
        int i1 = Math.min(map.width(), (int) Math.ceil((maxX + reach) / cell));
        int j0 = Math.max(0, (int) Math.floor((minZ - reach) / cell));
        int j1 = Math.min(map.depth(), (int) Math.ceil((maxZ + reach) / cell));
        if (i1 < i0 || j1 < j0) {
            return null;
        }
        int boxWidth = i1 - i0 + 1;
        float[] nearest = new float[boxWidth * (j1 - j0 + 1)];
        float[] target = new float[nearest.length];
        java.util.Arrays.fill(nearest, Float.MAX_VALUE);
        for (int k = 0; k + 1 < n; k++) {
            float ax = path.x(k);
            float az = path.z(k);
            float dx = path.x(k + 1) - ax;
            float dz = path.z(k + 1) - az;
            float lengthSquared = dx * dx + dz * dz;
            int a0 = Math.max(i0, (int) Math.floor((Math.min(ax, ax + dx) - reach) / cell));
            int a1 = Math.min(i1, (int) Math.ceil((Math.max(ax, ax + dx) + reach) / cell));
            int b0 = Math.max(j0, (int) Math.floor((Math.min(az, az + dz) - reach) / cell));
            int b1 = Math.min(j1, (int) Math.ceil((Math.max(az, az + dz) + reach) / cell));
            for (int j = b0; j <= b1; j++) {
                for (int i = a0; i <= a1; i++) {
                    float px = i * cell;
                    float pz = j * cell;
                    float t = lengthSquared == 0f ? 0f : Math.clamp(((px - ax) * dx + (pz - az) * dz) / lengthSquared, 0f, 1f);
                    float d = (float) Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
                    int v = (j - j0) * boxWidth + (i - i0);
                    if (d <= reach && d < nearest[v]) {
                        nearest[v] = d;
                        target[v] = profile[k] + (profile[k + 1] - profile[k]) * t;
                    }
                }
            }
        }
        // Level under it; beside it, cut down or filled up only where the land is steeper than a bank, so grading
        // ground that is graded already leaves it as it is.
        int columns = map.width() + 1;
        float[] heights = map.heights();
        int changedX0 = Integer.MAX_VALUE, changedZ0 = Integer.MAX_VALUE, changedX1 = -1, changedZ1 = -1;
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                int v = (j - j0) * boxWidth + (i - i0);
                if (nearest[v] == Float.MAX_VALUE) {
                    continue;
                }
                float rise = Math.max(0f, nearest[v] - flat) * BANK_SLOPE;
                int at = j * columns + i;
                float graded = Math.clamp(heights[at], target[v] - rise, target[v] + rise);
                if (graded != heights[at]) {
                    heights[at] = graded;
                    changedX0 = Math.min(changedX0, i);
                    changedZ0 = Math.min(changedZ0, j);
                    changedX1 = Math.max(changedX1, i);
                    changedZ1 = Math.max(changedZ1, j);
                }
            }
        }
        return changedX1 < 0 ? null : new int[]{changedX0, changedZ0, changedX1, changedZ1};
    }

    /** How steeply graded ground beside a spline may rise to the land or fall to it: about 1 in 1.7. */
    public static final float BANK_SLOPE = 0.6f;
    /** How far beyond its edges a spline's grading reaches the land at most. */
    public static final float BANK_REACH = 12f;

    /**
     * The height the ground is graded to at each sample along a line: through the ground at each of its points, a
     * Fritsch-Carlson monotone cubic between them by distance along it, round the join of a loop too.
     */
    static float[] grade(WorldMap map, SplinePath path) {
        int points = path.points();
        int knots = path.closed() ? points + 1 : points;
        float[] at = new float[knots];
        float[] height = new float[knots];
        for (int i = 0; i < knots; i++) {
            int point = i % points;
            at[i] = i == points ? path.length() : path.pointAlong(point);
            height[i] = map.heightAt(Math.clamp(path.pointX(point), 0f, map.worldWidth()),
                    Math.clamp(path.pointZ(point), 0f, map.worldDepth()));
        }
        float[] secant = new float[knots - 1];
        for (int i = 0; i + 1 < knots; i++) {
            float run = Math.max(1e-4f, at[i + 1] - at[i]);
            secant[i] = (height[i + 1] - height[i]) / run;
        }
        float[] slope = new float[knots];
        for (int i = 0; i < knots; i++) {
            boolean wraps = path.closed() && (i == 0 || i == knots - 1);
            float before = i > 0 ? secant[i - 1] : wraps ? secant[knots - 2] : secant[0];
            float after = i < knots - 1 ? secant[i] : wraps ? secant[0] : secant[knots - 2];
            slope[i] = before * after <= 0f ? 0f : (before + after) * 0.5f;
        }
        for (int i = 0; i + 1 < knots; i++) {
            if (secant[i] == 0f) {
                slope[i] = 0f;
                slope[i + 1] = 0f;
                continue;
            }
            float a = slope[i] / secant[i];
            float b = slope[i + 1] / secant[i];
            float size = a * a + b * b;
            if (size > 9f) {
                float tau = 3f / (float) Math.sqrt(size);
                slope[i] = tau * a * secant[i];
                slope[i + 1] = tau * b * secant[i];
            }
        }
        float[] out = new float[path.size()];
        int interval = 0;
        for (int k = 0; k < out.length; k++) {
            float along = path.along(k);
            while (interval < knots - 2 && along > at[interval + 1]) {
                interval++;
            }
            float run = Math.max(1e-4f, at[interval + 1] - at[interval]);
            float t = Math.clamp((along - at[interval]) / run, 0f, 1f);
            float t2 = t * t;
            float t3 = t2 * t;
            out[k] = (2f * t3 - 3f * t2 + 1f) * height[interval] + (t3 - 2f * t2 + t) * run * slope[interval]
                    + (-2f * t3 + 3f * t2) * height[interval + 1] + (t3 - t2) * run * slope[interval + 1];
        }
        return out;
    }

    /**
     * The cells a map's roads, paths and footpaths cover, a bit per cell row by row along z: for a game to keep its
     * tall grass, its encounters or anything else that belongs off the paths out of them. A cell counts once the
     * surface reaches most of the way across it.
     */
    public static java.util.BitSet covered(WorldMap map) {
        java.util.BitSet cells = new java.util.BitSet(map.width() * map.depth());
        float cell = map.cellSize();
        for (MapSpline spline : map.splines()) {
            if (!(spline.kind.flat() || spline.kind == MapSpline.Kind.FOOTPATH) || !spline.drawable()) {
                continue;
            }
            SplinePath path = SplinePath.of(spline, Math.min(STEP, cell));
            if (path == null) {
                continue;
            }
            float reach = (spline.kind.flat() ? spline.halfReach() : spline.width * 0.5f) + cell * 0.35f;
            for (int k = 0; k + 1 < path.size(); k++) {
                float ax = path.x(k);
                float az = path.z(k);
                float dx = path.x(k + 1) - ax;
                float dz = path.z(k + 1) - az;
                float lengthSquared = dx * dx + dz * dz;
                int i0 = Math.max(0, (int) Math.floor((Math.min(ax, ax + dx) - reach) / cell));
                int i1 = Math.min(map.width() - 1, (int) Math.floor((Math.max(ax, ax + dx) + reach) / cell));
                int j0 = Math.max(0, (int) Math.floor((Math.min(az, az + dz) - reach) / cell));
                int j1 = Math.min(map.depth() - 1, (int) Math.floor((Math.max(az, az + dz) + reach) / cell));
                for (int j = j0; j <= j1; j++) {
                    for (int i = i0; i <= i1; i++) {
                        float px = (i + 0.5f) * cell;
                        float pz = (j + 0.5f) * cell;
                        float t = lengthSquared == 0f ? 0f
                                : Math.clamp(((px - ax) * dx + (pz - az) * dz) / lengthSquared, 0f, 1f);
                        if (Math.hypot(px - (ax + dx * t), pz - (az + dz * t)) <= reach) {
                            cells.set(j * map.width() + i);
                        }
                    }
                }
            }
        }
        return cells;
    }

    /**
     * Takes up the props standing on a spline — trees, rocks, grass on a road just drawn through them — but not road
     * pieces, which the road network lays, any spline's models, nor anything marked to stay
     * ({@link MapObject#clearedBySpline}). Returns how many were taken.
     */
    public static int clearProps(WorldMap map, MapSpline spline) {
        SplinePath path = spline.drawable() ? SplinePath.of(spline, Math.min(STEP, map.cellSize())) : null;
        if (path == null) {
            return 0;
        }
        float reach = spline.halfReach() + 0.5f;
        int before = map.objects().size();
        map.objects().removeIf(object -> SplineClearing.clears(object)
                && path.nearest(object.x, object.z).distance() <= reach);
        return before - map.objects().size();
    }
}
