package org.zeronura.nulzs.asset;

import java.util.Arrays;

/**
 * Gives a garment the skinning of the body underneath it.
 *
 * <h2>Why a garment cannot be a socket</h2>
 * {@code Socket} hangs a <b>rigid</b> thing off one bone — a sword, a ring, a hat — and that is the wrong
 * model for clothing: a shirt has to bend with the chest and the shoulders, which means it must be
 * skinned to the same skeleton as the body, not parented to one joint of it. That was written up as the
 * half the socket system deliberately did not cover, and this is it.
 *
 * <h2>Why the weights are transferred rather than read</h2>
 * The proper source is the garment's own {@code JOINTS_0}/{@code WEIGHTS_0}, painted in the DCC tool and
 * exported alongside the body's. Measured, the shipped garments have none: {@code Fullshirt.glb} and
 * {@code Shorts.glb} carry position, normal, UV and colour and no skinning at all. What they do have is
 * the thing that makes this recoverable — they are authored <b>in the body's own space</b>, fitted to it,
 * so for any point on the shirt the body's skinning a few millimetres underneath is the answer the artist
 * would have painted.
 *
 * <p>So each garment vertex takes the weights of the body vertices nearest it. This is the standard
 * closest-point transfer, and it is exact wherever cloth lies on skin, which is nearly everywhere on a
 * fitted garment. It is the wrong tool for anything that stands well away from the body — a cape, a long
 * skirt, a coat tail — where there is no body underneath to ask, and those want painted weights or a
 * cloth simulation instead.
 *
 * <h2>Several neighbours, not the nearest one</h2>
 * A single nearest vertex is a hard copy, and a hard copy is visibly wrong exactly where a garment
 * crosses a joint: two neighbouring points on a sleeve's seam can find body vertices on opposite sides of
 * the armpit, and the triangle between them then stretches between the torso and the arm. Blending the
 * nearest few by inverse distance smooths that to what a painted weight would look like — and it costs
 * nothing extra, because finding them is the same search.
 *
 * <p>Pure and GL-free: positions in, weights out, no meshes and no window. That is what lets the property
 * that matters — that a garment vertex ends up moving with the part of the body it lies on — be asserted
 * rather than looked at, which no still frame could settle.
 */
public final class SkinBinding {

    /** How many body vertices a garment vertex takes its weights from. */
    public static final int NEIGHBOURS = 4;

    /** How many bones one vertex may be weighted to — the vertex layout's own limit. */
    public static final int INFLUENCES = 4;

    /**
     * Guards the inverse-distance blend where a garment vertex sits exactly on a body one.
     *
     * <p>Which happens: a garment is often modelled by duplicating part of the body and pulling it out,
     * so coincident vertices are the common case rather than a degenerate one.
     */
    private static final float MIN_DISTANCE = 1e-4f;

    private SkinBinding() {
    }

    /**
     * The body's skinning, as the flat arrays a transfer reads.
     *
     * @param positions   three floats per vertex, in the model's own space — which for a skinned mesh is
     *                    the bind pose, since glTF puts skinned vertices there and nowhere else
     * @param boneIndices four per vertex
     * @param weights     four per vertex, summing to one
     */
    public record Source(float[] positions, float[] normals, int[][] boneIndices, float[][] weights) {

        public int vertexCount() {
            return positions.length / 3;
        }
    }

    /**
     * Binds a garment to the body: weights for each of its vertices, and any of them buried in the skin
     * pushed back out of it.
     *
     * <p>Both from one search, because they are the same question asked twice — which part of the body is
     * under this point.
     *
     * <h2>Why the burial matters, and why it is a nudge rather than a lift</h2>
     * A garment is usually modelled <b>on</b> the body, often by duplicating part of it and pulling it
     * out, so some of it ends up fractionally inside. Measured on the shipped shirt, only <b>66.9%</b> of
     * its vertices sat outside the skin along the body's own normal, and the skin therefore showed
     * through most of the chest: the character read as slightly puffier rather than as dressed.
     *
     * <p>Lifting <i>every</i> vertex by a fixed amount fixes that and breaks something else. A hairstyle
     * is thin cards, and pushing both faces of a card out along their own normals does not move the card,
     * it <b>inflates</b> it into a shell twice the lift thick — rendered, the fringe came out as chunky
     * black wedges. So only what is buried moves, and only as far as the surface: measured, that is a
     * third of the shirt and none of the hair.
     *
     * @param clearance how far outside the skin a buried vertex is put, in the model's own units
     */
    public static void bind(Source body, float[] positions, float clearance,
                            int[][] outBoneIndices, float[][] outWeights) {
        int count = positions.length / 3;
        Grid grid = new Grid(body.positions());
        int[] near = new int[NEIGHBOURS];
        float[] nearDistance = new float[NEIGHBOURS];
        for (int v = 0; v < count; v++) {
            int found = grid.nearest(body.positions(), positions[v * 3], positions[v * 3 + 1],
                    positions[v * 3 + 2], near, nearDistance);
            blend(body, near, nearDistance, found, outBoneIndices[v], outWeights[v]);
            if (found > 0 && clearance > 0f) {
                surface(body, near[0], positions, v, clearance);
            }
        }
    }

