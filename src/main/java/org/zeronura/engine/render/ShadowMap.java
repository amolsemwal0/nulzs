package org.zeronura.engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.asset.Model;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

/**
 * A single-cascade shadow map for the sun: the depth of the world as the key light sees it, rendered
 * once per frame and sampled by every scene shader.
 *
 * <h2>One cascade, centred on the player</h2>
 * Cascaded maps exist to keep texel density high across a long view distance. This world's view distance
 * is already short — aerial perspective saturates at 96 units and the far plane is 100 — so one map
 * covering a {@value #RADIUS}-unit radius around the player gives roughly {@code 2*RADIUS/RESOLUTION}
 * world units per texel, which is finer than a cascade split would be worth. Shadows fade out toward the
 * edge of that box (in the shaders, not here) rather than stopping at a visible square boundary.
 *
 * <h2>The box is snapped to the texel grid</h2>
 * {@link #begin} rounds the light-space centre to whole texels. Without that, walking moves the box by a
 * fraction of a texel each frame, every shadow edge re-rasterises slightly differently, and the result is
 * a constant crawling shimmer along every silhouette — far more distracting than the shadows are worth.
 * The rotation depends only on the light direction, so the snap is a pure translation in light space.
 *
 * <h2>The depth texture is a comparison sampler, and that is what makes edges smooth</h2>
 * The map is sampled as a {@code sampler2DShadow} with {@code GL_COMPARE_REF_TO_TEXTURE} and
 * {@code GL_LINEAR}, so the depth test happens per texel <em>inside</em> the filter and one tap returns a
 * bilinear blend of four already-compared texels. Comparing in the shader against a {@code GL_NEAREST}
 * fetch instead — which is what this did first — makes every tap binary, so the 3x3 kernel in the
 * receiving shaders can only report one of ten values and each tap snaps to a whole texel: the shadow
 * edge then shows the map's own pixel grid as a staircase, which was reported as exactly that. Note the
 * ordering is the point; averaging raw depths and comparing once is not the same thing and is wrong,
 * since the average of two depths is a depth no surface here actually has.
 *
 * <h2>Self-contained GL state</h2>
 * {@link #begin} records the bound framebuffer and viewport and {@link #end} restores exactly those,
 * rather than assuming the default framebuffer and the window size. That is what lets the offscreen
 * preview tools — which render the whole scene into their own FBO — take a shadow pass without
 * knowing they need to put anything back, the same contract {@code Skybox} and {@code UIRenderer} follow.
 */
public class ShadowMap {

    /**
     * Depth texture size. 4096² over a 112-unit box is ~2.7 cm per texel.
     *
     * <p>Raised from 2048 because the casters were being recorded far better than they were being read.
     * The depth map showed trunks and individual leaf cards in clean detail, but a whole tree canopy landed
     * on only ~60 texels, so the receiving kernel — which spans several of them — smeared that detail into
     * soft blobs and a tree's shadow stopped looking like a tree. Doubling the density is what buys the
     * <i>shape</i>; the kernel below is what stops it being blurred away again.
     */
    public static final int RESOLUTION = 4096;

    /**
     * The map's actual side, which the shadow-quality setting may lower from {@link #RESOLUTION}.
     *
     * <p>An instance field rather than the constant, because this is the largest single lever on the
     * frame after the sky: the pass is bounded by fill on the leaf cutout and by vertex count on the bark,
     * and dropping the side halves the first outright.
     *
     * <p><b>What it costs is the shape of a shadow, not its presence</b>, and that is measured rather than
     * argued — see the class note above. At 2048 a whole tree canopy lands on about sixty texels and the
     * receiving kernel smears its dapple into a soft blob; at 4096 the same canopy keeps leaf-shaped
     * detail. So the cheap settings are not "slightly worse shadows", they are the blob this map was
     * doubled to get away from, and the setting is worth having precisely because that is a trade some
     * players will want to make.
     */
    private int resolution = RESOLUTION;

    /** Largest single mesh's worth of casters in one batch; see {@code Renderer.MAX_PROP_INSTANCES}. */
    private static final int MAX_SHADOW_INSTANCES = 4096;

