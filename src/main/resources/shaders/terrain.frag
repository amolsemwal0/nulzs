#version 330 core

in vec3 worldPos;
in vec3 worldNormal;
in vec3 vertexColor;
in vec4 groundWeights;
in vec4 groundWeightsHigh;
in vec4 groundWeightsThird;

layout(location = 0) out vec4 fragColor;
// The geometry buffer GlobalIllumination gathers from: world normal, and how much of this fragment's light
// was ambient fill. Written by every opaque scene pass; see the note where it is filled in at the bottom of
// main. A target that is not attached simply discards the write, so this costs nothing in a pass rendering
// somewhere that has no geometry buffer — a preview, or the water's mirrored render.
layout(location = 1) out vec4 fragGeometry;

uniform vec3 viewPos;

// toSun points from the surface toward the sun, so it is the light vector directly — no negation, which
// is one fewer sign convention to get wrong.
uniform vec3 toSun;
uniform vec3 sunColor;
uniform vec3 ambientColor;

uniform vec3 rimColor;
uniform float rimPower;
uniform float rimThreshold;

// Ground cover, stood in for past the distance real cover is drawn at — see GroundDetail. Zero strength
// disables the whole thing, which is what the prop pass sets since a prop is not ground.
uniform vec3 coverColor;
uniform float coverMatch;
uniform float coverFadeStart;
uniform float coverFadeEnd;
uniform float coverStrength;
uniform float coverScale;
uniform float coverDarkening;

// Hand-rolled value-noise fBm, the same trick skybox.frag and fluid.frag use (JNoise is Java-only, and
// GLSL 330 has no #include so each scene shader carries its own copy).
float coverHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float coverNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(coverHash(i), coverHash(i + vec2(1.0, 0.0)), u.x),
               mix(coverHash(i + vec2(0.0, 1.0)), coverHash(i + vec2(1.0, 1.0)), u.x), u.y);
}

/** The scene clock, for the magma gradient below. Nothing else here animates. */
uniform float time;

// --- Molten rock ---------------------------------------------------------------------------------
// Magma is drawn from a procedural orange-through-yellow gradient rather than from art, exactly as the
// lava that wells out of it is — see fluid.frag's `moltenGradient`, which this is the terrain half of.
//
// The three stops are DUPLICATED between the two shaders and must stay equal, because the solid rock of
// the conduit and the melt standing in it have to be visibly the same substance. There is no shared GLSL
// include here (GLSL 330 has none), which is the same position the wind sway rate is in, so it is settled
// the same way: `MoltenGradientTest` reads both files and pins that they agree, and pins these against
// `Fluids.palette()`'s lava entry, which is where the values are actually authored.
const vec3 MOLTEN_COOL = vec3(0.45, 0.05, 0.01);
const vec3 MOLTEN_EMBER = vec3(1.60, 0.40, 0.05);
const vec3 MOLTEN_HOT = vec3(3.40, 2.60, 0.60);

/** Where the middle stop sits along the heat range. See fluid.frag, which explains why it is past half. */
const float MOLTEN_MID = 0.62;

/** Reciprocal world units of the temperature field — one warm patch spans about 1 / this. */
const float MOLTEN_FIELD_SCALE = 0.22;

/**
 * How hot solid magma runs on average, and how far either side of that its field wanders.
 *
 * <p>Hotter than a flow on purpose — {@code fluid.frag}'s centre is lower — because a tongue on the flank
 * has cooled on its way down while this is the feeder itself, and because it is only ever seen where a cave
 * or a dig has cut into it, where reading unmistakably as *molten* matters. But not much hotter: at 0.62
 * the whole exposed face sat past {@link MOLTEN_MID} and the vent floor came back a sheet of near-white
 * with no gradient left in it, which is the same blowout the fluid's own centre had to come down for.
 *
 * <p>The spread is wide because this is a large flat face seen close up, where a narrow one reads as a
 * painted slab rather than as rock at varying temperature.
 */
const float MAGMA_BASE_HEAT = 0.50;
const float MAGMA_HEAT_SPREAD = 0.62;

float moltenFbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * coverNoise(p);
        p *= 2.0;
        amp *= 0.5;
    }
    return sum;
}

/**
 * The gradient: dull red through orange to yellow, with the upper two stops above 1.
 *
 * <p>Everything past 1 is split off as an unlit glow further down, which survives shadow and blooms — so
 * this ramp is what makes magma read as something emitting light rather than as orange paint.
 */
vec3 moltenGradient(float heat) {
    float h = clamp(heat, 0.0, 1.0);
    return h < MOLTEN_MID
            ? mix(MOLTEN_COOL, MOLTEN_EMBER, h / MOLTEN_MID)
            : mix(MOLTEN_EMBER, MOLTEN_HOT, (h - MOLTEN_MID) / (1.0 - MOLTEN_MID));
}

/**
 * How hot this patch of solid magma is.
 *
 * <p>Projected on the plane the surface lies in rather than straight down, for the reason the ground
 * materials are triplanar: a conduit wall is vertical, and a field taken from world XZ alone smears into
 * vertical streaks down exactly the faces this material is seen on.
 *
 * <p>It drifts, slowly. Solid magma is rock rather than a flow, so this is convection under a crust
 * instead of the melt travelling — but it must move at all, or the one glowing thing in the world is the
 * one thing in it that is visibly a still image.
 */
float magmaHeat(vec3 p, vec3 n) {
    vec2 q = abs(n.y) > 0.5 ? p.xz : vec2(p.x + p.z, p.y);
    q = q * MOLTEN_FIELD_SCALE + vec2(0.0, time * 0.03);
    float field = moltenFbm(q) * 0.65 + moltenFbm(q * 2.6 - vec2(time * 0.05, 0.0)) * 0.35;
    // The fBm sits around 0.5 with most of its mass in the middle of its range, so the ramp is placed
    // either side of that rather than over [0,1] — over the full range it barely leaves mid-orange.
    return clamp(MAGMA_BASE_HEAT + (smoothstep(0.30, 0.70, field) - 0.5) * MAGMA_HEAT_SPREAD, 0.0, 1.0);
}

/**
 * How much this fragment should read as covered ground, and how patchy that cover is.
 *
 * Two gates. Distance, so near ground — where real tufts are standing — is left completely alone and only
 * the far field is painted. And colour: terrain whose albedo sits near coverColor is the ground this cover
 * grows on. The colour test is honest rather than a guess because the grassland biome is literally painted
 * with the grass model's own texel, and it blends for free at a biome edge because the albedo does.
 */
