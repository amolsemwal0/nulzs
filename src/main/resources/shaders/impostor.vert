#version 330 core

// A distant prop, drawn as one camera-facing quad carrying a sprite baked from its own mesh.
//
// The entire geometry is a unit quad uploaded once; a forest is a stream of eight-float placements over it.
// That is the whole point: every prop in this world is otherwise one draw call, and past the swap distance
// they outnumber the near ones roughly twenty to one.

// Per-vertex: the unit quad. x spans [-1, 1], y spans [0, 1] with the feet at 0.
layout(location = 0) in vec2 inCorner;

// Per-instance (divisor 1).
layout(location = 1) in vec4 inPlacement;   // xyz = where the feet sit, w = half width
layout(location = 2) in vec4 inSize;        // x = height, yzw = tint

uniform mat4 view;
uniform mat4 projection;

// The camera's horizontal right vector. Y is deliberately excluded, exactly as the grass billboard does it:
// a standing prop turns about its own trunk and must never tilt, or looking down on a wood lays every tree
// over on its back.
uniform vec3 cameraRight;

out vec3 worldPos;
out vec2 uv;
out vec3 tint;

void main() {
    float halfWidth = inPlacement.w;
    float height = inSize.x;

    vec3 world = inPlacement.xyz
            + cameraRight * (inCorner.x * halfWidth)
            + vec3(0.0, inCorner.y * height, 0.0);

    // The sprite is a square framed on the prop's larger dimension with its feet at the bottom edge, so
    // the quad has to sample the matching sub-rectangle rather than the whole image, or a tree wider than
    // it is tall comes out squashed. Both sides derive the framing from the same two numbers.
    float extent = max(height, halfWidth * 2.0);
    uv = vec2(0.5 + inCorner.x * halfWidth / extent, inCorner.y * height / extent);

    worldPos = world;
    tint = inSize.yzw;
    gl_Position = projection * view * vec4(world, 1.0);
}
