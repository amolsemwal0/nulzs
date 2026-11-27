#version 330 core

// One parametric fluid, driven by a palette rather than a shader per liquid. Everything that used to be
// hard-coded "water, crossfaded toward lava" is now an appearance entry, and `emissive` is what spans the
// two models: 0 is a lit surface (a pond — diffuse, fresnel sheen, sun glint), 1 is a self-lit one (molten
// rock — the same rippling surface under an orange-through-yellow gradient emitted above 1, so bloom makes
// it glow). Anything between crossfades, so a third fluid is a palette entry rather than a new code path.

in vec3 worldPos;
in vec3 faceNormal;
in float vDepth;

/** Depth, in world units, below which a *lit* fluid fades out as the film it is. */
const float THIN_FILM = 0.35;

/**
 * How much longer than the water's own vertical thickness a view ray may be counted as travelling.
 *
 * <p>The optical path has two estimates and they fail in opposite directions. <b>The depth buffer</b> —
 * the distance from this fragment to whatever was drawn behind it — is right whenever the ray really does
 * stay in the water until it hits the bed, which is the normal case, and it is the only one of the two that
 * knows about a rock or a player standing in the pond and correctly shortens the path in front of them.
 * But it is <b>badly wrong for a ray that skims</b>: a ray clearing a sand bar leaves the water almost at
 * once and then travels tens of units through air before hitting the ground, and the buffer cannot tell
 * those two stretches apart. <b>The local slab</b> — {@code vDepth} divided by how steeply the ray is
 * descending — cannot see obstacles at all and underestimates a ray heading into deeper water, but it
 * never mistakes air for water.
 *
 * <p>So the path is the <b>smaller of the two</b>: the buffer shortens it where something is in the way,
 * the slab bounds it where the ray has left. Trusting the buffer alone is not a hypothetical failure — it
 * drew a scatter of narrow blue fingers running inland from the shore in {@code fluid_shelf.png}, one at
 * every gap in the sand bars, each one a strip of shallow water shaded as though it were metres deep.
 * Their pixels measured (75, 134, 153) against a neighbouring (98, 124, 120), which is the deep tone
 * arriving a foot from the beach.
 *
 * <p>This then bounds what is left. A ray crossing water {@code d} thick can honestly travel further than
 * {@code d} — that is exactly why a grazing look across a pond is bluer than a look straight down into it,
 * and it is a large part of the target look — but at a near-horizontal view the slab estimate diverges,
 * so it is capped here rather than being allowed to run away with the last few degrees.
 */
const float MAX_PATH_STRETCH = 8.0;

/** Stands in for "nothing was drawn behind this fragment" — the sky over the lip of a fall. */
const float NO_PATH = 1e6;

/**
 * The shallowest a view ray is allowed to count as descending, when the path is worked out from the local
 * slab.
 *
 * <p>{@code vDepth / |ray.y|} is the honest crossing of a slab that thick, and it diverges as the ray goes
 * horizontal. This floors the divisor; {@code MAX_PATH_STRETCH} bounds the result anyway, so it exists to
 * keep the arithmetic finite rather than to decide anything.
 */
const float MIN_RAY_DESCENT = 0.05;

/**
 * The same depth for a *molten* fluid — all but switched off, because thinning means something completely
 * different to the two models.
 *
 * Water genuinely becomes see-through as it thins: a film a centimetre deep shows the bed through it, and
 * fading it out is both honest and what stops a flood's ragged edge reading as a mosaic of bright tiles.
 * Molten rock does not do that. A centimetre of lava on a slope is still an opaque glowing crust; it is
 * rock either way, and its thickness changes how *hot* it looks, not how much of the ground shows through.
 *
 * Applying water's threshold to lava is what made a flank leak read as **dithering** rather than flowing,
 * and the size of the mistake only became clear once the flow was measured. A leak spreading over a slope
 * is a **2–5 cm sheet almost everywhere** — measured, fewer than 3% of its cells are thicker than 8 cm,
 * and lowering the flow rate to a sixth of water's barely moves that, because a small leak simply does not
 * have the volume to run deep. At water's 0.35 essentially the whole tongue was faded to nothing; at an
 * intermediate 0.16 a 3 cm cell drew at a tenth of the opacity of a 6 cm one, so ordinary
 * centimetre-scale variation in the bed turned into a violent brightness speckle. That *is* the dithering.
 *
 * So the threshold has to sit below the sheet rather than through it. At 0.015 every cell that survives
 * the mesher's own floor draws fully opaque, brightness stops encoding depth, and what varies across the
 * flow is its own temperature — which is what a lava flow actually looks like. It is kept marginally above
 * zero only so a cell on its way to empty fades out rather than popping.
 */
const float MOLTEN_FILM = 0.015;

/**
 * How much of the wind's speed the ripple pattern travels at.
 *
 * <p>Far below 1: what moves across water in a breeze is the wave pattern, not the water, and a surface
 * whose texture slid at the full speed of the air would read as a river flowing in whatever direction the
 * weather happened to be going.
 */
const float WIND_RIPPLE_DRIFT = 0.08;

/**
 * The floor under the molten model's diffuse. Molten rock is rock at thirteen hundred degrees, not a
 * pebble, so its lit half never sits as dark as shadowed stone; this is what stops a night-time flow
 * losing its coolest parts to pure black and with them its silhouette.
 */
const float MOLTEN_LIT_FLOOR = 0.30;

/**
 * Where the gradient's middle stop sits along the heat range.
 *
 * <p>Well past halfway on purpose. A flow is orange over nearly all of it and yellow only where it is
 * hottest — that is what colour temperature does — so the ramp has to spend most of its length between the
 * cool tone and the ember one. Placed at 0.5 the melt comes back uniformly yellow, which is the flat
 * plastic slab this model exists to avoid; placed much higher the yellow never arrives and there is no
 * gradient left, only an orange sheet.
 */
const float MOLTEN_MID = 0.62;

/**
 * Reciprocal world units of the temperature field — one feature spans about 1 / this.
 *
 * <p>Four and a half metres, which is deliberately the scale the procedural crust this replaced already
 * ran at: that is where a flow reads as a body of rock with hotter and cooler parts across it, rather than
 * as either a uniform sheet (features wider than the pool they are in) or a noise pattern.
 */
const float MOLTEN_FIELD_SCALE = 0.22;

/**
 * How hot melt runs on average, and how far either side of that its own field wanders.
 *
 * <p><b>The field varies about a hot centre rather than climbing from cold</b>, and getting that backwards
 * is what the first render showed: with the noise as the whole of the heat, an ordinary pool sat around a
 * third of the way up the ramp, never crossed {@link MOLTEN_MID}, and came back a flat dull red with no
 * yellow in it anywhere — the two upper stops were unreachable and the gradient may as well not have
 * existed. The centre is placed a little under the middle stop, so a flow is orange over most of itself
 * with its hotter patches running yellow — which is a gradient, where a centre placed on the middle stop
 * puts a deep pool at the top of the ramp and blows the whole thing out to white.
 */
