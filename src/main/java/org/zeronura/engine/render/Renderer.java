package org.zeronura.engine.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.zeronura.engine.animation.Animator;
import org.zeronura.engine.asset.Material;
import org.zeronura.engine.asset.Model;

import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

public class Renderer {

    private static final int MAX_BONES = 200;

    /** Meshes the models drawn next leave out, because something else draws them (a part in a shader); null for none. */
    private java.util.Set<Mesh> skippedMeshes;
    private static final int BONE_MATRICES_BINDING = 0;

    /**
     * Texture unit the shadow map lives on for the whole frame.
     *
     * <p>Above every unit a material uses (base colour on 0, normal map on 1), so the map can be bound
     * once and left there instead of being re-bound around each pass and fighting the material textures
     * for a slot.
     */
    private static final int SHADOW_TEXTURE_UNIT = 4;

    /** Units 0/1 are the foliage albedo/normal pair, 4 is the shadow map; the ground material takes 2/3. */
    private static final int GROUND_ALBEDO_UNIT = 2;
    private static final int GROUND_NORMAL_UNIT = 3;
    /** A tiled map's ground pieces, above the shadow map: see {@link GroundPieces}. */
    private static final int PIECE_ALBEDO_UNIT = 5;
    private static final int PIECE_NORMAL_UNIT = 6;
    private static final int PIECE_LAYOUT_UNIT = 7;
    /**
     * A character material's optional anime maps — see {@code Material.setLightMap} and its neighbours.
     * Above everything else here so the character pass can never land on a unit another pass left bound
     * with a different sampler type; GL 3.3 guarantees sixteen.
     */
    private static final int LIGHT_MAP_UNIT = 8;
    private static final int SHADOW_RAMP_UNIT = 9;
    private static final int FACE_MAP_UNIT = 10;
    private static final int MATCAP_UNIT = 11;
    /** The figure's own depth, for the screen-space rim — see {@link #drawRimDepth}. */
    private static final int RIM_DEPTH_UNIT = 12;
    /** The map's water art and its flow, for {@link #renderWater}: the last of the sixteen. */
    private static final int WATER_ALBEDO_UNIT = 13;
    private static final int WATER_NORMAL_UNIT = 14;
    private static final int WATER_FLOW_UNIT = 15;

    private final Shader outlineShader;
    /** The character's own cel shader — {@code character.frag}, shared with nothing. */
    private final Shader characterShader;
    private final Shader terrainShader;
    private final Shader foliageShader;
    /** The painted leaves' shader, for a material that asks for it (FoliageMaterial.anime): foliage's vertex shader. */
    private final Shader animeLeafShader;
    private final Shader entityShader;
    private final ShadowMap shadowMap;
    private final int boneMatrixUbo;
    private final FloatBuffer boneMatrixBuffer;

    /**
     * Scratch for the per-instance normal matrix, reused across every draw of every pass.
     *
     * <p>The three instanced passes below run once per object per frame — measured at ~750 draws a frame
     * standing in the trees — and each was allocating a fresh {@code Matrix3f} purely to hand it to
     * {@code setUniform} and drop it. The matrix is consumed synchronously (it is copied into a stack
     * buffer inside {@code Shader.setUniform} before the call returns), so nothing can retain it and one
     * instance serves them all. Single-threaded by construction: these are GL calls, so they are only ever
     * made on the render thread.
     */
    private final Matrix3f scratchNormalMatrix = new Matrix3f();

    // A dark desaturated brown-grey rather than pure black: a soft ink line reads as drawn, where flat
    // black reads as a cut-out sticker. Thin, since the outline shader now holds it to a constant screen
    // width by scaling with distance — the value is a screen-space width, not a world-space one.
    private final Vector3f outlineColor = new Vector3f(0.10f, 0.08f, 0.07f);
    private final float outlineThickness = 0.0028f;

    /**
     * How far the ink line takes its hue from the surface it outlines — 0 is a single ink colour
     * everywhere, 1 is fully coloured lineart.
     *
     * <p>Painted anime lineart is not black: it is a deep, saturated version of whatever it borders —
     * reddish brown round skin, deep navy round a blue coat, near-black only round black hair. The shader
     * keeps {@link #outlineColor}'s <i>darkness</i> and takes only the <i>hue</i> from the albedo, so a
     * white shirt and a black boot get lines of the same weight. Set to 0 for the single-colour line this
     * replaced.
     */
    private float outlineAlbedoMix = 0.75f;

    /** Where the head is found — see {@link FaceRig}. */
    private FaceRig faceRig = FaceRig.DEFAULT;

    /**
     * The current figure's head this frame, in world space, worked out once by {@link #renderFrame} and
     * read by the body and every garment drawn after it, since they share one pose.
     */
    private final Vector3f headCenter = new Vector3f();
    private final Vector3f headForward = new Vector3f(0f, 0f, 1f);
    private final Vector3f headUp = new Vector3f(0f, 1f, 0f);
    private boolean headFound;
    private boolean warnedAboutMissingHead;
    private final Matrix4f scratchHeadDelta = new Matrix4f();

    /**
     * Whether the character's rim is found in screen space, from its own depth, rather than from fresnel.
     * See {@code depthRim} in {@code character.frag}; {@link #setDepthRim} turns it off.
     */
    private boolean depthRim = true;
    /** Draws a figure's depth and nothing else: the character's own vertex shader, an empty fragment one. */
    private final Shader rimDepthShader;
    /** Where that depth goes — sized to the viewport on first use and whenever the viewport changes. */
    private int rimFramebuffer;
    private int rimDepthTexture;
    private int rimWidth;
    private int rimHeight;
    /** Set if the target ever fails to build, so the rim falls back to fresnel instead of failing every frame. */
    private boolean rimTargetBroken;
    private final int[] scratchViewport = new int[4];
    private final Vector2f scratchRimOrigin = new Vector2f();

    /**
     * Where the outline starts to thin, and where there is none left, in metres from the camera.
     *
     * <p>A constant screen width is right for a figure that fills part of the screen and wrong for one that
     * is a few pixels of it: the line does not shrink with the character, so past some distance it is most
     * of what is left, and a crowd on the far side of the island reads as a row of black marks. It is drawn
     * at full strength out to {@value #OUTLINE_FADE_START} m, where a person stands about sixty pixels tall
     * at 1080p and the line is a twentieth of that, and is gone by {@value #OUTLINE_FADE_END} m, where they
     * are half the height and it would be a tenth of it on each side.
     *
     * <p>Nothing pops at the far end: the thickness is already near zero when the pass stops being drawn.
     */
    private static final float OUTLINE_FADE_START = 25f;
    private static final float OUTLINE_FADE_END = 45f;

    /** Reused by the outline fade, which measures once a model and runs on the render thread. */
    private final Vector3f scratchModelOrigin = new Vector3f();

    // Light and rim colours now arrive per frame in SceneLighting; only the shape of the response
    // (how tight the rim is, how sharp the specular) stays a fixed property of the renderer.
    private final float rimPower = 3.0f;
    private final float rimThreshold = 0.6f;

    private final Vector3f specularColor = new Vector3f(1.0f, 1.0f, 1.0f);

    /**
     * The hue of the drawn line at the shading boundary — the painted anime edge.
     *
     * <p><b>A multiplier over the tone the line lies on, not light added to it</b>, which is why it is a
     * warm <i>near</i>-white rather than the saturated orange it used to be. As added light it produced
     * exactly what was reported from play — a bright white line between light and shadow — for two
     * reasons at once: most of the line lies on the lit tone, so adding to it manufactured a tone
     * brighter than any in the ramp (measured, luminance 191 against the lit tone's 167), and on a
     * fragment that bright the red channel is against the shoulder of the ACES curve, so the warmth was
     * compressed out and a colour authored at 1.00 : 0.45 : 0.32 reached the screen at 1.00 : 0.79 :
     * 0.61. Multiplying instead cannot overshoot, and works by pulling green and blue down into the
     * responsive part of the curve, so the warmth survives.
     *
     * <p>Read as a multiplier the numbers say: leave red alone, take a third out of green and nearly
     * half out of blue. {@code character.frag} mixes toward it by {@code terminatorStrength}, so the
     * band lands in value between the lit tone and the one below it, which is where a painted
     * terminator accent sits.
     */
    private final Vector3f terminatorColor = new Vector3f(1.0f, 0.66f, 0.55f);

