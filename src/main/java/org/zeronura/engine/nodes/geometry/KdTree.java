package org.zeronura.engine.nodes.geometry;

/** A 3D k-d tree over points, for nearest-point queries (Sample Nearest, Merge by Distance, Poisson disks). */
final class KdTree {

    private final float[] points;
    private final int[] order;
    private final int count;

    KdTree(float[] points) {
        this.points = points;
        this.count = points.length / 3;
        this.order = new int[count];
        for (int k = 0; k < count; k++) {
            order[k] = k;
        }
        build(0, count, 0);
    }

    private void build(int from, int to, int axis) {
        if (to - from <= 1) {
            return;
        }
        int mid = (from + to) >>> 1;
        select(from, to - 1, mid, axis);
        build(from, mid, (axis + 1) % 3);
        build(mid + 1, to, (axis + 1) % 3);
    }

    private float coord(int index, int axis) {
        return points[3 * order[index] + axis];
    }

    /** Quickselect on order[from..to] so that order[k] holds the median along an axis. */
    private void select(int from, int to, int k, int axis) {
        while (to > from) {
            float pivot = coord((from + to) >>> 1, axis);
            int i = from, j = to;
            while (i <= j) {
                while (coord(i, axis) < pivot) {
                    i++;
                }
                while (coord(j, axis) > pivot) {
                    j--;
                }
                if (i <= j) {
                    int t = order[i];
                    order[i] = order[j];
                    order[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                to = j;
            } else if (k >= i) {
                from = i;
            } else {
                return;
            }
        }
    }

    /** The index of the point nearest (x, y, z), lowest index on ties; -1 when there are no points. */
    int nearest(float x, float y, float z) {
        if (count == 0) {
            return -1;
        }
        float[] best = {Float.MAX_VALUE};
        int[] bestIndex = {-1};
        search(0, count, 0, x, y, z, best, bestIndex);
        return bestIndex[0];
    }

    private void search(int from, int to, int axis, float x, float y, float z, float[] best, int[] bestIndex) {
        if (from >= to) {
            return;
        }
        int mid = (from + to) >>> 1;
        int p = order[mid];
        float dx = points[3 * p] - x, dy = points[3 * p + 1] - y, dz = points[3 * p + 2] - z;
        float d = dx * dx + dy * dy + dz * dz;
        if (d < best[0] || (d == best[0] && p < bestIndex[0])) {
            best[0] = d;
            bestIndex[0] = p;
        }
        float q = axis == 0 ? x : axis == 1 ? y : z;
        float split = points[3 * p + axis];
        float diff = q - split;
        int next = (axis + 1) % 3;
        if (diff < 0) {
            search(from, mid, next, x, y, z, best, bestIndex);
            if (diff * diff <= best[0]) {
                search(mid + 1, to, next, x, y, z, best, bestIndex);
            }
        } else {
            search(mid + 1, to, next, x, y, z, best, bestIndex);
            if (diff * diff <= best[0]) {
                search(from, mid, next, x, y, z, best, bestIndex);
            }
        }
    }

    /** Every point within a radius of (x, y, z). */
    void within(float x, float y, float z, float radius, java.util.function.IntConsumer found) {
        within(0, count, 0, x, y, z, radius * radius, radius, found);
    }

    private void within(int from, int to, int axis, float x, float y, float z, float r2, float r,
                        java.util.function.IntConsumer found) {
        if (from >= to) {
            return;
        }
        int mid = (from + to) >>> 1;
        int p = order[mid];
        float dx = points[3 * p] - x, dy = points[3 * p + 1] - y, dz = points[3 * p + 2] - z;
        if (dx * dx + dy * dy + dz * dz <= r2) {
            found.accept(p);
        }
        float diff = (axis == 0 ? x : axis == 1 ? y : z) - points[3 * p + axis];
        int next = (axis + 1) % 3;
        if (diff - r <= 0) {
            within(from, mid, next, x, y, z, r2, r, found);
        }
        if (diff + r >= 0) {
            within(mid + 1, to, next, x, y, z, r2, r, found);
        }
    }
}
