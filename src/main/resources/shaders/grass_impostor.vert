#version 330 core

// The tuft's own geometry, drawn once into an offscreen buffer to bake a billboard sprite.
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;

// Orthographic, framing the clump from the side — a billboard stands upright and is only ever seen
// edge-on to its own plane, so a side elevation is the honest projection to bake.
uniform mat4 projection;
uniform float tuftHeight;

out vec2 uv;
out float shade;

void main() {
    uv = inUv;
    // The same root-to-tip ramp grass.vert applies, so a billboard carries the gradient the 3D clump
    // has rather than reading as a flat green cutout the moment it swaps in.
    float heightFraction = clamp(inPosition.y / tuftHeight, 0.0, 1.0);
    float root = heightFraction * heightFraction;
    shade = mix(0.45, 1.0, root);

    gl_Position = projection * vec4(inPosition, 1.0);
}
