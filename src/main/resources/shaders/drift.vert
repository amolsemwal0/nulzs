#version 330 core

// A drifting speck is one point sprite, sized in world units so perspective shrinks it with distance: a flake near
// the camera is a big soft blob, one far away a fine grain.

layout(location = 0) in vec3 position;
layout(location = 1) in float scale;
layout(location = 2) in float alpha;

uniform mat4 view;
uniform mat4 projection;
uniform float size;            // world-space diameter of an average speck
uniform float viewportHeight;  // pixels
uniform float maxPixels;       // so a flake brushing the lens does not cover the screen

out float vAlpha;

void main() {
    vec4 viewPos = view * vec4(position, 1.0);
    gl_Position = projection * viewPos;
    float pixelsPerUnit = viewportHeight * projection[1][1] * 0.5;
    float pixels = size * scale * pixelsPerUnit / max(0.25, -viewPos.z);
    gl_PointSize = clamp(pixels, 1.0, maxPixels);
    // A speck smaller than a pixel fades instead of shimmering as a whole pixel.
    vAlpha = alpha * clamp(pixels, 0.0, 1.0);
}
