package org.zeronura.nulzs.render;

import org.joml.Vector3f;

/**
 * How a character is cel shaded — the parameters of {@code character.frag}, which is the character's own
 * shader and is shared with nothing.
 *
 * <h2>Why the character has its own look at all</h2>
 * Terrain and foliage want a broad soft falloff over kilometres; a figure wants a small number of flat
 * tones with a drawn line between them. They ran on one set of numbers, and the result was reported from
 * play as the characters being <b>like a glass model</b>. Every value here was arrived at by rendering
 * the term it controls on its own — see {@code CharacterPreview}'s {@code shine_*} set.
 *
 * <h2>What was measured, and what it changed</h2>
 * The shader this replaced <i>did</i> quantise its diffuse, which is why the fault was hard to name: the
 * bands were real and hard-edged. What was wrong is <b>where they fell</b>. It thresholded raw
 * {@code N·L}, so with a key light anywhere near the camera — which a portrait light is — nearly every
 * fragment of the torso sat in the top band and the body came out one flat tone with a thin dark rind
 * round it. Rendering the band value on its own is what showed it; the lit frame just looked smooth.
 *
 * <p>So the light term is <b>half-lambert</b> now, {@code N·L * 0.5 + 0.5}, and {@link #rampStart} /
 * {@link #rampEnd} place the boundaries across the middle of that range. That is the whole of why the
 * figure has shape in it.
 *
 * @param celBands          how many flat tones. Three is the anime default: lit, shadow, and a core
 *                          shadow that gives the form weight. Two is flatter and more graphic; one is
 *                          unshaded.
 * @param rampStart         where the first tone boundary may fall, in half-lambert. <b>This and
 *                          {@link #rampEnd} decide whether the shading describes the form at all</b> —
 *                          push them high and the body is uniformly lit, low and it is uniformly dark.
 * @param rampEnd           where the last one may fall.
 * @param shadowTint        a multiplier over the shadow tone. Cool and slightly violet, which is what
 *                          painted shadow on skin is; it must stay near 1 in value, since the darkening
 *                          is {@link #shadowLift}'s job and doing it twice makes mud.
 * @param shadowSaturation  how much the albedo's saturation is pushed <b>before</b> it meets the blue
 *                          fill. Above 1 on purpose: multiplying a colour by a blue ambient drags it
 *                          toward grey-blue, and a grey-blue shadow on skin is the single thing that
 *                          reads as a 3D render rather than as drawn art. This is the counterweight.
 * @param shadowLift        how bright the shadow tone sits. Anime shadow is not dark — it is a different
 *                          colour at a similar value — so this is above 1 against a fill that is already
 *                          bright.
 * @param terminatorStrength the thin warm line along the <b>last</b> tone boundary — where the lit tone
 *                          begins — which is the drawn edge. It replaced a broad ramp covering a third
 *                          of the lit hemisphere, which was most of why the old character read as a
 *                          smooth gradient. <b>How far the tone is tinted toward
 *                          {@code terminatorColor}, not how much light is added</b>: added light there
 *                          is what produced the bright white line reported from play, since most of the
 *                          line lies on the lit tone and so could only invent a brighter one. Because it
 *                          is a tint, this now buys <i>warmth</i> rather than brightness — measured
 *                          across 0.30/0.55/0.80 the band's overshoot above the lit tone stays at +1 of
 *                          255 while its R−B spread goes 12/28/39 — so it cannot be turned up into a
 *                          glare. Past about 0.8 it stops reading as an edge and starts reading as a
 *                          third coloured band.
 * @param rimStrength       the light line along the silhouette. Gated to the lit side in the shader — an
 *                          ungated rim wraps the whole outline evenly, which is exactly how a polished
 *                          or translucent object reads.
 */
