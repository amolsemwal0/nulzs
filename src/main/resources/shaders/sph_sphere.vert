#version 330 core

// One particle as a camera-facing quad, expanded from gl_VertexID with no vertex buffer of its own — the
// same attributeless trick the skybox and the fullscreen passes use, so a particle costs five floats of
// instance data and nothing else.
//
// The quad is built in VIEW space, which is what makes it face the camera for free: a billboard is only
// hard in world space, and here the sphere the fragment shader reconstructs is defined about the view-space
// centre anyway. Nothing on the CPU orients anything.
//
// Shared by the depth pass and the thickness pass. They disagree about what to write and agree exactly
// about where the sphere is, so a second copy of this would be a copy free to drift — and a thickness pass
// that placed its spheres a fraction differently from the depth pass draws a fluid whose shading and whose
// opacity describe two different surfaces.

layout(location = 0) in vec4 instance;   // xyz = centre in world space, w = radius
layout(location = 1) in float fluidIndex;

uniform mat4 view;
uniform mat4 projection;

out vec3 vEyeCenter;
out float vRadius;
out float vFluid;
out vec2 vOffset;

const vec2 CORNERS[6] = vec2[6](
    vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
);

void main() {
    vec2 corner = CORNERS[gl_VertexID];
    vOffset = corner;
    vRadius = instance.w;
    vFluid = fluidIndex;
    vEyeCenter = (view * vec4(instance.xyz, 1.0)).xyz;

    // Pushed toward the camera by its own radius so the quad still covers the sphere's near cap when the
    // camera is close enough for perspective to matter. Without it a particle the camera is nearly inside
    // has its front clipped away by its own billboard.
    vec3 eyePos = vEyeCenter + vec3(corner * vRadius, vRadius);
    gl_Position = projection * vec4(eyePos, 1.0);
}
