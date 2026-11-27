package org.zeronura.engine.render;

/**
 * The knobs {@link GlobalIllumination} reads.
 *
 * <p>An engine-side value type, a dumb carrier of numbers, exactly as {@link PostSettings} is to the post
 * chain and {@link SceneLighting} to the scene shaders. Which numbers is art direction and belongs to the
 * game layer; how they are spent is this package's business.
 *
 * @param radius         how far, in world units, a surface gathers occlusion and bounced light from. This is
 *                       the one constant that decides what the effect <i>is</i>: small values darken the
 *                       creases where two surfaces meet, large ones shade whole hollows and read as sky
 *                       occlusion. A metre or so sits between the two
 * @param bias           how far in front of a sample point an occluder must be before it counts, in world
 *                       units. Guards against a surface shadowing itself, which is the same problem the
 *                       shadow map's depth bias solves and fails the same way — too little and flat ground
 *                       self-occludes into a grey haze, too much and contact darkening detaches from the
 *                       contact
 * @param occlusion      how deeply an occluded fragment loses its fill light — an <b>exponent</b> on the
 *                       gathered visibility rather than a fraction, so 0 is off, 1 is the gather taken at
 *                       face value, and above that deepens it without ever being able to push a pixel past
 *                       black. See {@code gi_apply.frag} for why the obvious lerp cannot do the job. Never
 *                       reaches the key light either way: a sunlit surface's direct light is the shadow
 *                       map's business, and taking it away here would darken a scene twice for one reason
 * @param bounce         how strongly nearby surfaces tint this one with the light they reflect, 0 disabling
 *                       colour bleed and leaving plain ambient occlusion
 * @param fillFloor      the least fill a fragment is treated as having, whatever its shading says. 0 is the
 *                       physical answer — a surface in full sun has no ambient to occlude — and it also
 *                       means a bright hillside gets no contact darkening at all, which reads as the effect
 *                       switching itself off in daylight. A small floor keeps the creases legible
 * @param range          how far from the camera the effect is worth computing, in world units. Past it the
 *                       radius covers well under a pixel and every sample lands in the same texel, so the
 *                       pass would pay full price to return exactly nothing
 * @param samples        taps per pixel. Cost is very nearly linear in this, and it trades against noise,
 *                       which the blur then trades against detail
 */
public record GiSettings(
        float radius,
        float bias,
        float occlusion,
        float bounce,
        float fillFloor,
        float range,
        int samples) {

    /** The most taps {@code gi.frag} declares room for. Raising it means editing the shader's array too. */
    public static final int MAX_SAMPLES = 32;

    /** Switched off entirely — the "no GI pass ran" state, and what a renderer without one behaves as. */
    public static final GiSettings OFF = new GiSettings(1.0f, 0.02f, 0f, 0f, 0.25f, 120f, 16);

    /**
     * The tuned default: contact darkening in the creases plus a gentle colour bleed.
     *
     * <p>Deliberately restrained. This renderer quantises diffuse into cel bands, so anything the ambient
     * term does arrives as a shift in which band a fragment lands in — a strong occlusion term stops reading
     * as shading and starts reading as a second, dirtier set of shadows fighting the painted ones.
     */
    public static GiSettings tuned() {
        return new GiSettings(2.0f, 0.035f, 4.0f, 6.0f, 0.4f, 140f, 20);
    }

    public GiSettings {
        samples = Math.max(1, Math.min(MAX_SAMPLES, samples));
    }

    /** Whether this would change any pixel at all — false lets the caller skip the whole pass. */
    public boolean isActive() {
        return occlusion > 0f || bounce > 0f;
    }

    /** The same settings with occlusion and bounce scaled, for a quality setting or a preview's A/B. */
    public GiSettings scaled(float strength) {
        return new GiSettings(radius, bias, occlusion * strength, bounce * strength,
                fillFloor, range, samples);
    }
}
