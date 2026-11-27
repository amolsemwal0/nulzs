package org.zeronura.nulzs.render;

import org.zeronura.nulzs.asset.MorphTarget;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

public class Mesh {

    public static final int STRIDE = 76;

    /** Byte offsets inside a vertex of the two fields a shape key moves. */
    private static final int POSITION_OFFSET = 0;
    private static final int NORMAL_OFFSET = 12;

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;
    private int materialIndex = -1;

    /**
     * The shape keys this mesh carries, the untouched base pose, and the buffer the blend is built in.
     *
     * <h2>Blended on the CPU and re-uploaded, deliberately</h2>
     * The obvious alternative is to hand the deltas to the vertex shader, and it does not fit: GL 3.3
     * has sixteen vertex attributes and this layout already uses six, so a shader could take three or
     * four shapes where this model carries <b>57</b>. The usual way round that is a texture buffer of
     * deltas sampled per vertex, which is a second code path through every shader that draws a
     * character, including the two depth ones.
     *
     * <p>What makes the CPU the right answer here is <b>how often the weights change</b>: never, during
     * play. A character's shape is chosen once in the creation screen and is then a constant for the
     * rest of the session, so the cost is a buffer rebuild per slider drag rather than per frame — and
     * that buffer is re-uploaded only when {@link #applyMorphWeights} is actually handed something
     * different (see {@link #lastWeights}). Runtime <i>expressions</i>, which would change per frame,
     * are what would justify revisiting this.
     */
    private List<MorphTarget> morphTargets = List.of();
    private ByteBuffer baseVertexData;
    private ByteBuffer morphScratch;
    private float[] lastWeights;

    public Mesh(ByteBuffer vertexData, IntBuffer indices) {
        indexCount = indices.remaining();

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // GL_DYNAMIC_DRAW rather than STATIC once a mesh can be re-uploaded. Harmless for the meshes
        // that never morph — this is a usage hint, not a restriction.
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, STRIDE, 12);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, STRIDE, 24);
        glVertexAttribPointer(3, 3, GL_FLOAT, false, STRIDE, 32);
        glVertexAttribIPointer(4, 4, GL_INT, STRIDE, 44);
        glVertexAttribPointer(5, 4, GL_FLOAT, false, STRIDE, 60);
        for (int i = 0; i <= 5; i++) {
            glEnableVertexAttribArray(i);
        }

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * The GL buffer holding this mesh's vertices, {@link #STRIDE} bytes each: position, normal, UV, tangent, bone ids and
     * weights. For a renderer that reads the same vertices through a layout of its own, as a shader-node material does.
     */
    public int vertexBuffer() {
        return vbo;
    }

    /** The GL buffer holding this mesh's triangle indices. */
    public int indexBuffer() {
        return ebo;
    }

    /** How many indices this mesh draws — three per triangle. */
    public int getIndexCount() {
        return indexCount;
    }

    public void draw() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    }

    /**
     * Attaches this mesh's shape keys and keeps a copy of the base pose to blend them against.
     *
     * <p>The copy is not optional and not a nicety: a blend has to start from the <i>unmorphed</i>
     * vertices every time, or moving a slider from 0.6 to 0.5 would subtract from an already-morphed
     * buffer and the shape would drift away from anything the artist authored. This is the same reason
     * the character's bone matrices are rebuilt from the bind pose each frame rather than accumulated.
     */
    public void setMorphTargets(List<MorphTarget> targets, ByteBuffer vertexData) {
        morphTargets = List.copyOf(targets);
        baseVertexData = ByteBuffer.allocateDirect(vertexData.remaining()).order(ByteOrder.nativeOrder());
        baseVertexData.put(vertexData.duplicate()).flip();
        morphScratch = ByteBuffer.allocateDirect(baseVertexData.remaining()).order(ByteOrder.nativeOrder());
    }

    public List<MorphTarget> getMorphTargets() {
        return morphTargets;
    }

    public boolean hasMorphTargets() {
        return !morphTargets.isEmpty();
    }

    /**
     * Blends the shape keys at the given weights and uploads the result.
     *
     * <p>One weight per target, in the order {@link #getMorphTargets} reports. A no-op when nothing has
     * changed since the last call, which is what makes it safe to call every frame from a screen whose
     * sliders are usually still.
     *
     * @return whether the buffer was actually rebuilt
     */
    public boolean applyMorphWeights(float[] weights) {
        if (morphTargets.isEmpty() || baseVertexData == null) {
            return false;
        }
        if (java.util.Arrays.equals(weights, lastWeights)) {
            return false;
        }
        lastWeights = weights.clone();

        morphScratch.clear();
        morphScratch.put(baseVertexData.duplicate());
        morphScratch.flip();

        int count = Math.min(weights.length, morphTargets.size());
        for (int t = 0; t < count; t++) {
            float weight = weights[t];
            if (weight == 0f) {
                continue;
            }
            MorphTarget target = morphTargets.get(t);
            int[] indices = target.vertexIndices();
            float[] positionDeltas = target.positionDeltas();
            float[] normalDeltas = target.normalDeltas();
            boolean withNormals = target.hasNormals();

            for (int i = 0; i < indices.length; i++) {
                int vertexBase = indices[i] * STRIDE;
                addVec3(morphScratch, vertexBase + POSITION_OFFSET, positionDeltas, i, weight);
                if (withNormals) {
                    addVec3(morphScratch, vertexBase + NORMAL_OFFSET, normalDeltas, i, weight);
                }
            }
        }

        // Normals are left un-normalised on purpose. They are re-normalised per fragment by every
        // shader that reads them, so doing it here would be work thrown away — and a weighted sum of
        // two unit normals is the right *direction* either way, which is all that survives.
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, morphScratch);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return true;
    }

    private static void addVec3(ByteBuffer target, int byteOffset, float[] deltas, int entry,
                                float weight) {
        int source = entry * 3;
        for (int axis = 0; axis < 3; axis++) {
            int at = byteOffset + axis * Float.BYTES;
            target.putFloat(at, target.getFloat(at) + deltas[source + axis] * weight);
        }
    }

    public int getMaterialIndex() {
        return materialIndex;
    }

    public void setMaterialIndex(int materialIndex) {
        this.materialIndex = materialIndex;
    }

    public void delete() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
    }
}
