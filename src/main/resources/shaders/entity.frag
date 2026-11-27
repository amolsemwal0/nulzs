#version 330 core

in vec3 worldPos;
in vec3 worldNormal;
in vec2 uv;
in vec3 tint;

out vec4 fragColor;

uniform sampler2D albedoTexture;

// The material's own opacity: 1 for solid parts, below 1 for a translucent shell. Taken from the
// model's base-colour alpha rather than chosen here, so a re-exported asset changes how it looks
// without anyone editing a shader.
uniform float opacity;

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

void main() {
    vec3 albedo = texture(albedoTexture, uv).rgb * tint;

    // Values above 1 are emissive, exactly as in terrain.frag: a tint brighter than white is how a lava
    // or fire creature glows through its own shadow and blooms in the post pass, with no separate flag
    // and no second shader path. Ordinary tints are <= 1 and this term is zero for them.
    vec3 emissive = max(albedo - vec3(1.0), vec3(0.0));
    albedo = min(albedo, vec3(1.0));

    vec3 normal = normalize(worldNormal);
    vec3 toView = normalize(viewPos - worldPos);
    float NdotL = dot(normal, toSun);

    // The same four-step band as terrain.frag and foliage.frag. A creature standing on that ground has to
    // share its response curve or it reads as pasted onto the scene rather than lit by it.
    float band = smoothstep(0.26, 0.32, NdotL) * 0.34
               + smoothstep(0.58, 0.64, NdotL) * 0.33
               + smoothstep(0.83, 0.89, NdotL) * 0.33;
    band *= sunVisibility(worldPos, normal, NdotL);

    vec3 light = mix(ambientColor, sunColor, band);
    vec3 color = albedo * light;

    // Fresnel: how edge-on this fragment is. It does double duty below — the silhouette of a blob of gel
    // is both its brightest part and its most opaque, because that is where a view ray passes through the
    // most material. Getting both from one term is what makes the shape read as translucent rather than
    // as a flat decal with a border.
    float NdotV = max(dot(normal, toView), 0.0);
    float fresnel = pow(1.0 - NdotV, 3.0);

    float rim = pow(1.0 - NdotV, rimPower);
    rim = smoothstep(rimThreshold - 0.1, rimThreshold + 0.1, rim) * (0.3 + 0.7 * band);
    // Tinted by the creature's own colour rather than only by the scene's rim colour, so a purple slime
    // rims purple. Pushed above 1 so it survives tone-mapping as a highlight, like the scene's other
    // rim terms.
    color += (rimColor * 0.5 + tint * 0.9) * rim * 0.35;

    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, saturation);
    color += emissive;

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    // Edge-on fragments turn nearly solid. A constant alpha over the whole shape makes it look like
    // tinted glass cut to the outline; thickening at the silhouette is what a translucent volume does.
    float alpha = clamp(opacity + (1.0 - opacity) * fresnel * 0.9, 0.0, 1.0);
    // Fully fogged geometry must not paint fog over the sky it has already dissolved into.
    alpha *= 1.0 - fog;

    fragColor = vec4(color, alpha);
}
