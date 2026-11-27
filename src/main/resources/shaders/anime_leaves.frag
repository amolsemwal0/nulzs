#version 330 core

// Leaves painted the way the user's reference paints a crown (~/Documents/Game References/anime trees and bushes.blend):
// not lit leaf by leaf, but each clump one flat colour taken from a RAMP by where it sits in the crown — dark, cool and
// saturated under the crown and deep in it, the leaf's own colour on its flanks, pale on top. The reference shades its
// clumps with emission only, a gradient through a colour ramp; this is that, in the game's light.
//
// Drawn for a leaf material that asks for it (FoliageMaterial.anime) in place of foliage.frag, through the same vertex
// shader and the same uniforms, so wind, cutout, shadows, lamps, fog and occlusion all behave as they do there. The
// cards' normals are the crown's VOLUME normals (FoliageMaterial.cutoutWithVolumeNormals): every card of a clump has its
// place's one normal, so a clump takes one place on the ramp, as the reference's do.
//
// What it does, in the order main() does it:
//   1. The leaf colour is the picture's, each oval of a rosette its own flat colour (tools/anime_leaf_rosettes.py), times
//      the card's tint: how deep in the crown it is, and a little of its own.
//   2. Its PLACE on the ramp: how far its crown normal faces up, pulled a little toward the sun (crownPlace), less a
//      step where something casts a shadow on it (SHADOW_DROP), so a tree in a building's shade is darker.
//   3. The RAMP (leafRamp): the colour at that place, from a dark, cool, saturated version of it at the bottom to the
//      colour itself and a little more at the top; times the light of the hour (rampLight), so night darkens it.
// After that it is foliage.frag again: the night's saturation drain, lamplight, fog.
//
// The far trees' pictures (lit_impostor.frag) take the same ramp, copied between the LEAF RAMP lines and pinned equal by
// LeafRampTest, so a tree turning into its picture keeps its colours.

in vec3 worldPos;
in vec3 worldNormal;
in vec2 uv;
in vec3 tint;
in vec3 worldTangent;
// The card's centre: the sun's shadow is read there, so a clump takes one tone, as a painted clump does, and the
// shadow map — drawn from the cards as they stand, not as they are turned to the camera — is read where the card is.
in vec3 cardCentre;

layout(location = 0) out vec4 fragColor;
// World normal + ambient-fill share, for the indirect-light pass — see terrain.frag.
layout(location = 1) out vec4 fragGeometry;

uniform sampler2D albedoTexture;

// As in foliage.frag.
uniform int occluded;
uniform int unbloomed;
uniform float alphaCutoff;

uniform vec3 viewPos;

uniform vec3 toSun;
uniform vec3 sunColor;
uniform vec3 ambientColor;

uniform float saturation;

uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float fogHorizon;
uniform float fogHeightFalloff;
uniform float fogBaseY;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// --- LEAF RAMP BEGIN ---------------------------------------------------------------------------------
// Copied into lit_impostor.frag and pinned equal by LeafRampTest: near trees and their far pictures on one ramp.

// How far toward the sun the light a crown is read by leans, from straight down onto it: the top of a crown is always
// the light end of the ramp, as in the reference, and its sunny side lighter than its shady one.
const float RAMP_SUN_PULL = 0.55;
// The ramp's stops, as the reference's colour ramp runs: where each sits along it (x), how bright against the leaf's
// own colour (y), how saturated (z), and how far toward RAMP_COOL (w): near black-green and cool at the bottom, deep
// and saturated, then the leaf's colour, then lighter and paler at the top, as the reference's mint tops are.
const vec4 RAMP_0 = vec4(0.00, 0.16, 1.45, 1.00);
const vec4 RAMP_1 = vec4(0.30, 0.46, 1.36, 0.70);
const vec4 RAMP_2 = vec4(0.58, 0.78, 1.22, 0.30);
const vec4 RAMP_3 = vec4(0.82, 1.02, 1.04, 0.05);
const vec4 RAMP_4 = vec4(1.00, 1.20, 0.86, 0.00);
// The shadow end's hue: teal, as the reference's dark leaves are.
const vec3 RAMP_COOL = vec3(0.78, 0.94, 1.14);
// The ramp is drawn for full daylight: the light of the hour against that, so dusk and night darken and colour it.
const float RAMP_DAYLIGHT = 1.2;
const float RAMP_SUN_SHARE = 0.7;

