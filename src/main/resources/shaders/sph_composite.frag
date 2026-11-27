#version 330 core

// The last pass of screen-space fluid rendering: turns a smoothed depth image and a thickness image into
// lit, translucent fluid, and blends it over the scene.
//
// No geometry reaches this shader. The surface it shades was never built — it is reconstructed here, per
// pixel, from the one number the depth pass left behind, and its NORMAL comes from the gradient of that
// number across neighbouring pixels. That is the whole trick of the technique and it is why a splash of
// four thousand particles costs the same to shade as a splash of four hundred covering the same area.

in vec2 uv;

uniform sampler2D fluidSurface;    // R: eye distance, G: fluid index
uniform sampler2D fluidThickness;
uniform float background;

uniform mat4 invProjection;
uniform mat4 invView;
uniform vec2 invTargetSize;
uniform vec3 viewPos;

uniform vec3 toSun;
uniform vec3 sunColor;
uniform vec3 ambientColor;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float fogHorizon;
uniform float fogHeightFalloff;
uniform float fogBaseY;

// --- Aerial perspective ---------------------------------------------------------------------------
// How much of `fogColor` stands between the eye and a point. Duplicated verbatim into every shader that
// fogs anything (there is no #include in GLSL 330 and no preprocessor here), and pinned equal by
// AerialPerspectiveTest — two surfaces in one frame that disagreed about the air between them and the
// camera would be a seam no still frame explains.
//
// Haze is a LAYER, not a function of distance alone. `fogHeightFalloff` is the reciprocal scale height,
// so density falls off exponentially with altitude and the term below is that density integrated along
// the view ray in closed form. A ray that both begins and ends at `fogBaseY` integrates to exactly one
// and gets precisely the tuned distance fog; a ray climbing to a summit averages much thinner air, which
// is why a landmark keeps a silhouette while the plain at its foot has dissolved.
//
// `fogHorizon` is the one term altitude cannot escape, and it is what stops that escape being unbounded:
// it takes everything, however tall, by the far edge of the streamed world, so nothing is left to be
// clipped by the far plane instead of fading out.
float aerialPerspective(vec3 fragWorldPos, vec3 eyeWorldPos) {
    vec3 ray = fragWorldPos - eyeWorldPos;
    float d = length(ray);

    float dy = fogHeightFalloff * ray.y;
    // (1 - exp(-dy)) / dy, with its removable singularity at dy = 0 spelled out.
    float span = abs(dy) < 1e-4 ? 1.0 : (1.0 - exp(-dy)) / dy;
    // Clamped at 1 so air denser than the layer's base — a ray in a valley, or under it — never fogs
    // harder than the range was tuned for. That clamp is also what makes ground level exactly unchanged.
    float air = clamp(exp(-fogHeightFalloff * (eyeWorldPos.y - fogBaseY)) * span, 0.0, 1.0);

    return max(smoothstep(fogStart, fogEnd, d) * air, smoothstep(fogStart, fogHorizon, d));
}
uniform float saturation;

uniform vec3 skyZenithColor;
uniform vec3 skyMiddleColor;
uniform vec3 skyHorizonColor;
uniform vec3 sunDirection;

const int MAX_FLUIDS = 4;
uniform vec3 shallowColor[MAX_FLUIDS];
uniform vec3 deepColor[MAX_FLUIDS];
uniform vec3 emberColor[MAX_FLUIDS];
uniform vec3 hotColor[MAX_FLUIDS];
uniform float emissive[MAX_FLUIDS];
// The alpha ceiling for a thick splash of this fluid. Named apart from the pooled surface's own
// transparency, which fluid.frag derives from the fluid's absorption over the view ray — a splash a
// few particles deep is clearer than a pool of the same stuff, so the two are separate numbers.
uniform float sprayOpacity[MAX_FLUIDS];
uniform float specular[MAX_FLUIDS];

// How fast a fluid saturates with its own colour as it gets thicker, in units of thickness. Beer's law
// wants an exponential and this is its scale: below it a sheet is nearly clear, well past it the deep
// colour is all that is left.
uniform float absorption;

out vec4 fragColor;

/**
 * The view-space point behind a pixel, given how far away the surface is there.
 *
 * The inverse projection turns the pixel into a ray through the near plane; dividing by its own z makes it
 * a direction one unit deep, and scaling that by the distance the depth pass recorded lands exactly on the
 * surface. Doing it from a linear distance rather than from a hardware depth value is what keeps the
 * arithmetic well conditioned far from the camera, where a depth buffer has almost no precision left.
 */
vec3 eyePosAt(vec2 coord, float distance) {
    vec4 clip = vec4(coord * 2.0 - 1.0, 0.0, 1.0);
    vec3 ray = (invProjection * clip).xyz / (invProjection * clip).w;
    ray /= -ray.z;
    return ray * distance;
}