float coverAmount(vec3 albedo, float viewDistance) {
    if (coverStrength <= 0.0) {
        return 0.0;
    }
    float reach = smoothstep(coverFadeStart, coverFadeEnd, viewDistance);
    if (reach <= 0.0) {
        return 0.0;
    }
    float belongs = 1.0 - smoothstep(coverMatch * 0.4, coverMatch, distance(albedo, coverColor));
    return reach * belongs * coverStrength;
}

// --- Ground materials ---------------------------------------------------------------------------
// Tiling materials laid over the ground they belong to — see GroundTextures. There are no UVs on terrain,
// so each is projected from world space, triplanarly because the ground they cover is mostly slopes. A
// zero count disables the whole block, which is what the prop pass sets.
//
// Which material a fragment wears arrives as a PER-VERTEX WEIGHT, not as a colour the shader tries to
// recognise. Colour keys work for a material that owns a distinctive shade and cannot express a boundary:
// halfway between two biomes the ground is a colour near neither key, so the treatment vanishes in the
// middle of the transition instead of blending. Weights are a smooth field, so a boundary is a genuine
// mixture and grades exactly where the biome does.
//
// The art is two ARRAY textures with one layer per material, never a sampler per material: GLSL 3.30 lets
// an array of samplers be indexed only by a constant expression. A layer is a coordinate, so it can be
// anything. (Plain uniform arrays below have no such restriction and are indexed by the loop.)
#define MAX_GROUND_MATERIALS 12
uniform sampler2DArray groundAlbedo;
uniform sampler2DArray groundNormal;
uniform int groundCount;
uniform int groundHasNormal;
uniform vec3 groundMean[MAX_GROUND_MATERIALS];
uniform float groundTile[MAX_GROUND_MATERIALS];
uniform float groundNormalStrength[MAX_GROUND_MATERIALS];
uniform float groundGrainStrength[MAX_GROUND_MATERIALS];
uniform float groundColorInfluence[MAX_GROUND_MATERIALS];

// NOTE: there used to be a `groundEmissive` scale here, so that magma's ground art could decide where the
// glow went rather than have a flat sheet of light laid over it. Magma no longer wears a ground material
// at all — it is drawn from `moltenGradient` above, which is a procedural surface and a glow in one — so
// the whole set is unlit again and the scale had exactly one user left with nothing to do.
uniform float groundFadeStart;
uniform float groundFadeEnd;

/**
 * How much ground material reaches this fragment at all.
 *
 * Distance gates it, and runs the opposite way to coverAmount's: cover paints the *far* field, where
 * instanced tufts have thinned out, while a surface texture is only worth sampling *near*, because past a
 * point one tile is under a pixel and mipmapping has already averaged it back to that material's own mean
 * — which by construction is no change at all.
 */
float groundReach(float viewDistance) {
    if (groundCount == 0) {
        return 0.0;
    }
    return 1.0 - smoothstep(groundFadeStart, groundFadeEnd, viewDistance);
}

// --- Ground pieces -------------------------------------------------------------------------------
// Whole pictures over a tiled map's blocks of tiles, one per block: see GroundPieces. pieceLayout holds a
// texel per block whose red is the layer + 1, read with texelFetch so a block never blends with the next
// one's number. Off (piecesOn 0) in the prop pass: a rock on a path block is not the path.
uniform int piecesOn;
uniform sampler2DArray pieceAlbedo;
uniform sampler2DArray pieceNormal;
uniform sampler2D pieceLayout;
uniform int pieceHasNormal;
uniform vec2 pieceOrigin;
uniform float pieceBlockSize;
uniform vec2 pieceGrid;
uniform float pieceNormalStrength;
uniform float pieceFadeStart;
uniform float pieceFadeEnd;

/** Blend weights for the three planar projections, sharpened so faces are mostly one sample. */
vec3 triplanarBlend(vec3 n) {
    vec3 blend = pow(abs(n), vec3(4.0));
    return blend / max(blend.x + blend.y + blend.z, 1e-4);
}

uniform float saturation;

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

// --- Sun shadowing ------------------------------------------------------------------------------
// Kept as a copy in each scene shader rather than shared, for the same reason the cel band is: GLSL 330
// has no #include and this project loads each shader as a single file. If one copy changes, all must.
uniform sampler2DShadow shadowMap;
uniform mat4 lightSpaceMatrix;
uniform float shadowTexelUv;
uniform float shadowTexelWorld;
uniform float shadowBias;
uniform float shadowStrength;

// How much of the key light reaches this fragment: 1 lit, 0 fully shadowed.
float sunVisibility(vec3 position, vec3 normal, float NdotL) {
    // Zero strength is the "no shadows this frame" path — a grazing sun, or a renderer that never ran a
    // shadow pass at all. Skipping the nine taps then is not just an optimisation: the map's contents
    // are undefined in that case.
    if (shadowStrength <= 0.0) {
        return 1.0;
    }

    // Normal offset rather than a large depth bias. Pushing the lookup off the surface — further where
    // the light grazes, since that is where a texel covers the most depth — kills acne without moving
    // the comparison along the light ray, which is what detaches a shadow from the foot of its caster.
    float slope = clamp(1.0 - NdotL, 0.0, 1.0);
    vec3 offset = normal * shadowTexelWorld * (1.5 + 3.0 * slope);
    vec4 lightClip = lightSpaceMatrix * vec4(position + offset, 1.0);
    vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;

    // Beyond the far plane of the light's box there is nothing recorded to be shadowed by.
    if (proj.z > 1.0) {
        return 1.0;
    }

    float bias = shadowBias * (1.0 + 4.0 * slope);
    float lit = 0.0;
    // Nine hardware-compared taps. Each one is already bilinearly filtered across four texels, so the
    // kernel covers ~4 texels of penumbra with a smooth ramp instead of the ten discrete steps a
    // compare-in-the-shader kernel can produce. Taps are offset by half a texel so the four texels each
    // one blends are weighted evenly rather than landing centred on one of them.
    float reference = proj.z - bias;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 tap = proj.xy + (vec2(x, y) + 0.5) * shadowTexelUv;
            lit += texture(shadowMap, vec3(tap, reference));
        }
    }
    // Tighten the ramp. Every tap is already bilinearly filtered by the hardware, so the raw average is a
    // soft gradient several texels wide; steepening it keeps that antialiasing while making the shadow
    // read as a *shape with an edge* rather than a smudge — which is the same thing the cel bands do to
    // the diffuse, and the reason a stylised scene wants this and a photoreal one would not.
    float visibility = smoothstep(0.15, 0.85, lit / 9.0);

    // Fade out toward the edge of the map. The box only covers a radius around the player, and without
    // this the world would show a hard square edge travelling with them where shadows simply stop.
    vec2 fromCentre = abs(proj.xy - 0.5) * 2.0;
    visibility = mix(visibility, 1.0, smoothstep(0.8, 1.0, max(fromCentre.x, fromCentre.y)));

    return mix(1.0, visibility, shadowStrength);
}

