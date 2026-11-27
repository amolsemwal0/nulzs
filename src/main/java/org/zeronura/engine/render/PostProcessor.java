package org.zeronura.engine.render;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Turns the HDR scene into the final image: bloom, filmic tone-mapping and a colour grade.
 *
 * <h2>Bloom by down- then up-sampling</h2>
 * A bright-pass keeps only the highlights, which are then downsampled through a small chain of
 * ever-smaller buffers and tent-filtered back up, each level adding its own radius of glow. This is the
 * cheap, smooth bloom used across modern engines — a single wide Gaussian would cost many more taps and a
 * plain box blur would look stepped.
 *
 * <h2>Self-contained</h2>
 * Owns its shaders, an attributeless fullscreen-triangle VAO (like {@code Skybox}), and the bloom chain's
 * colour-only FBOs. {@link #process} ends by drawing to the <b>default framebuffer</b> — the caller draws
 * the UI on top of that afterwards, so the HUD stays crisp and ungraded. Resizable to follow the window.
 */
public class PostProcessor {

    /** Bloom mip levels. Five gives a wide, soft glow; the smallest is a thumbnail. */
    private static final int LEVELS = 5;

    private final Shader prefilter;
    private final Shader tent;
    private final Shader composite;
    private final Shader fxaa;
    private final int vao;

    private final int[] fbo = new int[LEVELS];
    private final int[] tex = new int[LEVELS];
    private final int[] mipW = new int[LEVELS];
    private final int[] mipH = new int[LEVELS];

    /**
     * Where the composite goes when antialiasing is on, so FXAA has something to read.
     *
     * <p>Full size and {@code RGBA8} rather than the chain's {@code RGBA16F}: this holds the finished,
     * tone-mapped image, which is by definition in 0..1, and FXAA's thresholds are calibrated for display
     * values. Sized to whatever {@link #process} was last asked to write to rather than to the window,
     * because the previews render at their own sizes and a target that quietly disagreed with the viewport
     * would antialias a scaled copy of the frame.
     */
    private int aaFbo;
    private int aaTex;
    private int aaWidth;
    private int aaHeight;

    /** @see #setAntiAliasing(boolean) */
    private boolean antiAliasing;

    /** @see #setSunRays */
    private int sunRays;
    private final org.joml.Vector3f sunRayColor = new org.joml.Vector3f();
    /** @see #setSkyBeams */
    private int skyBeams;
    private final org.joml.Vector3f skyBeamColor = new org.joml.Vector3f();

    public PostProcessor(int width, int height) {
        prefilter = new Shader("/shaders/fullscreen.vert", "/shaders/bloom_prefilter.frag");
        tent = new Shader("/shaders/fullscreen.vert", "/shaders/bloom_tent.frag");
        composite = new Shader("/shaders/fullscreen.vert", "/shaders/composite.frag");
        fxaa = new Shader("/shaders/fullscreen.vert", "/shaders/fxaa.frag");
        vao = glGenVertexArrays();
        createChain(width, height);
    }

    private void createChain(int width, int height) {
        int w = width;
        int h = height;
        for (int i = 0; i < LEVELS; i++) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            mipW[i] = w;
            mipH[i] = h;

            fbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i]);
            tex[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[i], 0);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void resize(int width, int height) {
        if (mipW[0] == Math.max(1, width / 2) && mipH[0] == Math.max(1, height / 2)) {
            return;
        }
        for (int i = 0; i < LEVELS; i++) {
            glDeleteFramebuffers(fbo[i]);
            glDeleteTextures(tex[i]);
        }
        createChain(width, height);
    }

    /**
     * Whether the finished image gets a pass of FXAA on its way to the target.
     *
     * <p>Off by default, so every preview and every headless caller keeps producing the frame it always
     * did unless it asks otherwise — a preview whose antialiasing turned itself on would make every stored
     * A/B in this project differ by a pass nobody had asked about.
     *
     * <p>Unlike the texture settings this one takes effect on the next <i>frame</i>: it is a pass, not a
     * property baked into an object at upload.
     */
    public void setAntiAliasing(boolean antiAliasing) {
        this.antiAliasing = antiAliasing;
    }

    /** @see #setAntiAliasing(boolean) */
    public boolean isAntiAliasing() {
        return antiAliasing;
    }

    /**
     * Sun rays for the next {@link #process}: a {@link SunRays} texture and the colour one unit of it adds to the
     * picture, or texture 0 for none — which is what every preview and headless caller gets, unless it asks.
     */
    public void setSunRays(int texture, org.joml.Vector3f color) {
        sunRays = texture;
        sunRayColor.set(color);
    }

    /**
     * Beams in the sky for the next {@link #process}: {@link SunRays#renderBeams}' texture and the colour its full
     * strength adds, or texture 0 for none.
     */
    public void setSkyBeams(int texture, org.joml.Vector3f color) {
        skyBeams = texture;
        skyBeamColor.set(color);
    }

    /**
     * The LDR target the composite writes to when FXAA is on, made to fit whatever it is about to hold.
     *
     * <p>Allocated here rather than in {@link #resize} because the size that matters is the <i>target's</i>,
     * which is the window in a session and something else entirely in a preview. Nothing is allocated at
     * all while antialiasing is off.
     */
    private void ensureAaTarget(int width, int height) {
        if (aaFbo != 0 && aaWidth == width && aaHeight == height) {
            return;
        }
        deleteAaTarget();
        aaWidth = width;
        aaHeight = height;
        aaFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, aaFbo);
        aaTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, aaTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        // Linear, because FXAA's final read lands between texels by design — that sub-texel offset is the
        // whole mechanism, and a nearest fetch would snap it back to the pixel it started on and do
        // nothing at all.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, aaTex, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void deleteAaTarget() {
        if (aaFbo != 0) {
            glDeleteFramebuffers(aaFbo);
            glDeleteTextures(aaTex);
            aaFbo = 0;
            aaTex = 0;
        }
    }

    /**
     * Runs the pipeline and writes the finished image to {@code targetFbo} at {@code targetW×H}.
     *
     * @param sceneColorTexture the HDR scene, from a {@link Framebuffer}
     * @param targetFbo         where the composite goes — 0 for the window, or a preview's own FBO
     */
    /**
     * The whole chain with nothing submerged — what every preview and every headless caller wants.
     */
    public void process(int sceneColorTexture, PostSettings settings, int targetFbo,
                        int targetW, int targetH) {
        process(sceneColorTexture, settings, UnderwaterView.NONE, targetFbo, targetW, targetH);
    }

    /**
     * @param underwater what the camera is looking through — see {@link UnderwaterView}. Applied in the
     *                   composite, in linear light <b>before</b> the tone curve, because absorption
     *                   through water is something that happens to the light on its way to the lens and
     *                   not a filter laid over the photograph. After the curve it would flatten highlights
     *                   that the curve has already rolled off, and the deep end of a pond would come out
     *                   as a grey wash rather than as dark water.
     */
    public void process(int sceneColorTexture, PostSettings settings, UnderwaterView underwater,
                        int targetFbo, int targetW, int targetH) {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);

        // Bright pass into the top bloom level.
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
        glViewport(0, 0, mipW[0], mipH[0]);
        prefilter.use();
        bindTexture(sceneColorTexture, 0);
        prefilter.setUniform("scene", 0);
        prefilter.setUniform("threshold", settings.bloomThreshold());
        draw();

        // Downsample down the chain.
        tent.use();
        for (int i = 1; i < LEVELS; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i]);
            glViewport(0, 0, mipW[i], mipH[i]);
            bindTexture(tex[i - 1], 0);
            tent.setUniform("src", 0);
            tent.setUniform("srcTexel", new org.joml.Vector2f(1f / mipW[i - 1], 1f / mipH[i - 1]));
            draw();
        }

        // Upsample back up, adding each smaller level's glow onto the larger one.
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        for (int i = LEVELS - 2; i >= 0; i--) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i]);
            glViewport(0, 0, mipW[i], mipH[i]);
            bindTexture(tex[i + 1], 0);
            tent.setUniform("src", 0);
            tent.setUniform("srcTexel", new org.joml.Vector2f(1f / mipW[i + 1], 1f / mipH[i + 1]));
            draw();
        }
        glDisable(GL_BLEND);

        // Composite scene + bloom, tone-mapped and graded — to the target, or to the LDR buffer FXAA is
        // about to read. Antialiasing has to come after the grade rather than before it: contrast and the
        // tone curve are what decide how hard an edge actually is, so an edge detector run in linear light
        // would be looking at a different image from the one on screen.
        if (antiAliasing) {
            ensureAaTarget(targetW, targetH);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, antiAliasing ? aaFbo : targetFbo);
        glViewport(0, 0, targetW, targetH);
        composite.use();
        bindTexture(sceneColorTexture, 0);
        composite.setUniform("scene", 0);
        bindTexture(tex[0], 1);
        composite.setUniform("bloom", 1);
        composite.setUniform("exposure", settings.exposure());
        composite.setUniform("bloomIntensity", settings.bloomIntensity());
        composite.setUniform("contrast", settings.contrast());
        composite.setUniform("saturation", settings.saturation());
        composite.setUniform("colorFilter", settings.colorFilter());
        composite.setUniform("lift", settings.lift());
        composite.setUniform("vignette", settings.vignette());
        composite.setUniform("filmic", settings.filmic() ? 1 : 0);
        composite.setUniform("underwaterDepth", underwater.isSubmerged()
                ? underwater.depthBelowSurface() : 0f);
        composite.setUniform("underwaterColor", underwater.color());
        composite.setUniform("underwaterDensity", underwater.density());
        composite.setUniform("invViewProjection", underwater.invViewProjection());
        composite.setUniform("cameraPosition", underwater.cameraPosition());
        // Unit 2 — the composite's own samplers are the scene (0) and the bloom (1). Legal to sample here
        // where it was not during the fluid pass: by now the scene framebuffer is not the draw target, so
        // there is no feedback loop.
        bindTexture(underwater.sceneDepthTexture(), 2);
        composite.setUniform("sceneDepth", 2);
        composite.setUniform("hasSceneDepth", underwater.sceneDepthTexture() != 0 ? 1 : 0);
        bindTexture(sunRays, 3);
        composite.setUniform("sunRays", 3);
        composite.setUniform("sunRayColor", sunRayColor);
        composite.setUniform("sunRayMaxHaze", SunRays.MAX_HAZE);
        composite.setUniform("hasSunRays", sunRays != 0 ? 1 : 0);
        bindTexture(skyBeams, 4);
        composite.setUniform("skyBeams", 4);
        composite.setUniform("skyBeamColor", skyBeamColor);
        composite.setUniform("skyBeamMax", SunRays.BEAM_MAX);
        composite.setUniform("hasSkyBeams", skyBeams != 0 ? 1 : 0);
        draw();
        glActiveTexture(GL_TEXTURE0);

        if (antiAliasing) {
            glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
            glViewport(0, 0, targetW, targetH);
            fxaa.use();
            bindTexture(aaTex, 0);
            fxaa.setUniform("image", 0);
            fxaa.setUniform("texel", new org.joml.Vector2f(1f / targetW, 1f / targetH));
            draw();
        }

        glBindVertexArray(0);
    }

    private void draw() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void bindTexture(int texture, int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    public void delete() {
        for (int i = 0; i < LEVELS; i++) {
            glDeleteFramebuffers(fbo[i]);
            glDeleteTextures(tex[i]);
        }
        deleteAaTarget();
        glDeleteVertexArrays(vao);
        prefilter.delete();
        tent.delete();
        composite.delete();
        fxaa.delete();
    }
}
