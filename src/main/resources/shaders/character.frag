#version 330 core

// The character's own cel shader.
//
// Split off from the world's shading rather than sharing it, because a figure and a landscape want
// opposite things from the same terms: terrain wants a broad soft sheen and a gentle falloff over
// kilometres, and a character wants a small number of flat tones with a drawn edge between them. Sharing
// one set of numbers is what produced the look this replaced — reported from play as the characters
// being "like a glass model".
//
// Four decisions carry the whole appearance, and each one was arrived at by rendering the term on its own:
//
//   1. The light term is HALF-LAMBERT, not raw N.L. Raw N.L over a thresholded ramp puts the shading
//      boundary at the geometric terminator, which on a figure lit anywhere near the camera is right at
//      the silhouette -- so the entire body lands in one flat tone with a thin dark rind and describes no
//      form at all. That was measured on the shader this replaces: nearly every fragment of the torso sat
//      in the top band. Wrapping the light spreads the ramp across the whole form, which is where a
//      painted character's tone boundary actually sits.
//   2. The tones are QUANTISED with a screen-space antialiased edge, so a boundary is a crisp drawn line
//      at any distance rather than a gradient that happens to be steep.
//   3. The shadow tone is the ALBEDO's own colour, darkened and hue-shifted, with its saturation pushed
//      UP first. Multiplying albedo by a blue ambient -- the obvious way, and what the old shader did --
//      desaturates toward grey-blue, and a grey-blue shadow on skin is the single thing that reads as a
//      3D render rather than as drawn art.
//   4. There is NO specular by default. It is a per-material strength, off for skin and cloth and on for
//      hair, because one global glint is exactly what cannot serve both.

in vec3 worldPos;
in vec3 worldNormal;
in vec2 uv;

layout(location = 0) out vec4 fragColor;
// World normal + ambient-fill share, for the indirect-light pass -- see terrain.frag, which carries the
// reasoning for the whole set.
layout(location = 1) out vec4 fragGeometry;

uniform sampler2D baseColorTexture;
uniform vec4 baseColorFactor;

uniform vec3 viewPos;

// Points from the surface toward the sun; used directly as the light vector.
uniform vec3 toSun;
uniform vec3 sunColor;
uniform vec3 ambientColor;

// --- The cel ramp ---------------------------------------------------------------------------------
// How many flat tones, and the half-lambert range the boundaries are spread across. The range is what
// decides whether the shading describes the form: too high and the body is uniformly lit, too low and it
// is uniformly shadowed.
uniform int celBands;
uniform float rampStart;
uniform float rampEnd;

// --- The shadow tone ------------------------------------------------------------------------------
uniform vec3 shadowTint;
uniform float shadowSaturation;
uniform float shadowLift;

// A thin warm line laid along the last tone boundary -- the drawn edge, not a gradient.
//
// A MULTIPLIER over the tone the fragment already has, not light added to it. See the terminator block
// in main() for why: added light there is both brighter than the lit tone and, after the tone map, no
// longer warm.
uniform vec3 terminatorColor;
uniform float terminatorStrength;

uniform vec3 rimColor;
uniform float rimStrength;

// Per material, and zero for anything nobody has said is glossy.
uniform float specularStrength;
uniform float specularShininess;
uniform vec3 specularColor;

// --- Anime material maps --------------------------------------------------------------------------
// Each optional, each off unless the material carries it -- a model nobody has painted maps for renders
// exactly as it did before they existed. What each holds is documented on its setter in Material.java;
// how to paint them is docs/anime-character-shading-2026-11-08.md.

// The same uniform as character.vert's; read here for the matcap's view-space normal.
uniform mat4 view;

// LIGHT MAP: R specular mask, G shadow bias (0.5 neutral), B highlight size, A shadow ramp row.
uniform int hasLightMap;
uniform sampler2D lightMap;

// SHADOW RAMP: u is how lit, the colour there multiplies the lit tone. Row by the light map's alpha, or
// shadowRampRow for the whole material without one.
uniform int hasShadowRamp;
uniform sampler2D shadowRamp;
uniform float shadowRampRow;

