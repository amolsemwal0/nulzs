package org.zeronura.engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * The sky behind a 3D world, drawn before anything else: a gradient from the lighting's horizon colour up to its zenith
 * (the colours water reflects, so the two agree), the sun, and painted clouds on a dome round the camera from a
 * {@link CloudField}.
 *
 * <p>How the clouds are lit is the field's {@link CloudField.Look}: one tint for the sunlit parts of their pictures and
 * one for the shaded parts, told apart by brightness, so a picture painted for noon turns gold and violet at sunset.
 * Those low over the horizon fade into its colour, so a heap there rises out of the haze, and those near the sun glow
 * with it.
 *
 * <h2>What the clouds let through</h2>
 * Each frame it also draws the clouds as the sun sees them into a small {@link #sunMask}: how much cloud there is in
 * every direction within {@value #MASK_FOV_DEGREES} degrees of the sun. {@link SunRays} reads it, so the sun's rays are
 * the light that comes through the gaps between clouds.
 *
 * <h2>Right overhead</h2>
 * The field's {@link CloudField.Layer} of clouds seen from underneath is drawn after the dome's clouds, from its own
 * atlas, flat on its plane over the camera, so perspective spreads it out overhead and foreshortens it toward the
 * horizon. It goes into the sun mask too, so its clouds cast rays and shadows like the rest.
 *
 * <p>Drawn without depth, so everything drawn after covers it, and without touching the alpha channel. Puts back every
 * GL state it touches, the framebuffer and viewport included. The atlases belong to the caller.
 */
public final class SkyDome {

    /**
     * The clouds as the sun sees them: a square texture (R, 0 clear to 1 cloud, mipmapped, so a coarse level says how
     * cloudy it is round the sun), the matrix taking a direction, as a point, to it, and how bright the sun is
     * ({@link CloudField.Look#sun}), which is how bright the beams through the gaps can be.
     */
    public record SunMask(int texture, Matrix4f viewProjection, float sun) {
    }

    /** How far the sun mask reaches round the sun, all the way across, in degrees. */
    public static final float MASK_FOV_DEGREES = 64f;
    /** The sun mask's size, each way. */
    static final int MASK_SIZE = 256;
    /** How far the dome is drawn from the camera: anywhere well inside its near and far planes. */
    private static final float DOME_RADIUS = 100f;
    /**
     * How high the layer overhead is drawn over the camera. Only its directions count, as the sky is infinitely far, so
     * any height keeps it looking the same; this keeps even the corners of its square inside the dome.
     */
    private static final float LAYER_HEIGHT = DOME_RADIUS / 10f;
    /** Position xyz, uv, alpha. */
    private static final int FLOATS_PER_VERTEX = 6;

    private final Texture atlas;
    private final Texture overheadAtlas;
    private final CloudField field;
    private final Shader skyShader;
    private final Shader cloudShader;
    private final Shader maskShader;
    private final int skyVao;
    private final int cloudVao;
    private final int cloudVbo;
    private final FloatBuffer vertices;
    private final int maskFbo;
    private final int maskTexture;
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Matrix4f maskView = new Matrix4f();
    private final Matrix4f maskProjection = new Matrix4f();
    private final Matrix4f maskViewProjection = new Matrix4f();
    private final int[] framebuffer = new int[1];
    private final int[] viewport = new int[4];
    private final float[] clearColor = new float[4];

    public SkyDome(Texture atlas, CloudField field) {
        this(atlas, null, field);
    }

    /**
     * @param overheadAtlas the pictures of the field's {@link CloudField.Layer}; null when it has none
     */
    public SkyDome(Texture atlas, Texture overheadAtlas, CloudField field) {
        if (overheadAtlas == null && !field.layer().clouds().isEmpty()) {
            throw new IllegalArgumentException("the clouds overhead need their atlas");
        }
        this.atlas = atlas;
        this.overheadAtlas = overheadAtlas;
        this.field = field;
        skyShader = new Shader("/shaders/fullscreen.vert", "/shaders/sky.frag");
        cloudShader = new Shader("/shaders/clouds.vert", "/shaders/clouds.frag");
        maskShader = new Shader("/shaders/clouds.vert", "/shaders/cloud_mask.frag");
        skyVao = glGenVertexArrays();

        vertices = MemoryUtil.memAllocFloat(Math.max(1, field.clouds().size() + field.layer().clouds().size()) * 6
                * FLOATS_PER_VERTEX);
        cloudVao = glGenVertexArrays();
        glBindVertexArray(cloudVao);
        cloudVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, cloudVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) vertices.capacity() * Float.BYTES, GL_STREAM_DRAW);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 5 * Float.BYTES);
        for (int i = 0; i < 3; i++) {
            glEnableVertexAttribArray(i);
        }
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        maskTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, maskTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, MASK_SIZE, MASK_SIZE, 0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // Clear past its edge: what the mask does not reach has no cloud in it as far as the rays are concerned.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{0f, 0f, 0f, 0f});
        glGenerateMipmap(GL_TEXTURE_2D);
        maskFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, maskFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, maskTexture, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        maskProjection.setPerspective((float) Math.toRadians(MASK_FOV_DEGREES), 1f, 0.1f, 1000f);
    }

    public CloudField field() {
        return field;
    }

    /** The clouds as the sun saw them in the last frame drawn. */
    public SunMask sunMask() {
        return new SunMask(maskTexture, maskViewProjection, field.look().sun());
    }

    /** Draws the sky over the whole screen, the sun, then the clouds, for the camera and its light. */
    public void render(Camera camera, SceneLighting lighting) {
        // Only the camera's turn: the sky is infinitely far.
        view.set(camera.getViewMatrix()).setTranslation(0f, 0f, 0f);
        inverse.set(camera.getProjectionMatrix()).mul(view).invert();
        Vector3f toSun = new Vector3f(lighting.toSun()).normalize();
        CloudField.Look look = field.look();

        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean blend = glIsEnabled(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        int drawn = buildClouds();
        int overhead = layered;
        drawSunMask(toSun, drawn, overhead);

        // The alpha channel is the picture's own business (the window's, or a bloom mask's): left as it was cleared.
        glColorMask(true, true, true, false);
        skyShader.use();
        skyShader.setUniform("inverseViewProjection", inverse);
        skyShader.setUniform("zenithColor", lighting.skyZenith());
        skyShader.setUniform("middleColor", lighting.skyMiddle());
        skyShader.setUniform("horizonColor", lighting.skyHorizon());
        skyShader.setUniform("sunDirection", lighting.sunDirection());
        skyShader.setUniform("toSun", toSun);
        skyShader.setUniform("sunColor", lighting.sunColor());
        skyShader.setUniform("sunStrength", look.sun());
        glBindVertexArray(skyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        if (drawn + overhead > 0) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            cloudShader.use();
            cloudShader.setUniform("view", view);
            cloudShader.setUniform("projection", camera.getProjectionMatrix());
            cloudShader.setUniform("atlas", 0);
            cloudShader.setUniform("litTint", look.litTint());
            cloudShader.setUniform("shadeTint", look.shadeTint());
            cloudShader.setUniform("hazeColor", lighting.skyHorizon());
            cloudShader.setUniform("haze", look.haze());
            cloudShader.setUniform("toSun", toSun);
            cloudShader.setUniform("sunColor", lighting.sunColor());
            cloudShader.setUniform("sunStrength", look.sun());
            drawQuads(drawn, overhead);
        }
        glBindVertexArray(0);

        glColorMask(true, true, true, true);
        glDepthMask(true);
        if (depthTest) {
            glEnable(GL_DEPTH_TEST);
        }
        if (cull) {
            glEnable(GL_CULL_FACE);
        }
        if (!blend) {
            glDisable(GL_BLEND);
        }
    }

    /**
     * The clouds into the sun mask, seen from the camera's place looking at the sun: their opacity, nothing else. Then
     * its mipmaps, which average it.
     */
    /** The dome's clouds from its atlas, then the layer overhead from its own, with whatever shader is in use. */
    private void drawQuads(int drawn, int overhead) {
        glBindVertexArray(cloudVao);
        if (drawn > 0) {
            atlas.bind(0);
            glDrawArrays(GL_TRIANGLES, 0, drawn * 6);
        }
        if (overhead > 0) {
            overheadAtlas.bind(0);
            glDrawArrays(GL_TRIANGLES, drawn * 6, overhead * 6);
        }
        glBindVertexArray(0);
    }

    private void drawSunMask(Vector3f toSun, int drawn, int overhead) {
        Vector3f up = Math.abs(toSun.y) > 0.98f ? new Vector3f(0f, 0f, 1f) : new Vector3f(0f, 1f, 0f);
        maskView.setLookAt(0f, 0f, 0f, toSun.x, toSun.y, toSun.z, up.x, up.y, up.z);
        maskProjection.mul(maskView, maskViewProjection);

        glGetIntegerv(GL_FRAMEBUFFER_BINDING, framebuffer);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
        glBindFramebuffer(GL_FRAMEBUFFER, maskFbo);
        glViewport(0, 0, MASK_SIZE, MASK_SIZE);
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        if (drawn + overhead > 0) {
            // Clouds overlapping stack up toward fully cloudy, as their light would be blocked twice.
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            maskShader.use();
            maskShader.setUniform("view", maskView);
            maskShader.setUniform("projection", maskProjection);
            maskShader.setUniform("atlas", 0);
            drawQuads(drawn, overhead);
            glDisable(GL_BLEND);
        }
        glBindTexture(GL_TEXTURE_2D, maskTexture);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0]);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
    }

    /** How many of the layer's clouds {@link #buildClouds} put in after the dome's. */
    private int layered;

    /**
     * Fills the vertex buffer with the dome's clouds showing, in the field's order, then the layer's; returns how many
     * of the dome's, and leaves how many of the layer's in {@link #layered}.
     */
    private int buildClouds() {
        vertices.clear();
        int count = 0;
        for (CloudField.Placed p : field.place()) {
            CloudField.Picture picture = field.pictures().get(p.cloud().picture());
            float uLeft = p.cloud().flip() ? picture.u1() : picture.u0();
            float uRight = p.cloud().flip() ? picture.u0() : picture.u1();
            // Two triangles: bottom left, bottom right, top right; bottom left, top right, top left. v runs down.
            corner(p, -1f, -1f, uLeft, picture.v1());
            corner(p, 1f, -1f, uRight, picture.v1());
            corner(p, 1f, 1f, uRight, picture.v0());
            corner(p, -1f, -1f, uLeft, picture.v1());
            corner(p, 1f, 1f, uRight, picture.v0());
            corner(p, -1f, 1f, uLeft, picture.v0());
            count++;
        }
        layered = 0;
        for (CloudField.Laid laid : field.lay()) {
            CloudField.Picture picture = field.layer().pictures().get(laid.cloud().picture());
            float uLeft = laid.cloud().flip() ? picture.u1() : picture.u0();
            float uRight = laid.cloud().flip() ? picture.u0() : picture.u1();
            float[][] uv = {{uLeft, picture.v1()}, {uRight, picture.v1()}, {uRight, picture.v0()}, {uLeft, picture.v0()}};
            for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
                Vector3f c = laid.corners()[i];
                vertices.put(c.x * LAYER_HEIGHT).put(c.y * LAYER_HEIGHT).put(c.z * LAYER_HEIGHT)
                        .put(uv[i][0]).put(uv[i][1]).put(laid.alpha()[i]);
            }
            layered++;
        }
        vertices.flip();
        glBindBuffer(GL_ARRAY_BUFFER, cloudVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return count;
    }

    /** A corner of a cloud's picture, on the plane touching the dome at the picture's middle. */
    private void corner(CloudField.Placed p, float across, float above, float u, float v) {
        float a = across * p.halfWidth();
        float b = above * p.halfHeight();
        Vector3f m = p.middle();
        Vector3f r = p.right();
        Vector3f up = p.up();
        vertices.put((m.x + r.x * a + up.x * b) * DOME_RADIUS)
                .put((m.y + r.y * a + up.y * b) * DOME_RADIUS)
                .put((m.z + r.z * a + up.z * b) * DOME_RADIUS)
                .put(u).put(v).put(p.alpha());
    }

    public void delete() {
        MemoryUtil.memFree(vertices);
        glDeleteBuffers(cloudVbo);
        glDeleteVertexArrays(cloudVao);
        glDeleteVertexArrays(skyVao);
        glDeleteFramebuffers(maskFbo);
        glDeleteTextures(maskTexture);
        skyShader.delete();
        cloudShader.delete();
        maskShader.delete();
    }
}
