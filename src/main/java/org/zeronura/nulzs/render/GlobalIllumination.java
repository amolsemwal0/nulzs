package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL33.*;

/**
 * Indirect light: how much sky a surface can actually see, and what colour the surfaces around it reflect
 * onto it.
 *
 * <h2>The gap this fills</h2>
 * The renderer's key/fill split gives every fragment the same sky-coloured ambient wherever it stands, so a
 * crevice, the underside of a boulder and an open hillside are all filled by the identical light. That is
 * what makes an unoccluded scene here read as flat in a way no amount of tuning the ambient colour fixes —
 * the missing information is not the colour, it is that ambient light is <b>occluded</b>, and that nearby
 * surfaces send some of it back tinted with whatever they are made of.
 *
 * <h2>Screen space, and what that means it can and cannot do</h2>
 * Everything here is gathered from the frame already drawn: depth for where surfaces are, the geometry
 * buffer for which way they face and how much fill they take, and the scene colour for what they are
 * reflecting. That buys one bounce of genuine light transport for the cost of a half-resolution pass and no
 * precomputation, in a world that is streamed, dug into and rebuilt constantly — which is what rules out
 * every offline alternative. A lightmap cannot be baked for terrain the player reshapes with a pickaxe, and
 * an irradiance-probe volume would have to be revoxelised behind them as they walked.
 *
 * <p>What it cannot do follows from the same fact: it only knows about surfaces that are on screen. An
 * object out of frame casts no bounce, and turning away from a lit wall removes the light it was throwing.
 * That is the standard limitation of the technique and the reason the strength is kept low enough that its
 * absence is not what the eye notices.
 *
 * <h2>Three passes, half resolution</h2>
 * <ol>
 *   <li><b>gather</b> ({@code gi.frag}) — the expensive one, at half resolution in each axis. Occlusion and
 *       bounce come out of one set of taps because they are one question: an occluder is precisely a
 *       surface that blocks the sky and returns its own light instead.</li>
 *   <li><b>blur</b> ({@code gi_blur.frag}) — separable and depth-aware, twice. The gather rotates its kernel
 *       per pixel, which turns its error into noise specifically so that this can average it out.</li>
 *   <li><b>apply</b> ({@code gi_apply.frag}) — one blended fullscreen triangle onto the scene buffer.</li>
 * </ol>
 *
 * <h2>Where it goes in the frame</h2>
 * After every opaque pass and before anything translucent. It has to be after, because it reads the finished
 * depth and colour of the geometry it is shading; and it has to be before, because the sky must not be
 * occluded by the hill in front of it, rain must not be darkened by the ground it is falling past, and the
 * water surface is drawn with the terrain's depth beneath it rather than its own.
 *
 * <h2>Self-contained</h2>
 * Owns its shaders, its targets and an attributeless fullscreen-triangle VAO, and puts back the GL state it
 * changed — the same contract {@code Skybox} and {@code UIRenderer} follow, and necessary here because this
 * runs in the middle of the frame rather than at the end of it.
 */
public class GlobalIllumination {

    /**
     * Fraction of the window the gather runs at, in each axis.
     *
     * <p>A quarter of the pixels. Indirect light is low-frequency by nature — it is an integral over a whole
     * hemisphere, so it has no detail finer than the radius it gathers over — and the blur that follows
     * would throw away anything the extra resolution bought. This is the cheapest saving in the feature and
     * the first thing to spend if it ever needs to be more accurate.
     */
    public static final int RESOLUTION_DIVISOR = 2;

    /**
     * How sharply the blur refuses to cross a depth difference, per world unit.
     *
     * <p>Set against the gather radius rather than picked: a metre of depth gap is about where two surfaces
     * stop being the same surface at this scale. Higher keeps silhouettes crisper and lets more of the
     * gather's noise through.
     */
    private static final float BLUR_DEPTH_SHARPNESS = 1.6f;

