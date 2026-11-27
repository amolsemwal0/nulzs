package org.zeronura.nulzs.nodes.tex;

/**
 * Blender's Voronoi Texture in one to four dimensions: F1, F2, Smooth F1, Distance to Edge and N-Sphere Radius,
 * with its fractal octaves, randomness and four distance metrics. Cell points are placed by the integer
 * {@link Hash} of the cell, as Blender places them.
 */
public final class Voronoi {

    public static final int F1 = 0;
    public static final int F2 = 1;
    public static final int SMOOTH_F1 = 2;
    public static final int DISTANCE_TO_EDGE = 3;
    public static final int N_SPHERE_RADIUS = 4;

    public static final int EUCLIDEAN = 0;
    public static final int MANHATTAN = 1;
    public static final int CHEBYCHEV = 2;
    public static final int MINKOWSKI = 3;

    private Voronoi() {
    }

    public static int feature(String name) {
        return switch (name) {
            case "F2" -> F2;
            case "SMOOTH_F1" -> SMOOTH_F1;
            case "DISTANCE_TO_EDGE" -> DISTANCE_TO_EDGE;
            case "N_SPHERE_RADIUS" -> N_SPHERE_RADIUS;
            default -> F1;
        };
    }

    public static int metric(String name) {
        return switch (name) {
            case "MANHATTAN" -> MANHATTAN;
            case "CHEBYCHEV" -> CHEBYCHEV;
            case "MINKOWSKI" -> MINKOWSKI;
            default -> EUCLIDEAN;
        };
    }

    /** The node's settings, after the node has clamped them. */
    public static final class Params {
        public float scale = 5f;
        public float detail;
        public float roughness = 0.5f;
        public float lacunarity = 2f;
        public float smoothness = 0.5f;
        public float exponent = 0.5f;
        public float randomness = 1f;
        public float maxDistance;
        public boolean normalize;
        public int feature;
        public int metric;
        int dims;

        public Params set(int dims, float scale, float detail, float roughness, float lacunarity, float smoothness,
                          float exponent, float randomness, boolean normalize, int feature, int metric) {
            this.dims = dims;
            this.scale = scale;
            this.detail = Math.clamp(detail, 0f, 15f);
            this.roughness = Math.clamp(roughness, 0f, 1f);
            this.lacunarity = lacunarity;
            this.smoothness = Math.clamp(smoothness / 2f, 0f, 0.5f);
            this.exponent = exponent;
            this.randomness = Math.clamp(randomness, 0f, 1f);
            this.normalize = normalize;
            this.feature = feature;
            this.metric = metric;
            if (feature == DISTANCE_TO_EDGE) {
                maxDistance = 0.5f + 0.5f * this.randomness;
            } else if (dims == 1) {
                maxDistance = (0.5f + 0.5f * this.randomness) * (feature == F2 ? 2f : 1f);
            } else {
                float[] corner = new float[dims];
                java.util.Arrays.fill(corner, 0.5f + 0.5f * this.randomness);
                maxDistance = distance(corner, new float[dims], this) * (feature == F2 ? 2f : 1f);
            }
            return this;
        }
    }

    /** Distance, Color (3), Position (4: xyz and w, already divided by the scale). */
    public static final class Output {
        public float distance;
        public final float[] color = new float[3];
        public final float[] position = new float[4];
    }

    static float distance(float[] a, float[] b, Params params) {
        int n = a.length;
        if (n == 1) {
            return Math.abs(a[0] - b[0]);
        }
        switch (params.metric) {
            case MANHATTAN -> {
                float sum = 0f;
                for (int i = 0; i < n; i++) {
                    sum += Math.abs(a[i] - b[i]);
                }
                return sum;
            }
            case CHEBYCHEV -> {
                float max = 0f;
                for (int i = 0; i < n; i++) {
                    max = Math.max(max, Math.abs(a[i] - b[i]));
                }
                return max;
            }
            case MINKOWSKI -> {
                float sum = 0f;
                for (int i = 0; i < n; i++) {
                    sum += (float) Math.pow(Math.abs(a[i] - b[i]), params.exponent);
                }
                return (float) Math.pow(sum, 1f / params.exponent);
            }
            default -> {
                float sum = 0f;
                for (int i = 0; i < n; i++) {
                    float d = a[i] - b[i];
                    sum += d * d;
                }
                return (float) Math.sqrt(sum);
            }
        }
    }