    /**
     * How shiny the character is. Settable so a preview can sweep it; see {@link CharacterSurface},
     * which is also where the numbers and the reason they moved are written down.
     */
    private CharacterSurface characterSurface = CharacterSurface.DEFAULT;

    /**
     * Per-instance transform streams for the two prop passes.
     *
     * <p>Two buffers rather than one, because only foliage needs a tint and a stream that carried it
     * regardless would upload three dead floats for every rock in the world. Sized for the largest single
     * mesh's worth of props in view, not the whole pass — measured, the busiest pool puts a few dozen
     * instances in a batch, so this is generous by two orders of magnitude and costs well under a megabyte.
     */
    private static final int MAX_PROP_INSTANCES = 4096;

    /**
     * Actual GL submissions made by the prop passes since {@link #resetDrawStats()}.
     *
     * <p>Counted rather than derived, because the two numbers stopped agreeing the moment these passes were
     * instanced: {@code FrameCostProbe} was reporting one draw per visible prop, which was true when it was
     * written and is now the instance count. A figure that is merely plausible is worse than none.
     */
    private int propDrawCalls;

    private final InstanceBuffer propInstances = new InstanceBuffer(MAX_PROP_INSTANCES, false);
    private final InstanceBuffer foliageInstances = new InstanceBuffer(MAX_PROP_INSTANCES, true);

    public Renderer() {
        outlineShader = new Shader("/shaders/outline.vert", "/shaders/outline.frag");
        characterShader = new Shader("/shaders/character.vert", "/shaders/character.frag");
        terrainShader = new Shader("/shaders/terrain.vert", "/shaders/terrain.frag");
        foliageShader = new Shader("/shaders/foliage.vert", "/shaders/foliage.frag");
        animeLeafShader = new Shader("/shaders/foliage.vert", "/shaders/anime_leaves.frag");
        entityShader = new Shader("/shaders/entity.vert", "/shaders/entity.frag");
        rimDepthShader = new Shader("/shaders/character.vert", "/shaders/depth.frag");

        outlineShader.bindUniformBlock("BoneMatrices", BONE_MATRICES_BINDING);
        characterShader.bindUniformBlock("BoneMatrices", BONE_MATRICES_BINDING);
        rimDepthShader.bindUniformBlock("BoneMatrices", BONE_MATRICES_BINDING);

        // The shadow map shares the bone UBO binding point, so its skinned depth shader poses the
        // character from the same upload the toon pass uses rather than keeping a second copy.
        shadowMap = new ShadowMap(BONE_MATRICES_BINDING);

        boneMatrixUbo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, boneMatrixUbo);
        glBufferData(GL_UNIFORM_BUFFER, (long) MAX_BONES * 16 * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, BONE_MATRICES_BINDING, boneMatrixUbo);

