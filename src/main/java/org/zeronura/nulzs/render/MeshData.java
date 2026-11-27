package org.zeronura.nulzs.render;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * CPU-side mesh buffers, ready to be handed to a {@link StaticMesh} constructor.
 *
 * <p>This split exists so mesh <i>generation</i> can run off the GL thread: building these buffers
 * touches no GL, while {@code new StaticMesh(...)} must happen on the context thread. A worker
 * produces {@code MeshData}; the render thread uploads it.
 *
 * @param vertexData    interleaved vertices in {@link StaticMesh}'s 36-byte layout, flipped and ready
 *                      to read
 * @param indexData     triangle indices, flipped and ready to read
 * @param groundWeights {@link StaticMesh#GROUND_WEIGHT_BYTES} unsigned bytes per vertex of ground-material weights, or null for a mesh
 *                      that wears none — see {@link StaticMesh#GROUND_WEIGHT_LOCATION}
 */
public record MeshData(ByteBuffer vertexData, IntBuffer indexData, ByteBuffer groundWeights) {

    public MeshData(ByteBuffer vertexData, IntBuffer indexData) {
        this(vertexData, indexData, null);
    }

    public boolean isEmpty() {
        return indexData.remaining() == 0;
    }

    public StaticMesh upload() {
        return new StaticMesh(vertexData, indexData, groundWeights);
    }
}
