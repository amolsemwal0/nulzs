#version 330 core

// Fragment stage of every material compiled from Blender shader nodes. ShaderCompiler fills in the marked places:
// uniforms for the material's images and parameters, functions for its colour ramps and curves, and the body that
// evaluates its node tree into a Closure. Everything around that is the engine's own lighting, so a Blender
// material sits in a scene lit like everything else in it.
//
// Two spaces meet here. The node graph runs in Blender's (Z up), so positions and normals handed to it are
// converted; lighting, shadows and fog run in the engine's (Y up), the space the rest of the scene is lit in.

in vec3 v_worldPos;
in vec3 v_worldNormal;
in vec3 v_localPos;
in vec3 v_localNormal;
in vec2 v_uv;
in vec4 v_color;
in vec4 v_tangent;
in vec3 v_instance;
in vec4 v_attr0;
in vec4 v_attr1;
in vec4 v_attr2;
in vec4 v_attr3;
in vec4 v_attr4;
in vec4 v_attr5;

layout(location = 0) out vec4 fragColor;
// The geometry buffer GlobalIllumination reads, as terrain.frag writes it: normal, and the share of the light that
// was ambient fill.
layout(location = 1) out vec4 fragGeometry;

uniform vec3 viewPos;
uniform mat4 view;
uniform mat4 projection;
uniform mat4 model;
uniform int instanced;
uniform vec3 toSun;
uniform vec3 sunColor;
uniform vec3 ambientColor;
uniform vec3 rimColor;
uniform float rimPower;
uniform float rimThreshold;
uniform float saturation;
uniform float time;
uniform float frame;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float fogHorizon;
uniform float fogHeightFalloff;
uniform float fogBaseY;
/** Blender's auto texture space of the mesh: Generated coordinates are measured across it. */
uniform vec3 texspaceLocation;
uniform vec3 texspaceSize;
/** 1 for a material Blender draws "Blended" (sorted, drawn after the opaque scene); 0 opaque or dithered. */
uniform int blended;
/** Dithered transparency: Blender's default render method keeps transparent materials in the opaque pass. */
uniform int dithered;
uniform float alphaThreshold;
uniform float emissiveScale;
/**
 * Baking (NodeBaker) rather than drawing: 0 draws as usual; 1 writes the surface's own colour, unlit, as Blender's
 * Diffuse Color bake does; 2 writes its shading normal in the surface's tangent space, as a normal map holds it.
 */
uniform int bake;

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
// A copy, like the one in terrain.frag and foliage.frag: GLSL 330 has no #include and this project
// loads each shader as a single file. If one copy changes, all must.
uniform sampler2DShadow shadowMap;
uniform mat4 lightSpaceMatrix;
uniform float shadowTexelUv;
uniform float shadowTexelWorld;
uniform float shadowBias;
uniform float shadowStrength;

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
    // Tighten the ramp. Every tap is already bilinearly filtered by the hardware, so the raw average is a
    // soft gradient several texels wide; steepening it keeps that antialiasing while making the shadow
    // read as a *shape with an edge* rather than a smudge — which is the same thing the cel bands do to
    // the diffuse, and the reason a stylised scene wants this and a photoreal one would not.
    float visibility = smoothstep(0.15, 0.85, lit / 9.0);

    vec2 fromCentre = abs(proj.xy - 0.5) * 2.0;
    visibility = mix(visibility, 1.0, smoothstep(0.8, 1.0, max(fromCentre.x, fromCentre.y)));

    return mix(1.0, visibility, shadowStrength);
}

// --- Blender space -----------------------------------------------------------------------------------------

vec3 nb_bl(vec3 v) { return vec3(v.x, -v.z, v.y); }
vec3 nb_gl(vec3 v) { return vec3(v.x, v.z, -v.y); }

// @NODE_LIBRARY@

// --- Closures ------------------------------------------------------------------------------------------------
// A shader socket's value: the light leaving the surface (display-referred, as the engine draws), the light
// passing through it (Transparent BSDF), and the normal and fill share the geometry buffer wants, weighted so a
// Mix Shader blends them as it blends the colour.

