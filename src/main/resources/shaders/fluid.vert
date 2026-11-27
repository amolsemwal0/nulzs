#version 330 core

// A fluid surface vertex. Unlike the height field this replaced, the surface is a real volume boundary, so
// a vertex carries a normal: top faces point up, and the side faces of a waterfall or the lip of a pool
// point outward. The depth is how much fluid is stacked below it (the shallow-to-deep tint) and the index
// selects which entry of the appearance palette this fluid uses.
//
// The mesh is rebuilt in world space every frame from the sim's wet cells, so there is no model matrix.

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in float depth;
layout(location = 3) in float fluidIndex;
// The simulation's own horizontal velocity for this cell, in world units per second. It is what lets the
// surface look like it is going somewhere: ripples scroll along it and whitewater appears where it is
// fastest, so a river and a pond are told apart by the water rather than by their setting.
layout(location = 4) in vec2 flow;

uniform mat4 view;
uniform mat4 projection;

out vec3 worldPos;
out vec3 faceNormal;
out float vDepth;
out vec2 vFlow;
flat out int vFluid;

void main() {
    worldPos = position;
    faceNormal = normal;
    vDepth = depth;
    vFlow = flow;
    // Flat, not interpolated: the index picks a palette entry, and interpolating across a triangle whose
    // corners were different fluids would index something in between that no fluid owns.
    vFluid = int(fluidIndex + 0.5);
    gl_Position = projection * view * vec4(position, 1.0);
}
