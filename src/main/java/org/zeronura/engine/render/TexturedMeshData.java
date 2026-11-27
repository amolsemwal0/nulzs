package org.zeronura.engine.render;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * CPU-side buffers in {@link TexturedMesh}'s 44-byte layout, ready to upload.
 *
 * <p>Same split, and the same reason, as {@link MeshData}: building geometry touches no GL, while
 * {@code new TexturedMesh(...)} must happen on the context thread. Here it also makes generated trees
 * testable — a test can assert on vertex counts and positions with no window open.
 *
 * @param vertexData interleaved vertices, flipped and ready to read
 * @param indexData  triangle indices, flipped and ready to read
 */
public record TexturedMeshData(ByteBuffer vertexData, IntBuffer indexData) {

    public boolean isEmpty() {
        return indexData.remaining() == 0;
    }

    public int vertexCount() {
        return vertexData.remaining() / TexturedMesh.STRIDE;
    }

    public int triangleCount() {
        return indexData.remaining() / 3;
    }

    /** Must run on the GL thread. */
    public TexturedMesh upload() {
        return new TexturedMesh(vertexData, indexData);
    }
}
