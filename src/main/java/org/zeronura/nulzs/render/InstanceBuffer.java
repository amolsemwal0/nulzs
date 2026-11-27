package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * One reusable stream of per-instance transforms, for drawing a shared mesh many times in a single call.
 *
 * <h2>What this is for</h2>
 * The prop pools are a handful of meshes drawn hundreds of times — measured, <b>408 tree draws against a
 * pool of 14 variant meshes, and 344 rock draws against a pool of 8</b>. Every one of those was a separate
 * submission carrying its own {@code model} and {@code normalMatrix} uniform upload, and together they were
 * 752 of the frame's 1387 draw calls, 1.19&nbsp;ms in the foliage pass and 0.46&nbsp;ms in the props pass.
 * Uploading the mesh once and streaming the placements over it is what {@link GrassRenderer} already does
 * for the same reason, and this is that mechanism made shareable.
 *
 * <h2>A transform is all it carries, and that is a measurement rather than a simplification</h2>
 * The obvious worry is normals: a shader handed a model matrix instead of a precomputed
 * {@code normalMatrix} has to derive one, and the honest derivation is an inverse-transpose. It is not
 * needed here. Every prop transform in this project is built by
 * {@code Matrix4f.translationRotateScale(position, orientation, scale)} with a <b>scalar</b> scale, so the
 * upper 3&times;3 is exactly {@code s&middot;R} — and {@code normalize(mat3(model) * n)} then equals the
 * inverse-transpose result exactly, because the {@code s} divides out in the normalise. So the instance is
 * sixteen floats and the shader does one matrix multiply it was doing anyway.
 *
 * <p><b>The limit that leaves</b>: a caller passing a <i>non-uniform</i> scale would get subtly wrong
 * normals. Nothing does today — building pieces bake their dimensions into the mesh at import rather than
 * scaling the instance — and a caller that needs one should pass a normal matrix rather than quietly
 * relying on this.
 *
 * <h2>Attribute layout</h2>
 * The model matrix occupies four consecutive locations from {@link #MODEL_LOCATION}, because a vertex
 * attribute is at most a {@code vec4} and a {@code mat4} is therefore four of them. Both mesh classes here
 * leave locations 5-9 free ({@link StaticMesh} uses 0-4 and 10 with its ground weights, {@link TexturedMesh}
 * 0-4 with its tint and tangent), so one layout serves both. An optional {@code vec3} tint follows at
 * {@link #TINT_LOCATION} for the foliage pass, whose per-instance dark-forest overlay is a uniform today.
 *
 * <p>The attributes are re-pointed at this buffer on <b>every</b> batch rather than configured once per
 * VAO and remembered. Configuring once would be fewer calls and needs a map keyed on VAO id — and GL
 * recycles those when a mesh is deleted, which the prop pools do, so a stale entry would silently leave a
 * new mesh reading a buffer nobody bound. A handful of {@code glVertexAttribPointer} calls per batch is
 * client-side state and cheap; there are a few dozen batches, against the hundreds of draw calls this
 * removes.
 */
public final class InstanceBuffer {

    /** First of the four locations the model matrix occupies (5, 6, 7, 8). */
    public static final int MODEL_LOCATION = 5;

    /** Where the optional per-instance tint goes. */
    public static final int TINT_LOCATION = 9;

    private static final int MATRIX_FLOATS = 16;
    private static final int TINT_FLOATS = 3;

    private final int vbo;
    private final int capacity;
    private final boolean tinted;
    private final int floatsPerInstance;
    private final FloatBuffer staging;
    /**
     * The batch as it is built, copied into {@link #staging} in one go when it is uploaded. Matrices go into an array
     * rather than straight into the native buffer: on Android, JOML writes a native buffer a float at a time with every
     * check, which was the biggest cost of a frame there; into an array it is plain stores, on any platform.
     */
    private final float[] batch;

    private int count;

    /**
     * @param capacity the most instances one batch may hold. A batch is one mesh's worth of props in view,
     *                 so this only has to cover the largest single pool, not the whole world
     * @param tinted   whether instances carry a colour as well as a transform
     */
    public InstanceBuffer(int capacity, boolean tinted) {
        this.capacity = capacity;
        this.tinted = tinted;
        this.floatsPerInstance = MATRIX_FLOATS + (tinted ? TINT_FLOATS : 0);
        this.staging = MemoryUtil.memAllocFloat(capacity * floatsPerInstance);
        this.batch = new float[capacity * floatsPerInstance];

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // STREAM: rewritten whole every frame, since which props are visible changes as the player moves.
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * floatsPerInstance * Float.BYTES, GL_STREAM_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Starts a batch. */
    public void begin() {
        staging.clear();
        count = 0;
    }

    /** Whether another instance would fit. A full batch drops the rest rather than growing mid-frame. */
    public boolean hasRoom() {
        return count < capacity;
    }

    public int count() {
        return count;
    }

    /** The most instances one batch may hold; the caller splits a larger group across several. */
    public int capacity() {
        return capacity;
    }

    /** Appends a transform. Column-major, which is what {@code Matrix4f.get} writes and what GL expects. */
    public void put(Matrix4f model) {
        if (count >= capacity) {
            return;
        }
        model.get(batch, count * floatsPerInstance);
        count++;
    }

    /** Appends a transform and a tint. */
    public void put(Matrix4f model, Vector3f tint) {
        if (count >= capacity) {
            return;
        }
        int at = count * floatsPerInstance;
        model.get(batch, at);
        batch[at + MATRIX_FLOATS] = tint.x;
        batch[at + MATRIX_FLOATS + 1] = tint.y;
        batch[at + MATRIX_FLOATS + 2] = tint.z;
        count++;
    }

    /**
     * Uploads the batch and points the given VAO's instance attributes at it.
     *
     * <p>Leaves the VAO bound, ready for the draw — the caller issues that, because only the mesh knows its
     * index count and type.
     */
    public void bind(int vao) {
        staging.clear();
        staging.put(batch, 0, count * floatsPerInstance);
        staging.flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, staging);

        int stride = floatsPerInstance * Float.BYTES;
        for (int c = 0; c < 4; c++) {
            int loc = MODEL_LOCATION + c;
            glVertexAttribPointer(loc, 4, GL_FLOAT, false, stride, (long) c * 4 * Float.BYTES);
            glEnableVertexAttribArray(loc);
            // The divisor is the whole mechanism: these advance once per instance rather than once per
            // vertex, so the shared mesh is re-read for every placement in the buffer.
            glVertexAttribDivisor(loc, 1);
        }
        if (tinted) {
            glVertexAttribPointer(TINT_LOCATION, 3, GL_FLOAT, false, stride,
                    (long) MATRIX_FLOATS * Float.BYTES);
            glEnableVertexAttribArray(TINT_LOCATION);
            glVertexAttribDivisor(TINT_LOCATION, 1);
        }

        staging.clear();
    }

    /**
     * Turns the instance attributes off again on the VAO that was last bound.
     *
     * <p>Not tidiness: these VAOs belong to meshes that are also drawn <b>un-instanced</b> — by the shadow
     * pass, which has its own shaders and no instance stream. An attribute left enabled there would have it
     * reading whatever this buffer last held, and a disabled one at least falls back to a defined constant.
     */
    public void unbind() {
        for (int c = 0; c < 4; c++) {
            glDisableVertexAttribArray(MODEL_LOCATION + c);
        }
        if (tinted) {
            glDisableVertexAttribArray(TINT_LOCATION);
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void delete() {
        glDeleteBuffers(vbo);
        MemoryUtil.memFree(staging);
    }
}