/** Copied from fluid.frag, so a splash and the pool it lands in agree about what colour the sky is. */
vec3 skyReflection(vec3 rd) {
    float altitude = max(rd.y, 0.0);
    float sunAmount = max(dot(rd, sunDirection), 0.0);

    vec3 color = mix(skyMiddleColor, skyZenithColor, smoothstep(0.12, 0.70, altitude));
    color = mix(skyHorizonColor, color, smoothstep(0.0, 0.20, altitude));

    float sunLow = 1.0 - smoothstep(0.0, 0.40, abs(sunDirection.y));
    float horizonBand = pow(1.0 - altitude, 6.0);
    float warmth = pow(sunAmount, 2.5) * sunLow * horizonBand;
    color = mix(color, vec3(1.00, 0.42, 0.16), clamp(warmth * 0.75, 0.0, 0.85));

    return mix(color, skyHorizonColor * 0.75, 1.0 - smoothstep(-0.15, 0.02, rd.y));
}

void main() {
    vec2 surface = texture(fluidSurface, uv).rg;
    float distance = surface.r;
    if (distance >= background) {
        discard;                       // no fluid on this pixel, and the scene keeps what it had
    }

    int fluid = clamp(int(surface.g + 0.5), 0, MAX_FLUIDS - 1);
    vec3 eye = eyePosAt(uv, distance);

    // The normal, as the gradient of the depth image.
    //
    // Both neighbours are tried on each axis and the SMALLER step is kept. That is not a refinement — at a
    // silhouette one of the two neighbours is on the far side of an edge, metres away in depth, and a
    // plain central difference there produces a normal perpendicular to nothing, which draws as a hard
    // black rim all the way round the fluid. Taking the nearer neighbour keeps the difference on the
    // surface the pixel actually belongs to.
    vec2 dx = vec2(invTargetSize.x, 0.0);
    vec2 dy = vec2(0.0, invTargetSize.y);
    vec3 right = eyePosAt(uv + dx, texture(fluidSurface, uv + dx).r) - eye;
    vec3 left = eye - eyePosAt(uv - dx, texture(fluidSurface, uv - dx).r);
    if (abs(left.z) < abs(right.z)) {
        right = left;
    }
    vec3 up = eyePosAt(uv + dy, texture(fluidSurface, uv + dy).r) - eye;
    vec3 down = eye - eyePosAt(uv - dy, texture(fluidSurface, uv - dy).r);
    if (abs(down.z) < abs(up.z)) {
        up = down;
    }

    vec3 eyeNormal = normalize(cross(right, up));
    if (eyeNormal.z < 0.0) {
        // A surface the camera can see faces the camera. Which way the cross product comes out depends on
        // the winding of the two differences, and that flips at a silhouette where one of them was
        // replaced above — so the answer is asserted rather than assumed.
        eyeNormal = -eyeNormal;
    }
    vec3 normal = normalize(mat3(invView) * eyeNormal);
    vec3 worldPos = (invView * vec4(eye, 1.0)).xyz;
    vec3 toView = normalize(viewPos - worldPos);

    float thickness = texture(fluidThickness, uv).r;
    // Beer's law: how much of the fluid's own colour has accumulated along the ray. This is what makes the
    // thin edge of a splash pale and its middle saturated, and it is the same number the alpha comes from,
    // so what looks thick and what is opaque cannot disagree.
    float saturationAmount = 1.0 - exp(-thickness * absorption);

    vec3 base = mix(shallowColor[fluid], deepColor[fluid], saturationAmount);

    // Lit and molten are the same two models fluid.frag spans with `emissive`, so a fluid looks the same
    // whether it is in a pool or in the air — which is the point of both renderers reading one palette.
    float band = max(dot(normal, toSun), 0.0);
    band = smoothstep(0.0, 0.55, band);
    vec3 lit = base * mix(ambientColor, sunColor, band);

    float fresnel = pow(1.0 - clamp(dot(normal, toView), 0.0, 1.0), 4.0);
    vec3 reflected = skyReflection(reflect(-toView, normal));
    lit = mix(lit, reflected, fresnel * 0.7);

    vec3 halfway = normalize(toSun + toView);
    float gloss = pow(max(dot(normal, halfway), 0.0), 90.0);
    lit += sunColor * gloss * specular[fluid] * (0.35 + fresnel);

    // The molten model: crust up to 1 is lit as the rock it is, and the excess over 1 is added unlit so it
    // survives shadow and blooms. The same split terrain.frag makes for magma.
    vec3 molten = mix(emberColor[fluid], hotColor[fluid], saturationAmount);
    vec3 moltenLit = min(molten, vec3(1.0)) * mix(ambientColor, sunColor, max(band, 0.35))
            + max(molten - vec3(1.0), vec3(0.0));
    vec3 color = mix(lit, moltenLit, emissive[fluid]);

    // Opacity from thickness, lifted at grazing angles. A splash's edge is a few particles deep and should
    // be nearly clear; its middle should not. The fresnel term is what stops a surface seen almost edge-on
    // from disappearing, which is both what real water does and what stops the silhouette shimmering.
    float alpha = clamp(saturationAmount * sprayOpacity[fluid] + fresnel * 0.35, 0.0, 1.0);
    // Emissive fluid is opaque whatever its thickness: you cannot see through molten rock, and a thin
    // sheet of it that faded out would read as the glow being drawn over the terrain rather than as lava.
    alpha = mix(alpha, max(alpha, 0.85), emissive[fluid]);

    float grey = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(grey), color, saturation);

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);
    // Fully fogged fluid fades out rather than painting fog over the sky it has already become.
    alpha *= 1.0 - fog;

    fragColor = vec4(color, alpha);
}