const float MOLTEN_BASE_HEAT = 0.42;
const float MOLTEN_HEAT_SPREAD = 0.45;

/**
 * How much of the heat range depth alone is worth.
 *
 * <p>The middle of a pool runs hotter than its rim, which is the same signal a lit fluid spends on its
 * shallow-to-deep tint — so the two models read the identical geometry and disagree only about what to do
 * with it. It is also what keeps a thin tongue on a hillside from glowing as hard as the crater pool.
 */
const float MOLTEN_DEPTH_HEAT = 0.16;

/** ...and how much speed is worth: melt that is moving hard is torn open and glows through. */
const float MOLTEN_FLOW_HEAT = 0.20;

/** How hard the grazing edge brightens, so the lip of a tongue catches the eye against the rock. */
const float MOLTEN_RIM = 0.45;

in vec2 vFlow;
flat in int vFluid;
// TWO OUTPUTS, BOTH AT LOCATION 0 — DUAL-SOURCE BLENDING, AND IT IS WHAT MAKES WATER LOOK LIKE WATER.
//
// Ordinary alpha blending has ONE alpha, so a surface hides the same fraction of every channel. Water does
// not: it absorbs red several times faster than blue, which is the whole reason a sandy shallow reads as
// turquoise rather than as sand under a blue wash. Measured on the reference image, red stays suppressed
// to a fifth of blue right down to the waterline, over a bed that is plainly visible in green and blue —
// and no single alpha can produce that. Sweeping one only trades the two failures against each other: low
// enough to see the bed and the sand's red comes through unchanged; high enough to kill the red and the
// bed has stopped showing at all, which was measured at a scalar rate of 1.1 and is the wrong picture.
//
// So the transmittance is a colour, delivered as a SECOND source and used as the destination factor:
// `result = inscatter + bed * transmit`, per channel. GL 3.3 has this in core (ARB_blend_func_extended),
// it costs one pass, and it reduces exactly to today's behaviour when a fluid's three rates are equal.
//
// The one thing it requires is a single draw buffer, which this pass already has — every pass after the
// indirect-light point goes through `Framebuffer.bindColorOnly`, so even with that pass reconnected the
// fluid still draws to colour alone.
layout(location = 0, index = 0) out vec4 fragColor;
layout(location = 0, index = 1) out vec4 fragTransmit;

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
uniform float time;

/**
 * The world's wind, in world units per second — the same velocity the clouds drift in and the grass leans
 * along. It drives the ripples on <b>still</b> water; a river's own current is `vFlow`, which is per-vertex
 * and already moves both the ripples and the surface pattern downstream.
 */
uniform vec2 windVelocity;

// The sky, for the reflection. These are the same three colours skybox.frag builds its gradient from, so
// water reflects the sky the player is actually under rather than a guess assembled from ambient and fog.
uniform vec3 skyZenithColor;
uniform vec3 skyMiddleColor;
uniform vec3 skyHorizonColor;

// Where the sun really is — deliberately NOT `toSun`, which is the *key light* and is mirrored above the
// horizon at night (WorldLighting.keyLightDirection). Lighting wants the mirrored one so nothing is lit
// from below; a reflection wants the true one, or the water carries a sun path at midnight.
uniform vec3 sunDirection;

// The planar reflection: the scene drawn a second time from a camera mirrored through `reflectionPlaneY`.
// `hasReflection` is 0 when no plane was worth mirroring this frame (no water on screen, or too little),
// in which case the sky is the whole answer.
uniform sampler2D reflectionTex;
uniform mat4 reflectionViewProj;
uniform int hasReflection;
uniform float reflectionPlaneY;

const int MAX_FLUIDS = 4;
// The cast-shadow lookup, copied from terrain.frag rather than shared, because GLSL 330 has no #include
// and this project has no shader preprocessor. WorldLightingTest-style drift is the risk that buys; the
// alternative was leaving fluid the one lit surface in the scene that ignores shadows entirely, which is
// what it was — reported from play as water under an overhang not being shaded.
uniform sampler2DShadow shadowMap;
uniform mat4 lightSpaceMatrix;
uniform float shadowTexelUv;
uniform float shadowTexelWorld;
uniform float shadowBias;
uniform float shadowStrength;

// How much of the key light reaches this fragment: 1 lit, 0 fully shadowed. See terrain.frag, which this
// is a transcription of — same nine hardware-compared taps, same normal offset, same edge fade.
float sunVisibility(vec3 position, vec3 surfNormal, float NdotL) {
    if (shadowStrength <= 0.0) {
        return 1.0;
    }
    float slope = clamp(1.0 - NdotL, 0.0, 1.0);
    vec3 offset = surfNormal * shadowTexelWorld * (1.5 + 3.0 * slope);
    vec4 lightClip = lightSpaceMatrix * vec4(position + offset, 1.0);
    vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    if (proj.z > 1.0) {
        return 1.0;
    }
    float bias = shadowBias * (1.0 + 4.0 * slope);
    float lit = 0.0;
    float reference = proj.z - bias;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 tap = proj.xy + (vec2(x, y) + 0.5) * shadowTexelUv;
            lit += texture(shadowMap, vec3(tap, reference));
        }
    }
    float visibility = smoothstep(0.15, 0.85, lit / 9.0);
    vec2 fromCentre = abs(proj.xy - 0.5) * 2.0;
    visibility = mix(visibility, 1.0, smoothstep(0.8, 1.0, max(fromCentre.x, fromCentre.y)));
    return mix(1.0, visibility, shadowStrength);
}

uniform vec3 shallowColor[MAX_FLUIDS];
uniform vec3 deepColor[MAX_FLUIDS];
uniform vec3 emberColor[MAX_FLUIDS];
uniform vec3 hotColor[MAX_FLUIDS];
uniform float emissive[MAX_FLUIDS];
uniform float opacity[MAX_FLUIDS];
uniform float specular[MAX_FLUIDS];
uniform float rippleScale[MAX_FLUIDS];
// The opaque scene's depth, copied before this pass — see FluidRenderer.captureSceneDepth. Sampling the
// depth attachment this pass is drawing into would be a feedback loop, which is why it is a copy.
uniform sampler2D sceneDepth;
uniform int hasSceneDepth;
uniform float nearPlane;
uniform float farPlane;

/**
 * Distance from the eye, in world units, for a depth-buffer value.
 *
 * A depth buffer is hyperbolic — nearly all of its precision is spent close to the camera — so the raw
 * values cannot be subtracted to get a distance. Undoing the projection is what makes the edge width below
 * mean the same thing at any range, instead of being a hair wide underfoot and metres wide at the horizon.
 */
float linearDepth(float depth) {
    float ndc = depth * 2.0 - 1.0;
    return (2.0 * nearPlane * farPlane) / (farPlane + nearPlane - ndc * (farPlane - nearPlane));
}