struct Closure {
    vec3 radiance;
    vec3 transmittance;
    vec3 normal;
    float fill;
    float weight;
};

Closure nb_closure_none() { return Closure(vec3(0.0), vec3(0.0), vec3(0.0), 0.0, 0.0); }

Closure nb_mix_closure(float f, Closure a, Closure b) {
    f = clamp(f, 0.0, 1.0);
    return Closure(mix(a.radiance, b.radiance, f), mix(a.transmittance, b.transmittance, f),
                   mix(a.normal * a.weight, b.normal * b.weight, f), mix(a.fill * a.weight, b.fill * b.weight, f),
                   mix(a.weight, b.weight, f)) ;
}

Closure nb_add_closure(Closure a, Closure b) {
    return Closure(a.radiance + b.radiance, a.transmittance + b.transmittance, a.normal * a.weight + b.normal * b.weight,
                   a.fill * a.weight + b.fill * b.weight, a.weight + b.weight);
}

vec3 nb_worldPos;   // engine space, for lighting
vec3 nb_view;       // engine space, surface to eye

// The engine's four-step cel band (terrain.frag), or a plain clamped Lambert when banded is false: what a material
// feeding Shader to RGB sees, so its own colour ramp does the banding as it does in Blender.
float nb_band(float NdotL, bool banded) {
    if (!banded) return clamp(NdotL, 0.0, 1.0);
    return smoothstep(0.26, 0.32, NdotL) * 0.34 + smoothstep(0.58, 0.64, NdotL) * 0.33 + smoothstep(0.83, 0.89, NdotL) * 0.33;
}

/** Diffuse light on an albedo (display-referred), lit as the engine lights terrain; fill share into fill. */
vec3 nb_light_diffuse(vec3 albedo, vec3 N, bool banded, out float fill, out float band) {
    if (bake != 0) {
        fill = 0.0;
        band = 1.0;
        return albedo;
    }
    float NdotL = dot(N, toSun);
    band = nb_band(NdotL, banded) * sunVisibility(nb_worldPos, N, NdotL);
    fill = 1.0 - band;
    vec3 color = albedo * mix(ambientColor, sunColor, band);
    if (banded) {
        float terminator = clamp(smoothstep(0.0, 0.2, NdotL) - smoothstep(0.2, 0.55, NdotL), 0.0, 1.0);
        color += albedo * vec3(1.0, 0.55, 0.4) * terminator * band * 0.14;
        float NdotV = max(dot(N, nb_view), 0.0);
        float rim = pow(1.0 - NdotV, rimPower);
        rim = smoothstep(rimThreshold - 0.1, rimThreshold + 0.1, rim) * (0.3 + 0.7 * band);
        color += rimColor * rim * 0.35;
    }
    return color;
}

/** A sun highlight: normalised Blinn-Phong from roughness, hardened into a toon highlight when banded. */
vec3 nb_light_specular(vec3 f0, float roughness, vec3 N, float band, bool banded) {
    if (bake != 0) return vec3(0.0);
    vec3 H = normalize(toSun + nb_view);
    float r = clamp(roughness, 0.04, 1.0);
    float shininess = clamp(2.0 / (r * r * r * r) - 2.0, 1.0, 4096.0);
    float spec = pow(max(dot(N, H), 0.0), shininess) * (shininess + 8.0) / (8.0 * NB_PI);
    if (banded) spec = smoothstep(0.45, 0.55, spec * 0.5) * (shininess + 8.0) / (8.0 * NB_PI) * 0.5;
    vec3 environment = mix(ambientColor, sunColor, 0.5) * (1.0 - r * 0.7);
    return f0 * (sunColor * spec * band + environment * 0.35);
}

Closure nb_bsdf_diffuse(vec4 color, vec3 Nbl, bool banded) {
    vec3 N = normalize(nb_gl(Nbl));
    float fill, band;
    vec3 c = nb_light_diffuse(nb_linear_to_srgb(max(color.rgb, 0.0)), N, banded, fill, band);
    return Closure(c, vec3(0.0), N, fill, 1.0);
}