    /** Half-width of the area around the focus point that receives shadows. */
    /**
     * How far from the player the one cascade reaches.
     *
     * <p>Public because a caster's own draw distance has to be read against it: anything that stops being
     * drawn nearer than this goes on casting a shadow with nothing above it to cast one, which is a
     * plainly wrong frame rather than a missing detail. {@code PropInstances.SHRUB_DRAW_DISTANCE} is the
     * consumer.
     */
    public static final float RADIUS = 56f;

    /**
     * Half-depth of the light's ortho box, along the light direction.
     *
     * <p>Generous on purpose: it has to contain every caster whose shadow could land inside the radius,
     * and terrain here spans bedrock at −64 to peaks above +72. Depth precision costs nothing to spare —
     * the map is a 24-bit depth buffer over a range measured in hundreds of units.
     */
    private static final float DEPTH = 160f;

    /**
     * Depth-comparison bias in world units, before slope scaling.
     *
     * <p>Small, because the heavy lifting is done by the normal offset in the receiving shaders: a
     * constant bias large enough to kill acne on its own is also large enough to detach a shadow from the
     * foot of whatever cast it.
     */
    private static final float BIAS_WORLD_UNITS = 0.05f;

    private final Shader staticShader;
    private final Shader skinnedShader;
    private final Shader cutoutShader;

    private final int fbo;
    /** Not final: {@link #setResolution} throws this away and makes another. */
    private int depthTexture;

    private final Matrix4f lightSpaceMatrix = new Matrix4f();

    private int previousFbo;
    private final int[] previousViewport = new int[4];
    private boolean recording;

    /**
     * Per-instance transform stream for the depth pass.
     *
     * <p>Its own rather than shared with {@link Renderer}'s two, because the shadow pass runs to
     * completion before the colour passes start and a shared buffer would have to be re-uploaded between
     * them anyway. Untinted: a depth pass has no colour to carry.
     */
    private final InstanceBuffer instanceStream = new InstanceBuffer(MAX_SHADOW_INSTANCES, false);

    /**
     * GL submissions this pass has made since {@link #resetDrawStats()}.
     *
     * <p>Counted rather than derived from the batch sizes handed in, for the reason {@code Renderer} had
     * to learn the same lesson: once the pass is instanced those two numbers are different, and
     * {@code FrameCostProbe} was reporting one draw per caster — true when it was written, and merely
     * plausible afterwards.
     */
    private int drawCalls;

    public ShadowMap(int boneMatricesBinding) {
        staticShader = new Shader("/shaders/depth_static.vert", "/shaders/depth.frag");
        skinnedShader = new Shader("/shaders/depth_skinned.vert", "/shaders/depth.frag");
        cutoutShader = new Shader("/shaders/depth_cutout.vert", "/shaders/depth_cutout.frag");
        skinnedShader.bindUniformBlock("BoneMatrices", boneMatricesBinding);

        createDepthTexture();

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
        // Depth only. Without saying so explicitly the FBO is incomplete: the default draw and read
        // buffers refer to a colour attachment that does not exist.
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("shadow map FBO incomplete");
        }

