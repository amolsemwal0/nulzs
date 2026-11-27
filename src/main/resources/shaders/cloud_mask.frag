#version 330 core

// A cloud as the sun sees it (SkyDome's sun mask): only how much it blocks, premultiplied for stacking.

in vec2 vUv;
in float vAlpha;
in vec3 vDirection;
out vec4 fragColor;

uniform sampler2D atlas;

void main() {
    float a = texture(atlas, vUv).a * vAlpha;
    fragColor = vec4(a);
}
