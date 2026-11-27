#version 330 core

// Bright pass: keep only the light past the threshold, so bloom glows off what is brighter than its surroundings (a
// lamp, a glowing move, a sunlit white, the sky's brightest) rather than smearing the whole image into soft focus. Only
// the excess is kept, not the whole colour of anything over the line: a broad bright area — the sky — is summed up
// every level of the bloom chain on its way back up, so passing it whole hazes the horizon white. A soft knee ramps
// the cut in over a short range instead of a hard clip, which stops a crawling edge where pixels flicker across the
// threshold as the camera moves.

in vec2 uv;
out vec4 fragColor;

uniform sampler2D scene;
uniform float threshold;

// What a sprite writes to alpha, 0, keeps it out: pixel art takes no halo and keeps its whites (Renderer.
// setSpritesUnbloomed). Everything else is drawn opaque, alpha 1; the water blended over it keeps most of that.
void main() {
    vec4 drawn = texture(scene, uv);
    vec3 c = drawn.rgb * clamp(drawn.a * 2.0, 0.0, 1.0);
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));

    // The excess over the threshold, eased in quadratically over a knee either side of it.
    float knee = 0.15 * threshold;
    float ramp = clamp(luma - threshold + knee, 0.0, 2.0 * knee);
    ramp = ramp * ramp / (4.0 * knee + 1e-5);
    float excess = max(ramp, luma - threshold);
    fragColor = vec4(c * (excess / max(luma, 1e-4)), 1.0);
}
