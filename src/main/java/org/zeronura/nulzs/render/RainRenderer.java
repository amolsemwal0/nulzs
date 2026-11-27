package org.zeronura.nulzs.render;

import org.joml.Vector3f;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL33.*;

/**
 * Falling-streak particles filling a box around the camera — the engine's rain.
 *
 * <h2>A camera-anchored box, world-advected</h2>
 * A finite set of streaks is kept inside a box centred on the camera and wrapped back in when they leave
 * it, so a few thousand particles give constant density however far the player walks — the alternative,
 * particles with fixed world homes, would need millions to cover any distance. But the box is not rigidly
 * bolted to the camera: each frame every streak is advected by the camera's own movement before wrapping,
 * so the rain hangs in the world and the player moves <i>through</i> it, rather than it sliding along
 * pinned to the viewpoint. Wrapping is a modulo, so a streak that falls out of the bottom reappears at the
 * top with its horizontal position preserved — no spawning, no pop.
 *
 * <h2>Real streaks, not a screen overlay</h2>
 * Each drop is a short line in <b>world space</b>, so the view/projection give it true perspective: near
 * rain is long streaks, distant rain short dashes, and terrain occludes rain behind a hill because the
 * pass depth-tests against the scene the opaque passes already drew. That is the whole reason this is
 * geometry and not a fullscreen shader.
 *
 * <h2>Generic</h2>
 * Knows nothing about weather — it takes an intensity and a colour. The game's {@code Weather} supplies
 * both, the same layering rule that keeps {@code Renderer} taking {@code SceneLighting} rather than a
 * time of day.
 */
public class RainRenderer {

    /** Total streaks. Only {@code intensity}·MAX are drawn, so light rain is genuinely sparser. */
    private static final int MAX_PARTICLES = 5000;

    /** Half-extents of the box the streaks live in, around the camera. */
    private static final float BOX_HALF_XZ = 20f;
    private static final float BOX_HALF_Y = 15f;

    private static final float FALL_SPEED = 26f;
    private static final float STREAK_LENGTH = 0.9f;

    // A slight sideways drift by default, so rain slants rather than falling as a dead-vertical grid.
    private static final float DEFAULT_WIND_X = 2.2f;
    private static final float DEFAULT_WIND_Z = 1.1f;

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final FloatBuffer vertexData;

    // Per-particle offset from the camera, in world units. Advected and wrapped each frame.
    private final float[] ox = new float[MAX_PARTICLES];
    private final float[] oy = new float[MAX_PARTICLES];
    private final float[] oz = new float[MAX_PARTICLES];

    private final Vector3f wind = new Vector3f(DEFAULT_WIND_X, 0f, DEFAULT_WIND_Z);
    private final Vector3f fallDir = new Vector3f(wind.x, -FALL_SPEED, wind.z).normalize();
    private final Vector3f lastCamera = new Vector3f();
    private boolean hasLastCamera;

    public RainRenderer() {
        shader = new Shader("/shaders/rain.vert", "/shaders/rain.frag");

        Random random = new Random(0x4A17L);
        for (int i = 0; i < MAX_PARTICLES; i++) {
            ox[i] = (random.nextFloat() * 2f - 1f) * BOX_HALF_XZ;
            oy[i] = (random.nextFloat() * 2f - 1f) * BOX_HALF_Y;
            oz[i] = (random.nextFloat() * 2f - 1f) * BOX_HALF_XZ;
        }

        // Two vertices per streak, four floats each (position xyz + alpha).
        vertexData = org.lwjgl.system.MemoryUtil.memAllocFloat(MAX_PARTICLES * 2 * 4);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_PARTICLES * 2 * 4 * Float.BYTES, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * Float.BYTES, 0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 4 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Sets the sideways drift in units/s; a stronger wind slants the rain harder. */
    public void setWind(float x, float z) {
        wind.set(x, 0f, z);
        fallDir.set(x, -FALL_SPEED, z).normalize();
    }

    /**
     * Advances the streaks and rebuilds the vertex buffer. Fed the same paused-aware delta as everything
     * else, so rain freezes with the world.
     *
     * @param cameraPosition where the box is centred
     * @param intensity      0 (dry) to 1 (heavy); scales how many streaks are drawn
     */
    public void update(double dt, Vector3f cameraPosition, float intensity) {
        if (intensity <= 0f) {
            // Keep the anchor current so resuming rain does not jump every streak by the distance walked
            // while it was dry.
            lastCamera.set(cameraPosition);
            hasLastCamera = true;
            return;
        }

        Vector3f delta = new Vector3f();
        if (hasLastCamera) {
            cameraPosition.sub(lastCamera, delta);
        }
        lastCamera.set(cameraPosition);
        hasLastCamera = true;

        float fall = FALL_SPEED * (float) dt;
        float windX = wind.x * (float) dt;
        float windZ = wind.z * (float) dt;

        int drawn = Math.max(0, Math.min(MAX_PARTICLES, Math.round(MAX_PARTICLES * intensity)));

        vertexData.clear();
        for (int i = 0; i < MAX_PARTICLES; i++) {
            // World-advect by camera movement so the rain stays put as the player moves through it, then
            // fall and drift, then wrap back into the box.
            ox[i] = wrap(ox[i] - delta.x + windX, BOX_HALF_XZ);
            oy[i] = wrap(oy[i] - delta.y - fall, BOX_HALF_Y);
            oz[i] = wrap(oz[i] - delta.z + windZ, BOX_HALF_XZ);

            if (i >= drawn) {
                continue;
            }
            // Head at the bottom of the streak (bright), tail trailing up along the fall direction (faint).
            float headX = cameraPosition.x + ox[i];
            float headY = cameraPosition.y + oy[i];
            float headZ = cameraPosition.z + oz[i];
            vertexData.put(headX - fallDir.x * STREAK_LENGTH)
                    .put(headY - fallDir.y * STREAK_LENGTH)
                    .put(headZ - fallDir.z * STREAK_LENGTH)
                    .put(0.15f);
            vertexData.put(headX).put(headY).put(headZ).put(1.0f);
        }
        vertexData.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        lastDrawnVertices = drawn * 2;
    }

    private int lastDrawnVertices;

    /**
     * Draws the streaks over the already-rendered scene.
     *
     * <p>Depth test on so a hill occludes the rain behind it, depth write <b>off</b> because rain is
     * transparent and must not stop the sky or anything else drawing where a streak happens to be. Blended,
     * and both GL states are put back afterwards — the same self-contained contract {@code Skybox} follows.
     */
    public void render(Camera camera, Vector3f rainColor, float intensity) {
        if (lastDrawnVertices <= 0 || intensity <= 0f) {
            return;
        }
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);

        shader.use();
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("rainColor", rainColor);
        shader.setUniform("intensity", Math.min(1f, intensity * 1.4f));

        glBindVertexArray(vao);
        glDrawArrays(GL_LINES, 0, lastDrawnVertices);
        glBindVertexArray(0);

        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /** Maps a value into [-half, half) by modulo, so a streak leaving one face re-enters the opposite one. */
    private static float wrap(float v, float half) {
        float size = 2f * half;
        return v - size * (float) Math.floor((v + half) / size);
    }

    public void delete() {
        org.lwjgl.system.MemoryUtil.memFree(vertexData);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.delete();
    }
}