vec3 withSaturation(vec3 colour, float amount) {
    float grey = dot(colour, vec3(0.2126, 0.7152, 0.0722));
    return max(mix(vec3(grey), colour, amount), vec3(0.0));
}

// Where a clump sits on the ramp, 0 to 1, by its crown normal: 1 facing straight up into the light, 0 facing down.
float crownPlace(vec3 normal, vec3 toSun) {
    vec3 light = normalize(vec3(0.0, 1.0, 0.0) + toSun * RAMP_SUN_PULL);
    return dot(normal, light) * 0.5 + 0.5;
}

vec3 rampStop(vec3 albedo, vec4 stop) {
    return withSaturation(albedo, stop.z) * stop.y * mix(vec3(1.0), RAMP_COOL, stop.w);
}

// The leaf's colour at a place on the ramp, eased from stop to stop.
vec3 leafRamp(vec3 albedo, float place) {
    float t = clamp(place, 0.0, 1.0);
    vec3 colour = rampStop(albedo, RAMP_0);
    colour = mix(colour, rampStop(albedo, RAMP_1), smoothstep(RAMP_0.x, RAMP_1.x, t));
    colour = mix(colour, rampStop(albedo, RAMP_2), smoothstep(RAMP_1.x, RAMP_2.x, t));
    colour = mix(colour, rampStop(albedo, RAMP_3), smoothstep(RAMP_2.x, RAMP_3.x, t));
    colour = mix(colour, rampStop(albedo, RAMP_4), smoothstep(RAMP_3.x, RAMP_4.x, t));
    return colour;
}

// The light of the hour, against the full daylight the ramp is drawn for.
vec3 rampLight(vec3 ambient, vec3 sun) {
    return mix(ambient, sun, RAMP_SUN_SHARE) * RAMP_DAYLIGHT;
}
// --- LEAF RAMP END -----------------------------------------------------------------------------------

// How far a cast shadow moves a clump down the ramp: a building's or another tree's shade darkens it a step.
const float SHADOW_DROP = 0.3;

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

// --- Sun shadowing ------------------------------------------------------------------------------
// Kept as a copy in each scene shader rather than shared, for the same reason the cel band is: GLSL 330
// has no #include and this project loads each shader as a single file. If one copy changes, all must.
uniform sampler2DShadow shadowMap;
uniform mat4 lightSpaceMatrix;
uniform float shadowTexelUv;
uniform float shadowTexelWorld;
uniform float shadowBias;
uniform float shadowStrength;

// How much of the key light reaches this fragment: 1 lit, 0 fully shadowed.
float sunVisibility(vec3 position, vec3 normal, float NdotL) {
    // Zero strength is the "no shadows this frame" path — a grazing sun, or a renderer that never ran a
    // shadow pass at all. Skipping the nine taps then is not just an optimisation: the map's contents
    // are undefined in that case.
    if (shadowStrength <= 0.0) {
        return 1.0;
    }

    // Normal offset rather than a large depth bias. Pushing the lookup off the surface — further where
    // the light grazes, since that is where a texel covers the most depth — kills acne without moving
    // the comparison along the light ray, which is what detaches a shadow from the foot of its caster.
    float slope = clamp(1.0 - NdotL, 0.0, 1.0);
    vec3 offset = normal * shadowTexelWorld * (1.5 + 3.0 * slope);
    vec4 lightClip = lightSpaceMatrix * vec4(position + offset, 1.0);
    vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;

    // Beyond the far plane of the light's box there is nothing recorded to be shadowed by.
    if (proj.z > 1.0) {
        return 1.0;
    }

    float bias = shadowBias * (1.0 + 4.0 * slope);
    float lit = 0.0;
    // Nine hardware-compared taps. Each one is already bilinearly filtered across four texels, so the
    // kernel covers ~4 texels of penumbra with a smooth ramp instead of the ten discrete steps a
    // compare-in-the-shader kernel can produce. Taps are offset by half a texel so the four texels each
    // one blends are weighted evenly rather than landing centred on one of them.
    float reference = proj.z - bias;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 tap = proj.xy + (vec2(x, y) + 0.5) * shadowTexelUv;
            lit += texture(shadowMap, vec3(tap, reference));
        }
    }
    // Tighten the ramp. Every tap is already bilinearly filtered by the hardware, so the raw average is a
    // soft gradient several texels wide; steepening it keeps that antialiasing while making the shadow
    // read as a *shape with an edge* rather than a smudge — which is the same thing the cel bands do to
    // the diffuse, and the reason a stylised scene wants this and a photoreal one would not.
    float visibility = smoothstep(0.15, 0.85, lit / 9.0);

    // Fade out toward the edge of the map. The box only covers a radius around the player, and without
    // this the world would show a hard square edge travelling with them where shadows simply stop.
    vec2 fromCentre = abs(proj.xy - 0.5) * 2.0;
    visibility = mix(visibility, 1.0, smoothstep(0.8, 1.0, max(fromCentre.x, fromCentre.y)));

    return mix(1.0, visibility, shadowStrength);
}

