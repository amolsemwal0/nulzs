package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL33.*;

/**
 * Soft drifting specks filling a box around the camera: snow, blowing sand, ash or pollen.
 *
 * <p>The same camera-anchored, world-advected box as {@link RainRenderer} (see there for why), but each speck is a
 * round point sprite rather than a streak, falls slowly, and sways on its own phase, so a snowfall flutters instead
 * of dropping in lockstep. Specks are sized in world units and shrink with distance, so near flakes read as big and
 * soft and far ones as fine grain. They fade toward the faces of the box, so one wrapping round to the far side
 * vanishes rather than popping.
 *
 * <p>Generic like {@code RainRenderer}: it takes an intensity, a colour and a {@link Motion}, and knows nothing about
 * the weather that chose them. Puts back every GL state it touches.
 */
public class DriftRenderer {

    /**
     * How the specks move and how big they are.
     *
     * @param fallSpeed downward speed in units/s; near zero for sand carried on the wind
     * @param windX     sideways drift in units/s
     * @param windZ     see {@link #windX}
     * @param sway      how fast, in units/s, each speck wobbles side to side on its own
     * @param size      world-space diameter of an average speck
     */
    public record Motion(float fallSpeed, float windX, float windZ, float sway, float size) {
    }

    private static final int MAX_PARTICLES = 6000;
    private static final float BOX_HALF_XZ = 18f;
    private static final float BOX_HALF_Y = 12f;
    private static final float MAX_POINT_PIXELS = 40f;
    /** Position xyz, size scale, alpha. */
    private static final int FLOATS_PER_VERTEX = 5;

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final FloatBuffer vertexData;

    // Per-speck offset from the camera, a phase for its sway, and its size relative to the average.
    private final float[] ox = new float[MAX_PARTICLES];
    private final float[] oy = new float[MAX_PARTICLES];
    private final float[] oz = new float[MAX_PARTICLES];
    private final float[] phase = new float[MAX_PARTICLES];
    private final float[] scale = new float[MAX_PARTICLES];

    private final Vector3f lastCamera = new Vector3f();
    private boolean hasLastCamera;
    private float time;
    private float size;
    private int lastDrawn;

    public DriftRenderer(long seed) {
        shader = new Shader("/shaders/drift.vert", "/shaders/drift.frag");

        Random random = new Random(seed);
        for (int i = 0; i < MAX_PARTICLES; i++) {
            ox[i] = (random.nextFloat() * 2f - 1f) * BOX_HALF_XZ;
            oy[i] = (random.nextFloat() * 2f - 1f) * BOX_HALF_Y;
            oz[i] = (random.nextFloat() * 2f - 1f) * BOX_HALF_XZ;
            phase[i] = random.nextFloat() * 6.2832f;
            scale[i] = 0.5f + random.nextFloat();
        }

        vertexData = MemoryUtil.memAllocFloat(MAX_PARTICLES * FLOATS_PER_VERTEX);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_PARTICLES * FLOATS_PER_VERTEX * Float.BYTES, GL_STREAM_DRAW);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 4 * Float.BYTES);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Advances the specks and rebuilds the vertex buffer. Fed the paused-aware delta, so they freeze with the world.
     *
     * @param intensity 0 (none) to 1 (thick); scales how many specks are drawn
     */
    public void update(double dt, Vector3f cameraPosition, float intensity, Motion motion) {
        if (intensity <= 0f) {
            // Keep the anchor current, so the specks do not jump by the distance walked while there were none.
            lastCamera.set(cameraPosition);
            hasLastCamera = true;
            lastDrawn = 0;
            return;
        }

        Vector3f delta = new Vector3f();
        if (hasLastCamera) {
            cameraPosition.sub(lastCamera, delta);
        }
        lastCamera.set(cameraPosition);
        hasLastCamera = true;

        float step = (float) dt;
        time += step;
        size = motion.size();
        int drawn = Math.max(0, Math.min(MAX_PARTICLES, Math.round(MAX_PARTICLES * intensity)));

        vertexData.clear();
        for (int i = 0; i < MAX_PARTICLES; i++) {
            // Bigger specks fall and drift a little faster, so the fall has depth rather than moving as one sheet.
            float pace = 0.7f + 0.3f * scale[i];
            float swayX = (float) Math.sin(time * 1.3f + phase[i]) * motion.sway();
            float swayZ = (float) Math.cos(time * 1.1f + phase[i] * 1.7f) * motion.sway();
            ox[i] = wrap(ox[i] - delta.x + (motion.windX() * pace + swayX) * step, BOX_HALF_XZ);
            oy[i] = wrap(oy[i] - delta.y - motion.fallSpeed() * pace * step, BOX_HALF_Y);
            oz[i] = wrap(oz[i] - delta.z + (motion.windZ() * pace + swayZ) * step, BOX_HALF_XZ);

            if (i >= drawn) {
                continue;
            }
            float edge = Math.max(Math.max(Math.abs(ox[i]), Math.abs(oz[i])) / BOX_HALF_XZ, Math.abs(oy[i]) / BOX_HALF_Y);
            float edge2 = edge * edge;
            vertexData.put(cameraPosition.x + ox[i]).put(cameraPosition.y + oy[i]).put(cameraPosition.z + oz[i])
                    .put(scale[i]).put(1f - edge2 * edge2);
        }
        vertexData.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        lastDrawn = drawn;
    }

    /**
     * Draws the specks over the already-rendered scene: depth-tested so hills hide them, not depth-written because
     * they are transparent.
     *
     * @param viewportHeight the framebuffer's height in pixels, which sizes the point sprites
     */
    public void render(Camera camera, int viewportHeight, Vector3f color, float intensity) {
        if (lastDrawn <= 0 || intensity <= 0f) {
            return;
        }
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glEnable(GL_PROGRAM_POINT_SIZE);

        shader.use();
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("color", color);
        shader.setUniform("intensity", Math.min(1f, intensity * 1.5f));
        shader.setUniform("size", size);
        shader.setUniform("viewportHeight", (float) viewportHeight);
        shader.setUniform("maxPixels", MAX_POINT_PIXELS);

        glBindVertexArray(vao);
        glDrawArrays(GL_POINTS, 0, lastDrawn);
        glBindVertexArray(0);

        glDisable(GL_PROGRAM_POINT_SIZE);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /** Maps a value into [-half, half) by modulo, so a speck leaving one face re-enters the opposite one. */
    private static float wrap(float v, float half) {
        float span = 2f * half;
        return v - span * (float) Math.floor((v + half) / span);
    }

    public void delete() {
        MemoryUtil.memFree(vertexData);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.delete();
    }
}
