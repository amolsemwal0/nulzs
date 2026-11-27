#version 330 core

// Vertices arrive already in world space: a decal is rebuilt every frame from the terrain it lies on, so
// there is no rest pose for a model matrix to transform and nothing a local frame would buy.
layout (location = 0) in vec3 aPosition;
// 0 at the middle of the shape, 1 at its rim. This is the *same* normalised distance the edit itself
// uses — Euclidean for a circle, Chebyshev for a square — so the outline drawn here is the boundary of
// the solid that will be subtracted rather than a separately-drawn approximation of it.
layout (location = 1) in float aEdge;

uniform mat4 view;
uniform mat4 projection;

out float vEdge;

void main() {
    gl_Position = projection * view * vec4(aPosition, 1.0);
    vEdge = aEdge;
}
