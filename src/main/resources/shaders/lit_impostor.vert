#version 330 core

// A far prop as one flat picture standing on its feet, turned about the upright to face the camera (LitImpostor).
// The quad is the whole baked frame: `extent` metres square, its bottom edge `bottom` metres from the feet, so the
// picture is drawn exactly the size the prop is.

layout(location = 0) in vec2 inCorner;      // x from -0.5 to 0.5, y from 0 to 1
layout(location = 1) in vec4 inPlacement;   // xyz = where the feet stand, w = the prop's scale

uniform mat4 view;
uniform mat4 projection;
uniform vec3 cameraRight;                   // level: a standing prop does not tilt with the camera
uniform float extent;
uniform float bottom;

out vec3 worldPos;
out vec2 uv;

void main() {
    float scale = inPlacement.w;
    vec3 world = inPlacement.xyz
            + cameraRight * (inCorner.x * extent * scale)
            + vec3(0.0, (bottom + inCorner.y * extent) * scale, 0.0);
    uv = vec2(inCorner.x + 0.5, inCorner.y);
    worldPos = world;
    gl_Position = projection * view * vec4(world, 1.0);
}
