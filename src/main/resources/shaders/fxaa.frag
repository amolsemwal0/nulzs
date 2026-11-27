#version 330 core

// FXAA — spatial antialiasing over the finished, tone-mapped image.
//
// WHY THIS AND NOT MSAA, WHICH IS THE BETTER ANSWER. The scene is drawn into an HDR framebuffer whose
// colour AND depth are sampled afterwards by four different things — the fluid pass copies the depth, the
// spray solver rejects against it, the composite reads it for underwater absorption, and the indirect
// light pass reconstructs positions from it. A multisampled attachment is not a sampler2D: every one of
// those would have to move to texelFetch on a sampler2DMS, and a multisample depth buffer cannot be
// resolved to a single-sample one by a blit at all. MSAA here is a rewrite of the water and spray passes,
// not a framebuffer flag.
//
// FXAA needs none of that. It runs last, on LDR pixels, reads one texture and writes one, and knows
// nothing about the scene that produced it.
//
// WHAT IT DOES AND DOES NOT FIX, because the difference matters for judging it. It finds high-contrast
// edges and blends along them, so a staircase becomes a ramp — that is most of the crawl on a trunk
// silhouette, an outline, a rock's horizon, a roof against the sky. It cannot recover detail that was
// never sampled: a leaf card thinner than a pixel is present in one frame and absent in the next whatever
// this does afterwards, which is why the impostor swap and the coverage-preserving cutout carry that half
// of the problem and this carries the edges.
//
// Luma is computed from the pixel rather than carried in alpha. The usual FXAA integration writes luma
// into the alpha channel of the pass before it, which would mean the composite reserving its alpha for
// this; three dot products are cheaper than that coupling.

in vec2 uv;
out vec4 fragColor;

uniform sampler2D image;
uniform vec2 texel;

// Below this much local contrast the pixel is left exactly alone. Not merely an optimisation — it is what
// keeps flat areas bit-for-bit untouched, so the grade, the vignette and the UI-free background come
// through this pass unchanged and only edges are ever rewritten.
const float EDGE_THRESHOLD_MIN = 0.0312;

// Relative contrast an edge needs before it is treated as one, as a fraction of the local maximum. Lower
// finds more edges and softens more of the image with them; this is the standard value and it is chosen
// against exactly the failure a cel-shaded renderer cares about — a quantised tone boundary is a genuine
// edge in the art, and blurring those would undo the bands the shading exists to draw.
const float EDGE_THRESHOLD_MAX = 0.125;

// How far along an edge the search runs, and how fine a sub-pixel offset it may end on. Twelve steps at
// this reach covers the near-horizontal roof lines and hillside horizons that alias worst here.
const int SEARCH_STEPS = 12;
const float SUBPIXEL_QUALITY = 0.75;