        boneMatrixBuffer = BufferUtils.createFloatBuffer(MAX_BONES * 16);
    }

    /**
     * The sun's shadow map. Callers drive the depth pass through it — {@code begin}, draw every caster,
     * {@code end} — before rendering the scene; each scene pass then samples whatever is in it.
     *
     * <p>Exposed rather than hidden behind a single {@code renderShadows(everything)} because the list of
     * what casts is game knowledge: which props exist, that leaves need the cutout path, that the
     * character is one skinned model. {@link Renderer} would have to learn all of it to own the pass.
     */
    public ShadowMap getShadowMap() {
        return shadowMap;
    }

    /**
     * Uploads the animator's current pose to the shared bone-matrix UBO.
     *
     * <p>Public because the shadow pass runs before {@link #renderFrame} and needs the same pose to skin
     * its depth shader with. Calling it and then {@code renderFrame} in the same frame re-uploads the
     * identical data — a few kilobytes, and cheaper than the alternative of the two passes each keeping
     * their own copy of the skeleton.
     */
    /** Whether a rig too big for the block has already been complained about. */
    private boolean warnedAboutBoneCount;

    public void uploadPose(Animator animator) {
        // GL 3.3 guarantees a uniform block of only 16 KB, which is where MAX_BONES comes from, so a rig
        // with more deforming bones than that is a real possibility rather than a hypothetical — and
        // writing them all would overflow the buffer and end the session. The character comes out wrong
        // from the surplus bone onward instead, which is a thing a person can see and report.
        if (animator.getBoneCount() > MAX_BONES && !warnedAboutBoneCount) {
            warnedAboutBoneCount = true;
            System.err.println("Rig has " + animator.getBoneCount() + " deforming bones; only the first "
                    + MAX_BONES + " can be posed. Everything weighted to the rest will not move.");
        }
        animator.writeBoneMatrices(boneMatrixBuffer);
        glBindBuffer(GL_UNIFORM_BUFFER, boneMatrixUbo);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, boneMatrixBuffer);
    }

    /** Scratch for the tinted base colour, so a per-mesh uniform upload allocates nothing. */
    private final Vector4f tintedBaseColor = new Vector4f();

    public void renderFrame(Camera camera, SceneLighting lighting, Model model, Animator animator, Matrix4f modelMatrix) {
        uploadPose(animator);
        findHead(animator, modelMatrix);
        drawCharacterModel(camera, lighting, model, modelMatrix, true, headFound);
    }

    /**
     * Where this figure's head is and which way it faces, in world space — see {@link FaceRig}. Leaves
     * {@link #headFound} false on a rig with none of the named bones, which turns face shading off for the
     * figure rather than measuring it against somewhere arbitrary.
     */
    private void findHead(Animator animator, Matrix4f modelMatrix) {
        headFound = false;
        for (String name : faceRig.headNodes()) {
            if (animator.nodeFromBind(name, scratchHeadDelta) == null) {
                continue;
            }
            // Model matrix after the delta: rest → now in model space, then model → world.
            scratchHeadDelta.mulLocal(modelMatrix);
            animator.nodeBindPosition(name, headCenter).add(faceRig.centerOffset());
            scratchHeadDelta.transformPosition(headCenter);
            scratchHeadDelta.transformDirection(headForward.set(faceRig.forward())).normalize();
            scratchHeadDelta.transformDirection(headUp.set(faceRig.up())).normalize();
            headFound = true;
            return;
        }
    }

    /**
     * Draws a rigid attachment — what is hanging off a socket — with the character's own look.
     *
     * <p>Through the same shaders as the body rather than the prop path, and that is the point rather than
     * a convenience: a sword lit by the terrain shader in a hand lit by the cel ramp reads as two objects
     * photographed separately. It shares the outline, the tone boundaries, the drawn edge and the rim, so
     * an attachment belongs to the figure carrying it.
     *
     * <p>No pose is uploaded and none is read — see the {@code skinned} switch in {@code character.vert}.
     * The socket has already put the thing where it goes; skinning it again would drag it off by whatever
     * the bone UBO happened to hold.
     */
    /**
     * Leaves these meshes out of the models drawn from now on (their outlines too), until called again; null or empty
     * for none. For a model some of whose parts something else draws, as a {@code Model3D} draws a part in a shader.
     */
    public void skipMeshes(java.util.Set<Mesh> meshes) {
        skippedMeshes = meshes == null || meshes.isEmpty() ? null : meshes;
    }

    public void renderAttachment(Camera camera, SceneLighting lighting, Model model, Matrix4f modelMatrix) {
        // No face: an attachment has no head of its own, and the last figure's is not this one's.
        drawCharacterModel(camera, lighting, model, modelMatrix, false, false);
    }

    /**
     * Draws a garment — clothing, hair, anything skinned to the character rather than hung off it.
     *
     * <p>Skinned, and with <b>no pose upload of its own</b>: a garment shares the character's bone table
     * outright (see {@code AssetLoader.loadGarment}), so what is already in the bone UBO from the body's
     * own draw is exactly the pose it wants. Uploading again would be the same matrices at best and, the
     * first time a caller reordered the two draws, a shirt a frame behind the chest inside it.
     *
     * <p>Which means the body must be drawn first. That is not an ordering anyone should have to
     * remember, so {@link #renderFrame} is what uploads and {@code Engine} calls this after it.
     */
    public void renderGarment(Camera camera, SceneLighting lighting, Model garment, Matrix4f modelMatrix) {
        // The body's head, found by renderFrame — the garment shares its pose, so it shares its head too.
        drawCharacterModel(camera, lighting, garment, modelMatrix, true, headFound);
    }

    /**
     * One definition of how a character-shaded model is drawn, used by the body and by everything on it.
     *
     * <p>Two copies would be two sets of cel parameters, two outline colours and two gloss rules, free to
     * drift the first time either is retuned — and what that looks like is a hat shaded like a different
     * game from the head under it.
     */
    /** How much of the outline a model this far from the camera gets: 1 near, 0 beyond the fade. */
    private float outlineFade(Camera camera, Matrix4f modelMatrix) {
        modelMatrix.getTranslation(scratchModelOrigin);
        float distance = scratchModelOrigin.distance(camera.getPosition());
        if (distance <= OUTLINE_FADE_START) {
            return 1f;
        }
        if (distance >= OUTLINE_FADE_END) {
            return 0f;
        }
        return 1f - (distance - OUTLINE_FADE_START) / (OUTLINE_FADE_END - OUTLINE_FADE_START);
    }

    private void drawCharacterModel(Camera camera, SceneLighting lighting, Model model,
                                    Matrix4f modelMatrix, boolean skinned, boolean headKnown) {
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);

        Matrix4f view = camera.getViewMatrix();
        Matrix4f projection = camera.getProjectionMatrix();
        Matrix3f normalMatrix = modelMatrix.normal(new Matrix3f());
        // Measured from the model's origin rather than per vertex: one figure is either near enough to be
        // drawn with a line or it is not, and half a character outlined would be worse than either.
        float outlineWidth = outlineThickness * outlineFade(camera, modelMatrix);
        // Before any of the figure is drawn, so the rim reads the whole of it — and once per figure rather
        // than per mesh, so a sleeve's rim knows where the arm inside it is.
        boolean rimDrawn = depthRim && drawRimDepth(model, modelMatrix, normalMatrix, skinned, view, projection);

        for (Mesh mesh : model.getMeshes()) {
            if (skippedMeshes != null && skippedMeshes.contains(mesh)) {
                continue;
            }
            Material material = model.getMaterials().isEmpty()
                    ? null
                    : model.getMaterials().get(mesh.getMaterialIndex());

            float meshOutlineWidth = outlineWidth * (material != null ? material.getOutlineScale() : 1f);
            if (meshOutlineWidth > 0f) {
                glCullFace(GL_FRONT);
                outlineShader.use();
                outlineShader.setUniform("model", modelMatrix);
                outlineShader.setUniform("view", view);
                outlineShader.setUniform("projection", projection);
                outlineShader.setUniform("outlineColor", outlineColor);
                outlineShader.setUniform("outlineThickness", meshOutlineWidth);
                outlineShader.setUniform("skinned", skinned ? 1 : 0);
                // The line's hue comes from the surface it borders — see outlineAlbedoMix.
                if (material != null) {
                    material.getBaseColorTexture().bind(0);
                    outlineShader.setUniform("baseColorTexture", 0);
                    outlineShader.setUniform("baseColorFactor", material.tintedBaseColor(tintedBaseColor));
                    outlineShader.setUniform("outlineAlbedoMix", outlineAlbedoMix);
                } else {
                    outlineShader.setUniform("outlineAlbedoMix", 0f);
                }
                mesh.draw();
            }

            glCullFace(GL_BACK);
            characterShader.use();
            characterShader.setUniform("model", modelMatrix);
            characterShader.setUniform("view", view);
            characterShader.setUniform("projection", projection);
            characterShader.setUniform("normalMatrix", normalMatrix);
            characterShader.setUniform("skinned", skinned ? 1 : 0);
            characterShader.setUniform("viewPos", camera.getPosition());
            applyLighting(characterShader, lighting);
            // The character's own cel ramp — see CharacterSurface, which is also where every number
            // here and the render that chose it are written down.
            characterShader.setUniform("celBands", characterSurface.celBands());
            characterShader.setUniform("rampStart", characterSurface.rampStart());
            characterShader.setUniform("rampEnd", characterSurface.rampEnd());
            characterShader.setUniform("shadowTint", characterSurface.shadowTint());
            characterShader.setUniform("shadowSaturation", characterSurface.shadowSaturation());
            characterShader.setUniform("shadowLift", characterSurface.shadowLift());
            characterShader.setUniform("terminatorColor", terminatorColor);
            characterShader.setUniform("terminatorStrength", characterSurface.terminatorStrength());
            characterShader.setUniform("rimStrength", characterSurface.rimStrength());
            characterShader.setUniform("specularColor", specularColor);

            if (material != null) {
                material.getBaseColorTexture().bind(0);
                characterShader.setUniform("baseColorTexture", 0);
                // Tinted rather than raw: the game marks a model's skin materials with the colour the
                // player chose, and the renderer multiplies without ever learning which is which. See
                // Material.setTint. An untinted material multiplies by white and is unaffected.
                characterShader.setUniform("baseColorFactor", material.tintedBaseColor(tintedBaseColor));
                // And how glossy this particular surface is, which is zero unless the game has said
                // otherwise — hair takes a highlight, skin takes none.
                characterShader.setUniform("specularStrength", material.getGlossStrength());
                characterShader.setUniform("specularShininess", material.getGlossShininess());
            } else {
                characterShader.setUniform("baseColorFactor", new Vector4f(1, 1, 1, 1));
                characterShader.setUniform("specularStrength", 0f);
                characterShader.setUniform("specularShininess", Material.DEFAULT_GLOSS_SHININESS);
            }
            applyAnimeMaps(material, headKnown);
            characterShader.setUniform("rimDepth", RIM_DEPTH_UNIT);
            characterShader.setUniform("hasDepthRim", rimDrawn ? 1 : 0);
            if (rimDrawn) {
                glActiveTexture(GL_TEXTURE0 + RIM_DEPTH_UNIT);
                glBindTexture(GL_TEXTURE_2D, rimDepthTexture);
                characterShader.setUniform("rimViewportOrigin",
                        scratchRimOrigin.set(scratchViewport[0], scratchViewport[1]));
                characterShader.setUniform("rimPixelScale", rimHeight / 1080f);
            }

            mesh.draw();
        }
        // Unit 0 active again on the way out. The anime maps and the rim depth leave a high unit selected,
        // and code elsewhere binds with a bare glBindTexture and samples unit 0 — BitmapFont among it — so
        // whatever it bound next would land on unit 12 and its draw would read whatever was on 0 instead.
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * The optional painted maps that take a character from cel shaded to anime — each one off unless the
     * material carries it, so an unpainted model draws exactly as before. See {@code Material} for what
     * each map holds and {@code character.frag} for what is done with it.
     *
     * <p>Every sampler is pointed at its own unit on every draw, used or not. Left at their default of 0
     * they would share a unit with each other, which is harmless, but a sampler that ever shared one with
     * the shadow map's {@code sampler2DShadow} would fail the draw outright.
     */
    private void applyAnimeMaps(Material material, boolean headKnown) {
        characterShader.setUniform("lightMap", LIGHT_MAP_UNIT);
        characterShader.setUniform("shadowRamp", SHADOW_RAMP_UNIT);
        characterShader.setUniform("faceShadowMap", FACE_MAP_UNIT);
        characterShader.setUniform("matcap", MATCAP_UNIT);

        Texture lightMap = material != null ? material.getLightMap() : null;
        characterShader.setUniform("hasLightMap", lightMap != null ? 1 : 0);
        if (lightMap != null) {
            lightMap.bind(LIGHT_MAP_UNIT);
        }

        Texture ramp = material != null ? material.getShadowRamp() : null;
        characterShader.setUniform("hasShadowRamp", ramp != null ? 1 : 0);
        if (ramp != null) {
            ramp.bind(SHADOW_RAMP_UNIT);
            characterShader.setUniform("shadowRampRow", material.getShadowRampRow());
        }

        Texture matcap = material != null ? material.getMatcap() : null;
        characterShader.setUniform("hasMatcap", matcap != null && material.getMatcapStrength() > 0f ? 1 : 0);
        if (matcap != null) {
            matcap.bind(MATCAP_UNIT);
            characterShader.setUniform("matcapStrength", material.getMatcapStrength());
        }

        // 0 = lit by its own normals, 1 = lit as the sphere of the head, 2 = the face shadow map.
        int faceMode = 0;
        if (material != null && material.isFace()) {
            if (headKnown) {
                faceMode = material.getFaceShadowMap() != null ? 2 : 1;
            } else if (!warnedAboutMissingHead) {
                // Said once, loudly, rather than silently shading the face by its normals: a face that
                // looks blotchy for no visible reason is a long hunt, and this is the reason.
                warnedAboutMissingHead = true;
                System.err.println("Material '" + material.getName() + "' is marked as a face, but the rig has"
                        + " none of the head bones " + faceRig.headNodes() + " — face shading is off."
                        + " Name the head with Renderer.setFaceRig.");
            }
        }
        characterShader.setUniform("faceMode", faceMode);
        if (faceMode > 0) {
            characterShader.setUniform("headCenter", headCenter);
            characterShader.setUniform("headForward", headForward);
            characterShader.setUniform("headUp", headUp);
        }
        if (faceMode == 2) {
            material.getFaceShadowMap().bind(FACE_MAP_UNIT);
            characterShader.setUniform("faceMirrorU", material.getFaceMirrorU());
        }
    }

    /**
     * Draws one figure's depth, and only that figure's, into a texture the size of the current viewport —
     * what {@code depthRim} in {@code character.frag} looks across to find the figure's edges.
     *
     * <h2>Why its own depth and not the scene's</h2>
     * The scene's depth buffer is the one the character is about to be drawn into, and a pass cannot read
     * what it is writing. Copying it first would work, but would put the rim at the mercy of whatever stands
     * behind the figure: a wall half a metre back erases it. A rim is a property of the drawing, not of the
     * set, so the figure is measured against itself — past its silhouette is nothing at all, and past an arm
     * held in front of the body is the body.
     *
     * <p>Cheap: a depth-only redraw of a few thousand triangles with an empty fragment shader, and a clear.
     * Everything it changes — framebuffer, viewport, the active texture unit — is put back before it returns.
     *
     * @return whether the depth is there to read; false falls the rim back to fresnel
     */
    private boolean drawRimDepth(Model model, Matrix4f modelMatrix, Matrix3f normalMatrix, boolean skinned,
                                 Matrix4f view, Matrix4f projection) {
        if (rimTargetBroken) {
            return false;
        }
        glGetIntegerv(GL_VIEWPORT, scratchViewport);
        int width = scratchViewport[2];
        int height = scratchViewport[3];
        if (width <= 0 || height <= 0 || !ensureRimTarget(width, height)) {
            return false;
        }

        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, rimFramebuffer);
            glViewport(0, 0, width, height);
            if (scissor) {
                glDisable(GL_SCISSOR_TEST);
            }
            glDepthMask(true);
            glClear(GL_DEPTH_BUFFER_BIT);
            glCullFace(GL_BACK);

            rimDepthShader.use();
            rimDepthShader.setUniform("model", modelMatrix);
            rimDepthShader.setUniform("view", view);
            rimDepthShader.setUniform("projection", projection);
            rimDepthShader.setUniform("normalMatrix", normalMatrix);
            rimDepthShader.setUniform("skinned", skinned ? 1 : 0);
            for (Mesh mesh : model.getMeshes()) {
                mesh.draw();
            }
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
            glViewport(scratchViewport[0], scratchViewport[1], width, height);
            if (scissor) {
                glEnable(GL_SCISSOR_TEST);
            }
        }
        return true;
    }

    /** Builds the rim's depth target, or resizes it to a viewport that has changed. False if GL refused. */
    private boolean ensureRimTarget(int width, int height) {
        if (rimFramebuffer != 0 && width == rimWidth && height == rimHeight) {
            return true;
        }
        // Its own unit, so building the texture never disturbs one another pass left bound — the shadow
        // map's above all, which several passes expect to find where they left it.
        glActiveTexture(GL_TEXTURE0 + RIM_DEPTH_UNIT);
        if (rimDepthTexture == 0) {
            rimDepthTexture = glGenTextures();
        }
        glBindTexture(GL_TEXTURE_2D, rimDepthTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT,
                (java.nio.ByteBuffer) null);
        // Read texel by texel with texelFetch, as plain depth rather than as a shadow comparison.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);

        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        if (rimFramebuffer == 0) {
            rimFramebuffer = glGenFramebuffers();
        }
        glBindFramebuffer(GL_FRAMEBUFFER, rimFramebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, rimDepthTexture, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
        glActiveTexture(GL_TEXTURE0);

        if (status != GL_FRAMEBUFFER_COMPLETE) {
            rimTargetBroken = true;
            System.err.println("Character rim depth target is incomplete (status 0x" + Integer.toHexString(status)
                    + "); the rim falls back to fresnel.");
            return false;
        }
        rimWidth = width;
        rimHeight = height;
        return true;
    }

    /**
     * Whether the character's rim is found in screen space from its own depth (true, the default) or from
     * fresnel (false, the look before it, and one depth pass cheaper per figure).
     */
    public void setDepthRim(boolean enabled) {
        this.depthRim = enabled;
    }

    public boolean isDepthRim() {
        return depthRim;
    }

    /**
     * How coloured the ink line is: 0 a single ink colour, 1 fully the hue of what it borders. 0.75 ships.
     */
    public void setOutlineAlbedoMix(float mix) {
        this.outlineAlbedoMix = Math.max(0f, Math.min(1f, mix));
    }

    public float getOutlineAlbedoMix() {
        return outlineAlbedoMix;
    }

    /** Where to find a character's head for face shading — see {@link FaceRig}. */
    public void setFaceRig(FaceRig rig) {
        this.faceRig = rig == null ? FaceRig.DEFAULT : rig;
    }

    public FaceRig getFaceRig() {
        return faceRig;
    }

    /** The character's shine. Set once by whoever owns the look, not per frame. */
    public void setCharacterSurface(CharacterSurface surface) {
        this.characterSurface = surface == null ? CharacterSurface.DEFAULT : surface;
    }

    public CharacterSurface getCharacterSurface() {
        return characterSurface;
    }

    /**
     * Installs the stand-in for ground cover past the distance real cover reaches — see {@link GroundDetail}.
     * Set once by the game, not per frame: it describes the world's materials, not this frame.
     */
    public void setGroundDetail(GroundDetail detail) {
        this.groundDetail = detail == null ? GroundDetail.NONE : detail;
    }

    private void applyGroundDetail(Shader shader, GroundDetail detail) {
        shader.setUniform("coverColor", detail.coverColor());
        shader.setUniform("coverMatch", detail.match());
        shader.setUniform("coverFadeStart", detail.fadeStart());
        shader.setUniform("coverFadeEnd", detail.fadeEnd());
        shader.setUniform("coverStrength", detail.strength());
        shader.setUniform("coverScale", detail.scale());
        shader.setUniform("coverDarkening", detail.darkening());
    }

    private GroundDetail groundDetail = GroundDetail.NONE;

    /**
     * Installs what the ground cover bends away from — see {@link CoverPush}.
     *
     * <p>Frame state, unlike {@link #setGroundDetail}: the centre is a walking body. Set it every frame
     * while anything is walking through cover, and {@link CoverPush#NONE} when nothing is.
     */
    public void setCoverPush(CoverPush push) {
        this.coverPush = push == null ? CoverPush.NONE : push;
    }

    private void applyCoverPush(Shader shader, CoverPush push) {
        boolean present = push.isPresent();
        shader.setUniform("coverPush", present ? 1 : 0);
        // Set even when switched off: the flag and the radius are both read before anything else, and
        // leaving a live radius behind is how a stale frame's parting follows the next pass around.
        shader.setUniform("pushRadius", present ? push.radius() : 0f);
        if (!present) {
            return;
        }
        shader.setUniform("pushCentre", push.centre());
        shader.setUniform("pushStrength", push.strength());
        shader.setUniform("pushHeight", push.height());
    }

    private CoverPush coverPush = CoverPush.NONE;

    /**
     * World height below which terrain and foliage fragments are discarded, for the planar water
     * reflection. {@link #NO_CLIP} everywhere else.
     *
     * <p>Frame state rather than world state, unlike {@link #setGroundDetail} — it changes between the
     * reflection pass and the pass that follows it, so <b>whoever sets it must set it back</b>. That is why
     * it is applied on every terrain and foliage draw rather than once: a stale clip left over from the
     * reflection would cut the real world off at the waterline, which is a spectacular way to fail and an
     * easy one to leave behind.
     */
    public static final float NO_CLIP = -1.0e9f;

    private float propEmissive = 1f;
    private float clipBelowY = NO_CLIP;

    /** The point lights this frame is lit by; see {@link #setPointLights}. */
    private final PointLights.Frame pointLights = new PointLights.Frame();
    /** Bumped whenever {@link #pointLights} changes, so a shader is sent them again only then. */
    private int pointLightVersion = 1;
    /** The version each shader was last sent: uniforms stay with the program, and most frames change nothing. */
    private final java.util.Map<Shader, Integer> pointLightsSent = new java.util.IdentityHashMap<>();
    private static final String[] POINT_LIGHT_POSITION = uniformArray("pointLightPosition");
    private static final String[] POINT_LIGHT_COLOR = uniformArray("pointLightColor");
    private static final String[] POINT_LIGHT_RADIUS = uniformArray("pointLightRadius");

    private static String[] uniformArray(String name) {
        String[] names = new String[PointLights.MAX];
        for (int i = 0; i < names.length; i++) {
            names[i] = name + "[" + i + "]";
        }
        return names;
    }

    /**
     * How far a leaf card is displaced at the top of its sway, in world units.
     *
     * <p>A couple of centimetres. A canopy is thousands of cards a few centimetres across, so what reads is
     * the whole mass breathing rather than any one card travelling — and anything larger detaches a leaf
     * visibly from the twig it hangs on, since the branch does not move with it.
     */
    private static final float LEAF_SWAY = 0.035f;

    /** The world's wind and the scene clock, for the leaf sway. Set by the game; still air by default. */
    private Vector2f wind = new Vector2f();
    private float sceneTime;
    /** Whether sprites mark their pixels out of the bloom; see {@link #setSpritesUnbloomed}. */
    private boolean spritesUnbloomed;

    public void setWind(Vector2f windVelocity, float time) {
        this.wind = windVelocity == null ? new Vector2f() : new Vector2f(windVelocity);
        this.sceneTime = time;
    }

    /**
     * How hard an emissive <b>prop</b> burns — a village lamp, and nothing else today.
     *
     * <p>Props only, and that restriction is the point: {@code renderTerrain} passes 1 unconditionally, so
     * <b>magma is never dimmed by this</b>. Molten rock is molten at noon; a street lamp is not lit at
     * noon. They are two different questions and one scale cannot answer both.
     *
     * <p>Set from {@code WorldLighting.lampGlow} every frame, so lamps come on at dusk and go out at dawn.
     * Frame state, and like {@link #setClipBelowY} whoever changes it must put it back — it is also what
     * lets a tool render the control for an emissive material, since a pane never baked HDR and a lamp
     * working perfectly produce the same picture. See {@code VillagePreview}.
     */
    public void setPropEmissive(float scale) {
        this.propEmissive = scale;
    }

    /**
     * The lamps and other {@link PointLight}s lighting what is drawn from now on, as {@link PointLights#nearest}
     * picked them for the frame: lighting the ground, props, trees and sprites near them, in {@code terrain.frag}
     * and {@code foliage.frag}. Frame state, like {@link #setPropEmissive}: a world sets its own before drawing and
     * puts back {@link PointLights.Frame#NONE} after, so a scene with no lamps is never lit by another's.
     */
    public void setPointLights(PointLights.Frame lights) {
        if (pointLights.copy(lights == null ? PointLights.Frame.NONE : lights)) {
            pointLightVersion++;
        }
    }

    public void setClipBelowY(float y) {
        this.clipBelowY = y;
    }

    /**
     * Installs the tiling materials laid over the ground — see {@link GroundTextures}. Set once by the
     * game like {@link #setGroundDetail}: it describes the world's materials, not this frame.
     */
    public void setGroundTextures(GroundTextures textures) {
        this.groundTextures = textures == null ? GroundTextures.NONE : textures;
    }

    private void applyGroundTextures(Shader shader, GroundTextures textures) {
        boolean present = textures.isPresent();
        shader.setUniform("groundCount", present ? textures.materials().size() : 0);
        // The samplers still have to name a legal unit even with the block switched off: an unbound
        // sampler is undefined even on a branch the shader never takes.
        shader.setUniform("groundAlbedo", GROUND_ALBEDO_UNIT);
        shader.setUniform("groundNormal", GROUND_NORMAL_UNIT);
        if (!present) {
            shader.setUniform("groundHasNormal", 0);
            return;
        }

        textures.albedo().bind(GROUND_ALBEDO_UNIT);
        boolean hasNormal = textures.normalMap() != null;
        if (hasNormal) {
            textures.normalMap().bind(GROUND_NORMAL_UNIT);
        }
        shader.setUniform("groundHasNormal", hasNormal ? 1 : 0);
        shader.setUniform("groundFadeStart", textures.fadeStart());
        shader.setUniform("groundFadeEnd", textures.fadeEnd());

        List<GroundMaterial> materials = textures.materials();
        for (int i = 0; i < materials.size(); i++) {
            GroundMaterial material = materials.get(i);
            shader.setUniform("groundMean[" + i + "]", material.meanAlbedo());
            shader.setUniform("groundTile[" + i + "]", material.tileSize());
            shader.setUniform("groundNormalStrength[" + i + "]", material.normalStrength());
            shader.setUniform("groundGrainStrength[" + i + "]", material.grainStrength());
            shader.setUniform("groundColorInfluence[" + i + "]", material.colorInfluence());
        }
    }

    private GroundTextures groundTextures = GroundTextures.NONE;

    /** The water being drawn by {@link #renderWater}, for the props pass it goes through; null at every other time. */
    private WaterLook drawingWater;
    /** Its clock, seconds: what moves it. */
    private float drawingWaterTime;

    /**
     * Draws a map's water — its rivers, lakes and falls, or the sea's plane — wearing its own moving art: see
     * {@link WaterLook}. The meshes are the same as {@link #renderProps} would draw with the water's ground layer.
     *
     * @param time seconds on the water's own clock: what carries it along, drifts the sea and rolls the waves in
     */
    public void renderWater(Camera camera, SceneLighting lighting, Collection<StaticInstance> instances, WaterLook look,
                            float time) {
        drawingWater = look;
        drawingWaterTime = time;
        try {
            renderProps(camera, lighting, instances, GroundTextures.NONE, null);
        } finally {
            drawingWater = null;
            terrainShader.use();
            terrainShader.setUniform("waterPass", 0);
        }
    }

    /** Sends the terrain shader the water's art, or switches the water off ({@code look} null). */
    private void applyWater(Shader shader, WaterLook look) {
        // Legal units whatever happens: an unbound sampler is undefined even on a branch the shader never takes.
        shader.setUniform("waterAlbedo", WATER_ALBEDO_UNIT);
        shader.setUniform("waterNormal", WATER_NORMAL_UNIT);
        shader.setUniform("waterFlow", WATER_FLOW_UNIT);
        if (look == null || look.albedo() == null || look.flow() == null) {
            shader.setUniform("waterPass", 0);
            return;
        }
        shader.setUniform("waterPass", 1);
        // The water's own clock, over the scene's (which a game may never set, leaving the water still).
        shader.setUniform("time", drawingWaterTime);
        look.albedo().bind(WATER_ALBEDO_UNIT);
        boolean relief = look.normalMap() != null;
        if (relief) {
            look.normalMap().bind(WATER_NORMAL_UNIT);
        }
        shader.setUniform("waterHasNormal", relief ? 1 : 0);
        look.flow().bind(WATER_FLOW_UNIT);
        shader.setUniform("waterFlowRect", new org.joml.Vector4f(look.originX(), look.originZ(), look.sizeX(), look.sizeZ()));
        shader.setUniform("waterShoreRange", WaterLook.SHORE_RANGE);
        shader.setUniform("waterTiles", look.tiles());
        shader.setUniform("waterFlowSpeed", look.flowSpeed());
        shader.setUniform("waterShallow", look.shallowColor());
        shader.setUniform("waterWaveLength", look.waveLength());
        shader.setUniform("waterWaveReach", look.waveReach());
    }

    /**
     * Installs the whole-block pictures laid over a tiled map's ground — see {@link GroundPieces}. Set by the scene
     * that shows the map, and put back to {@link GroundPieces#NONE} when it ends: every scene shares this renderer.
     */
    public void setGroundPieces(GroundPieces pieces) {
        this.groundPieces = pieces == null ? GroundPieces.NONE : pieces;
    }

    private void applyGroundPieces(Shader shader, GroundPieces pieces) {
        boolean present = pieces.isPresent();
        shader.setUniform("piecesOn", present ? 1 : 0);
        // Legal units even when off, as with the ground textures: an unset sampler is undefined on any branch.
        shader.setUniform("pieceAlbedo", PIECE_ALBEDO_UNIT);
        shader.setUniform("pieceNormal", PIECE_NORMAL_UNIT);
        shader.setUniform("pieceLayout", PIECE_LAYOUT_UNIT);
        if (!present) {
            shader.setUniform("pieceHasNormal", 0);
            return;
        }
        pieces.albedo().bind(PIECE_ALBEDO_UNIT);
        pieces.layout().bind(PIECE_LAYOUT_UNIT);
        boolean hasNormal = pieces.normalMap() != null;
        if (hasNormal) {
            pieces.normalMap().bind(PIECE_NORMAL_UNIT);
        }
        shader.setUniform("pieceHasNormal", hasNormal ? 1 : 0);
        shader.setUniform("pieceOrigin", new Vector2f(pieces.originX(), pieces.originZ()));
        shader.setUniform("pieceBlockSize", pieces.blockSize());
        shader.setUniform("pieceGrid", new Vector2f(pieces.layout().getWidth(), pieces.layout().getHeight()));
        shader.setUniform("pieceNormalStrength", pieces.normalStrength());
        shader.setUniform("pieceFadeStart", pieces.fadeStart());
        shader.setUniform("pieceFadeEnd", pieces.fadeEnd());
    }

    private GroundPieces groundPieces = GroundPieces.NONE;

    public void renderTerrain(Camera camera, SceneLighting lighting, Collection<StaticMesh> terrainMeshes) {
        renderTerrain(camera, lighting, terrainMeshes, new Matrix4f(), groundTextures);
    }

    /**
     * Terrain meshes placed by a model matrix (a terrain that stands where its entity does) and laid with ground
     * pictures of their own rather than the ones {@link #setGroundTextures} installed.
     */
    public void renderTerrain(Camera camera, SceneLighting lighting, Collection<StaticMesh> terrainMeshes, Matrix4f placed,
                              GroundTextures textures) {
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        Matrix4f view = camera.getViewMatrix();
        Matrix4f projection = camera.getProjectionMatrix();
        Matrix4f model = new Matrix4f(placed);
        Matrix3f normalMatrix = model.normal(new Matrix3f());

        terrainShader.use();
        applyWater(terrainShader, null);
        terrainShader.setUniform("model", model);
        terrainShader.setUniform("view", view);
        terrainShader.setUniform("projection", projection);
        terrainShader.setUniform("normalMatrix", normalMatrix);
        terrainShader.setUniform("viewPos", camera.getPosition());
        applyLighting(terrainShader, lighting);
        terrainShader.setUniform("rimPower", rimPower);
        terrainShader.setUniform("rimThreshold", rimThreshold);
        // The magma gradient is the one animated thing in this shader; everything else here is static.
        terrainShader.setUniform("time", sceneTime);
        // An HDR albedo here is molten rock — magma is the only terrain material whose colour leaves the
        // [0,1] range. In renderProps it is a light instead; see terrain.frag's `emissiveIsMolten`.
        terrainShader.setUniform("emissiveIsMolten", 1);
        // Always 1: molten rock is molten whatever the hour, so the lamp dimmer must not reach it.
        terrainShader.setUniform("emissiveScale", 1f);
        applyGroundDetail(terrainShader, groundDetail);
        // The ground itself never gives way underfoot, and the props pass shares this shader and leaves
        // the parting switched on until it switches it off again.
        applyCoverPush(terrainShader, CoverPush.NONE);
        terrainShader.setUniform("clipBelowY", clipBelowY);
        // Terrain slabs carry a model uniform, not an instance stream. Stated rather than relied upon: the
        // props pass shares this shader and leaves the flag set until it resets it.
        terrainShader.setUniform("instanced", 0);
        applyGroundTextures(terrainShader, textures == null ? GroundTextures.NONE : textures);
        applyGroundPieces(terrainShader, groundPieces);

        for (StaticMesh mesh : terrainMeshes) {
            mesh.draw();
        }
    }

    public void renderProps(Camera camera, SceneLighting lighting, Collection<StaticInstance> instances) {
        renderProps(camera, lighting, instances, GroundTextures.NONE, null);
    }

    /**
     * Draws props wearing a ground material, at one constant mixture for the whole pass.
     *
     * <p>For rocks, which are a single substance throughout and so want the material terrain varies per
     * vertex as a constant instead — no UVs needed, because the ground block projects triplanarly from
     * world space, which is the same reason terrain can be textured at all.
     *
     * @param groundWeights the mixture every mesh in this pass wears, or null for none. Which slot means
     *                      which material is the caller's business; this layer only forwards it
     */
    public void renderProps(Camera camera, SceneLighting lighting, Collection<StaticInstance> instances,
                            GroundTextures groundTextures, float[] groundWeights) {
        renderProps(camera, lighting, instances, groundTextures, groundWeights, false);
    }

    /**
     * Props, optionally drawn with back-face culling off.
     *
     * <p>For meshes built of <b>open shells</b> rather than closed solids — undergrowth, whose leaves are
     * one-sided sheets and whose stems are uncapped tubes, so culling deletes whichever half faces away.
     * The same allowance {@link #renderFoliage} makes for a leaf card, and it restores the state before
     * returning for the same reason, the contract {@code Skybox} and {@code UIRenderer} also follow.
     *
     * <p><b>Nothing flips the normal of a back face here</b>, unlike {@code foliage.frag}, which keys on
     * {@code gl_FrontFacing}. So this is only honest for geometry whose normals do not describe a
     * particular face — {@code ShrubModel} points its leaf normals out of the crown centroid precisely so
     * that both sides of a leaf are equally right. Handing arbitrary two-sided geometry to this will light
     * half of it as though the sun were on the other side.
     */
    public void renderProps(Camera camera, SceneLighting lighting, Collection<StaticInstance> instances,
                            GroundTextures groundTextures, float[] groundWeights, boolean twoSided) {
        renderProps(camera, lighting, instances, groundTextures, groundWeights, twoSided, CoverPush.NONE);
    }

    /**
     * Draws ground cover — the tufts of tall grass a field is made of, and nothing else today.
     *
     * <p>The same instanced prop path everything else here takes, with one difference: these bend away
     * from whatever is standing in them, per {@link #setCoverPush}. It is a pass of its own rather than a
     * flag on the instance because the parting is a uniform, and mixing a clump of grass into the same
     * call as the rock beside it would mean either bending the rock or streaming a per-instance flag to
     * say not to.
     *
     * <p>Cover is <b>not</b> given the {@link GroundDetail} treatment, for the reason props are not: that
     * one stands in for cover past the distance cover is drawn at, and applying it to the cover itself
     * would count the same grass twice.
     */
    public void renderGroundCover(Camera camera, SceneLighting lighting, Collection<StaticInstance> instances) {
        renderProps(camera, lighting, instances, GroundTextures.NONE, null, false, coverPush);
    }

    private void renderProps(Camera camera, SceneLighting lighting, Collection<StaticInstance> instances,
                             GroundTextures groundTextures, float[] groundWeights, boolean twoSided,
                             CoverPush push) {
        glEnable(GL_DEPTH_TEST);
        if (twoSided) {
            glDisable(GL_CULL_FACE);
        } else {
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
        }

        Matrix4f view = camera.getViewMatrix();
        Matrix4f projection = camera.getProjectionMatrix();

        terrainShader.use();
        // Props share this shader and must not take the *cover* treatment whatever else they wear: that
        // one is keyed on albedo, so a rock or a wall that happened to be the right shade of green would
        // sprout grass tone.
        applyGroundDetail(terrainShader, GroundDetail.NONE);
        applyCoverPush(terrainShader, push);
        terrainShader.setUniform("time", sceneTime);
        // An HDR albedo on a PROP is a light, not molten rock: nothing here is ever magma, and a prop
        // given the terrain reading comes out full of animated lava. See terrain.frag's
        // `emissiveIsMolten`. Every prop that predates village lamps has an albedo at or under 1, so this
        // changes nothing for any of them.
        terrainShader.setUniform("emissiveIsMolten", 0);
        terrainShader.setUniform("emissiveScale", propEmissive);
        terrainShader.setUniform("clipBelowY", clipBelowY);
        applyGroundTextures(terrainShader, groundTextures);
        applyWater(terrainShader, drawingWater);
        // A prop standing on a path block is not the path: the pieces are the ground's alone.
        applyGroundPieces(terrainShader, GroundPieces.NONE);
        // Props carry no per-vertex ground weights, and OpenGL's constant for a disabled attribute is
        // (0, 0, 0, 1) — that stray 1 would apply the fourth ground material to every rock and wall.
        if (groundWeights == null) {
            StaticMesh.clearGroundWeights();
        } else {
            StaticMesh.setGroundWeights(groundWeights);
        }
        terrainShader.setUniform("view", view);
        terrainShader.setUniform("projection", projection);
        terrainShader.setUniform("viewPos", camera.getPosition());
        applyLighting(terrainShader, lighting);
        terrainShader.setUniform("rimPower", rimPower);
        terrainShader.setUniform("rimThreshold", rimThreshold);

        // One instanced call per distinct mesh instead of one call per prop. Measured before this: 344
        // rock draws against a pool of 8 shared meshes, each carrying its own model and normalMatrix
        // upload. Grouping is a walk over a few hundred instances against hundreds of submissions saved,
        // and it is done here rather than at the call sites so every caller — rocks, buildings, ore —
        // gets it without knowing.
        terrainShader.setUniform("instanced", 1);
        java.util.Map<StaticMesh, java.util.List<StaticInstance>> byMesh = new java.util.IdentityHashMap<>();
        for (StaticInstance instance : instances) {
            byMesh.computeIfAbsent(instance.mesh(), m -> new java.util.ArrayList<>()).add(instance);
        }
        for (java.util.Map.Entry<StaticMesh, java.util.List<StaticInstance>> group : byMesh.entrySet()) {
            java.util.List<StaticInstance> batch = group.getValue();
            for (int from = 0; from < batch.size(); from += propInstances.capacity()) {
                propInstances.begin();
                int to = Math.min(from + propInstances.capacity(), batch.size());
                for (int i = from; i < to; i++) {
                    propInstances.put(batch.get(i).modelMatrix());
                }
                propInstances.bind(group.getKey().vaoHandle());
                group.getKey().drawInstanced(propInstances.count());
                propDrawCalls++;
                propInstances.unbind();
            }
        }
        terrainShader.setUniform("instanced", 0);
        if (twoSided) {
            glEnable(GL_CULL_FACE);
        }
    }

    /**
     * Whether the pictures stood upright — sprites, drawn with {@code occluded} false — write 0 alpha, which the bloom's
     * bright pass reads as nothing to glow off: pixel art keeps its crisp edges and its whites, rather than taking on a
     * halo and washing out under the glow of everything round it. On only while drawing into a picture that blooms; the
     * window's own alpha is left as it was.
     */
    public void setSpritesUnbloomed(boolean unbloomed) {
        spritesUnbloomed = unbloomed;
    }

    /**
     * Draws textured instances — trunks, branches, leaf cards — through the cutout foliage shader.
     *
     * <p>Called once per texture, because a tiling texture cannot live in an atlas: {@code GL_REPEAT}
     * wraps the whole image, so bark that tiles up a trunk cannot be a sub-region of a shared sheet.
     * Trees therefore draw twice, once for bark and once for leaves.
     *
     * <p>A two-sided material (leaf cards have no back) runs with culling off; the previous state is
     * restored before returning, the same contract {@code Skybox} and {@code UIRenderer} follow.
     */
    public void renderFoliage(Camera camera, SceneLighting lighting, Collection<TexturedInstance> instances,
                              FoliageMaterial material) {
        renderFoliage(camera, lighting, instances, material, true);
    }

    /**
     * The same, saying whether screen-space ambient occlusion may shade what is drawn.
     *
     * @param occluded false for a flat picture stood upright — a sprite — which occlusion gathered over a card
     *                 only smudges: it is left as drawn, though it still shades the ground it stands on. Nor does
     *                 bloom soften it, while {@link #setSpritesUnbloomed} says the picture blooms
     */
    public void renderFoliage(Camera camera, SceneLighting lighting, Collection<TexturedInstance> instances,
                              FoliageMaterial material, boolean occluded) {
        if (instances.isEmpty()) {
            return;
        }
        boolean twoSided = material.twoSided();
        glEnable(GL_DEPTH_TEST);
        if (twoSided) {
            glDisable(GL_CULL_FACE);
        } else {
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
        }

        // Every uniform below is one both shaders read, or one the painted leaves' shader lacks and ignores.
        Shader foliageShader = material.anime() ? animeLeafShader : this.foliageShader;
        foliageShader.use();
        foliageShader.setUniform("clipBelowY", clipBelowY);
        // The anime leaves' cards face the camera (foliage.vert); nothing else drawn here does.
        foliageShader.setUniform("billboard", material.anime() ? 1 : 0);
        // Only a two-sided pass is leaves — bark and stumps are drawn with culling on — so the sidedness
        // the material already carries is what says whether this pass should move in the wind. A swaying
        // trunk would be a tree made of rubber.
        foliageShader.setUniform("windVelocity", wind);
        foliageShader.setUniform("time", sceneTime);
        foliageShader.setUniform("leafSway", material.twoSided() ? LEAF_SWAY : 0f);
        foliageShader.setUniform("view", camera.getViewMatrix());
        foliageShader.setUniform("projection", camera.getProjectionMatrix());
        foliageShader.setUniform("viewPos", camera.getPosition());
        applyLighting(foliageShader, lighting);
        foliageShader.setUniform("rimPower", rimPower);
        foliageShader.setUniform("rimThreshold", rimThreshold);
        foliageShader.setUniform("alphaCutoff", material.alphaCutoff());
        foliageShader.setUniform("flipBackFaceNormals", material.flipsBackFaceNormals() ? 1 : 0);
        foliageShader.setUniform("occluded", occluded ? 1 : 0);
        foliageShader.setUniform("unbloomed", !occluded && spritesUnbloomed ? 1 : 0);

        material.albedo().bind(0);
        foliageShader.setUniform("albedoTexture", 0);

        foliageShader.setUniform("useNormalMap", material.normalMap() != null ? 1 : 0);
        foliageShader.setUniform("normalMapMirrored", material.normalMapMirrored() ? 1 : 0);
        if (material.normalMap() != null) {
            material.normalMap().bind(1);
            foliageShader.setUniform("normalTexture", 1);
        }

        // One instanced call per distinct mesh. Measured before this: 408 tree draws against a pool of 14
        // variant meshes, three uniform uploads apiece, 1.19 ms — the largest cost in the frame after the
        // shadow pass. The tint rides in the stream because it is genuinely per instance (the dark forest
        // overlay), which is why this buffer is the tinted one and the props buffer is not.
        foliageShader.setUniform("instanced", 1);
        java.util.Map<TexturedMesh, java.util.List<TexturedInstance>> byMesh = new java.util.IdentityHashMap<>();
        for (TexturedInstance instance : instances) {
            byMesh.computeIfAbsent(instance.mesh(), m -> new java.util.ArrayList<>()).add(instance);
        }
        for (java.util.Map.Entry<TexturedMesh, java.util.List<TexturedInstance>> group : byMesh.entrySet()) {
            java.util.List<TexturedInstance> batch = group.getValue();
            for (int from = 0; from < batch.size(); from += foliageInstances.capacity()) {
                foliageInstances.begin();
                int to = Math.min(from + foliageInstances.capacity(), batch.size());
                for (int i = from; i < to; i++) {
                    foliageInstances.put(batch.get(i).modelMatrix(), batch.get(i).tint());
                }
                foliageInstances.bind(group.getKey().vaoHandle());
                group.getKey().drawInstanced(foliageInstances.count());
                propDrawCalls++;
                foliageInstances.unbind();
            }
        }
        foliageShader.setUniform("instanced", 0);

        if (twoSided) {
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
        }
    }

    /**
     * Draws pictures stood up in the world — sprites — see-through where something stands in front of them, and not at
     * all where nothing does: a character kept in sight behind a building. Drawn after everything solid, testing depth
     * the other way round and writing none, blended in at {@code opacity}.
     */
    public void renderHidden(Camera camera, SceneLighting lighting, Collection<TexturedInstance> instances,
                             FoliageMaterial material, float opacity) {
        if (instances.isEmpty()) {
            return;
        }
        boolean blendWas = glIsEnabled(GL_BLEND);
        glDepthFunc(GL_GREATER);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendColor(0f, 0f, 0f, opacity);
        glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
        // The geometry target keeps what stands in front: what shows through it is no surface of its own.
        glColorMaski(1, false, false, false, false);
        renderFoliage(camera, lighting, instances, material, false);
        glColorMaski(1, true, true, true, true);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (!blendWas) {
            glDisable(GL_BLEND);
        }
        glDepthMask(true);
        glDepthFunc(GL_LESS);
    }

    /**
     * Uploads everything a scene shader shares — camera, lighting, shadow map, rim, wind clock — to a shader the
     * renderer does not own, such as a material compiled from Blender shader nodes, so it is lit exactly as the
     * shaders here are. The shader must be in use.
     */
    public void applySceneUniforms(Shader shader, Camera camera, SceneLighting lighting) {
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("viewPos", camera.getPosition());
        applyLighting(shader, lighting);
        shader.setUniform("rimPower", rimPower);
        shader.setUniform("rimThreshold", rimThreshold);
        shader.setUniform("time", sceneTime);
        shader.setUniform("emissiveScale", propEmissive);
    }

    /**
     * Uploads the frame's lighting to a shader. Every scene shader takes the same set of uniforms —
     * including the shadow lookup, which is why this binds the map here rather than per pass.
     *
     * <p>Setting a uniform a shader does not declare is a silent no-op in GL (the location comes back
     * -1), so the outline pass and anything else that ignores shadows costs nothing for these.
     */
    private void applyLighting(Shader shader, SceneLighting lighting) {
        shader.setUniform("toSun", lighting.toSun());
        shader.setUniform("sunColor", lighting.sunColor());
        shader.setUniform("ambientColor", lighting.ambientColor());
        shader.setUniform("rimColor", lighting.rimColor());
        shader.setUniform("fogColor", lighting.fogColor());
        shader.setUniform("fogStart", lighting.fogStart());
        shader.setUniform("fogEnd", lighting.fogEnd());
        shader.setUniform("fogHorizon", lighting.fogHorizon());
        shader.setUniform("fogHeightFalloff", lighting.fogHeightFalloff());
        shader.setUniform("fogBaseY", lighting.fogBaseY());
        shader.setUniform("saturation", lighting.saturation());

        shadowMap.bind(SHADOW_TEXTURE_UNIT);
        shader.setUniform("shadowMap", SHADOW_TEXTURE_UNIT);
        shader.setUniform("lightSpaceMatrix", shadowMap.lightSpaceMatrix());
        shader.setUniform("shadowTexelUv", shadowMap.texelUvSize());
        shader.setUniform("shadowTexelWorld", shadowMap.texelWorldSize());
        shader.setUniform("shadowBias", shadowMap.depthBias());
        shader.setUniform("shadowStrength", lighting.shadowStrength());
        applyPointLights(shader);
    }

    /** Sends a shader the frame's point lights, unless it has them already. */
    private void applyPointLights(Shader shader) {
        Integer sent = pointLightsSent.get(shader);
        if (sent != null && sent == pointLightVersion) {
            return;
        }
        pointLightsSent.put(shader, pointLightVersion);
        int count = pointLights.count();
        shader.setUniform("pointLightCount", count);
        Vector3f value = new Vector3f();
        for (int i = 0; i < count; i++) {
            shader.setUniform(POINT_LIGHT_POSITION[i], pointLights.position(i, value));
            shader.setUniform(POINT_LIGHT_COLOR[i], value.set(pointLights.color(i, 0), pointLights.color(i, 1),
                    pointLights.color(i, 2)));
            shader.setUniform(POINT_LIGHT_RADIUS[i], pointLights.radius(i));
        }
    }

    /**
     * Draws textured, individually tinted instances of a shared mesh — the path creatures render through.
     *
     * <h2>Why not the foliage path</h2>
     * It is close: same mesh class, same per-instance tint, same cel bands. What it cannot do is
     * <b>blend</b>. Foliage is deliberately an alpha <i>cutout</i> so thousands of leaf cards can stay in
     * the opaque pass with no sorting, and giving it blend state would put that decision — and the
     * back-to-front ordering it implies — into the hot path of every tree in the world. A translucent
     * creature is a handful of draws where a tree canopy is hundreds.
     *
     * @param opacity the material's alpha. At 1 this draws exactly like an opaque pass; below 1 it blends
     *                with <b>depth writes off</b>, so a translucent shell neither occludes what is behind
     *                it nor fights itself where the shape folds over
     */
    public void renderEntities(Camera camera, SceneLighting lighting, Collection<TexturedInstance> instances,
                               Texture albedo, float opacity) {
        if (instances.isEmpty()) {
            return;
        }
        boolean blended = opacity < 0.999f;

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        if (blended) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
        }

        entityShader.use();
        entityShader.setUniform("view", camera.getViewMatrix());
        entityShader.setUniform("projection", camera.getProjectionMatrix());
        entityShader.setUniform("viewPos", camera.getPosition());
        entityShader.setUniform("opacity", opacity);
        applyLighting(entityShader, lighting);
        entityShader.setUniform("rimPower", rimPower);
        entityShader.setUniform("rimThreshold", rimThreshold);

        albedo.bind(0);
        entityShader.setUniform("albedoTexture", 0);

        for (TexturedInstance instance : instances) {
            entityShader.setUniform("model", instance.modelMatrix());
            entityShader.setUniform("normalMatrix", instance.modelMatrix().normal(scratchNormalMatrix));
            entityShader.setUniform("instanceTint", instance.tint());
            instance.mesh().draw();
        }

        if (blended) {
            glDepthMask(true);
            glDisable(GL_BLEND);
        }
    }

    /** Zeroes the submission counter. Call at the top of a frame. */
    public void resetDrawStats() {
        propDrawCalls = 0;
    }

    /** GL submissions the prop and foliage passes have made since the last reset. */
    public int propDrawCalls() {
        return propDrawCalls;
    }

    public void delete() {
        propInstances.delete();
        foliageInstances.delete();
        outlineShader.delete();
        characterShader.delete();
        rimDepthShader.delete();
        if (rimFramebuffer != 0) {
            glDeleteFramebuffers(rimFramebuffer);
        }
        if (rimDepthTexture != 0) {
            glDeleteTextures(rimDepthTexture);
        }
        terrainShader.delete();
        foliageShader.delete();
        animeLeafShader.delete();
        entityShader.delete();
        shadowMap.delete();
        glDeleteBuffers(boneMatrixUbo);
    }
}
