#version 330 core

// A far tree as one flat picture (LitImpostor), coloured the way anime_leaves.frag colours the near ones, so the change
// from the tree to its picture is a change of detail and not of colour: the picture's own colour, its baked normals
// turned from the picture into the world, the same place on the same ramp (the LEAF RAMP lines, pinned equal by
// LeafRampTest), the same night drain and fog. Not read: the sun's shadow map, which covers a few tens of metres round
// the player and never reaches as far as a tree drawn this way; lamplight, which does not carry so far either.

in vec3 worldPos;
in vec2 uv;

layout(location = 0) out vec4 fragColor;
// World normal + ambient-fill share, for the indirect-light pass — see terrain.frag.
layout(location = 1) out vec4 fragGeometry;

uniform sampler2D albedoTexture;
uniform sampler2D normalTexture;
uniform float alphaCutoff;
uniform vec3 cameraRight;

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
    // Sampled before the discard: cutoutAlpha takes screen-space derivatives. See foliage.frag.
    vec4 texel = texture(albedoTexture, uv);
    float coverage = cutoutAlpha(albedoTexture, uv, texel.a);
    vec3 baked = texture(normalTexture, uv).xyz * 2.0 - 1.0;
    if (coverage < alphaCutoff) {
        discard;
    }
    vec3 albedo = texel.rgb;

    // The picture's normals are its own: x to its right, y up, z out of it toward whoever looks. Turned into the world
    // as the picture stands, facing the camera about the upright.
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 out_ = cross(cameraRight, up);
    vec3 normal = normalize(baked.x * cameraRight + baked.y * up + baked.z * out_);

    // The near trees' ramp (anime_leaves.frag), at the same place in the crown, in the same light; no cast shadow.
    float place = crownPlace(normal, toSun);
    vec3 color = leafRamp(albedo, place) * rampLight(ambientColor, sunColor);

    color = mix(vec3(dot(color, LUMA)), color, saturation);

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    fragColor = vec4(color, 1.0);
    fragGeometry = vec4(normal * 0.5 + 0.5, 1.0 - clamp(place, 0.0, 1.0));
}
