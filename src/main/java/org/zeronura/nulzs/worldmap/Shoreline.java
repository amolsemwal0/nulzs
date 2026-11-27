package org.zeronura.nulzs.worldmap;

/**
 * Where a tiled map's water meets its land as one smooth line, rather than round the side of every cell: a field over
 * the map, how far each point lies into the water (less than 0 on land), whose 0 is the shore. {@link MapTerrain} cuts
 * the land along it (when {@link MapTerrain#setSmoothShores} says), and {@link WaterFlow} hands the same field to the
 * water shader, so the foam along a shore follows the line the land is cut to.
 *
 * <p>The field is the distance from each cell's middle to the edge between wet and dry cells, smoothed: a bank that
 * steps cell by cell across the grid comes out a gentle line along it, corners rounded. It is never let swallow a cell
 * whole ({@link #KEEP}): a stream a cell wide stays a stream, a spit of land a cell wide stays land, only narrower.
 * Between cells' middles it is read bilinearly, as the shader's filtered texture reads it.
 */
public final class Shoreline {

    /** How many pieces a side of a cell is cut into, to follow the line through it. */
    public static final int STEPS = 4;
    /**
     * How far into the land the field reaches, metres: past this, land reads as this far. Far enough that the
     * smoothing never reaches it from a shore, so a straight shore stays exactly on its cells' sides.
     */
    public static final float INSIDE = 3f;
    /** How far into the water it reaches, metres. */
    public static final float RANGE = WaterFlow.SHORE_RANGE;
    /** The least a cell's middle is from the line on its own side, as a share of a cell. */
    static final float KEEP = 0.25f;
    /** Times the field is smoothed, each a 1-2-1 pass across x and then z. */
    static final int SMOOTHING = 2;
    /**
     * How many cells beyond a window {@link #field} must see for its values within the window, where they lie near a
     * shore, to be the whole map's: the distances near a shore, and the cells round them they're smoothed with.
     */
    public static final int MARGIN = 8;

    private Shoreline() {
    }

    /** Whether a cell holds water: its own (a river or a lake), or the sea's, where its ground lies under the map's level. */
    public static boolean wet(WorldMap map, int cx, int cz) {
        return map.hasOwnWater(cx, cz) || map.hasWater() && map.tileHeight(cx, cz) < map.waterLevel();
    }

    /** The field over a window of the map's cells, {@code x0} to {@code x1} and {@code z0} to {@code z1} exclusive. */
    public static Field field(WorldMap map, int x0, int z0, int x1, int z1) {
        x0 = Math.max(0, x0);
        z0 = Math.max(0, z0);
        x1 = Math.min(map.width(), x1);
        z1 = Math.min(map.depth(), z1);
        int width = Math.max(1, x1 - x0);
        int depth = Math.max(1, z1 - z0);
        boolean[] wet = new boolean[width * depth];
        boolean[] dry = new boolean[width * depth];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int k = z * width + x;
                wet[k] = x0 + x < map.width() && z0 + z < map.depth() && wet(map, x0 + x, z0 + z);
                dry[k] = !wet[k];
            }
        }
        return new Field(x0, z0, width, depth, map.cellSize(), values(wet, dry, width, depth, map.cellSize()));
    }

    /** The field's values at each cell's middle, for a window's wet and dry cells. */
    static float[] values(boolean[] wet, boolean[] dry, int width, int depth, float cell) {
        float[] intoWater = WaterFlow.shoreDistance(wet, width, depth, cell);
        float[] intoLand = WaterFlow.shoreDistance(dry, width, depth, cell);
        int cells = width * depth;
        float[] values = new float[cells];
        for (int k = 0; k < cells; k++) {
            values[k] = wet[k] ? Math.min(intoWater[k], RANGE) : -Math.min(intoLand[k], INSIDE);
        }
        float[] next = new float[cells];
        for (int pass = 0; pass < SMOOTHING; pass++) {
            smooth(values, next, width, depth, 1, 0);
            smooth(next, values, width, depth, 0, 1);
        }
        float keep = KEEP * cell;
        for (int k = 0; k < cells; k++) {
            values[k] = wet[k] ? Math.max(values[k], keep) : Math.min(values[k], -keep);
        }
        return values;
    }

    /** One 1-2-1 pass one way, the window's edge standing in for what lies past it. */
    private static void smooth(float[] from, float[] to, int width, int depth, int dx, int dz) {
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int bx = Math.max(0, x - dx);
                int bz = Math.max(0, z - dz);
                int ax = Math.min(width - 1, x + dx);
                int az = Math.min(depth - 1, z + dz);
                to[z * width + x] = (from[bz * width + bx] + 2f * from[z * width + x] + from[az * width + ax]) * 0.25f;
            }
        }
    }

    /** The field over a window of cells; see {@link Shoreline}. */
    public static final class Field {
        private final int x0;
        private final int z0;
        private final int width;
        private final int depth;
        private final float cell;
        private final float[] values;

        Field(int x0, int z0, int width, int depth, float cell, float[] values) {
            this.x0 = x0;
            this.z0 = z0;
            this.width = width;
            this.depth = depth;
            this.cell = cell;
            this.values = values;
        }

        /** At a cell's middle, metres into the water, less than 0 on land; past the window, as at its edge. */
        public float at(int cx, int cz) {
            int x = Math.clamp(cx - x0, 0, width - 1);
            int z = Math.clamp(cz - z0, 0, depth - 1);
            return values[z * width + x];
        }

        /**
         * At a corner of the pieces cells are cut into, {@link #STEPS} to a cell from the map's origin: between the
         * middles of the four cells round it, bilinearly. The same corner reads the same from every cell it touches.
         */
        public float corner(int gx, int gz) {
            // From the cells' middles, in halves of a piece: a middle lies STEPS halves into its cell.
            int twiceX = 2 * gx - STEPS;
            int twiceZ = 2 * gz - STEPS;
            int span = 2 * STEPS;
            int i = Math.floorDiv(twiceX, span);
            int j = Math.floorDiv(twiceZ, span);
            float fx = (twiceX - i * span) / (float) span;
            float fz = (twiceZ - j * span) / (float) span;
            float a = at(i, j) + (at(i + 1, j) - at(i, j)) * fx;
            float b = at(i, j + 1) + (at(i + 1, j + 1) - at(i, j + 1)) * fx;
            return a + (b - a) * fz;
        }

        /** The size of a cell, metres. */
        public float cell() {
            return cell;
        }
    }
}
