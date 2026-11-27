#version 330 core

in vec3 worldPos;
in vec3 worldNormal;
in vec2 uv;
in float shade;
// 0 at the roots, 1 at the tips. Drives the root-to-tip gradient below.
in float heightFrac;
// The terrain colour under this tuft, from the same blend the ground is painted with.
flat in vec3 groundColor;
// How far this tuft has arrived, 0 to 1, from its distance to the player. See the dissolve below.
flat in float fade;
flat in float variant;

layout(location = 0) out vec4 fragColor;
// World normal + ambient-fill share, for the indirect-light pass — see terrain.frag, which carries the
// reasoning for the whole set. The normal written is the up-biased one the tuft is shaded with, not the
// blade's own facing, for exactly the reason it is shaded with it: a field lit like the hillside it stands
// on should gather indirect light like it too.
layout(location = 1) out vec4 fragGeometry;

uniform sampler2D albedoTexture;
// Whether there is a texture at all. The clump in the tree carries no embedded image, so this is 0 and
// the texel is white; the branch is on a uniform, so it is free and — unlike sampling an unbound sampler
// — defined. Only the texel's ALPHA is used now (the billboard's blade shape lives in it); its colour is
// the gradient below, which is absolute rather than a multiplier. See GrassMeshFactory.gradientFor.
uniform int hasAlbedo;

/**
 * 1: the blade is painted by its texture (a baked Shader Tool material over the tuft's UVs), and the gradient below
 * tints it (white = as painted). 0: the gradient is the colour and the texture only cuts the blade's shape out.
 */
uniform int albedoColour;

/** How far a blade takes the ground's hue (keeping its own lightness); the gradient look's own 0.65 by default. */
uniform float groundTint;

/**
 * 1: the screen's ambient occlusion darkens the grass where blades crowd, and the grass the ground round it (the
 * default). 0: the grass is left out of it altogether, as a painted field is drawn — light all through, its depth in
 * its colours — and casts no dark halo on the ground about it either: it writes no normal to the geometry buffer, as the
 * sprites do, but with alpha 1, which gi.frag reads as "not in the way" where a sprite (alpha 0) still shades the
 * ground at its feet.
 */
uniform int receivesOcclusion;

/** A gust's crest passing over this tuft, from grass.vert: its tips lit up a little. */
in float gustLight;

// The blade's colour, root to tip. Three stops rather than one, because grass changes HUE up its length
// — dark and blue-green down where blades crowd and shade each other, meadow green through the body, pale
// yellow-green at the tips — and a brightness ramp over a single colour cannot express a hue change at
// all. See GrassVariant, which is where these come from and why the pale variant is derived from them
// rather than written out separately.
//
// Absolute colours, not multipliers on the texel. That is what makes them unambiguous: a tint only means
// anything if there is a texel to multiply, and the clump carries no image at all, which is the trap
// GrassMeshFactory.gradientFor is written up under.
// One gradient per colour variant, picked per fragment rather than per draw call.
//
// A patch holds both greens mixed together — the pale variant gathers in drifting patches, not in whole
// patches — so a variant chosen by draw call would mean either sorting every patch's tufts by colour or
// drawing every patch twice. A two-entry uniform array costs one flat varying and collapses a patch into
// a single call. Dynamically indexed, which is legal for a uniform array (unlike a sampler array, where
// GLSL 330 requires a constant expression and this codebase has already been bitten by the difference).
const int GRASS_VARIANTS = 2;
uniform vec3 grassRoot[GRASS_VARIANTS];
uniform vec3 grassMid[GRASS_VARIANTS];
uniform vec3 grassTip[GRASS_VARIANTS];

/**
 * How far up a blade the middle stop sits.
 *
 * Halfway, so the two segments are even and "root, middle, tip" means what it says. It is a named
 * constant rather than a literal 0.5 because it is the one number here that decides how much of a blade
 * is dark: pulling it down bows the ramp so a blade is properly green earlier, which is what to reach for
 * if the field ever reads too dark at its base.
 */
const float GRADIENT_MID = 0.5;

// Above zero only for the billboard pass, whose sprite carries the blade shape in its alpha. The 3D
// clumps have no alpha at all — their texture is fully opaque and the shape is geometry — so for them
// this is 0 and the branch costs nothing.
uniform float alphaCutoff;

