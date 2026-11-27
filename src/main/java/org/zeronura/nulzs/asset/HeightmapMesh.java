package org.zeronura.nulzs.asset;

import java.util.Arrays;

/**
 * Turns a model whose vertices form a <b>regular XZ lattice</b> into a {@link HeightmapImage}.
 *
 * <p>The same thing {@link HeightmapImage#load} does for a greyscale PNG, for a landscape delivered as
 * geometry instead. A sculpted terrain tile exported from a modelling tool is a heightfield in every way
 * that matters — one vertex per column, no overhangs, an even grid — and the only difference from an image
 * is the container. Reading it as a field rather than drawing it as a mesh is what lets it become
 * <i>terrain</i>: this world's ground is a density field whose surface term is a height function, so a
 * landform expressed that way gets caves, collision, object placement, the minimap and the water table for
 * free, where a mesh dropped into the scene would get none of them and would have to be special-cased by
 * every one of those systems.
 *
 * <p>Engine-layer and GL-free, like {@code HeightmapImage} itself: it produces plain CPU data and there is
 * no upload and no native buffer to free.
 *
 * <h2>The root transform is composed in, and that is the trap</h2>
 * This goes through {@link AssetLoader#loadStaticGeometry}, which applies {@code aiProcess_PreTransformVertices}
 * and therefore <b>bakes each node's transform into its vertices</b>. That is not incidental — it is the
 * whole reason this loader exists rather than reading {@code loadTexturedParts}, which leaves the root
 * transform out. A vertical exaggeration applied at the node is exactly how a terrain sculpt is usually
 * authored, and measured on {@code land_canyon_spiky.glb} the root carries a <b>non-uniform (1, 3.25, 1)
 * scale</b>: the raw vertex positions span y [-1.0, 1.5] while the transform they are meant to be seen
 * under spans [-4.07, 4.07]. Read the raw positions and the landform arrives at <b>under a third of its
 * authored relief</b> — no error, no warning, just a much flatter place than the artist made.
 *
 * <p>It is the mirror of the trap {@code AssetLoader} documents for the slime, where the root transform is
 * a stale pose the animation overrides and composing it renders a permanently squashed pancake. The rule
 * that separates them: a root transform on something <b>animated</b> is a pose, and on something
 * <b>static</b> it is the shape.
 *
 * <h2>Normalized floor to peak</h2>
 * The grid is shifted so its lowest point is 0 and scaled so its highest is 1, which is what makes the two
 * numbers a caller has to choose — where the floor sits in the world and how much relief it stands in —
 * independent of whatever units the asset happened to be authored at. A re-export at a different scale
 * therefore cannot silently resize the landform, the same guarantee {@code ShrubModel.MODEL_HEIGHT} and
 * {@code GrassMeshFactory.normalise} give for props.
 */
public final class HeightmapMesh {

    /**
     * How far apart two coordinates may be and still count as the same lattice line, as a fraction of the
     * grid step.
     *
     * <p>Needed at all because the coordinates come back as floats through a transform: measured on the
     * canyon tile the step is 0.118110 against 0.118111 across the whole grid, i.e. the last digit moves.
     * A tenth of a step is far tighter than any real lattice error and far looser than that.
     */
    private static final float LATTICE_TOLERANCE = 0.1f;

    private HeightmapMesh() {
    }

    /**
     * Loads a lattice model as a normalized elevation grid.
     *
     * <p>Throws rather than degrading if the vertices are not a lattice. A model with an overhang, a hole
     * or an uneven grid is not a heightfield and cannot be read as one — and the failure of guessing is
     * not an obviously broken landform, it is a plausible-looking one built from whichever vertex happened
     * to land in each slot, which is the sort of thing that gets found weeks later. Everything a caller
     * would need to diagnose it is in the message.
     */
    public static HeightmapImage load(String path) {
        StaticGeometry geometry = AssetLoader.loadStaticGeometry(path);
        float[] positions = geometry.positions();
        int vertexCount = positions.length / 3;

        float[] xs = distinctSorted(positions, 0, vertexCount);
        float[] zs = distinctSorted(positions, 2, vertexCount);
        if (xs.length * zs.length != vertexCount) {
            throw new IllegalArgumentException(path + " is not a heightfield lattice: " + xs.length + " x "
                    + zs.length + " distinct columns hold " + vertexCount + " vertices, so it has either "
                    + "overhangs or an uneven grid");
        }
        requireEvenSpacing(path, "x", xs);
        requireEvenSpacing(path, "z", zs);

        // Row-major over z, matching HeightmapImage's (u -> x, v -> z) sampling. NaN marks a slot nothing
        // wrote, which is how a lattice with a hole and a duplicated column is told apart from a good one
        // even though both satisfy the count above.
        float[] elevation = new float[vertexCount];
        Arrays.fill(elevation, Float.NaN);
        for (int i = 0; i < vertexCount; i++) {
            int xi = nearestIndex(xs, positions[i * 3]);
            int zi = nearestIndex(zs, positions[i * 3 + 2]);
            elevation[zi * xs.length + xi] = positions[i * 3 + 1];
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : elevation) {
            if (Float.isNaN(v)) {
                throw new IllegalArgumentException(path + " is not a heightfield lattice: at least one "
                        + "column holds no vertex, so some other column holds two");
            }
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min <= 0f) {
            throw new IllegalArgumentException(path + " is flat: nothing to read as a heightfield");
        }

        for (int i = 0; i < elevation.length; i++) {
            elevation[i] -= min;
        }
        // HeightmapImage.of normalizes against its own maximum, so shifting the floor to zero first is
        // what lands the result on exactly [0, 1] rather than on [min/max, 1].
        return HeightmapImage.of(xs.length, zs.length, elevation);
    }

    private static float[] distinctSorted(float[] positions, int component, int vertexCount) {
        float[] all = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            all[i] = positions[i * 3 + component];
        }
        Arrays.sort(all);
        float[] out = new float[vertexCount];
        int n = 0;
        for (float v : all) {
            if (n == 0 || v != out[n - 1]) {
                out[n++] = v;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static void requireEvenSpacing(String path, String axis, float[] lines) {
        if (lines.length < 2) {
            throw new IllegalArgumentException(path + " has only " + lines.length + " distinct " + axis
                    + " coordinates, which is not a grid");
        }
        float step = (lines[lines.length - 1] - lines[0]) / (lines.length - 1);
        for (int i = 1; i < lines.length; i++) {
            float gap = lines[i] - lines[i - 1];
            if (Math.abs(gap - step) > step * LATTICE_TOLERANCE) {
                throw new IllegalArgumentException(path + " has an uneven " + axis + " lattice: step "
                        + gap + " against an expected " + step + " at index " + i);
            }
        }
    }

    private static int nearestIndex(float[] lines, float value) {
        int at = Arrays.binarySearch(lines, value);
        return at >= 0 ? at : Math.min(-at - 1, lines.length - 1);
    }
}