float luma(vec3 color) {
    // Rec. 601, and deliberately not the Rec. 709 weights used elsewhere in this project: FXAA's
    // thresholds above were tuned against this curve, so swapping it silently retunes the edge detector.
    return dot(color, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 centre = texture(image, uv).rgb;
    float lumaCentre = luma(centre);

    float lumaDown = luma(textureOffset(image, uv, ivec2(0, -1)).rgb);
    float lumaUp = luma(textureOffset(image, uv, ivec2(0, 1)).rgb);
    float lumaLeft = luma(textureOffset(image, uv, ivec2(-1, 0)).rgb);
    float lumaRight = luma(textureOffset(image, uv, ivec2(1, 0)).rgb);

    float lumaMin = min(lumaCentre, min(min(lumaDown, lumaUp), min(lumaLeft, lumaRight)));
    float lumaMax = max(lumaCentre, max(max(lumaDown, lumaUp), max(lumaLeft, lumaRight)));
    float range = lumaMax - lumaMin;

    // Flat enough to be nothing: pass the pixel straight through, unmodified.
    if (range < max(EDGE_THRESHOLD_MIN, lumaMax * EDGE_THRESHOLD_MAX)) {
        fragColor = vec4(centre, 1.0);
        return;
    }

    float lumaDownLeft = luma(textureOffset(image, uv, ivec2(-1, -1)).rgb);
    float lumaUpRight = luma(textureOffset(image, uv, ivec2(1, 1)).rgb);
    float lumaUpLeft = luma(textureOffset(image, uv, ivec2(-1, 1)).rgb);
    float lumaDownRight = luma(textureOffset(image, uv, ivec2(1, -1)).rgb);

    float lumaDownUp = lumaDown + lumaUp;
    float lumaLeftRight = lumaLeft + lumaRight;
    float lumaLeftCorners = lumaDownLeft + lumaUpLeft;
    float lumaDownCorners = lumaDownLeft + lumaDownRight;
    float lumaRightCorners = lumaDownRight + lumaUpRight;
    float lumaUpCorners = lumaUpRight + lumaUpLeft;

    // Which way the edge runs, from where the luma changes fastest across it.
    float edgeHorizontal = abs(-2.0 * lumaLeft + lumaLeftCorners)
            + abs(-2.0 * lumaCentre + lumaDownUp) * 2.0
            + abs(-2.0 * lumaRight + lumaRightCorners);
    float edgeVertical = abs(-2.0 * lumaUp + lumaUpCorners)
            + abs(-2.0 * lumaCentre + lumaLeftRight) * 2.0
            + abs(-2.0 * lumaDown + lumaDownCorners);
    bool isHorizontal = edgeHorizontal >= edgeVertical;

    float luma1 = isHorizontal ? lumaDown : lumaLeft;
    float luma2 = isHorizontal ? lumaUp : lumaRight;
    float gradient1 = luma1 - lumaCentre;
    float gradient2 = luma2 - lumaCentre;
    bool is1Steepest = abs(gradient1) >= abs(gradient2);
    float gradientScaled = 0.25 * max(abs(gradient1), abs(gradient2));

    float stepLength = isHorizontal ? texel.y : texel.x;
    float lumaLocalAverage;
    if (is1Steepest) {
        stepLength = -stepLength;
        lumaLocalAverage = 0.5 * (luma1 + lumaCentre);
    } else {
        lumaLocalAverage = 0.5 * (luma2 + lumaCentre);
    }

    // Half a texel onto the edge itself, so the walk below runs along the boundary rather than beside it.
    vec2 currentUv = uv;
    if (isHorizontal) {
        currentUv.y += stepLength * 0.5;
    } else {
        currentUv.x += stepLength * 0.5;
    }

    vec2 offset = isHorizontal ? vec2(texel.x, 0.0) : vec2(0.0, texel.y);
    vec2 uv1 = currentUv - offset;
    vec2 uv2 = currentUv + offset;

    float lumaEnd1 = luma(texture(image, uv1).rgb) - lumaLocalAverage;
    float lumaEnd2 = luma(texture(image, uv2).rgb) - lumaLocalAverage;
    bool reached1 = abs(lumaEnd1) >= gradientScaled;
    bool reached2 = abs(lumaEnd2) >= gradientScaled;
    bool reachedBoth = reached1 && reached2;

    if (!reached1) {
        uv1 -= offset;
    }
    if (!reached2) {
        uv2 += offset;
    }

    // Walk outwards along the edge until both ends of it are found. The loop is uniform in trip count and
    // the work inside is masked, rather than breaking early: a divergent break here buys nothing on a GPU
    // that runs the whole quad regardless.
    if (!reachedBoth) {
        for (int i = 2; i < SEARCH_STEPS; i++) {
            if (!reached1) {
                lumaEnd1 = luma(texture(image, uv1).rgb) - lumaLocalAverage;
                reached1 = abs(lumaEnd1) >= gradientScaled;
            }
            if (!reached2) {
                lumaEnd2 = luma(texture(image, uv2).rgb) - lumaLocalAverage;
                reached2 = abs(lumaEnd2) >= gradientScaled;
            }
            if (!reached1) {
                uv1 -= offset;
            }
            if (!reached2) {
                uv2 += offset;
            }
            if (reached1 && reached2) {
                break;
            }
        }
    }

    float distance1 = isHorizontal ? (uv.x - uv1.x) : (uv.y - uv1.y);
    float distance2 = isHorizontal ? (uv2.x - uv.x) : (uv2.y - uv.y);
    bool isDirection1 = distance1 < distance2;
    float distanceFinal = min(distance1, distance2);
    float edgeThickness = distance1 + distance2;
    float pixelOffset = -distanceFinal / edgeThickness + 0.5;

    // Which side of the edge this pixel is on. If the luma at the nearer end went the same way as the
    // centre did, the pixel is on the far side and must not be shifted — that is what would smear an edge
    // outwards into the flat area beside it instead of softening it.
    bool isLumaCentreSmaller = lumaCentre < lumaLocalAverage;
    bool correctVariation =
            ((isDirection1 ? lumaEnd1 : lumaEnd2) < 0.0) != isLumaCentreSmaller;
    float finalOffset = correctVariation ? pixelOffset : 0.0;

    // Sub-pixel term: how far the centre sits from the average of its whole neighbourhood. It is what
    // catches a feature smaller than a pixel, which the edge walk above cannot see at all.
    float lumaAverage = (1.0 / 12.0)
            * (2.0 * (lumaDownUp + lumaLeftRight) + lumaLeftCorners + lumaRightCorners);
    float subPixelOffset1 = clamp(abs(lumaAverage - lumaCentre) / range, 0.0, 1.0);
    float subPixelOffset2 = (-2.0 * subPixelOffset1 + 3.0) * subPixelOffset1 * subPixelOffset1;
    float subPixelOffsetFinal = subPixelOffset2 * subPixelOffset2 * SUBPIXEL_QUALITY;

    finalOffset = max(finalOffset, subPixelOffsetFinal);

    vec2 finalUv = uv;
    if (isHorizontal) {
        finalUv.y += finalOffset * stepLength;
    } else {
        finalUv.x += finalOffset * stepLength;
    }
    fragColor = vec4(texture(image, finalUv).rgb, 1.0);
}