/**
 * How far this fragment is from whatever was drawn behind it, in world units along the view ray — i.e.
 * <b>how much fluid the light reaching the eye came through</b>.
 *
 * <p>This is what carries the whole look. Water is not a coloured pane, it is a volume that absorbs: what
 * decides its colour and how much of the bed shows through is the length of the path the light took inside
 * it, and that length is a property of the view as much as of the water. Reading it from the depth buffer
 * gets the two cases a vertical thickness cannot: a grazing look across a pond is a long path and comes out
 * deep blue where the same water looked straight down into is much clearer, and a rock, a tree or the
 * player standing in the water shortens the path in front of them and so is <i>surrounded</i> by shallows,
 * for free and with the mesher knowing nothing about any of them.
 *
 * <p>It is also the mirror of what the composite already does from inside the water: {@code composite.frag}
 * takes a path from the same depth buffer and absorbs along it, so the tint seen looking <i>through</i> the
 * surface and the tint seen looking through it from <i>underneath</i> are one model at two viewpoints
 * rather than two effects tuned to agree.
 */
float sceneRayPath() {
    if (hasSceneDepth == 0) {
        return NO_PATH;
    }
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(sceneDepth, 0));
    float behind = linearDepth(texture(sceneDepth, uv).r);
    float here = linearDepth(gl_FragCoord.z);
    // Nothing behind at all — water against the sky over the lip of a fall. There is no path to read, so
    // the caller falls back to the geometry.
    if (behind >= farPlane * 0.999) {
        return NO_PATH;
    }
    return max(behind - here, 0.0);
}

uniform vec3 foamColor[MAX_FLUIDS];
uniform vec3 flowLineColor[MAX_FLUIDS];
uniform float foamSpeed[MAX_FLUIDS];

/**
 * How fast this fluid absorbs the light passing through it, per world unit of path.
 *
 * <p><b>Three rates, not one</b>, and that is the point rather than a refinement: red is absorbed several
 * times faster than blue, so the same body of water is turquoise where it is thin and blue where it is
 * deep without either colour being stated anywhere. It is also what lets a sandy bed show through the
 * shallows while its warmth does not.
 *
 * <p>The rest of the water body is made of it too. Everything the depth tint used to be spelled out in — a
 * stated shallow depth, a crossfade half-width around it, an alpha at each end of that range, a pair of
 * images standing in for the two ends — collapses into Beer's law over the optical path: what survives a
 * length {@code L} of fluid is {@code exp(-density * L)}, and what the surface itself shows is the rest.
 * A shallow rim is transparent and takes the bed's own colour because the path through it is short, a
 * grazing look across the same water is opaque because the path is long, and the boundary between them is
 * a smooth curve rather than a contour ring at a stated depth. That ring was visible in the old pond shot.
 *
 * <p>Per fluid rather than a constant, because it is the whole difference between a pond and a lava flow:
 * molten rock absorbs so fast that a centimetre of it is opaque, which is why {@code emissive} fluids also
 * pin their own alpha and never reach this.
 */
uniform vec3 absorption[MAX_FLUIDS];

// NOTE: the molten model has no art at all any more. It used to sample a crust albedo (plus a registered
// normal map, a measured mean and a measured heat range) and read the plate field, the glow and the relief
// off that one image. That is gone, and what replaced it is `moltenGradient` below: an orange-through-yellow
// ramp taken straight from the palette. The deciding reason is that the two upper stops of that ramp sit
// **above 1** — which is the whole of why molten rock glows through shadow and blooms — and an 8-bit albedo
// cannot carry a value of 3.4 however bright it looks. The art could only ever say *where* to glow while
// the palette said *how much*, so the image was doing the smaller half of the job at the cost of two
// texture units, five uniforms and a set of measurements that failed silently when the art was re-exported.


/**
 * How much of the surface is reflection at each end of the depth range, before Fresnel.
 *
 * Deep water reflects most of what reaches it because almost nothing comes back from below; shallow water
 * is dominated by its own bright bed. These are the floors — the angle term climbs from here — so
 * SHALLOW_REFLECTIVITY is what a pond's rim shows when looked at straight down, and it is deliberately not
 * zero: even a puddle has a sheen.
 */
const float SHALLOW_REFLECTIVITY = 0.06;
const float DEEP_REFLECTIVITY = 0.30;

/**
 * How far a grazing view carries reflectivity toward a mirror, over and above the depth floor.
 *
 * This is the Fresnel half, and it is the one that must stay strong: looking along water is when real water
 * turns into a mirror, and it is the single cue that most says "this is a liquid surface" rather than a
 * coloured plane.
 */
const float FRESNEL_REFLECT_GAIN = 0.85;

/** Overall trim on the reflection, so the model can be dialled back without retuning its shape. */
const float REFLECTION_STRENGTH = 0.9;

/**
 * The least opaque a lit fluid may be, however short the path through it.
 *
 * <p>Not the old SHALLOW_ALPHA, which was the alpha of a stated shallow depth and so applied to a whole
 * rim. This is a floor on the *surface itself*: even a film of water is a surface, and one that vanished
 * completely would leave a hole in the pond with the bed showing through unmodified. Small enough that a
 * shoreline still reads as the bed rather than as the water over it, which is the whole of the target look.
 */
const float MIN_SURFACE_ALPHA = 0.04;

/**
 * How much a reflecting surface is forced opaque.
 *
 * Light that bounced off the surface never went through it, so a strongly reflecting patch cannot also be
 * clear. Without this the two halves of the model contradict each other at grazing angles — a mirror you
 * can see the gravel through.
 */
const float REFLECTION_OPACITY = 0.95;

/** Water never goes fully opaque; a little of the bed always shows, which is what keeps it reading as water. */
const float MAX_ALPHA = 0.94;

/** Must match {@code PlanarReflection.PLANE_TOLERANCE}: how far off the mirror plane water may still be. */
const float PLANE_TOLERANCE = 0.5;

/**
 * How far the ripple displaces the reflection lookup, in screen fractions per unit of normal tilt.
 *
 * Small on purpose. This is a screen-space nudge standing in for a per-fragment re-render, so it is a lie
 * that only holds while it stays subtle — pushed far it drags the reflection off the thing casting it and
 * a tree's mirror image detaches from its trunk at the shoreline.
 */
const float REFLECTION_DISTORTION = 0.055;

/** Fraction of the mirrored frame's edge over which it gives way to the sky. */
const float REFLECTION_EDGE_FADE = 0.06;