// The weights span three attributes because a vec4 holds four. Kept as one accessor so the loop below
// reads as though the set were contiguous, which is how every other layer of this system treats it.
float groundWeightAt(int i) {
    return i < 4 ? groundWeights[i] : i < 8 ? groundWeightsHigh[i - 4] : groundWeightsThird[i - 8];
}

// --- Lamps -------------------------------------------------------------------------------------------
// Light from the points near this fragment: street lamps after dark. A copy, like the shadow lookup, in
// terrain.frag and foliage.frag -- GLSL 330 has no #include -- and PointLightShaderTest pins the copies
// equal: a lamp's pool has to light the paving, the bench on it, the wall behind it and the person
// walking past by the same rule, or it stops at the edge of a material instead of at the edge of its light.
//
// The pool is BANDED like the sun's light, three steps from its bright heart out to its rim, so it reads
// as a painted pool of light on cel-shaded ground rather than a photographic glow. Under the bands it
// falls off as the inverse square does, windowed to nothing at the light's radius, so there is never an
// edge where it stops. A surface turned away from the lamp gets none, and one side-on still gets some --
// the wrap -- so a post or a person beside the lamp is lit down the side facing it. There is no shadow: a
// lamp lights through whatever stands between it and a surface, which its radius keeps to its own street.
//
// Only the lights PointLights picks for the frame are here, at most MAX_POINT_LIGHTS, and none by day.
const int MAX_POINT_LIGHTS = 16;
uniform int pointLightCount;
uniform vec3 pointLightPosition[MAX_POINT_LIGHTS];
uniform vec3 pointLightColor[MAX_POINT_LIGHTS];
uniform float pointLightRadius[MAX_POINT_LIGHTS];

vec3 pointLight(vec3 position, vec3 normal) {
    vec3 total = vec3(0.0);
    for (int i = 0; i < pointLightCount; i++) {
        vec3 toLight = pointLightPosition[i] - position;
        float distance = length(toLight);
        float reach = distance / pointLightRadius[i];
        if (reach >= 1.0) {
            continue;
        }
        float window = 1.0 - reach * reach;
        float falloff = window * window / (1.0 + 16.0 * reach * reach);
        float pool = smoothstep(0.018, 0.026, falloff) * 0.25
                   + smoothstep(0.060, 0.080, falloff) * 0.30
                   + smoothstep(0.180, 0.220, falloff) * 0.45;
        float facing = clamp((dot(normal, toLight / max(distance, 1e-4)) + 0.5) / 1.5, 0.0, 1.0);
        total += pointLightColor[i] * pool * facing;
    }
    return total;
}

/**
 * Everything below this world height is discarded.
 *
 * <p>For the planar water reflection: the mirrored camera sees the far side of the water plane, and the
 * ground under the pond would otherwise be drawn upside down into the reflection and read as silt floating
 * in the sky. Its default is far below the world, so every ordinary pass is unaffected and nothing but the
 * reflection pass ever pays for it.
 *
 * <p>A fragment discard rather than {@code gl_ClipDistance}, which would be the tidier tool and would mean
 * touching every vertex shader that draws in that pass plus enabling a clip plane around it. Two lines
 * here, in the two shaders the pass actually uses, is a much smaller change for the same result.
 */
uniform float clipBelowY;

/**
 * What an HDR albedo means in this pass: molten rock, or a light.
 *
 * <p>An albedo above 1 is this shader's signal that a fragment emits, and until villages existed it had
 * exactly one producer — {@code TerrainMaterial.MAGMA} — so the signal and the substitution below could be
 * the same test. A village lamp broke that: it is drawn through this same shader, and given an HDR colour
 * it came out as a lantern full of <b>animated orange lava</b>.
 *
 * <p>The pass is the honest discriminator rather than another colour key, because <b>magma only ever
 * occurs in terrain</b>: {@code Renderer.renderTerrain} sets this and {@code renderProps} clears it, so no
 * caller has to know and no frame state has to be put back. Every prop that existed before this has an
 * albedo at or under 1, so nothing changes for any of them.
 */
uniform int emissiveIsMolten;

/**
 * How hard an emissive material burns, as a multiplier on the surplus over 1.
 *
 * <p>1 is the authored strength and is what the game runs at. It exists as a knob because "how brightly do
 * the lamps burn" is a question that will be asked — a lights-out state, or dimming them by day — and
 * because it is the only way to render the <b>control</b> for an emissive material: a pane that was never
 * baked HDR, a surplus the shader threw away, and a lamp working perfectly all produce a picture of a
 * lamp, so the only thing that can fail is the pair.
 */
uniform float emissiveScale;

// --- The map's water -----------------------------------------------------------------------------
// Rivers, lakes, their falls and the sea, drawn through this shader when the map's water has art of its own
// (Renderer.renderWater, WaterLook) so it is lit, shadowed and fogged exactly as the ground beside it. Every
// other pass sets waterPass 0 and none of this runs.
//
// Where each texel's water goes comes from the map's flow picture (WaterFlow): R, G the current across x and
// z, B how far it is to land (by the smooth shore line, Shoreline, so a little into the land as well), A 1 for a
// river's or lake's own water, and the land beside it, and 0 for the sea.
//
// No texture read here sits in a branch that neighbouring fragments can take differently: a fragment works out
// the river, the sea and a fall alike and mixes them, so every derivative is taken in uniform control flow (the
// same trap the ground block above steers round with textureGrad).
uniform int waterPass;
uniform sampler2DArray waterAlbedo;   // layers: river (still), ocean, foam, running
uniform sampler2DArray waterNormal;
uniform int waterHasNormal;
uniform sampler2D waterFlow;
uniform vec4 waterFlowRect;           // origin x, z; size x, z
uniform float waterShoreRange;
uniform vec4 waterTiles;              // metres a picture: river (still), ocean, foam, running
uniform float waterFlowSpeed;
uniform vec3 waterShallow;
uniform float waterWaveLength;
uniform float waterWaveReach;

