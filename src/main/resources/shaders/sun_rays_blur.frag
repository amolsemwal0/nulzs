#version 330 core

// Smooths the sun rays one way at a time (across, then down), keeping to surfaces at about the same depth so a shaft
// behind a building does not bleed over the building's edge. See SunRays.java.

in vec2 uv;
out float scatter;

uniform sampler2D rays;
uniform sampler2D sceneDepth;
// One texel of the rays' target, in the direction this pass blurs.
uniform vec2 texelStep;
uniform float near;
uniform float far;

float linearDepth(float depth) {
    float z = depth * 2.0 - 1.0;
    return 2.0 * near * far / (far + near - z * (far - near));
}

void main() {
    const float WEIGHTS[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
    float centre = linearDepth(texture(sceneDepth, uv).r);
    float total = texture(rays, uv).r * WEIGHTS[0];
    float weights = WEIGHTS[0];
    for (int i = 1; i < 5; i++) {
        for (int side = -1; side <= 1; side += 2) {
            vec2 at = uv + texelStep * float(i * side) * 1.5;
            float there = linearDepth(texture(sceneDepth, at).r);
            // Full weight within a tenth of the depth, none past a third of it.
            float similar = 1.0 - smoothstep(0.1, 0.33, abs(there - centre) / max(centre, 1e-3));
            float weight = WEIGHTS[i] * similar;
            total += texture(rays, at).r * weight;
            weights += weight;
        }
    }
    scatter = total / weights;
}