// ---------------------------------------------------------------------------------------------------
// BOUNDED FLOW ADVECTION — how a surface pattern is carried along by the fluid under it.
//
// Every pattern here that rides the current used to be sampled at `p - flow * time * 0.35`, where `time`
// is Engine.sceneTime and accumulates from zero for the whole session. That offset therefore grew without
// bound, and the damage is not the offset itself but its GRADIENT: `vFlow` is interpolated across a
// triangle, so two ends of one triangle sample the pattern `dFlow * time * 0.35` apart. Five minutes into
// a session, with a flow difference of 1 across a triangle, that is tens of whole pattern periods inside a
// single triangle — which aliases into long smears and chevrons, gets steadily worse the longer the
// session runs, and is worst exactly where the fluid moves fastest. It was reported from play as the lava
// shader breaking in places, and it is invisible to every preview because those render at three seconds
// and mostly at zero flow.
//
// The fix is the standard flowmap: advect against a phase that RESETS every period, and cross-fade two
// copies half a cycle out of step so the reset is never seen. Each layer is at full strength in the middle
// of its own cycle and faded to nothing at the moment it restarts. The offset is then bounded by
// `flow * PERIOD * RATE` however long the session runs, and so is its gradient.
//
// What it costs is that the pattern is a blend of two advections rather than one true one, which on
// surfaces this noisy reads as texture rather than as ghosting. The period is the knob: longer carries the
// pattern further before resetting (more convincing flow, more smear), shorter is cleaner and pulses more.
const float FLOW_ADVECT_PERIOD = 3.0;
const float FLOW_ADVECT_RATE = 0.35;

// The two cycle positions and the weight between them: x and y are elapsed seconds within each layer's own
// cycle, z is the cross-fade toward the second.
vec3 flowPhases() {
    float cycle = time / FLOW_ADVECT_PERIOD;
    float a = fract(cycle);
    return vec3(a * FLOW_ADVECT_PERIOD, fract(cycle + 0.5) * FLOW_ADVECT_PERIOD,
                abs(1.0 - 2.0 * a));
}

// How far the current has carried a pattern at one point in a cycle. Bounded by construction.
vec2 flowOffset(vec2 flow, float phase) {
    return flow * phase * FLOW_ADVECT_RATE;
}

// A rippled surface normal from a few crossing waves. Cheap and animated by the shared clock, enough to
// break up the flat sheet and give the sun something to glint off — this is a stylised pond, not an ocean
// solver.
//
// The waves *ride the current*: the pattern is sampled upstream by how far the water has travelled, so it
// scrolls downstream at the water's own speed. This is the whole difference between a river and a blue
// ribbon lying in a valley — the geometry of a stream is nearly static, and motion is the only cue left.
// Standing water has zero flow and falls back to exactly the old fixed-bearing drift.
//
// Raised on the world's up axis and *not* on the face's own normal, which was tried and is a trap worth
// leaving marked. Riding the mesh normal is the more correct-sounding answer — it would light a tilted
// sheet as tilted — but the mesher emits one flat normal per cell, and on a surface as gently shaped as a
// pond those per-cell normals are noisy at cell scale (measured across a settled bowl they swing from
// +0.21 to -0.41 between neighbouring cells, because the feathered corner heights they are fitted to move
// by centimetres). Building the wave on world up ignores that noise; building it on the face exposes it,
// and a settled pond came back as a visible checkerboard of cells. Lava is raised the same way and for the
// same reason: its shape comes from the mesh and from where its own heat field runs hot, not from tilting
// a wave into the slope.
// The wave slopes at one point in an advection cycle. Split out so the two phases can be cross-faded.
vec2 rippleSlope(vec2 p, float t, vec2 flow, float phase) {
    // Advected by the fluid's own current *and* by the wind. A still pond has no current at all, so before
    // the wind term its ripples sat in one place and merely pulsed — water that was moving without going
    // anywhere. The current is scaled down because a river's surface already carries the pattern downstream
    // through `fluidUv`; this is only the ripple riding on top of it.
    //
    // The wind term stays on the session clock and is right to: it carries no per-fragment factor, so every
    // fragment of every triangle shares it exactly and it cannot produce a gradient. Only the CURRENT is
    // per-fragment, and only the current is bounded.
    vec2 q = p - flowOffset(flow, phase) - windVelocity * t * WIND_RIPPLE_DRIFT;
    // Partial derivatives of a sum of directional sine waves give a plausible surface slope.
    float sx = cos(q.x * 0.9 + t * 1.3) * 0.9
             + cos((q.x + q.y) * 0.5 + t * 0.8) * 0.5;
    float sz = cos(q.y * 0.8 - t * 1.1) * 0.8
             + cos((q.x + q.y) * 0.5 + t * 0.8) * 0.5;
    return vec2(sx, sz);
}

vec3 rippleNormal(vec2 p, float strength, vec2 flow) {
    float t = time;
    vec3 phase = flowPhases();
    vec2 s = mix(rippleSlope(p, t, flow, phase.x), rippleSlope(p, t, flow, phase.y), phase.z);
    return normalize(vec3(-s.x * 0.12 * strength, 1.0, -s.y * 0.12 * strength));
}

// Where a surface pattern is sampled: projected on the plane the face lies in, so a falling sheet gets a
// moving pattern rather than a stretched smear of one sample. Shared by the albedo, the molten heat field
// and the whitewater streaks, all three of which used to spell it out separately.
vec2 planarCoords(vec3 normal) {
    return abs(normal.y) > 0.5 ? worldPos.xz : vec2(worldPos.x + worldPos.z, worldPos.y);
}

// Texture coordinates for the albedo pair, in world space rather than per-vertex.
//
// There is no UV attribute on a fluid vertex and there should not be: the surface mesh is rebuilt from
// scratch every frame from whichever cells happen to be wet, so any UV assigned at build time would swim
// as the sheet's own triangles came and went. Tiling against world position instead pins the pattern to
// the world, which is what makes a pond look like it is sitting in the landscape rather than scrolling
// under it.
//
// It rides the current on the same convention as `rippleNormal`, so a river's surface moves downstream and
// standing water (flow exactly 0) is static.
// ---------------------------------------------------------------------------------------------------
// What the water reflects.
//
// One function, deliberately, because the *source* of the reflection is the thing most likely to change:
// everything below composes a colour with the depth and Fresnel model and never asks where it came from.
// Today that source is the sky evaluated analytically; a planar or screen-space pass swaps in here and
// nothing else moves.
//
// `skyReflection` is skybox.frag's own `skyGradient` (lines 112-129 there) carried across verbatim. It has
// no helper dependencies at all — no hash, no noise, no fbm — so it is about fifteen instructions, and
// copying it is what guarantees the water and the sky agree about what colour the sky is. The clouds are
// deliberately NOT carried across: `cloudLayer` costs some 45 noise evaluations per pixel, which would
// more than double this shader.
// ---------------------------------------------------------------------------------------------------
vec3 skyReflection(vec3 rd) {
    float altitude = max(rd.y, 0.0);
    float sunAmount = max(dot(rd, sunDirection), 0.0);

    vec3 color = mix(skyMiddleColor, skyZenithColor, smoothstep(0.12, 0.70, altitude));
    color = mix(skyHorizonColor, color, smoothstep(0.0, 0.20, altitude));

    // The sunset warmth keys on the sun's own bearing, so a reflected ray pointing at the sun picks up the
    // warm band — which is exactly the sun path across water, arriving for free rather than as a term
    // anybody had to add.
    float sunLow = 1.0 - smoothstep(0.0, 0.40, abs(sunDirection.y));
    float horizonBand = pow(1.0 - altitude, 6.0);
    float warmth = pow(sunAmount, 2.5) * sunLow * horizonBand;
    color = mix(color, vec3(1.00, 0.42, 0.16), clamp(warmth * 0.75, 0.0, 0.85));

    // A reflected ray that points below the horizon has nothing above it to mirror. The sky shader darkens
    // there; here it means the ray is looking into the water's own body, so it settles toward the horizon
    // rather than going black.
    return mix(color, skyHorizonColor * 0.75, 1.0 - smoothstep(-0.15, 0.02, rd.y));
}

