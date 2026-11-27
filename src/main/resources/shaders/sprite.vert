#version 330 core

// 2D world-space quads from SpriteBatch. The camera's orthographic matrix does all the work, so a sprite's
// position, rotation and scale are already baked into its four corners on the CPU.
layout (location = 0) in vec2 aPosition;
layout (location = 1) in vec2 aUv;
layout (location = 2) in vec4 aColor;

uniform mat4 projection;

out vec2 vUv;
out vec4 vColor;

void main() {
    gl_Position = projection * vec4(aPosition, 0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
}