    // --- Cell hashing --------------------------------------------------------------------------------
    // One dimension hashes the cell's coordinate as a float with Jenkins' hash; two to four use the PCG hashes
    // of Jarzynski and Olano ("Hash Functions for GPU Rendering", JCGT 2020) on the integer cell, in the
    // signed-integer form Blender uses (arithmetic shifts), which is what Blender 4.1 onwards places points by.

    private static final float PCG_SCALE = (float) (1.0 / 2147483648.0);

    private static int[] pcg2(int x, int y) {
        x = x * 1664525 + 1013904223;
        y = y * 1664525 + 1013904223;
        x += y * 1664525;
        y += x * 1664525;
        x ^= x >> 16;
        y ^= y >> 16;
        x += y * 1664525;
        y += x * 1664525;
        return new int[]{x, y};
    }

    private static int[] pcg3(int x, int y, int z) {
        x = x * 1664525 + 1013904223;
        y = y * 1664525 + 1013904223;
        z = z * 1664525 + 1013904223;
        x += y * z;
        y += z * x;
        z += x * y;
        x ^= x >> 16;
        y ^= y >> 16;
        z ^= z >> 16;
        x += y * z;
        y += z * x;
        z += x * y;
        return new int[]{x, y, z};
    }

    private static int[] pcg4(int x, int y, int z, int w) {
        x = x * 1664525 + 1013904223;
        y = y * 1664525 + 1013904223;
        z = z * 1664525 + 1013904223;
        w = w * 1664525 + 1013904223;
        x += y * w;
        y += z * x;
        z += x * y;
        w += y * z;
        x ^= x >> 16;
        y ^= y >> 16;
        z ^= z >> 16;
        w ^= w >> 16;
        x += y * w;
        y += z * x;
        z += x * y;
        w += y * z;
        return new int[]{x, y, z, w};
    }

    private static float unit(int h) {
        return (float) (h & 0x7fffffff) * PCG_SCALE;
    }

    /** The jittered point of a cell, offset into [0, 1) of each axis. */
    static void cellPoint(int[] cell, float[] out) {
        switch (cell.length) {
            case 1 -> out[0] = Hash.floatToFloat(cell[0]);
            case 2 -> {
                int[] h = pcg2(cell[0], cell[1]);
                out[0] = unit(h[0]);
                out[1] = unit(h[1]);
            }
            case 3 -> {
                int[] h = pcg3(cell[0], cell[1], cell[2]);
                out[0] = unit(h[0]);
                out[1] = unit(h[1]);
                out[2] = unit(h[2]);
            }
            default -> {
                int[] h = pcg4(cell[0], cell[1], cell[2], cell[3]);
                for (int i = 0; i < 4; i++) {
                    out[i] = unit(h[i]);
                }
            }
        }
    }

    /** A cell's colour. */
    static void cellColor(int[] cell, float[] out) {
        int[] h;
        switch (cell.length) {
            case 1 -> {
                float x = cell[0];
                out[0] = Hash.floatToFloat(x);
                out[1] = Hash.vec2ToFloat(x, 1f);
                out[2] = Hash.vec2ToFloat(x, 2f);
                return;
            }
            case 2 -> h = pcg3(cell[0], cell[1], 0);
            case 3 -> h = pcg3(cell[0], cell[1], cell[2]);
            default -> h = pcg4(cell[0], cell[1], cell[2], cell[3]);
        }
        out[0] = unit(h[0]);
        out[1] = unit(h[1]);
        out[2] = unit(h[2]);
    }

