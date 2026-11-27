package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.zeronura.engine.physics.FieldContact;
import org.zeronura.engine.physics.SolidField;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * A flat shape painted onto the terrain wherever it happens to lie — the footprint of something about to
 * happen to the ground, rather than a solid floating in it.
 *
 * <h2>Why not just draw the solid</h2>
 * The terraforming preview started as the actual dig volume: a translucent sphere or cube, drawn at the
 * spot the swing would land. That is the most literal possible preview and it reads <b>backwards</b>. A
 * dig is centred on the surface, so half the solid stands in the air and half is buried — and the half
 * you can see is precisely the half that does nothing, while the half doing the work is inside the ground
 * and invisible. What the player is trying to judge is how much <i>ground</i> goes away, and a ball
 * hanging in the air in front of them is a picture of the wrong thing. Worse, it over-promises: the
 * silhouette is a whole sphere and the hole is the part of it that had material in it.
 *
 * <p>Projecting the cross-section onto the surface answers the question that was actually being asked. It
 * is also very nearly exact rather than a convenient approximation: material is removed where the edit's
 * falloff beats the field's own density, and at the surface the field's density goes to zero, so removal
 * there extends to essentially the <i>full</i> radius. The footprint drawn here is the footprint dug.
 *
 * <h2>Conformed on the CPU, not projected in a shader</h2>
 * The standard trick is a deferred decal — draw a box, read the depth buffer, reconstruct world position,
 * discard outside the shape. That needs the scene's depth as a sampleable texture, which would mean
 * changing the HDR scene target that every pass already renders into. Here the terrain is a density field
 * the CPU can query directly, so each vertex is simply dropped onto the surface with a short bisection.
 * It costs a few hundred field probes a frame, touches nothing else, and — unlike a depth-buffer decal —
 * is correct for ground that is hidden behind something in this particular frame.
 *
 * <h2>Oriented to the surface, so a wall works like a floor</h2>
 * The patch is laid out in the tangent plane of the surface it is landing on, using
 * {@link FieldContact#normalAt}, and each vertex is conformed <i>along that normal</i>. A plain top-down
 * projection is simpler and is wrong for exactly the case a pickaxe spends its life in: mining into a
 * cliff face or a cave wall, where a vertical drop finds the floor several metres below and paints the
 * marking somewhere the player is not aiming.
 *
 * <p>Generic: it knows about a {@link SolidField} and a {@link DecalShape} and has never heard of digging.
 */
public final class SurfaceDecal {

    /** Rings and segments for a round patch, and grid steps across a square one. */
    private static final int RINGS = 4;
    private static final int SEGMENTS = 28;
    private static final int GRID = 12;

    /**
     * How far along the normal a vertex is lifted off the surface it landed on.
     *
     * <p>It cannot be zero — a patch built to sit exactly on the surface z-fights with it over its whole
     * area, which flickers as the camera moves. But the real reason it is centimetres rather than
     * millimetres is that <b>this conforms to the density field and the terrain drawn is Surface Nets'
     * smoothed approximation of it</b>: a mesh vertex sits at the average of the edge crossings in its
     * cell, so the drawn ground and the true isosurface disagree by a fraction of a cell wherever the
     * field curves. Under that, a hair's lift leaves the marking buried in the hillside it is describing.
     */
    private static final float LIFT = 0.12f;

    /** Bisection steps when dropping a vertex onto the surface. Seven brings the search band under 2 cm. */
    private static final int REFINE_STEPS = 7;

    /** Floats per vertex: position (3) + the normalised distance from the middle (1). */
    private static final int FLOATS_PER_VERTEX = 4;

    /** Enough for the denser of the two layouts (a square grid) with room to spare. */
    private static final int MAX_VERTICES = 4096;

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final FloatBuffer vertices;
    private int vertexCount;

    public SurfaceDecal() {
        shader = new Shader("/shaders/decal.vert", "/shaders/decal.frag");
        vertices = BufferUtils.createFloatBuffer(MAX_VERTICES * FLOATS_PER_VERTEX);

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) vertices.capacity() * Float.BYTES, GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    /**
     * Builds the patch for this frame and draws it.
     *
     * @param centre where the shape is centred — the point the tool is aimed at
     * @param radius half-extent, meaning the same thing it means to the edit being previewed
     */
    public void render(Camera camera, SolidField field, Vector3f centre, float radius, DecalShape shape,
                       Vector3f color, float alpha) {
        build(field, centre, radius, shape);
        if (vertexCount == 0) {
            return;
        }

        boolean cullWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthWriteWas = glGetBoolean(GL_DEPTH_WRITEMASK);

        glEnable(GL_DEPTH_TEST);
        // Off, like every other translucent overlay here: a see-through marking must not punch a hole in
        // the depth buffer for the passes that come after it.
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // A patch lying on a wall is seen from whichever side the player is on, and on a steep slope the
        // conformed vertices can leave a triangle wound either way. Winding is not a property this shape
        // has any business having.
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("decalColor", color);
        shader.setUniform("decalAlpha", alpha);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        vertices.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        vertices.clear();
        glBindVertexArray(0);

        glDepthMask(depthWriteWas);
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (cullWasEnabled) {
            glEnable(GL_CULL_FACE);
        }
    }

    /** Lays the patch out in the surface's tangent plane and drops every vertex onto the ground. */
    private void build(SolidField field, Vector3f centre, float radius, DecalShape shape) {
        vertices.clear();
        vertexCount = 0;

        Vector3f normal = FieldContact.normalAt(field, centre.x, centre.y, centre.z, new Vector3f());
        if (normal.lengthSquared() < 1e-6f) {
            // No surface to lie on. Happens when the aim point is in open air or deep inside solid rock,
            // and drawing a patch on an imagined plane would be worse than drawing nothing.
            return;
        }
        // A frame spanning the tangent plane. The helper axis is chosen away from the normal so the cross
        // product cannot degenerate — the same trap the tube builder's rotation-minimising frames avoid.
        Vector3f helper = Math.abs(normal.y) > 0.9f ? new Vector3f(1f, 0f, 0f) : new Vector3f(0f, 1f, 0f);
        Vector3f u = new Vector3f(helper).cross(normal).normalize();
        Vector3f v = new Vector3f(normal).cross(u).normalize();

        if (shape == DecalShape.SQUARE) {
            buildSquare(field, centre, radius, normal, u, v);
        } else {
            buildCircle(field, centre, radius, normal, u, v);
        }
    }

    private void buildCircle(SolidField field, Vector3f centre, float radius, Vector3f normal,
                             Vector3f u, Vector3f v) {
        // (ring, segment) lattice. Ring 0 is the single centre point, repeated per segment so the whole
        // patch can be emitted as quads without a separate fan case.
        Vector3f[] points = new Vector3f[(RINGS + 1) * SEGMENTS];
        for (int ring = 0; ring <= RINGS; ring++) {
            float r = radius * ring / RINGS;
            for (int seg = 0; seg < SEGMENTS; seg++) {
                double angle = 2.0 * Math.PI * seg / SEGMENTS;
                float s = (float) Math.cos(angle) * r;
                float t = (float) Math.sin(angle) * r;
                points[ring * SEGMENTS + seg] = conform(field, centre, normal, u, v, s, t, radius);
            }
        }
        for (int ring = 0; ring < RINGS; ring++) {
            for (int seg = 0; seg < SEGMENTS; seg++) {
                int next = (seg + 1) % SEGMENTS;
                Vector3f a = points[ring * SEGMENTS + seg];
                Vector3f b = points[ring * SEGMENTS + next];
                Vector3f c = points[(ring + 1) * SEGMENTS + next];
                Vector3f d = points[(ring + 1) * SEGMENTS + seg];
                float inner = (float) ring / RINGS;
                float outer = (float) (ring + 1) / RINGS;
                quad(a, inner, b, inner, c, outer, d, outer);
            }
        }
    }

    private void buildSquare(SolidField field, Vector3f centre, float radius, Vector3f normal,
                             Vector3f u, Vector3f v) {
        Vector3f[] points = new Vector3f[(GRID + 1) * (GRID + 1)];
        float[] edges = new float[(GRID + 1) * (GRID + 1)];
        for (int i = 0; i <= GRID; i++) {
            float s = radius * (2f * i / GRID - 1f);
            for (int j = 0; j <= GRID; j++) {
                float t = radius * (2f * j / GRID - 1f);
                int index = i * (GRID + 1) + j;
                points[index] = conform(field, centre, normal, u, v, s, t, radius);
                // Chebyshev, which is exactly the metric a cube edit measures with — so the rim lands on
                // the square's own boundary rather than on a square drawn to look like it.
                edges[index] = Math.max(Math.abs(s), Math.abs(t)) / radius;
            }
        }
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                int a = i * (GRID + 1) + j;
                int b = a + 1;
                int c = (i + 1) * (GRID + 1) + j + 1;
                int d = (i + 1) * (GRID + 1) + j;
                quad(points[a], edges[a], points[b], edges[b], points[c], edges[c], points[d], edges[d]);
            }
        }
    }

    /**
     * Drops a point in the tangent plane onto the surface along the normal.
     *
     * <p>Returns null where there is no surface within a radius either way, and the caller then omits the
     * triangles that touch it. A patch with a bite out of it is honest — it means the ground genuinely is
     * not where the projection expected, at a cliff edge or over a hole — and is much better than the
     * alternative of stretching the marking onto a surface metres away.
     */
    private Vector3f conform(SolidField field, Vector3f centre, Vector3f normal, Vector3f u, Vector3f v,
                             float s, float t, float search) {
        Vector3f base = new Vector3f(centre).fma(s, u).fma(t, v);
        Vector3f outside = new Vector3f(base).fma(search, normal);
        Vector3f inside = new Vector3f(base).fma(-search, normal);

        if (field.isSolid(outside.x, outside.y, outside.z)) {
            return null;    // solid even a radius out: an overhang, not the surface being aimed at
        }
        if (!field.isSolid(inside.x, inside.y, inside.z)) {
            return null;    // air all the way down: over an edge
        }
        for (int i = 0; i < REFINE_STEPS; i++) {
            Vector3f mid = new Vector3f(outside).add(inside).mul(0.5f);
            if (field.isSolid(mid.x, mid.y, mid.z)) {
                inside.set(mid);
            } else {
                outside.set(mid);
            }
        }
        return outside.fma(LIFT, normal);
    }

    /** Two triangles, skipped entirely if any corner failed to find the surface. */
    private void quad(Vector3f a, float ea, Vector3f b, float eb, Vector3f c, float ec,
                      Vector3f d, float ed) {
        if (a == null || b == null || c == null || d == null) {
            return;
        }
        if (vertexCount + 6 > MAX_VERTICES) {
            return;
        }
        vertex(a, ea);
        vertex(b, eb);
        vertex(c, ec);
        vertex(a, ea);
        vertex(c, ec);
        vertex(d, ed);
    }

    private void vertex(Vector3f p, float edge) {
        vertices.put(p.x).put(p.y).put(p.z).put(edge);
        vertexCount++;
    }

    public void delete() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
    }
}
