#version 330 core

in vec3 worldPos;
in vec2 uv;
in vec3 tint;
layout(location = 0) out vec4 fragColor;
// World normal + ambient-fill share, for the indirect-light pass — see terrain.frag. The normal written is
// the same up vector the sprite is shaded with rather than the card's own facing, so a distant tree does not
// swing its indirect light around as the player turns.
layout(location = 1) out vec4 fragGeometry;

uniform sampler2D albedoTexture;
uniform float alphaCutoff;

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

uniform sampler2DShadow shadowMap;
uniform mat4 lightSpaceMatrix;
uniform float shadowTexelUv;
uniform float shadowTexelWorld;
uniform float shadowBias;
uniform float shadowStrength;

// A copy, not an include — GLSL 330 has none, which is why every scene shader here carries this.
float sunVisibility(vec3 position, vec3 normal, float NdotL) {
    if (shadowStrength <= 0.0) {
        return 1.0;
    }
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

void main() {
    vec4 texel = texture(albedoTexture, uv);
    // The same cutout the baked sprite was masked with: the prop's silhouette lives in this alpha, so the
    // billboard's outline is the mesh's outline. Through the coverage-preserving rescale for the same
    // reason a leaf card is -- more so, in fact, since a 256-square sprite drawn at a few dozen pixels is
    // reading a deep mip level by definition, which is the case the plain test erodes hardest.
    if (cutoutAlpha(albedoTexture, uv, texel.a) < alphaCutoff) {
        discard;
    }
    vec3 albedo = texel.rgb * tint;

    // The shading normal is *up*, not the quad's own facing, and that is the load-bearing choice here.
    // A camera-facing card's normal points at the viewer by construction, so lighting it by its facing
    // would make every distant tree take exactly the light of whatever direction the player happens to be
    // looking — the whole wood would brighten and dim as they turned on the spot. Up is what the modelled
    // canopy's normals are already biased toward (the same reasoning GrassMeshFactory applies to blades),
    // so the two sides of the swap answer the sun the same way. The form's own light and shade is already
    // in the sprite, baked, where it belongs.
    vec3 normal = vec3(0.0, 1.0, 0.0);

    float NdotL = dot(normal, toSun);

    // The same four-step banding as terrain.frag, foliage.frag and grass.frag. A distant tree stands among
    // near ones and on that ground, so it has to answer the light with the identical curve or the swap
    // distance shows as a ring of differently-lit trees travelling with the player.
    float band = smoothstep(0.26, 0.32, NdotL) * 0.34
               + smoothstep(0.58, 0.64, NdotL) * 0.33
               + smoothstep(0.83, 0.89, NdotL) * 0.33;

    band *= sunVisibility(worldPos, normal, NdotL);

    vec3 color = albedo * mix(ambientColor, sunColor, band);

    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, saturation);

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    fragColor = vec4(color, 1.0);
    fragGeometry = vec4(normal * 0.5 + 0.5, 1.0 - band);
}
