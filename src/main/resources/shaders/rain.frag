#version 330 core

in float vAlpha;
out vec4 fragColor;

uniform vec3 rainColor;
uniform float intensity;   // overall opacity, so rain fades in and out with the weather rather than popping

void main() {
    fragColor = vec4(rainColor, vAlpha * intensity);
}