// FACE: 0 own normals, 1 the sphere of the head, 2 the face shadow (threshold) map. The head frame is
// world space, found by the renderer from the head bone each frame.
uniform int faceMode;
uniform sampler2D faceShadowMap;
uniform float faceMirrorU;
uniform vec3 headCenter;
uniform vec3 headForward;
uniform vec3 headUp;

// MATCAP: a painted sphere of what a metal reflects, by view-space normal.
uniform int hasMatcap;
uniform sampler2D matcap;
uniform float matcapStrength;

// --- Screen-space depth rim ---------------------------------------------------------------------------
// The figure's own depth, drawn by the renderer into a texture of its own just before this pass -- only
// the figure, so anything not part of it reads as the far plane. See depthRim().
uniform int hasDepthRim;
uniform sampler2D rimDepth;
// Where this viewport starts in the window, so a fragment finds its own texel in rimDepth.
uniform vec2 rimViewportOrigin;
// Viewport height / 1080: keeps the rim the same share of the screen at any resolution or supersampling.
uniform float rimPixelScale;
// The same uniform as character.vert's; read here to turn a depth sample back into a distance.
uniform mat4 projection;

uniform float saturation;

uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float fogHorizon;
uniform float fogHeightFalloff;
uniform float fogBaseY;

// --- Aerial perspective ---------------------------------------------------------------------------
// How much of `fogColor` stands between the eye and a point. Duplicated verbatim into every shader that
// fogs anything (there is no #include in GLSL 330 and no preprocessor here), and pinned equal by
// AerialPerspectiveTest — two surfaces in one frame that disagreed about the air between them and the
// camera would be a seam no still frame explains.
//
// Haze is a LAYER, not a function of distance alone. `fogHeightFalloff` is the reciprocal scale height,
// so density falls off exponentially with altitude and the term below is that density integrated along
// the view ray in closed form. A ray that both begins and ends at `fogBaseY` integrates to exactly one
// and gets precisely the tuned distance fog; a ray climbing to a summit averages much thinner air, which
// is why a landmark keeps a silhouette while the plain at its foot has dissolved.
//
// `fogHorizon` is the one term altitude cannot escape, and it is what stops that escape being unbounded:
// it takes everything, however tall, by the far edge of the streamed world, so nothing is left to be
// clipped by the far plane instead of fading out.
float aerialPerspective(vec3 fragWorldPos, vec3 eyeWorldPos) {
    vec3 ray = fragWorldPos - eyeWorldPos;
    float d = length(ray);

    float dy = fogHeightFalloff * ray.y;
    // (1 - exp(-dy)) / dy, with its removable singularity at dy = 0 spelled out.
    float span = abs(dy) < 1e-4 ? 1.0 : (1.0 - exp(-dy)) / dy;
    // Clamped at 1 so air denser than the layer's base — a ray in a valley, or under it — never fogs
    // harder than the range was tuned for. That clamp is also what makes ground level exactly unchanged.
    float air = clamp(exp(-fogHeightFalloff * (eyeWorldPos.y - fogBaseY)) * span, 0.0, 1.0);

    return max(smoothstep(fogStart, fogEnd, d) * air, smoothstep(fogStart, fogHorizon, d));
}

// How wide the terminator line is, in half-lambert units. A value width rather than a pixel width, so it
// follows the surface's curvature the way a painted line does instead of ringing the silhouette evenly.
//
// THERE IS A FLOOR ON THIS, and it is not obvious: the band's edges are antialiased in screen space, so a
// width near that AA span never reaches full strength and smears back into the ramp this was changed to
// stop being. Measured, 0.012 came out as a 9px soft transition -- softer than switching the term off
// entirely, which steps in 4. Was 0.055, which with hard edges is a band wide enough to read as a fourth
// tone rather than as a line.
const float TERMINATOR_WIDTH = 0.025;

// How far the line reaches into the shadow, as a fraction of how far it reaches into the light. See the
// terminator block below: symmetric, half of it is in shadow by construction.
const float TERMINATOR_SHADOW_SHARE = 0.33;

