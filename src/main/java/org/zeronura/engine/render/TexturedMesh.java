package org.zeronura.engine.render;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * A textured, unskinned mesh: position, normal, UV, tint and tangent. 56-byte stride, locations 0-4.
 *
 * <h2>Why a third mesh class</h2>
 * {@link StaticMesh} is 36 bytes with no UV, and terrain slabs — by far the largest consumer of vertex
 * memory here — would pay 8 bytes per vertex for texture coordinates they never sample, on top of
 * {@code SurfaceNets} and {@code VolumeChunk} both needing to emit them. {@code StaticMesh} was itself
 * split from {@link Mesh} for exactly this reason; widening a layout that most of its users do not need
 * is the thing that split was avoiding.
 *
 * <h2>The tangent channel</h2>
 * Carried per vertex rather than reconstructed in the fragment shader from screen-space derivatives,
 * because everything built here has an <i>analytic</i> tangent: a tube's is the direction its ring runs,
 * a quad's is its own right vector. A derivative-based frame is an approximation that costs per-fragment
 * work to rebuild something the generator already knew exactly.
 *
 * <h2>The tint channel</h2>
 * Multiplied into the sampled texel rather than replacing it. One bark texture can then serve pale and
 * dark trunks, and one leaf texture a summer and an autumn canopy, without a second image — and it reuses
 * the per-vertex colour plumbing every other mesh here already has.
 */
public class TexturedMesh {

    /** position(12) + normal(12) + uv(8) + tint(12) + tangent(12). */
    public static final int STRIDE = 56;

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;
    private final MeshBounds bounds;

    public TexturedMesh(ByteBuffer vertexData, IntBuffer indices) {
        indexCount = indices.remaining();
        bounds = MeshBounds.of(vertexData, STRIDE);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, STRIDE, 12);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, STRIDE, 24);
        glVertexAttribPointer(3, 3, GL_FLOAT, false, STRIDE, 32);
        glVertexAttribPointer(4, 3, GL_FLOAT, false, STRIDE, 44);
        for (int i = 0; i <= 4; i++) {
            glEnableVertexAttribArray(i);
        }

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public int getIndexCount() {
        return indexCount;
    }

    /** The sphere round this mesh's vertices, in its own space: for culling a placed copy without drawing it. */
    public MeshBounds bounds() {
        return bounds;
    }

    public void draw() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    }

    /** The VAO an {@link InstanceBuffer} points its per-instance attributes at. */
    public int vaoHandle() {
        return vao;
    }

    /** Draws this mesh {@code count} times, one per placement in the bound instance stream. */
    public void drawInstanced(int count) {
        glBindVertexArray(vao);
        glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0, count);
    }

    public void delete() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
    }
}