// --- Lamps -------------------------------------------------------------------------------------------
// Light from the points near this fragment: street lamps after dark. A copy, like the shadow lookup, in
// terrain.frag and foliage.frag -- GLSL 330 has no #include -- and PointLightShaderTest pins the copies
// equal: a lamp's pool has to light the paving, the bench on it, the wall behind it and the person
// walking past by the same rule, or it stops at the edge of a material instead of at the edge of its light.
//
// The pool is BANDED like the sun's light, three steps from its bright heart out to its rim, so it reads
// as a painted pool of light on cel-shaded ground rather than a photographic glow. Under the bands it
// falls off as the inverse square does, windowed to nothing at the light's radius, so there is never an
// edge where it stops. A surface turned away from the lamp gets none, and one side-on still gets some --
// the wrap -- so a post or a person beside the lamp is lit down the side facing it. There is no shadow: a
// lamp lights through whatever stands between it and a surface, which its radius keeps to its own street.
//
// Only the lights PointLights picks for the frame are here, at most MAX_POINT_LIGHTS, and none by day.
const int MAX_POINT_LIGHTS = 16;
uniform int pointLightCount;
uniform vec3 pointLightPosition[MAX_POINT_LIGHTS];
uniform vec3 pointLightColor[MAX_POINT_LIGHTS];
uniform float pointLightRadius[MAX_POINT_LIGHTS];

vec3 pointLight(vec3 position, vec3 normal) {
    vec3 total = vec3(0.0);
    for (int i = 0; i < pointLightCount; i++) {
        vec3 toLight = pointLightPosition[i] - position;
        float distance = length(toLight);
        float reach = distance / pointLightRadius[i];
        if (reach >= 1.0) {
            continue;
        }
        float window = 1.0 - reach * reach;
        float falloff = window * window / (1.0 + 16.0 * reach * reach);
        float pool = smoothstep(0.018, 0.026, falloff) * 0.25
                   + smoothstep(0.060, 0.080, falloff) * 0.30
                   + smoothstep(0.180, 0.220, falloff) * 0.45;
        float facing = clamp((dot(normal, toLight / max(distance, 1e-4)) + 0.5) / 1.5, 0.0, 1.0);
        total += pointLightColor[i] * pool * facing;
    }
    return total;
}

/**
 * Everything below this world height is discarded.
 *
 * <p>For the planar water reflection: the mirrored camera sees the far side of the water plane, and the
 * ground under the pond would otherwise be drawn upside down into the reflection and read as silt floating
 * in the sky. Its default is far below the world, so every ordinary pass is unaffected and nothing but the
 * reflection pass ever pays for it.
 *
 * <p>A fragment discard rather than {@code gl_ClipDistance}, which would be the tidier tool and would mean
 * touching every vertex shader that draws in that pass plus enabling a clip plane around it. Two lines
 * here, in the two shaders the pass actually uses, is a much smaller change for the same result.
 */
uniform float clipBelowY;

// How hard a cutout's alpha is pushed back up per mip level, for cutoutAlpha below.
//
// Set against what the averaging actually costs rather than picked: one mip level halves a cutout's
// linear feature size, and on the leaf art measured here that takes roughly a quarter of the surviving
// coverage with it per level. Higher and a distant canopy grows *denser* than the one it is meant to
// match, which reads as trees fattening as you back away from them; lower and the erosion is merely
// slower rather than gone. Judge it from trees_stand.png against a close-up, never from the number.
const float MIP_ALPHA_SCALE = 0.25;

