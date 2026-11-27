#version 330 core

in float vAlpha;
out vec4 fragColor;

uniform vec3 color;
uniform float intensity;   // overall opacity, so snow fades in and out with the weather rather than popping

void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(d, d);
    if (r2 >= 1.0) {
        discard;
    }
    float soft = 1.0 - r2;
    fragColor = vec4(color, vAlpha * soft * soft * intensity);
}