// How hard a cutout's alpha is pushed back up per mip level, for cutoutAlpha below.
//
// Set against what the averaging actually costs rather than picked: one mip level halves a cutout's
// linear feature size, and on the leaf art measured here that takes roughly a quarter of the surviving
// coverage with it per level. Higher and a distant canopy grows *denser* than the one it is meant to
// match, which reads as trees fattening as you back away from them; lower and the erosion is merely
// slower rather than gone. Judge it from trees_stand.png against a close-up, never from the number.
const float MIP_ALPHA_SCALE = 0.25;

// --- Coverage-preserving alpha for a mipmapped cutout ---------------------------------------------
// Duplicated verbatim in foliage.frag, depth_cutout.frag, impostor.frag and grass.frag. GLSL 330 has no
// #include and this project has no shader preprocessor, so the copies are pinned equal by
// AlphaCutoutTest rather than shared. They HAVE to agree: a canopy that discards on a different rule
// from the one its own shadow discards on draws shade under the gaps in itself.
//
// WHY A PLAIN `a < cutoff` IS NOT ENOUGH. Mipmapping is a box filter, so it averages a cutout's alpha
// toward the texture's own mean -- and a leaf sheet's mean is well under 1 (measured on the highland
// canopy: 57.6% clear, 40.7% opaque, mean 0.416). A fixed threshold therefore discards more of the card
// the further away it is: the canopy erodes toward a bare skeleton at range, and this codebase has
// already had to drop one cutoff from 0.5 to 0.25 to work around exactly that. Worse than the erosion is
// its edge -- every texel sitting near the threshold flips between kept and discarded as the mip level
// slides with the camera, which is the "transition sweeping across the forest as the camera moves"
// already recorded here. That sweep IS the shimmer.
//
// Scaling the alpha back up by the mip level being read undoes the averaging, so a card keeps roughly
// the coverage it was authored with at any distance and its near-threshold texels are pushed clear of
// the threshold instead of hovering on it. The level is computed from the derivatives rather than asked
// for: textureQueryLod is GLSL 400 and this is 330.
//
// It is exactly a no-op at mip 0, which is the property that makes it safe to apply everywhere: lod
// clamps at zero, the scale is 1, and every close-up in the project is untouched by construction.
//
// Must be called in uniform control flow -- it takes derivatives, which are undefined once a quad's
// fragments have taken different paths. That is why every caller samples and rescales BEFORE its
// discards rather than after.
float cutoutAlpha(sampler2D tex, vec2 texUv, float sampledAlpha) {
    vec2 texels = vec2(textureSize(tex, 0));
    vec2 dx = dFdx(texUv) * texels;
    vec2 dy = dFdy(texUv) * texels;
    float lod = 0.5 * log2(max(dot(dx, dx), dot(dy, dy)));
    return sampledAlpha * (1.0 + max(0.0, lod) * MIP_ALPHA_SCALE);
}

uniform vec3 viewPos;

// --- Root-to-tip gradient -----------------------------------------------------------------------
/**
 * How much of the ground's own colour the very base of a blade takes. 1 would make the roots exactly the
 * terrain colour and hide the join completely; a little short of that keeps the blade readable as a blade
 * where it meets the soil, which matters because the clump is only about a foot tall and its base is a
 * large share of it.
 */
const float GROUND_BLEND = 0.85;

/**
 * How quickly the ground's colour gives way to the blade's going up.
 *
 * <p>Above 1 so the blend is concentrated in the bottom of the tuft rather than spread evenly: what this
 * part of the mask is for is the <b>join</b>, and a linear ramp drags the terrain colour halfway up every
 * blade, which reads as the grass being the wrong colour rather than as it being rooted in something. At
 * 2.5 it is down to a third of its strength by a third of the way up.
 *
 * <p>It does fall to <b>zero</b> at the tip, and that is correct now: making the biome show up the whole
 * blade was tried here first, as a floor under this falloff, and it does not read — see
 * {@code GROUND_COLOR_BLEND}, which does that job as a separate even term and does it by changing hue
 * rather than luminance. This one is left to be purely the join.
 */
const float GROUND_FALLOFF = 2.5;

