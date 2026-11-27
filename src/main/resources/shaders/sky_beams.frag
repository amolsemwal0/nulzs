#version 330 core

// Sky beams, the first pass (SunRays.java): the light the sky gives off round the sun, where the clouds let it through.
// Only open sky does: a pixel showing the land, a building or a tree gives none, and a cloud over the sky blocks it as
// much as it is opaque, by SkyDome's sun mask. Spread out from the sun by sky_beams_blur.frag, the gaps between clouds
// and between the hills and trees on the skyline become beams.
//
// Only while something is in the way: a sun in open sky makes no beams, only its glow. How much of the sky round the
// sun is covered, by cloud or by whatever stands in front of it on the screen, is sampled on rings round the sun; the
// beams come in as that rises, so they are always the shapes of real clouds, trees and roofs.

in vec2 uv;
out float light;

uniform sampler2D sceneDepth;
uniform sampler2D cloudMask;
uniform mat4 cloudMaskViewProjection;
uniform mat4 invViewProjection;
uniform mat4 viewProjection;
uniform vec3 cameraPosition;
uniform vec3 toSun;

float cloudAt(vec3 direction) {
    vec4 clip = cloudMaskViewProjection * vec4(direction, 1.0);
    return clip.w > 0.0 ? texture(cloudMask, clip.xy / clip.w * 0.5 + 0.5).r : 0.0;
}

// How much of the sky round the sun is covered, 0 to 1: rings of directions out to about 12 degrees, each counted as
// covered by as much cloud as is there, or wholly where the scene stands in front of it on the screen.
float covered() {
    vec3 side = normalize(abs(toSun.y) < 0.95 ? cross(toSun, vec3(0.0, 1.0, 0.0)) : cross(toSun, vec3(1.0, 0.0, 0.0)));
    vec3 up = cross(side, toSun);
    float total = 0.0;
    float weights = 0.0;
    for (int ring = 1; ring <= 3; ring++) {
        float angle = 0.07 * float(ring);
        // Nearer the sun counts for more: that is where the glow the beams are made of is.
        float weight = 1.0 / float(ring);
        for (int i = 0; i < 8; i++) {
            float around = 6.2831853 * (float(i) + 0.5 * float(ring)) / 8.0;
            vec3 direction = normalize(toSun + tan(angle) * (cos(around) * side + sin(around) * up));
            float cover = cloudAt(direction);
            vec4 clip = viewProjection * vec4(cameraPosition + direction * 500.0, 1.0);
            if (clip.w > 0.0) {
                vec2 at = clip.xy / clip.w * 0.5 + 0.5;
                if (all(greaterThan(at, vec2(0.0))) && all(lessThan(at, vec2(1.0))) && texture(sceneDepth, at).r < 1.0) {
                    cover = 1.0;
                }
            }
            total += cover * weight;
            weights += weight;
        }
    }
    return total / weights;
}

void main() {
    if (texture(sceneDepth, uv).r < 1.0) {
        light = 0.0;
        return;
    }
    vec4 far = invViewProjection * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec3 direction = normalize(far.xyz / far.w - cameraPosition);
    float toward = max(dot(direction, toSun), 0.0);
    // Brightest at the sun, still giving light some way round it, so gaps a little off the sun make beams too.
    float glow = pow(toward, 500.0) + 0.3 * pow(toward, 70.0);
    light = glow * (1.0 - cloudAt(direction)) * smoothstep(0.05, 0.3, covered());
}
