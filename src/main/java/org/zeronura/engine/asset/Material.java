package org.zeronura.engine.asset;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.engine.render.Texture;

/**
 * One of a model's materials: what a mesh is drawn with.
 *
 * <h2>The name is carried, because it is the only thing that says what a surface <i>is</i></h2>
 * A glTF material has a name and nothing else that distinguishes skin from cloth from hair — no tag, no
 * slot, no convention the format itself understands. Both character models here name theirs
 * meaningfully ({@code SkinShader} on the base mesh, {@code N00_000_00_Body_00_SKIN} on the player), so
 * the name is what a caller matches on. It was being read and thrown away before.
 *
 * <h2>The tint is set by the game, never derived here</h2>
 * {@link #setTint} exists so the character's chosen skin colour can reach the renderer without the
 * engine ever learning what "skin" means: the game walks a model's materials, decides which ones are
 * skin by their names, and tints those. The renderer multiplies and asks nothing. That is the same split
 * {@code TexturedInstance} already makes for the dark forest's trees — the tint travels with the thing
 * being drawn, and the rule that chose it stays in the layer that owns the rule.
 *
 * <p>White is "leave it alone", so a material nobody has an opinion about renders exactly as it did
 * before this field existed.
 */
public class Material {

    private final String name;
    private final Vector4f baseColor;
    private final Texture baseColorTexture;
    private final Vector3f tint = new Vector3f(1f, 1f, 1f);
    private float glossStrength;
    private float glossShininess = DEFAULT_GLOSS_SHININESS;

    // --- Anime material maps ------------------------------------------------------------------------
    // All optional and all null until the game hands one over, the same way gloss and tint are: a model
    // nobody has painted maps for renders exactly as it did before these fields existed. What each map
    // holds is written on its setter; how to paint one is in docs/anime-character-shading-2026-11-08.md.
    private Texture lightMap;
    private Texture shadowRamp;
    private float shadowRampRow = 0.5f;
    private boolean face;
    private Texture faceShadowMap;
    private float faceMirrorU = 0.5f;
    private Texture matcap;
    private float matcapStrength;
    private float outlineScale = 1f;

    /** The tightness a highlight gets when something turns one on without saying how tight. */
    public static final float DEFAULT_GLOSS_SHININESS = 40f;

    public Material(String name, Vector4f baseColor, Texture baseColorTexture) {
        this.name = name == null ? "" : name;
        this.baseColor = baseColor;
        this.baseColorTexture = baseColorTexture;
    }

    /** The material's own name, exactly as the file carries it. Never null; empty when unnamed. */
    public String getName() {
        return name;
    }

    public Vector4f getBaseColor() {
        return baseColor;
    }

    public Texture getBaseColorTexture() {
        return baseColorTexture;
    }

    /**
     * How glossy this surface is, and how tight its highlight — <b>zero, until somebody says otherwise</b>.
     *
     * <p>Per material rather than per character, because one global glint is exactly what cannot serve a
     * whole figure: hair wants a bright shaped band and skin wants none at all, and setting it anywhere
     * between the two is what made every character look moulded in plastic. Off by default so a model
     * nobody has an opinion about is matt, which is the safe answer — a missing highlight reads as a
     * stylistic choice where a wrong one reads as a rendering fault.
     *
     * <p>Set by the game from the material's name ({@code CharacterMaterials}), the same way the skin
     * tint is, and for the same reason: the engine draws what it is given and never learns what hair is.
     */
    public float getGlossStrength() {
        return glossStrength;
    }

    public float getGlossShininess() {
        return glossShininess;
    }

    public void setGloss(float strength, float shininess) {
        this.glossStrength = Math.max(0f, strength);
        this.glossShininess = Math.max(1f, shininess);
    }

    public void clearGloss() {
        this.glossStrength = 0f;
        this.glossShininess = DEFAULT_GLOSS_SHININESS;
    }

    /**
     * A four-channel <b>data</b> map painted over the material's own UVs — not a colour, and it must not be
     * colour-corrected on export:
     * <ul>
     *   <li><b>R</b> — specular mask: where the stylised highlight (and the matcap) may appear, and how
     *       strongly. Black for skin and cloth, white on a hair band or a buckle.</li>
     *   <li><b>G</b> — shadow bias. <b>0.5 is neutral.</b> Darker pre-shadows a texel: the crease of an
     *       elbow, under the chin, inside a collar, the folds of a skirt. Black is shadow at any light
     *       angle; white is lit at any light angle.</li>
     *   <li><b>B</b> — highlight size, from a pinpoint at black to a broad band at white.</li>
     *   <li><b>A</b> — which row of the {@linkplain #setShadowRamp shadow ramp} this texel takes its
     *       shadow colours from: row {@code k} of {@code N} is alpha {@code (k + 0.5) / N}.</li>
     * </ul>
     * The highlight still needs {@link #setGloss} to turn it on; this map only says where and how big.
     */
    public void setLightMap(Texture lightMap) {
        this.lightMap = lightMap;
    }