/** One water picture at a point; its relief (along x and z, as the ground's top projection reads it) into relief. */
vec3 waterTexel(float layer, vec2 uv, inout vec2 relief, float weight) {
    vec3 c = texture(waterAlbedo, vec3(uv, layer)).rgb;
    vec3 n = texture(waterNormal, vec3(uv, layer)).rgb * 2.0 - 1.0;
    relief += n.xy * weight * float(waterHasNormal);
    return c;
}

// The flow texture holds two pictures of the map, one over the other (WaterFlow): the flow on top, and under it where
// falls come down — R how churned the water is at a fall's foot, G and B each cell's level, A whether it holds water.
// The levels are WaterFlow.LEVEL_FLOOR and LEVEL_STEPS: they must stay equal.
const float WATER_LEVEL_FLOOR = -1024.0;
const float WATER_LEVEL_STEPS = 32.0;
// The flow's B reaches this far into the land below 0 (Shoreline.INSIDE): read filtered, it is 0 right on the smooth
// line the land is cut to, so the foam along a shore follows it. It must stay equal.
const float WATER_SHORE_INSIDE = 3.0;

/** Where a point lies on the map's pictures, 0 to 1 across it. */
vec2 waterMapUv(vec2 xz) {
    return (xz - waterFlowRect.xy) / waterFlowRect.zw;
}

/** The top picture, the flow, at a point: kept off the bottom one, which would bleed into its last row. */
vec4 waterFlowAt(vec2 xz) {
    float halfTexel = 0.5 / float(textureSize(waterFlow, 0).y);
    vec2 uv = waterMapUv(xz);
    return texture(waterFlow, vec2(uv.x, clamp(uv.y * 0.5, halfTexel, 0.5 - halfTexel)));
}

/** How churned the water is by a fall coming down into it, at a point: 1 at the foot, settling to 0. */
float waterChurnAt(vec2 xz) {
    float halfTexel = 0.5 / float(textureSize(waterFlow, 0).y);
    vec2 uv = waterMapUv(xz);
    return texture(waterFlow, vec2(uv.x, clamp(0.5 + uv.y * 0.5, 0.5 + halfTexel, 1.0 - halfTexel))).r;
}

/** The level of the water (or ground, where it has none) of the cell a point is in, metres; w 1 if it holds water. */
vec2 waterCellLevel(vec2 xz) {
    ivec2 size = textureSize(waterFlow, 0);
    ivec2 cells = ivec2(size.x, size.y / 2);
    ivec2 cell = clamp(ivec2(floor(waterMapUv(xz) * vec2(cells))), ivec2(0), cells - 1);
    vec4 t = texelFetch(waterFlow, ivec2(cell.x, cells.y + cell.y), 0);
    float steps = floor(t.g * 255.0 + 0.5) * 256.0 + floor(t.b * 255.0 + 0.5);
    return vec2(steps / WATER_LEVEL_STEPS + WATER_LEVEL_FLOOR, step(0.5, t.a));
}

// Hashes for the water's own patterns: arithmetic, so they hold steady thousands of metres out, as sine hashes don't.
float waterHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 waterHash2(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float waterNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(waterHash(i), waterHash(i + vec2(1.0, 0.0)), u.x),
               mix(waterHash(i + vec2(0.0, 1.0)), waterHash(i + vec2(1.0, 1.0)), u.x), u.y);
}

/**
 * Boiling foam: round bubbles about a jittered grid, each wandering a little and swelling and shrinking on its own
 * beat, the bigger the more churned the water is, until they run together into solid white. In grid units: x how far
 * a point is outside the nearest bubble (less than 0 inside it), y how near that bubble's rim it is, 0 at its middle.
 */
vec2 waterBubbles(vec2 p, float t, float churn) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    vec2 nearest = vec2(1e9, 0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec2 h = waterHash2(cell + o);
            vec2 centre = o + 0.5 + 0.28 * sin(t * (0.5 + h) + 6.2831 * h.yx);
            float beat = 0.5 + 0.5 * sin(t * (1.8 + 2.2 * h.x) + 6.2831 * h.y);
            float radius = (0.16 + 0.42 * churn) * (0.7 + 0.5 * beat) * (0.65 + 0.7 * h.y);
            float d = length(f - centre) - radius;
            if (d < nearest.x) {
                nearest = vec2(d, clamp(1.0 + d / max(radius, 1e-3), 0.0, 1.0));
            }
        }
    }
    return nearest;
}

/**
 * How much of a point is churned white water, and its colour: a solid mass where the water is churned most, wobbling
 * at its edge, broken into boiling bubbles of two sizes as it settles. Bubbles are shaded pale blue toward their rims
 * so they read round. {@code p} is in metres on the surface; far off, where bubbles would shimmer, it evens out.
 */
vec4 waterFoamAt(vec2 p, float churn, float t) {
    float wobble = waterNoise(p * 0.9 + vec2(t * 0.35, -t * 0.25)) - 0.5;
    float solid = smoothstep(0.6, 0.68, churn + wobble * 0.3);
    vec2 bigP = p * 1.7;
    vec2 smallP = p * 3.6 + 7.3;
    // Taken out here, where every fragment of a quad takes it: the bubbles below are only worked out where there are any.
    float bigSoft = length(fwidth(bigP)) * 0.7 + 0.02;
    float smallSoft = length(fwidth(smallP)) * 0.7 + 0.02;
    float far = smoothstep(0.25, 0.6, bigSoft);
    float bubbles = 0.0;
    float rim = 0.0;
    if (churn > 0.02 && far < 0.999) {
        vec2 big = waterBubbles(bigP, t * 1.6, churn);
        vec2 small = waterBubbles(smallP, t * 2.3, churn * 0.85);
        float bigCover = 1.0 - smoothstep(-bigSoft, bigSoft, big.x);
        float smallCover = 1.0 - smoothstep(-smallSoft, smallSoft, small.x);
        bubbles = max(bigCover, smallCover * 0.95) * smoothstep(0.04, 0.22, churn);
        rim = bigCover >= smallCover ? big.y : small.y;
    }
    // Past where single bubbles can be made out, a soft white as thick as they would be on average.
    bubbles = mix(bubbles, smoothstep(0.08, 0.7, churn) * 0.85, far);
    float amount = max(solid, bubbles);
    float shade = mix(rim * rim * 0.75 * (1.0 - solid), 0.25 * smoothstep(0.2, 0.0, wobble + 0.3), far);
    vec3 colour = mix(vec3(0.97, 0.99, 1.0), vec3(0.66, 0.84, 0.94), shade);
    return vec4(colour, amount);
}

