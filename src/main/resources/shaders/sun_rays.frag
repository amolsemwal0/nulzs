#version 330 core

// Sun rays: shafts of sunlight standing in the low air, slanting down from the sun. Drawn at half the screen's size;
// blurred (sun_rays_blur.frag) and then added to the picture in the composite, tinted by the sun's colour. See
// SunRays.java.
//
// This pixel's view ray is walked in even steps; at each, the air glows if sunlight reaches it.
//
// Under a cloud sky (SkyDome's sun mask) only real things stop the light: the clouds between the air and the sun, and
// whatever the sun's shadow map says stands in the way, trees, roofs and hills. And the light shows as shafts only
// where some of the air along the ray is in shadow: in a forest, between the trunks under the canopy; at a cloud
// shadow's edge; past the end of a roof. A ray through air that is all in sunlight gives nothing, as the eye sees no
// shafts in open sunshine, only a clear day.
//
// Without a cloud sky the sunlight reaches the air in a soft pattern of patches across its path (fixed in the world,
// drifting slowly, as through a canopy), each lighting a column of air along the sun's direction, cut by the shadow map.

in vec2 uv;
out float scatter;

uniform sampler2D sceneDepth;
uniform sampler2DShadow shadowMap;
uniform mat4 lightSpaceMatrix;
uniform float shadowBias;

uniform mat4 invViewProjection;
uniform vec3 cameraPosition;
uniform vec3 toSun;

// How far along the ray the air is walked, at most.
uniform float maxDistance;
// The air glows thickest at ground level and thins above it, by e over every hazeHeight.
uniform float groundY;
uniform float hazeHeight;
// The shafts: how wide a patch of light is across the sunlight (world units), how much of the sunlight comes through
// as shafts (0..1, a threshold on the pattern), and how far the pattern has drifted (world units).
uniform float shaftSize;
uniform float shaftCover;
uniform vec2 drift;
// How strongly the air scatters light on forward, toward the eye when looking at the sun: 0 evenly, every way.
uniform float anisotropy;
uniform int steps;
// With a cloud sky (SkyDome), the shafts are the light its clouds let through instead of the pattern above: the sun's
// mask of them (0 clear to 1 cloud, mipmapped) and the matrix taking a direction to it. A spot of air looks up toward
// the sun as if the clouds were cloudReach away, so spots side by side see different parts of the clouds near the sun,
// and each gap between them lights its own shaft.
uniform int hasCloudMask;
uniform sampler2D cloudMask;
uniform mat4 cloudMaskViewProjection;
uniform float cloudReach;
// With a cloud sky, the air counts in full out to nearReach from the focus (the player, across the ground) and fades
// by e every nearFade after: the shafts are the near air's, and farther off the light lies on the air all alike, a veil.
uniform vec3 focus;
uniform float nearReach;
uniform float nearFade;
// With a cloud sky, a spot of sunlit air glows only as much as the air round it, shaftWidth away on the level, is in
// shadow: a narrow beam through a gap glows, the open air of a sunny clearing does not.
uniform float shaftWidth;

