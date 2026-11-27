#version 330 core

in vec2 uv;

// The ink colour — and, more to the point, the ink's DARKNESS. See main().
uniform vec3 outlineColor;

// Coloured lineart: the surface the line borders, and how far the line takes its hue from it.
uniform sampler2D baseColorTexture;
uniform vec4 baseColorFactor;
uniform float outlineAlbedoMix;

layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 fragGeometry;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// How much more saturated the line is than the surface. Above 1 because a line is thin: at the same
// saturation as the albedo it reads as grey against it, and the whole point is that it does not.
const float OUTLINE_SATURATION = 1.6;

void main() {
    // Painted anime lineart is a deep, saturated version of whatever it borders -- reddish brown round
    // skin, navy round a blue coat -- rather than one black for everything. So the line takes its HUE
    // from the albedo and its LUMINANCE from outlineColor, which is what keeps it a line: a white shirt
    // and a black boot get ink of the same weight, where darkening the albedo by a fixed factor would
    // give the shirt a light grey outline and the boot an invisible one.
    vec3 albedo = texture(baseColorTexture, uv).rgb * baseColorFactor.rgb;
    vec3 hue = max(mix(vec3(dot(albedo, LUMA)), albedo, OUTLINE_SATURATION), vec3(0.0));
    float hueLuma = dot(hue, LUMA);
    float inkLuma = dot(outlineColor, LUMA);
    // A black or near-black albedo has no hue to give, and dividing by its luminance would amplify noise
    // into one; it falls back to the plain ink colour instead.
    vec3 ink = hueLuma > 1e-3 ? clamp(hue * (inkLuma / hueLuma), 0.0, 1.0) : outlineColor;

    fragColor = vec4(mix(outlineColor, ink, outlineAlbedoMix), 1.0);

    // A zero normal, which is the geometry buffer's way of saying "no surface here". The hull is an
    // expanded copy of the character drawn inside out to make an ink line — it is not a thing in the world,
    // it has no shading to occlude and no light to bounce, and the normals it does have point backwards by
    // construction. It still writes DEPTH, though, which is why this cannot simply be left out: an enabled
    // draw buffer a shader declares no output for gets undefined values, and undefined normals along every
    // silhouette in the frame is exactly where they would be most visible.
    fragGeometry = vec4(0.5, 0.5, 0.5, 0.0);
}