    /** Visits every cell offset in [-r, r] along each of {@code dims} axes, x fastest, as Blender's loops do. */
    private static int offsets(int dims, int r, int index, int[] out) {
        int side = 2 * r + 1;
        int rest = index;
        for (int d = 0; d < dims; d++) {
            out[d] = rest % side - r;
            rest /= side;
        }
        return rest;
    }

    private static int cellCount(int dims, int r) {
        int side = 2 * r + 1;
        int n = 1;
        for (int d = 0; d < dims; d++) {
            n *= side;
        }
        return n;
    }

    private static void position(float[] point, float[] out) {
        java.util.Arrays.fill(out, 0f);
        switch (point.length) {
            case 1 -> out[3] = point[0];
            case 2 -> {
                out[0] = point[0];
                out[1] = point[1];
            }
            case 3 -> System.arraycopy(point, 0, out, 0, 3);
            default -> System.arraycopy(point, 0, out, 0, 4);
        }
    }

    private static void f1(Params params, float[] coord, Output out) {
        int dims = coord.length;
        int[] cellPos = new int[dims];
        float[] cellF = new float[dims];
        float[] local = new float[dims];
        for (int i = 0; i < dims; i++) {
            cellF[i] = (float) Math.floor(coord[i]);
            local[i] = coord[i] - cellF[i];
            cellPos[i] = (int) cellF[i];
        }
        int[] offset = new int[dims];
        int[] cell = new int[dims];
        float[] jitter = new float[dims];
        float[] point = new float[dims];
        float minDistance = Float.MAX_VALUE;
        int[] target = new int[dims];
        float[] targetPos = new float[dims];
        int count = cellCount(dims, 1);
        for (int index = 0; index < count; index++) {
            offsets(dims, 1, index, offset);
            for (int i = 0; i < dims; i++) {
                cell[i] = cellPos[i] + offset[i];
            }
            cellPoint(cell, jitter);
            for (int i = 0; i < dims; i++) {
                point[i] = offset[i] + jitter[i] * params.randomness;
            }
            float d = distance(point, local, params);
            if (d < minDistance) {
                minDistance = d;
                System.arraycopy(offset, 0, target, 0, dims);
                System.arraycopy(point, 0, targetPos, 0, dims);
            }
        }
        out.distance = minDistance;
        for (int i = 0; i < dims; i++) {
            cell[i] = cellPos[i] + target[i];
            targetPos[i] += cellF[i];
        }
        cellColor(cell, out.color);
        position(targetPos, out.position);
    }

    private static void f2(Params params, float[] coord, Output out) {
        int dims = coord.length;
        int[] cellPos = new int[dims];
        float[] cellF = new float[dims];
        float[] local = new float[dims];
        for (int i = 0; i < dims; i++) {
            cellF[i] = (float) Math.floor(coord[i]);
            local[i] = coord[i] - cellF[i];
            cellPos[i] = (int) cellF[i];
        }
        int[] offset = new int[dims];
        int[] cell = new int[dims];
        float[] jitter = new float[dims];
        float[] point = new float[dims];
        float d1 = Float.MAX_VALUE;
        float d2 = Float.MAX_VALUE;
        int[] off1 = new int[dims];
        int[] off2 = new int[dims];
        float[] pos1 = new float[dims];
        float[] pos2 = new float[dims];
        int count = cellCount(dims, 1);
        for (int index = 0; index < count; index++) {
            offsets(dims, 1, index, offset);
            for (int i = 0; i < dims; i++) {
                cell[i] = cellPos[i] + offset[i];
            }
            cellPoint(cell, jitter);
            for (int i = 0; i < dims; i++) {
                point[i] = offset[i] + jitter[i] * params.randomness;
            }
            float d = distance(point, local, params);
            if (d < d1) {
                d2 = d1;
                d1 = d;
                System.arraycopy(off1, 0, off2, 0, dims);
                System.arraycopy(offset, 0, off1, 0, dims);
                System.arraycopy(pos1, 0, pos2, 0, dims);
                System.arraycopy(point, 0, pos1, 0, dims);
            } else if (d < d2) {
                d2 = d;
                System.arraycopy(offset, 0, off2, 0, dims);
                System.arraycopy(point, 0, pos2, 0, dims);
            }
        }
        out.distance = d2;
        for (int i = 0; i < dims; i++) {
            cell[i] = cellPos[i] + off2[i];
            pos2[i] += cellF[i];
        }
        cellColor(cell, out.color);
        position(pos2, out.position);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (x < edge0) {
            return 0f;
        }
        if (x >= edge1) {
            return 1f;
        }
        float t = (x - edge0) / (edge1 - edge0);
        return (3f - 2f * t) * (t * t);
    }