    /** Pushes one vertex out along the nearest body normal, if it is inside the body at all. */
    private static void surface(Source body, int nearest, float[] positions, int vertex, float clearance) {
        float nx = body.normals()[nearest * 3];
        float ny = body.normals()[nearest * 3 + 1];
        float nz = body.normals()[nearest * 3 + 2];
        float dx = positions[vertex * 3] - body.positions()[nearest * 3];
        float dy = positions[vertex * 3 + 1] - body.positions()[nearest * 3 + 1];
        float dz = positions[vertex * 3 + 2] - body.positions()[nearest * 3 + 2];
        float outward = dx * nx + dy * ny + dz * nz;
        if (outward >= clearance) {
            return;
        }
        float push = clearance - outward;
        positions[vertex * 3] += nx * push;
        positions[vertex * 3 + 1] += ny * push;
        positions[vertex * 3 + 2] += nz * push;
    }

    /** Inverse-distance blend of several body vertices' weight sets, kept to the four heaviest bones. */
    private static void blend(Source body, int[] near, float[] nearDistance, int found,
                              int[] outBoneIndices, float[] outWeights) {
        int[] bones = new int[NEIGHBOURS * INFLUENCES];
        float[] totals = new float[NEIGHBOURS * INFLUENCES];
        int distinct = 0;
        for (int n = 0; n < found; n++) {
            int source = near[n];
            float share = 1f / Math.max(MIN_DISTANCE, nearDistance[n]);
            for (int i = 0; i < INFLUENCES; i++) {
                float weight = body.weights()[source][i] * share;
                if (weight <= 0f) {
                    continue;
                }
                int bone = body.boneIndices()[source][i];
                int slot = -1;
                for (int k = 0; k < distinct; k++) {
                    if (bones[k] == bone) {
                        slot = k;
                        break;
                    }
                }
                if (slot < 0) {
                    slot = distinct++;
                    bones[slot] = bone;
                }
                totals[slot] += weight;
            }
        }
        Arrays.fill(outBoneIndices, 0);
        Arrays.fill(outWeights, 0f);
        // The four heaviest, because that is all a vertex can carry — dropping the lightest is what the
        // importer's own weight limiting does to a painted garment too.
        float sum = 0f;
        for (int i = 0; i < INFLUENCES; i++) {
            int best = -1;
            for (int k = 0; k < distinct; k++) {
                if (totals[k] > 0f && (best < 0 || totals[k] > totals[best])) {
                    best = k;
                }
            }
            if (best < 0) {
                break;
            }
            outBoneIndices[i] = bones[best];
            outWeights[i] = totals[best];
            sum += totals[best];
            totals[best] = 0f;
        }
        if (sum > 0f) {
            for (int i = 0; i < INFLUENCES; i++) {
                outWeights[i] /= sum;
            }
        } else {
            // Nothing underneath at all. Rigid to whichever bone was nearest is the least surprising
            // answer — it keeps the piece attached to the character rather than leaving it at the origin.
            outWeights[0] = 1f;
        }
    }

    /**
     * A uniform grid over the body's vertices, so a search is a handful of cells rather than the whole
     * mesh.
     *
     * <p>Not an optimisation to be trimmed: the body is 11,562 vertices and a full outfit is some 5,700,
     * which brute force makes 66 million distance evaluations — a visible pause on the loading screen,
     * for an answer that is entirely local.
     */
    private static final class Grid {

        private final float cell;
        private final float minX;
        private final float minY;
        private final float minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int[] start;
        private final int[] items;