    public Texture getLightMap() {
        return lightMap;
    }

    /**
     * Paints the shadow's <b>colour</b> instead of computing it — a horizontal gradient where u is how lit
     * (0 the deepest shadow, 1 full light) and the colour there multiplies the lit tone. White at the right
     * end leaves the lit tone alone; a hard step at the middle is a two-tone cel ramp; a warm stripe just
     * left of it is a painted terminator.
     *
     * <p>Several rows can share one texture (skin, cloth, metal, hair…) and the {@linkplain #setLightMap
     * light map's} alpha picks a row per texel. Without a light map, {@code row} picks it for the whole
     * material, as a v coordinate: 0.5 for a single-row ramp.
     *
     * <p>Load it with {@code GL_CLAMP_TO_EDGE}, or full light wraps round and samples the shadow end.
     */
    public void setShadowRamp(Texture ramp, float row) {
        this.shadowRamp = ramp;
        this.shadowRampRow = row;
    }

    public Texture getShadowRamp() {
        return shadowRamp;
    }

    public float getShadowRampRow() {
        return shadowRampRow;
    }

    /**
     * Marks this material as a <b>face</b>, which stops it being shaded by its own normals.
     *
     * <p>A face's normals are modelled for the nose, the eye sockets and the lips, and lit through a cel
     * ramp every one of those becomes a hard-edged blotch — the single most "3D render" thing an anime
     * character can do. Two replacements, depending on what has been painted:
     * <ul>
     *   <li><b>No map</b> ({@code sdf} null) — the face is lit as the sphere of the head, around a point the
     *       renderer finds from the head bone. Smooth, round, one clean terminator. Needs nothing.</li>
     *   <li><b>A face shadow map</b> — a greyscale threshold map over the face's UVs: each texel holds the
     *       light angle at which it goes into shadow, from 0 (dark as soon as the light leaves the front)
     *       to 1 (lit until the light is directly behind), painted for light from the character's
     *       <b>left</b>. Light from the right reads it mirrored about {@code mirrorU}, the u coordinate of
     *       the face's centre line — 0.5 when the face has the texture to itself. Nobody paints this
     *       directly: paint shadow masks at a handful of angles and bake them with
     *       {@code tools/face_shadow_map.py}.</li>
     * </ul>
     * Either way the renderer needs to find the head: see {@code FaceRig}.
     */
    public void setFace(Texture sdf, float mirrorU) {
        this.face = true;
        this.faceShadowMap = sdf;
        this.faceMirrorU = mirrorU;
    }

    public void clearFace() {
        this.face = false;
        this.faceShadowMap = null;
        this.faceMirrorU = 0.5f;
    }

    public boolean isFace() {
        return face;
    }

    public Texture getFaceShadowMap() {
        return faceShadowMap;
    }

    public float getFaceMirrorU() {
        return faceMirrorU;
    }

    /**
     * What a metal surface reflects, painted onto a sphere — a "matcap", looked up by the view-space
     * normal, so a buckle or a sword guard catches a sharp painted reflection without the scene having
     * anything to reflect. Mid-grey in the matcap is neutral; darker darkens, lighter lifts. Masked by the
     * {@linkplain #setLightMap light map's} red channel when there is one, so a metal trim on cloth can
     * share the cloth's material.
     */
    public void setMatcap(Texture matcap, float strength) {
        this.matcap = matcap;
        this.matcapStrength = Math.max(0f, strength);
    }

    public Texture getMatcap() {
        return matcap;
    }

    public float getMatcapStrength() {
        return matcapStrength;
    }

    /**
     * How thick this material's outline is, against the character's own — 1 by default. <b>0 for eyes,
     * eyelashes, brows, mouth interiors and eye highlights</b>: an ink line round an iris or a highlight
     * decal is a hull poking out from behind the face, and is the most common thing that makes an
     * imported anime model look broken. Around 0.5 for a face, which wants a finer line than clothing.
     */
    public void setOutlineScale(float scale) {
        this.outlineScale = Math.max(0f, scale);
    }

    public float getOutlineScale() {
        return outlineScale;
    }

    /** A multiplier over this material's colour, white until somebody sets one. */
    public Vector3f getTint() {
        return tint;
    }

    public void setTint(float r, float g, float b) {
        tint.set(r, g, b);
    }

    public void clearTint() {
        tint.set(1f, 1f, 1f);
    }

    /**
     * The base colour with the tint applied, which is what the shader wants.
     *
     * <p>Alpha is untouched: a tint answers what colour a surface is, and whether it is see-through is a
     * property of the material rather than of anything the game is choosing.
     */
    public Vector4f tintedBaseColor(Vector4f dst) {
        return dst.set(baseColor.x * tint.x, baseColor.y * tint.y, baseColor.z * tint.z, baseColor.w);
    }
}