    private static void smoothF1(Params params, float[] coord, Output out) {
        int dims = coord.length;
        int[] cellPos = new int[dims];
        float[] cellF = new float[dims];
        float[] local = new float[dims];
        for (int i = 0; i < dims; i++) {
            cellF[i] = (float) Math.floor(coord[i]);
            local[i] = coord[i] - cellF[i];
            cellPos[i] = (int) cellF[i];
        }
        int[] offset = new int[dims];
        int[] cell = new int[dims];
        float[] jitter = new float[dims];
        float[] point = new float[dims];
        float[] cellColor = new float[3];
        float smoothDistance = 0f;
        float[] smoothColor = new float[3];
        float[] smoothPosition = new float[dims];
        float h = -1f;
        int count = cellCount(dims, 2);
        for (int index = 0; index < count; index++) {
            offsets(dims, 2, index, offset);
            for (int i = 0; i < dims; i++) {
                cell[i] = cellPos[i] + offset[i];
            }
            cellPoint(cell, jitter);
            for (int i = 0; i < dims; i++) {
                point[i] = offset[i] + jitter[i] * params.randomness;
            }
            float d = distance(point, local, params);
            h = h == -1f ? 1f : smoothstep(0f, 1f, 0.5f + 0.5f * (smoothDistance - d) / params.smoothness);
            float correction = params.smoothness * h * (1f - h);
            smoothDistance = (1f - h) * smoothDistance + h * d - correction;
            correction /= 1f + 3f * params.smoothness;
            cellColor(cell, cellColor);
            for (int c = 0; c < 3; c++) {
                smoothColor[c] = (1f - h) * smoothColor[c] + h * cellColor[c] - correction;
            }
            for (int i = 0; i < dims; i++) {
                smoothPosition[i] = (1f - h) * smoothPosition[i] + h * point[i] - correction;
            }
        }
        out.distance = smoothDistance;
        System.arraycopy(smoothColor, 0, out.color, 0, 3);
        for (int i = 0; i < dims; i++) {
            smoothPosition[i] += cellF[i];
        }
        position(smoothPosition, out.position);
    }

    private static float distanceToEdge(Params params, float[] coord) {
        int dims = coord.length;
        if (dims == 1) {
            float cellPosition = (float) Math.floor(coord[0]);
            float local = coord[0] - cellPosition;
            float mid = Hash.floatToFloat(cellPosition) * params.randomness;
            float left = -1f + Hash.floatToFloat(cellPosition - 1f) * params.randomness;
            float right = 1f + Hash.floatToFloat(cellPosition + 1f) * params.randomness;
            float toLeft = Math.abs((mid + left) / 2f - local);
            float toRight = Math.abs((mid + right) / 2f - local);
            return Math.min(toLeft, toRight);
        }
        int[] cellPos = new int[dims];
        float[] local = new float[dims];
        for (int i = 0; i < dims; i++) {
            float f = (float) Math.floor(coord[i]);
            local[i] = coord[i] - f;
            cellPos[i] = (int) f;
        }
        int[] offset = new int[dims];
        int[] cell = new int[dims];
        float[] jitter = new float[dims];
        float[] toPoint = new float[dims];
        float[] toClosest = new float[dims];
        float minDistance = Float.MAX_VALUE;
        int count = cellCount(dims, 1);
        for (int index = 0; index < count; index++) {
            offsets(dims, 1, index, offset);
            for (int i = 0; i < dims; i++) {
                cell[i] = cellPos[i] + offset[i];
            }
            cellPoint(cell, jitter);
            float d = 0f;
            for (int i = 0; i < dims; i++) {
                toPoint[i] = offset[i] + jitter[i] * params.randomness - local[i];
                d += toPoint[i] * toPoint[i];
            }
            if (d < minDistance) {
                minDistance = d;
                System.arraycopy(toPoint, 0, toClosest, 0, dims);
            }
        }
        minDistance = Float.MAX_VALUE;
        for (int index = 0; index < count; index++) {
            offsets(dims, 1, index, offset);
            for (int i = 0; i < dims; i++) {
                cell[i] = cellPos[i] + offset[i];
            }
            cellPoint(cell, jitter);
            float perpLen2 = 0f;
            for (int i = 0; i < dims; i++) {
                toPoint[i] = offset[i] + jitter[i] * params.randomness - local[i];
                float perp = toPoint[i] - toClosest[i];
                perpLen2 += perp * perp;
            }
            if (perpLen2 > 0.0001f) {
                float inv = (float) (1.0 / Math.sqrt(perpLen2));
                float edge = 0f;
                for (int i = 0; i < dims; i++) {
                    edge += (toClosest[i] + toPoint[i]) / 2f * ((toPoint[i] - toClosest[i]) * inv);
                }
                minDistance = Math.min(minDistance, edge);
            }
        }
        return minDistance;
    }