// Running water wears its own picture (WaterLook.RUNNING), the picture's x laid along the current and slid down it. A
// picture can't be bent round a flow that turns without tearing, so the map is cut into cells WATER_RUN_CELL metres a
// side, each turning the whole picture (about the map's origin) to its own flow, put to one of WATER_RUN_TURNS ways and
// WATER_RUN_SPEEDS speeds: neighbouring cells that run alike then lay exactly the same picture, and a point mixing its
// four nearest cells doubles nothing. Only where cells run differently do two pictures mix, and there the mix is pushed
// back out to the picture's own contrast rather than going pale.
const float WATER_RUN_CELL = 2.0;
const float WATER_RUN_TURNS = 32.0;
const float WATER_RUN_SPEEDS = 4.0;
/** How fast a fall pours, metres a second. */
const float WATER_FALL_SPEED = 3.2;

/** How the cell centred at a point lays the running picture: x, y the way it runs (a unit vector), z how fast. */
vec3 waterRunWay(vec2 centre) {
    vec2 f = waterFlowAt(centre).rg * 2.0 - 1.0;
    float strength = length(f);
    float turn = 6.2831853 / WATER_RUN_TURNS;
    float angle = strength > 0.02 ? floor(atan(f.y, f.x) / turn + 0.5) * turn : 0.0;
    float speed = max(floor(strength * WATER_RUN_SPEEDS + 0.5), 1.0) / WATER_RUN_SPEEDS * waterFlowSpeed;
    return vec3(cos(angle), sin(angle), speed);
}

/**
 * The running picture's colour at a point (metres, on the map). It is flat: the picture has no relief of its own drawn
 * (the user's choice). {@code mean} is its average colour; {@code dpdx}, {@code dpdy} how the point moves across the
 * screen, for the picture's mip level, worked out once because each cell's turn would jump it at the cell's edge.
 */
vec3 waterRunning(vec2 p, vec2 dpdx, vec2 dpdy, vec3 mean) {
    vec2 g = p / WATER_RUN_CELL - 0.5;
    vec2 corner = floor(g);
    vec2 f = g - corner;
    vec3 way[4];
    float share[4];
    for (int i = 0; i < 4; i++) {
        vec2 o = vec2(float(i & 1), float(i >> 1));
        way[i] = waterRunWay((corner + o + 0.5) * WATER_RUN_CELL);
        vec2 s = mix(1.0 - f, f, o);
        share[i] = s.x * s.y;
    }
    // How much of the mix is one picture: 1 where the four cells run alike, down to a quarter where all four differ.
    float alike = 0.0;
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            alike += share[i] * share[j] * (1.0 - step(1e-4, distance(way[i], way[j])));
        }
    }
    float tile = waterTiles.w;
    vec3 colour = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 along = way[i].xy;
        vec2 across = vec2(-along.y, along.x);
        vec2 uv = vec2(dot(p, along) / tile - fract(time * way[i].z / tile), dot(p, across) / tile);
        vec2 gx = vec2(dot(dpdx, along), dot(dpdx, across)) / tile;
        vec2 gy = vec2(dot(dpdy, along), dot(dpdy, across)) / tile;
        vec3 c = textureGrad(waterAlbedo, vec3(uv, 3.0), gx, gy).rgb;
        colour += share[i] * (c - mean);
    }
    return clamp(mean + colour * inversesqrt(max(alike, 0.25)), 0.0, 1.0);
}

/**
 * The water's own colour and relief here, and how much of it is foam (which the sun glint skips).
 *
 * A river wears the running picture laid along its current and slid downstream (waterRunning), lighter by the banks.
 * Still water (a lake, or the slack middle of a wide one) keeps the still river art, drifting. The sea is two
 * layers drifting different ways, the lighter of the two showing, so its crests come and go. Waves of foam roll in to
 * every shore in bands, and a frill laps at the edge.
 *
 * A fall pours the running picture down its face, a bright lip where it tips over, and where it comes down into water,
 * white water boils: up the foot of the fall, and out over the water below, settling into bubbles as it spreads.
 */
