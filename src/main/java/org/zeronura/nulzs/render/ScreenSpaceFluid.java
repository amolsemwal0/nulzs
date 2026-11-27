package org.zeronura.nulzs.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws a cloud of fluid particles as a smooth liquid surface, without ever building one.
 *
 * <h2>What it is for</h2>
 * The particle solver next door produces spray: thousands of droplets in the air off a waterfall's lip or a
 * vent's mouth. There is no good way to draw that as geometry. Billboarded sprites read as a hail of
 * pellets, however carefully they are shaded, because a sprite has a silhouette of its own and a fluid does
 * not. Turning the particles into a mesh — marching cubes over their density field — is the textbook answer
 * and it is a rebuild of a few hundred thousand triangles every frame on the CPU, for fluid that is on
 * screen for a fraction of a second.
 *
 * <p>Screen-space fluid rendering sidesteps both. The surface is only ever constructed <b>where it can be
 * seen</b>, at the resolution it is seen at, as an image:
 *
 * <ol>
 *   <li><b>Depth.</b> Every particle is drawn as an analytic sphere and the depth buffer keeps the nearest
 *       at each pixel. What comes out is a picture of the fluid's surface — bumpy, at exactly the scale of
 *       one particle.</li>
 *   <li><b>Blur.</b> Those bumps are filtered out of the depth <i>image</i>, edge-preserving so the
 *       silhouette survives. This is the step that turns a bag of spheres into a body of water, and it
 *       costs a fixed number of taps per pixel rather than anything proportional to the particle count.</li>
 *   <li><b>Thickness.</b> Every particle's chord is summed additively, giving how much fluid is along each
 *       ray — which is what Beer's law wants, and therefore where the colour and the opacity come from.</li>
 *   <li><b>Composite.</b> The surface's normal is the gradient of the smoothed depth, taken per pixel. A
 *       normal and a distance is everything shading needs, so the fluid is lit, tinted by its thickness and
 *       blended over the scene without a single triangle of it having existed.</li>
 * </ol>
 *
 * <h2>Half resolution, and why that is not a compromise</h2>
 * Every target here is half the width and half the height of the scene. The whole pipeline's second step is
 * a blur, so detail finer than the filter is thrown away by the next pass whatever resolution it was
 * computed at — this is the same argument the water's planar reflection makes, and it is stronger here. It
 * quarters the cost of the two passes that scale with particle count.
 *
 * <h2>Self-contained</h2>
 * Owns its four shaders, its own framebuffers and its own instance buffer, and records and restores every
 * GL state it touches — the same contract {@code Skybox}, {@code PostProcessor} and {@code RainRenderer}
 * follow. It knows nothing about the solver: it takes a buffer of {@code position + radius + fluid index}
 * and a palette of {@link FluidAppearance}, which is the same palette {@code FluidRenderer} draws the pooled
 * surface from, so a droplet and the pond it lands in are the same colour by construction rather than by
 * two tunings agreeing.
 */
public class ScreenSpaceFluid {

    /** Floats per particle: position (3), radius, fluid index. Must match {@code SphFluid}. */
    private static final int FLOATS_PER_INSTANCE = 5;

    /** Palette slots the composite shader declares. Raising it means editing {@code sph_composite.frag}. */
    public static final int MAX_FLUIDS = 4;

    /**
     * What the depth target is cleared to — "no fluid on this pixel".
     *
     * <p>Far beyond any view distance the game offers, so it can never collide with a real reading, and
     * finite rather than infinity because it is compared against and averaged near in the blur, where an
     * infinity would produce a NaN that spreads.
     */
    private static final float BACKGROUND = 1.0e9f;

    /**
     * Spatial falloff of the blur, as the coefficient of the squared tap offset in texels.
     *
     * <p>Tuned against the eight-tap radius the shader is fixed at: at this value the outermost tap is worth
     * about a fiftieth of the centre, so the kernel is effectively finished inside its own radius and
     * widening it further would buy nothing. Smaller and the fluid stays visibly lumpy; larger and it turns
     * into a puddle of jelly with no droplets left in it at all.
     */
    private static final float SPATIAL_SIGMA = 0.06f;

    /**
     * How much depth difference the filter treats as a different surface, as the coefficient of the squared
     * difference in world units.
     *
     * <p>This is the constant that decides whether the technique works. Too small and the range term rejects
     * everything, the blur does nothing, and the fluid renders as spheres; too large and it stops being
     * bilateral, drags the far surface into the near one, and draws the halo of stretched geometry around
     * every silhouette that this whole family of filter exists to avoid. At this value two points a quarter
     * of a unit apart in depth are worth about a tenth of each other, which is roughly a particle's radius —
     * i.e. it smooths within a droplet and refuses to smooth between two that merely overlap on screen.
     */
    private static final float DEPTH_SIGMA = 40f;

