package org.zeronura.engine.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.opengl.GL33.*;

public class StaticMesh {

    public static final int STRIDE = 36;

    /**
     * Above this many vertices a mesh needs 32-bit indices; at or below it, 16 bits address every vertex.
     *
     * <p>An index is a vertex number, so the widest one a mesh can contain is bounded by its own vertex
     * count — no scan of the index buffer is needed to know which type will do, which is what makes this
     * decision free rather than a pass over the data.
     *
     * <p>Terrain is comfortably inside it and provably so, rather than by observation: a Surface Nets slab
     * emits at most one vertex per padded cell plus at most one skirt vertex per border cell, which for
     * {@code VolumeChunk}'s 34³ padded grid is <b>43,928</b> against the 65,536 here. Measured across
     * every detail level the real worst case is 5,914 — see {@code TerrainMeshProbe}.
     */
    private static final int MAX_SHORT_INDEXED_VERTICES = 65536;

    /**
     * Attribute slot for per-vertex ground-material weights — see {@code GroundTextures}.
     *
     * <p>In a <b>separate buffer</b> rather than interleaved, and optional, because only terrain has any:
     * props and building pieces are the overwhelming majority of these meshes and would otherwise each
     * carry four bytes per vertex of zeroes, and every existing builder would have to learn to write them.
     *
     * <p>Four <b>normalised unsigned bytes</b>, not floats: these are weights in [0,1] where eight bits is
     * ample, and it makes the attribute 4 bytes a vertex instead of 16 — which matters, because terrain is
     * the one mesh here that is streamed and rebuilt constantly.
     *
     * <p><b>A mesh without them relies on the caller setting the constant attribute to zero</b>, since
     * OpenGL's default for a disabled attribute is (0, 0, 0, <b>1</b>) — that stray 1 would read as the
     * fourth material applied at full strength to every prop in the world. {@link #clearGroundWeights} is
     * that call, and {@code Renderer.renderProps} makes it.
     */
    public static final int GROUND_WEIGHT_LOCATION = 3;

    /**
     * The second half of the weights, slots 4-7.
     *
     * <p>The set outgrew one {@code vec4} when the ground gained its own grass and iron materials beside
     * the two soils, stone and magma. A vertex attribute is capped at four components, so a fifth material
     * means a second attribute — which this codebase has paid for once before and got back by making ore
     * geometry, and is now paying again deliberately. It costs <b>4 more bytes per terrain vertex</b>,
     * about 2 MB across a whole streamed world, against the alternative of packing an exclusive-material
     * index into a spare component and branching on it in the shader — cheaper in bytes and much worse to
     * read, for a saving that does not matter.
     *
     * <p>Carries the same (0, 0, 0, <b>1</b>) default hazard as the first, so {@link #clearGroundWeights}
     * zeroes it too.
     */
    public static final int GROUND_WEIGHT_LOCATION_HIGH = 4;

    /**
     * The third four, slots 8-11.
     *
     * <p>Added when a game's ground filled all eight: Crelmons' two map views between them wear grass, soil,
     * paths, roads, stone, water, the tile map's own grass twice over, and then snow and ice. Location 10, not 5:
     * 5-8 are the instance model matrix {@code InstanceBuffer} streams to props drawn through the same shader,
     * and 9 the foliage pass's instance tint. Another 4 bytes per terrain vertex, as the second attribute was.
     *
     * <p>Carries the same (0, 0, 0, <b>1</b>) default hazard as the other two, so {@link #clearGroundWeights}
     * zeroes it as well.
     */
    public static final int GROUND_WEIGHT_LOCATION_THIRD = 10;

    /** Ground-material weights carried per vertex, across all three attributes. */
    public static final int GROUND_WEIGHT_COUNT = 12;

    /** Bytes of ground weight per vertex, all three attributes together. */
    public static final int GROUND_WEIGHT_BYTES = GROUND_WEIGHT_COUNT;

    private final int vao;
    private final int vbo;
    private final int groundWeightVbo;
    private final int ebo;
    private final int indexCount;
    private final int indexType;
    private final MeshBounds bounds;

    public StaticMesh(ByteBuffer vertexData, IntBuffer indices) {
        this(vertexData, indices, null);
    }

