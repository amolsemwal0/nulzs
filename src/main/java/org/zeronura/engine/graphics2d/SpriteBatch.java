package org.zeronura.engine.graphics2d;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.zeronura.engine.render.Shader;
import org.zeronura.engine.render.Texture;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws textured and coloured quads in 2D world space, batched into as few GPU calls as possible.
 *
 * <p>Every quad between {@link #begin} and {@link #end} is appended to one vertex buffer; the batch is sent
 * to the GPU when the texture changes, when it fills, or at {@link #end}. Draw order is preserved exactly,
 * so later draws appear on top — pack sprites that are drawn together into one sheet and a whole level is a
 * handful of calls.
 *
 * <p>Untextured shapes ({@link #rect}, {@link #line}, {@link #circle}) use a built-in white texture, so
 * they batch with each other and never force a flush between themselves.
 */
public final class SpriteBatch {

    public static final int MAX_QUADS = 10_000;

    private static final int FLOATS_PER_VERTEX = 8;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;

    private final Shader shader;
    private final FloatBuffer vertices;
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final Texture white;
    private final Matrix4f projection = new Matrix4f();

    private Texture texture;
    private int quadCount;
    private boolean drawing;
    private boolean premultiplied;
    private int drawCalls;
    private int lastFrameDrawCalls;

    public SpriteBatch() {
        shader = new Shader("/shaders/sprite.vert", "/shaders/sprite.frag");
        vertices = MemoryUtil.memAllocFloat(MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);
        white = Texture.solidColor(255, 255, 255, 255);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) vertices.capacity() * Float.BYTES, GL_DYNAMIC_DRAW);

        // The index pattern never changes, so it is uploaded once for the largest batch.
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indices = MemoryUtil.memAllocInt(MAX_QUADS * INDICES_PER_QUAD);
        try {
            for (int quad = 0; quad < MAX_QUADS; quad++) {
                int base = quad * VERTICES_PER_QUAD;
                indices.put(base).put(base + 1).put(base + 2).put(base + 2).put(base + 3).put(base);
            }
            indices.flip();
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(indices);
        }

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    public void begin(Camera2D camera) {
        begin(camera.combined(projection));
    }

    /** Starts a batch with an explicit world-to-clip matrix. */
    public void begin(Matrix4f worldToClip) {
        if (drawing) {
            throw new IllegalStateException("SpriteBatch.begin() called twice without end()");
        }
        drawing = true;
        quadCount = 0;
        texture = null;
        drawCalls = 0;
        premultiplied = false;

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.use();
        shader.setUniform("projection", worldToClip);
        shader.setUniform("spriteTexture", 0);
    }

    public void end() {
        if (!drawing) {
            throw new IllegalStateException("SpriteBatch.end() called without begin()");
        }
        flush();
        drawing = false;
        lastFrameDrawCalls = drawCalls;
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    public boolean isDrawing() {
        return drawing;
    }

    /**
     * Blends what is drawn from here on as premultiplied alpha — colour already multiplied by its own alpha — or, turned
     * off, as straight alpha again. Every batch begins straight.
     *
     * <p>For art with soft, see-through edges that is drawn scaled: filtering straight-alpha texels blends the colour of
     * the clear pixels beside an edge (black, as exported) into it, a dark fringe round every glow, where premultiplied
     * texels blend exactly. A tint drawn with it must be premultiplied too: {@code (a, a, a, a)} fades by {@code a}.
     */
    public void setPremultipliedAlpha(boolean enabled) {
        if (enabled == premultiplied) {
            return;
        }
        flush();
        premultiplied = enabled;
        glBlendFunc(enabled ? GL_ONE : GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Whether what is drawn now blends as premultiplied alpha. */
    public boolean isPremultipliedAlpha() {
        return premultiplied;
    }

    /** GPU draw calls made by the last completed batch. */
    public int drawCalls() {
        return lastFrameDrawCalls;
    }

    public Texture whiteTexture() {
        return white;
    }

    // --- Sprites ----------------------------------------------------------------------------------------

    /** Draws a region with its bottom-left corner at (x, y). A null region draws a solid rectangle. */
    public void draw(TextureRegion region, float x, float y, float width, float height, Color color) {
        draw(region, x, y, width, height, 0f, 0f, 0f, false, false, color);
    }

    public void draw(Texture image, float x, float y, float width, float height, Color color) {
        quad(image, x, y, 0f, 1f, x + width, y, 1f, 1f, x + width, y + height, 1f, 0f, x, y + height, 0f, 0f, color);
    }

    /**
     * Draws a region rotated about an origin.
     *
     * @param x        world x of the unrotated bottom-left corner
     * @param originX  rotation centre, measured from the bottom-left corner
     * @param rotation counter-clockwise, radians
     */
    public void draw(TextureRegion region, float x, float y, float width, float height,
                     float originX, float originY, float rotation, boolean flipX, boolean flipY, Color color) {
        float left = -originX;
        float bottom = -originY;
        float right = width - originX;
        float top = height - originY;
        float pivotX = x + originX;
        float pivotY = y + originY;

        float x0, y0, x1, y1, x2, y2, x3, y3;
        if (rotation == 0f) {
            x0 = pivotX + left;
            y0 = pivotY + bottom;
            x1 = pivotX + right;
            y1 = pivotY + bottom;
            x2 = pivotX + right;
            y2 = pivotY + top;
            x3 = pivotX + left;
            y3 = pivotY + top;
        } else {
            float cos = (float) Math.cos(rotation);
            float sin = (float) Math.sin(rotation);
            x0 = pivotX + left * cos - bottom * sin;
            y0 = pivotY + left * sin + bottom * cos;
            x1 = pivotX + right * cos - bottom * sin;
            y1 = pivotY + right * sin + bottom * cos;
            x2 = pivotX + right * cos - top * sin;
            y2 = pivotY + right * sin + top * cos;
            x3 = pivotX + left * cos - top * sin;
            y3 = pivotY + left * sin + top * cos;
        }
        regionQuad(region, x0, y0, x1, y1, x2, y2, x3, y3, flipX, flipY, color);
    }

    /**
     * Draws a region as a local rectangle placed by a full transform — what sprite components use, so a
     * sprite inherits its parents' position, rotation and scale.
     */
    public void draw(TextureRegion region, Matrix4f transform, float localX, float localY, float width, float height,
                     boolean flipX, boolean flipY, Color color) {
        float right = localX + width;
        float top = localY + height;
        float m00 = transform.m00();
        float m01 = transform.m01();
        float m10 = transform.m10();
        float m11 = transform.m11();
        float m30 = transform.m30();
        float m31 = transform.m31();
        regionQuad(region,
                m00 * localX + m10 * localY + m30, m01 * localX + m11 * localY + m31,
                m00 * right + m10 * localY + m30, m01 * right + m11 * localY + m31,
                m00 * right + m10 * top + m30, m01 * right + m11 * top + m31,
                m00 * localX + m10 * top + m30, m01 * localX + m11 * top + m31,
                flipX, flipY, color);
    }

    private void regionQuad(TextureRegion region, float x0, float y0, float x1, float y1,
                            float x2, float y2, float x3, float y3, boolean flipX, boolean flipY, Color color) {
        Texture image = region == null ? null : region.texture();
        float u0 = region == null ? 0f : region.u0();
        float v0 = region == null ? 0f : region.v0();
        float u1 = region == null ? 1f : region.u1();
        float v1 = region == null ? 1f : region.v1();
        float uLeft = flipX ? u1 : u0;
        float uRight = flipX ? u0 : u1;
        // v0 is the top of the image and y points up, so the quad's top edge takes v0.
        float vBottom = flipY ? v0 : v1;
        float vTop = flipY ? v1 : v0;
        quad(image, x0, y0, uLeft, vBottom, x1, y1, uRight, vBottom, x2, y2, uRight, vTop, x3, y3, uLeft, vTop, color);
    }

    // --- Shapes -----------------------------------------------------------------------------------------

    public void rect(float x, float y, float width, float height, Color color) {
        quad(null, x, y, 0f, 1f, x + width, y, 1f, 1f, x + width, y + height, 1f, 0f, x, y + height, 0f, 0f, color);
    }

    public void rectOutline(float x, float y, float width, float height, float thickness, Color color) {
        rect(x, y, width, thickness, color);
        rect(x, y + height - thickness, width, thickness, color);
        rect(x, y + thickness, thickness, height - 2f * thickness, color);
        rect(x + width - thickness, y + thickness, thickness, height - 2f * thickness, color);
    }

    public void line(float x1, float y1, float x2, float y2, float thickness, Color color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length == 0f) {
            return;
        }
        float nx = -dy / length * thickness * 0.5f;
        float ny = dx / length * thickness * 0.5f;
        quad(null, x1 - nx, y1 - ny, 0f, 1f, x2 - nx, y2 - ny, 1f, 1f, x2 + nx, y2 + ny, 1f, 0f,
                x1 + nx, y1 + ny, 0f, 0f, color);
    }

    /** A filled circle, as a fan of {@code segments} wedges. */
    public void circle(float centerX, float centerY, float radius, int segments, Color color) {
        int count = Math.max(3, segments);
        double step = 2.0 * Math.PI / count;
        for (int i = 0; i < count; i++) {
            float ax = centerX + radius * (float) Math.cos(i * step);
            float ay = centerY + radius * (float) Math.sin(i * step);
            float bx = centerX + radius * (float) Math.cos((i + 1) * step);
            float by = centerY + radius * (float) Math.sin((i + 1) * step);
            // A wedge is a quad with two corners on the centre: one of its triangles collapses to nothing.
            quad(null, centerX, centerY, 0.5f, 0.5f, centerX, centerY, 0.5f, 0.5f, ax, ay, 0.5f, 0.5f,
                    bx, by, 0.5f, 0.5f, color);
        }
    }

    /** The primitive every other draw call ends in: four corners, counter-clockwise from bottom-left. */
    public void quad(Texture image,
                     float x0, float y0, float u0, float v0,
                     float x1, float y1, float u1, float v1,
                     float x2, float y2, float u2, float v2,
                     float x3, float y3, float u3, float v3,
                     Color color) {
        if (!drawing) {
            throw new IllegalStateException("SpriteBatch.begin() must be called before drawing");
        }
        Texture wanted = image == null ? white : image;
        if (wanted != texture) {
            flush();
            texture = wanted;
        }
        if (quadCount == MAX_QUADS) {
            flush();
        }
        vertex(x0, y0, u0, v0, color);
        vertex(x1, y1, u1, v1, color);
        vertex(x2, y2, u2, v2, color);
        vertex(x3, y3, u3, v3, color);
        quadCount++;
    }

    private void vertex(float x, float y, float u, float v, Color color) {
        vertices.put(x).put(y).put(u).put(v).put(color.r()).put(color.g()).put(color.b()).put(color.a());
    }

    private void flush() {
        if (quadCount == 0) {
            return;
        }
        vertices.flip();
        texture.bind(0);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, vertices);
        glDrawElements(GL_TRIANGLES, quadCount * INDICES_PER_QUAD, GL_UNSIGNED_INT, 0L);
        vertices.clear();
        quadCount = 0;
        drawCalls++;
    }

    public void delete() {
        MemoryUtil.memFree(vertices);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
        shader.delete();
        white.delete();
    }
}