    /** N-Sphere Radius: half the distance from the nearest point to the point nearest it. */
    public static float nSphereRadius(Params params, float[] coord) {
        int dims = coord.length;
        int[] cellPos = new int[dims];
        float[] local = new float[dims];
        for (int i = 0; i < dims; i++) {
            float f = (float) Math.floor(coord[i]);
            local[i] = coord[i] - f;
            cellPos[i] = (int) f;
        }
        int[] offset = new int[dims];
        int[] cell = new int[dims];
        float[] jitter = new float[dims];
        float[] point = new float[dims];
        float[] closest = new float[dims];
        int[] closestOffset = new int[dims];
        float minDistance = Float.MAX_VALUE;
        Params euclid = new Params();
        euclid.metric = EUCLIDEAN;
        int count = cellCount(dims, 1);
        for (int index = 0; index < count; index++) {
            offsets(dims, 1, index, offset);
            for (int i = 0; i < dims; i++) {
                cell[i] = cellPos[i] + offset[i];
            }
            cellPoint(cell, jitter);
            for (int i = 0; i < dims; i++) {
                point[i] = offset[i] + jitter[i] * params.randomness;
            }
            float d = distance(point, local, euclid);
            if (d < minDistance) {
                minDistance = d;
                System.arraycopy(point, 0, closest, 0, dims);
                System.arraycopy(offset, 0, closestOffset, 0, dims);
            }
        }
        minDistance = Float.MAX_VALUE;
        float[] nearest = new float[dims];
        for (int index = 0; index < count; index++) {
            offsets(dims, 1, index, offset);
            boolean centre = true;
            for (int i = 0; i < dims; i++) {
                centre &= offset[i] == 0;
            }
            if (centre) {
                continue;
            }
            for (int i = 0; i < dims; i++) {
                int o = offset[i] + closestOffset[i];
                cell[i] = cellPos[i] + o;
                offset[i] = o;
            }
            cellPoint(cell, jitter);
            for (int i = 0; i < dims; i++) {
                point[i] = offset[i] + jitter[i] * params.randomness;
            }
            float d = distance(closest, point, euclid);
            if (d < minDistance) {
                minDistance = d;
                System.arraycopy(point, 0, nearest, 0, dims);
            }
        }
        return distance(nearest, closest, euclid) / 2f;
    }

    private static void octave(Params params, float[] coord, Output out) {
        if (params.feature == F2) {
            f2(params, coord, out);
        } else if (params.feature == SMOOTH_F1 && params.smoothness != 0f) {
            smoothF1(params, coord, out);
        } else {
            f1(params, coord, out);
        }
    }

