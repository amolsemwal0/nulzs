#version 330 core

// A single triangle that covers the screen, generated from gl_VertexID with no vertex buffer — the same
// attributeless trick the skybox uses. Three vertices at (-1,-1),(3,-1),(-1,3) in NDC over-cover the
// [-1,1] square; the fragment shader only ever sees the visible portion, and its uv runs 0..1 across it.

out vec2 uv;

void main() {
    uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