void waterSurface(inout vec3 albedo, inout vec3 normal, out float foam) {
    vec3 geometric = normalize(worldNormal);
    vec4 cellFlow = waterFlowAt(worldPos.xz);
    vec2 flow = (cellFlow.rg * 2.0 - 1.0) * waterFlowSpeed;
    float shore = cellFlow.b * (waterShoreRange + WATER_SHORE_INSIDE) - WATER_SHORE_INSIDE;
    float own = smoothstep(0.4, 0.6, cellFlow.a);
    float along = coverNoise(worldPos.xz * 0.045);
    float isFall = 1.0 - smoothstep(0.3, 0.6, geometric.y);

    // Rivers and lakes.
    vec2 riverRelief = vec2(0.0);
    vec2 base = worldPos.xz / waterTiles.x;
    float moving = smoothstep(0.04, 0.2, length(flow));
    vec3 still = waterTexel(0.0, base + vec2(0.011, 0.007) * time / waterTiles.x * 6.0, riverRelief, 1.0 - moving);
    // The running picture's average: the smallest step of its mipmaps.
    vec3 runMean = textureLod(waterAlbedo, vec3(0.5, 0.5, 3.0), 16.0).rgb;
    vec3 running = waterRunning(worldPos.xz, dFdx(worldPos.xz), dFdy(worldPos.xz), runMean);
    vec3 river = mix(still, running, moving);
    // Lighter along the banks, where it runs shallow.
    river = mix(river, river * 1.18 + vec3(0.04, 0.06, 0.06), (1.0 - smoothstep(0.2, 1.6, shore)) * 0.6);

    // The sea.
    vec2 seaRelief = vec2(0.0);
    vec2 seaUv = worldPos.xz / waterTiles.y;
    vec3 seaA = waterTexel(1.0, seaUv + time * vec2(0.010, 0.006), seaRelief, 0.5);
    vec3 seaB = waterTexel(1.0, mat2(0.77, -0.64, 0.64, 0.77) * seaUv * 1.31 + time * vec2(-0.007, 0.009)
                           + vec2(0.31, 0.57), seaRelief, 0.5);
    vec3 sea = max(seaA, seaB * 0.98);
    sea = mix(sea, waterShallow, (1.0 - smoothstep(0.0, 9.0, shore)) * 0.55);

    // Foam: waves rolling in to the shore, unevenly along it, and a frill swelling and ebbing at the edge.
    float lace = texture(waterAlbedo, vec3(worldPos.xz / waterTiles.z + time * vec2(0.004, 0.006), 2.0)).r;
    float band = fract(shore / waterWaveLength + time * 0.11 + along * 0.9);
    float crest = smoothstep(0.0, 0.035, band) * (1.0 - smoothstep(0.06, 0.2, band));
    float wave = crest * (1.0 - smoothstep(waterWaveReach * 0.35, waterWaveReach, shore));
    float lap = 0.9 + 0.6 * sin(time * 0.9 + along * 6.0);
    float frill = 1.0 - smoothstep(lap * 0.5, lap * 1.4, shore);
    float bank = 1.0 - smoothstep(0.1, 0.45, shore);
    float amount = mix(max(wave, frill), bank * 0.85, own);
    float topFoam = smoothstep(0.45, 0.55, amount * (0.55 + 0.6 * lace));

    // White water at the foot of a fall, out over the water it comes down into.
    float churn = waterChurnAt(worldPos.xz) * (1.0 - isFall);
    vec4 pool = waterFoamAt(worldPos.xz, churn, time);

    // A fall: the running picture pouring down its face, its x straight down, a bright lip where it tips over, and
    // white water boiling up its foot.
    vec2 acrossFace = vec2(-geometric.z, geometric.x);
    float a = dot(worldPos.xz, acrossFace);
    vec2 outward = normalize(geometric.xz + vec2(1e-4));
    vec2 lip = waterCellLevel(worldPos.xz - outward * 0.5);
    vec2 foot = waterCellLevel(worldPos.xz + outward * 0.5);
    float drop = max(lip.x - foot.x, 0.0);
    vec2 pourUv = vec2(-worldPos.y / waterTiles.w - fract(time * WATER_FALL_SPEED / waterTiles.w), a / waterTiles.w);
    vec3 dpdx = dFdx(worldPos);
    vec3 dpdy = dFdy(worldPos);
    vec3 poured = textureGrad(waterAlbedo, vec3(pourUv, 3.0),
                              vec2(-dpdx.y, dot(dpdx.xz, acrossFace)) / waterTiles.w,
                              vec2(-dpdy.y, dot(dpdy.xz, acrossFace)) / waterTiles.w).rgb;
    // A little deeper toward the foot.
    vec3 fallWater = poured * mix(1.0, 0.82, smoothstep(0.0, max(drop, 0.5), lip.x - worldPos.y) * 0.6);
    float lipBand = 1.0 - smoothstep(0.06, 0.3, lip.x - worldPos.y);
    fallWater = mix(fallWater, vec3(0.92, 0.97, 1.0), lipBand * 0.85);
    // Up the foot, as high as a share of the drop, its top edge boiling.
    float aboveFoot = worldPos.y - foot.x;
    float footReach = clamp(drop * 0.22, 0.6, 2.2) * (0.72 + 0.4 * waterNoise(vec2(a * 1.4, time * 1.5)));
    float footChurn = (1.0 - smoothstep(footReach * 0.55, footReach, aboveFoot)) * foot.y * step(0.5, drop) * isFall;
    vec4 footFoam = waterFoamAt(vec2(a, worldPos.y * 1.3 - time * 0.5), footChurn, time);

    vec3 top = mix(sea, river, own);
    vec2 relief = mix(seaRelief, riverRelief, own);
    vec3 surfaceColour = mix(top, fallWater, isFall);
    float whiteWater = max(pool.a, footFoam.a);
    vec3 whiteColour = pool.a >= footFoam.a ? pool.rgb : footFoam.rgb;
    surfaceColour = mix(surfaceColour, whiteColour, whiteWater);
    foam = max(mix(topFoam, 0.0, isFall), whiteWater);
    albedo = min(mix(surfaceColour, vec3(0.96, 0.985, 1.0), mix(topFoam, 0.0, isFall) * (1.0 - whiteWater)), vec3(1.0));
    // The relief only tilts a surface facing up, and foam lies flat on it. A fall is lit as if it leaned back toward
    // the sky, so it pours bright white-blue as falls are drawn, rather than going grey on its shaded face.
    vec3 tilted = normalize(geometric + vec3(relief.x, 0.0, relief.y) * 0.6 * (1.0 - foam));
    vec3 leaning = normalize(geometric + vec3(0.0, 1.4, 0.0));
    normal = normalize(mix(tilted, leaning, isFall));
}

