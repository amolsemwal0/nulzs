package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws many copies of one small mesh in a single call — the engine's ground cover.
 *
 * <h2>Why this is not the foliage path</h2>
 * A grass field is thousands of tufts, and every other prop path here is <b>one draw call per instance</b>
 * ({@code renderProps}, {@code renderFoliage}). That is the right trade for six hundred trees and quite
 * the wrong one for ten thousand tufts of grass: it is not the triangles that would sink it, it is the
 * ten thousand uniform uploads and draw submissions. So this is the first renderer here to use
 * <b>instanced arrays</b> — the tuft's geometry is uploaded once and the field is a stream of eight
 * floats per placement, drawn with one {@code glDrawElementsInstanced}. At 12000 tufts that is ~380 KB
 * of instance data per frame against ~1.2 MB of matrices and 12000 submissions the old way.
 *
 * <h2>Generic</h2>
 * Knows nothing about grass, biomes or where a tuft belongs — it takes a mesh, a texture and a buffer of
 * placements, exactly as {@link RainRenderer} takes an intensity and a colour rather than the weather.
 * The game layer decides what is scattered and where.
 *
 * <h2>Self-contained</h2>
 * Owns its shader and buffers and puts back every GL state it touches, the same contract {@code Skybox},
 * {@link WaterRenderer} and {@link RainRenderer} follow. Culling is off while it draws: a blade is a
 * single-sided modelled strip with no back face, and the source model mirrors half of its blades, which
 * reverses their winding.
 */
public class GrassRenderer {

    /**
     * x, y, z, base scale, cos(yaw), sin(yaw), shade, sway phase, <b>appearance distance</b>, the ground
     * colour under the tuft as three floats, +1 or -1 for whether the clump is reflected about its own
     * axis, and which colour variant it is drawn in.
     *
     * <p><b>Every one of these is a property of the SPOT, not of the frame</b>, which is the whole point:
     * a placement is written once when its patch is scattered and never touched again. The two values
     * that do depend on where the player is standing — how far the thinning has grown this tuft
     * ({@code spreadAt}) and how far through its arrival it is — used to be computed on the CPU and
     * streamed every frame, and are derived in the vertex shader now from the appearance distance carried
     * here. See {@link #renderPatches}.
     *
     * <p>The ground colour is per instance rather than per vertex because it is a property of the spot the
     * tuft was scattered on, not of the geometry — the mesh is shared by every tuft in the world. It costs
     * 12 bytes a placement and nothing to produce: {@code HeightmapGenerator.sample} computes the terrain's
     * colour alongside its height in the same multi-biome blend, and the scatter was discarding it.
     *
     * <p>The <b>variant</b> is per instance rather than per draw, and that is what collapses a patch into
     * a single call: a patch holds both greens mixed together, so a variant chosen by draw call would mean
     * either sorting every patch's tufts by colour or drawing every patch twice. It costs four bytes and
     * indexes a two-entry gradient array in the shader.
     */
    public static final int FLOATS_PER_INSTANCE = 14;

    /** position(12) + normal(12) + uv(8). No tint, no tangent — the shader reads neither. */
    private static final int VERTEX_STRIDE = 32;

    private static final int INSTANCE_STRIDE = FLOATS_PER_INSTANCE * Float.BYTES;

    /** Where the shadow map is bound for this pass. Unit 0 is the albedo; a pass owns its own units. */
    private static final int SHADOW_TEXTURE_UNIT = 4;

    /**
     * How far a tip leans at full sway, as a fraction of the tuft's <b>own height</b> — so a taller clump
     * leans further and the wind looks the same at any scale. Peak lean is about 1.35× this, from the two
     * summed sine terms.
     */
    private static final float SWAY_STRENGTH = 0.18f;

    /** Which way the wind blows. A constant, not the weather's — nothing here has a wind vector yet. */
    /**
     * The world's wind, set by the game. Direction and rate both come from it — this class no longer owns a
     * wind of its own, because grass leaning one way while the sky moves another is exactly what having
     * four separate winds looked like.
     */
    private Vector2f wind = new Vector2f(1f, 0f);