// Where the rim turns on, as fresnel. High, so it is a line along the edge and not a sheen down the whole
// limb -- which is what the shared world value produced and what read as glass.
const float RIM_EDGE = 0.72;
const float RIM_SOFTNESS = 0.06;

// Where a glossy material's highlight turns on. Deliberately a step: an anime highlight is a shape with an
// edge, and a falloff is what makes one look like a wet patch.
const float SPECULAR_EDGE = 0.55;

// The same edge when a light map's blue channel sets the size: a pinpoint at 0, a broad band at 1.
const float SPECULAR_EDGE_TIGHT = 0.92;
const float SPECULAR_EDGE_BROAD = 0.25;

// How far the light map's green channel can move a texel along the half-lambert ramp either side of
// neutral. At 1.0 a texel painted 0.25 needs the light a quarter of the ramp further round before it is
// lit, which is about right for a crease; the ends (0 and 1) are forced outright below rather than
// relying on this to reach them.
const float LIGHT_MAP_BIAS_RANGE = 1.0;

// How completely a face's own normals are replaced by the head's sphere in face mode 1. At 1 the nose,
// eye sockets and lips stop shading altogether, which is the point; lower brings a little modelling back.
const float FACE_SPHERE_BLEND = 1.0;

// Matcap gain: a mid-grey matcap texel (0.5) leaves the surface as it was.
const float MATCAP_GAIN = 2.0;

const float PI = 3.14159265;

// How wide the depth rim is, in pixels at 1080p, on a figure DEPTH_RIM_REFERENCE_DISTANCE away. Nearer it
// widens and further it narrows, as a painted rim would on a bigger or smaller drawing -- but only within
// DEPTH_RIM_SCALE_RANGE, so a face filling the screen does not get a band, nor a far figure lose it.
const float DEPTH_RIM_WIDTH = 4.0;
const float DEPTH_RIM_REFERENCE_DISTANCE = 3.0;
const vec2 DEPTH_RIM_SCALE_RANGE = vec2(0.35, 1.6);

// How much further away, in metres, what lies past the edge must be for this to count as an edge. Small
// enough that an arm in front of the torso gets a rim, large enough that a fold in a sleeve does not.
const float DEPTH_RIM_THRESHOLD = 0.10;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// How much of the ambient's CHROMA reaches the shadow tone; its luminance always does. See the fill block
// in main(): at 1.0 skin shadows go violet, because the sky is far bluer than any shadow on a face.
const float SHADOW_AMBIENT_CHROMA = 0.35;

// How dark the darkest tone may get, as a fraction of the lit tone's luminance. The ask this answers is
// "the shadow should just be a little darker than the partial shadow" -- with three tones the middle one
// sits halfway, so this sets both steps at once: at 0.72 they are 14% of the lit tone each.
const float SHADOW_FLOOR = 0.72;

// --- Sun shadowing ------------------------------------------------------------------------------
// Kept as a copy in each scene shader rather than shared, for the same reason the cel ramp is: GLSL 330
// has no #include and this project loads each shader as a single file. If one copy changes, all must.
uniform sampler2DShadow shadowMap;
uniform mat4 lightSpaceMatrix;
uniform float shadowTexelUv;
uniform float shadowTexelWorld;
uniform float shadowBias;
uniform float shadowStrength;

// How much of the key light reaches this fragment: 1 lit, 0 fully shadowed.
float sunVisibility(vec3 position, vec3 normal, float NdotL) {
    // Zero strength is the "no shadows this frame" path -- a grazing sun, or a renderer that never ran a
    // shadow pass at all. Skipping the nine taps then is not just an optimisation: the map's contents
    // are undefined in that case.
    if (shadowStrength <= 0.0) {
        return 1.0;
    }

    // Normal offset rather than a large depth bias. Pushing the lookup off the surface -- further where
    // the light grazes, since that is where a texel covers the most depth -- kills acne without moving
    // the comparison along the light ray, which is what detaches a shadow from the foot of its caster.
    float slope = clamp(1.0 - NdotL, 0.0, 1.0);
    vec3 offset = normal * shadowTexelWorld * (1.5 + 3.0 * slope);
    vec4 lightClip = lightSpaceMatrix * vec4(position + offset, 1.0);
    vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;

    if (proj.z > 1.0) {
        return 1.0;
    }

    float bias = shadowBias * (1.0 + 4.0 * slope);
    float lit = 0.0;
    float reference = proj.z - bias;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 tap = proj.xy + (vec2(x, y) + 0.5) * shadowTexelUv;
            lit += texture(shadowMap, vec3(tap, reference));
        }
    }
    float visibility = smoothstep(0.15, 0.85, lit / 9.0);

    vec2 fromCentre = abs(proj.xy - 0.5) * 2.0;
    visibility = mix(visibility, 1.0, smoothstep(0.8, 1.0, max(fromCentre.x, fromCentre.y)));

    return mix(1.0, visibility, shadowStrength);
}