/**
 * How strongly the ground's own colour is imposed on the WHOLE blade, as a "colour" blend.
 *
 * <p><b>Not a linear mix toward the ground, and that distinction is the whole of why this works.</b>
 * Mixing a blade toward volcanic ash (0.10, 0.095, 0.092) or a dark forest floor (0.15, 0.16, 0.18)
 * barely moves its hue — those grounds are near-neutral, so almost all of what the mix does is reduce
 * luminance. And the eye reads a darker green as <b>the same grass in shadow</b>, not as different grass.
 * That was measured and shipped and reported straight back as still looking the same: the blades really
 * were 28% darker in the dark forest and still read as one green.
 *
 * <p>The "colour" blend mode from image editing does the thing that actually reads — it takes <b>hue and
 * saturation</b> from the blend layer and <b>luminosity</b> from the base. So a blade keeps its own
 * root-to-tip light structure, which is what makes it look like a blade, and takes on the colour of what
 * it is growing in. Over an achromatic forest floor that drains the green out toward grey; over desert
 * sand it turns it straw; over the meadow's own green it changes almost nothing.
 *
 * <p><b>Applied evenly up the whole blade</b>, unlike the join below. The two are separate jobs: this is
 * what the grass is made of, and the join is how it meets the soil.
 *
 * <h2>Why 0.65, bounded at both ends by measurement</h2>
 * Measured on the grass pixels alone (isolated against the grass-off control, so the sample is exactly the
 * blades), as the strength is swept:
 *
 * <pre>
 *   strength   grassland      dark forest    volcanic
 *   0.00       ( 27,121, 32)  ( 21, 88, 32)  ( 20, 93, 25)
 *   0.35       ( 34,119, 34)  ( 35, 81, 48)  ( 37, 85, 42)
 *   0.65       ( 40,117, 36)  ( 49, 76, 63)  ( 53, 80, 57)
 *   1.00       ( 47,115, 38)  ( 66, 70, 80)  ( 72, 72, 74)
 * </pre>
 *
 * <p><b>The upper bound is that grass must still be green.</b> At 1.00 the dark forest reads (66, 70, 80)
 * — blue above green — and the volcano (72, 72, 74), which is not desaturated grass, it is grey. That is
 * the blend mode working exactly as defined: at full strength the blade's hue IS the ground's, and both
 * those grounds are achromatic. 0.65 leaves green dominant in every biome with margin.
 *
 * <p><b>The lower bound is that it has to be seen.</b> A linear mix was tried first and reported back
 * twice as no difference at all — see the note above on why. 0.35 works and 0.65 is unmistakable.
 *
 * <p>It costs the meadow very little, which is the self-scaling property that makes one constant safe:
 * the blend imposes the ground's hue, and where the ground is already the grass's own green there is
 * almost nothing to impose — the grassland moves 4 points of green across the entire sweep while the dark
 * forest moves 45. <b>Judge it from grass_dark_forest.png, never from the grassland shots or the
 * constant.</b>
 */
const float GROUND_COLOR_BLEND = 0.65;

// --- The "colour" blend mode (W3C compositing), used by GROUND_COLOR_BLEND ------------------------
// Rec. 601 weights, which is what the blend mode is defined in terms of — deliberately NOT the Rec. 709
// weights used for the saturation drain further down, which is a different operation from a different
// standard. Matching them would be tidier and would make this stop being the blend mode it is named after.
float blendLum(vec3 c) {
    return dot(c, vec3(0.3, 0.59, 0.11));
}

/**
 * Pulls a colour back into range by scaling it about its own luminance rather than clamping per channel.
 *
 * Clamping is the obvious thing and it shifts the hue, which is exactly what this blend mode exists to
 * control: a channel clipped on its own leaves the other two where they were and the result is a
 * different colour, not a contained one. Reaching out of gamut is normal here rather than exceptional —
 * imposing a dark ground's hue at a bright tip's luminosity routinely lands outside the cube.
 */
vec3 clipColor(vec3 c) {
    float l = blendLum(c);
    float low = min(c.r, min(c.g, c.b));
    float high = max(c.r, max(c.g, c.b));
    if (low < 0.0) {
        c = l + (c - l) * (l / max(l - low, 1e-5));
    }
    if (high > 1.0) {
        c = l + (c - l) * ((1.0 - l) / max(high - l, 1e-5));
    }
    return c;
}

/** The blend colour's hue and saturation carried at the base colour's luminosity. */
vec3 colorBlend(vec3 base, vec3 blend) {
    return clipColor(blend + (blendLum(base) - blendLum(blend)));
}

/**
 * A touch darker right at the base, on top of everything else.
 *
 * <p>The shade ramp in the vertex stage already darkens the roots, but it darkens the <i>blade</i>; this
 * darkens where the blade meets the soil, which is a contact shadow and belongs to the ground. Without it
 * a root blended to exactly the terrain colour is <b>invisible</b> — the tuft reads as floating, because
 * the one cue that it is standing on something is the shadow it casts into its own base, and a 12 cm clump
 * is far too small for the shadow map to resolve.
 */
const float CONTACT_SHADE = 0.72;

uniform vec3 toSun;
uniform vec3 sunColor;
uniform vec3 ambientColor;

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

