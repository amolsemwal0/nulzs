#version 330 core

// Per-vertex: the shared tuft mesh, in its own local space with the roots at y = 0.
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;

// Per-instance (divisor 1). One tuft is nine floats, which is the whole reason a grass field is
// affordable: the geometry is uploaded once and the field is a stream of placements over it.
layout(location = 3) in vec4 inPlacement;   // xyz = where the roots sit, w = scale
layout(location = 4) in vec4 inVariation;   // xy = (cos, sin) of the yaw, z = shade, w = sway phase
// How far this tuft has arrived, 0 (not yet) to 1 (fully). Multiplied into the texture's alpha in the
// fragment stage — see grass.frag, which screen-doors the result rather than blending it.
layout(location = 5) in float inAppear;   // how far out this tuft starts being drawn
// The colour the terrain under this tuft is painted, straight out of the same multi-biome blend the
// ground itself is coloured by. The roots are drawn in it, so a blade rises out of the ground rather
// than standing on it — see grass.frag.
layout(location = 6) in vec3 inGroundColor;
// +1 or -1: whether this tuft is reflected about its own axis before it is yawed.
//
// The clump the field is built from LEANS — measured, its blades sweep 24% of its height to one side —
// and the yaw is deliberately held within ten degrees of the authored facing. Those two together comb
// the whole field one way, and no amount of randomness inside ten degrees fixes it, because the range
// is smaller than the lean. A reflection is not a rotation: the tuft still faces within ten degrees of
// where the model faces, but its lean points the other way, so half the field cancels the other half.
layout(location = 7) in vec2 inMirrorVariant;  // x = +1/-1 reflection, y = which colour variant

uniform mat4 view;
uniform mat4 projection;

// The scene clock. Fed the paused-aware delta, so the wind stops with everything else.
uniform float time;
// The world's wind, in world units per second — the same velocity the clouds drift in and the water
// ripples along. Direction and rate both come from it, so grass cannot end up leaning one way while the
// sky moves another, or rippling at a rate the air is not going.
uniform vec2 windVelocity;
uniform float swayStrength;

/**
 * Gusts: broad waves of wind rolling across a field in the wind's direction, the way a painted (anime) meadow shows
 * the wind — each crest bows the grass further and lights its tips as it passes. 0 strength leaves them out (the
 * default; Theta has none). Spacing is metres from one crest to the next, speed metres a second.
 */
uniform float gustStrength;
uniform float gustSpacing;
uniform float gustSpeed;
out float gustLight;

/**
 * Sway cycles per second at one world unit per second of wind.
 *
 * The conversion from a speed to a rate: it is what makes the same wind stir short grass visibly while a
 * cloud deck hundreds of units up merely creeps. Set so the old hand-tuned 1.9 rad/s comes back at the
 * wind's own speed, which keeps the grass looking as it did while making it follow the wind from now on.
 */
const float SWAY_RATE_PER_SPEED = 0.317;

// The height the tuft mesh was authored at, used to work out how far up a blade a vertex sits.
uniform float tuftHeight;

// Billboard mode. The reduced-detail far field is a camera-facing quad carrying a sprite baked from
// this same clump, so it shares every other rule here — sway, scale, the shading ramp — and differs
// only in how the local vertex is oriented.
uniform int billboard;

// --- The density and arrival curve ---------------------------------------------------------------
//
// What the CPU used to evaluate per tuft per frame. Every one of these is handed over by the game layer
// (see GrassCurve) rather than written here, so there is a single statement of the curve and nothing to
// keep in step. fieldCentre is the PLAYER, not the camera: a third-person camera orbits the body by
// several units, and measuring from it would swing every tuft's size and arrival as the player turned
// on the spot.
uniform vec3 fieldCentre;
uniform float fullDensityDistance;
uniform float settleBlend;
uniform float farDensity;
uniform float maxDistance;
uniform float maxThinningSpread;
uniform float fadeFullSizeDistance;
uniform float minAppearFade;
uniform float appearFade;
uniform float dissolveShare;
// 0 draws the settled field opaque, 1 draws the tufts still fading in. Each pass collapses the other's
// instances to a degenerate triangle: an arriving tuft writes no depth, so it must come after every
// opaque thing that can occlude it — including the settled grass.
uniform int arrivingPass;
// The camera's horizontal right vector. Y is deliberately excluded: a grass billboard must turn about
// its own stem and never tilt, or looking down at a field lays every blade over on its back.
uniform vec3 cameraRight;

