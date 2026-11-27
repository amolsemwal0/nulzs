#version 330 core

// Sky beams, the spreading (SunRays.java): each pixel gathers the light along the line from it toward the sun, fainter
// the farther it comes from, so whatever lets the sun's light through draws a beam out from the sun across the picture,
// and whatever blocks it a shadow. Run twice, the second time over a shorter reach, to smooth the steps out. The beams
// are only ever what the clouds and the skyline make of the light: nothing is painted over them.

in vec2 uv;
out float light;

uniform sampler2D source;
uniform vec2 sunUv;         // where the sun is on the screen; may be off it
uniform float reach;        // how much of the way toward the sun a pixel gathers from, 0 to 1
uniform float decay;        // how much of the light is left after each step back toward the sun
uniform int samples;
// On the last pass: fade the beams out with distance from the sun across the picture.
uniform int streaks;
uniform float aspect;       // the screen's width over its height, so the fade is round the sun and not stretched

// A pixel's own start along its line, so neighbours step at different places and the steps blur out.
float jitter(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec2 step = (sunUv - uv) * reach / float(samples);
    vec2 at = uv + step * jitter(gl_FragCoord.xy);
    float falloff = 1.0;
    float total = 0.0;
    float weights = 0.0;
    for (int i = 0; i < samples; i++) {
        total += texture(source, at).r * falloff;
        weights += falloff;
        falloff *= decay;
        at += step;
    }
    light = total / weights;
    if (streaks == 1) {
        // They reach only so far from the sun across the picture, so a sun just off the top of the screen streams light
        // down past the treetops rather than laying it over the whole town.
        vec2 away = (uv - sunUv) * vec2(aspect, 1.0);
        light *= 1.0 - smoothstep(0.22, 0.8, length(away));
    }
}