float sunVisibility(vec3 position, vec3 normal, float NdotL) {
    if (shadowStrength <= 0.0) {
        return 1.0;
    }

    float slope = clamp(1.0 - NdotL, 0.0, 1.0);
    vec3 offset = normal * shadowTexelWorld * (1.5 + 3.0 * slope);
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
    // Tighten the ramp. Every tap is already bilinearly filtered by the hardware, so the raw average is a
    // soft gradient several texels wide; steepening it keeps that antialiasing while making the shadow
    // read as a *shape with an edge* rather than a smudge — which is the same thing the cel bands do to
    // the diffuse, and the reason a stylised scene wants this and a photoreal one would not.
    float visibility = smoothstep(0.15, 0.85, lit / 9.0);

    vec2 fromCentre = abs(proj.xy - 0.5) * 2.0;
    visibility = mix(visibility, 1.0, smoothstep(0.8, 1.0, max(fromCentre.x, fromCentre.y)));

    return mix(1.0, visibility, shadowStrength);
}

// --- Arrival fade -------------------------------------------------------------------------------
// The arrival is a real alpha now, written straight out and blended by the pass that draws it — see
// GrassRenderer's blended overload for why blending is affordable for the few tufts mid-arrival even
// though it is not for the field as a whole.
//
// It used to be screen-doored: alpha turned into a discard against an interleaved-gradient-noise
// threshold in screen space, because the whole field was drawn in one opaque pass. That works and is
// the standard answer, and it has one flaw that no amount of tuning reaches — the pattern is anchored
// in *screen* space, so as a tuft moves across the screen the holes land on different parts of the
// blade every frame. A half-arrived tuft therefore does not read as faint, it **fizzes**, which on a
// clump a hundred pixels tall standing among settled neighbours is precisely what the eye catches.
// Reported from play as grass becoming visible in a distracting way while everything around it had
// been standing for a while. Nothing is discarded for the arrival any more.
//
// Tufts that have fully arrived carry a fade of exactly 1 and are drawn in the opaque pass with
// blending off, where this alpha is simply ignored.

