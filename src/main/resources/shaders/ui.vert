#version 330 core

// Vertices arrive in *pixel* coordinates with the origin at the top-left, which is how UI layout is
// naturally written; the conversion to NDC lives here so no widget ever has to think about it.
layout (location = 0) in vec2 aPosition;
layout (location = 1) in vec2 aUv;
layout (location = 2) in vec4 aColor;

uniform vec2 screenSize;

out vec2 vUv;
out vec4 vColor;

void main() {
    vec2 ndc = vec2(
        aPosition.x / screenSize.x * 2.0 - 1.0,
        1.0 - aPosition.y / screenSize.y * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
}