    /**
     * Seed for the sample kernel.
     *
     * <p>Fixed, for the reason every scatter in this project is: the same build must produce the same
     * frame. A kernel reseeded per run would make an A/B render of an unrelated change differ by a wash of
     * noise, and there is no way to tell that apart from the change having done something.
     */
    private static final long KERNEL_SEED = 0x51_ADE_5EEDL;

    private final Shader gather;
    private final Shader blur;
    private final Shader apply;
    private final int vao;

    /** The gather target and the blur's second buffer — a separable blur cannot work in place. */
    private final int[] fbo = new int[2];
    private final int[] tex = new int[2];
    private int width;
    private int height;

    private final Vector3f[] kernel;

    /** Scratch, reused every frame: these are consumed synchronously by {@code setUniform}. */
    private final Matrix4f scratchInverse = new Matrix4f();

    public GlobalIllumination(int sceneWidth, int sceneHeight) {
        gather = new Shader("/shaders/fullscreen.vert", "/shaders/gi.frag");
        blur = new Shader("/shaders/fullscreen.vert", "/shaders/gi_blur.frag");
        apply = new Shader("/shaders/fullscreen.vert", "/shaders/gi_apply.frag");
        vao = glGenVertexArrays();
        kernel = buildKernel();
        create(sceneWidth, sceneHeight);
    }

    /**
     * A hemisphere of sample offsets about +Z, bunched toward the origin.
     *
     * <p>The bunching is the point. Distributed evenly through the hemisphere's volume, most taps land near
     * its outer shell — which is where a surface is least likely to be occluded and where the depth-gap
     * rejection throws the answer away anyway. Scaling by the square of the sample's index puts most of them
     * close in, where the creases that this effect is for actually are.
     *
     * <p>Built on the CPU rather than hashed per pixel: a fixed set is reproducible frame to frame, so what
     * moves in the image is the scene rather than the sampling, and it costs nothing per fragment.
     */
    private static Vector3f[] buildKernel() {
        Random random = new Random(KERNEL_SEED);
        Vector3f[] samples = new Vector3f[GiSettings.MAX_SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            Vector3f sample = new Vector3f(
                    random.nextFloat() * 2f - 1f,
                    random.nextFloat() * 2f - 1f,
                    // Never negative: a sample behind the surface is inside it, and would report the surface
                    // as occluding itself everywhere.
                    random.nextFloat());
            sample.normalize();
            sample.mul(random.nextFloat());

            // Bunched toward the origin, but far less hard than the textbook 0.1..1 ramp. That figure comes
            // from scenes built out of unit cubes; this world is metres, its terrain cells are a metre
            // across and its rocks are about one across, so a kernel whose taps average a fifth of the
            // radius spends most of them inside the cell they started in and finds nothing.
            float t = i / (float) samples.length;
            sample.mul(0.35f + 0.65f * t * t);
            samples[i] = sample;
        }
        return samples;
    }

    private void create(int sceneWidth, int sceneHeight) {
        width = Math.max(1, sceneWidth / RESOLUTION_DIVISOR);
        height = Math.max(1, sceneHeight / RESOLUTION_DIVISOR);

        for (int i = 0; i < fbo.length; i++) {
            fbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i]);
            tex[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
            // Linear, because the apply pass reads this at full resolution and the interpolation is the
            // upsample.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[i], 0);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void resize(int sceneWidth, int sceneHeight) {
        if (width == Math.max(1, sceneWidth / RESOLUTION_DIVISOR)
                && height == Math.max(1, sceneHeight / RESOLUTION_DIVISOR)) {
            return;
        }
        deleteTargets();
        create(sceneWidth, sceneHeight);
    }

