package org.zeronura.engine.worldmap;

import org.lwjgl.BufferUtils;
import org.zeronura.engine.render.Texture;
import org.zeronura.engine.render.WaterLook;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Which way a map's water runs, and how far each point of it lies from land: the picture the water shader reads to move
 * a river along its course and roll the sea's waves in to the shore ({@link org.zeronura.engine.render.WaterLook}).
 *
 * <p>Worked out from the water alone, so any map has it without being told: a cell holds water if it has water of its
 * own (a river or a lake, in the {@linkplain WorldMap#waterSurface water table}) or lies under the map's water level
 * (the sea). A river runs the shortest way through the water to the sea, so its flow is downhill on that distance; a
 * lake no river reaches lies still, and so does the middle of a wide one, where the current spreads out. A river is
 * followed across short gaps in its water ({@link #BRIDGE_GAP}), such as a bridge laid over it as ground.
 *
 * <p>One texel a cell, rows along x, the first row the cells at z 0: R and G the flow across x and z, a share of the
 * water's full speed (0.5 still), B how far the cell's middle lies into the water, by the smooth line of the shore
 * ({@link Shoreline}): from {@link Shoreline#INSIDE} metres into the land at 0 up to {@link #SHORE_RANGE} out at 1, so
 * read with the texture's filtering it is 0 along the very line {@link MapTerrain} cuts the land to; A 1 where the cell
 * has water of its own, or is land beside such water (where a smooth shore cuts the land back, the water drawn there
 * is the river's), 0 for the sea or other land.
 *
 * <p>The texture holds a second picture of the same size under that one, rows {@code depth} on ({@link #falls}): where
 * the falls come down and churn the water, and how high each cell's water (or ground) lies, so the shader can tell a
 * fall's lip from its foot.
 */
public final class WaterFlow {

    /** How far from land the distance in B reaches before it stops counting, in metres. */
    public static final float SHORE_RANGE = WaterLook.SHORE_RANGE;

    /**
     * The widest gap a river's course is followed across, metres: a bridge's deck laid over the river as ground, say,
     * leaves the water on either side apart, and the stretch upstream would otherwise lie still.
     */
    public static final float BRIDGE_GAP = 8f;

    /** The lowest level the second picture can say, metres; levels are kept in 32nds of a metre from here. */
    public static final float LEVEL_FLOOR = -1024f;
    /** How many steps of the second picture's levels make a metre. */
    public static final float LEVEL_STEPS = 32f;
    /** Drops smaller than this, metres, are steps in the water rather than falls: they churn nothing. */
    static final float LEAST_FALL = 0.5f;

    private static final int STRAIGHT = 10;
    private static final int DIAGONAL = 14;
    private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private WaterFlow() {
    }

    /**
     * The pictures, uploaded: the flow ({@link #compute}) with the falls ({@link #falls}) under it, twice as tall as the
     * map is deep; smoothly filtered and clamped, so the sea beyond the map's edge is as at the edge.
     */
    public static Texture texture(WorldMap map) {
        byte[] flow = compute(map);
        byte[] falls = falls(map);
        ByteBuffer buffer = BufferUtils.createByteBuffer(flow.length + falls.length);
        buffer.put(flow).put(falls).flip();
        return Texture.fromPixels(buffer, map.width(), map.depth() * 2);
    }

    /**
     * The second picture's texels, RGBA, GL-free: R how churned the water is by a fall coming down into it — white water
     * at its foot, settling over a few metres, further below a taller fall; G and B the level of the cell's water, or of
     * its ground where that lies higher (the level a fall beside it pours down to), in {@link #LEVEL_STEPS}ths of a metre
     * up from {@link #LEVEL_FLOOR}, high byte first; A 1 where the cell holds water, its own or the sea.
     *
     * <p>A fall is where {@link MapTerrain} draws one: down a side of a cell of a river's or lake's own water, to the cell
     * beside it whose water or ground lies more than {@link #LEAST_FALL} lower. Only a fall into water churns it.
     */
    public static byte[] falls(WorldMap map) {
        int width = map.width();
        int depth = map.depth();
        float cell = map.cellSize();
        int cells = width * depth;
        float[] level = new float[cells];
        boolean[] wet = new boolean[cells];
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                int k = cz * width + cx;
                float ground = map.tileHeight(cx, cz);
                float surface = map.waterSurface(cx, cz);
                // As the water mesh has it: a cell's own water, else the sea's level, where either lies over its ground.
                wet[k] = map.hasOwnWater(cx, cz) || map.hasWater() && surface > ground;
                level[k] = wet[k] ? surface : ground;
            }
        }
        float[] churn = new float[cells];
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                if (!map.hasOwnWater(cx, cz)) {
                    continue;
                }
                float surface = map.waterSurface(cx, cz);
                for (int[] side : sides) {
                    int nx = cx + side[0];
                    int nz = cz + side[1];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) {
                        continue;
                    }
                    int n = nz * width + nx;
                    float drop = surface - level[n];
                    if (!wet[n] || drop < LEAST_FALL) {
                        continue;
                    }
                    // White water spreads from the foot, the middle of the side the fall comes down, over the water
                    // at the foot's level: further, and thicker, below a taller fall.
                    float footX = (cx + 0.5f + side[0] * 0.5f) * cell;
                    float footZ = (cz + 0.5f + side[1] * 0.5f) * cell;
                    float reach = Math.clamp(1.6f + drop * 0.35f, 2.5f, 6f);
                    stir(churn, level, wet, width, depth, cell, footX, footZ, level[n], reach);
                }
            }
        }
        byte[] out = new byte[cells * 4];
        for (int k = 0; k < cells; k++) {
            int steps = Math.clamp(Math.round((level[k] - LEVEL_FLOOR) * LEVEL_STEPS), 0, 65535);
            out[k * 4] = unit(churn[k]);
            out[k * 4 + 1] = (byte) (steps >> 8);
            out[k * 4 + 2] = (byte) steps;
            out[k * 4 + 3] = (byte) (wet[k] ? 255 : 0);
        }
        return out;
    }

    /** Churns the water at one level round a fall's foot, keeping whichever fall churns each cell the most. */
    private static void stir(float[] churn, float[] level, boolean[] wet, int width, int depth, float cell, float footX,
                             float footZ, float footLevel, float reach) {
        int x0 = Math.max(0, (int) Math.floor((footX - reach) / cell));
        int x1 = Math.min(width - 1, (int) Math.floor((footX + reach) / cell));
        int z0 = Math.max(0, (int) Math.floor((footZ - reach) / cell));
        int z1 = Math.min(depth - 1, (int) Math.floor((footZ + reach) / cell));
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int k = z * width + x;
                if (!wet[k] || Math.abs(level[k] - footLevel) > 0.3f) {
                    continue;
                }
                float dx = (x + 0.5f) * cell - footX;
                float dz = (z + 0.5f) * cell - footZ;
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                churn[k] = Math.max(churn[k], 1f - smooth(reach * 0.3f, reach, distance));
            }
        }
    }

    /** The picture's texels, RGBA, GL-free; see the class note. */
    public static byte[] compute(WorldMap map) {
        int width = map.width();
        int depth = map.depth();
        float cell = map.cellSize();
        int cells = width * depth;
        boolean[] water = new boolean[cells];
        boolean[] own = new boolean[cells];
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                int k = cz * width + cx;
                own[k] = map.hasOwnWater(cx, cz);
                water[k] = own[k] || groundHeight(map, cx, cz) < map.waterLevel();
            }
        }
        float[] shore = shoreDistance(water, width, depth, cell);
        int[] toSea = distanceToSea(water, channel(own, water, width, depth, cell), own, width, depth);
        float[] flow = flow(own, toSea, shore, width, depth, cell);
        boolean[] dry = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            dry[k] = !water[k];
        }
        float[] line = Shoreline.values(water, dry, width, depth, cell);
        boolean[] beside = besideOwn(own, water, flow, width, depth);

        byte[] out = new byte[cells * 4];
        for (int k = 0; k < cells; k++) {
            out[k * 4] = unit(0.5f + 0.5f * flow[2 * k]);
            out[k * 4 + 1] = unit(0.5f + 0.5f * flow[2 * k + 1]);
            out[k * 4 + 2] = unit((line[k] + Shoreline.INSIDE) / (SHORE_RANGE + Shoreline.INSIDE));
            out[k * 4 + 3] = (byte) (own[k] || beside[k] ? 255 : 0);
        }
        return out;
    }

    /**
     * The land cells beside a river's or lake's own water (any of the eight round): where a smooth shore cuts the land
     * back, the water there is that water. Each is given the flow of the water beside it, averaged, into {@code flow}.
     */
    static boolean[] besideOwn(boolean[] own, boolean[] water, float[] flow, int width, int depth) {
        boolean[] beside = new boolean[width * depth];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int k = z * width + x;
                if (water[k]) {
                    continue;
                }
                float fx = 0f;
                float fz = 0f;
                int count = 0;
                for (int[] step : AROUND) {
                    int nx = x + step[0];
                    int nz = z + step[1];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth || !own[nz * width + nx]) {
                        continue;
                    }
                    int n = nz * width + nx;
                    fx += flow[2 * n];
                    fz += flow[2 * n + 1];
                    count++;
                }
                if (count > 0) {
                    beside[k] = true;
                    flow[2 * k] = fx / count;
                    flow[2 * k + 1] = fz / count;
                }
            }
        }
        return beside;
    }

    private static float groundHeight(WorldMap map, int cx, int cz) {
        if (map.isTiled()) {
            return map.tileHeight(cx, cz);
        }
        float cell = map.cellSize();
        return map.heightAt((cx + 0.5f) * cell, (cz + 0.5f) * cell);
    }

    private static byte unit(float value) {
        return (byte) Math.clamp(Math.round(Math.clamp(value, 0f, 1f) * 255f), 0, 255);
    }

    /**
     * How far each water cell's middle is from the nearest cell of land, in metres (0 on land), by the two-pass chamfer
     * transform: within a few percent of the true distance, in two sweeps.
     */
    static float[] shoreDistance(boolean[] water, int width, int depth, float cell) {
        int cells = width * depth;
        int[] d = new int[cells];
        int far = Integer.MAX_VALUE / 4;
        for (int k = 0; k < cells; k++) {
            d[k] = water[k] ? far : 0;
        }
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int k = z * width + x;
                if (d[k] == 0) {
                    continue;
                }
                int best = d[k];
                if (x > 0) best = Math.min(best, d[k - 1] + STRAIGHT);
                if (z > 0) {
                    best = Math.min(best, d[k - width] + STRAIGHT);
                    if (x > 0) best = Math.min(best, d[k - width - 1] + DIAGONAL);
                    if (x < width - 1) best = Math.min(best, d[k - width + 1] + DIAGONAL);
                }
                d[k] = best;
            }
        }
        for (int z = depth - 1; z >= 0; z--) {
            for (int x = width - 1; x >= 0; x--) {
                int k = z * width + x;
                if (d[k] == 0) {
                    continue;
                }
                int best = d[k];
                if (x < width - 1) best = Math.min(best, d[k + 1] + STRAIGHT);
                if (z < depth - 1) {
                    best = Math.min(best, d[k + width] + STRAIGHT);
                    if (x < width - 1) best = Math.min(best, d[k + width + 1] + DIAGONAL);
                    if (x > 0) best = Math.min(best, d[k + width - 1] + DIAGONAL);
                }
                d[k] = best;
            }
        }
        float[] metres = new float[cells];
        for (int k = 0; k < cells; k++) {
            // From the cell's middle to the land's edge, which is half a cell nearer than the land cell's middle.
            metres[k] = d[k] >= far ? SHORE_RANGE : Math.max(0f, (d[k] / (float) STRAIGHT - 0.5f) * cell);
        }
        return metres;
    }

    /**
     * Where a river's course runs: its own water, and the dry gaps up to {@link #BRIDGE_GAP} across between its
     * stretches (a closing: the water grown by half the gap, then shrunk back by as much), but never the sea.
     */
    static boolean[] channel(boolean[] own, boolean[] water, int width, int depth, float cell) {
        int cells = width * depth;
        float reach = BRIDGE_GAP * 0.5f;
        boolean[] dry = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            dry[k] = !own[k];
        }
        // How far each dry cell is from a river's own water; within reach, it is grown over.
        float[] fromOwn = shoreDistance(dry, width, depth, cell);
        boolean[] grown = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            grown[k] = own[k] || fromOwn[k] <= reach;
        }
        // Shrunk back: what stays more than the reach inside the grown water.
        float[] inside = shoreDistance(grown, width, depth, cell);
        boolean[] channel = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            channel[k] = own[k] || (grown[k] && inside[k] > reach - cell && !water[k]);
        }
        return channel;
    }

    /**
     * How far each cell with water of its own is from the sea going along the channel ({@link #channel}), in tenths of
     * a cell (8 ways round, diagonals 14); -1 where the sea can't be reached that way, or for any other cell. A bucket
     * queue, since the steps are small whole numbers.
     */
    static int[] distanceToSea(boolean[] water, boolean[] channel, boolean[] own, int width, int depth) {
        int cells = width * depth;
        int[] cost = new int[cells];
        Arrays.fill(cost, -1);
        // Buckets of cells by cost, kept as a ring: no step is longer than a diagonal.
        int ring = DIAGONAL + 1;
        IntQueue[] buckets = new IntQueue[ring];
        for (int i = 0; i < ring; i++) {
            buckets[i] = new IntQueue();
        }
        int pending = 0;
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int k = z * width + x;
                if (!water[k] || own[k]) {
                    continue;
                }
                // The sea cells a river runs into are where it starts from.
                for (int[] step : AROUND) {
                    int nx = x + step[0];
                    int nz = z + step[1];
                    if (nx >= 0 && nz >= 0 && nx < width && nz < depth && channel[nz * width + nx]) {
                        cost[k] = 0;
                        buckets[0].add(k);
                        pending++;
                        break;
                    }
                }
            }
        }
        int at = 0;
        while (pending > 0) {
            IntQueue bucket = buckets[at % ring];
            while (!bucket.isEmpty()) {
                int k = bucket.poll();
                pending--;
                if (cost[k] != at) {
                    continue;
                }
                int x = k % width;
                int z = k / width;
                for (int[] step : AROUND) {
                    int nx = x + step[0];
                    int nz = z + step[1];
                    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) {
                        continue;
                    }
                    int n = nz * width + nx;
                    if (!channel[n]) {
                        continue;
                    }
                    int next = at + (step[0] != 0 && step[1] != 0 ? DIAGONAL : STRAIGHT);
                    if (cost[n] < 0 || next < cost[n]) {
                        cost[n] = next;
                        buckets[next % ring].add(n);
                        pending++;
                    }
                }
            }
            at++;
        }
        for (int k = 0; k < cells; k++) {
            if (!own[k]) {
                cost[k] = -1;
            }
        }
        return cost;
    }

    /** How many cells round each one its banks' way is gathered from ({@link #bankWays}), and how many times. */
    static final int BANK_REACH = 2;
    static final int BANK_PASSES = 2;

    /**
     * Each own-water cell's flow, x and z: along its banks, downstream, faster in the middle of a channel than at its
     * banks, and fading out where it widens into a lake. Smoothed, so a bend turns the current gradually.
     *
     * <p>Downstream is down the distance to the sea, but that distance only says which way along the banks to go, not
     * the way itself: it is counted in steps of a grid (straight and diagonal), so its slope keeps to a few set angles
     * whatever way a river really runs, and a river laid at any other angle would run slantwise across itself. Where
     * the banks don't say which way they run (a lake, where rivers meet), the slope of the distance is taken as it is.
     */
    static float[] flow(boolean[] own, int[] toSea, float[] shore, int width, int depth, float cell) {
        int cells = width * depth;
        float[] banks = bankWays(own, shore, width, depth);
        float[] flow = new float[2 * cells];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int k = z * width + x;
                if (toSea[k] < 0) {
                    continue;
                }
                float here = toSea[k];
                float gx = slope(toSea, here, x, z, 1, 0, width, depth);
                float gz = slope(toSea, here, x, z, 0, 1, width, depth);
                float length = (float) Math.sqrt(gx * gx + gz * gz);
                if (length < 1e-4f) {
                    continue;
                }
                float downX = -gx / length;
                float downZ = -gz / length;
                float c = banks[3 * k];
                float s = banks[3 * k + 1];
                float weight = banks[3 * k + 2];
                float sure = weight > 1e-6f ? (float) Math.sqrt(c * c + s * s) / weight : 0f;
                if (sure > 0f) {
                    // Half the doubled angle: the way the banks run, turned to go downstream.
                    double half = Math.atan2(s, c) * 0.5;
                    float alongX = (float) Math.cos(half);
                    float alongZ = (float) Math.sin(half);
                    if (alongX * downX + alongZ * downZ < 0f) {
                        alongX = -alongX;
                        alongZ = -alongZ;
                    }
                    float trust = smooth(0.3f, 0.6f, sure);
                    float wayX = downX + (alongX - downX) * trust;
                    float wayZ = downZ + (alongZ - downZ) * trust;
                    float wayLength = (float) Math.sqrt(wayX * wayX + wayZ * wayZ);
                    if (wayLength > 1e-4f) {
                        downX = wayX / wayLength;
                        downZ = wayZ / wayLength;
                    }
                }
                float d = shore[k];
                float speed = (0.35f + 0.65f * smooth(0f, 2f, d)) * (1f - smooth(5f, 12f, d));
                flow[2 * k] = downX * speed;
                flow[2 * k + 1] = downZ * speed;
            }
        }
        float[] next = new float[2 * cells];
        for (int pass = 0; pass < 3; pass++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    int k = z * width + x;
                    if (!own[k]) {
                        next[2 * k] = 0f;
                        next[2 * k + 1] = 0f;
                        continue;
                    }
                    float sx = 0f;
                    float sz = 0f;
                    int count = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            int nz = z + dz;
                            if (nx < 0 || nz < 0 || nx >= width || nz >= depth || !own[nz * width + nx]) {
                                continue;
                            }
                            int n = nz * width + nx;
                            sx += flow[2 * n];
                            sz += flow[2 * n + 1];
                            count++;
                        }
                    }
                    next[2 * k] = sx / count;
                    next[2 * k + 1] = sz / count;
                }
            }
            float[] swap = flow;
            flow = next;
            next = swap;
        }
        return flow;
    }

    /**
     * Which way each own-water cell's banks run, gathered from the cells round it: the way along the lines of equal
     * distance to land, as a doubled angle — so a way and its reverse count alike, and the far bank agrees with the near
     * one, though the distance climbs from one and falls to the other. Three numbers a cell: the doubled angle's x and z
     * weighted by how steeply the distance changes, and the sum of those weights; their length over that sum says how
     * sure the way is, 1 where every cell round runs the same way, toward 0 where they don't agree.
     */
    static float[] bankWays(boolean[] own, float[] shore, int width, int depth) {
        int cells = width * depth;
        float[] ways = new float[3 * cells];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int k = z * width + x;
                if (!own[k]) {
                    continue;
                }
                float gx = (shore[z * width + Math.min(x + 1, width - 1)] - shore[z * width + Math.max(x - 1, 0)]);
                float gz = (shore[Math.min(z + 1, depth - 1) * width + x] - shore[Math.max(z - 1, 0) * width + x]);
                float steep = (float) Math.sqrt(gx * gx + gz * gz);
                if (steep < 1e-5f) {
                    continue;
                }
                // Along the banks: the distance's slope turned a quarter, (-gz, gx); its doubled angle, times the steepness.
                ways[3 * k] = (gz * gz - gx * gx) / steep;
                ways[3 * k + 1] = -2f * gx * gz / steep;
                ways[3 * k + 2] = steep;
            }
        }
        float[] next = new float[3 * cells];
        for (int pass = 0; pass < BANK_PASSES; pass++) {
            gather(ways, next, own, width, depth, 1, 0);
            gather(next, ways, own, width, depth, 0, 1);
        }
        return ways;
    }

    /** Sums each own-water cell's {@link #BANK_REACH} neighbours one way (its own water's only) into {@code to}. */
    private static void gather(float[] from, float[] to, boolean[] own, int width, int depth, int dx, int dz) {
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int k = z * width + x;
                float c = 0f;
                float s = 0f;
                float w = 0f;
                if (own[k]) {
                    for (int step = -BANK_REACH; step <= BANK_REACH; step++) {
                        int nx = x + dx * step;
                        int nz = z + dz * step;
                        if (nx < 0 || nz < 0 || nx >= width || nz >= depth || !own[nz * width + nx]) {
                            continue;
                        }
                        int n = nz * width + nx;
                        c += from[3 * n];
                        s += from[3 * n + 1];
                        w += from[3 * n + 2];
                    }
                }
                to[3 * k] = c;
                to[3 * k + 1] = s;
                to[3 * k + 2] = w;
            }
        }
    }

    /** The distance's change across a cell one way, from the reached neighbours either side (or just one of them). */
    private static float slope(int[] toSea, float here, int x, int z, int dx, int dz, int width, int depth) {
        float ahead = here;
        float behind = here;
        int span = 0;
        if (x + dx < width && z + dz < depth && toSea[(z + dz) * width + x + dx] >= 0) {
            ahead = toSea[(z + dz) * width + x + dx];
            span++;
        }
        if (x - dx >= 0 && z - dz >= 0 && toSea[(z - dz) * width + x - dx] >= 0) {
            behind = toSea[(z - dz) * width + x - dx];
            span++;
        }
        return span == 0 ? 0f : (ahead - behind) / span;
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** A growable queue of ints, for the bucket queue: no boxing for maps of millions of cells. */
    private static final class IntQueue {
        private int[] items = new int[64];
        private int head;
        private int tail;

        void add(int value) {
            if (tail == items.length) {
                if (head > items.length / 2) {
                    System.arraycopy(items, head, items, 0, tail - head);
                    tail -= head;
                    head = 0;
                } else {
                    items = Arrays.copyOf(items, items.length * 2);
                }
            }
            items[tail++] = value;
        }

        int poll() {
            int value = items[head++];
            if (head == tail) {
                head = 0;
                tail = 0;
            }
            return value;
        }

        boolean isEmpty() {
            return head == tail;
        }
    }
}