        // Cleared once at construction so a renderer that never runs a shadow pass — CharacterPreview,
        // say — samples a map that says "nothing is occluding anything" rather than uninitialised memory.
        glClear(GL_DEPTH_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Aims the light at {@code focus} and starts recording depth. Every caster drawn until {@link #end}
     * lands in the map.
     *
     * @param toLight unit vector pointing <b>toward</b> the light — the same convention as
     *                {@link SceneLighting#toSun()}, so the caller passes that straight through
     * @param focus   world point the shadowed area is centred on; the player, in practice
     */
    public void begin(Vector3f toLight, Vector3f focus) {
        Matrix4f lightView = lightView(toLight);
        Vector3f centre = lightView.transformPosition(new Vector3f(focus));
        float texelWorldSize = texelWorldSize();
        centre.x = Math.round(centre.x / texelWorldSize) * texelWorldSize;
        centre.y = Math.round(centre.y / texelWorldSize) * texelWorldSize;
        project(lightView, centre);
        bindForRecording();
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    /** The light's rotation, looking along it. */
    private static Matrix4f lightView(Vector3f toLight) {
        // A light pointing straight down leaves lookAlong with no way to orient its up vector. The key
        // light cannot actually reach the zenith (the sun's orbit is tilted for exactly that reason), but
        // a renderer should not depend on a game-layer decision staying true.
        Vector3f up = Math.abs(toLight.y) > 0.99f ? new Vector3f(0f, 0f, 1f) : new Vector3f(0f, 1f, 0f);
        // Rotation only: the light is directional, so where its "eye" sits is meaningless. Keeping the
        // translation out of the view matrix is what makes the texel snap a simple clamp of the
        // centre rather than a correction applied to a full transform.
        return new Matrix4f().lookAlong(new Vector3f(toLight).negate(), up);
    }

    /** The light-space matrix for a box round {@code centre}, given in light space. */
    private void project(Matrix4f lightView, Vector3f centre) {
        // JOML's ortho takes near/far as distances along -Z, which is where lookAlong put the light.
        lightSpaceMatrix
                .setOrtho(centre.x - RADIUS, centre.x + RADIUS,
                        centre.y - RADIUS, centre.y + RADIUS,
                        -centre.z - DEPTH, -centre.z + DEPTH)
                .mul(lightView);
    }

    private void bindForRecording() {
        previousFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, previousViewport);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, resolution, resolution);
        glEnable(GL_DEPTH_TEST);
        recording = true;
    }

    /** Terrain slabs and anything else drawn at the world origin with no per-instance transform. */
    public void drawStatic(Collection<StaticMesh> meshes) {
        drawStatic(meshes, new Matrix4f());
    }

    /** Static meshes placed by a model matrix: a terrain standing where its entity does. */
    public void drawStatic(Collection<StaticMesh> meshes, Matrix4f model) {
        if (meshes.isEmpty()) {
            return;
        }
        useStatic(model);
        for (StaticMesh mesh : meshes) {
            mesh.draw();
            drawCalls++;
        }
    }

    /** Rocks and other placed props, each with its own transform. */
    public void drawInstances(Collection<StaticInstance> instances) {
        drawInstances(instances, false);
    }

    /**
     * Placed props, optionally recorded with culling off.
     *
     * <p>Undergrowth needs it for the same reason a leaf card does — see {@link #drawTextured}. A shrub is
     * built of one-sided sheets, so culled it casts the shadow of whichever half happens to face the sun
     * and a bush lit from behind records almost nothing at all. <b>This pass does not set cull state
     * otherwise</b>, it inherits whatever the previous pass left, so the flag has to be explicit rather
     * than assumed either way.
     */
    public void drawInstances(Collection<StaticInstance> instances, boolean twoSided) {
        if (instances.isEmpty()) {
            return;
        }
        if (twoSided) {
            glDisable(GL_CULL_FACE);
        }
        staticShader.use();
        staticShader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        staticShader.setUniform("instanced", 1);

        Map<StaticMesh, List<StaticInstance>> byMesh = new IdentityHashMap<>();
        for (StaticInstance instance : instances) {
            byMesh.computeIfAbsent(instance.mesh(), m -> new ArrayList<>()).add(instance);
        }
        for (Map.Entry<StaticMesh, List<StaticInstance>> group : byMesh.entrySet()) {
            List<StaticInstance> batch = group.getValue();
            for (int from = 0; from < batch.size(); from += instanceStream.capacity()) {
                instanceStream.begin();
                int to = Math.min(from + instanceStream.capacity(), batch.size());
                for (int i = from; i < to; i++) {
                    instanceStream.put(batch.get(i).modelMatrix());
                }
                instanceStream.bind(group.getKey().vaoHandle());
                group.getKey().drawInstanced(instanceStream.count());
                drawCalls++;
                instanceStream.unbind();
            }
        }

        staticShader.setUniform("instanced", 0);
        if (twoSided) {
            glEnable(GL_CULL_FACE);
        }
    }

    /**
     * Any other instanced meshes, by vertex array: each mesh's placements in one submission per batch. Positions
     * only, so cutouts cast as solids; two-sided meshes draw with culling off, as {@link #drawInstances} does.
     */
    public void drawVertexArrays(java.util.Map<? extends InstancedVertexArray, ? extends List<org.joml.Matrix4f>> placements,
                                 boolean twoSided) {
        if (placements.isEmpty()) {
            return;
        }
        if (twoSided) {
            glDisable(GL_CULL_FACE);
        }
        staticShader.use();
        staticShader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        staticShader.setUniform("instanced", 1);
        for (java.util.Map.Entry<? extends InstancedVertexArray, ? extends List<org.joml.Matrix4f>> group : placements.entrySet()) {
            List<org.joml.Matrix4f> batch = group.getValue();
            for (int from = 0; from < batch.size(); from += instanceStream.capacity()) {
                instanceStream.begin();
                int to = Math.min(from + instanceStream.capacity(), batch.size());
                for (int i = from; i < to; i++) {
                    instanceStream.put(batch.get(i));
                }
                instanceStream.bind(group.getKey().vaoHandle());
                group.getKey().drawInstanced(instanceStream.count());
                drawCalls++;
                instanceStream.unbind();
            }
        }
        staticShader.setUniform("instanced", 0);
        if (twoSided) {
            glEnable(GL_CULL_FACE);
        }
    }

    /**
     * Textured instances — trunks and leaf cards.
     *
     * <p>The material decides the path: a cutout material casts through the discarding shader so a canopy
     * shadows as dappled leaves rather than as a stack of opaque rectangles, and it draws with culling off
     * for the same reason it renders that way, since a card lit from behind has no front face to record.
     */
    public void drawTextured(Collection<TexturedInstance> instances, FoliageMaterial material) {
        if (instances.isEmpty()) {
            return;
        }
        boolean cutout = material.twoSided();
        Shader shader = cutout ? cutoutShader : staticShader;
        shader.use();
        shader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        shader.setUniform("instanced", 1);
        if (cutout) {
            material.albedo().bind(0);
            shader.setUniform("albedoTexture", 0);
            shader.setUniform("alphaCutoff", material.alphaCutoff());
            glDisable(GL_CULL_FACE);
        }

        // Grouped by mesh, exactly as Renderer.renderFoliage does — the material is already fixed for the
        // whole call, so a group is one instanced submission. Bark and leaves are the two largest groups of
        // shadow draws in the frame between them.
        Map<TexturedMesh, List<TexturedInstance>> byMesh = new IdentityHashMap<>();
        for (TexturedInstance instance : instances) {
            byMesh.computeIfAbsent(instance.mesh(), m -> new ArrayList<>()).add(instance);
        }
        for (Map.Entry<TexturedMesh, List<TexturedInstance>> group : byMesh.entrySet()) {
            List<TexturedInstance> batch = group.getValue();
            for (int from = 0; from < batch.size(); from += instanceStream.capacity()) {
                instanceStream.begin();
                int to = Math.min(from + instanceStream.capacity(), batch.size());
                for (int i = from; i < to; i++) {
                    instanceStream.put(batch.get(i).modelMatrix());
                }
                instanceStream.bind(group.getKey().vaoHandle());
                group.getKey().drawInstanced(instanceStream.count());
                drawCalls++;
                instanceStream.unbind();
            }
        }

        shader.setUniform("instanced", 0);
        if (cutout) {
            glEnable(GL_CULL_FACE);
        }
    }

    /**
     * The skinned character, posed by whatever is currently in the bone-matrix UBO.
     *
     * <p>Takes no {@code Animator}: the pose is uploaded once per frame by
     * {@link Renderer#uploadPose}, and re-uploading it here would make the shadow pass and the colour
     * pass two independent copies of the same state — the kind of duplication that eventually lets a
     * shadow lag a frame behind its character.
     */
    public void drawSkinned(Model model, Matrix4f modelMatrix) {
        skinnedShader.use();
        skinnedShader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        skinnedShader.setUniform("model", modelMatrix);
        for (Mesh mesh : model.getMeshes()) {
            mesh.draw();
            drawCalls++;
        }
    }

    /**
     * A rigid attachment on a socket — a weapon, a tool, a hat — which casts like any other solid thing.
     *
     * <p>Through the <b>static</b> depth shader although the mesh carries a character's vertex layout,
     * which is exactly what that shader's one-declared-attribute rule is for: position is location 0 in
     * every mesh class here, and the skinning attributes its VAO leaves enabled are ignored by a shader
     * that does not declare them. Skinning it would be wrong twice over — the socket has already placed
     * it, and the bone UBO holds the character's pose rather than anything about this model.
     */
    public void drawRigid(Model model, Matrix4f modelMatrix) {
        staticShader.use();
        staticShader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        staticShader.setUniform("instanced", 0);
        staticShader.setUniform("model", modelMatrix);
        for (Mesh mesh : model.getMeshes()) {
            mesh.draw();
            drawCalls++;
        }
    }

    // --- The world's own shadows, kept between frames -----------------------------------------------------

    /**
     * How far the sun may move before the kept shadows are drawn again, in radians: about a tenth of a degree. The sun
     * crosses the sky in twelve real minutes, so this redraws them about every 0.4 s, and a 15 m tree's shadow tip,
     * with the sun 20° up, moves about 20 cm at a time.
     */
    static final float LIGHT_DRIFT = (float) Math.toRadians(0.1);

    /**
     * How far the shadowed box may lag behind its focus, across the light, before it is moved and the kept shadows
     * drawn again, in metres. The box reaches {@value #RADIUS} m from where it is centred, so the far side of the
     * player's view loses at most this much of it.
     */
    static final float FOCUS_DRIFT = 6f;

    /** The same along the light, where the box reaches {@value #DEPTH} m either way. */
    private static final float DEPTH_DRIFT = 24f;

    private int keptFbo;
    private int keptDepth;
    private int keptResolution;
    private boolean kept;
    private Object keptOwner;
    private long keptVersion;
    private final Matrix4f keptMatrix = new Matrix4f();
    /** The light and the box centre (light space) the kept shadows were drawn for; null before the first. */
    private Vector3f heldToLight;
    private final Vector3f heldCentre = new Vector3f();
    private boolean keptRedrawn;

    /**
     * Starts recording, as {@link #begin} does, for a world whose own casters — ground, trees, buildings — stand still
     * and are worth keeping: they are drawn once into a kept copy of the map and copied back each frame after, and only
     * what moves is drawn over them.
     *
     * <p>The light and the box are held while the sun has moved less than {@link #LIGHT_DRIFT} and the focus less than
     * {@link #FOCUS_DRIFT}, so the matrix stays exactly the same from frame to frame and the copy still fits it. Then,
     * and while {@code owner} and {@code version} are the same as when the copy was made, this copies it in and returns
     * false: draw only what moves. Otherwise it clears the map and returns true: draw the world's own casters, then call
     * {@link #keepStatic}, then what moves.
     *
     * @param owner   whose casters these are — a world's terrain — so another world sharing this map is never handed
     *                the first world's shadows
     * @param version changes whenever any of the owner's casters do: one added, gone, moved or rebuilt
     */
    public boolean beginCached(Vector3f toLight, Vector3f focus, Object owner, long version) {
        boolean sameOwner = owner == keptOwner;
        if (heldToLight == null || !sameOwner || heldToLight.angle(toLight) > LIGHT_DRIFT) {
            heldToLight = new Vector3f(toLight);
            sameOwner = false;
        }
        Matrix4f lightView = lightView(heldToLight);
        Vector3f centre = lightView.transformPosition(new Vector3f(focus));
        if (!sameOwner || Math.abs(centre.x - heldCentre.x) > FOCUS_DRIFT || Math.abs(centre.y - heldCentre.y) > FOCUS_DRIFT
                || Math.abs(centre.z - heldCentre.z) > DEPTH_DRIFT) {
            float texel = texelWorldSize();
            heldCentre.set(Math.round(centre.x / texel) * texel, Math.round(centre.y / texel) * texel, centre.z);
        }
        project(lightView, heldCentre);

        boolean fits = kept && sameOwner && version == keptVersion && keptResolution == resolution
                && keptMatrix.equals(lightSpaceMatrix);
        bindForRecording();
        keptRedrawn = !fits;
        if (fits) {
            copyDepth(keptFbo, fbo);
        } else {
            glClear(GL_DEPTH_BUFFER_BIT);
            kept = false;
            keptOwner = owner;
            keptVersion = version;
            keptMatrix.set(lightSpaceMatrix);
        }
        return !fits;
    }

    /** Keeps what has been drawn since {@link #beginCached} returned true, for the frames after to copy back. */
    public void keepStatic() {
        if (!recording) {
            return;
        }
        if (keptFbo == 0 || keptResolution != resolution) {
            createKeptDepth();
        }
        copyDepth(fbo, keptFbo);
        kept = true;
    }

    /** Whether the last {@link #beginCached} had the world's own shadows drawn again rather than copied: for a probe. */
    public boolean keptRedrawn() {
        return keptRedrawn;
    }

    /** Forgets the kept shadows, so the next {@link #beginCached} draws them again whatever it is given. */
    public void forgetKept() {
        kept = false;
        keptOwner = null;
        heldToLight = null;
    }

    private void createKeptDepth() {
        if (keptDepth != 0) {
            glDeleteTextures(keptDepth);
        }
        keptDepth = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, keptDepth);
        // The same format as the map, which a depth copy between them needs; never sampled, so no filtering.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, resolution, resolution, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        if (keptFbo == 0) {
            keptFbo = glGenFramebuffers();
        }
        glBindFramebuffer(GL_FRAMEBUFFER, keptFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, keptDepth, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        keptResolution = resolution;
    }

    /** Copies one framebuffer's depth into the other's, the whole map, and leaves the map bound for drawing. */
    private void copyDepth(int from, int to) {
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        if (scissor) {
            glDisable(GL_SCISSOR_TEST);
        }
        glDepthMask(true);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, from);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, to);
        glBlitFramebuffer(0, 0, resolution, resolution, 0, 0, resolution, resolution, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        if (scissor) {
            glEnable(GL_SCISSOR_TEST);
        }
    }