Closure nb_bsdf_principled(vec4 base, float metallic, float roughness, float alpha, vec3 Nbl, vec4 emission,
                           float emissionStrength, float transmission, float coat, float coatRoughness, float sheen,
                           vec4 sheenTint, float specularLevel, bool banded) {
    vec3 N = normalize(nb_gl(Nbl));
    vec3 albedo = nb_linear_to_srgb(max(base.rgb, 0.0));
    metallic = clamp(metallic, 0.0, 1.0);
    float fill, band;
    vec3 diffuse = nb_light_diffuse(albedo, N, banded, fill, band);
    vec3 f0 = mix(vec3(0.08 * clamp(specularLevel, 0.0, 1.0)), albedo, metallic);
    vec3 c = bake != 0 ? albedo
            : diffuse * (1.0 - metallic) * (1.0 - clamp(transmission, 0.0, 1.0)) + nb_light_specular(f0, roughness, N, band, banded);
    if (coat > 0.0) c += nb_light_specular(vec3(0.04 * coat), coatRoughness, N, band, banded);
    if (sheen > 0.0) {
        float facing = 1.0 - max(dot(N, nb_view), 0.0);
        c += nb_linear_to_srgb(sheenTint.rgb) * sheen * pow(facing, 4.0) * mix(ambientColor, sunColor, band);
    }
    c += nb_linear_to_srgb(max(emission.rgb * emissionStrength, 0.0)) * emissiveScale;
    alpha = clamp(alpha, 0.0, 1.0);
    vec3 through = vec3(1.0 - alpha) + vec3(transmission * 0.6 * alpha);
    return Closure(c * alpha, through, N, fill, 1.0);
}

Closure nb_bsdf_glossy(vec4 color, float roughness, vec3 Nbl, bool banded) {
    vec3 N = normalize(nb_gl(Nbl));
    float band = nb_band(dot(N, toSun), banded) * sunVisibility(nb_worldPos, N, dot(N, toSun));
    vec3 c = nb_light_specular(nb_linear_to_srgb(max(color.rgb, 0.0)), roughness, N, band, banded);
    return Closure(c, vec3(0.0), N, 1.0 - band, 1.0);
}

Closure nb_bsdf_toon(vec4 color, float size, float smoothness, vec3 Nbl, bool glossy) {
    vec3 N = normalize(nb_gl(Nbl));
    float maxAngle = clamp(size, 0.0, 1.0) * NB_PI * 0.5;
    float smoothAngle = clamp(smoothness, 0.0, 1.0) * NB_PI * 0.5;
    float cosine = glossy ? dot(N, normalize(toSun + nb_view)) : dot(N, toSun);
    float angle = acos(clamp(cosine, -1.0, 1.0));
    float intensity = angle < maxAngle ? 1.0 : (angle < maxAngle + smoothAngle ? 1.0 - (angle - maxAngle) / max(smoothAngle, 1e-6) : 0.0);
    float band = intensity * sunVisibility(nb_worldPos, N, dot(N, toSun));
    vec3 albedo = nb_linear_to_srgb(max(color.rgb, 0.0));
    vec3 c = bake != 0 ? albedo : glossy ? albedo * sunColor * band : albedo * mix(ambientColor, sunColor, band);
    return Closure(c, vec3(0.0), N, 1.0 - band, 1.0);
}

Closure nb_bsdf_translucent(vec4 color, vec3 Nbl) {
    vec3 N = normalize(nb_gl(Nbl));
    float back = clamp(-dot(N, toSun), 0.0, 1.0);
    vec3 albedo = nb_linear_to_srgb(max(color.rgb, 0.0));
    return Closure(bake != 0 ? albedo : albedo * mix(ambientColor, sunColor, back), vec3(0.0), N, 1.0 - back, 1.0);
}