/**
 * One tone boundary, antialiased in SCREEN space rather than in value space.
 *
 * fwidth is how much the value changes over one pixel, so a boundary drawn this way is always about one
 * pixel of softening wide -- crisp on a flat-facing surface where the value crawls, and still smooth
 * where it changes fast. A fixed value width cannot do both: it is a hard staircase on the first and a
 * soft gradient on the second, which is why a cel shader that looks right on a test sphere shimmers along
 * a character's arm.
 */
float toneEdge(float value, float edge) {
    float aa = max(fwidth(value), 1e-5);
    return smoothstep(edge - aa, edge + aa, value);
}

/** The lit fraction, flattened into celBands discrete tones across [rampStart, rampEnd]. */
float celRamp(float value) {
    if (celBands <= 1) {
        return 1.0;
    }
    float tones = float(celBands);
    float level = 0.0;
    for (int i = 1; i < celBands; i++) {
        // Boundaries sit inside the range rather than at its ends, so the darkest and lightest tones are
        // real areas rather than slivers that only appear exactly at the extremes.
        float edge = mix(rampStart, rampEnd, (float(i) - 0.5) / (tones - 1.0));
        level += toneEdge(value, edge);
    }
    return level / (tones - 1.0);
}

vec3 withSaturation(vec3 color, float amount) {
    return mix(vec3(dot(color, LUMA)), color, amount);
}

/**
 * How lit a face texel is, read from the face shadow map instead of from the surface: 1 lit, 0 shadow.
 *
 * Only the light's angle AROUND the head matters -- a face in anime is lit as a mask, not as a relief --
 * so the light is flattened onto the head's horizontal plane and measured from the face's forward: 0 in
 * front, 0.5 at the side, 1 behind. A texel is lit while that angle is below what the map holds for it.
 *
 * The map is painted for light from the character's LEFT; from the right it is read mirrored about the
 * face's centre line. That is the whole reason a face map is a threshold map and not a shadow drawing:
 * one map, every angle, both sides, and a boundary that sweeps smoothly across the face as the light
 * turns rather than jumping between drawings.
 */
float faceShadowLight(vec2 faceUV) {
    vec3 up = normalize(headUp);
    vec3 forward = normalize(headForward - up * dot(headForward, up));
    // The character's own right: for a +Z-facing, +Y-up head this is -X, its right being the viewer's left.
    vec3 right = cross(forward, up);

    vec3 across = toSun - up * dot(toSun, up);
    float acrossLength = length(across);
    vec3 light = acrossLength > 1e-4 ? across / acrossLength : forward;
    float angle = acos(clamp(dot(forward, light), -1.0, 1.0)) / PI;
    // A sun nearly overhead has hardly any side to come from, and the flattened direction swings wildly
    // with the smallest movement; fading the angle toward "front" as it climbs keeps the face lit from
    // above rather than flickering between sides.
    angle *= smoothstep(0.0, 0.25, acrossLength);

    vec2 mapUV = dot(right, light) > 0.0 ? vec2(2.0 * faceMirrorU - faceUV.x, faceUV.y) : faceUV;
    float threshold = texture(faceShadowMap, mapUV).r;
    return toneEdge(threshold - angle, 0.0);
}

