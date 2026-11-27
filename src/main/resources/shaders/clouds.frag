#version 330 core

// A painted cloud, lit for the hour: the picture's sunlit parts (its bright tones) take litTint and its shaded parts
// (its blue-violet undersides) shadeTint, blended by how bright the painted tone is, so a cloud painted for noon
// turns gold over violet at sunset and grey under rain. Low over the horizon it fades into the horizon's colour, so a
// heap stands up out of the haze; near the sun it glows, lit through from behind.

in vec2 vUv;
in float vAlpha;
in vec3 vDirection;
out vec4 fragColor;

uniform sampler2D atlas;
uniform vec3 litTint;
uniform vec3 shadeTint;
uniform vec3 hazeColor;
uniform float haze;          // how much the lowest clouds fade into the horizon, 0 to 1
uniform vec3 toSun;
uniform vec3 sunColor;
uniform float sunStrength;

void main() {
    vec4 texel = texture(atlas, vUv);
    vec3 direction = normalize(vDirection);
    // Below the horizon the land or the sea is in front; what shows past a cliff's edge fades out.
    float a = texel.a * vAlpha * smoothstep(-0.06, -0.01, direction.y);
    if (a < 0.004) {
        discard;
    }
    float tone = dot(texel.rgb, vec3(0.2126, 0.7152, 0.0722));
    // The painted shade is about 0.7 bright and the sunlit white 1.0.
    float lit = smoothstep(0.66, 0.97, tone);
    vec3 color = texel.rgb * mix(shadeTint, litTint, lit);
    // Lit through toward the sun: the clouds round it glow its colour, the most where they are thin and bright.
    float toward = max(dot(direction, toSun), 0.0);
    float glow = (pow(toward, 60.0) * 0.4 + pow(toward, 10.0) * 0.08) * sunStrength;
    color = mix(color, mix(vec3(1.0), sunColor, 0.4) * 1.1, clamp(glow * (0.3 + 0.7 * lit), 0.0, 0.45));
    float low = 1.0 - smoothstep(0.0, 0.22, max(direction.y, 0.0));
    fragColor = vec4(mix(color, hazeColor, haze * low * low), a);
}
