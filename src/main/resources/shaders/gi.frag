#version 330 core

// Screen-space global illumination: how much of the sky a fragment can actually see, and what colour the
// surfaces around it are reflecting onto it. One gather per pixel produces both, because they are the same
// question asked of the same samples — an occluder is exactly a surface that blocks sky light and sends its
// own back instead.
//
// Run at half resolution into a target of its own. The scene buffer's depth, geometry and colour are all
// read here and none of them is bound for writing, which is the whole reason this is a separate pass rather
// than something folded into the composite: sampling a texture attached to the framebuffer you are drawing
// into is a feedback loop, and it is undefined however carefully the depth writes are masked off.

in vec2 uv;

// rgb: light bounced onto this fragment, already scaled by how receptive it is.
// a:   how much of its fill light survives — 1 unoccluded, 0 fully enclosed.
out vec4 fragColor;

uniform sampler2D sceneDepth;
uniform sampler2D sceneGeometry;   // rgb = world normal biased to 0..1, a = ambient fill fraction; no normal:
                                   // nothing to shade there, and a = 1 means nothing in the way either
uniform sampler2D sceneColor;      // the HDR scene, for the bounce

uniform mat4 projection;
uniform mat4 invProjection;
uniform mat4 view;                 // world -> view, to bring the stored world normals into this pass's space

uniform vec2 sceneTexel;           // 1 / full resolution
uniform float radius;
uniform float bias;
uniform float fillFloor;
uniform float range;
uniform int sampleCount;

#define MAX_SAMPLES 32
// A hemisphere about +Z, weighted toward the origin so the near field — where contact darkening lives —
// gets most of the taps. Built once on the CPU: a fixed set is reproducible and costs no per-pixel work,
// where a hash-generated direction would be recomputed for every pixel of every frame.
uniform vec3 kernel[MAX_SAMPLES];

/**
 * View-space position of whatever was drawn at a texel.
 *
 * <p>Unprojecting the depth buffer rather than storing positions: a position target would be a third
 * full-resolution attachment carrying data the depth already holds exactly.
 */
