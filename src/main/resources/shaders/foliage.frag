#version 330 core

in vec3 worldPos;
in vec3 worldNormal;
in vec2 uv;
in vec3 tint;
in vec3 worldTangent;

layout(location = 0) out vec4 fragColor;
// World normal + ambient-fill share, for the indirect-light pass — see terrain.frag, which carries the
// reasoning for the whole set.
layout(location = 1) out vec4 fragGeometry;

uniform sampler2D albedoTexture;

// Tangent-space normal map. Optional: leaves have no such art, and sampling a flat one for them would be
// pure cost, so the flag skips it entirely rather than binding a placeholder.
uniform sampler2D normalTexture;
uniform int useNormalMap;

// Set when the normal map had to be uploaded GL_MIRRORED_REPEAT because it does not tile. Applying the
// un-mirroring below to a map that DOES tile would light every second tile from the wrong side, so this
// tracks how the texture was actually uploaded rather than being assumed.
uniform int normalMapMirrored;

// Set when a back-facing fragment should negate its shading normal. Derived on the CPU from two facts at
// once — see FoliageMaterial.flipsBackFaceNormals, which carries the reasoning — because the fragment
// cannot tell them apart: it is right only for a two-sided card whose normal is its own plane, and wrong
// for one whose normal was borrowed from the canopy volume.
uniform int flipBackFaceNormals;

// 0 for a flat picture stood upright (a sprite): it writes "no surface" to the geometry target, so ambient
// occlusion leaves it as drawn rather than smudging it, while its depth still shades the ground at its feet.
uniform int occluded;

// 1 for a sprite drawn into a picture that blooms: it writes 0 alpha, which the bloom's bright pass reads as nothing to
// glow off, so pixel art keeps its crisp edges and its whites. See Renderer.setSpritesUnbloomed.
uniform int unbloomed;

// Cutout threshold. The leaf texture's alpha is binary (measured: 67% at 0, 33% at 255, nothing
// between), so discarding at the midpoint is exact — there is no soft edge to preserve, and staying in
// the opaque pass means no blending and no back-to-front sorting of thousands of leaf cards.
uniform float alphaCutoff;

uniform vec3 viewPos;

uniform vec3 toSun;
uniform vec3 sunColor;
uniform vec3 ambientColor;

uniform vec3 rimColor;
uniform float rimPower;
uniform float rimThreshold;

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
    // Sampled and rescaled before either discard, and the order is load-bearing: cutoutAlpha takes
    // screen-space derivatives, which are only defined while every fragment of the quad is still on the
    // same path. Discarding first is what would make them undefined.
    vec4 texel = texture(albedoTexture, uv);
    float coverage = cutoutAlpha(albedoTexture, uv, texel.a);

    if (worldPos.y < clipBelowY) {
        discard;
    }
    if (coverage < alphaCutoff) {
        discard;
    }

    vec3 albedo = texel.rgb * tint;

    // An asset-authored leaf card is a sheet whose normal is its own plane, so the back of one must flip
    // that normal or half the canopy falls into permanent shadow. Two things matter here:
    //   - the test is gl_FrontFacing, not dot(normal, toView). The dot test asks "does this normal face
    //     me", which on a curved surface flickers along the silhouette; gl_FrontFacing asks which side of
    //     the triangle is being rasterised, which is the actual question.
    //   - whether to flip at all is NOT a question this shader can answer, hence the uniform. A generated
    //     canopy's normals point out of the crown's centroid rather than off each card's plane, and
    //     negating one of those is negating a direction the rasterised side says nothing about — so the
    //     crown reshades as the camera orbits. See FoliageMaterial.flipsBackFaceNormals.
    vec3 normal = normalize(worldNormal);
    if (flipBackFaceNormals == 1 && !gl_FrontFacing) {
        normal = -normal;
    }

    if (useNormalMap == 1) {
        vec3 sampled = texture(normalTexture, uv).xyz * 2.0 - 1.0;

        // A map that does not tile (oak: opposite edges differ by ~40/255 against a ~12 baseline) is
        // sampled GL_MIRRORED_REPEAT to hide the seam. Mirroring flips the surface direction, so
        // tangent-space X and Y must be flipped back in the mirrored tiles — otherwise every second tile
        // is lit as though the light came from the other side. Birch's map tiles and skips all of this.
        if (normalMapMirrored == 1) {
            vec2 mirrored = mod(floor(uv), 2.0);
            sampled.x *= mix(1.0, -1.0, mirrored.x);
            sampled.y *= mix(1.0, -1.0, mirrored.y);
        }

        vec3 t = normalize(worldTangent - normal * dot(normal, worldTangent));
        vec3 b = cross(normal, t);
        normal = normalize(mat3(t, b, normal) * sampled);
    }

    vec3 toView = normalize(viewPos - worldPos);

    float NdotL = dot(normal, toSun);

    // Same four-step banding as terrain.frag, deliberately: trees standing on that ground have to be lit
    // by the same response curve or they read as pasted on. Kept as a copy rather than a shared include
    // because GLSL 330 has no include and this project loads each shader as one file.
    float band = smoothstep(0.26, 0.32, NdotL) * 0.34
               + smoothstep(0.58, 0.64, NdotL) * 0.33
               + smoothstep(0.83, 0.89, NdotL) * 0.33;

    // Cast shadows multiply the band down, which lands a shadowed fragment on `ambientColor` — exactly
    // where the unlit side of the same surface already sits. That is the whole reason this works with the
    // key/fill model: a shadow is not a darkening applied afterwards, it is the surface being told the key
    // light does not reach it, so shadowed ground matches shaded ground instead of being a grey smear over
    // it. The rim and specular terms are gated on `band` too, so both follow for free.
    band *= sunVisibility(worldPos, normal, NdotL);

    vec3 light = mix(ambientColor, sunColor, band);
    vec3 color = albedo * light;

    float NdotV = max(dot(normal, toView), 0.0);
    float rim = pow(1.0 - NdotV, rimPower);
    rim = smoothstep(rimThreshold - 0.1, rimThreshold + 0.1, rim) * (0.3 + 0.7 * band);
    color += rimColor * rim * 0.15;

    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, saturation);

    // Lamplight after the saturation drain, as in terrain.frag: a warm pool on a moonlit scene.
    color += albedo * pointLight(worldPos, normal);

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    fragColor = vec4(color, unbloomed == 1 ? 0.0 : 1.0);

    // The flipped normal for a back-facing leaf card, deliberately: a card lit from its back has to occlude
    // and bounce from that side too, or the underside of a canopy gathers light through itself.
    fragGeometry = occluded == 1 ? vec4(normal * 0.5 + 0.5, 1.0 - band) : vec4(0.5, 0.5, 0.5, 0.0);
}