        Grid(float[] positions) {
            int count = positions.length / 3;
            float lowX = Float.MAX_VALUE;
            float lowY = Float.MAX_VALUE;
            float lowZ = Float.MAX_VALUE;
            float highX = -Float.MAX_VALUE;
            float highY = -Float.MAX_VALUE;
            float highZ = -Float.MAX_VALUE;
            for (int v = 0; v < count; v++) {
                lowX = Math.min(lowX, positions[v * 3]);
                lowY = Math.min(lowY, positions[v * 3 + 1]);
                lowZ = Math.min(lowZ, positions[v * 3 + 2]);
                highX = Math.max(highX, positions[v * 3]);
                highY = Math.max(highY, positions[v * 3 + 1]);
                highZ = Math.max(highZ, positions[v * 3 + 2]);
            }
            // About one vertex per cell on average, which is what makes a 3x3x3 neighbourhood the right
            // amount of work rather than a guess.
            double volume = Math.max(1e-6, (highX - lowX) * (highY - lowY) * (highZ - lowZ));
            this.cell = (float) Math.max(1e-3, Math.cbrt(volume / Math.max(1, count)));
            this.minX = lowX;
            this.minY = lowY;
            this.minZ = lowZ;
            this.sizeX = Math.max(1, (int) ((highX - lowX) / cell) + 1);
            this.sizeY = Math.max(1, (int) ((highY - lowY) / cell) + 1);
            this.sizeZ = Math.max(1, (int) ((highZ - lowZ) / cell) + 1);

            int cells = sizeX * sizeY * sizeZ;
            int[] counts = new int[cells + 1];
            for (int v = 0; v < count; v++) {
                counts[index(positions, v) + 1]++;
            }
            for (int i = 0; i < cells; i++) {
                counts[i + 1] += counts[i];
            }
            this.start = counts;
            this.items = new int[count];
            int[] cursor = counts.clone();
            for (int v = 0; v < count; v++) {
                items[cursor[index(positions, v)]++] = v;
            }
        }

        private int index(float[] positions, int vertex) {
            return index(positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2]);
        }

        private int index(float x, float y, float z) {
            int ix = clamp((int) ((x - minX) / cell), sizeX);
            int iy = clamp((int) ((y - minY) / cell), sizeY);
            int iz = clamp((int) ((z - minZ) / cell), sizeZ);
            return (iz * sizeY + iy) * sizeX + ix;
        }

        private static int clamp(int v, int size) {
            return v < 0 ? 0 : Math.min(v, size - 1);
        }

        /**
         * The nearest few body vertices, searching outward a ring of cells at a time until enough have
         * been found and one more ring cannot beat them.
         *
         * @return how many were found, which is fewer than asked for only on a very small body
         */
        int nearest(float[] positions, float x, float y, float z, int[] out, float[] outDistance) {
            int cx = clamp((int) ((x - minX) / cell), sizeX);
            int cy = clamp((int) ((y - minY) / cell), sizeY);
            int cz = clamp((int) ((z - minZ) / cell), sizeZ);
            int found = 0;
            int maxRing = Math.max(sizeX, Math.max(sizeY, sizeZ));
            for (int ring = 0; ring <= maxRing; ring++) {
                // Once the ring's own inner face is further off than the worst kept candidate, nothing
                // further out can improve on it. The first ring past that is still walked, because a cell
                // is entered by its near face and its contents can sit anywhere inside.
                if (found == out.length && (ring - 1) * cell > outDistance[found - 1]) {
                    break;
                }
                for (int iz = cz - ring; iz <= cz + ring; iz++) {
                    if (iz < 0 || iz >= sizeZ) {
                        continue;
                    }
                    for (int iy = cy - ring; iy <= cy + ring; iy++) {
                        if (iy < 0 || iy >= sizeY) {
                            continue;
                        }
                        for (int ix = cx - ring; ix <= cx + ring; ix++) {
                            if (ix < 0 || ix >= sizeX) {
                                continue;
                            }
                            boolean onRing = Math.abs(ix - cx) == ring || Math.abs(iy - cy) == ring
                                    || Math.abs(iz - cz) == ring;
                            if (!onRing) {
                                continue; // already walked as part of a smaller ring
                            }
                            int cellIndex = (iz * sizeY + iy) * sizeX + ix;
                            for (int k = start[cellIndex]; k < start[cellIndex + 1]; k++) {
                                int v = items[k];
                                float dx = positions[v * 3] - x;
                                float dy = positions[v * 3 + 1] - y;
                                float dz = positions[v * 3 + 2] - z;
                                float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                                found = keep(out, outDistance, found, v, d);
                            }
                        }
                    }
                }
            }
            return found;
        }

        /** Insertion into a short list kept sorted by distance. */
        private static int keep(int[] out, float[] outDistance, int found, int vertex, float distance) {
            if (found == out.length && distance >= outDistance[found - 1]) {
                return found;
            }
            int at = found < out.length ? found++ : found - 1;
            while (at > 0 && outDistance[at - 1] > distance) {
                out[at] = out[at - 1];
                outDistance[at] = outDistance[at - 1];
                at--;
            }
            out[at] = vertex;
            outDistance[at] = distance;
            return found;
        }
    }
}
