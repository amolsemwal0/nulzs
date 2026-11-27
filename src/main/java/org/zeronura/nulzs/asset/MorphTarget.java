package org.zeronura.nulzs.asset;

/**
 * One shape key: a named set of per-vertex offsets from a mesh's base pose.
 *
 * <h2>Sparse, and that is the whole reason this is a class rather than two arrays</h2>
 * A shape key is authored as a complete copy of the mesh — glTF and assimp both hand it over that way —
 * but almost none of it moves. A brow shape touches the brow. Stored densely, the player model's face
 * costs its vertex count times 57 targets times six floats; stored as "these vertices, by these
 * amounts", it costs what the artist actually moved, which measures at a small fraction of that.
 *
 * <p>The threshold below is what decides membership. It is deliberately tiny — this is separating
 * "the exporter wrote a float that round-trips a hair off" from "the artist moved this vertex", not
 * making a level-of-detail decision, and a generous value would quietly drop the subtlest half of a
 * shape.
 *
 * <h2>Normals are carried, not recomputed</h2>
 * A shape that changes a silhouette changes its shading, and the alternative to storing the artist's
 * normals is recomputing them per frame from the blended positions — which for a cel-shaded face means
 * re-deriving smoothing groups the exporter already resolved. The deltas are stored beside the position
 * ones and blended by the same weight.
 *
 * @param name          the shape key's name, as authored
 * @param vertexIndices which vertices this target moves, ascending
 * @param positionDeltas three floats per entry in {@code vertexIndices}
 * @param normalDeltas   three floats per entry, or an empty array if the source had no normals
 */
public record MorphTarget(String name, int[] vertexIndices, float[] positionDeltas,
                          float[] normalDeltas) {

    /**
     * How far a vertex must move to be counted as part of the shape, in model units.
     *
     * <p>The player model stands about 1.7 units tall, so this is well under a tenth of a millimetre of
     * character — small enough that it can only ever be catching exporter noise.
     */
    public static final float EPSILON = 1e-6f;

    /** How many vertices this shape actually moves. */
    public int movedVertexCount() {
        return vertexIndices.length;
    }

    /** Whether the target carries normal deltas as well as position ones. */
    public boolean hasNormals() {
        return normalDeltas.length == positionDeltas.length;
    }
}
