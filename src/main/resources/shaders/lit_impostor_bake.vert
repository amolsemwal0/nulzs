#version 330 core

// Bakes a prop's side view for LitImpostor: an orthographic camera looking down -z, x to the right, y up.
// A part whose cards face the camera in the game (the anime leaves, foliage.vert's billboard) has them turned to
// face this camera the same way — about the centre carried in the tangent, by the same angle of its own — so the
// picture shows the crown as the game draws it close up.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec3 inTint;
layout(location = 4) in vec3 inTangent;

uniform mat4 projection;
uniform int billboard;

out vec2 uv;
out vec3 normal;
out vec3 tint;

void main() {
    vec3 position = inPosition;
    if (billboard == 1) {
        float half = length(inPosition - inTangent) * 0.70710678;
        vec2 corner = (vec2(inUv.x, 1.0 - inUv.y) * 2.0 - 1.0) * half;
        // As foliage.vert turns it.
        float turn = (fract(sin(dot(inTangent, vec3(12.9898, 78.233, 37.719))) * 43758.5453) - 0.5) * 1.0;
        corner = mat2(cos(turn), sin(turn), -sin(turn), cos(turn)) * corner;
        position = inTangent + vec3(corner, 0.0);
    }
    uv = inUv;
    normal = inNormal;
    tint = inTint;
    gl_Position = projection * vec4(position, 1.0);
}