// A pixel's own fraction of a step to start the walk at, so neighbours sample the air at different depths and the blur
// after turns what would be bands into smooth light. Interleaved gradient noise: even, and the same every frame, so the
// shafts do not crawl.
float jitter(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Smooth value noise, 0..1.
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

// How much sunlight comes through as a shaft at this spot across the sunlight's path, 0..1: soft patches.
float shaft(vec2 across) {
    vec2 p = across / shaftSize;
    float n = 0.65 * noise(p) + 0.35 * noise(p * 2.3 + 17.0);
    return smoothstep(1.0 - shaftCover, 1.0 - shaftCover + 0.22, n);
}

// How much sunlight the clouds let through to this spot of air, 0..1.
float throughClouds(vec3 position) {
    vec3 seen = normalize(position - cameraPosition + toSun * cloudReach);
    vec4 clip = cloudMaskViewProjection * vec4(seen, 1.0);
    if (clip.w <= 0.0) {
        return 1.0;
    }
    return 1.0 - texture(cloudMask, clip.xy / clip.w * 0.5 + 0.5).r;
}

// How much the sun's shadow map lets through to a spot, 1 past its far end.
float sunAt(vec3 position) {
    vec4 lightClip = lightSpaceMatrix * vec4(position, 1.0);
    vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    return proj.z > 1.0 ? 1.0 : texture(shadowMap, vec3(proj.xy, proj.z - shadowBias));
}

// The Henyey-Greenstein phase function, scaled so that scattering evenly every way is 1.
float phase(float cosine, float g) {
    float denominator = 1.0 + g * g - 2.0 * g * cosine;
    return (1.0 - g * g) / (denominator * sqrt(denominator));
}

void main() {
    float depth = texture(sceneDepth, uv).r;
    vec4 hit = invViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 end = hit.xyz / hit.w;
    vec3 ray = end - cameraPosition;
    float rayLength = length(ray);
    vec3 direction = ray / max(rayLength, 1e-4);
    // The sky: as far as the walk goes.
    float walk = depth >= 1.0 ? maxDistance : min(rayLength, maxDistance);

    // Two directions across the sunlight: every point of a shaft, all the way along the sun's direction, has the same
    // place across it.
    vec3 side = normalize(abs(toSun.y) < 0.95 ? cross(toSun, vec3(0.0, 1.0, 0.0)) : cross(toSun, vec3(1.0, 0.0, 0.0)));
    vec3 up = cross(side, toSun);

    float stepLength = walk / float(steps);
    float offset = jitter(gl_FragCoord.xy);
    float lit = 0.0;
    for (int i = 0; i < steps; i++) {
        vec3 position = cameraPosition + direction * ((float(i) + offset) * stepLength);
        float density = exp(-max(position.y - groundY, 0.0) / hazeHeight);
        if (hasCloudMask == 1) {
            density *= exp(-max(length(position.xz - focus.xz) - nearReach, 0.0) / nearFade);
        }
        if (density < 0.01) {
            continue;
        }
        vec4 lightClip = lightSpaceMatrix * vec4(position, 1.0);
        vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;
        // Outside the shadow map nothing is known about what shades the air, so shafts fade out toward its edge.
        vec2 fromCentre = abs(proj.xy - 0.5) * 2.0;
        float inside = 1.0 - smoothstep(0.75, 1.0, max(fromCentre.x, fromCentre.y));
        // Under a cloud sky, as much light as the clouds let through toward the sun; without one, the pattern.
        float light = hasCloudMask == 1 ? throughClouds(position) : shaft(vec2(dot(position, side), dot(position, up)) + drift);
        if (light <= 0.0 || inside <= 0.0) {
            continue;
        }
        float sun = proj.z > 1.0 ? 1.0 : texture(shadowMap, vec3(proj.xy, proj.z - shadowBias));
        if (hasCloudMask == 1 && sun > 0.0) {
            // A shaft is lit air with shade round it: the share of a ring of spots round it in shadow. The ring is level,
            // so it stays in the air: one tilted across the sunlight would dip into the ground, and the ground's own
            // shadow would make every bit of air near it a shaft.
            float around = 0.0;
            for (int k = 0; k < 6; k++) {
                float a = 1.0471976 * (float(k) + 0.5 * float(i & 1));
                around += sunAt(position + vec3(cos(a), 0.0, sin(a)) * shaftWidth);
            }
            // Only a beam all but surrounded by shade: under an open canopy, where most of the air is lit, the light
            // through it is a glow, not beams.
            sun *= smoothstep(0.55, 0.95, 1.0 - around / 6.0);
        }
        lit += light * sun * density * inside;
    }
    // Mostly forward-scattered, with an even part, so shafts show whichever way the camera looks and brightest toward
    // the sun.
    float scattering = mix(1.0, phase(dot(direction, toSun), anisotropy), 0.4);
    scatter = lit * stepLength * scattering;
}
