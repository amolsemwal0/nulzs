#version 330 core

// The first pass of screen-space fluid rendering: how far away the nearest particle surface is, per pixel.
//
// This is the whole idea of the technique. Thousands of particles are never turned into geometry at all —
// no marching cubes, no mesh, nothing rebuilt on the CPU. Each is drawn as a sphere, the depth buffer keeps
// whichever is nearest at each pixel, and what comes out is a DEPTH IMAGE of the fluid's surface as seen
// from here. Smoothing that image and reading its gradient gives a normal, and a normal plus a depth is
// everything shading needs. The surface is only ever constructed for the pixels that can see it.
//
// The sphere is reconstructed analytically rather than drawn as a mesh: the quad's own coordinate says how
// far from the centre this pixel is, and the rest is Pythagoras. So a particle is round from every angle at
// any distance, costs two triangles, and has no tessellation to be caught at.

in vec3 vEyeCenter;
in float vRadius;
in float vFluid;
in vec2 vOffset;

uniform mat4 projection;
// The opaque scene's depth, from a framebuffer that is NOT the one being written here. That separation is
// the point: rejecting against the scene in this pass rather than at composite time means nothing ever
// samples a depth texture attached to the target it is drawing into, which is undefined behaviour and the
// kind that works on the driver it was written against.
uniform sampler2D sceneDepth;
uniform vec2 invTargetSize;

// R: distance from the eye, positive and linear. G: which fluid, so the composite can shade lava and water
// differently without a second pass over the particles.
layout(location = 0) out vec2 fragSurface;

void main() {
    float r2 = dot(vOffset, vOffset);
    if (r2 > 1.0) {
        discard;                       // outside the sphere's silhouette — the quad's corners
    }

    // The near cap of the sphere, in view space. z is toward the camera, so this is the front surface.
    float z = sqrt(1.0 - r2);
    vec3 eye = vEyeCenter + vec3(vOffset * vRadius, z * vRadius);

    vec4 clip = projection * vec4(eye, 1.0);
    float ndcDepth = clip.z / clip.w;
    // Writing the sphere's own depth rather than the quad's is what makes particles sort against each
    // other correctly: without it, whichever quad was submitted last wins, and a cloud of spheres renders
    // as a flat collage in emission order.
    gl_FragDepth = ndcDepth * 0.5 + 0.5;

    float scene = texture(sceneDepth, gl_FragCoord.xy * invTargetSize).r;
    if (gl_FragDepth > scene) {
        discard;                       // behind the terrain — a splash on the far side of a hill
    }

    fragSurface = vec2(-eye.z, vFluid);
}
