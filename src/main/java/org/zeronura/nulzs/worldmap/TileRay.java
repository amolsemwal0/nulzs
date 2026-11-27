package org.zeronura.nulzs.worldmap;

import org.joml.Vector3f;

/**
 * Picking on a {@linkplain WorldMap#isTiled tiled} map, or any grid of tiles standing at their own heights — a room's
 * floor and walls: the tile a ray meets first, on its top or on the side of it that stands over a lower neighbour. The
 * ray is followed tile by tile across the grid rather than sampled along its length as {@link TerrainRay} does, so it
 * never steps over the corner of a ledge or a pillar one tile wide.
 */
public final class TileRay {

    /**
     * Where a ray met a tile.
     *
     * @param top   whether it met the tile's top; otherwise it met the side of the tile facing the way the ray came
     * @param faceX the side met, as the way it faces out of the tile along x and z: one of them 1 or -1 for a side, both
     *              0 for the top, or for a ray that started inside the tile
     * @param faceZ see {@code faceX}
     */
    public record Hit(int cellX, int cellZ, float x, float y, float z, boolean top, int faceX, int faceZ) {
    }

    /** How high each tile of a grid stands. */
    @FunctionalInterface
    public interface Heights {
        float at(int cellX, int cellZ);
    }

    private TileRay() {
    }

    /**
     * The first tile a ray meets within a distance, or null if it meets none before leaving the map.
     *
     * @param direction need not be of unit length; distances are measured in its lengths
     */
    public static Hit cast(WorldMap map, Vector3f origin, Vector3f direction, float maxDistance) {
        return cast(map.width(), map.depth(), map.cellSize(), map::tileHeight, origin, direction, maxDistance);
    }

    /**
     * The first tile of a grid a ray meets within a distance, or null if it meets none before leaving the grid. The
     * grid's corner is at the origin, its tiles {@code cell} wide.
     *
     * @param direction need not be of unit length; distances are measured in its lengths
     */
    public static Hit cast(int width, int depth, float cell, Heights heights, Vector3f origin, Vector3f direction,
                           float maxDistance) {
        float[] span = {0f, maxDistance};
        if (!clip(origin.x, direction.x, width * cell, span) || !clip(origin.z, direction.z, depth * cell, span)) {
            return null;
        }
        float t = span[0];
        float end = span[1];
        int cx = Math.clamp((int) Math.floor((origin.x + direction.x * t) / cell), 0, width - 1);
        int cz = Math.clamp((int) Math.floor((origin.z + direction.z * t) / cell), 0, depth - 1);
        int stepX = direction.x > 0f ? 1 : -1;
        int stepZ = direction.z > 0f ? 1 : -1;
        float deltaX = direction.x == 0f ? Float.POSITIVE_INFINITY : cell / Math.abs(direction.x);
        float deltaZ = direction.z == 0f ? Float.POSITIVE_INFINITY : cell / Math.abs(direction.z);
        float nextX = direction.x == 0f ? Float.POSITIVE_INFINITY
                : ((stepX > 0 ? cx + 1 : cx) * cell - origin.x) / direction.x;
        float nextZ = direction.z == 0f ? Float.POSITIVE_INFINITY
                : ((stepZ > 0 ? cz + 1 : cz) * cell - origin.z) / direction.z;
        // The side the ray came into the tile it is in through, facing back the way it came: at first, whichever
        // edge of the grid it crossed to get in, if it came in from outside.
        int faceX = 0;
        int faceZ = 0;
        if (t > 0f) {
            float enterX = direction.x == 0f ? Float.NEGATIVE_INFINITY : ((stepX > 0 ? 0f : width * cell) - origin.x) / direction.x;
            float enterZ = direction.z == 0f ? Float.NEGATIVE_INFINITY : ((stepZ > 0 ? 0f : depth * cell) - origin.z) / direction.z;
            if (enterX >= enterZ) {
                faceX = -stepX;
            } else {
                faceZ = -stepZ;
            }
        }
        while (t <= end) {
            float leave = Math.min(Math.min(nextX, nextZ), end);
            float top = heights.at(cx, cz);
            float y = origin.y + direction.y * t;
            if (y <= top) {
                // Below this tile's top where the ray comes into it: it met the side (or the grid's edge), unless it
                // started inside the ground, where the tile it is in is the answer.
                boolean inside = t == 0f;
                return hit(origin, direction, t, cx, cz, inside, inside ? 0 : faceX, inside ? 0 : faceZ);
            }
            float yLeave = origin.y + direction.y * leave;
            if (yLeave <= top) {
                return hit(origin, direction, (top - origin.y) / direction.y, cx, cz, true, 0, 0);
            }
            if (leave >= end) {
                return null;
            }
            t = leave;
            if (nextX < nextZ) {
                cx += stepX;
                nextX += deltaX;
                faceX = -stepX;
                faceZ = 0;
            } else {
                cz += stepZ;
                nextZ += deltaZ;
                faceX = 0;
                faceZ = -stepZ;
            }
            if (cx < 0 || cz < 0 || cx >= width || cz >= depth) {
                return null;
            }
        }
        return null;
    }

    private static Hit hit(Vector3f origin, Vector3f direction, float t, int cx, int cz, boolean top, int faceX, int faceZ) {
        return new Hit(cx, cz, origin.x + direction.x * t, origin.y + direction.y * t, origin.z + direction.z * t, top,
                faceX, faceZ);
    }

    /** Narrows {@code span} to where a ray's coordinate lies in [0, size]; false if it never does. */
    private static boolean clip(float start, float direction, float size, float[] span) {
        if (direction == 0f) {
            return start >= 0f && start <= size;
        }
        float a = (0f - start) / direction;
        float b = (size - start) / direction;
        span[0] = Math.max(span[0], Math.min(a, b));
        // A hair short of the far edge, so the last tile is the one inside the map rather than the edge itself.
        span[1] = Math.min(span[1], Math.max(a, b) - 1e-4f);
        return span[0] <= span[1];
    }
}
