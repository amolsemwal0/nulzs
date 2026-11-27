#version 330 core

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUV;
layout(location = 3) in vec3 inTangent;
layout(location = 4) in ivec4 inBoneIndices;
layout(location = 5) in vec4 inBoneWeights;

const int MAX_BONES = 200;
layout(std140) uniform BoneMatrices {
    mat4 boneMatrices[MAX_BONES];
};

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform float outlineThickness;

// For outline.frag, which takes the line's hue from the albedo it borders.
out vec2 uv;

// --- Skinning switch ------------------------------------------------------------------------------
// The same uniform switch terrain.vert carries for instancing, for the same reason: GLSL 330 has no
// #include, so a second copy of this file for unskinned geometry would be a duplicate to keep in step —
// and a drifted copy shows as a sword lit differently from the hand holding it.
//
// It exists for SOCKETS. An attachment — a weapon, a tool, a ring — is rigid: it is placed by its socket's
// matrix and must not be skinned again, and it cannot simply be given identity bone weights either,
// because the bone UBO it would read holds the CHARACTER's pose, so bone 0 is somebody's hip.
uniform int skinned;

mat4 skinMatrixOf(ivec4 indices, vec4 weights) {
    if (skinned == 0) {
        return mat4(1.0);
    }
    return boneMatrices[indices.x] * weights.x
         + boneMatrices[indices.y] * weights.y
         + boneMatrices[indices.z] * weights.z
         + boneMatrices[indices.w] * weights.w;
}

void main() {
    mat4 skinMatrix = skinMatrixOf(inBoneIndices, inBoneWeights);

    vec4 skinnedPosition = skinMatrix * vec4(inPosition, 1.0);
    vec3 skinnedNormal = mat3(skinMatrix) * inNormal;

    vec4 worldPosition = model * skinnedPosition;
    vec3 worldNormal = normalize(mat3(model) * skinnedNormal);

    // Scale the hull expansion by the vertex's distance from the camera so the outline holds a roughly
    // constant screen width — thin and even like Genshin's, instead of chunky up close and vanishing far
    // off, which a fixed world-space expansion gives. Expanding by (thickness · distance) projects to a
    // screen size proportional to (thickness · distance) / distance = thickness, i.e. constant.
    float distance = -(view * worldPosition).z;
    worldPosition.xyz += worldNormal * outlineThickness * distance;
    uv = inUV;

    gl_Position = projection * view * worldPosition;
}
