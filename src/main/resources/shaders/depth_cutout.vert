#version 330 core

// Depth-only pass for alpha-cut geometry: leaf cards.
//
// Location 2 is the UV in TexturedMesh's layout, which is what the cutout needs. Casting leaves through
// the plain depth shader instead would shadow the whole quad, and a canopy of a few hundred opaque
// rectangles casts a solid black slab rather than dappled shade.
layout(location = 0) in vec3 inPosition;
layout(location = 2) in vec2 inUv;

uniform mat4 model;
uniform mat4 lightSpaceMatrix;

// See depth_static.vert: the same instancing switch, reading the same locations from the same
// InstanceBuffer. Leaf cards are the single largest group of shadow submissions in the frame.
uniform int instanced;
layout(location = 5) in mat4 inModel;

out vec2 uv;

void main() {
    uv = inUv;
    mat4 m = instanced == 1 ? inModel : model;
    gl_Position = lightSpaceMatrix * m * vec4(inPosition, 1.0);
}