vec3 viewPositionAt(vec2 texUv, float depth) {
    vec4 clip = vec4(texUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 v = invProjection * clip;
    return v.xyz / v.w;
}

/**
 * Interleaved gradient noise — a per-pixel angle to spin the kernel by.
 *
 * <p>Without it every pixel gathers in the same directions and the banding that produces is a pattern the
 * eye locks onto immediately; with it the error becomes noise instead, which is what the blur that follows
 * is able to remove. The particular form is chosen because its output decorrelates over a 3x3 neighbourhood,
 * so a small blur kernel sees a full spread of rotations rather than a handful repeated.
 */
float rotationNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    // The full-resolution texel this half-resolution pixel stands for. texelFetch rather than a filtered
    // read: a depth interpolated across a silhouette describes a surface that is not there, and the
    // reconstruction below would place it in mid-air.
    ivec2 fullTexel = ivec2(gl_FragCoord.xy) * 2;
    vec2 fullUv = (vec2(fullTexel) + 0.5) * sceneTexel;

    float depth = texelFetch(sceneDepth, fullTexel, 0).r;
    vec4 geometry = texelFetch(sceneGeometry, fullTexel, 0);
    vec3 rawNormal = geometry.rgb * 2.0 - 1.0;

    // Nothing was drawn here, or what was drawn writes no geometry — the sky, or the character's outline
    // hull, which is flat ink and has no business taking bounced light. Fully lit, no bounce: the two
    // values that make the resolve below a no-op.
    if (depth >= 1.0 || dot(rawNormal, rawNormal) < 0.01) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec3 position = viewPositionAt(fullUv, depth);
    vec3 normal = normalize(mat3(view) * rawNormal);

    // How much of this fragment's light is ambient fill, which is the only part occlusion may touch. A
    // sunlit surface's key light already has a shadow map deciding whether it arrives; taking it away again
    // here would be the same scene darkened twice for one reason.
    float receptivity = max(geometry.a, fillFloor);

    // Distance fade. The radius subtends fewer and fewer pixels as a surface recedes, so past the range
    // every tap lands in the texel it started from and the pass pays in full to return nothing.
    float viewDistance = -position.z;
    float reach = 1.0 - smoothstep(range * 0.7, range, viewDistance);
    if (reach <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // A basis about the normal, spun per pixel. The kernel is a hemisphere about +Z, so this is what points
    // it out of the surface.
    float angle = rotationNoise(gl_FragCoord.xy) * 6.2831853;
    vec3 seed = vec3(cos(angle), sin(angle), 0.0);
    vec3 tangent = normalize(seed - normal * dot(seed, normal));
    mat3 basis = mat3(tangent, cross(normal, tangent), normal);

    float occlusion = 0.0;
    vec3 bounce = vec3(0.0);

    for (int i = 0; i < sampleCount; i++) {
        vec3 samplePos = position + basis * kernel[i] * radius;

        vec4 clip = projection * vec4(samplePos, 1.0);
        if (clip.w <= 0.0) {
            continue;
        }
        vec2 sampleUv = clip.xy / clip.w * 0.5 + 0.5;
        // Off screen there is no information, and clamping would smear the frame's edge pixel inward as a
        // band of false occlusion all the way round.
        if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) {
            continue;
        }

        float sampleDepth = texture(sceneDepth, sampleUv).r;
        if (sampleDepth >= 1.0) {
            continue;   // sky: the thing this is measuring the visibility of, so never an occluder
        }
        vec3 occluder = viewPositionAt(sampleUv, sampleDepth);

        // View space looks down -Z, so a nearer surface has the larger z. An occluder in front of the
        // sample point is what blocks it.
        if (occluder.z <= samplePos.z + bias) {
            continue;
        }

        // Reject an occluder that merely happens to lie in front in screen space while being nowhere near
        // in the world — the far hillside behind a tree trunk, which would otherwise ring every silhouette
        // with a dark halo. Fades rather than cuts, or the halo comes back as a hard edge.
        //
        // Measured in TRUE DISTANCE, not along the view axis. Comparing view-space z is the cheaper and far
        // more common form, and it is wrong for exactly the surface this world is mostly made of: ground
        // seen at a grazing angle, where two points a hand's breadth apart differ by metres of z. It threw
        // away nearly every genuine hit on open terrain, and the whole pass measured as doing almost
        // nothing. The vector is needed for the bounce below in any case, so this costs one length().
        vec3 toOccluder = occluder - position;
        float distance = length(toOccluder);
        float within = 1.0 - smoothstep(0.0, 1.0, distance / max(radius, 1e-4));
        if (within <= 0.0) {
            continue;
        }

        // What wrote no normal and alpha 1 asked to be left out of occlusion altogether — grass drawn as a
        // painted field — so it shades nothing round it. A sprite writes no normal with alpha 0, and still
        // shades the ground at its feet.
        vec4 occluderGeometry = texture(sceneGeometry, sampleUv);
        vec3 occluderRaw = occluderGeometry.rgb * 2.0 - 1.0;
        if (dot(occluderRaw, occluderRaw) < 0.01 && occluderGeometry.a > 0.5) {
            continue;
        }

        occlusion += within;

        // The same hit, read as a light source. This is the part that makes it global illumination rather
        // than ambient occlusion: the occluder is not merely blocking the sky, it is reflecting whatever
        // reached it back at this fragment, and the scene buffer already holds that colour.
        if (distance > 1e-3) {
            vec3 direction = toOccluder / distance;
            vec3 occluderNormal = normalize(mat3(view) * occluderRaw);
            // Both ends of the transfer: how squarely this fragment faces the occluder, and how squarely
            // the occluder faces back. Dropping the second term is the usual shortcut and it makes a
            // surface bleed onto whatever is behind it, which reads as light leaking through walls.
            float toward = max(dot(normal, direction), 0.0);
            float facing = max(dot(-direction, occluderNormal), 0.0);
            bounce += texture(sceneColor, sampleUv).rgb * toward * facing * within;
        }
    }

    float visibility = 1.0 - (occlusion / float(sampleCount));

    fragColor = vec4(
            bounce / float(sampleCount) * receptivity * reach,
            mix(1.0, visibility, receptivity * reach));
}
