#version 330 core

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec3 inTint;
layout(location = 4) in vec3 inTangent;

uniform mat4 model;
// --- Instancing -----------------------------------------------------------------------------------
// The prop pools are a few shared meshes drawn hundreds of times, so they are submitted as one instanced
// call per mesh with the transform streamed per instance. See engine.render.InstanceBuffer.
//
// A uniform switch rather than a second vertex shader. GLSL 330 has no #include, so a duplicate would be
// a copy of this whole file to keep in step — the same trap the copied sunVisibility block already is,
// and this one would be worse because a drifted copy shows as props lit differently from terrain.
// Reading an attribute nobody bound is *defined* (it returns the constant), so the unused branch is safe,
// and the branch itself is on a uniform and therefore free.
uniform int instanced;
layout(location = 5) in mat4 inModel;
layout(location = 9) in vec3 inInstanceTint;
uniform mat4 view;
uniform mat4 projection;
uniform mat3 normalMatrix;
// Per-instance albedo multiplier on top of the mesh's baked vertex tint. White for ordinary props; the
// dark forest darkens its trees toward grey-black through this without needing a second mesh.
uniform vec3 instanceTint;

/**
 * The world's wind, in world units per second — the same velocity the clouds drift in and the grass leans
 * along.
 */
uniform vec2 windVelocity;

/**
 * The scene clock, and how much this pass sways in it.
 *
 * <p>Zero for bark and for everything else drawn through this shader, so only leaves move. Bark and leaves
 * are separate draws of the same shader, which is what makes a per-pass amount the whole mechanism: a
 * swaying trunk would be a tree made of rubber, and a sway applied to both would move them by different
 * amounts anyway because the tint attribute is all that distinguishes them here.
 */
uniform float time;
uniform float leafSway;

/** Sway cycles per second at one world unit per second of wind, matching grass.vert's own conversion. */
const float SWAY_RATE_PER_SPEED = 0.317;

/**
 * 1 to turn each card to face the camera about its centre, which the mesh carries in the tangent channel
 * (TexturedMeshBuilder.addCard): the anime leaves, so every clump shows its whole shape from any side and none is
 * seen edge on as a sliver. Each is turned in the view by an angle of its own, so they do not all stand the same way
 * up. The normal is left as it is — the crown's, not the card's.
 */
uniform int billboard;

out vec3 worldPos;
/** The card's centre in the world, for a shader that shades a whole card alike; the vertex itself otherwise. */
out vec3 cardCentre;
out vec3 worldNormal;
out vec2 uv;
out vec3 tint;
out vec3 worldTangent;

void main() {
    // Uniform scale everywhere props are concerned, so mat3(m) is s*R and normalising divides the s out
    // — the same answer the inverse-transpose normalMatrix gives, for no extra attributes.
    mat4 m = instanced == 1 ? inModel : model;
    vec4 world;
    if (billboard == 1) {
        vec4 centre = m * vec4(inTangent, 1.0);
        // The corners are half the card's diagonal from its centre; the card is scaled as its tree is.
        float half = length(inPosition - inTangent) * 0.70710678 * length(m[0].xyz);
        // V runs down the picture: v = 1 is the card's bottom edge.
        vec2 corner = (vec2(inUv.x, 1.0 - inUv.y) * 2.0 - 1.0) * half;
        float turn = (fract(sin(dot(inTangent, vec3(12.9898, 78.233, 37.719))) * 43758.5453) - 0.5) * 1.0;
        corner = mat2(cos(turn), sin(turn), -sin(turn), cos(turn)) * corner;
        vec3 cameraRight = vec3(view[0][0], view[1][0], view[2][0]);
        vec3 cameraUp = vec3(view[0][1], view[1][1], view[2][1]);
        world = vec4(centre.xyz + cameraRight * corner.x + cameraUp * corner.y, 1.0);
        cardCentre = centre.xyz;
    } else {
        world = m * vec4(inPosition, 1.0);
        cardCentre = world.xyz;
    }

    // Leaves ride the wind. Phase varies with world position so a canopy ripples rather than every card in
    // it flicking together, and the displacement is a fraction of a leaf rather than a distance in world
    // units — the trap grass.vert records, where a plain offset laid a 12 cm tuft flat across the ground.
    //
    // The cards move and the branch they hang on does not, which is a small lie and the standard one: doing
    // it properly means the whole limb bending, and a limb here is a tube whose vertices know nothing about
    // which twig they belong to. At this amplitude the join is not something the eye follows.
    if (leafSway > 0.0) {
        float windSpeed = length(windVelocity);
        vec2 windDirection = windSpeed > 0.0001 ? windVelocity / windSpeed : vec2(1.0, 0.0);
        float phase = time * windSpeed * SWAY_RATE_PER_SPEED
                    + world.x * 0.45 + world.z * 0.38 + world.y * 0.22;
        float lean = sin(phase) + 0.35 * sin(phase * 2.3 + 1.7);
        world.xz += windDirection * lean * leafSway;
    }

    worldPos = world.xyz;
    worldNormal = instanced == 1
            ? normalize(mat3(m) * inNormal)
            : normalize(normalMatrix * inNormal);
    // The tangent transforms by the normal matrix too. For the uniform scales used by props this is the
    // same as the model matrix, but it stays correct if an instance is ever scaled non-uniformly — which
    // stumps already are.
    worldTangent = instanced == 1
            ? normalize(mat3(m) * inTangent)
            : normalize(normalMatrix * inTangent);
    uv = inUv;
    tint = inTint * (instanced == 1 ? inInstanceTint : instanceTint);

    gl_Position = projection * view * world;
}
