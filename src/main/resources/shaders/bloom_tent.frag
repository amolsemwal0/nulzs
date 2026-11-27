#version 330 core

// A 3x3 tent filter, used for both the downsample and the upsample legs of the bloom chain. Downsampling
// it into ever smaller buffers and tent-filtering back up is what turns a few bright pixels into a wide,
// smooth glow cheaply — far cheaper than one huge Gaussian, and without the boxy steps a plain average
// leaves. On the way up the result is added onto the larger level (the caller uses additive blending), so
// each level contributes its own radius of glow.

in vec2 uv;
out vec4 fragColor;

uniform sampler2D src;
uniform vec2 srcTexel;   // 1 / source resolution

void main() {
    vec3 s =
        texture(src, uv + srcTexel * vec2(-1.0, -1.0)).rgb * 1.0 +
        texture(src, uv + srcTexel * vec2( 0.0, -1.0)).rgb * 2.0 +
        texture(src, uv + srcTexel * vec2( 1.0, -1.0)).rgb * 1.0 +
        texture(src, uv + srcTexel * vec2(-1.0,  0.0)).rgb * 2.0 +
        texture(src, uv + srcTexel * vec2( 0.0,  0.0)).rgb * 4.0 +
        texture(src, uv + srcTexel * vec2( 1.0,  0.0)).rgb * 2.0 +
        texture(src, uv + srcTexel * vec2(-1.0,  1.0)).rgb * 1.0 +
        texture(src, uv + srcTexel * vec2( 0.0,  1.0)).rgb * 2.0 +
        texture(src, uv + srcTexel * vec2( 1.0,  1.0)).rgb * 1.0;
    fragColor = vec4(s / 16.0, 1.0);
}