Closure nb_bsdf_glass(vec4 color, float roughness, float ior, vec3 Nbl, bool banded) {
    vec3 N = normalize(nb_gl(Nbl));
    float cosTheta = clamp(dot(N, nb_view), 0.0, 1.0);
    float r0 = pow((1.0 - ior) / (1.0 + ior), 2.0);
    float fresnel = r0 + (1.0 - r0) * pow(1.0 - cosTheta, 5.0);
    float band = nb_band(dot(N, toSun), banded) * sunVisibility(nb_worldPos, N, dot(N, toSun));
    vec3 c = nb_light_specular(vec3(1.0), roughness, N, band, banded) * fresnel;
    return Closure(c, nb_linear_to_srgb(max(color.rgb, 0.0)) * (1.0 - fresnel), N, 1.0, 1.0);
}

Closure nb_emission(vec4 color, float strength) {
    return Closure(nb_linear_to_srgb(max(color.rgb * strength, 0.0)) * emissiveScale, vec3(0.0), vec3(0.0), 0.0, 0.0);
}

Closure nb_transparent(vec4 color) {
    return Closure(vec3(0.0), max(color.rgb, 0.0), vec3(0.0), 0.0, 0.0);
}

/** Shader to RGB: the lit colour, back in scene-linear so the nodes after it see what Blender's would. */
vec4 nb_shader_to_rgb(Closure c) {
    float alpha = clamp(1.0 - dot(c.transmittance, vec3(1.0 / 3.0)), 0.0, 1.0);
    return vec4(nb_srgb_to_linear(max(c.radiance, 0.0)), alpha);
}

// Fragment-wide inputs the node code reads.
vec3 nb_P;       // world position, Blender space
vec3 nb_N;       // shading normal, Blender space
vec3 nb_Ng;      // geometric normal, Blender space
vec3 nb_I;       // towards the eye, Blender space
vec3 nb_T;       // tangent, Blender space
vec3 nb_B;       // bitangent, Blender space
vec3 nb_Pobj;    // object space position, Blender space
vec3 nb_Nobj;    // object space normal, Blender space
vec3 nb_generated;
float nb_backfacing;

float nb_fresnel_cos(float cosi, float eta) {
    float c = abs(cosi);
    float g = eta * eta - 1.0 + c * c;
    if (g > 0.0) {
        g = sqrt(g);
        float a = (g - c) / (g + c);
        float b = (c * (g + c) - 1.0) / (c * (g - c) + 1.0);
        return 0.5 * a * a * (1.0 + b * b);
    }
    return 1.0;
}

float nb_fresnel(float ior, vec3 N) {
    float eta = max(ior, 0.00001);
    return nb_fresnel_cos(dot(nb_I, normalize(N)), gl_FrontFacing ? eta : 1.0 / eta);
}

vec2 nb_layer_weight(float blend, vec3 N) {
    N = normalize(N);
    float eta = max(1.0 - blend, 0.00001);
    float fresnel = nb_fresnel_cos(dot(nb_I, N), gl_FrontFacing ? 1.0 / eta : eta);
    float facing = abs(dot(nb_I, N));
    if (blend != 0.5) {
        blend = clamp(blend, 0.0, 0.99999);
        blend = blend < 0.5 ? 2.0 * blend : 0.5 / (1.0 - blend);
        facing = pow(facing, blend);
    }
    return vec2(fresnel, 1.0 - facing);
}

/** Blender's Bump: the height's screen-space gradient turned into a perturbed normal (Mikkelsen's method). */
vec3 nb_bump(float strength, float dist, float height, vec3 N, float invert) {
    N = normalize(N);
    dist *= gl_FrontFacing ? invert : -invert;
    vec3 dPdx = dFdx(nb_P), dPdy = dFdy(nb_P);
    vec3 Rx = cross(dPdy, N), Ry = cross(N, dPdx);
    float det = dot(dPdx, Rx);
    vec3 surfgrad = dFdx(height) * Rx + dFdy(height) * Ry;
    vec3 result = normalize(abs(det) * N - dist * sign(det) * surfgrad + vec3(1e-12));
    return normalize(mix(N, result, max(strength, 0.0)));
}

