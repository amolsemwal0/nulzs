#version 330 core

// The final image: the HDR scene plus its bloom, tone-mapped to display range, then colour-graded. This
// is where the Genshin look mostly comes from — the cel shading gives clean bands, and this gives the
// glow, the filmic highlight rolloff, and the warm/cool grade that shifts with the time of day.

in vec2 uv;
out vec4 fragColor;

uniform sampler2D scene;
uniform sampler2D bloom;

uniform float exposure;
uniform float bloomIntensity;
uniform float contrast;
uniform float saturation;
uniform vec3 colorFilter;    // warm/cool balance, a multiply
uniform vec3 lift;           // shadow lift, added scaled by darkness
uniform float vignette;
// 1 for the ACES curve below; 0 clips at white as a picture drawn straight to the window does, so a scene that
// is only here for its occlusion, bloom or antialiasing keeps the colours it was drawn in.
uniform int filmic;

// What the camera is looking through, when it is inside a body of fluid — see UnderwaterView. Zero depth
// is "not submerged", which is the overwhelmingly common case and costs one compare.
uniform float underwaterDepth;      // how far the eye is below the surface
uniform vec3 underwaterColor;
uniform float underwaterDensity;
uniform mat4 invViewProjection;
uniform vec3 cameraPosition;
uniform sampler2D sceneDepth;
uniform int hasSceneDepth;

// Sun rays (SunRays.java): the sunlit air in shafts along each pixel's view ray, at half size, and the colour a unit
// of it adds.
uniform sampler2D sunRays;
uniform vec3 sunRayColor;
uniform float sunRayMaxHaze;
uniform int hasSunRays;
// Beams in the sky (SunRays.renderBeams): the sun's light through the gaps between clouds, drawn out from the sun, at a
// quarter size; the colour their full strength adds, and the most they may add, as a share of it.
uniform sampler2D skyBeams;
uniform vec3 skyBeamColor;
uniform float skyBeamMax;
uniform int hasSkyBeams;

/**
 * Where the absorption saturates, in world units.
 *
 * Not infinity, because `exp` of a very large number is a needless way to reach the same answer, and not
 * the far plane, because the tint is fully saturated long before that at any sensible density.
 */
const float MAX_UNDERWATER_PATH = 60.0;

/**
 * How far this pixel's view ray travels through fluid, in world units.
 *
 * Bounded by TWO things, and leaving either out is wrong in a way that shows immediately.
 *
 * The SURFACE, which is where a ray leaves upward. Treated as the horizontal plane the eye is under, which
 * is what a body of water is at the scale of one frame. A ray pointing down or level never leaves this
 * way; one pointing up leaves after `depth / dir.y`, which is a long way when the eye is deep and almost
 * nothing when it is just under the surface. THAT is the half-submerged split — with the eye at the
 * surface every downward pixel is water and every upward one is air, and the boundary is the plane's own
 * horizon rather than a line drawn at a fixed screen height.
 *
 * The SCENE, which is where the water runs out downward — the bed, the far bank, a rock, the player's own
 * legs. The first version had only the surface bound, so a downward ray was given the whole saturating
 * path whatever was in front of it: at water's absorption that is `1 - exp(-9.6)`, a solid slab of colour,
 * and it was reported from play as the camera seeing nothing but blue. A ray pointing at a bed a metre
 * away passes through a metre of water, not sixty.
 *
 * Together they are what makes the tint read as an AMOUNT OF WATER rather than as a filter: the same metre
 * of water looks the same whether it is a puddle or the top metre of a lake, and the deep end is darker
 * than the shallows because there is genuinely more of it in the way.
 */
float underwaterPath(vec3 dir, float toScene) {
    float toSurface = dir.y > 0.0 ? underwaterDepth / dir.y : MAX_UNDERWATER_PATH;
    return min(min(toSurface, toScene), MAX_UNDERWATER_PATH);
}


// ACES filmic tone-map (Narkowicz fit): rolls highlights off gently instead of the hard white clip an
// LDR framebuffer gives, which is what lets a bright sky or a bloom bloom without flattening to a slab.
vec3 aces(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec3 hdr = texture(scene, uv).rgb;
    hdr += texture(bloom, uv).rgb * bloomIntensity;
    // Light the air scatters toward the eye, in front of whatever the pixel shows; kept out of the bloom, so a beam
    // stays a beam rather than a glow.
    // Eased toward a ceiling (SunRays.MAX_HAZE metres of lit haze), so even looking straight into a low sun the
    // shafts lie over the picture as a light veil and never wash it out.
    if (hasSunRays == 1) {
        float haze = texture(sunRays, uv).r;
        hdr += sunRayMaxHaze * (1.0 - exp(-haze / sunRayMaxHaze)) * sunRayColor;
    }
    // Eased toward their ceiling the same way, so a gap right over the sun streams light rather than whiting out.
    if (hasSkyBeams == 1) {
        float beam = texture(skyBeams, uv).r;
        hdr += skyBeamMax * (1.0 - exp(-beam / skyBeamMax)) * skyBeamColor;
    }

    // Exposure and the warm/cool tint act in linear HDR, before the curve, so the tint shifts the whole
    // range rather than only the midtones.
    hdr *= exposure;
    hdr *= colorFilter;

    // Absorption through the fluid, before the curve: this is something that happens to the light on its
    // way to the lens, not a filter over the finished photograph. Beer's law on the path length above, so
    // a glance up through half a metre of water barely tints and a look down into it goes fully to the
    // fluid's own colour.
    if (underwaterDepth > 0.0) {
        vec4 farPoint = invViewProjection * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w - cameraPosition);
        // How far away whatever this pixel is looking at actually is. Reconstructed through the same
        // inverse matrix rather than by linearising the raw depth, so it needs no near/far pair and cannot
        // disagree with the ray above about where the camera is.
        float toScene = MAX_UNDERWATER_PATH;
        if (hasSceneDepth == 1) {
            float depth = texture(sceneDepth, uv).r;
            if (depth < 1.0) {
                vec4 hit = invViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                toScene = distance(hit.xyz / hit.w, cameraPosition);
            }
        }
        float absorbed = 1.0 - exp(-underwaterDensity * underwaterPath(dir, toScene));
        hdr = mix(hdr, underwaterColor, absorbed);
    }

    vec3 col = filmic == 1 ? aces(hdr) : clamp(hdr, 0.0, 1.0);

    // Grade in display range: an S-curve around mid-grey, then saturation, then a lifted shadow that can
    // carry a cool cast — the anime "shadows never quite reach black" look.
    col = (col - 0.5) * contrast + 0.5;
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(vec3(luma), col, saturation);
    col += lift * (1.0 - clamp(luma, 0.0, 1.0));
    col = clamp(col, 0.0, 1.0);

    // A soft vignette pulls the eye to the centre and frames the vista.
    vec2 d = uv - 0.5;
    float vig = 1.0 - dot(d, d) * vignette * 2.0;
    col *= clamp(vig, 0.0, 1.0);

    fragColor = vec4(col, 1.0);
}
