#version 330 core

// Vertex stage of every material compiled from Blender shader nodes (see ShaderCompiler). Meshes are in the
// engine's space (Y up); the node graph works in Blender's (Z up), so this also hands on the object-space position
// and normal converted back, which is what Blender's Object and Generated coordinates are measured in.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec4 inColor;
layout(location = 4) in vec4 inTangent;
layout(location = 5) in mat4 inModel;
layout(location = 9) in vec3 inInstance;
layout(location = 10) in vec4 inAttr0;
layout(location = 11) in vec4 inAttr1;
layout(location = 12) in vec4 inAttr2;
layout(location = 13) in vec4 inAttr3;
layout(location = 14) in vec4 inAttr4;
layout(location = 15) in vec4 inAttr5;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform int instanced;
/** For a single draw, what instances carry per instance: x is the object's random value. */
uniform vec3 objectData;

out vec3 v_worldPos;
out vec3 v_worldNormal;
out vec3 v_localPos;
out vec3 v_localNormal;
out vec2 v_uv;
out vec4 v_color;
out vec4 v_tangent;
out vec3 v_instance;
out vec4 v_attr0;
out vec4 v_attr1;
out vec4 v_attr2;
out vec4 v_attr3;
out vec4 v_attr4;
out vec4 v_attr5;

vec3 toBlender(vec3 v) { return vec3(v.x, -v.z, v.y); }

void main() {
    mat4 m = instanced == 1 ? inModel : model;
    vec4 world = m * vec4(inPosition, 1.0);
    mat3 normalMatrix = transpose(inverse(mat3(m)));
    v_worldPos = world.xyz;
    v_worldNormal = normalize(normalMatrix * inNormal);
    v_localPos = toBlender(inPosition);
    v_localNormal = toBlender(inNormal);
    v_uv = inUv;
    v_color = inColor;
    v_tangent = vec4(normalize(mat3(m) * inTangent.xyz + vec3(1e-8)), inTangent.w);
    v_instance = instanced == 1 ? inInstance : objectData;
    v_attr0 = inAttr0;
    v_attr1 = inAttr1;
    v_attr2 = inAttr2;
    v_attr3 = inAttr3;
    v_attr4 = inAttr4;
    v_attr5 = inAttr5;
    gl_Position = projection * view * world;
}