    /**
     * @param groundWeights {@link #GROUND_WEIGHT_BYTES} unsigned bytes per vertex, in the same order as {@code vertexData}, or
     *                      null for a mesh that wears no ground material
     */
    public StaticMesh(ByteBuffer vertexData, IntBuffer indices, ByteBuffer groundWeights) {
        indexCount = indices.remaining();
        // Narrowed here rather than at the call sites, so every mesh drawn through this class gets it —
        // terrain slabs, rock and tree props, ore lumps and building pieces — with no producer having to
        // know or decide. The decision is O(1) (see the constant); only the copy touches the data.
        int vertexCount = vertexData.remaining() / STRIDE;
        indexType = vertexCount <= MAX_SHORT_INDEXED_VERTICES ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT;
        bounds = MeshBounds.of(vertexData, STRIDE);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        if (indexType == GL_UNSIGNED_SHORT) {
            // memAlloc/memFree, never create/let-go: this block is handed to the driver and then released
            // by us, and mixing the two allocators is what corrupts the C heap and crashes somewhere else
            // entirely a frame later. The same pairing the font packer had to be fixed to use.
            ShortBuffer narrow = MemoryUtil.memAllocShort(indexCount);
            try {
                for (int i = 0; i < indexCount; i++) {
                    narrow.put(i, (short) indices.get(indices.position() + i));
                }
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, narrow, GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(narrow);
            }
        } else {
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        }

        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, STRIDE, 12);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, STRIDE, 24);
        for (int i = 0; i <= 2; i++) {
            glEnableVertexAttribArray(i);
        }

        if (groundWeights != null) {
            groundWeightVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, groundWeightVbo);
            glBufferData(GL_ARRAY_BUFFER, groundWeights, GL_STATIC_DRAW);
            // Normalised, so the shader reads the bytes back as weights in [0,1]. Twelve weights are
            // interleaved in one buffer and read as three vec4s, four bytes apart.
            glVertexAttribPointer(GROUND_WEIGHT_LOCATION, 4, GL_UNSIGNED_BYTE, true,
                    GROUND_WEIGHT_BYTES, 0);
            glEnableVertexAttribArray(GROUND_WEIGHT_LOCATION);
            glVertexAttribPointer(GROUND_WEIGHT_LOCATION_HIGH, 4, GL_UNSIGNED_BYTE, true,
                    GROUND_WEIGHT_BYTES, 4);
            glEnableVertexAttribArray(GROUND_WEIGHT_LOCATION_HIGH);
            glVertexAttribPointer(GROUND_WEIGHT_LOCATION_THIRD, 4, GL_UNSIGNED_BYTE, true,
                    GROUND_WEIGHT_BYTES, 8);
            glEnableVertexAttribArray(GROUND_WEIGHT_LOCATION_THIRD);
        } else {
            groundWeightVbo = 0;
        }

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public void draw() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, indexType, 0);
    }

    /** The VAO an {@link InstanceBuffer} points its per-instance attributes at. */
    public int vaoHandle() {
        return vao;
    }

    /**
     * Indices this mesh draws — its triangle count times three.
     *
     * <p>For budget reporting in the preview tools. Read off the uploaded mesh rather than the
     * {@link MeshData} it came from on purpose: that is what is actually submitted, so a builder that
     * silently dropped geometry on the way in cannot be reported as though it had not.
     */
    public int indexCount() {
        return indexCount;
    }

    /** The sphere round this mesh's vertices, in its own space: for culling a placed copy without drawing it. */
    public MeshBounds bounds() {
        return bounds;
    }

    /** Draws this mesh {@code count} times, one per placement in the bound instance stream. */
    public void drawInstanced(int count) {
        glBindVertexArray(vao);
        glDrawElementsInstanced(GL_TRIANGLES, indexCount, indexType, 0, count);
    }

    /**
     * Sets the constant ground weights every mesh without its own gets.
     *
     * <p>Must be called before drawing such meshes through a shader that reads the attribute: OpenGL's
     * default constant is (0, 0, 0, 1), and that 1 would apply the fourth ground material at full strength
     * to everything. Context state rather than VAO state, so one call covers a whole pass.
     */
    public static void clearGroundWeights() {
        setGroundWeights(new float[GROUND_WEIGHT_COUNT]);
    }

    /**
     * Sets the constant ground weights to an arbitrary mixture, for a pass of meshes that all wear the
     * same ground material without carrying a per-vertex weight for it.
     *
     * <p>This is how a rock gets the stone material: it is one uniform-ish body of a single substance, so
     * the weight that terrain has to vary per vertex is a constant for the whole draw — no attribute, no
     * buffer, no change to any mesh builder. Which material that is stays a caller's decision, since the
     * slot order is a game-layer contract this class knows nothing about.
     */
    public static void setGroundWeights(float[] weights) {
        if (weights.length != GROUND_WEIGHT_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + GROUND_WEIGHT_COUNT + " ground weights, got " + weights.length);
        }
        glVertexAttrib4f(GROUND_WEIGHT_LOCATION, weights[0], weights[1], weights[2], weights[3]);
        glVertexAttrib4f(GROUND_WEIGHT_LOCATION_HIGH, weights[4], weights[5], weights[6], weights[7]);
        glVertexAttrib4f(GROUND_WEIGHT_LOCATION_THIRD, weights[8], weights[9], weights[10], weights[11]);
    }

    public void delete() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        if (groundWeightVbo != 0) {
            glDeleteBuffers(groundWeightVbo);
        }
        glDeleteBuffers(ebo);
    }
}
