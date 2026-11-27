#version 330 core

// A cloud's corner on the dome round the camera (SkyDome): only the camera's turn applies.

layout(location = 0) in vec3 position;
layout(location = 1) in vec2 texCoord;
layout(location = 2) in float alpha;

uniform mat4 view;         // the camera's turn, without its position
uniform mat4 projection;

out vec2 vUv;
out float vAlpha;
out vec3 vDirection;       // which way this point of the cloud is from the camera, not yet normalised

void main() {
    vUv = texCoord;
    vAlpha = alpha;
    vDirection = position;
    gl_Position = projection * view * vec4(position, 1.0);
}