/**
 * The reflection seam.
 *
 * Everything downstream composes this colour with the depth and Fresnel model and never asks where it came
 * from, which is the point: swapping the source is a change to this function alone. `worldPos` is unused by
 * the sky source and is taken anyway, because a planar or screen-space source needs it to project.
 */
vec3 reflectionColor(vec3 rd, vec3 surfacePos, vec3 rippled, vec3 flatNormal) {
    vec3 sky = skyReflection(rd);
    if (hasReflection == 0) {
        return sky;
    }

    // Only water actually sitting on the mirror plane may use the mirrored render. A volumetric fluid has
    // bodies at many heights and the plane can only describe one; a stream a metre below it would sample a
    // mirror at the wrong height, which puts everything in the reflection at the wrong distance. The sky is
    // the better answer there, and it fades in over the tolerance rather than switching at a line.
    float offPlane = smoothstep(PLANE_TOLERANCE * 0.5, PLANE_TOLERANCE,
                                abs(surfacePos.y - reflectionPlaneY));
    if (offPlane >= 1.0) {
        return sky;
    }

    vec4 clip = reflectionViewProj * vec4(surfacePos, 1.0);
    if (clip.w <= 0.0001) {
        return sky;   // behind the mirrored camera; there is nothing of it to sample
    }
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;

    // The ripple displaces the lookup rather than the ray. Reflecting the *rippled* normal and projecting
    // that would need the reflection re-rendered per fragment; nudging the sample by how far the ripple
    // tilts off flat is the standard cheat and is what makes a reflection wobble with the surface.
    vec2 wobble = (rippled - flatNormal).xz * REFLECTION_DISTORTION;
    uv += wobble;

    // Off the edge of the mirrored frame there is nothing to read — the mirrored camera saw less than this
    // one does at grazing angles — so it settles back to the sky rather than clamping a stretched edge
    // pixel across the water.
    vec2 inside = smoothstep(vec2(0.0), vec2(REFLECTION_EDGE_FADE), uv)
                * (1.0 - smoothstep(vec2(1.0 - REFLECTION_EDGE_FADE), vec2(1.0), uv));
    float edge = inside.x * inside.y;

    vec3 mirrored = texture(reflectionTex, clamp(uv, 0.0, 1.0)).rgb;
    return mix(mirrored, sky, max(offPlane, 1.0 - edge));
}

// Hand-rolled value-noise fBm, the same trick skybox.frag and terrain.frag use (JNoise is Java-only, and
// GLSL 330 has no #include so each shader carries its own copy). Drives the molten model's heat field and
// the whitewater streaks.
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * valueNoise(p);
        p *= 2.0;
        amp *= 0.5;
    }
    return sum;
}

// One layer of the temperature field, at one point in its own advection cycle — see FLOW_ADVECT_PERIOD,
// which is what keeps the melt from tearing itself apart as a session ages.
float moltenField(vec2 p, vec2 flow, float phase) {
    vec2 q = (p - flowOffset(flow, phase)) * MOLTEN_FIELD_SCALE + vec2(0.0, time * 0.05);
    // The two inner drifts stay on the session clock deliberately: they carry no per-fragment term, so
    // every fragment of every triangle shares them exactly and they cannot produce a gradient at all.
    return fbm(q) * 0.65 + fbm(q * 2.6 - vec2(time * 0.08, 0.0)) * 0.35;
}

// ---------------------------------------------------------------------------------------------------
// STREAMLINES — thin bright lines drawn ALONG the current, so a moving body of fluid says which way it is
// going.
//
// Moving water is otherwise very hard to read. Its geometry is nearly static — a river is a ribbon lying
// in a valley whose shape barely changes — so unless something on the surface travels, the only cue that
// it is moving at all is the ripple normal, which is a texture rather than a direction. A flow that
// arrives on newly dug ground therefore reads as appearing rather than as running.
//
// <b>Lines, not a wash.</b> The whitewater term below already tints fast fluid toward foam, and that says
// "this is moving" without saying WHERE. What carries direction is something long and thin and parallel to
// the current: the eye reads the orientation of a streak before it reads its brightness.
//
// The field is one fBm sampled through a deliberately lopsided frame — barely compressed along the flow,
// heavily compressed across it — so its features come out as long thin filaments lying downstream. That is
// the same trick the whitewater uses, pushed much further and then thresholded hard so what survives is a
// line rather than a smear.
const float FLOW_LINE_ALONG = 0.045;   // along the current: long features
const float FLOW_LINE_ACROSS = 6.5;    // across it: narrow ones
const float FLOW_LINE_SCROLL = 1.15;   // how fast the pattern travels downstream, per unit of flow

/** Where the lines begin to show, as a fraction of the speed at which the fluid foams. */
const float FLOW_LINE_ONSET = 0.16;
const float FLOW_LINE_STRENGTH = 0.95;

/**
 * Depth below which the lines fade out — their OWN threshold, deliberately not `film`.
 *
 * <p>This is the trap the edge line next door already records, and it bites harder here. `film` exists to
 * fade water too thin to see, and <b>fast water is thin water</b>: a run over freshly dug ground is a sheet
 * of a few centimetres, which is exactly the case a player most needs told which way it is going. Faded on
 * `film` the lines were all but invisible on the one scene they were asked for, while showing plainly on
 * the deep slow water that needs them least.
 */
const float FLOW_LINE_MIN_DEPTH = 0.02;

/**
 * How much line this fragment carries, 0..1.
 *
 * <p>Advected on the SAME bounded phase the ripples and the melt use — see FLOW_ADVECT_PERIOD. Scrolling
 * these against the session clock would tear them into the chevrons that bug produced, and would do it
 * worst exactly where the fluid runs fastest, which is where the lines are.
 */
float flowLines(vec2 p, vec2 flow, float speed) {
    vec2 dir = speed > 0.0001 ? flow / speed : vec2(1.0, 0.0);
    vec2 across = vec2(-dir.y, dir.x);
    vec3 phase = flowPhases();
    float sideways = dot(p, across) * FLOW_LINE_ACROSS;
    float alongA = dot(p, dir) * FLOW_LINE_ALONG - phase.x * speed * FLOW_LINE_SCROLL;
    float alongB = dot(p, dir) * FLOW_LINE_ALONG - phase.y * speed * FLOW_LINE_SCROLL;
    float field = mix(fbm(vec2(alongA, sideways)), fbm(vec2(alongB, sideways)), phase.z);
    // Hard either side of a narrow band rather than a ramp from a threshold upward: a ramp gives a smear
    // that is brightest where the noise happens to peak, where a band gives an edge on both sides, which
    // is what reads as a drawn line. The same reasoning the character shader's drawn terminator records.
    return smoothstep(0.545, 0.575, field) * (1.0 - smoothstep(0.605, 0.640, field));
}