out vec3 worldPos;
out vec3 worldNormal;
out vec2 uv;
out float shade;
// How far up the blade this vertex sits, 0 at the roots and 1 at the tips. Interpolated, because the
// root-to-tip gradient in grass.frag is a function of it and has to vary *along* a blade — this is the
// one quantity here that must not be flat.
out float heightFrac;
// Flat: one tuft stands on one patch of ground, so its whole clump shares a root colour. Interpolating
// it would mean the near and far sides of a blade blended toward different greens.
flat out vec3 groundColor;
// Flat rather than interpolated: it is one value for the whole instance, and letting it vary across a
// blade would dissolve a tuft from one corner instead of evenly.
flat out float fade;
flat out float variant;

/**
 * How far the thinning has grown a tuft at this distance.
 *
 * Coverage is count times width squared and the density is exactly (F/r)^2 past the core, so growing a
 * survivor by 1/sqrt(density) holds ground cover exactly constant while the instance count still falls
 * as the square. Not a fitted curve — an identity, which is why it is worth reproducing here rather
 * than approximating.
 *
 * The smoothstep is written out rather than called: GLSL's smoothstep is UNDEFINED when edge0 >= edge1,
 * this codebase has already drawn a starfield as grid-aligned squares by relying on the reversed form,
 * and here the edges are uniforms that a retune could in principle cross.
 */
float spreadAt(float range) {
    float density;
    if (range <= fullDensityDistance) {
        density = 1.0;
    } else if (range >= maxDistance) {
        // Past the edge nothing is drawn at all; answering with the cap keeps the function total and
        // matches the largest tuft the culling bounds are sized for.
        return maxThinningSpread;
    } else {
        float ratio = fullDensityDistance / range;
        float thinned = ratio * ratio;
        float blendEnd = fullDensityDistance * settleBlend;
        if (range < blendEnd) {
            // Smoothstepped onto the inverse square rather than starting from it. Both factors vanish at
            // the core edge — the weight is 0 there and so is (thinned - 1) — which is what makes the
            // join flat rather than merely continuous, and so what stops tufts arriving in a ring at the
            // edge of the stable core.
            float t = clamp((range - fullDensityDistance) / (blendEnd - fullDensityDistance), 0.0, 1.0);
            float w = t * t * (3.0 - 2.0 * t);
            thinned = 1.0 + w * (thinned - 1.0);
        }
        density = max(farDensity, thinned);
    }
    return clamp(inversesqrt(density), 1.0, maxThinningSpread);
}