public record CharacterSurface(int celBands, float rampStart, float rampEnd, Vector3f shadowTint,
                               float shadowSaturation, float shadowLift, float terminatorStrength,
                               float rimStrength) {

    /**
     * The look as it ships.
     *
     * <p>Three tones over a half-lambert range of 0.40 to 0.78 — so the boundaries land across the body
     * rather than at its edge — a shadow lifted well clear of black and pushed a third more saturated
     * than the albedo, a narrow warm line at the terminator, and a rim at a fraction of the old one.
     *
     * <p><b>{@link #terminatorStrength} ships at 0 — the drawn edge is off.</b> Not because it does not
     * work; it was fixed twice over (a tint rather than added light, then a hard-edged band rather than a
     * ramp) and both fixes stand. It is off because on a three-tone ramp it is a <i>fourth</i> band, and
     * asked for directly: what was wanted on skin is skin tone, partial shadow, shadow, with the
     * boundaries between them being plain steps rather than coloured stripes. An accent line is the one
     * thing that turns a boundary into a stripe.
     *
     * <p>So it is one constant away rather than gone, and the machinery is in working order for whoever
     * wants it: 0.30 reads as a warm edge, 0.55 as a painted terminator accent, 0.80 as a third colour.
     * Judge it on the <b>face</b> and not on the shirt — the same strength is far more conspicuous on a
     * saturated albedo, which is what made it read as weird on skin while looking fine on cloth.
     *
     * <p>How <b>deep</b> the darkest tone goes is not set here at all — it is {@code SHADOW_FLOOR} in
     * {@code character.frag}, which bounds the shadow's distance below the lit tone whatever the scene's
     * ambient is doing. That had to move into the shader because it is a statement about a
     * <i>relationship between two tones</i> rather than about the surface, and because leaving it to the
     * ambient meant a night shadow collapsing to near-black: {@code AMBIENT_NIGHT} has a luminance of
     * 0.15 against high sun's 0.54, so the same character lost most of its shadow detail after dusk.
     *
     * <p>The one number worth knowing is {@link #shadowSaturation}. At 1.0 the shadow side of skin comes
     * out the flat grey-beige the previous shader produced; at 1.7 it comes out as a deeper, warmer
     * version of the skin itself — dusty rose on the palest tone, rich brown on the darkest. Nothing else
     * here moves the look as far, and <b>it must be judged on bare skin</b>: on the player model's white
     * shirt it does almost nothing, because a colour with no saturation has none to boost.
     */
    public static final CharacterSurface DEFAULT = new CharacterSurface(
            3, 0.40f, 0.78f, new Vector3f(0.97f, 0.90f, 1.00f), 1.70f, 1.30f, 0.0f, 0.45f);

    /**
     * The look before the character had a shader of its own, as far as this type can express it.
     *
     * <p>It cannot express it exactly — the old one thresholded raw {@code N·L} rather than half-lambert,
     * which is the very thing that changed — so this is the nearest equivalent: the same shading spread
     * over the top of the range, where raw {@code N·L} put it, with a grey shadow and the old broad
     * terminator and rim. It exists so the preview can shoot the A/B, and its whole point is to look
     * wrong.
     *
     * <p>It is a step further from the original since the drawn edge became a tint: this cannot reproduce
     * the <i>additive</i> terminator at all, and its 1.20 is clamped to a full mix by the shader. So the
     * one thing this preset no longer shows is the bright white line — which is fine, because that is a
     * defect rather than part of the look it stands for, and {@code shine_no_terminator} is the honest
     * comparison for the edge itself.
     */
    public static final CharacterSurface GLOSSY = new CharacterSurface(
            3, 0.50f, 0.98f, new Vector3f(1f, 1f, 1f), 1.0f, 1.0f, 1.20f, 1.80f);

    /** This surface with no rim, for attributing which term a render is showing. */
    public CharacterSurface withoutRim() {
        return new CharacterSurface(celBands, rampStart, rampEnd, shadowTint, shadowSaturation,
                shadowLift, terminatorStrength, 0f);
    }

    /** Likewise with no drawn edge at the terminator. */
    public CharacterSurface withoutTerminator() {
        return new CharacterSurface(celBands, rampStart, rampEnd, shadowTint, shadowSaturation,
                shadowLift, 0f, rimStrength);
    }

    /**
     * Likewise with the shadow left unsaturated and unlifted — the grey-blue wash a plain multiply by the
     * ambient gives, which is what this is here to be compared against.
     */
    public CharacterSurface withPlainShadow() {
        return new CharacterSurface(celBands, rampStart, rampEnd, new Vector3f(1f, 1f, 1f), 1f, 1f,
                terminatorStrength, rimStrength);
    }

    /** Likewise with the tones collapsed, so the ramp's contribution can be seen on its own. */
    public CharacterSurface withBands(int bands) {
        return new CharacterSurface(bands, rampStart, rampEnd, shadowTint, shadowSaturation, shadowLift,
                terminatorStrength, rimStrength);
    }
}