    /** Stops recording and restores the framebuffer and viewport that were bound at {@link #begin}. */
    public void end() {
        if (!recording) {
            return;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        recording = false;
    }

    private void useStatic(Matrix4f model) {
        staticShader.use();
        staticShader.setUniform("lightSpaceMatrix", lightSpaceMatrix);
        staticShader.setUniform("model", model);
        // Explicit rather than relying on the instanced paths to have put it back. A uniform persists on
        // the program, so a pass that forgot would read locations 5-8 off whatever VBO was last bound —
        // terrain drawn at whatever transform the previous batch of rocks happened to leave behind.
        staticShader.setUniform("instanced", 0);
    }

    /** Submissions since the last reset, for the frame probe. */
    public int drawCalls() {
        return drawCalls;
    }

    public void resetDrawStats() {
        drawCalls = 0;
    }

    public void bind(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glActiveTexture(GL_TEXTURE0);
    }


    /**
     * Allocates the depth map and sets its sampling state.
     *
     * <p>Split out of the constructor so {@link #setResolution} can re-make it. The FBO it hangs off is
     * <b>not</b> re-made: an attachment can be replaced on a live framebuffer, and re-creating the FBO
     * would invalidate the handle every preview and probe holds.
     */
    private void createDepthTexture() {
        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, resolution, resolution, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        // Hardware depth comparison, sampled as a sampler2DShadow. The comparison happens *before* the
        // filter, so GL_LINEAR here bilinearly blends four already-compared texels and one tap returns a
        // fractional visibility rather than 0 or 1. That is the whole difference between a stair-stepped
        // shadow edge and a smooth one: with GL_NEAREST and a compare in the shader, a 3x3 kernel can
        // only ever report one of ten values and each tap snaps to a whole texel, so the edge shows the
        // map's pixel grid. Filtering a depth texture is only meaningful this way round — averaging raw
        // depths and comparing once would compare against a depth that no surface actually has.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

        // Anything outside the map must read as *lit*, so the border is the far depth. CLAMP_TO_EDGE
        // instead would smear the edge texels outward and stretch whatever shadow happens to sit on the
        // boundary across the entire rest of the world.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{1f, 1f, 1f, 1f});
        if (fbo != 0) {
            // Re-attach: the old texture this replaced is gone, and a framebuffer left pointing at a
            // deleted attachment is incomplete rather than merely stale. Mid-frame, so whatever the
            // frame was drawing into is bound again after: the window, or an HDR scene buffer.
            int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
            glClear(GL_DEPTH_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, previous);
        }
    }

    /** The map's current side. Read it rather than {@link #RESOLUTION} — they differ once a setting has. */
    public int resolution() {
        return resolution;
    }

    /**
     * Re-makes the depth map at a new size, or does nothing if it is already that size.
     *
     * <p>Takes effect on the next frame rather than the next world, unlike the texture settings: a depth
     * attachment is cheap to recreate and nothing caches anything derived from it — {@link #texelWorldSize}
     * and {@link #texelUvSize} are read per frame by whoever needs them, which is what makes this safe. The
     * shaders' normal offset is measured in texels, so it follows the size automatically; a cached copy of
     * it anywhere would be the thing that broke here.
     *
     * <p>Silently clamped to something sane. A hand-edited settings file asking for a 32-texel shadow map
     * is not a look anybody chose, and one asking for 16384 is a request the driver may refuse in a way
     * that takes the session with it.
     */
    public void setResolution(int side) {
        int clamped = Math.clamp(side, MIN_RESOLUTION, RESOLUTION);
        if (clamped == resolution) {
            return;
        }
        resolution = clamped;
        glDeleteTextures(depthTexture);
        createDepthTexture();
    }

    /** Below this the map is too coarse for the kernel to be doing anything but blurring. */
    private static final int MIN_RESOLUTION = 512;

    /**
     * Reads the depth texture back into {@code destination} ({@link #resolution()}² floats).
     *
     * <p>Values are unsigned; compare with {@code Integer.compareUnsigned} or widen. A verification hook,
     * not part of rendering — a shadow that never appears on screen has two very
     * different causes, "the caster was never recorded" and "the lookup is wrong", and looking at the map
     * itself is the only thing that separates them. {@code ShadowPreview} writes it out as an image.
     */
    public void readDepth(java.nio.IntBuffer destination) {
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        // Read as normalised unsigned ints, which is the storage this texture actually has
        // (DEPTH_COMPONENT24) rather than a format the driver has to convert to. Asking for GL_FLOAT
        // segfaulted inside the driver once the map went to 4096² — a 64 MB conversion this diagnostic
        // never needed, since it only ever contrast-stretches whatever range it gets.
        glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, destination);
    }

    public Matrix4f lightSpaceMatrix() {
        return new Matrix4f(lightSpaceMatrix);
    }

    /** The same, into {@code dest}, for a caller that asks every frame. */
    public Matrix4f lightSpaceMatrix(Matrix4f dest) {
        return dest.set(lightSpaceMatrix);
    }

    /** One texel's width in world units, which is the unit the shaders' normal offset is measured in. */
    public float texelWorldSize() {
        return 2f * RADIUS / resolution;
    }

    /** One texel in UV units, for the PCF tap offsets. */
    public float texelUvSize() {
        return 1f / resolution;
    }

    /**
     * Depth bias in the shaders' units — projected depth runs 0..1 across the whole {@code 2 * DEPTH}
     * box, so a bias meant in world units has to be divided by that range rather than guessed at.
     */
    public float depthBias() {
        return BIAS_WORLD_UNITS / (2f * DEPTH);
    }

    public void delete() {
        instanceStream.delete();
        glDeleteFramebuffers(fbo);
        glDeleteTextures(depthTexture);
        if (keptFbo != 0) {
            glDeleteFramebuffers(keptFbo);
            glDeleteTextures(keptDepth);
        }
        staticShader.delete();
        skinnedShader.delete();
        cutoutShader.delete();
    }
}
