#version 330 core

in vec2 uv;
in float shade;

out vec4 fragColor;

uniform sampler2D albedoTexture;

void main() {
    // Albedo only — no lighting, no fog, no tint. What is baked has to be the *material*, so a
    // billboard can be lit at runtime by the same cel bands as everything else and can carry either
    // colour variant's tint. Baking a lit tuft would freeze one sun angle into the far field.
    //
    // Alpha 1 wherever geometry covers a texel; the buffer is cleared fully transparent, so the alpha
    // channel comes out as the cutout mask for free. That is the whole reason this bakes rather than
    // needing an authored sprite: the grass texture has no alpha at all, the blade shapes are geometry.
    fragColor = vec4(texture(albedoTexture, uv).rgb * shade, 1.0);
}