    /**
     * Whether the blades are painted by the albedo texture (a baked material over the tuft's UVs, the gradients then
     * only tinting it) rather than coloured by the gradients with the texture cutting out their shape. Off by default.
     */
    public void setPaintedByTexture(boolean painted) {
        this.paintedByTexture = painted;
    }

    /** How far the blades take the ground's hue under them, 0 to 1; 0.65 by default. */
    public void setGroundTint(float tint) {
        this.groundTint = tint;
    }

    /**
     * Gusts rolling across the field in the wind's direction, bowing the grass and lighting its tips as each crest
     * passes, as painted fields show the wind: {@code strength} 0 (none, the default) to about 1, crests
     * {@code spacing} metres apart moving at {@code speed} metres a second.
     */
    public void setGusts(float strength, float spacing, float speed) {
        this.gustStrength = strength;
        this.gustSpacing = spacing;
        this.gustSpeed = speed;
    }

    /**
     * How far a tip leans at full sway, as a share of the tuft's own height: {@value #SWAY_STRENGTH} by default; 0
     * for something that doesn't sway (a stone), less for something stiff (a bush).
     */
    public void setSwayStrength(float strength) {
        this.swayStrength = strength;
    }

    private float swayStrength = SWAY_STRENGTH;
    private Shader shadowShader;

