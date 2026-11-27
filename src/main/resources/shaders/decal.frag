#version 330 core

in float vEdge;

uniform vec3 decalColor;
uniform float decalAlpha;

out vec4 fragColor;

// Where the rim starts, as a fraction of the radius. The fill answers "which ground is affected" and the
// rim answers "exactly where does it stop" — and the second question is the one the old solid ghost could
// not answer at all, because its visible half was the part standing in the air doing nothing.
const float RIM_START = 0.80;

// How much of the alpha the fill gets. Deliberately low: this sits over ground the player is looking at
// constantly, so it has to read as a marking on the terrain rather than as a pane of glass over it.
const float FILL_SHARE = 0.35;

void main() {
    // Increasing edges, never the reversed-edge form. smoothstep is undefined when edge0 >= edge1 and this
    // driver has already drawn a starfield as grid-aligned squares over exactly that mistake.
    float rim = smoothstep(RIM_START, 1.0, vEdge);
    float alpha = decalAlpha * mix(FILL_SHARE, 1.0, rim);

    // The rim is also brighter, not just more opaque. Alpha alone leaves the outline reading as a slightly
    // thicker patch of the same wash over bright ground, where a lift in value survives any background.
    fragColor = vec4(decalColor * mix(0.75, 1.35, rim), alpha);
}
