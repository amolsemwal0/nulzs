#version 330 core

// A particle billboard vertex: a world-space corner (the CPU already expanded the quad to face the
// camera), the soft-dot texture coordinate, and the per-particle colour with its life-fade in the alpha.

layout(location = 0) in vec3 position;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec4 color;

uniform mat4 view;
uniform mat4 projection;

out vec2 vUv;
out vec4 vColor;

void main() {
    vUv = uv;
    vColor = color;
    gl_Position = projection * view * vec4(position, 1.0);
}
