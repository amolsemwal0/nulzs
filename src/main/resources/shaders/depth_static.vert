#version 330 core

// Depth-only pass for unskinned geometry: terrain slabs, rock props, tree bark.
//
// Only location 0 is declared. Every mesh class in this project puts position there (StaticMesh's
// 36-byte layout, TexturedMesh's 56-byte one), and the extra attributes their VAOs leave enabled are
// simply ignored by a shader that does not declare them — so one shader covers both formats without
// either having to know about the shadow pass.
layout(location = 0) in vec3 inPosition;

uniform mat4 model;
uniform mat4 lightSpaceMatrix;

// --- Instancing -----------------------------------------------------------------------------------
// The same switch terrain.vert and foliage.vert carry, for the same reason and reading the same attribute
// locations — see engine.render.InstanceBuffer. This pass was the last one still submitting one draw per
// prop, and it was by far the biggest offender: measured, 551 of a frame's 678 draw calls were the shadow
// pass alone. Halving the map's resolution — a quarter of the pixels — took the pass from 1.71 ms to only
// 1.40, which is what says it is bound by *submitting* those draws rather than by filling them.
//
// A depth pass needs no normal, so unlike the colour shaders there is nothing here but the matrix.
uniform int instanced;
layout(location = 5) in mat4 inModel;

void main() {
    mat4 m = instanced == 1 ? inModel : model;
    gl_Position = lightSpaceMatrix * m * vec4(inPosition, 1.0);
}