void main() {
    // The cutout is for the billboard pass alone. A near clump is 24 modelled blade strips on a texture
    // measured fully opaque (100% at alpha 255), so its cutoff is 0 and nothing is ever discarded; the
    // far billboard is a quad whose blade shape lives entirely in the baked sprite's alpha. Both stay in
    // the opaque pass either way — a discard needs no blending and no sorting.
    vec4 texel = hasAlbedo == 1 ? texture(albedoTexture, uv) : vec4(1.0);
    // Through the coverage-preserving rescale, which for the 3D clump is doubly a no-op — its texture is
    // fully opaque and its cutoff is 0 — and for the billboard is the thing that stops a distant tuft
    // thinning out of existence. The branch is on a UNIFORM, so the derivatives inside are taken in
    // uniform control flow; a per-fragment branch here would make them undefined.
    float coverage = hasAlbedo == 1 ? cutoutAlpha(albedoTexture, uv, texel.a) : texel.a;
    if (coverage < alphaCutoff) {
        discard;
    }

    // The blade's own colour: the root-to-tip gradient, evaluated at this fragment's own height.
    //
    // Per FRAGMENT rather than per vertex, which matters because the ramp is piecewise. Interpolating a
    // colour computed at the vertices would draw a straight line between whatever two heights a triangle's
    // corners happen to sit at, so the middle stop would land wherever the tessellation put it and a blade
    // modelled with few segments would miss the bow entirely. Two mixes a fragment is not worth caring
    // about next to that.
    //
    // The texture's RGB is deliberately not multiplied in. The clump carries no image, so it would be a
    // no-op today and a double-darkening the moment a green one came back; only the ALPHA is read, which
    // is what the billboard's blade shape lives in.
    int v = clamp(int(variant + 0.5), 0, GRASS_VARIANTS - 1);
    vec3 blade = heightFrac < GRADIENT_MID
            ? mix(grassRoot[v], grassMid[v], heightFrac / GRADIENT_MID)
            : mix(grassMid[v], grassTip[v], (heightFrac - GRADIENT_MID) / (1.0 - GRADIENT_MID));
    if (albedoColour == 1 && hasAlbedo == 1) {
        // Painted: the texture is the blade, the gradient only tints it (a variant's warmer or cooler patch).
        blade = texel.rgb * blade;
    }

    // The root-to-tip gradient. A tuft used to be one flat green from soil to tip, so the line where it
    // met the ground was a hard edge between two unrelated colours — the single most obvious way a field
    // of separate clumps reads as models standing on a texture rather than as grass growing out of it.
    // The base is drawn in the terrain's own colour instead and gives way to the blade going up.
    //
    // The ground colour is per instance and comes from the same multi-biome blend that paints the terrain
    // (see GrassField), so it cannot disagree with what is actually under the tuft — which is the whole
    // reason it is sampled rather than assumed. It follows a biome edge, a patch of dug subsoil or a snow
    // line for free, and it is why this is worth 12 bytes an instance over simply darkening the roots.
    // Two separate things, in this order.
    //
    // FIRST, what the grass is MADE of: the ground's hue and saturation imposed on the whole blade at the
    // blade's own luminosity — see GROUND_COLOR_BLEND. Even from root to tip, because a plant growing in
    // ash is ashen all the way up rather than only at its ankles.
    vec3 biome = mix(blade, colorBlend(blade, groundColor), groundTint);

    // SECOND, the join: a straight mix toward the ground, concentrated hard at the base. It has to be a
    // straight mix rather than a colour blend, and that is the reason the two cannot be one term — a
    // colour blend preserves the BLADE's luminosity by definition, so a base blended that way keeps the
    // blade's brightness and stays plainly visible against dark soil. Disappearing into the ground means
    // taking the ground's value as well as its hue, which is what a straight mix does and a colour blend
    // never can.
    // max: interpolation can carry heightFrac a rounding error past 1 at a tip, and pow of a negative base is NaN.
    float groundMix = GROUND_BLEND * pow(max(1.0 - heightFrac, 0.0), GROUND_FALLOFF);
    vec3 rooted = mix(biome, groundColor, groundMix);

    // Contact shadow, applied over the blend rather than folded into it: it has to darken the ground
    // colour too, or the roots come out exactly the terrain's brightness and the tuft loses its footing.
    // Painted grass keeps its roots lighter: its depth is in its colours already.
    float contact = mix(albedoColour == 1 ? 0.9 : CONTACT_SHADE, 1.0, heightFrac * heightFrac);

    vec3 albedo = rooted * shade * contact;
    // A gust passing: the bowed tips catch the light, most at the tips.
    albedo *= 1.0 + gustLight * 0.4 * heightFrac;

    // The blade's own normal is deliberately not used as-is: it is baked up-biased at load time (see
    // GrassMeshFactory), so a tuft takes light much the way the ground under it does and does not read as
    // a patch of differently-lit spikes pasted onto the hillside. That also sidesteps the source model's
    // mirrored halves, whose normals point into the ground. Both faces are shaded with the same normal —
    // there is no gl_FrontFacing flip here, because flipping an up-biased normal points it downward.
    vec3 normal = normalize(worldNormal);

    float NdotL = dot(normal, toSun);

    // The same four-step banding as terrain.frag and foliage.frag, deliberately: grass stands on that
    // ground and among those trees, so it has to answer the light with the same curve or it reads as
    // pasted on. A copy rather than an include — GLSL 330 has none.
    float band = smoothstep(0.26, 0.32, NdotL) * 0.34
               + smoothstep(0.58, 0.64, NdotL) * 0.33
               + smoothstep(0.83, 0.89, NdotL) * 0.33;

    band *= sunVisibility(worldPos, normal, NdotL);

    vec3 light = mix(ambientColor, sunColor, band);
    vec3 color = albedo * light;

    // No rim term, unlike every other scene shader here, and that is deliberate rather than an omission.
    // A rim light picks out the silhouette of a rounded volume, and it does that by leaning on
    // 1 - dot(normal, toView) being small except near the edge. Grass has neither property: the normals
    // are biased to up (see above) and the camera looks along the ground, so that factor is close to 1
    // for the *whole* field at once and the term stops being an edge highlight and becomes a flat white
    // wash over every blade. It rendered as exactly that — pale, chalky tufts on a green field — before
    // this came out.

    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, saturation);

    float fog = aerialPerspective(worldPos, viewPos);
    color = mix(color, fogColor, fog);

    // The arrival, as a real alpha. A tuft materialises over its fade band instead of appearing in one
    // frame; a settled tuft carries exactly 1 and is drawn in the opaque pass, where this is ignored.
    // The texture's own alpha rides along for the billboard path, whose blade shape lives in it.
    fragColor = vec4(color, texel.a * fade);
    fragGeometry = receivesOcclusion == 1 ? vec4(normal * 0.5 + 0.5, 1.0 - band) : vec4(0.5, 0.5, 0.5, 1.0);
}
