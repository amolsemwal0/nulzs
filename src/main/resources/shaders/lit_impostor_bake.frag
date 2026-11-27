#version 330 core

// Bakes a prop's colour and its normals, unlit, into two pictures: the colour as the near shader starts from it
// (texture times vertex tint), the normal as the near shader shades with it — for a tree's leaves the crown's volume
// normal — so lit_impostor.frag can light the flat picture as the real thing is lit, at any time of day.

in vec2 uv;
in vec3 normal;
in vec3 tint;

layout(location = 0) out vec4 albedoOut;
layout(location = 1) out vec4 normalOut;

uniform sampler2D albedoTexture;
uniform float alphaCutoff;

void main() {
    vec4 texel = texture(albedoTexture, uv);
    if (texel.a < alphaCutoff) {
        discard;
    }
    albedoOut = vec4(texel.rgb * tint, 1.0);
    normalOut = vec4(normalize(normal) * 0.5 + 0.5, 1.0);
}