    /**
     * Scales the raw chord length into the thickness the composite's absorption expects.
     *
     * <p>Separate from the absorption constant even though the two multiply together, because they answer
     * different questions and are re-tuned for different reasons: this one keeps the accumulated sum inside
     * a sensible numeric range for a 16-bit float target — a deep clump is hundreds of overlapping chords —
     * while absorption is art direction about how quickly a fluid saturates with its own colour.
     */
    private static final float THICKNESS_SCALE = 0.35f;

    /**
     * How fast fluid saturates with its own colour, per unit of accumulated thickness.
     *
     * <p>The art-direction half of the pair above. At this value the thin edge of a splash — a couple of
     * particles deep — is nearly clear and reads as spray, while the body of a jet is solidly its own
     * colour. Judge it from a render of a splash rather than from the number: it is what makes the
     * difference between water and a blue balloon.
     */
    private static final float ABSORPTION = 0.9f;

    private final Shader depthShader;
    private final Shader thicknessShader;
    private final Shader blurShader;
    private final Shader compositeShader;

    private final int vao;
    private final int instanceVbo;
    private final int capacity;

    private int width;
    private int height;

    /** RG32F: eye distance and fluid index, with a real depth attachment so spheres sort against each other. */
    private int surfaceFbo;
    private int surfaceTexture;
    private int surfaceDepth;
    /** The blur's other half — two targets, ping-ponged, because a separable filter cannot work in place. */
    private int blurFbo;
    private int blurTexture;
    /** R16F, additive. */
    private int thicknessFbo;
    private int thicknessTexture;

    private FluidAppearance[] palette = new FluidAppearance[0];

    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();