/** A depth-buffer value back to a distance from the eye, by inverting whatever perspective is in use. */
float viewDistance(float windowDepth) {
    float ndc = windowDepth * 2.0 - 1.0;
    return projection[3][2] / (ndc + projection[2][2]);
}

/**
 * The screen-space rim: 1 where this fragment is within a few pixels of an edge of the figure, 0 inside.
 *
 * Looks a short way across the screen in the direction the surface is turning away -- the view-space
 * normal's xy -- and asks how far away the figure is THERE. Past the silhouette there is no figure, so the
 * sample reads the far plane; past an arm held in front of the body it reads the body, well behind. Either
 * way the step in depth says "edge", and the fragment gets the rim.
 *
 * Why this rather than fresnel. A fresnel rim is a function of the normal alone, so its width on screen is
 * whatever the curvature makes it: a hairline on a broad back, a fat band round a thin wrist, nothing at
 * all along a flat-shaded edge. Measuring in pixels makes it the same drawn line everywhere, which is what
 * a painted rim is.
 *
 * The step is scaled by how far the normal is turned from the camera, so a surface facing the viewer --
 * whose normal's screen direction is mostly noise -- looks nowhere and gets nothing.
 */
float depthRim(vec3 normal) {
    vec2 across = (mat3(view) * normal).xy;
    float own = viewDistance(gl_FragCoord.z);
    float scale = clamp(DEPTH_RIM_REFERENCE_DISTANCE / max(own, 1e-3),
            DEPTH_RIM_SCALE_RANGE.x, DEPTH_RIM_SCALE_RANGE.y);
    vec2 pixel = gl_FragCoord.xy - rimViewportOrigin + across * (DEPTH_RIM_WIDTH * rimPixelScale * scale);
    // Clamped, so a figure cut off by the screen edge does not light up along the cut.
    ivec2 texel = clamp(ivec2(pixel), ivec2(0), textureSize(rimDepth, 0) - 1);
    float beyond = viewDistance(texelFetch(rimDepth, texel, 0).r);
    return step(DEPTH_RIM_THRESHOLD, beyond - own);
}

