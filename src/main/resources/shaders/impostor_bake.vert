#version 330 core

// Baking a prop's side elevation into a sprite. Orthographic and unlit: what is wanted is the object's
// *material*, not one frozen lighting solution, so the runtime billboard can be lit by the same cel bands
// as the real mesh and answer the sun and the time of day. Baking a lit prop would nail one sun angle into
// everything past the swap distance, and the swap would show as a lighting discontinuity travelling with
// the player.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;

uniform mat4 projection;

out vec2 uv;
out vec3 normal;

void main() {
    uv = inUv;
    normal = inNormal;
    gl_Position = projection * vec4(inPosition, 1.0);
}
