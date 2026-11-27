#version 330 core

// The sky behind a 3D world: the lighting's horizon colour at the horizon, deepening through the middle colour to the
// zenith overhead, and warm round a low sun. The gradient is fluid.frag's skyReflection, line for line, so water
// mirrors the sky that is drawn. Below the horizon (seen past a cliff's edge) it settles toward the horizon colour.
// Then the sun itself, a bright disc in a soft glow, which the clouds drawn after it can cover.

in vec2 uv;
out vec4 fragColor;

uniform mat4 inverseViewProjection;   // of the camera's turn only, no position: the sky is infinitely far
uniform vec3 zenithColor;
uniform vec3 middleColor;
uniform vec3 horizonColor;
uniform vec3 sunDirection;
uniform vec3 toSun;
uniform vec3 sunColor;
uniform float sunStrength;            // 0 at night or under overcast, 1 for a clear day's sun

void main() {
    vec4 far = inverseViewProjection * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize(far.xyz / far.w);
    float altitude = max(rd.y, 0.0);
    float sunAmount = max(dot(rd, sunDirection), 0.0);

    vec3 color = mix(middleColor, zenithColor, smoothstep(0.12, 0.70, altitude));
    color = mix(horizonColor, color, smoothstep(0.0, 0.20, altitude));

    float sunLow = 1.0 - smoothstep(0.0, 0.40, abs(sunDirection.y));
    float horizonBand = pow(1.0 - altitude, 6.0);
    float warmth = pow(sunAmount, 2.5) * sunLow * horizonBand;
    color = mix(color, vec3(1.00, 0.42, 0.16), clamp(warmth * 0.75, 0.0, 0.85));

    // The sun: a disc of about one and a half degrees, past white so it blooms, in a glow that widens round it.
    float toward = max(dot(rd, toSun), 0.0);
    vec3 light = mix(vec3(1.0), sunColor, 0.35);
    float disc = smoothstep(0.99955, 0.99970, toward);
    float glow = pow(toward, 1500.0) * 0.3 + pow(toward, 90.0) * 0.14 + pow(toward, 8.0) * 0.05;
    color = mix(color, light * 1.35, disc * sunStrength);
    color += light * glow * sunStrength;

    color = mix(color, horizonColor, 1.0 - smoothstep(-0.15, 0.02, rd.y));
    fragColor = vec4(color, 1.0);
}