    /**
     * @param maxParticles upper bound on particles ever submitted; the instance buffer is sized to it once
     * @param width        scene width in pixels — the targets are built at half of it
     */
    public ScreenSpaceFluid(int maxParticles, int width, int height) {
        this.capacity = maxParticles;
        depthShader = new Shader("/shaders/sph_sphere.vert", "/shaders/sph_depth.frag");
        thicknessShader = new Shader("/shaders/sph_sphere.vert", "/shaders/sph_thickness.frag");
        blurShader = new Shader("/shaders/fullscreen.vert", "/shaders/sph_blur.frag");
        compositeShader = new Shader("/shaders/fullscreen.vert", "/shaders/sph_composite.frag");

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        instanceVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) maxParticles * FLOATS_PER_INSTANCE * Float.BYTES,
                GL_STREAM_DRAW);
        int stride = FLOATS_PER_INSTANCE * Float.BYTES;
        glVertexAttribPointer(0, 4, GL_FLOAT, false, stride, 0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        // One set of attributes per particle rather than per vertex — the quad's six corners come from
        // gl_VertexID, so a particle is five floats and no geometry at all.
        glVertexAttribDivisor(0, 1);
        glVertexAttribDivisor(1, 1);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        createTargets(width, height);
    }

    /** Installs how each fluid looks. The same palette the pooled surface is drawn from — see the class note. */
    public void setPalette(FluidAppearance... appearances) {
        if (appearances.length > MAX_FLUIDS) {
            throw new IllegalArgumentException(
                    "sph_composite.frag declares " + MAX_FLUIDS + " palette slots, got " + appearances.length);
        }
        this.palette = appearances.clone();
    }

    private void createTargets(int sceneWidth, int sceneHeight) {
        // At least one pixel, or a minimised window makes an incomplete framebuffer.
        this.width = Math.max(1, sceneWidth / 2);
        this.height = Math.max(1, sceneHeight / 2);

        surfaceFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, surfaceFbo);
        // RG32F rather than a 16-bit format: this holds a linear distance in world units out to the view
        // distance, and half floats run out of mantissa in the hundreds — which would quantise a distant
        // splash's depth into steps large enough for the normal reconstruction to read as facets.
        surfaceTexture = colorTarget(GL_RG32F, GL_RG);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, surfaceTexture, 0);
        surfaceDepth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, surfaceDepth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, surfaceDepth);
        require("screen-space fluid surface");

        blurFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, blurFbo);
        blurTexture = colorTarget(GL_RG32F, GL_RG);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, blurTexture, 0);
        require("screen-space fluid blur");

        thicknessFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, thicknessFbo);
        thicknessTexture = colorTarget(GL_R16F, GL_RED);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, thicknessTexture, 0);
        require("screen-space fluid thickness");

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }

    private int colorTarget(int internalFormat, int format) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, GL_FLOAT,
                (ByteBuffer) null);
        // Linear, deliberately: the composite reads this at full resolution, so the upsample is where the
        // last of the particle-scale stepping is smoothed out. Nearest here draws the half-resolution grid
        // as visible blocks along the fluid's silhouette.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static void require(String what) {
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(what + " framebuffer incomplete");
        }
    }

    /** Rebuilds the targets when the window changes size, if it actually did. */
    public void resize(int sceneWidth, int sceneHeight) {
        if (Math.max(1, sceneWidth / 2) == width && Math.max(1, sceneHeight / 2) == height) {
            return;
        }
        deleteTargets();
        createTargets(sceneWidth, sceneHeight);
    }

    /**
     * Runs the whole chain and blends the result into whatever framebuffer is bound on the way in.
     *
     * <p>Must be called with the scene's own target bound and the opaque passes finished — it reads the
     * scene's depth to reject fluid behind terrain, and it blends over the scene's colour. The depth is read
     * in the <b>particle</b> passes rather than at composite time, which is what keeps this from ever
     * sampling a texture attached to the framebuffer it is writing to.
     *
     * @param instances     particle stream, flipped, holding {@code count} lots of position + radius + fluid
     * @param sceneDepthTex the opaque scene's depth texture
     * @param targetFbo     the framebuffer to blend into, and the viewport to restore
     */
    public void render(Camera camera, SceneLighting lighting, FloatBuffer instances, int count,
                       int sceneDepthTex, int targetFbo, int targetWidth, int targetHeight) {
        if (count <= 0 || palette.length == 0) {
            return;
        }
        int drawn = Math.min(count, capacity);

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, instances);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Culling off throughout: every quad here is view-facing and generated from gl_VertexID, so its
        // winding is whatever the corner table happens to give and is nobody's business but this file's.
        // The 2D UI layer had exactly this bug — a triangle wound the other way vanished entirely.
        glDisable(GL_CULL_FACE);
        glViewport(0, 0, width, height);

        depthPass(camera, sceneDepthTex, drawn);
        blurPass();
        thicknessPass(camera, sceneDepthTex, drawn);
        compositePass(camera, lighting, targetFbo, targetWidth, targetHeight);

        glEnable(GL_CULL_FACE);
    }

    /** Nearest particle surface per pixel, sorted by a real depth test. */
    private void depthPass(Camera camera, int sceneDepthTex, int count) {
        glBindFramebuffer(GL_FRAMEBUFFER, surfaceFbo);
        // Cleared to "nothing here" and a fluid index of zero. The index does not matter where the distance
        // is the background — every reader tests the distance first — but leaving it undefined is the kind
        // of thing that starts mattering the moment somebody adds a reader that does not.
        glClearColor(BACKGROUND, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glDisable(GL_BLEND);

        depthShader.use();
        depthShader.setUniform("view", camera.getViewMatrix());
        depthShader.setUniform("projection", camera.getProjectionMatrix());
        depthShader.setUniform("sceneDepth", 0);
        depthShader.setUniform("invTargetSize", new org.joml.Vector2f(1f / width, 1f / height));
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneDepthTex);

        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
        glBindVertexArray(0);
    }

    /** Two edge-preserving passes over the depth image, one per axis. */
    private void blurPass() {
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);

        blurShader.use();
        blurShader.setUniform("source", 0);
        blurShader.setUniform("spatialSigma", SPATIAL_SIGMA);
        blurShader.setUniform("depthSigma", DEPTH_SIGMA);
        blurShader.setUniform("background", BACKGROUND);
        glActiveTexture(GL_TEXTURE0);

        glBindFramebuffer(GL_FRAMEBUFFER, blurFbo);
        blurShader.setUniform("direction", new org.joml.Vector2f(1f / width, 0f));
        glBindTexture(GL_TEXTURE_2D, surfaceTexture);
        fullscreen();

        glBindFramebuffer(GL_FRAMEBUFFER, surfaceFbo);
        blurShader.setUniform("direction", new org.joml.Vector2f(0f, 1f / height));
        glBindTexture(GL_TEXTURE_2D, blurTexture);
        fullscreen();
    }

    /** Every particle's chord, summed. No depth test — this is a sum through the body, not a surface. */
    private void thicknessPass(Camera camera, int sceneDepthTex, int count) {
        glBindFramebuffer(GL_FRAMEBUFFER, thicknessFbo);
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);

        thicknessShader.use();
        thicknessShader.setUniform("view", camera.getViewMatrix());
        thicknessShader.setUniform("projection", camera.getProjectionMatrix());
        thicknessShader.setUniform("sceneDepth", 0);
        thicknessShader.setUniform("invTargetSize", new org.joml.Vector2f(1f / width, 1f / height));
        thicknessShader.setUniform("thicknessScale", THICKNESS_SCALE);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneDepthTex);

        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
        glBindVertexArray(0);
    }

    /** Reconstructs, shades and blends. The only pass that touches the caller's framebuffer. */
    private void compositePass(Camera camera, SceneLighting lighting, int targetFbo, int targetWidth,
                               int targetHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, targetWidth, targetHeight);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // Depth test off and depth writes off. The rejection against the scene already happened, in the
        // particle passes and against a texture belonging to another framebuffer; doing it here would mean
        // sampling this target's own depth attachment, which is undefined behaviour.
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);

        compositeShader.use();
        camera.getProjectionMatrix().invert(inverseProjection);
        camera.getViewMatrix().invert(inverseView);
        compositeShader.setUniform("invProjection", inverseProjection);
        compositeShader.setUniform("invView", inverseView);
        compositeShader.setUniform("invTargetSize", new org.joml.Vector2f(1f / width, 1f / height));
        compositeShader.setUniform("viewPos", camera.getPosition());
        compositeShader.setUniform("background", BACKGROUND);
        compositeShader.setUniform("absorption", ABSORPTION);

        compositeShader.setUniform("toSun", lighting.toSun());
        compositeShader.setUniform("sunColor", lighting.sunColor());
        compositeShader.setUniform("ambientColor", lighting.ambientColor());
        compositeShader.setUniform("fogColor", lighting.fogColor());
        compositeShader.setUniform("fogStart", lighting.fogStart());
        compositeShader.setUniform("fogEnd", lighting.fogEnd());
        compositeShader.setUniform("fogHorizon", lighting.fogHorizon());
        compositeShader.setUniform("fogHeightFalloff", lighting.fogHeightFalloff());
        compositeShader.setUniform("fogBaseY", lighting.fogBaseY());
        compositeShader.setUniform("saturation", lighting.saturation());
        compositeShader.setUniform("skyZenithColor", lighting.skyZenith());
        compositeShader.setUniform("skyMiddleColor", lighting.skyMiddle());
        compositeShader.setUniform("skyHorizonColor", lighting.skyHorizon());
        compositeShader.setUniform("sunDirection", lighting.sunDirection());
        uploadPalette();

        compositeShader.setUniform("fluidSurface", 0);
        compositeShader.setUniform("fluidThickness", 1);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, surfaceTexture);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, thicknessTexture);
        glActiveTexture(GL_TEXTURE0);

        fullscreen();

        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    private void uploadPalette() {
        for (int i = 0; i < palette.length; i++) {
            FluidAppearance a = palette[i];
            compositeShader.setUniform("shallowColor[" + i + "]", a.shallowColor());
            compositeShader.setUniform("deepColor[" + i + "]", a.deepColor());
            compositeShader.setUniform("emberColor[" + i + "]", a.emberColor());
            compositeShader.setUniform("hotColor[" + i + "]", a.hotColor());
            compositeShader.setUniform("emissive[" + i + "]", a.emissive());
            compositeShader.setUniform("sprayOpacity[" + i + "]", a.sprayOpacity());
            compositeShader.setUniform("specular[" + i + "]", a.specular());
        }
    }

    /** The attributeless fullscreen triangle {@code fullscreen.vert} generates. */
    private void fullscreen() {
        glBindVertexArray(emptyVao());
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
    }

    private int emptyVao = 0;

    private int emptyVao() {
        if (emptyVao == 0) {
            emptyVao = glGenVertexArrays();
        }
        return emptyVao;
    }

    /** The smoothed depth image, for a preview that wants to show what the technique is actually doing. */
    public int surfaceTexture() {
        return surfaceTexture;
    }

    /** The accumulated thickness, likewise. */
    public int thicknessTexture() {
        return thicknessTexture;
    }

    public void delete() {
        deleteTargets();
        if (emptyVao != 0) {
            glDeleteVertexArrays(emptyVao);
        }
        glDeleteBuffers(instanceVbo);
        glDeleteVertexArrays(vao);
        depthShader.delete();
        thicknessShader.delete();
        blurShader.delete();
        compositeShader.delete();
    }

    private void deleteTargets() {
        glDeleteFramebuffers(surfaceFbo);
        glDeleteFramebuffers(blurFbo);
        glDeleteFramebuffers(thicknessFbo);
        glDeleteTextures(surfaceTexture);
        glDeleteTextures(blurTexture);
        glDeleteTextures(thicknessTexture);
        glDeleteRenderbuffers(surfaceDepth);
    }
}