void main() {
    if (worldPos.y < clipBelowY) {
        discard;
    }

    vec3 normal = normalize(worldNormal);
    vec3 toView = normalize(viewPos - worldPos);

    // Emissive split: a material may carry an HDR albedo above 1 (magma). The part up to 1 is lit
    // normally; the excess is added later as an unlit glow that survives shadow and blooms. Ordinary
    // terrain has albedo <= 1, so emissive is zero and nothing changes for it.
    vec3 albedo = min(vertexColor, vec3(1.0));
    float viewDistance = length(viewPos - worldPos);

    // Tiling ground materials, projected from world space because terrain has no UVs, and mixed by the
    // per-vertex weights so a biome boundary is a real blend of both biomes' art rather than a gap where
    // neither is recognised.
    float groundFade = groundReach(viewDistance);
    float groundTotal = dot(groundWeights, vec4(1.0)) + dot(groundWeightsHigh, vec4(1.0))
                      + dot(groundWeightsThird, vec4(1.0));
    if (groundFade > 0.0 && groundTotal > 0.001) {
        vec3 blend = triplanarBlend(normal);
        vec3 mixedTarget = vec3(0.0);
        vec3 mixedNormal = vec3(0.0);
        float mixedNormalStrength = 0.0;

        // Screen-space derivatives of the three planar projections, taken ONCE and out here where control
        // flow is still uniform. They have to be: a derivative is computed across a quad of fragments, so
        // asking for one inside a branch that some of those fragments skipped is undefined — and the
        // sampling below is exactly such a branch, since a fragment only pays for the materials it wears.
        // Every texture read in the loop is therefore textureGrad rather than texture, which is what makes
        // skipping a material safe instead of merely usually-working. Same family of trap as indexing a
        // sampler array by a computed value.
        //
        // Taken at tile size 1 and divided per material: the projection is linear in 1/tile, so this is
        // exact rather than an approximation, and it costs six dFdx/dFdy instead of six per material.
        vec2 dXdx = dFdx(worldPos.zy);
        vec2 dXdy = dFdy(worldPos.zy);
        vec2 dYdx = dFdx(worldPos.xz);
        vec2 dYdy = dFdy(worldPos.xz);
        vec2 dZdx = dFdx(worldPos.xy);
        vec2 dZdy = dFdy(worldPos.xy);

        for (int i = 0; i < groundCount; i++) {
            float w = groundWeightAt(i);
            if (w <= 0.0) {
                continue;
            }
            float layer = float(i);
            float tile = groundTile[i];
            float inv = 1.0 / tile;
            vec3 uvX = vec3(worldPos.zy * inv, layer);
            vec3 uvY = vec3(worldPos.xz * inv, layer);
            vec3 uvZ = vec3(worldPos.xy * inv, layer);

            vec3 sampled = textureGrad(groundAlbedo, uvX, dXdx * inv, dXdy * inv).rgb * blend.x
                         + textureGrad(groundAlbedo, uvY, dYdx * inv, dYdy * inv).rgb * blend.y
                         + textureGrad(groundAlbedo, uvZ, dZdx * inv, dZdy * inv).rgb * blend.z;

            // Two readings of what a ground texture is for, and colorInfluence picks between them.
            // Divided by the art's own mean it is pure *grain*: a mean-valued texel changes nothing, so
            // the vertex colour — already through the cave tint, the biome blend and the bare-rock pull —
            // survives intact. Used directly it is the *surface*: the ground simply is the art.
            // Variation about the art's own mean, scaled: a mean-valued texel is 1 whatever the
            // strength, so this only decides how far the grain departs from leaving the ground
            // alone. Needed because these files are authored at wildly different contrast.
            vec3 grain = mix(vec3(1.0), sampled / max(groundMean[i], vec3(1e-3)),
                             groundGrainStrength[i]);
            vec3 grained = albedo * grain;
            mixedTarget += w * mix(grained, sampled, groundColorInfluence[i]);

            // Skipped where a material has no usable relief, which is not hypothetical: every map in
            // the ore set is a broken export and carries strength zero, so this saves them three texture
            // reads apiece. Safe inside the branch because the samples are textureGrad.
            if (groundHasNormal == 1 && groundNormalStrength[i] > 0.0) {
                // Whiteout blend: each planar sample is a tangent-space normal for a different axis, so
                // its xy is added to the geometric normal's other two components and its z folded in on
                // the projection axis, then the three are swizzled back into world space. Per axis is what
                // keeps relief the right way up on a wall as well as on a floor.
                vec3 nX = textureGrad(groundNormal, uvX, dXdx * inv, dXdy * inv).rgb * 2.0 - 1.0;
                vec3 nY = textureGrad(groundNormal, uvY, dYdx * inv, dYdy * inv).rgb * 2.0 - 1.0;
                vec3 nZ = textureGrad(groundNormal, uvZ, dZdx * inv, dZdy * inv).rgb * 2.0 - 1.0;
                vec3 tX = vec3(nX.xy + normal.zy, abs(nX.z) * normal.x);
                vec3 tY = vec3(nY.xy + normal.xz, abs(nY.z) * normal.y);
                vec3 tZ = vec3(nZ.xy + normal.xy, abs(nZ.z) * normal.z);
                mixedNormal += w * (tX.zyx * blend.x + tY.xzy * blend.y + tZ.xyz * blend.z);
                mixedNormalStrength += w * groundNormalStrength[i];
            }
        }

        // Normalised by the total weight, not by the material count: the leftover is ground wearing no
        // material, and it must read as bare rather than as a darker version of whatever is next to it.
        float applied = groundFade * min(groundTotal, 1.0);
        // Clamped, and it is not defensive. The grain is a multiplier about 1 — a texel at the art's
        // brightest is roughly 1.9x its own mean — so a ground that is already bright is pushed past 1 and
        // clips. Silver ore is the case that exposed it: at an albedo of 0.86 the bright half of the art
        // blew out, and a seam rendered as a white honeycomb of the texture's own structure rather than as
        // metal in rock. Albedo here is a reflectance and cannot exceed 1; the emissive path is a separate
        // thing entirely, split off the vertex colour above, and magma is its only user.
        albedo = min(mix(albedo, mixedTarget / groundTotal, applied), vec3(1.0));

        if (groundHasNormal == 1 && dot(mixedNormal, mixedNormal) > 1e-8) {
            normal = normalize(mix(normal, normalize(mixedNormal),
                                   (mixedNormalStrength / groundTotal) * applied));
        }
    }

    // Ground pieces, over whatever the materials above made of a block's tops. The block coordinate is taken,
    // and its derivatives with it, out here in uniform control flow: the sample below sits in a branch that
    // neighbouring fragments can skip. Derived from the continuous coordinate rather than from fract() of
    // it, or the jump at every block edge would read as a huge derivative and draw a seam of the smallest mip.
    vec2 pieceCell = (worldPos.xz - pieceOrigin) / max(pieceBlockSize, 1e-4);
    vec2 pieceDx = dFdx(pieceCell);
    vec2 pieceDy = dFdy(pieceCell);
    if (piecesOn == 1 && worldNormal.y > 0.7) {
        ivec2 block = ivec2(floor(pieceCell));
        float pieceFade = 1.0 - smoothstep(pieceFadeStart, pieceFadeEnd, viewDistance);
        if (pieceFade > 0.0 && block.x >= 0 && block.y >= 0
                && float(block.x) < pieceGrid.x && float(block.y) < pieceGrid.y) {
            float piece = floor(texelFetch(pieceLayout, block, 0).r * 255.0 + 0.5);
            if (piece > 0.0) {
                vec3 uv = vec3(fract(pieceCell), piece - 1.0);
                albedo = mix(albedo, textureGrad(pieceAlbedo, uv, pieceDx, pieceDy).rgb, pieceFade);
                if (pieceHasNormal == 1 && pieceNormalStrength > 0.0) {
                    // The same whiteout blend as the materials' overhead projection, on a top face only.
                    vec3 geometric = normalize(worldNormal);
                    vec3 n = textureGrad(pieceNormal, uv, pieceDx, pieceDy).rgb * 2.0 - 1.0;
                    vec3 t = vec3(n.xy + geometric.xz, abs(n.z) * geometric.y);
                    normal = normalize(mix(geometric, normalize(t.xzy), pieceNormalStrength * pieceFade));
                }
            }
        }
    }

    // Stand-in ground cover. Two octaves only, and deliberately coarse: at the distances this applies to,
    // what reads is the patchiness of a field over metres, and blade-scale detail would be sub-pixel and
    // shimmer. Covered ground goes *darker*, which is what actually matches the real tufts — a canopy of
    // blades over soil is darker than the soil — so the far field tones into the near one rather than
    // merely being noisy.
    float coverFade = coverAmount(albedo, viewDistance);
    if (coverFade > 0.0) {
        vec2 cp = worldPos.xz * coverScale;
        float coverPatch = coverNoise(cp) * 0.65 + coverNoise(cp * 2.7) * 0.35;
        albedo = mix(albedo, albedo * (1.0 - coverDarkening), coverFade * (0.45 + 0.55 * coverPatch));
    }
    // Molten rock.
    //
    // **The test for it is the HDR albedo itself**, which is exact rather than a colour key: magma is the
    // only terrain material whose colour leaves the [0,1] range, so `vertexColor > 1` identifies it and
    // nothing else, with no vertex attribute, no ground-material slot and no extra uniform. That is the
    // same trick the ground cover plays on the grassland green, except that this one cannot be fooled by a
    // biome that happens to be a similar shade.
    //
    // Where it fires, the gradient replaces both halves: the lit part is the rock and the excess over 1 is
    // the glow. The undressed material's own flat colour is discarded, which is the point — one uniform
    // HDR value is a disc of flat pale yellow, and what a mass of molten rock actually shows is a
    // temperature that varies across it.
    //
    // In a PROP pass the substitution does not run and the excess is emitted as it stands — which is what
    // makes a lamp a lamp: the part up to 1 is glass lit like anything else, and the surplus is added
    // unlit at the end, so it survives shadow, blooms, and hazes with distance like every other colour
    // here. See `emissiveIsMolten`.
    vec3 emissive = max(vertexColor - vec3(1.0), vec3(0.0));
    if (emissiveIsMolten == 1 && emissive.r + emissive.g + emissive.b > 0.0) {
        vec3 molten = moltenGradient(magmaHeat(worldPos, normal));
        albedo = min(molten, vec3(1.0));
        emissive = max(molten - vec3(1.0), vec3(0.0));
    }

    float waterFoam = 0.0;
    if (waterPass == 1) {
        waterSurface(albedo, normal, waterFoam);
    }

    float NdotL = dot(normal, toSun);

    // Cel banding in four steps, with the thresholds spread across the upper range rather than bunched
    // near the terminator. Rolling ground under a high sun sits almost entirely above NdotL 0.5, so bands
    // placed low would put every hill in the same tone and flatten the landscape into one flat green —
    // the extra steps up top are what give the terrain its form. Edges are narrow but not zero-width: a
    // hard step crawls with aliasing across terrain this dense.
    float rawBand = smoothstep(0.26, 0.32, NdotL) * 0.34
               + smoothstep(0.58, 0.64, NdotL) * 0.33
               + smoothstep(0.83, 0.89, NdotL) * 0.33;

    // Cast shadows multiply the band down, which lands a shadowed fragment on `ambientColor` — exactly
    // where the unlit side of the same surface already sits. That is the whole reason this works with the
    // key/fill model: a shadow is not a darkening applied afterwards, it is the surface being told the key
    // light does not reach it, so shadowed ground matches shaded ground instead of being a grey smear over
    // it. The rim and specular terms are gated on `band` too, so both follow for free.
    float vis = sunVisibility(worldPos, normal, NdotL);
    float band = rawBand * vis;

    // The key/fill split: shadow takes the sky's colour rather than a dimmed sun. Blending between two
    // differently coloured lights is what keeps shadows blue and vivid instead of muddy.
    vec3 light = mix(ambientColor, sunColor, band);
    vec3 color = albedo * light;

    // Warm terminator: a thin ruddy glow where the sun grazes a slope, gated by the cast shadow — the
    // same painted anime edge the character shader gets, kept subtle on the broad terrain.
    float terminator = clamp(smoothstep(0.0, 0.2, NdotL) - smoothstep(0.2, 0.55, NdotL), 0.0, 1.0);
    color += albedo * vec3(1.0, 0.55, 0.4) * terminator * vis * 0.14;

    float NdotV = max(dot(normal, toView), 0.0);
    float rim = pow(1.0 - NdotV, rimPower);
    rim = smoothstep(rimThreshold - 0.1, rimThreshold + 0.1, rim) * (0.3 + 0.7 * band);
    color += rimColor * rim * 0.35;

    // The sun on water, as anime water sparkles: over the stretch that mirrors the sun, a soft sheen, and the art's own
    // white marks (the river's light lines, the sea's crests) flashing in it. Never a blown-out disc, never on foam.
    if (waterPass == 1) {
        float mirror = dot(reflect(-toSun, normal), toView);
        float path = smoothstep(0.86, 0.95, mirror);
        float marks = smoothstep(0.8, 0.9, dot(albedo, vec3(1.0 / 3.0)));
        color += sunColor * path * (0.06 + 0.55 * marks) * band * (1.0 - waterFoam);
    }

    // Aerial perspective. Distant ground dissolves into the sky colour, which gives the vistas depth and
    // also hides the far plane cutting off terrain that is still streamed in beyond it.
    // Drain colour before fog, never after: the fog colour comes from the sky palette and is already the
    // hue it should be, so desaturating the composite would wash the horizon out too.
    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, saturation);

    // Lamplight after the saturation drain, like the glow below: night drains the moonlit scene toward grey,
    // and a lamp's warm pool on it is the whole point of a lamp. Before fog, so a lit street far off hazes.
    color += albedo * pointLight(worldPos, normal);

    // Emissive glow added after the saturation drain (so magma keeps its hot hue at night) but before fog
    // (so distant lava still hazes into the vista like everything else — being HDR, it stays bright enough
    // to bloom through).
    color += emissive * emissiveScale;

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    fragColor = vec4(color, 1.0);

    // For the indirect-light pass. The normal is the one actually shaded with — the ground material's relief
    // is folded into it above — so occlusion follows the surface the player sees rather than the smooth
    // isosurface underneath it.
    //
    // The alpha is `1 - band`, the share of this fragment's light that came from the ambient fill, and it is
    // the whole reason the geometry buffer exists rather than the GI pass deriving normals from depth
    // gradients. Occlusion may only take away fill: the key light already has a shadow map deciding whether
    // it arrives, and darkening it again here would be one scene shadowed twice for one reason. Because
    // `band` is multiplied by the cast-shadow visibility, ground in shadow correctly reads as fully
    // fill-lit and takes occlusion in full.
    fragGeometry = vec4(normal * 0.5 + 0.5, 1.0 - band);
}