// --- Coverage-preserving alpha for a mipmapped cutout ---------------------------------------------
// Duplicated verbatim in foliage.frag, depth_cutout.frag, impostor.frag and grass.frag. GLSL 330 has no
// #include and this project has no shader preprocessor, so the copies are pinned equal by
// AlphaCutoutTest rather than shared. They HAVE to agree: a canopy that discards on a different rule
// from the one its own shadow discards on draws shade under the gaps in itself.
//
// WHY A PLAIN `a < cutoff` IS NOT ENOUGH. Mipmapping is a box filter, so it averages a cutout's alpha
// toward the texture's own mean -- and a leaf sheet's mean is well under 1 (measured on the highland
// canopy: 57.6% clear, 40.7% opaque, mean 0.416). A fixed threshold therefore discards more of the card
// the further away it is: the canopy erodes toward a bare skeleton at range, and this codebase has
// already had to drop one cutoff from 0.5 to 0.25 to work around exactly that. Worse than the erosion is
// its edge -- every texel sitting near the threshold flips between kept and discarded as the mip level
// slides with the camera, which is the "transition sweeping across the forest as the camera moves"
// already recorded here. That sweep IS the shimmer.
//
// Scaling the alpha back up by the mip level being read undoes the averaging, so a card keeps roughly
// the coverage it was authored with at any distance and its near-threshold texels are pushed clear of
// the threshold instead of hovering on it. The level is computed from the derivatives rather than asked
// for: textureQueryLod is GLSL 400 and this is 330.
//
// It is exactly a no-op at mip 0, which is the property that makes it safe to apply everywhere: lod
// clamps at zero, the scale is 1, and every close-up in the project is untouched by construction.
//
// Must be called in uniform control flow -- it takes derivatives, which are undefined once a quad's
// fragments have taken different paths. That is why every caller samples and rescales BEFORE its
// discards rather than after.
float cutoutAlpha(sampler2D tex, vec2 texUv, float sampledAlpha) {
    vec2 texels = vec2(textureSize(tex, 0));
    vec2 dx = dFdx(texUv) * texels;
    vec2 dy = dFdy(texUv) * texels;
    float lod = 0.5 * log2(max(dot(dx, dx), dot(dy, dy)));
    return sampledAlpha * (1.0 + max(0.0, lod) * MIP_ALPHA_SCALE);
}

void main() {
    // Sampled before either discard: cutoutAlpha takes screen-space derivatives, which are only defined while every
    // fragment of the quad is still on the same path. See foliage.frag.
    vec4 texel = texture(albedoTexture, uv);
    float coverage = cutoutAlpha(albedoTexture, uv, texel.a);

    if (worldPos.y < clipBelowY) {
        discard;
    }
    if (coverage < alphaCutoff) {
        discard;
    }

    // 1. The leaf's colour: its oval's, times how deep in the crown the card is.
    vec3 albedo = min(texel.rgb * tint, vec3(1.0));

    // The crown's normal: never flipped for the side of the card that is showing, since it is the volume's, not the
    // card's (FoliageMaterial.flipsBackFaceNormals).
    vec3 normal = normalize(worldNormal);

    // 2. Its place on the ramp, a step down in a cast shadow (read at the card's centre: one tone a clump).
    float sun = sunVisibility(cardCentre, normal, dot(normal, toSun));
    float place = crownPlace(normal, toSun) - (1.0 - sun) * SHADOW_DROP;

    // 3. The ramp's colour there, in the light of the hour.
    vec3 color = leafRamp(albedo, place) * rampLight(ambientColor, sunColor);

    color = mix(vec3(dot(color, LUMA)), color, saturation);

    // Lamplight after the saturation drain, as in foliage.frag: a warm pool on a moonlit scene.
    color += albedo * pointLight(worldPos, normal);

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    fragColor = vec4(color, unbloomed == 1 ? 0.0 : 1.0);

    // How much of its colour is the light's, for occlusion to leave alone: the lighter up the ramp, the more.
    fragGeometry = occluded == 1 ? vec4(normal * 0.5 + 0.5, 1.0 - clamp(place, 0.0, 1.0)) : vec4(0.5, 0.5, 0.5, 0.0);
}