/**
 * How hot this fragment of melt is: 0 at the palette's coolest tone, 1 at its hottest.
 *
 * <p>Three terms, and each is something the surface already carries rather than a texture standing in for
 * one. A slowly drifting noise field, because a body of molten rock is not one temperature and without it
 * a tongue is a flat sheet of paint. <b>Depth</b>, so the middle of a pool runs hotter than its rim — the
 * same signal a lit fluid spends on its shallow-to-deep tint, read here by the other model. And
 * <b>flow</b>, because melt that is moving hard is being torn open and glows through.
 *
 * <p>The field drifts with the melt, so a tongue's hot patches travel down the slope with it. Two octave
 * sets rather than one, moving at different rates, so the whole surface does not pulse together.
 */
float moltenHeat(vec2 p, vec2 flow, float agitation) {
    // Two layers half a cycle out of step, cross-faded — the standard flowmap. Each is shown at full
    // strength in the middle of its own cycle and faded to nothing at the moment it resets, so the reset
    // itself is never visible. What it costs is that the pattern is a blend of two advections rather than
    // one true one, which on a surface this noisy reads as texture rather than as ghosting.
    vec3 phase = flowPhases();
    float field = mix(moltenField(p, flow, phase.x), moltenField(p, flow, phase.y), phase.z);
    // The fBm sits around 0.5 with most of its mass in the middle of its range, so the ramp is placed
    // either side of that rather than over [0,1] — over the full range it barely leaves mid-orange.
    float varied = smoothstep(0.30, 0.70, field);
    return clamp(MOLTEN_BASE_HEAT + (varied - 0.5) * MOLTEN_HEAT_SPREAD
                    + clamp(vDepth / 4.0, 0.0, 1.0) * MOLTEN_DEPTH_HEAT
                    + agitation * MOLTEN_FLOW_HEAT,
                 0.0, 1.0);
}

/**
 * The gradient itself — dull red through orange to yellow, as three stops taken off the palette.
 *
 * <p>Read from the palette rather than written here for the reason the sky's ramp lives in
 * {@code WorldLighting} rather than in {@code skybox.frag}: what molten rock looks like is art direction,
 * it belongs beside what water looks like, and a second copy of a ramp is a copy that drifts. The stops
 * are {@code shallowColor} (coolest), {@code emberColor} and {@code hotColor}, which is what those palette
 * entries already meant.
 *
 * <p>The upper two sit <b>above 1</b>, and that is the whole of why this reads as something emitting light
 * rather than as orange plastic: everything past 1 is split off below and added unlit, so it survives
 * shadow and the bloom pass finds it. It is also why the crust image this replaced could never have been
 * the thing that glowed — an 8-bit albedo cannot carry a value of 3.4 however bright it looks.
 */
vec3 moltenGradient(int f, float heat) {
    float h = clamp(heat, 0.0, 1.0);
    return h < MOLTEN_MID
            ? mix(shallowColor[f], emberColor[f], h / MOLTEN_MID)
            : mix(emberColor[f], hotColor[f], (h - MOLTEN_MID) / (1.0 - MOLTEN_MID));
}

