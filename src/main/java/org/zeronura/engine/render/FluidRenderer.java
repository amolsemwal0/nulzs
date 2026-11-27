package org.zeronura.engine.render;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws a translucent fluid surface from a mesh rebuilt each frame.
 *
 * <p>Self-contained like {@code Skybox} and {@code RainRenderer}: it owns its shader and a
 * {@code GL_STREAM_DRAW} VBO, and it records and restores every GL state it touches. The caller fills a
 * {@link FloatBuffer} with the surface of whatever fluid it has — this class knows nothing about ponds,
 * lava, the simulation or the world, only how to draw {@code position + normal + depth + fluid index}
 * vertices against a palette of {@link FluidAppearance}s, the same layering rule that keeps
 * {@code Renderer} taking {@code SceneLighting}.
 *
 * <p>The index is what makes this a fluid renderer rather than a water renderer: a vertex says <i>which</i>
 * fluid it is and the shader looks up how that one behaves, so adding a third liquid is a palette entry
 * rather than another crossfade branch.
 *
 * <p>Drawn <b>after</b> the opaque terrain, props and character and <b>before</b> the sky, with blending
 * on and depth-writes off: fluid is transparent, so it must test against the depth the opaque passes wrote
 * (a hill in front occludes the pond) without writing depth itself (so the sky still fills behind it and
 * overlapping surfaces do not fight the depth buffer). Culling is left off — a surface seen from below
 * (the player's eyes dipping under, or standing behind a waterfall) should still draw.
 */
public class FluidRenderer {

    /**
     * position xyz + normal xyz + depth + fluid index + flow xz. Must match
     * {@code FluidSim.FLOATS_PER_VERTEX}.
     */
    private static final int FLOATS_PER_VERTEX = 10;

    /** Palette slots the shader declares. Raising it means editing {@code fluid.frag}'s array size too. */
    public static final int MAX_FLUIDS = 4;

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final int capacityVertices;

    private FluidAppearance[] palette = new FluidAppearance[0];

    /**
     * @param maxVertices upper bound on vertices the caller will ever submit; the VBO is sized to it once
     */
    public FluidRenderer(int maxVertices) {
        this.capacityVertices = maxVertices;
        shader = new Shader("/shaders/fluid.vert", "/shaders/fluid.frag");

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) maxVertices * FLOATS_PER_VERTEX * Float.BYTES, GL_STREAM_DRAW);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 7L * Float.BYTES);
        glVertexAttribPointer(4, 2, GL_FLOAT, false, stride, 8L * Float.BYTES);
        for (int i = 0; i <= 4; i++) {
            glEnableVertexAttribArray(i);
        }
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Installs how each fluid looks, indexed to match the vertex data's fluid index.
     *
     * <p>Set once by the game rather than passed per frame: a palette is a property of the world's
     * materials, not of a frame, and uploading four vec3s per fluid every frame to say the same thing
     * would be work done to accomplish nothing.
     */
    public void setPalette(FluidAppearance... appearances) {
        if (appearances.length > MAX_FLUIDS) {
            throw new IllegalArgumentException(
                    "fluid.frag declares " + MAX_FLUIDS + " palette slots, got " + appearances.length);
        }
        this.palette = appearances.clone();
    }

    /**
     * Uploads and draws the surface. {@code vertices} must be flipped and hold {@code vertexCount} vertices
     * of {@code position + normal + depth + fluid + flow}. A zero count is a no-op — a world with no fluid
     * in range pays nothing at all.
     */
    /**
     * Installs the planar reflection for the next draw, or clears it.
     *
     * <p>Set per frame rather than passed to {@link #render}, because whether there is a reflection at all
     * is a property of the frame — a viewpoint with no water on screen renders no mirror and the water
     * shader falls back to the sky.
     *
     * @param reflection the mirrored render, or null for none
     */
    public void setReflection(PlanarReflection reflection) {
        this.reflection = reflection != null && !Float.isNaN(reflection.planeY()) ? reflection : null;
    }

    private PlanarReflection reflection;

    /**
     * Installs the scene's shadow map, so the surface is shadowed like everything else that is lit.
     *
     * <p>Null means "no shadow pass ran", which is not a degraded mode but the honest one: the map's
     * contents are undefined if nothing drew into it, so the strength is forced to zero and the shader
     * takes its early-out. That is the same contract {@code SceneLighting.shadowStrength} already has, and
     * it is what lets every preview that renders fluid without a shadow pass go on working unchanged.
     *
     * <p>Set once rather than passed per frame, like {@link #setReflection}, because the map is a
     * long-lived object the renderer owns.
     */
    public void setShadowMap(ShadowMap shadowMap) {
        this.shadowMap = shadowMap;
    }

    private ShadowMap shadowMap;

    /**
     * A copy of the opaque scene's depth, so the surface can tell where it meets anything solid.
     *
     * <h2>Why a copy and not the depth buffer itself</h2>
     * The fluid pass draws into the scene framebuffer and depth-<i>tests</i> against its depth attachment.
     * Sampling that same attachment while it is attached is a <b>feedback loop</b> — undefined behaviour
     * of the sort that works perfectly on the driver it was written against and produces garbage on the
     * next one. GL 4.4 has read-only depth attachments for exactly this; on 3.3 core the way out is to
     * blit the depth somewhere else first, which is a full-screen depth copy per frame and the price of
     * the effect.
     *
     * <p>Owned here rather than by the caller because nothing else wants it, and resized to follow the
     * source so a window resize cannot leave the surface comparing against a stale shape.
     */
    private int depthCopyTexture;
    private int depthCopyFbo;
    private int depthCopyWidth;
    private int depthCopyHeight;

    /**
     * Copies the depth of whatever is currently in {@code source} for this frame's fluid pass.
     *
     * <p>Call it <b>after every opaque pass and before drawing fluid</b>: what it captures is the shape the
     * water is going to be compared against, so anything drawn later — the sky, particles, the fluid itself
     * — is deliberately not in it. That ordering is also why the player and the creatures get an edge line
     * and the sky does not.
     */
    public void captureSceneDepth(Framebuffer source) {
        if (source == null) {
            hasSceneDepth = false;
            return;
        }
        captureSceneDepth(source.id(), source.width(), source.height());
    }

    /**
     * The same, from a framebuffer this engine does not wrap — what the preview tools render into.
     *
     * <p>Its depth may be a renderbuffer rather than a texture; a blit does not care, which is the other
     * reason this is a copy rather than a bind.
     */
    public void captureSceneDepth(int sourceFbo, int width, int height) {
        // Asked BEFORE anything else touches the binding. ensureDepthCopy leaves framebuffer 0 bound when
        // it builds the copy, so querying afterwards reads 0 and this method then "restores" the caller to
        // the window — which for a preview meant the whole of its first frame was drawn to the screen and
        // its PNG came out empty, and only its first, because every later call finds the copy already made.
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        ensureDepthCopy(width, height);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, depthCopyFbo);
        // GL_NEAREST is not a choice here: a depth blit may not filter, and asking for GL_LINEAR is an
        // error rather than a slightly softer copy.
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, previous);
        hasSceneDepth = true;
    }

    private boolean hasSceneDepth;

    private void ensureDepthCopy(int width, int height) {
        if (depthCopyFbo != 0 && depthCopyWidth == width && depthCopyHeight == height) {
            return;
        }
        if (depthCopyFbo != 0) {
            glDeleteFramebuffers(depthCopyFbo);
            glDeleteTextures(depthCopyTexture);
        }
        depthCopyWidth = width;
        depthCopyHeight = height;
        depthCopyTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthCopyTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0,
                GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // No comparison mode: this is sampled for a distance, not compared like the shadow map. Left in
        // compare mode it would return a 0/1 test result and the edge would be a hard stripe.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        depthCopyFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, depthCopyFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthCopyTexture, 0);
        // A depth-only framebuffer draws no colour, and a driver is entitled to call it incomplete unless
        // it is told so explicitly.
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Unit 3 — the first one this shader's own samplers (0, 1, 2) leave free. */
    private static final int SCENE_DEPTH_UNIT = 3;

    /**
     * Unit 4, deliberately the same one {@code Renderer} binds the shadow map to.
     *
     * <p>Not a coincidence worth avoiding: keeping one number for one texture across every shader that
     * samples it means a reader never has to work out which pass a unit belongs to, and it cannot collide
     * with this shader's own 0, 1 and 2.
     */
    private static final int SHADOW_TEXTURE_UNIT = 4;

    /**
     * The world's wind, in world units per second — the same velocity the clouds drift in and the grass
     * leans along. It ripples still water; a river's own current is separate and already per-vertex.
     */
    private org.joml.Vector2f wind = new org.joml.Vector2f();

    public void setWind(org.joml.Vector2f windVelocity) {
        this.wind = windVelocity == null ? new org.joml.Vector2f() : new org.joml.Vector2f(windVelocity);
    }

    public void render(Camera camera, SceneLighting lighting, FloatBuffer vertices, int vertexCount,
                       float time) {
        if (vertexCount <= 0) {
            return;
        }
        int drawn = Math.min(vertexCount, capacityVertices);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glEnable(GL_BLEND);
        // DUAL-SOURCE BLENDING: the destination factor is the shader's SECOND output rather than one minus
        // its alpha, so the fluid hides a different fraction of each channel. That is not a refinement — it
        // is the whole difference between water and a coloured pane, because water absorbs red several
        // times faster than blue and a sandy shallow therefore reads as turquoise rather than as sand under
        // a blue wash. See the outputs at the top of fluid.frag for the measurement that forced it.
        //
        // `result = src + dst * src1`, where the shader writes premultiplied colour in src and the
        // per-channel transmittance in src1. With three equal rates this is exactly the alpha blend it
        // replaced. Core since GL 3.3 (ARB_blend_func_extended), and it needs a single draw buffer, which
        // this pass has — see Framebuffer.bindColorOnly.
        //
        // The alpha channel is deliberately left alone (GL_ZERO/GL_ONE): nothing downstream reads the
        // scene buffer's alpha, and accumulating into it would be a silent way to break whatever one day
        // does.
        glBlendFuncSeparate(GL_ONE, GL_SRC1_COLOR, GL_ZERO, GL_ONE);
        glDepthMask(false);
        // Two-sided: the surface must draw whether seen from above or from below (eyes dipping under, or
        // standing behind a fall), and a volume boundary has faces pointing every way. The opaque passes
        // leave culling on, so this must switch it off explicitly and put it back.
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("viewPos", camera.getPosition());
        shader.setUniform("toSun", lighting.toSun());
        shader.setUniform("sunColor", lighting.sunColor());
        shader.setUniform("ambientColor", lighting.ambientColor());
        shader.setUniform("fogColor", lighting.fogColor());
        shader.setUniform("fogStart", lighting.fogStart());
        shader.setUniform("fogEnd", lighting.fogEnd());
        shader.setUniform("fogHorizon", lighting.fogHorizon());
        shader.setUniform("fogHeightFalloff", lighting.fogHeightFalloff());
        shader.setUniform("fogBaseY", lighting.fogBaseY());
        shader.setUniform("time", time);
        shader.setUniform("windVelocity", wind);
        // The sky the surface reflects. `sunDirection` is deliberately not `toSun`: the key light is
        // mirrored above the horizon at night so nothing is lit from below, and reflecting that would put a
        // sun path on the water at midnight.
        shader.setUniform("skyZenithColor", lighting.skyZenith());
        shader.setUniform("skyMiddleColor", lighting.skyMiddle());
        shader.setUniform("skyHorizonColor", lighting.skyHorizon());
        shader.setUniform("sunDirection", lighting.sunDirection());
        // Cast shadows. Water was the one lit surface in the scene that ignored them entirely — reported
        // from play as a pool under an overhang being lit as though it were in the open.
        if (shadowMap != null) {
            shadowMap.bind(SHADOW_TEXTURE_UNIT);
            shader.setUniform("shadowMap", SHADOW_TEXTURE_UNIT);
            shader.setUniform("lightSpaceMatrix", shadowMap.lightSpaceMatrix());
            shader.setUniform("shadowTexelUv", shadowMap.texelUvSize());
            shader.setUniform("shadowTexelWorld", shadowMap.texelWorldSize());
            shader.setUniform("shadowBias", shadowMap.depthBias());
            shader.setUniform("shadowStrength", lighting.shadowStrength());
        } else {
            shader.setUniform("shadowStrength", 0f);
        }
        // The scene's shape behind the water, which is what says how much fluid the light came through —
        // see captureSceneDepth and fluid.frag's sceneRayPath. Bound whether or
        // not there is one, so the sampler never points at a unit somebody else filled.
        shader.setUniform("hasSceneDepth", hasSceneDepth ? 1 : 0);
        shader.setUniform("sceneDepth", SCENE_DEPTH_UNIT);
        shader.setUniform("nearPlane", camera.getNearPlane());
        shader.setUniform("farPlane", camera.getFarPlane());
        if (hasSceneDepth) {
            glActiveTexture(GL_TEXTURE0 + SCENE_DEPTH_UNIT);
            glBindTexture(GL_TEXTURE_2D, depthCopyTexture);
            glActiveTexture(GL_TEXTURE0);
        }
        uploadPalette();
        bindTextures();

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, drawn);
        glBindVertexArray(0);

        // Back to the ordinary blend. Every other translucent pass in the frame — particles, rain, spray,
        // the UI — writes one output and expects one alpha, and a dual-source function left standing would
        // read whatever their unwritten second output happens to hold, which is undefined.
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    private void uploadPalette() {
        for (int i = 0; i < palette.length; i++) {
            FluidAppearance a = palette[i];
            shader.setUniform("shallowColor[" + i + "]", a.shallowColor());
            shader.setUniform("deepColor[" + i + "]", a.deepColor());
            shader.setUniform("emberColor[" + i + "]", a.emberColor());
            shader.setUniform("hotColor[" + i + "]", a.hotColor());
            shader.setUniform("emissive[" + i + "]", a.emissive());
            shader.setUniform("absorption[" + i + "]", a.absorption());
            shader.setUniform("specular[" + i + "]", a.specular());
            shader.setUniform("rippleScale[" + i + "]", a.rippleScale());
            shader.setUniform("foamColor[" + i + "]", a.foamColor());
            shader.setUniform("foamSpeed[" + i + "]", a.foamSpeed());
            shader.setUniform("flowLineColor[" + i + "]", a.flowLineColor());
        }
    }

    /**
     * Points the reflection sampler at the mirrored render, if there is one.
     *
     * <p>The unit is set whether or not a reflection exists: {@code hasReflection} of 0 means the shader's
     * branch is never taken, but a sampler uniform left at its default still names a unit some other pass
     * may have left an unrelated texture in, and leaving that dangling is how a "why is the water showing
     * bark" bug starts.
     *
     * <p>Unit 2, and the two below it are now free. Units 0 and 1 used to be water's shallow and deep
     * albedos (both measured as flat single colours, so they were 45 MB carrying two numbers — see
     * {@link FluidAppearance}); before that, 2 and 3 were the molten model's crust albedo and normal map.
     * Both sets are gone and the water body is a palette colour absorbed over the optical path instead.
     */
    private void bindTextures() {
        shader.setUniform("reflectionTex", 2);
        shader.setUniform("hasReflection", reflection == null ? 0 : 1);
        if (reflection != null) {
            shader.setUniform("reflectionViewProj", reflection.viewProjection());
            shader.setUniform("reflectionPlaneY", reflection.planeY());
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, reflection.texture());
        }
        glActiveTexture(GL_TEXTURE0);
    }

    public void delete() {
        if (depthCopyFbo != 0) {
            glDeleteFramebuffers(depthCopyFbo);
            glDeleteTextures(depthCopyTexture);
            depthCopyFbo = 0;
        }
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.delete();
    }
}