// @NODE_UNIFORMS@

// @NODE_FUNCTIONS@

const float nb_bayer[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

void main() {
    vec3 worldPos = v_worldPos;
    nb_worldPos = worldPos;
    nb_view = normalize(viewPos - worldPos);
    vec3 geometric = normalize(v_worldNormal);
    nb_backfacing = gl_FrontFacing ? 0.0 : 1.0;
    if (!gl_FrontFacing) geometric = -geometric;
    nb_P = nb_bl(worldPos);
    nb_N = nb_bl(geometric);
    nb_Ng = nb_bl(normalize(cross(dFdx(worldPos), dFdy(worldPos))));
    if (dot(nb_Ng, nb_N) < 0.0) nb_Ng = -nb_Ng;
    nb_I = nb_bl(nb_view);
    vec3 t = v_tangent.xyz - geometric * dot(geometric, v_tangent.xyz);
    if (dot(t, t) < 1e-10) {
        // No tangent from the mesh: derive one from the UV gradients, as Blender would from the UV map.
        vec3 dp1 = dFdx(worldPos), dp2 = dFdy(worldPos);
        vec2 duv1 = dFdx(v_uv), duv2 = dFdy(v_uv);
        t = dp1 * duv2.y - dp2 * duv1.y;
        t = t - geometric * dot(geometric, t);
        if (dot(t, t) < 1e-12) t = abs(geometric.y) < 0.99 ? cross(vec3(0.0, 1.0, 0.0), geometric) : vec3(1.0, 0.0, 0.0);
    }
    t = normalize(t);
    float handedness = v_tangent.w < 0.0 ? -1.0 : 1.0;
    nb_T = nb_bl(t);
    nb_B = nb_bl(cross(geometric, t) * handedness);
    nb_Pobj = v_localPos;
    nb_Nobj = normalize(v_localNormal);
    nb_generated = (v_localPos - texspaceLocation) / max(texspaceSize, vec3(1e-6)) * 0.5 + 0.5;

    Closure surface = nb_closure_none();
    // @NODE_BODY@

    float alpha = clamp(1.0 - dot(surface.transmittance, vec3(1.0 / 3.0)), 0.0, 1.0);
    if (blended == 0) {
        if (dithered == 1) {
            ivec2 pixel = ivec2(gl_FragCoord.xy) & 3;
            if (alpha <= (nb_bayer[pixel.y * 4 + pixel.x] + 0.5) / 16.0) discard;
        } else if (alpha < alphaThreshold) {
            discard;
        }
    }
    // Closures carry colour already weighted by coverage; undo that for what is drawn.
    vec3 color = alpha > 1e-4 ? surface.radiance / alpha : surface.radiance;
    if (bake != 0) {
        vec3 shading = surface.weight > 0.0 ? normalize(surface.normal / surface.weight + vec3(1e-6)) : geometric;
        vec3 b = cross(geometric, t) * handedness;
        vec3 tangentSpace = normalize(vec3(dot(shading, t), dot(shading, b), dot(shading, geometric)));
        fragColor = bake == 1 ? vec4(clamp(color, 0.0, 1.0), alpha) : vec4(tangentSpace * 0.5 + 0.5, 1.0);
        fragGeometry = vec4(0.0);
        return;
    }
    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, saturation);
    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);
    if (blended == 1) {
        // Blended: the transmitted part lets what is behind through, tinted.
        fragColor = vec4(color, alpha * (1.0 - fog));
    } else {
        fragColor = vec4(color, 1.0);
    }
    vec3 normal = surface.weight > 0.0 ? normalize(surface.normal / surface.weight + vec3(1e-6)) : geometric;
    float fill = surface.weight > 0.0 ? surface.fill / surface.weight : 0.0;
    fragGeometry = vec4(normal * 0.5 + 0.5, clamp(fill, 0.0, 1.0));
}
