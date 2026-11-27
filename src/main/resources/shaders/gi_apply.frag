#version 330 core

// Puts the gathered indirect light onto the scene.
//
// The whole shader is two lines of arithmetic because the interesting decision is the blend it is written
// for: glBlendFunc(GL_DST_COLOR, GL_SRC_ALPHA), which resolves to
//
//     scene * (bounce + visibility)
//
// so BOTH terms scale the colour already in the buffer rather than being added over it.
//
// That is what makes the bounce honest without a fourth G-buffer channel. Indirect light is light a surface
// *reflects*, so it has to be multiplied by that surface's albedo — and the albedo is not in the scene
// buffer, only albedo-times-lighting is. Adding the bounce as plain light instead is the obvious shortcut
// and it makes black surfaces glow: soot in a green clearing lights up green. Scaling what is already there
// cannot do that, gets the hue interaction right for free (green light on a red wall stays dark, because it
// genuinely is), and costs nothing.
//
// The price, stated plainly: a surface deep in shadow has little in the buffer to scale, so it takes less
// bounce than it physically should. That is the wrong way round, and it is the trade being made — the
// alternative is carrying albedo through the geometry buffer, which is three more channels on a
// full-resolution target for every pass in the scene.

in vec2 uv;
out vec4 fragColor;

/**
 * The blurred half-resolution gather: rgb bounced light, a fill visibility.
 *
 * <p>The only thing this pass samples, and deliberately so. Sharpening the upsample with the scene's own
 * depth would be better and is not available: this draws into the scene buffer, and reading a texture
 * attached to the framebuffer being drawn into is a feedback loop. What it costs is a half-resolution
 * texel's worth of softness at silhouettes, which under a cel shader that quantises the result into bands
 * is very nearly invisible.
 */
uniform sampler2D gi;

uniform float occlusion;
uniform float bounce;

void main() {
    vec4 gathered = texture(gi, uv);

    // Strength as an EXPONENT, not a lerp toward the gathered value, and it is worth saying why.
    //
    // A gather over real terrain returns modest numbers — a rock sitting on open ground blocks something
    // like a fifth of the sky at its own foot, so the honest answer there is 0.8 and no tuning of the
    // sampling changes that. Lerping toward it can only ever reach 0.8, so the knob's whole useful range is
    // spent before the effect is visible under a cel shader, and pushing the lerp past 1 to compensate
    // sends dark pixels negative. A power deepens what the gather found while leaving what it did not found
    // exactly alone: 1 stays 1 at every strength, so open sky and unwritten pixels are untouched by
    // construction, 0 is off, and the result can never leave 0..1 however far it is pushed.
    //
    // It is also the same move sunVisibility already makes on the shadow lookup, and for the same reason —
    // a stylised renderer wants its shading to read as a shape rather than as a gradient.
    float visibility = pow(gathered.a, occlusion);
    vec3 bounced = gathered.rgb * bounce;

    // Unoccluded and unlit by anything nearby is (1, 0): scene * (0 + 1), an exact no-op. So the sky, the
    // frame's edges and every pixel the gather skipped come through untouched rather than nearly untouched.
    fragColor = vec4(bounced, visibility);
}