void main() {
    int f = clamp(vFluid, 0, MAX_FLUIDS - 1);
    vec3 toView = normalize(viewPos - worldPos);

    // How hard this patch of fluid is moving, as a fraction of the speed at which it breaks up. Everything
    // below reads off this one number, so still and running water are the same surface at two settings
    // rather than two looks that have to be kept consistent by hand.
    float speed = length(vFlow);
    float agitation = clamp(speed / max(foamSpeed[f], 0.001), 0.0, 1.0);

    // Only up-facing surfaces ripple. A side face is the wall of a falling sheet or the lip of a pool, and
    // perturbing it with a height-field wave would light it as though it were lying flat.
    // Moving water is choppier than still water, so the ripple grows with the current.
    vec3 surfaceNormal = normalize(faceNormal);
    vec3 normal = faceNormal.y > 0.5
            ? rippleNormal(worldPos.xz, rippleScale[f] * (1.0 + agitation * 1.6), vFlow)
            : surfaceNormal;

    // Fresnel: a lit fluid turns mirror-like and pale at grazing angles, clear looking straight down.
    float fresnel = pow(1.0 - max(dot(normal, toView), 0.0), 3.0);

    // THE WATER BODY, AND THE ONE QUANTITY IT IS ALL MADE OF: how far the light reaching the eye travelled
    // inside the fluid.
    //
    // Read from the depth buffer rather than from the surface's own vertical thickness — see sceneRayPath,
    // which is where that argument lives. `vDepth` still has a job here as the floor and as the scale the
    // stretch clamp is stated in: it is the honest thickness of the slab at this point, and a path can
    // never be shorter than it.
    // THE SHORTER OF WHAT THE DEPTH BUFFER SAYS AND WHAT A SLAB THIS THICK COULD GIVE AT THIS ANGLE.
    // The two are wrong in opposite directions and each is right where the other is not — see
    // MAX_PATH_STRETCH for the whole argument, and for what taking the depth buffer alone drew.
    float rayPath = sceneRayPath();
    vec3 viewRay = normalize(worldPos - viewPos);
    float slabPath = vDepth / max(abs(viewRay.y), MIN_RAY_DESCENT);
    float path = min(rayPath, slabPath);
    // Bounded ABOVE only. There is no floor, and putting one at vDepth was wrong for exactly the case a
    // depth-buffer path is best at: something standing in front of the water. A waterfall's curtain is the
    // loudest example — `vDepth` there is how much fluid is stacked BELOW the vertex, which for a falling
    // sheet is the rest of the fall rather than anything to do with how thick the sheet is, so flooring
    // the path at it made a curtain a few centimetres thick as opaque as several metres of pond and the
    // fall rendered as a slab of dark glass. The same floor would put a rock standing in a pool at the
    // bottom of the water rather than in front of it.
    path = clamp(path, 0.0, vDepth * MAX_PATH_STRETCH);

    // Beer's law, per channel. `absorbedRgb` is how much of what is behind the surface never made it out,
    // so it is both the transparency and — through red going first — most of the shallow-to-deep hue
    // shift. It reaches 0 exactly at the water's edge, so a shoreline is the bed showing through undimmed
    // rather than a line drawn on top of it.
    vec3 absorbedRgb = 1.0 - exp(-path * absorption[f]);
    // The scalar the surface's own colour and its reflectivity read: how deep this water READS, which is
    // one question however many rates the absorption has. The mean rather than any one channel — red
    // alone saturates within centimetres and would call a puddle deep, blue alone barely moves and would
    // call a lake shallow.
    float absorbed = (absorbedRgb.r + absorbedRgb.g + absorbedRgb.b) / 3.0;
    float deepMix = absorbed;
    vec3 base = mix(shallowColor[f], deepColor[f], absorbed);

    // Diffuse from the scene's sun and ambient, so the surface dims at night and greys under overcast with
    // everything else.
    float ndl = max(dot(normal, toSun), 0.0);
    // Shadowed like every other lit surface in the scene. The AMBIENT term is deliberately left alone:
    // shadow removes the key light, not the sky, so water under an overhang darkens to its own fill
    // colour rather than to black — the same rule terrain.frag follows by multiplying only its band.
    //
    // The lookup uses the RIPPLE normal, which is what is actually being lit, and that is worth stating:
    // the geometric normal is flat over most of a pond, so a lookup from it would offset every fragment
    // by the same amount and the whole surface would flip in and out of shadow together at a boundary.
    float vis = sunVisibility(worldPos, normal, ndl);
    vec3 lit = base * (ambientColor * 0.7 + sunColor * ndl * 0.6 * vis);

    // A tight sun glint off the ripples, strongest at grazing angles. Gated on the same visibility, or a
    // pond in the shade of a cliff keeps a sun highlight on it — which is the loudest way for a surface
    // to say it is not being shadowed.
    vec3 halfVec = normalize(toSun + toView);
    float spec = pow(max(dot(normal, halfVec), 0.0), 140.0);
    lit += sunColor * spec * specular[f] * (0.4 + 0.8 * fresnel) * vis;

    // How much of what the surface shows is reflection rather than the water body beneath it.
    //
    // Two things decide it, and they are different questions. **Depth** decides how much there is to see
    // *through* to: over a shallow bed the bottom is bright and close and dominates, while deep water
    // returns almost nothing from below, so the reflection is most of what is left. **Angle** decides how
    // much the surface reflects at all — that is Fresnel, and it is why any water goes mirror-like when you
    // look along it and stays clear when you look straight down.
    //
    // Multiplying them would let a grazing view make shallow water fully reflective, which is wrong for the
    // reason the depth term exists; adding a floor per depth and letting Fresnel climb from there keeps
    // both true.
    float depthReflect = mix(SHALLOW_REFLECTIVITY, DEEP_REFLECTIVITY, deepMix);
    float reflectivity = clamp(depthReflect + (1.0 - depthReflect) * fresnel * FRESNEL_REFLECT_GAIN,
                               0.0, 1.0);

    vec3 reflected = reflectionColor(reflect(-toView, normal), worldPos, normal, surfaceNormal);
    lit = mix(lit, reflected, reflectivity * REFLECTION_STRENGTH * (1.0 - emissive[f]));

    // Transparency, on the same two terms and in the same direction: shallow water is see-through, deep
    // water is not.
    //
    // The Fresnel floor is not decoration. Light that reflects off the surface is light that did not go
    // through it, so a surface reflecting strongly cannot also be see-through — tying alpha to
    // `reflectivity` keeps those two from contradicting each other, which they did when the sheen was faked
    // and alpha was tuned separately.
    // Per channel, so the water hides the bed's red long before it hides its blue. The reflection floor is
    // scalar and applies to all three: light that bounced off the surface never went through it at all, so
    // it is not something the water absorbed.
    vec3 depthAlpha = max(absorbedRgb, vec3(MIN_SURFACE_ALPHA));
    vec3 alpha = clamp(max(depthAlpha, vec3(reflectivity * REFLECTION_OPACITY)), 0.0, MAX_ALPHA);

    // A film thinner than a boot sole is barely there, and the floor in that clamp used to say otherwise:
    // it held every depth, however small, at a third opaque. Water spreading over rough ground breaks into
    // patches a couple of centimetres deep with dry cells between them, and drawn at a third opaque with
    // whitewater over the top, that reads as a mosaic of bright tiles on the hillside rather than as a wet
    // slope. Fading it out is both what a film actually looks like and what makes the coherent part of a
    // stream the part you see.
    // Which threshold depends on the model, since "thin" means see-through to one and merely cooler to the
    // other — see MOLTEN_FILM. Crossfaded on `emissive` like everything else here, so a fluid halfway
    // between the two gets a threshold halfway between them rather than a branch.
    float film = smoothstep(0.0, mix(THIN_FILM, MOLTEN_FILM, emissive[f]), vDepth);

    // Crossfade toward the self-lit model. A molten fluid is (near) opaque — you do not see through it —
    // so the transparency of the lit model is replaced rather than tinted.
    //
    // The colour, though, is *split* rather than replaced, on exactly the convention `terrain.frag` uses
    // for `TerrainMaterial.MAGMA`: the part up to 1 is rock, and is lit like rock, and only the excess
    // over 1 is the glow, added unlit so it survives shadow and blooms. Replacing the whole thing was what
    // made a lava flow read as blocky. A self-lit surface answers nothing about its own shape: every part
    // of a tongue came back the same shade whatever way it was tilted, so the only cues left were the
    // polygon outlines and the side walls, and a sheet that genuinely followed the slope photographed as a
    // staircase of tiles. It falls out for free that lava reads differently by day and by night — the lit
    // half darkens into the night grade while the glow holds its brightness, so a night-time flow is
    // fissures of light on dark rock rather than the same orange slab it showed at noon.
    //
    // Everything else here is *water's* model, deliberately and by construction rather than by imitation:
    // the same rippled normal, the same ambient/sun mix, the same specular glint at the same strength the
    // palette asks for. Molten rock at this scale flows and glints exactly as water does — what tells the
    // two apart is that one of them emits.
    if (emissive[f] > 0.001) {
        // The temperature field is projected on the *face's* plane, not the rippled normal's: this is the
        // fluid that runs down the near-vertical wall of a volcano, and a field projected as though that
        // wall were lying flat smears into vertical streaks.
        float heat = moltenHeat(planarCoords(surfaceNormal), vFlow, agitation);
        vec3 molten = moltenGradient(f, heat);

        // A faint brightening where the surface turns away, so the lip of a tongue reads against the rock
        // behind it. Ember rather than the hot tone, or every silhouette in the flow runs white.
        float rim = pow(1.0 - max(dot(normal, toView), 0.0), 4.0);
        molten += emberColor[f] * rim * MOLTEN_RIM;

        float moltenNdl = max(dot(normal, toSun), 0.0);
        vec3 moltenLight = max(ambientColor * 0.7 + sunColor * moltenNdl * 0.6, vec3(MOLTEN_LIT_FLOOR))
                + sunColor * spec * specular[f];
        vec3 moltenLit = min(molten, vec3(1.0)) * moltenLight + max(molten - vec3(1.0), vec3(0.0));
        lit = mix(lit, moltenLit, emissive[f]);
        alpha = mix(alpha, vec3(0.98), emissive[f]);
    }

    // The streamlines, before the whitewater so foam still reads over them where a rapid is doing both.
    // Their onset is a FRACTION of the foam threshold rather than a number of its own, so a fluid that has
    // been retuned to froth at a different speed shows its direction at a matching one — the same rule
    // ParticleFluidMotion follows for its mist. Water is running long before it breaks white, and saying
    // which way is exactly what it needs then.
    float lineOnset = foamSpeed[f] * FLOW_LINE_ONSET;
    if (speed > lineOnset) {
        float carry = clamp((speed - lineOnset) / max(foamSpeed[f] - lineOnset, 0.001), 0.0, 1.0);
        float lines = flowLines(planarCoords(surfaceNormal), vFlow, speed)
                * carry * FLOW_LINE_STRENGTH;
        // On its own depth threshold rather than on `film` — see FLOW_LINE_MIN_DEPTH. It still needs one:
        // without any, the lines are painted past the water's edge onto bare ground.
        float onWater = smoothstep(0.0, FLOW_LINE_MIN_DEPTH, vDepth);
        lines *= onWater;
        lit = mix(lit, flowLineColor[f], clamp(lines, 0.0, 1.0));
        // A drawn line on the surface hides what is under it, exactly as foam does — and on a thin sheet
        // that is most of what makes it visible at all, since there is barely any water to tint.
        alpha = clamp(alpha + lines * 0.45, 0.0, 1.0);
    }

    // Whitewater. Fast fluid breaks up, and this is the cue that carries furthest — a river reads as moving
    // from across a valley long after its ripples are sub-pixel, which is exactly the range at which a
    // scrolling normal has stopped saying anything.
    //
    // Broken into streaks by noise dragged along the current rather than laid on as a flat wash: an even
    // tint over fast water reads as the surface having been desaturated, not as foam. The pattern is
    // stretched *across* the flow (sampled at a finer scale along it) so the streaks run downstream, which
    // is what they do in a real rapid.
    if (agitation > 0.01) {
        vec2 dir = speed > 0.0001 ? vFlow / speed : vec2(1.0, 0.0);
        vec2 across = vec2(-dir.y, dir.x);
        vec2 p = planarCoords(surfaceNormal);
        // Dragged along the current on the SAME bounded phase the ripples and the melt use, and for the
        // same reason: `speed` is per-fragment, so `time * speed` grew a gradient across every triangle
        // that widened for as long as the session ran. See FLOW_ADVECT_PERIOD.
        vec3 streakPhase = flowPhases();
        float alongA = dot(p, dir) * 0.35 - streakPhase.x * speed * 0.5;
        float alongB = dot(p, dir) * 0.35 - streakPhase.y * speed * 0.5;
        float acrossQ = dot(p, across) * 1.4;
        float streaks = mix(fbm(vec2(alongA, acrossQ)), fbm(vec2(alongB, acrossQ)), streakPhase.z);
        // Thin fluid froths more readily than deep — the shallow edge of a run is where it actually breaks.
        float shallow = 1.0 - clamp(vDepth / 2.5, 0.0, 1.0);
        float foam = agitation * agitation * smoothstep(0.35, 0.75, streaks) * (0.55 + 0.45 * shallow);
        // Faded with the film too: froth on water that is not there is what drew the brightest tiles.
        lit = mix(lit, foamColor[f], clamp(foam * film, 0.0, 1.0));
        // Foam is froth, not water: it hides what is under it.
        alpha = clamp(alpha + foam * film * 0.5, 0.0, 1.0);
    }

    // NO EDGE LINE ANY MORE — AND WHAT IT WAS, WHY IT WENT AND WHAT DOES ITS JOB NOW.
    //
    // There used to be a white line here, drawn wherever the surface met something solid: a band of the
    // same depth difference `sceneRayPath` returns, converted to a distance in PIXELS by dividing by its
    // own screen-space rate, plus a darker rim outside it. The pixel conversion was deliberate and is not
    // what was wrong with it — a fixed band in metres turns a whole thin sheet white, because the bed under
    // a sheet is flat and the difference is very nearly constant across the whole of it.
    //
    // WHAT WAS WRONG IS THAT THE RATE WAS TAKEN ACROSS A SURFACE THE MESHER EMITS ONE QUAD PER CELL FOR.
    // `fwidth` of a faceted quantity is not a rate: at a cell boundary the value STEPS, and a derivative of
    // a step is a spike. So the pixel distance collapsed along every cell boundary and the line was drawn
    // there — a closed white loop round every cell — while the interior of the same sheet, where the true
    // rate is near zero, correctly showed nothing. Reported from play as a net of white lines over the
    // water, visible only at a distance: near water in this world is a river channel with a real gradient,
    // and it is the far shallows that are a broad sheet whose rate is all facet noise.
    //
    // Four fixes were measured before this and every one failed, which is what says the fault is
    // structural rather than a matter of tuning:
    //   * a gate in metres on the depth difference — a real shoreline has the bed right behind it, but so
    //     does a deep pond's bank, so it does not separate them and it damaged every shoreline in the set;
    //   * raising the line's own minimum depth, swept over four settings — it removes the net and takes the
    //     shoreline with it one for one, because a shoreline IS shallow water;
    //   * taking the rate from the scene depth alone — smooth, but it goes to zero against a vertical face,
    //     which is exactly where a line is most wanted;
    //   * standing the band off the contact — that is what the dashed-line artefact came from.
    // The only fix left was a smooth rate, which means shared corner heights out of the mesher: a rewrite
    // of its hottest loop, for a line the target look does not have.
    //
    // BECAUSE THE TARGET LOOK DOES NOT HAVE ONE. A shoreline in it is not a mark painted on the water, it
    // is the water running out: the path through it goes to zero, the absorption above goes with it, and
    // the bed shows through unmodified. That reads as a shore for the same reason a real one does, it is
    // resolution-independent, it cannot be drawn where there is no water, and it costs nothing — the
    // absorption is being computed anyway. A rock or the player standing in the pond is still ringed by
    // shallows, which is what the depth-buffer test was for in the first place; it just tints now instead
    // of drawing.
    //
    // If a lapping foam line is ever wanted back, it should come from the MESHER, which already knows which
    // corners are on the water's edge (see `shoreCorner`): one more vertex attribute carrying distance to
    // the shore is smooth by construction, immune to faceting at any range, and needs no derivative at all.
    // It should not come back as a screen-space band on this surface.

    // Match the scene's aerial perspective so distant fluid dissolves into the sky like the terrain does.
    float fog = aerialPerspective(worldPos, viewPos);
    vec3 color = mix(lit, fogColor, fog);
    // `film` still fades a fluid too thin to see, and for the lit model it is now very nearly redundant:
    // the absorption above has already taken a centimetre of water to about one percent opaque on its own.
    // It is kept because the molten model reads a completely different threshold through the same term —
    // see MOLTEN_FILM — and because it is what gates the whitewater and the streamlines above.
    vec3 cover = alpha * film * (1.0 - fog);
    // PREMULTIPLIED, because the destination factor is the second output rather than one minus this one's
    // alpha. `result = color * cover + bed * (1 - cover)`, per channel, which is exactly what an ordinary
    // alpha blend does with a scalar — this one simply lets the three channels disagree.
    fragColor = vec4(color * cover, 1.0);
    fragTransmit = vec4(1.0 - cover, 1.0);
}
