#version 330 core

// Smooths the fluid's depth image without smoothing across its silhouette — one axis per invocation, run
// twice.
//
// This is the pass that turns a bag of spheres into a body of water, and it is the reason the technique
// works at all. The raw depth image is bumpy at exactly the scale of a particle, so reading its gradient
// gives a normal per sphere and the fluid shades as a heap of ball bearings. Blurring the DEPTH before the
// normal is taken flattens those bumps into one surface — the geometry is never smoothed, only the picture
// of it, which is what makes this cost a fixed number of texture reads per pixel rather than anything
// proportional to the particle count.
//
// It must not be an ordinary blur. A Gaussian across a silhouette drags the background depth into the
// foreground and vice versa, and what that draws is a halo of stretched surface around every edge of the
// splash — the classic failure of this technique. So each tap is weighted by how far its depth is from the
// centre's as well as by distance: a tap on the other side of an edge is a long way off in depth and
// contributes essentially nothing. Bilateral in the range domain, which is the "narrow-range filter" the
// literature settled on after trying to fix the halo with wider kernels and larger radii.
//
// Separable in the spatial term and not, strictly, in the range term — two 1D passes are not identical to
// one 2D pass here. That is a known and universally-made approximation: it is 2R taps instead of R², and
// what it costs is a slight cross-shaped bias at corners of the silhouette, which is invisible under a
// surface that is already being shaded as a rippling liquid.

in vec2 uv;

uniform sampler2D source;
// Texel step along the axis being filtered — (1/width, 0) or (0, 1/height). One shader, two invocations.
uniform vec2 direction;
uniform float spatialSigma;
// How many units of depth difference count as "a different surface". Small enough and the filter does
// nothing; large enough and it becomes an ordinary Gaussian and brings the halo back with it.
uniform float depthSigma;
uniform float background;

layout(location = 0) out vec2 fragSurface;

// Fixed at compile time because a loop bound must be, and because the cost of this pass has to be
// predictable: it runs over every pixel the fluid covers, twice.
const int RADIUS = 8;

void main() {
    vec2 centre = texture(source, uv).rg;
    if (centre.r >= background) {
        // No fluid here. Passed through rather than filtered, so the background can never bleed inward and
        // pull the surface's edge toward the camera.
        fragSurface = centre;
        return;
    }

    float sum = 0.0;
    float weightSum = 0.0;
    for (int i = -RADIUS; i <= RADIUS; i++) {
        vec2 tap = texture(source, uv + direction * float(i)).rg;
        if (tap.r >= background) {
            continue;
        }
        float spatial = exp(-float(i * i) * spatialSigma);
        float delta = tap.r - centre.r;
        float range = exp(-delta * delta * depthSigma);
        float weight = spatial * range;
        sum += tap.r * weight;
        weightSum += weight;
    }

    // The fluid index is carried through unfiltered — it is an identity, not a quantity, and the average of
    // "water" and "lava" is a fluid the palette has no entry for. Taken from the centre tap, so the
    // boundary between two fluids stays exactly where the nearest surface says it is.
    fragSurface = vec2(weightSum > 0.0 ? sum / weightSum : centre.r, centre.g);
}
