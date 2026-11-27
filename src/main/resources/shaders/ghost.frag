#version 330 core

in vec3 worldPos;
in vec3 worldNormal;
in vec3 vertexColor;

// Flat tint for the whole preview (green = legal spot, red = illegal), plus a fresnel-style rim so the
// silhouette reads clearly against the world behind it rather than dissolving into a faint wash.
uniform vec3 ghostColor;
uniform float ghostAlpha;
// 1 to wear the mesh's own vertex colours instead of ghostColor: several washes of colour in one draw.
uniform int vertexColors;
uniform vec3 viewPos;

out vec4 fragColor;

void main() {
    vec3 n = normalize(worldNormal);
    vec3 toView = normalize(viewPos - worldPos);
    float facing = abs(dot(n, toView));
    float rim = pow(1.0 - facing, 2.0);

    vec3 color = (vertexColors == 1 ? vertexColor : ghostColor) + rim * 0.4;
    float alpha = clamp(ghostAlpha + rim * 0.45, 0.0, 0.9);
    fragColor = vec4(color, alpha);
}
