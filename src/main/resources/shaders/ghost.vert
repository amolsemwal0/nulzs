#version 330 core

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec3 inColor;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform mat3 normalMatrix;

out vec3 worldPos;
out vec3 worldNormal;
out vec3 vertexColor;

void main() {
    vec4 world = model * vec4(inPosition, 1.0);
    worldPos = world.xyz;
    worldNormal = normalize(normalMatrix * inNormal);
    vertexColor = inColor;
    gl_Position = projection * view * world;
}
