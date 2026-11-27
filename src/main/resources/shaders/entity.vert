#version 330 core

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec3 inTint;
// Location 4 (tangent) is declared by foliage.vert and left undeclared here: creatures carry no normal
// map, and a VAO's extra enabled attributes are simply ignored by a shader that does not name them —
// the same reason depth_static.vert can serve every mesh class here.

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform mat3 normalMatrix;

// The whole point of this path: one shared mesh drawn many times in different colours. A slime's type is
// this vector and nothing else. It may exceed 1, which the fragment shader treats as emissive.
uniform vec3 instanceTint;

out vec3 worldPos;
out vec3 worldNormal;
out vec2 uv;
out vec3 tint;

void main() {
    vec4 world = model * vec4(inPosition, 1.0);
    worldPos = world.xyz;
    // The normal matrix, not the model matrix: instances are squashed and stretched non-uniformly by the
    // idle animation, and reusing the model matrix would tilt every normal as the creature breathes.
    worldNormal = normalize(normalMatrix * inNormal);
    uv = inUv;
    tint = inTint * instanceTint;

    gl_Position = projection * view * world;
}
