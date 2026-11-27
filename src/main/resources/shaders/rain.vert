#version 330 core

// A rain streak is a two-vertex line in world space, so the view/projection give it real perspective:
// a drop near the camera is a long streak, a distant one a short dash. The alpha attribute fades each
// streak from a bright head to a faint tail, which is what reads as a falling drop rather than a stick.

layout(location = 0) in vec3 position;
layout(location = 1) in float alpha;

uniform mat4 view;
uniform mat4 projection;

out float vAlpha;

void main() {
    gl_Position = projection * view * vec4(position, 1.0);
    vAlpha = alpha;
}
