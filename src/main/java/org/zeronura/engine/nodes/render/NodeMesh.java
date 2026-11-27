package org.zeronura.engine.nodes.render;

import org.lwjgl.BufferUtils;
import org.zeronura.engine.render.InstancedVertexArray;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * A mesh on the GPU for a node material: position, normal, UV, colour and tangent, plus up to six named attributes
 * the material reads (at locations 10-15), each a vec4. Locations 5-9 stay free for instanced placements.
 */
public final class NodeMesh implements InstancedVertexArray {

    /** Floats per vertex before the named attributes: position 3, normal 3, uv 2, colour 4, tangent 4. */
    public static final int BASE_FLOATS = 16;

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;
    /** Whether the buffers are another mesh's, read through a layout of this mesh's own; only the layout is this one's. */
    private final boolean borrowed;
    public final int attributeCount;
    /** Bounds in the mesh's own (engine) space, for sorting blended draws. */
    public final float[] centre;

    public NodeMesh(float[] vertices, int[] indices, int attributeCount, float[] centre) {
        this.borrowed = false;
        this.attributeCount = attributeCount;
        this.indexCount = indices.length;
        this.centre = centre;
        int floats = BASE_FLOATS + 4 * attributeCount;
        int stride = floats * Float.BYTES;
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length).put(vertices);
        vertexBuffer.flip();
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length).put(indices);
        indexBuffer.flip();
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        attribute(0, 3, stride, 0);
        attribute(1, 3, stride, 3);
        attribute(2, 2, stride, 6);
        attribute(3, 4, stride, 8);
        attribute(4, 4, stride, 12);
        for (int k = 0; k < 6; k++) {
            if (k < attributeCount) {
                attribute(10 + k, 4, stride, BASE_FLOATS + 4 * k);
            } else {
                glDisableVertexAttribArray(10 + k);
                glVertexAttrib4f(10 + k, 0f, 0f, 0f, 0f);
            }
        }
        glBindVertexArray(0);
    }

    private NodeMesh(int vao, int indexCount, float[] centre) {
        this.vao = vao;
        this.vbo = 0;
        this.ebo = 0;
        this.indexCount = indexCount;
        this.attributeCount = 0;
        this.centre = centre;
        this.borrowed = true;
    }

    /**
     * A model's mesh, as loaded from a model file, drawn by a node material: the same vertices on the GPU, read through
     * this layout instead of the model's. The model has no vertex colours, so a material reading them gets white; its
     * bone weights are left out, so a rigged model is drawn as it stands in the file. {@link #delete} removes only the
     * layout: the vertices stay the model's.
     */
    public static NodeMesh over(org.zeronura.engine.render.Mesh mesh, float[] centre) {
        int stride = org.zeronura.engine.render.Mesh.STRIDE;
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, mesh.vertexBuffer());
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mesh.indexBuffer());
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 24);
        glEnableVertexAttribArray(2);
        glDisableVertexAttribArray(3);
        // The model's tangent has three parts; the fourth (which way the bitangent runs) reads as 1.
        glVertexAttribPointer(4, 3, GL_FLOAT, false, stride, 32);
        glEnableVertexAttribArray(4);
        for (int k = 0; k < 6; k++) {
            glDisableVertexAttribArray(10 + k);
        }
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return new NodeMesh(vao, mesh.getIndexCount(), centre);
    }

    private static void attribute(int location, int size, int stride, int offsetFloats) {
        glVertexAttribPointer(location, size, GL_FLOAT, false, stride, (long) offsetFloats * Float.BYTES);
        glEnableVertexAttribArray(location);
    }

    public void draw() {
        glBindVertexArray(vao);
        if (borrowed) {
            // Values for what the model doesn't carry (they belong to the context, not the layout, so set every draw).
            glVertexAttrib4f(3, 1f, 1f, 1f, 1f);
            for (int k = 0; k < 6; k++) {
                glVertexAttrib4f(10 + k, 0f, 0f, 0f, 0f);
            }
        }
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    @Override
    public int vaoHandle() {
        return vao;
    }

    @Override
    public void drawInstanced(int count) {
        glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0, count);
    }

    public int indexCount() {
        return indexCount;
    }

    public void delete() {
        if (!borrowed) {
            glDeleteBuffers(vbo);
            glDeleteBuffers(ebo);
        }
        glDeleteVertexArrays(vao);
    }
}