    /** F1, F2 or Smooth F1 with fractal octaves. {@code coord} is already multiplied by the scale. */
    public static void fractal(Params params, float[] coord, Output out) {
        int dims = coord.length;
        float amplitude = 1f;
        float maxAmplitude = 0f;
        float scale = 1f;
        out.distance = 0f;
        java.util.Arrays.fill(out.color, 0f);
        java.util.Arrays.fill(out.position, 0f);
        Output oct = new Output();
        float[] scaled = new float[dims];
        boolean zeroInput = params.detail == 0f || params.roughness == 0f;
        for (int i = 0; i <= Math.ceil(params.detail); i++) {
            for (int k = 0; k < dims; k++) {
                scaled[k] = coord[k] * scale;
            }
            octave(params, scaled, oct);
            if (zeroInput) {
                maxAmplitude = 1f;
                out.distance = oct.distance;
                System.arraycopy(oct.color, 0, out.color, 0, 3);
                System.arraycopy(oct.position, 0, out.position, 0, 4);
                break;
            }
            if (i <= params.detail) {
                maxAmplitude += amplitude;
                out.distance += oct.distance * amplitude;
                for (int c = 0; c < 3; c++) {
                    out.color[c] += oct.color[c] * amplitude;
                }
                for (int c = 0; c < 4; c++) {
                    out.position[c] = mix(out.position[c], oct.position[c] / scale, amplitude);
                }
                scale *= params.lacunarity;
                amplitude *= params.roughness;
            } else {
                float remainder = params.detail - (float) Math.floor(params.detail);
                if (remainder != 0f) {
                    maxAmplitude = mix(maxAmplitude, maxAmplitude + amplitude, remainder);
                    out.distance = mix(out.distance, out.distance + oct.distance * amplitude, remainder);
                    for (int c = 0; c < 3; c++) {
                        out.color[c] = mix(out.color[c], out.color[c] + oct.color[c] * amplitude, remainder);
                    }
                    for (int c = 0; c < 4; c++) {
                        out.position[c] = mix(out.position[c],
                                mix(out.position[c], oct.position[c] / scale, amplitude), remainder);
                    }
                }
            }
        }
        if (params.normalize) {
            out.distance /= maxAmplitude * params.maxDistance;
            for (int c = 0; c < 3; c++) {
                out.color[c] /= maxAmplitude;
            }
        }
        for (int c = 0; c < 4; c++) {
            out.position[c] = params.scale != 0f ? out.position[c] / params.scale : 0f;
        }
    }

    /** Distance to Edge with fractal octaves. {@code coord} is already multiplied by the scale. */
    public static float fractalDistanceToEdge(Params params, float[] coord) {
        int dims = coord.length;
        float amplitude = 1f;
        float maxAmplitude = params.maxDistance;
        float scale = 1f;
        float distance = 8f;
        float[] scaled = new float[dims];
        boolean zeroInput = params.detail == 0f || params.roughness == 0f;
        for (int i = 0; i <= Math.ceil(params.detail); i++) {
            for (int k = 0; k < dims; k++) {
                scaled[k] = coord[k] * scale;
            }
            float octave = distanceToEdge(params, scaled);
            if (zeroInput) {
                distance = octave;
                break;
            }
            if (i <= params.detail) {
                maxAmplitude = mix(maxAmplitude, params.maxDistance / scale, amplitude);
                distance = mix(distance, Math.min(distance, octave / scale), amplitude);
                scale *= params.lacunarity;
                amplitude *= params.roughness;
            } else {
                float remainder = params.detail - (float) Math.floor(params.detail);
                if (remainder != 0f) {
                    float lerpAmplitude = mix(maxAmplitude, params.maxDistance / scale, amplitude);
                    maxAmplitude = mix(maxAmplitude, lerpAmplitude, remainder);
                    float lerpDistance = mix(distance, Math.min(distance, octave / scale), amplitude);
                    distance = mix(distance, Math.min(distance, lerpDistance), remainder);
                }
            }
        }
        if (params.normalize) {
            distance /= maxAmplitude;
        }
        return distance;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
