#version 330 core

// The cleanup half of the GI pass: a depth-aware blur over the half-resolution gather.
//
// Rotating the sample kernel per pixel turns the gather's error from banding into noise, which is only an
// improvement because noise can be averaged away and banding cannot. This is where that is paid back.
// Separable — run once across and once down — so a 9x9 neighbourhood costs eighteen taps rather than
// eighty-one, which is exact for a Gaussian and near enough for the depth weighting layered over it.

in vec2 uv;
out vec4 fragColor;

uniform sampler2D source;      // half-res GI: rgb bounce, a visibility
uniform sampler2D sceneDepth;  // full-res depth, read at the same normalised position

uniform vec2 texelStep;        // one half-res texel along the axis being blurred, zero on the other
uniform float near;
uniform float far;

/**
 * How sharply a depth difference cuts the blur off, in world units.
 *
 * <p>The whole reason this is not a plain Gaussian. Occlusion belongs to the surface it was measured on, so
 * a kernel that reached across a silhouette would spread a tree trunk's contact darkening out onto the
 * hillside a hundred metres behind it — which is exactly the halo that makes a screen-space effect announce
 * itself as one.
 */
uniform float depthSharpness;

/** Distance to what was drawn, in world units — the depth buffer's own value is not linear in it. */
float linearDepth(float depth) {
    float ndc = depth * 2.0 - 1.0;
    return (2.0 * near * far) / (far + near - ndc * (far - near));
}

void main() {
    float centre = linearDepth(texture(sceneDepth, uv).r);

    // Five taps a side. The weights are a Gaussian evaluated at 0, 1 and 2 texels; the depth term below
    // multiplies into them, so the total has to be normalised rather than assumed to be 1.
    const float WEIGHTS[3] = float[3](0.38774, 0.24477, 0.06136);

    vec4 total = texture(source, uv) * WEIGHTS[0];
    float weightSum = WEIGHTS[0];

    for (int i = 1; i <= 2; i++) {
        for (int side = -1; side <= 1; side += 2) {
            vec2 tap = uv + texelStep * float(i * side);
            float tapDepth = linearDepth(texture(sceneDepth, tap).r);
            // Falls off with the depth gap rather than cutting at a threshold: a hard cut leaves the
            // kernel's own shape visible as a ring wherever a surface curves past the limit.
            float similarity = max(0.0, 1.0 - abs(tapDepth - centre) * depthSharpness);
            float weight = WEIGHTS[i] * similarity;
            total += texture(source, tap) * weight;
            weightSum += weight;
        }
    }

    fragColor = total / max(weightSum, 1e-4);
}
