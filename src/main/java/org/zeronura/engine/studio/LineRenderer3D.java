package org.zeronura.engine.studio;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.render.Shader;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Thin lines in 3D for the editor's viewport: the ground grid, outlines of what is selected, cameras' view cones. Lines
 * are gathered between {@link #begin} and {@link #draw}, then drawn in one call, fading out with distance from the
 * eye so the grid has no hard edge.
 */
final class LineRenderer3D {

    private static final int FLOATS = 7;
    private static final int MAX_VERTICES = 120_000;

    private static final String VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPosition;
            layout (location = 1) in vec4 aColor;
            uniform mat4 viewProjection;
            uniform float depthBias;
            out vec4 vColor;
            out vec3 vWorld;
            void main() {
                vColor = aColor;
                vWorld = aPosition;
                gl_Position = viewProjection * vec4(aPosition, 1.0);
                // Drawn just in front of a surface it lies on, so a floor at the grid's height doesn't flicker through it.
                gl_Position.z -= depthBias * gl_Position.w;
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            in vec4 vColor;
            in vec3 vWorld;
            uniform vec3 eye;
            uniform vec2 fade;
            out vec4 fragColor;
            void main() {
                float d = distance(vWorld.xz, eye.xz);
                float a = vColor.a * (1.0 - smoothstep(fade.x, fade.y, d));
                if (a <= 0.002) discard;
                fragColor = vec4(vColor.rgb, a);
            }
            """;

    private final Shader shader = Shader.fromSource(VERTEX, FRAGMENT, "editor lines");
    private final int vao = glGenVertexArrays();
    private final int vbo = glGenBuffers();
    private final FloatBuffer data = BufferUtils.createFloatBuffer(MAX_VERTICES * FLOATS);
    private int vertices;

    LineRenderer3D() {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) data.capacity() * Float.BYTES, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, FLOATS * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, FLOATS * Float.BYTES, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    void begin() {
        data.clear();
        vertices = 0;
    }

    void line(float x1, float y1, float z1, float x2, float y2, float z2, Color color) {
        if (vertices + 2 > MAX_VERTICES) {
            return;
        }
        data.put(x1).put(y1).put(z1).put(color.r()).put(color.g()).put(color.b()).put(color.a());
        data.put(x2).put(y2).put(z2).put(color.r()).put(color.g()).put(color.b()).put(color.a());
        vertices += 2;
    }

    void line(Vector3f a, Vector3f b, Color color) {
        line(a.x, a.y, a.z, b.x, b.y, b.z, color);
    }

    /** The twelve edges of a box given by its eight corners, numbered as bits x, y, z. */
    void box(Vector3f[] corners, Color color) {
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] edge : edges) {
            line(corners[edge[0]], corners[edge[1]], color);
        }
    }

    /** Draws what was gathered. {@code fadeStart} to {@code fadeEnd} is the distance over which lines fade out. */
    void draw(Matrix4f viewProjection, Vector3f eye, float fadeStart, float fadeEnd, boolean depthTest) {
        if (vertices == 0) {
            return;
        }
        data.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, data);
        shader.use();
        shader.setUniform("viewProjection", viewProjection);
        shader.setUniform("eye", eye);
        shader.setUniform("fade", new org.joml.Vector2f(fadeStart, fadeEnd));
        shader.setUniform("depthBias", depthTest ? 0.0004f : 0f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (depthTest) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        glDepthMask(false);
        glDrawArrays(GL_LINES, 0, vertices);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glBindVertexArray(0);
        data.clear();
        vertices = 0;
    }

    void delete() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
    }
}
