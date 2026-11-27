#version 330 core

in vec2 vUv;
in vec4 vColor;
out vec4 fragColor;

uniform sampler2D particleTexture;   // the shapes: grey shading in RGB, the outline in alpha
uniform vec3 tint;                   // scene brightness, so dust dims at night with everything else

void main() {
    vec4 shape = texture(particleTexture, vUv);
    // The particle's own alpha (its life-fade) times the shape's outline. For additive blending the
    // colour is scaled by this alpha in the blend func, so a fading spark dims correctly.
    fragColor = vec4(vColor.rgb * shape.rgb * tint, vColor.a * shape.a);
}