void main() {
    vec3 normal = normalize(worldNormal);
    vec3 toView = normalize(viewPos - worldPos);

    vec4 texColor = texture(baseColorTexture, uv);
    vec3 albedo = texColor.rgb * baseColorFactor.rgb;
    float alpha = texColor.a * baseColorFactor.a;

    // Neutral without a light map: full specular mask (so a glossy material is exactly as glossy as it
    // was), no shadow bias, and the material's own ramp row.
    vec4 lightMapTexel = hasLightMap == 1 ? texture(lightMap, uv) : vec4(1.0, 0.5, 0.5, shadowRampRow);

    // A face lit as the sphere of the head rather than by its own normals: the nose, the eye sockets and
    // the lips stop being hard-edged blotches through the ramp, and one clean terminator sweeps round the
    // face as the light turns. Before the light term so everything after -- shadow lookup, rim -- agrees.
    if (faceMode == 1) {
        normal = normalize(mix(normal, normalize(worldPos - headCenter), FACE_SPHERE_BLEND));
    }

    float NdotL = dot(normal, toSun);
    float vis = sunVisibility(worldPos, normal, NdotL);

    // Half lambert: the light wrapped around the form, which is where a painted tone boundary sits. See
    // the notes at the top -- this is the change that makes the shading describe the body at all.
    float wrapped = NdotL * 0.5 + 0.5;

    // The light map's painted shadow bias: a crease, the underside of a chin, the inside of a collar is
    // in shadow because an artist said so, not because a normal happened to point away. Neutral at 0.5,
    // so without a light map this is a no-op; the extremes are forced rather than merely pushed, so
    // "always shadow" and "always lit" mean it at every light angle.
    float shadowBias = lightMapTexel.g;
    wrapped = clamp(wrapped + (shadowBias - 0.5) * LIGHT_MAP_BIAS_RANGE, 0.0, 1.0);
    wrapped = shadowBias < 0.02 ? 0.0 : (shadowBias > 0.98 ? 1.0 : wrapped);

    float shade = celRamp(wrapped);
    // A face with a shadow map takes its light from the map alone: two tones, the boundary where the map
    // puts it. See faceShadowLight.
    if (faceMode == 2) {
        shade = faceShadowLight(uv);
    }

    // A cast shadow can only push a fragment darker, never brighten one already in shadow, so it clamps
    // rather than multiplying -- and clamping keeps the tones flat where a multiply would produce values
    // between them.
    shade = min(shade, vis);

    // The lit tone is the albedo under the key light. The shadow tone is the albedo's OWN colour, pushed
    // up in saturation before it meets the blue fill so it comes out as a deeper version of the surface
    // rather than as a grey-blue wash of it, then lifted so it stays painted rather than muddy.
    vec3 keyTone = albedo * sunColor;

    // The fill takes its LEVEL from the scene's ambient but only part of its HUE, and that split is the
    // whole of why the darkest tone is a deeper skin rather than a violet one.
    //
    // `ambientColor` is the sky, and the sky is strongly blue on purpose -- measured, (0.44, 0.55, 0.76)
    // at high sun, so blue is 1.7x red. Multiplied straight through, skin came out at (115, 95, 129):
    // blue ABOVE red, when every other band on the figure is warm. Its ratio to the lit tone was
    // (0.65, 0.63, 0.91) -- red and green losing a third while blue lost almost nothing -- so the step
    // into shadow was a hue flip rather than a darkening, which is exactly the "grey-blue shadow on
    // skin" this shader's shadow tone exists to avoid. `shadowSaturation` was the counterweight and it
    // is not enough on its own against a sky that blue.
    //
    // Keeping the ambient's luminance means a character still darkens in a cave or at dusk; keeping only
    // a fraction of its chroma means they darken toward their own colour. `shadowTint` still supplies
    // the deliberate slight violet, which is a look decision rather than a consequence of the sky.
    float ambientLevel = dot(ambientColor, LUMA);
    vec3 fillLight = mix(vec3(ambientLevel), ambientColor, SHADOW_AMBIENT_CHROMA);
    vec3 fillTone = withSaturation(albedo, shadowSaturation) * shadowTint * fillLight * shadowLift;

    // ...and it may not fall further below the lit tone than SHADOW_FLOOR, whatever the ambient is doing.
    //
    // Without this the darkest band is at the mercy of the sky's own brightness, which varies by a
    // factor of four across a day: AMBIENT_NIGHT is (0.11, 0.15, 0.28), luminance 0.15, so a night
    // shadow collapsed to about a fifth of the lit tone and read as black. Anime shadow is a different
    // colour at a SIMILAR value, so the band that matters is the one between the tones, not the depth of
    // the darkest one.
    //
    // Scaled on luminance rather than clamped per channel: a per-channel max lifts whichever channel is
    // lowest and bends the hue, which on skin means the blue is raised most and the violet comes back.
    float keyLum = dot(keyTone, LUMA);
    float fillLum = dot(fillTone, LUMA);
    float floorLum = keyLum * SHADOW_FLOOR;
    fillTone *= fillLum < floorLum ? floorLum / max(fillLum, 1e-4) : 1.0;

    vec3 color = mix(fillTone, keyTone, shade);

    // A painted shadow ramp replaces all of the above with the artist's own colours: how lit, across the
    // same half-lambert span the procedural tones are spread over (so the two agree on where the light
    // stops), looked up in the ramp and multiplied over the lit tone.
    if (hasShadowRamp == 1) {
        float lit = faceMode == 2
                ? shade
                : min(clamp((wrapped - rampStart) / max(rampEnd - rampStart, 1e-4), 0.0, 1.0), vis);
        // Onto texel centres, so full light reads the last texel rather than blending into the first
        // under a repeating wrap mode.
        float rampWidth = float(textureSize(shadowRamp, 0).x);
        float rampU = (lit * (rampWidth - 1.0) + 0.5) / rampWidth;
        // Level 0 explicitly: where the light map changes row, v jumps, and the derivative there would
        // drop an automatic lookup to a tiny mip that averages every row -- a seam along every boundary
        // between two materials.
        color = keyTone * textureLod(shadowRamp, vec2(rampU, lightMapTexel.a), 0.0).rgb;
        // The rim, the highlight and the indirect pass all read `shade`; they read the ramp's answer now.
        shade = lit;
    }

    // The drawn edge: a thin warm line where the light actually stops -- the ruddy graze a painted
    // character has along its terminator. Gated by the cast shadow, so there is none on skin the sun
    // cannot reach.
    //
    // Three things about it have been wrong, and the third is why it is a TINT and not added light.
    //
    //   * It was drawn at the FIRST tone boundary. With three tones that is the join between the two
    //     *shadow* tones, so the whole line lay inside the shadow and lit it up -- measured, it added
    //     light to 21% of the shadowed area at peak +76 of 255, which is not an edge, it is a shadow
    //     that has been made brighter. It belongs at the LAST boundary, which is where the lit tone
    //     begins, whatever the band count.
    //   * It fell symmetrically either side of the boundary, so half of it was in shadow by
    //     construction. It is lopsided now, reaching further into the light than into the dark; the
    //     sliver that crosses is what keeps the line attached to the boundary rather than floating off
    //     it.
    //   * IT WAS ADDED, and reported from play as "a bright white line between light and shadow". Both
    //     halves of that are exactly what an additive term does here:
    //
    //       - BRIGHT. Most of the line lies on the LIT tone, which is already the top of the ramp, so
    //         adding to it manufactures a tone brighter than any in the ramp. Measured on the shirt,
    //         luminance went shadow 112 -> mid 145 -> LINE 191 -> lit 167: the edge overshot the lit
    //         tone it borders by 24. A cel shader is a small set of flat tones and a drawn line between
    //         them; a term that invents a brighter one is working against the premise.
    //       - WHITE. The colour is authored strongly red (1.00 : 0.45 : 0.32) and arrived on screen at
    //         1.00 : 0.79 : 0.61, near neutral. On an already-bright lit tone the red channel is up
    //         against the shoulder of the ACES curve where there is no headroom left, while green and
    //         blue still have some -- so the curve compresses most of the red away, flattens the hue,
    //         and what survives is brightness. The term failed at the one thing it exists for.
    //
    //     Tinting fixes both by construction rather than by tuning. A multiply cannot exceed the tone it
    //     lies on, so there is nothing to overshoot and nothing for the bloom to catch; and it works by
    //     pulling green and blue DOWN, into the responsive part of the curve rather than the shoulder,
    //     so the warmth actually survives the tone map. The band lands in value between the lit tone and
    //     the one below it, which is where a painted terminator accent sits.
    float lastEdge = mix(rampStart, rampEnd,
            (max(float(celBands) - 1.0, 1.0) - 0.5) / max(float(celBands) - 1.0, 1.0));
    float acrossEdge = wrapped - lastEdge;
    float lineWidth = acrossEdge >= 0.0 ? TERMINATOR_WIDTH : TERMINATOR_WIDTH * TERMINATOR_SHADOW_SHARE;
    // A BAND WITH HARD EDGES, not a falloff -- antialiased in screen space to about a pixel, exactly the
    // way `toneEdge` draws the tone boundaries either side of it.
    //
    // This was `smoothstep(0.0, lineWidth, ...)`, which ramps from full strength at the boundary to
    // nothing at the edge of the line: a gradient, by definition, and reported from play as exactly
    // that. Measured on a scanline across the shirt it spread the mid-to-lit transition over **20
    // pixels**, against **4** with the term switched off and **3** for the shadow-to-mid boundary next
    // to it -- so the one place the shader laid down a soft ramp was the boundary a cel shader most has
    // to keep crisp, and it read as a glow smeared over a step rather than as a drawn line.
    //
    // The width stays a HALF-LAMBERT width rather than a pixel one, so the line still follows the
    // surface's curvature the way a painted line does instead of ringing the silhouette evenly. Only its
    // edges are screen-space, which is what makes it a line at any distance.
    float aa = max(fwidth(wrapped), 1e-5);
    float line = 1.0 - smoothstep(lineWidth - aa, lineWidth + aa, abs(acrossEdge));
    // Clamped because a surface may set a strength above 1 (the "before" preset does), and mix()
    // extrapolates rather than saturating -- which past 1 would drive the tint through black.
    // Not over a painted ramp, which paints its own edge if it wants one, and not on a face read from a
    // shadow map, whose boundary is wherever the map puts it rather than at the ramp's last edge.
    float terminatorGate = (hasShadowRamp == 0 && faceMode != 2) ? 1.0 : 0.0;
    color = mix(color, color * terminatorColor,
            clamp(line * vis * terminatorStrength * terminatorGate, 0.0, 1.0));

    // Metal: a painted reflection by view-space normal, masked by the light map's red channel. Before the
    // rim and highlight so they still sit on top of it, and a multiply so the cel tones underneath survive
    // -- a buckle in shadow is a darker buckle, not a uniformly bright sticker.
    if (hasMatcap == 1) {
        vec3 viewNormal = normalize(mat3(view) * normal);
        vec3 reflection = texture(matcap, viewNormal.xy * 0.5 + 0.5).rgb;
        float metal = clamp(lightMapTexel.r * matcapStrength, 0.0, 1.0);
        color = mix(color, color * reflection * MATCAP_GAIN, metal);
    }

    // Whether this fragment is in the ramp's LIT tone, which is the one gate both additive terms below
    // are allowed to use.
    //
    // Reported from play as a glint showing where there should be shadow, and it was two gates
    // disagreeing about the same fragment rather than either being too strong. The rim used to test raw
    // N.L on its own ramp and the highlight used to scale by `shade` itself -- which for three tones is
    // 0.5 in the MIDDLE one, so a shadow tone got half a highlight, and a fragment the ramp had just
    // placed in shadow could be two-thirds lit as far as the rim was concerned. Deriving both from the
    // tone makes them agree by construction: nothing can put light on a surface this shader has already
    // decided is shadowed, at any light angle, for any number of bands.
    //
    // Smoothstepped rather than a hard step because `shade` is antialiased across a tone boundary, so a
    // step would alias exactly along the lines these terms are drawn on. The window sits above the
    // second-highest tone for any band count: 0.5 for three tones, 0.667 for four, 0 for two.
    float litTone = smoothstep(0.75, 1.0, shade);

    // Rim: a crisp line along the silhouette, and only where the light actually reaches. An ungated rim
    // wraps the whole outline evenly, which is precisely how a translucent or polished object reads.
    // `shade` already carries the cast shadow, so this needs no separate `vis`.
    //
    // Two ways to find the edge: the screen-space depth rim when the renderer has drawn the figure's depth
    // (the default -- see depthRim for why), and fresnel when it has not. Both are gated the same way.
    float fresnel = 1.0 - max(dot(normal, toView), 0.0);
    float rim = hasDepthRim == 1
            ? depthRim(normal)
            : smoothstep(RIM_EDGE - RIM_SOFTNESS, RIM_EDGE + RIM_SOFTNESS, fresnel);
    color += rimColor * sunColor * rim * litTone * rimStrength;

    // Per-material highlight, off entirely unless something has said this surface is glossy.
    if (specularStrength > 0.0) {
        vec3 halfVector = normalize(toSun + toView);
        float NdotH = max(dot(normal, halfVector), 0.0);
        // With a light map, its red channel says where the highlight may fall and its blue how big it is
        // -- which is what turns one global glint into a hair band, a lip gloss and a buckle at once.
        float edge = hasLightMap == 1
                ? mix(SPECULAR_EDGE_TIGHT, SPECULAR_EDGE_BROAD, lightMapTexel.b)
                : SPECULAR_EDGE;
        float highlight = toneEdge(pow(NdotH, specularShininess), edge) * litTone * lightMapTexel.r;
        color += specularColor * sunColor * highlight * specularStrength;
    }

    // Drain colour before fog, never after: the fog colour comes from the sky palette and is already the
    // hue it should be, so desaturating the composite would wash the horizon out too.
    color = withSaturation(color, saturation);

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    fragColor = vec4(color, alpha);
    fragGeometry = vec4(normal * 0.5 + 0.5, 1.0 - shade);
}
