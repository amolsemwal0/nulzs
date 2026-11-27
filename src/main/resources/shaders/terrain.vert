#version 330 core

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec3 inColor;
// Per-vertex ground-material weights. Meshes without them rely on the constant attribute being cleared
// to zero — see StaticMesh.clearGroundWeights — since OpenGL's default is (0,0,0,1) and that stray 1
// would apply the fourth material to every prop in the world.
layout(location = 3) in vec4 inGroundWeights;
// Slots 4-7. A vertex attribute holds at most four components, so the set outgrowing one vec4 means a
// second attribute rather than a wider one. Same stray-1 hazard, so clearGroundWeights zeroes it too.
layout(location = 4) in vec4 inGroundWeightsHigh;
// Slots 8-11, at 10 because the instance matrix below takes 5-8 (and the foliage pass's tint 9). Zeroed
// by clearGroundWeights with the others.
layout(location = 10) in vec4 inGroundWeightsThird;

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
uniform mat4 view;
uniform mat4 projection;
uniform mat3 normalMatrix;

// --- Ground cover parting --------------------------------------------------------------------------
// Cover bends away from whoever is standing in it. Switched on for the ground-cover pass alone — this
// shader also draws the terrain itself and every rock, wall and building on it, and none of those give
// way when walked into. See Renderer.renderGroundCover and engine.render.CoverPush.
//
// The displacement is measured from the instance's own ORIGIN, not from the vertex: the roots of a clump
// are where it stands, so the whole clump leans as one thing. Weighting it per vertex instead would pull
// the near blades further than the far ones and splay a tuft open from the middle.
uniform int coverPush;
uniform vec3 pushCentre;   // the feet parting it, in world space
uniform float pushRadius;  // how far out the parting reaches; 0 disables it
uniform float pushStrength;// how far a tip is shoved aside, in world units
uniform float pushHeight;  // the height the cover stands at, for the root-to-tip weight

out vec3 worldPos;
out vec3 worldNormal;
out vec3 vertexColor;
// Interpolated, deliberately not flat: these weights exist to make a biome boundary a gradual mixture,
// and a flat varying would step it once per triangle.
out vec4 groundWeights;
out vec4 groundWeightsHigh;
out vec4 groundWeightsThird;

void main() {
    // Uniform scale everywhere props are concerned, so mat3(m) is s*R and normalising divides the s out
    // — the same answer the inverse-transpose normalMatrix gives, for no extra attributes.
    mat4 m = instanced == 1 ? inModel : model;
    vec4 world = m * vec4(inPosition, 1.0);
    if (coverPush == 1 && pushRadius > 0.0) {
        // The fourth column of a model matrix is where the thing stands.
        vec3 roots = m[3].xyz;
        vec2 away = roots.xz - pushCentre.xz;
        float range = length(away);
        if (range < pushRadius) {
            // Squared, exactly as the wind's lean is: the roots are in the soil and only the tips give,
            // so a blade bows rather than the clump sliding off the spot it grows on.
            float up = clamp((world.y - roots.y) / pushHeight, 0.0, 1.0);
            // Smoothed rather than linear, so the edge of the parting is not a visible circle travelling
            // with the player. Written out because GLSL's smoothstep is undefined with equal edges and
            // pushRadius is a uniform.
            float t = 1.0 - range / pushRadius;
            float falloff = t * t * (3.0 - 2.0 * t);
            // A clump standing exactly underfoot has no direction to lean away in, so it is given one.
            // Which one does not matter: at that range it is under the body and cannot be seen.
            vec2 direction = range > 0.0001 ? away / range : vec2(1.0, 0.0);
            world.xz += direction * (pushStrength * up * up * falloff);
        }
    }
    worldPos = world.xyz;
    worldNormal = instanced == 1
            ? normalize(mat3(m) * inNormal)
            : normalize(normalMatrix * inNormal);
    vertexColor = inColor;
    groundWeights = inGroundWeights;
    groundWeightsHigh = inGroundWeightsHigh;
    groundWeightsThird = inGroundWeightsThird;

    gl_Position = projection * view * world;
}
