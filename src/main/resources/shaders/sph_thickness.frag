#version 330 core

// How much fluid lies along each view ray, accumulated additively over every particle — the second pass of
// screen-space fluid rendering, and the one that decides what the fluid looks like rather than what shape
// it is.
//
// Depth alone gives a surface, and a surface shaded on its own reads as a solid: a splash and a chrome
// blob are the same picture. Thickness is what separates them, because it is the number Beer's law wants —
// light is absorbed in proportion to how far it travelled through the medium, so a thin sheet of water is
// nearly clear and a deep one is saturated with its own colour. It is also the honest source of opacity:
// spray at the edge of a splash is thin, and thin fluid should be see-through.
//
// Deliberately NOT depth-tested against other particles. The depth pass wants the nearest surface and
// nothing behind it; this wants the sum through the whole body, which is exactly what every particle
// contributes to. Depth writes are off and blending is additive.

in vec3 vEyeCenter;
in float vRadius;
in float vFluid;
in vec2 vOffset;

uniform mat4 projection;
uniform sampler2D sceneDepth;
uniform vec2 invTargetSize;
uniform float thicknessScale;

layout(location = 0) out float fragThickness;

void main() {
    float r2 = dot(vOffset, vOffset);
    if (r2 > 1.0) {
        discard;
    }

    // The chord through a sphere at this offset from its centre — the exact distance a ray travels inside
    // it. Analytic rather than a fitted falloff, so a clump of particles sums to something proportional to
    // the volume actually there and the absorption below it is physical rather than tuned.
    float chord = 2.0 * vRadius * sqrt(max(0.0, 1.0 - r2));

    // Still rejected against the opaque scene, for the same reason the depth pass is: fluid behind a hill
    // must not tint the hill. Measured at the sphere's near cap, so a particle half-buried in the ground
    // contributes the part of it that is showing.
    float z = sqrt(1.0 - r2);
    vec3 eye = vEyeCenter + vec3(vOffset * vRadius, z * vRadius);
    vec4 clip = projection * vec4(eye, 1.0);
    float ndcDepth = (clip.z / clip.w) * 0.5 + 0.5;
    if (ndcDepth > texture(sceneDepth, gl_FragCoord.xy * invTargetSize).r) {
        discard;
    }

    fragThickness = chord * thicknessScale;
}
