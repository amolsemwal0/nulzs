#version 330 core

in vec2 uv;
in vec3 normal;
out vec4 fragColor;

uniform sampler2D albedoTexture;
uniform vec3 tint;
uniform float alphaCutoff;

/**
 * A crude ambient-occlusion stand-in, baked because it is the one lighting term that *is* a property of the
 * object rather than of the sun. A billboard is a flat card, so at runtime it has no interior geometry left
 * to shade itself with — every part of a tree would come back the same brightness and the crown would read
 * as a green disc. Darkening by how much a surface faces away from the viewer at bake time keeps the
 * modelled form's own light and shade in the sprite, while the *directional* light is still applied live.
 */
const float FORM_SHADING = 0.35;

void main() {
    vec4 texel = texture(albedoTexture, uv);
    // The same cutout the real mesh uses: a leaf card's shape lives in its alpha, and keeping the test
    // identical is what makes the sprite's silhouette the mesh's silhouette.
    if (texel.a < alphaCutoff) {
        discard;
    }
    // The bake is a side elevation looking down -Z, so a surface's own facing toward the camera is just
    // its normal's z. Two-sided, because a leaf card's winding says nothing about which way it faces.
    float facing = abs(normalize(normal).z);
    float form = mix(1.0 - FORM_SHADING, 1.0, facing);
    fragColor = vec4(texel.rgb * tint * form, 1.0);
}