    /**
     * Draws the settled tufts of these patches into the sun's shadow map, swaying as they are drawn, for cover big
     * enough to throw a shadow (a bush, a stone; grass is too fine to). Call from a pass's shadow turn, while the
     * shadow map is being drawn.
     */
    public void renderShadows(ShadowMap shadowMap, GrassCurve curve, Vector3f centre, List<PatchDraw> patches, float time) {
        if (patches.isEmpty()) {
            return;
        }
        if (shadowShader == null) {
            shadowShader = new Shader("/shaders/grass.vert", "/shaders/grass_shadow.frag");
        }
        boolean wasCulling = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE);
        shadowShader.use();
        shadowShader.setUniform("view", new Matrix4f());
        shadowShader.setUniform("projection", shadowMap.lightSpaceMatrix());
        shadowShader.setUniform("time", time);
        shadowShader.setUniform("windVelocity", wind);
        shadowShader.setUniform("swayStrength", swayStrength);
        shadowShader.setUniform("tuftHeight", tuftHeight);
        shadowShader.setUniform("billboard", 0);
        shadowShader.setUniform("cameraRight", new Vector3f(1f, 0f, 0f));
        shadowShader.setUniform("fieldCentre", centre);
        shadowShader.setUniform("fullDensityDistance", curve.fullDensityDistance());
        shadowShader.setUniform("settleBlend", curve.settleBlend());
        shadowShader.setUniform("farDensity", curve.farDensity());
        shadowShader.setUniform("maxDistance", curve.maxDistance());
        shadowShader.setUniform("maxThinningSpread", curve.maxThinningSpread());
        shadowShader.setUniform("fadeFullSizeDistance", curve.fadeFullSizeDistance());
        shadowShader.setUniform("minAppearFade", curve.minAppearFade());
        shadowShader.setUniform("appearFade", curve.appearFade());
        shadowShader.setUniform("dissolveShare", curve.dissolveShare());
        shadowShader.setUniform("arrivingPass", 0);
        shadowShader.setUniform("gustStrength", gustStrength);
        shadowShader.setUniform("gustSpacing", gustSpacing);
        shadowShader.setUniform("gustSpeed", gustSpeed);
        for (PatchDraw patch : patches) {
            if (patch.count() > 0) {
                glBindVertexArray(patch.buffer().vao);
                glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0, patch.count());
            }
        }
        glBindVertexArray(0);
        if (wasCulling) {
            glEnable(GL_CULL_FACE);
        }
    }

    /**
     * Whether the grass takes part in the screen's ambient occlusion (it does by default): darkened where its blades
     * crowd, and darkening the ground round it. Off, it is neither, as a painted field is; see grass.frag.
     */
    public void setReceivesOcclusion(boolean receives) {
        this.receivesOcclusion = receives;
    }

    private boolean receivesOcclusion = true;
    private boolean paintedByTexture;
    private float groundTint = 0.65f;
    private float gustStrength;
    private float gustSpacing = 14f;
    private float gustSpeed = 4f;

    public void setWind(Vector2f windVelocity) {
        this.wind = windVelocity == null ? new Vector2f(1f, 0f) : new Vector2f(windVelocity);
    }

    private final Shader shader;
    private final Texture albedo;
    private final float tuftHeight;
    /** Set for the far field's camera-facing quad; see {@link #billboard}. */
    private final boolean billboard;
    private final float alphaCutoff;

    private final int vbo;
    private final int ebo;
    private final int indexCount;

    /**
     * @param positions    interleaved-free vertex positions, roots at y = 0
     * @param normals      per-vertex shading normals, already whatever the caller wants them to be
     * @param uvs          per-vertex texture coordinates
     * @param indices      triangle list
     * @param albedo       the tuft's texture; owned by the caller, not deleted here
     * @param tuftHeight   the mesh's own height, so the shader can tell a root vertex from a tip
     */
    public GrassRenderer(float[] positions, float[] normals, float[] uvs, int[] indices,
                         Texture albedo, float tuftHeight) {
        this(positions, normals, uvs, indices, albedo, tuftHeight, false, 0f);
    }

    /**
     * Builds a renderer for one detail level.
     *
     * @param billboard   whether the mesh is a flat quad to be turned toward the camera rather than a
     *                    modelled clump. Everything else — instancing, sway, the shading ramp, fog,
     *                    shadow receipt — is deliberately identical, so the two sides of a LOD swap
     *                    cannot disagree about anything but silhouette
     * @param alphaCutoff discard threshold; 0 for the opaque clumps, ~0.5 for a baked sprite whose
     *                    blade shape lives in its alpha
     */
    public GrassRenderer(float[] positions, float[] normals, float[] uvs, int[] indices,
                         Texture albedo, float tuftHeight,
                         boolean billboard, float alphaCutoff) {
        this.shader = new Shader("/shaders/grass.vert", "/shaders/grass.frag");
        this.albedo = albedo;
        this.tuftHeight = tuftHeight;
        this.billboard = billboard;
        this.alphaCutoff = alphaCutoff;
        this.indexCount = indices.length;

        int vertexCount = positions.length / 3;
        ByteBuffer vertexData = MemoryUtil.memAlloc(vertexCount * VERTEX_STRIDE);
        for (int v = 0; v < vertexCount; v++) {
            vertexData.putFloat(positions[v * 3]).putFloat(positions[v * 3 + 1]).putFloat(positions[v * 3 + 2]);
            vertexData.putFloat(normals[v * 3]).putFloat(normals[v * 3 + 1]).putFloat(normals[v * 3 + 2]);
            vertexData.putFloat(uvs[v * 2]).putFloat(uvs[v * 2 + 1]);
        }
        vertexData.flip();

        IntBuffer indexData = MemoryUtil.memAllocInt(indices.length);
        indexData.put(indices).flip();

        // Geometry only. There is no lasting VAO here: a VAO records which buffer each attribute reads
        // from, and the instance attributes live in a different buffer for every patch, so the vertex
        // array belongs to the patch rather than to the renderer — see PatchBuffer.
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);

        // The element buffer binding is VAO state, and vertex array 0 is not a valid vertex array in a
        // core profile — so filling the index buffer needs some VAO bound, even though the one that ends
        // up reading it is the patch's. A scratch VAO, deleted immediately: leaving it bound would make
        // every patch's setup depend on whatever this constructor happened to leave behind.
        int scratchVao = glGenVertexArrays();
        glBindVertexArray(scratchVao);
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexData, GL_STATIC_DRAW);
        glBindVertexArray(0);
        glDeleteVertexArrays(scratchVao);

        glBindBuffer(GL_ARRAY_BUFFER, 0);

        MemoryUtil.memFree(vertexData);
        MemoryUtil.memFree(indexData);
    }

    /**
     * One region's placements, uploaded once and drawn many times.
     *
     * <h2>Why the field is drawn out of these rather than a stream</h2>
     * A placement never changes: where a tuft stands, how big it is, which way it faces and what the soil
     * under it is painted are all decided when its patch is scattered. The old path nonetheless rewrote
     * and re-uploaded the whole visible set <b>every frame</b>, because the two things that do vary —
     * the distance thinning and the arrival fade — were folded into the stream on the CPU. That is
     * affordable at a few thousand tufts and is the wall at a hundred thousand: the walk, the float
     * writes and the upload all scale with the field, and the upload alone would be megabytes a frame.
     *
     * <p>Moving those two derivations into the vertex shader (they are functions of distance, which the
     * shader can compute) leaves nothing per-frame to write, so a patch's placements become static
     * geometry. What is left on the CPU is one frustum test and one binary search per patch.
     *
     * <p><b>Its own VAO, not a shared one with the pointers re-specified per draw.</b> The alternative is
     * five {@code glVertexAttribPointer} calls plus a buffer bind for every patch every frame, which is
     * more GL calls than the draws themselves. A VAO is a cheap driver object and a patch already costs
     * a scatter of hundreds of microseconds to build.
     */
    public static final class PatchBuffer {

        private final int vao;
        private final int vbo;
        private final int count;

        private PatchBuffer(int vao, int vbo, int count) {
            this.vao = vao;
            this.vbo = vbo;
            this.count = count;
        }

        /** How many placements it holds. A draw may ask for fewer, never for more. */
        public int count() {
            return count;
        }

        /** Releases the GPU buffers. Not optional — a patch is evicted whenever the player walks away. */
        public void delete() {
            glDeleteBuffers(vbo);
            glDeleteVertexArrays(vao);
        }
    }

    /**
     * Uploads one patch's placements.
     *
     * <p>The buffer must be positioned and limited to {@code count} placements'
     * worth of {@value #FLOATS_PER_INSTANCE}-float records, <b>sorted by appearance distance,
     * furthest-appearing first</b> — see {@link #renderPatches} for what that ordering buys.
     */
    public PatchBuffer createPatch(FloatBuffer placements, int count) {
        int patchVao = glGenVertexArrays();
        glBindVertexArray(patchVao);

        // The geometry is the renderer's own and is shared by every patch in the world: a VAO records
        // which buffer each attribute reads from, so pointing several VAOs at one vertex buffer costs
        // nothing but the VAO objects themselves.
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_STRIDE, 0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, VERTEX_STRIDE, 12);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, VERTEX_STRIDE, 24);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);

        int patchVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, patchVbo);
        // STATIC, and that word is the change: this is written once and read for as long as the patch is
        // resident, where the stream it replaces was rewritten every frame.
        glBufferData(GL_ARRAY_BUFFER, placements, GL_STATIC_DRAW);
        glVertexAttribPointer(3, 4, GL_FLOAT, false, INSTANCE_STRIDE, 0);
        glVertexAttribPointer(4, 4, GL_FLOAT, false, INSTANCE_STRIDE, 4 * Float.BYTES);
        glVertexAttribPointer(5, 1, GL_FLOAT, false, INSTANCE_STRIDE, 8 * Float.BYTES);
        glVertexAttribPointer(6, 3, GL_FLOAT, false, INSTANCE_STRIDE, 9 * Float.BYTES);
        glVertexAttribPointer(7, 2, GL_FLOAT, false, INSTANCE_STRIDE, 12 * Float.BYTES);
        for (int a = 3; a <= 7; a++) {
            glEnableVertexAttribArray(a);
            // The divisor is the whole mechanism: these attributes advance once per instance rather than
            // once per vertex, so the tuft's vertices are re-read for every placement in the buffer.
            glVertexAttribDivisor(a, 1);
        }

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        return new PatchBuffer(patchVao, patchVbo, count);
    }

    /**
     * One patch to draw this frame, and how much of it.
     *
     * @param buffer the patch's placements
     * @param count  how many of them can possibly be visible — the prefix of the buffer whose appearance
     *               distance still clears the nearest point of the patch. The rest are thinned out by
     *               distance and are skipped with no vertex work at all
     */
    public record PatchDraw(PatchBuffer buffer, int count) {
    }

    /**
     * Draws a frame of grass, one call per patch.
     *
     * <h2>Two passes, and what the ordering is for</h2>
     * Tufts still fading in are drawn <b>blended</b> and so write no depth, which means everything opaque
     * they can be occluded by must already be in the depth buffer — the terrain and props from earlier in
     * the frame, and, the part that is easy to miss, <b>the settled grass</b>. So the whole visible set is
     * drawn opaque and then the whole visible set is drawn again blended, with each pass collapsing the
     * instances that belong to the other to a degenerate triangle. Nothing is sorted within the blended
     * pass: those tufts are translucent, sparse and the same colour as one another.
     *
     * <p>The second pass is far cheaper than the first despite covering the same instances, because the
     * shader can reject on the placement attributes alone before doing any of the sway, shading or
     * transform work.
     *
     * <h2>Why {@code count} is a prefix and not the whole buffer</h2>
     * A patch's placements are sorted furthest-appearing first, so the tufts the distance curve has
     * thinned out are always a <b>suffix</b> and the caller can drop them by asking for fewer instances.
     * At the shipped density that is most of the buffer: the curve draws about four percent of what is
     * scattered, and a per-frame binary search on the patch's nearest corner is what turns that into
     * vertex work never issued rather than vertex work discarded.
     *
     * @param gradients one entry per colour variant, indexed by the variant carried on each placement
     * @param curve     the density and arrival curve the scatter was built against — passed rather than
     *                  duplicated in GLSL, so the shader and the game cannot drift apart about where a
     *                  tuft appears or how big the thinning has made it
     * @param centre    what distance is measured from. The <b>player</b>, not the camera: a third-person
     *                  camera orbits the body by several units, so measuring from it would swing every
     *                  tuft's size and arrival as the player turned on the spot
     */
    public void renderPatches(Camera camera, SceneLighting lighting, ShadowMap shadowMap,
                              GrassGradient[] gradients, GrassCurve curve, Vector3f centre,
                              List<PatchDraw> patches, float time, boolean blended) {
        if (patches.isEmpty()) {
            return;
        }

        // What this changes, to put back as it was: a scene may draw blended things after the grass (clouds,
        // particles) that count on blending staying as they left it.
        boolean wasBlending = glIsEnabled(GL_BLEND);
        boolean wasCulling = glIsEnabled(GL_CULL_FACE);
        boolean wasWritingDepth = glGetBoolean(GL_DEPTH_WRITEMASK);
        int srcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        int dstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);

        glEnable(GL_DEPTH_TEST);
        // A blade is a modelled strip with no back face, and half the source model's blades are mirrored
        // copies whose winding is therefore reversed. Culling would erase them.
        glDisable(GL_CULL_FACE);
        if (blended) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
        } else {
            glDisable(GL_BLEND);
            glDepthMask(true);
        }

        shader.use();
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("viewPos", camera.getPosition());
        shader.setUniform("time", time);
        shader.setUniform("windVelocity", wind);
        shader.setUniform("swayStrength", swayStrength);
        shader.setUniform("tuftHeight", tuftHeight);
        shader.setUniform("billboard", billboard ? 1 : 0);
        shader.setUniform("alphaCutoff", alphaCutoff);
        // The camera's horizontal right vector, for the billboard pass. Derived from the view matrix
        // rather than passed in, so it cannot fall out of step with the projection used to draw.
        Matrix4f view = camera.getViewMatrix();
        shader.setUniform("cameraRight",
                new Vector3f(view.m00(), view.m10(), view.m20()).normalize());

        // What the CPU used to do per tuft per frame, now done per vertex from the appearance distance
        // on the instance. Every constant comes from the caller, so there is one statement of the curve.
        shader.setUniform("fieldCentre", centre);
        shader.setUniform("fullDensityDistance", curve.fullDensityDistance());
        shader.setUniform("settleBlend", curve.settleBlend());
        shader.setUniform("farDensity", curve.farDensity());
        shader.setUniform("maxDistance", curve.maxDistance());
        shader.setUniform("maxThinningSpread", curve.maxThinningSpread());
        shader.setUniform("fadeFullSizeDistance", curve.fadeFullSizeDistance());
        shader.setUniform("minAppearFade", curve.minAppearFade());
        shader.setUniform("appearFade", curve.appearFade());
        shader.setUniform("dissolveShare", curve.dissolveShare());
        shader.setUniform("arrivingPass", blended ? 1 : 0);

        // An array rather than a draw call apiece — see FLOATS_PER_INSTANCE. A blade is a root-to-tip
        // gradient rather than a flat colour, so each variant is three stops; grass.frag evaluates the
        // ramp per fragment, and picks its variant from the flat varying this sets up.
        for (int i = 0; i < gradients.length; i++) {
            shader.setUniform("grassRoot[" + i + "]", gradients[i].root());
            shader.setUniform("grassMid[" + i + "]", gradients[i].mid());
            shader.setUniform("grassTip[" + i + "]", gradients[i].tip());
        }

        shader.setUniform("toSun", lighting.toSun());
        shader.setUniform("sunColor", lighting.sunColor());
        shader.setUniform("ambientColor", lighting.ambientColor());
        // No rim uniforms: grass.frag deliberately has no rim term — see the note there.
        shader.setUniform("saturation", lighting.saturation());
        shader.setUniform("fogColor", lighting.fogColor());
        shader.setUniform("fogStart", lighting.fogStart());
        shader.setUniform("fogEnd", lighting.fogEnd());
        shader.setUniform("fogHorizon", lighting.fogHorizon());
        shader.setUniform("fogHeightFalloff", lighting.fogHeightFalloff());
        shader.setUniform("fogBaseY", lighting.fogBaseY());

        // Grass does not cast (a 12 cm tuft covers about two shadow texels), but it very much receives:
        // walking a field into a tree's shadow is most of what sells the shadow in the first place.
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

        // Optional: the current clump carries no embedded texture, so the blade colour comes from the
        // gradient alone and the shader multiplies a white texel. Guarded by a uniform rather than by
        // binding a 1x1 white texture, so nothing has to be invented to stand in for art that is absent.
        if (albedo != null) {
            albedo.bind(0);
            shader.setUniform("albedoTexture", 0);
        }
        shader.setUniform("hasAlbedo", albedo != null ? 1 : 0);
        shader.setUniform("albedoColour", paintedByTexture && albedo != null ? 1 : 0);
        shader.setUniform("groundTint", groundTint);
        shader.setUniform("receivesOcclusion", receivesOcclusion ? 1 : 0);
        shader.setUniform("gustStrength", gustStrength);
        shader.setUniform("gustSpacing", gustSpacing);
        shader.setUniform("gustSpeed", gustSpeed);

        for (PatchDraw patch : patches) {
            if (patch.count() <= 0) {
                continue;
            }
            glBindVertexArray(patch.buffer().vao);
            glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0, patch.count());
        }
        glBindVertexArray(0);

        glDepthMask(wasWritingDepth);
        if (wasBlending) {
            glEnable(GL_BLEND);
        } else {
            glDisable(GL_BLEND);
        }
        glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        if (wasCulling) {
            glEnable(GL_CULL_FACE);
        }
        glCullFace(GL_BACK);
    }

    public void delete() {
        glDeleteBuffers(ebo);
        glDeleteBuffers(vbo);
        shader.delete();
        if (shadowShader != null) {
            shadowShader.delete();
        }
    }
}