void main() {
    // Everything below used to arrive on the instance, recomputed on the CPU and re-uploaded every
    // frame. It is derived here from the appearance distance instead, which is a property of the spot
    // and so can sit in a buffer that is written once — see GrassRenderer.PatchBuffer.
    vec2 toCentre = inPlacement.xz - fieldCentre.xz;
    // Named `range`, not `distance`: that is a GLSL built-in, and shadowing one is legal right up
    // until somebody wants to call it.
    float range = length(toCentre);

    // The band this tuft arrives over. It has to leave the tuft room to finish: an appearance distance
    // is never less than fullDensityDistance, so a wider band could never be crossed and the tuft would
    // stay part-grown however close the player walked.
    float band = clamp(inAppear - fadeFullSizeDistance, minAppearFade, appearFade);
    // t runs 0 at the tuft's appearance distance — its furthest, smallest-on-screen moment — to 1 up
    // close, compressed into the first dissolveShare of the band so everything nearer is exactly opaque.
    float t = clamp((inAppear - range) / (band * dissolveShare), 0.0, 1.0);
    float arrival = t * t * (3.0 - 2.0 * t);

    // Drawn at all? Thinned out by distance, past the edge of the field, or belonging to the other pass.
    // Collapsed to a point behind the near plane rather than discarded, which a vertex shader cannot do:
    // the whole triangle is clipped, and the rejection happens before any of the sway, shading or
    // transform work below. Compared against 1 exactly, which is safe because the clamp above produces
    // exactly 1 and its smoothstep of exactly 1 is exactly 1 — no tuft falls between the two passes.
    bool drawn = range < inAppear && range < maxDistance;
    bool arriving = arrival < 1.0;
    if (!drawn || (arriving != (arrivingPass == 1))) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        gustLight = 0.0;
        return;
    }

    float mirror = inMirrorVariant.x;
    // The thinning grows what survives it, so the field keeps its coverage instead of visibly emptying
    // out into a ring that travels with the player.
    float instanceScale = inPlacement.w * spreadAt(range);

    // Reflected about the clump's own XY plane before anything else, so the yaw below turns the mirrored
    // clump rather than the mirror turning with the world. Culling is already off for this pass (a blade
    // is a single-sided strip), so the flipped winding costs nothing and needs no second draw state.
    vec3 local = vec3(inPosition.x, inPosition.y, inPosition.z * mirror);
    vec3 scaled = local * instanceScale;

    // Yaw about the tuft's own axis, written out rather than built as a matrix: the instance carries the
    // cosine and sine already, so the alternative is reconstructing a mat3 per vertex from an angle.
    float c = inVariation.x;
    float s = inVariation.y;
    vec3 rotated;
    if (billboard == 1) {
        // The quad is authored in local XY; its width runs along the camera's right vector so it always
        // presents its face, and its height stays world-up.
        rotated = cameraRight * scaled.x + vec3(0.0, scaled.y, 0.0);
    } else {
        rotated = vec3(scaled.x * c + scaled.z * s, scaled.y, -scaled.x * s + scaled.z * c);
    }
    vec3 world = inPlacement.xyz + rotated;

    // Wind. The displacement is weighted by how far up the tuft a vertex sits, squared, so the roots are
    // pinned to the soil and only the tips lean — a uniform offset would slide the whole clump across the
    // ground. Phase varies per instance *and* with world position, so a field ripples rather than every
    // blade in it leaning in unison.
    //
    // The lean is scaled by the tuft's own height, which is the part that is easy to get wrong and was:
    // a displacement in plain world units is a fraction of a metre, and this asset is 12 cm tall, so it
    // laid every blade flat on the ground several times its own length. swayStrength is therefore a
    // fraction of the tuft's height, which also keeps it correct at any scale.
    float heightFraction = clamp(local.y / tuftHeight, 0.0, 1.0);
    float windSpeed = length(windVelocity);
    vec2 windDirection = windSpeed > 0.0001 ? windVelocity / windSpeed : vec2(1.0, 0.0);
    float phase = time * windSpeed * SWAY_RATE_PER_SPEED
                + inVariation.w + world.x * 0.32 + world.z * 0.27;
    float lean = swayStrength * heightFraction * heightFraction
               * (sin(phase) + 0.35 * sin(phase * 2.3 + 1.7));
    float crest = 0.0;
    if (gustStrength > 0.0) {
        // Measured at the roots, so a tuft bows as one; the crest lines wander so they don't march in rulered rows.
        vec2 across = vec2(-windDirection.y, windDirection.x);
        float side = dot(inPlacement.xz, across);
        float along = dot(inPlacement.xz, windDirection) + 3.5 * sin(side * 0.043) + 1.8 * sin(side * 0.11 + 1.3);
        float wave = (along - time * gustSpeed) / max(gustSpacing, 0.1);
        // pow of a base a rounding error below zero is undefined (NaN on some drivers), hence the max.
        crest = pow(max(0.0, 0.5 + 0.5 * sin(wave * 6.2831853)), 6.0) * gustStrength;
        lean += crest * 0.6 * heightFraction * heightFraction;
    }
    gustLight = crest;
    world.xz += windDirection * lean * tuftHeight * instanceScale;

    worldPos = world;
    // A billboard has no meaningful surface of its own, so it is shaded as though facing up — which is
    // what the 3D clump's normals are biased to anyway, so the two light the same either side of the
    // swap. Getting this wrong is how a LOD change announces itself as a brightness step.
    // The normal is mirrored with the geometry, or half the field is lit as though its blades faced the
    // other way — which on an up-biased normal is subtle enough to miss and wrong everywhere at once.
    vec3 localNormal = vec3(inNormal.x, inNormal.y, inNormal.z * mirror);
    worldNormal = billboard == 1
            ? vec3(0.0, 1.0, 0.0)
            : normalize(vec3(localNormal.x * c + localNormal.z * s,
                             localNormal.y,
                             -localNormal.x * s + localNormal.z * c));
    uv = inUv;

    // Darker at the roots, full brightness at the tips: the fake ambient occlusion that stops a field
    // reading as a carpet of evenly-lit spikes. Multiplied by the instance's own shade variation so no
    // two neighbouring tufts are exactly the same green. Squared, so the darkening stays concentrated
    // down where blades overlap and shade each other rather than washing up the whole blade.
    //
    // GENTLE AGAIN (0.75, not 0.45), and the history is the reason. This ramp was steepened to 0.45 when
    // the blade colour became a single flat green — with nothing else varying up a blade, the ramp *was*
    // the root-to-tip gradient, and a gentle one left a clump reading as one solid green shape. grass.frag
    // now carries a real three-stop colour gradient (see GrassVariant), so a steep ramp is the same job
    // done twice and the field comes out darker than the ground it stands in: measured at 0.45, a band of
    // tuft mass rendered at luminance 90.7 against open ground at 105.7, a 14% deficit — even though the
    // gradient's middle stop is BRIGHTER than the terrain green. At 0.75 it is what its name says, a
    // contact darkening between crowded blades, and the colour depth comes from the colour.
    float root = heightFraction * heightFraction;
    shade = inVariation.z * mix(0.75, 1.0, root);
    fade = arrival;
    variant = inMirrorVariant.y;
    heightFrac = heightFraction;
    groundColor = inGroundColor;

    gl_Position = projection * view * vec4(world, 1.0);
}