    /**
     * Gathers indirect light from the frame drawn so far and blends it onto {@code scene}.
     *
     * <p>Leaves {@code scene} bound with its geometry target switched off, which is what every pass after
     * this one wants: they draw colour only, and an enabled draw buffer a shader declares no output for gets
     * undefined values.
     *
     * @param scene    the HDR scene buffer, mid-frame, with every opaque pass drawn into it. Must have been
     *                 built with a geometry attachment; without one this is a no-op, so a preview or a tool
     *                 that never asked for one is not obliged to know this exists
     * @param settings the strengths and radius — {@link GiSettings#OFF} skips the work entirely
     */
    public void render(Framebuffer scene, Camera camera, GiSettings settings) {
        if (!scene.hasGeometry() || !settings.isActive()) {
            return;
        }

        Matrix4f projection = camera.getProjectionMatrix();

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);

        // --- gather --------------------------------------------------------------------------------
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
        glViewport(0, 0, width, height);
        gather.use();
        bindTexture(scene.depthTexture(), 0);
        gather.setUniform("sceneDepth", 0);
        bindTexture(scene.geometryTexture(), 1);
        gather.setUniform("sceneGeometry", 1);
        bindTexture(scene.colorTexture(), 2);
        gather.setUniform("sceneColor", 2);
        gather.setUniform("projection", projection);
        gather.setUniform("invProjection", projection.invert(scratchInverse));
        gather.setUniform("view", camera.getViewMatrix());
        gather.setUniform("sceneTexel", new Vector2f(1f / scene.width(), 1f / scene.height()));
        gather.setUniform("radius", settings.radius());
        gather.setUniform("bias", settings.bias());
        gather.setUniform("fillFloor", settings.fillFloor());
        gather.setUniform("range", settings.range());
        gather.setUniform("sampleCount", settings.samples());
        for (int i = 0; i < settings.samples(); i++) {
            gather.setUniform("kernel[" + i + "]", kernel[i]);
        }
        draw();

        // --- blur, across then down ----------------------------------------------------------------
        blur.use();
        bindTexture(scene.depthTexture(), 1);
        blur.setUniform("sceneDepth", 1);
        blur.setUniform("near", camera.getNearPlane());
        blur.setUniform("far", camera.getFarPlane());
        blur.setUniform("depthSharpness", BLUR_DEPTH_SHARPNESS);

        blurInto(1, 0, new Vector2f(1f / width, 0f));
        blurInto(0, 1, new Vector2f(0f, 1f / height));

        // --- apply ---------------------------------------------------------------------------------
        // Colour only from here on: the geometry buffer has been read and everything that draws after this
        // is translucent, so leaving its draw buffer enabled would have each of those passes' blend
        // functions applied to a normal.
        scene.bindColorOnly();
        glEnable(GL_BLEND);
        // scene * (bounce + visibility) — see gi_apply.frag for why both terms scale what is already there
        // rather than one of them being added over it. The scene's alpha is left as drawn: a sprite's 0 keeps it out
        // of the bloom.
        glBlendFuncSeparate(GL_DST_COLOR, GL_SRC_ALPHA, GL_ZERO, GL_ONE);
        apply.use();
        bindTexture(tex[0], 0);
        apply.setUniform("gi", 0);
        apply.setUniform("occlusion", settings.occlusion());
        apply.setUniform("bounce", settings.bounce());
        draw();

        glDisable(GL_BLEND);
        glBindVertexArray(0);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void blurInto(int target, int source, Vector2f step) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[target]);
        glViewport(0, 0, width, height);
        bindTexture(tex[source], 0);
        blur.setUniform("source", 0);
        blur.setUniform("texelStep", step);
        draw();
    }

    private void draw() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void bindTexture(int texture, int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    private void deleteTargets() {
        for (int i = 0; i < fbo.length; i++) {
            glDeleteFramebuffers(fbo[i]);
            glDeleteTextures(tex[i]);
        }
    }

    public void delete() {
        deleteTargets();
        glDeleteVertexArrays(vao);
        gather.delete();
        blur.delete();
        apply.delete();
    }
}
