#version 330 core

// Depth-only pass for the skinned character.
//
// The skinning has to be repeated here rather than shadowing the bind pose: a walk cycle whose shadow
// stands still is worse than no shadow at all. This reads the same BoneMatrices UBO the toon pass does,
// at the same binding point, so the pose is uploaded once per frame and both passes see it.
layout(location = 0) in vec3 inPosition;
layout(location = 4) in ivec4 inBoneIndices;
layout(location = 5) in vec4 inBoneWeights;

const int MAX_BONES = 200;
layout(std140) uniform BoneMatrices {
    mat4 boneMatrices[MAX_BONES];
};

uniform mat4 model;
uniform mat4 lightSpaceMatrix;

void main() {
    mat4 skinMatrix =
        boneMatrices[inBoneIndices.x] * inBoneWeights.x +
        boneMatrices[inBoneIndices.y] * inBoneWeights.y +
        boneMatrices[inBoneIndices.z] * inBoneWeights.z +
        boneMatrices[inBoneIndices.w] * inBoneWeights.w;

    gl_Position = lightSpaceMatrix * model * skinMatrix * vec4(inPosition, 1.0);
}
