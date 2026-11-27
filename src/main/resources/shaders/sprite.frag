#version 330 core

in vec2 vUv;
in vec4 vColor;

uniform sampler2D spriteTexture;

out vec4 fragColor;

void main() {
    // Tinted rather than replaced: white leaves the art as authored, and untextured shapes sample a
    // 1x1 white texture so they come out exactly the vertex colour.
    vec4 color = texture(spriteTexture, vUv) * vColor;
    if (color.a < 0.004) {
        discard;
    }
    fragColor = color;
}
