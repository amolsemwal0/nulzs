#version 330 core

in vec2 vUv;
in vec4 vColor;

// Single-channel glyph atlas. Solid quads bind nothing and take the useTexture == 0 path.
uniform sampler2D fontAtlas;
// Full RGBA artwork — a slot frame, an item icon. Its own sampler rather than a second use of the atlas
// because the two are different formats: the font is R8 coverage to be thresholded, this is colour with
// an alpha channel to be blended, and one path cannot serve both.
uniform sampler2D spriteAtlas;
uniform int useTexture;

out vec4 fragColor;

void main() {
    if (useTexture == 2) {
        vec4 sprite = texture(spriteAtlas, vUv);
        float spriteAlpha = sprite.a * vColor.a;
        if (spriteAlpha < 0.01) {
            discard;
        }
        // Tinted rather than replaced, so one frame can be drawn highlighted or dimmed without a
        // second image — and a white tint leaves the art exactly as authored.
        fragColor = vec4(sprite.rgb * vColor.rgb, spriteAlpha);
        return;
    }

    float coverage = 1.0;
    if (useTexture == 1) {
        // The atlas is R8 coverage. For the pixel-font fallback it is sampled GL_NEAREST and comes back
        // 0 or 1; for a rasterised typeface it is antialiased and filtered, and the intermediate values
        // are the glyph's edges. Either way it is used as alpha and never thresholded — rounding it would
        // throw away exactly the antialiasing that stops small text crawling.
        coverage = texture(fontAtlas, vUv).r;
    } else {
        // A solid rect carries 0..1 UVs across itself. Distance to its nearest side in screen pixels is
        // that UV distance over how fast the UV changes per pixel, and fading over the pixel either side
        // of the edge smooths a turned card's borders, which would otherwise step. An upright,
        // pixel-aligned rect has every pixel centre at least half a pixel in, so it stays fully solid.
        vec2 perPixel = vec2(length(vec2(dFdx(vUv.x), dFdy(vUv.x))), length(vec2(dFdx(vUv.y), dFdy(vUv.y))));
        vec2 inside = min(vUv, 1.0 - vUv);
        vec2 edge = vec2(
            perPixel.x > 1e-6 ? clamp(inside.x / perPixel.x + 0.5, 0.0, 1.0) : 1.0,
            perPixel.y > 1e-6 ? clamp(inside.y / perPixel.y + 0.5, 0.0, 1.0) : 1.0);
        coverage = edge.x * edge.y;
    }

    float alpha = vColor.a * coverage;
    if (alpha < 0.01) {
        discard;
    }
    fragColor = vec4(vColor.rgb, alpha);
}
