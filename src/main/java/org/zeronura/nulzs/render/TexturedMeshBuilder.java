package org.zeronura.nulzs.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Accumulates geometry in {@link TexturedMesh}'s layout — the textured counterpart to
 * {@link ProceduralMeshBuilder}.
 *
 * <p>Deliberately a separate class rather than an addition to {@code ProceduralMeshBuilder}: that one
 * emits the 36-byte position/normal/colour layout that rocks and stumps still use, and adding UVs to it
 * would change every existing prop for the benefit of the one that needs them.
 *
 * <p>Knows nothing about trees. Tubes, quads and vertices — what those are used to build is the game
 * layer's business.
 */
public class TexturedMeshBuilder {

    private static final int FLOATS_PER_VERTEX = 14;

    // Plain arrays rather than lists of boxed numbers: a tree drawn in the map editor is built again as its sliders move.
    private float[] vertexData = new float[FLOATS_PER_VERTEX * 256];
    private int vertexFloats;
    private int[] indices = new int[768];
    private int indexCount;

    public int vertexCount() {
        return vertexFloats / FLOATS_PER_VERTEX;
    }

    public int triangleCount() {
        return indexCount / 3;
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    /** @param tangent surface direction of increasing U, for tangent-space normal mapping */
    public int addVertex(Vector3f position, Vector3f normal, float u, float v, Vector3f tint,
                         Vector3f tangent) {
        int index = vertexCount();
        if (vertexFloats + FLOATS_PER_VERTEX > vertexData.length) {
            vertexData = java.util.Arrays.copyOf(vertexData, vertexData.length * 2);
        }
        float[] out = vertexData;
        int at = vertexFloats;
        out[at] = position.x;
        out[at + 1] = position.y;
        out[at + 2] = position.z;
        out[at + 3] = normal.x;
        out[at + 4] = normal.y;
        out[at + 5] = normal.z;
        out[at + 6] = u;
        out[at + 7] = v;
        out[at + 8] = tint.x;
        out[at + 9] = tint.y;
        out[at + 10] = tint.z;
        out[at + 11] = tangent.x;
        out[at + 12] = tangent.y;
        out[at + 13] = tangent.z;
        vertexFloats = at + FLOATS_PER_VERTEX;
        return index;
    }

    public void addTriangle(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            indices = java.util.Arrays.copyOf(indices, indices.length * 2);
        }
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    /**
     * Copies an imported mesh in under a transform, keeping its own UVs.
     *
     * <p>The one entry point here that takes geometry somebody else authored rather than generating it.
     * Everything else on this builder is parametric — a tube, a quad, a sphere — because everything else
     * here <i>is</i>; this exists so an asset can be merged into a generated mesh and share its draw call,
     * which is what lets an apple's twig ride in the tree's bark buffer and cost nothing extra to draw.
     *
     * <p><b>Normals go through the inverse transpose, not the transform.</b> Any non-uniform scale — and
     * the scale that shrinks a 2.2-unit authored apple to fruit size is applied here — skews a direction
     * differently from a position, so a normal carried through the plain matrix comes out no longer
     * perpendicular to its surface and the shading tilts. Renormalised afterwards, since the inverse
     * transpose does not preserve length.
     *
     * <p><b>The tangent is derived per triangle from the UVs</b> rather than passed in: this geometry has
     * real UVs and a normal map to light through them, and the analytic tangents the parametric builders
     * use (a ring's derivative, a quad's right vector) do not exist for an arbitrary mesh. A degenerate
     * UV triangle falls back to any vector perpendicular to the normal, which is what an unmapped face
     * deserves and never happens on a whole face of real art.
     *
     * @param uvs may be null, in which case the mesh is mapped to a single point of the texture — fine
     *            for geometry that takes a flat colour, wrong for anything with real art
     */
    public void addMesh(float[] positions, float[] normals, float[] uvs, int[] indexData,
                        Matrix4f transform, Vector3f tint) {
        int base = vertexCount();
        Matrix3f normalMatrix = new Matrix3f();
        transform.normal(normalMatrix);

        // Per-vertex tangents accumulated from the triangles that share them, so a smooth surface gets a
        // smooth tangent frame rather than a faceted one.
        Vector3f[] tangents = new Vector3f[positions.length / 3];
        for (int i = 0; i < tangents.length; i++) {
            tangents[i] = new Vector3f();
        }
        if (uvs != null) {
            accumulateTangents(positions, uvs, indexData, tangents);
        }

        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        Vector3f tangent = new Vector3f();
        for (int i = 0; i < positions.length / 3; i++) {
            position.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
                    .mulPosition(transform);
            normal.set(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2])
                    .mul(normalMatrix);
            if (normal.lengthSquared() < 1e-12f) {
                normal.set(0f, 1f, 0f);
            }
            normal.normalize();

            tangent.set(tangents[i]).mul(normalMatrix);
            // Gram-Schmidt against the normal, then a fallback for a vertex no triangle could orient.
            tangent.sub(new Vector3f(normal).mul(normal.dot(tangent)));
            if (tangent.lengthSquared() < 1e-12f) {
                tangent.set(Math.abs(normal.y) > 0.9f ? new Vector3f(1f, 0f, 0f) : new Vector3f(0f, 1f, 0f))
                        .cross(normal);
            }
            tangent.normalize();

            addVertex(position, normal,
                    uvs == null ? 0f : uvs[i * 2], uvs == null ? 0f : uvs[i * 2 + 1],
                    tint, tangent);
        }
        for (int i = 0; i < indexData.length; i += 3) {
            addTriangle(base + indexData[i], base + indexData[i + 1], base + indexData[i + 2]);
        }
    }

    private static void accumulateTangents(float[] positions, float[] uvs, int[] indexData,
                                           Vector3f[] tangents) {
        for (int t = 0; t < indexData.length; t += 3) {
            int a = indexData[t];
            int b = indexData[t + 1];
            int c = indexData[t + 2];

            float e1x = positions[b * 3] - positions[a * 3];
            float e1y = positions[b * 3 + 1] - positions[a * 3 + 1];
            float e1z = positions[b * 3 + 2] - positions[a * 3 + 2];
            float e2x = positions[c * 3] - positions[a * 3];
            float e2y = positions[c * 3 + 1] - positions[a * 3 + 1];
            float e2z = positions[c * 3 + 2] - positions[a * 3 + 2];

            float du1 = uvs[b * 2] - uvs[a * 2];
            float dv1 = uvs[b * 2 + 1] - uvs[a * 2 + 1];
            float du2 = uvs[c * 2] - uvs[a * 2];
            float dv2 = uvs[c * 2 + 1] - uvs[a * 2 + 1];

            float det = du1 * dv2 - du2 * dv1;
            if (Math.abs(det) < 1e-12f) {
                continue;
            }
            float r = 1f / det;
            float tx = (e1x * dv2 - e2x * dv1) * r;
            float ty = (e1y * dv2 - e2y * dv1) * r;
            float tz = (e1z * dv2 - e2z * dv1) * r;
            tangents[a].add(tx, ty, tz);
            tangents[b].add(tx, ty, tz);
            tangents[c].add(tx, ty, tz);
        }
    }

    /**
     * A flat quad spanning {@code right} and {@code up} from a centre, mapped to the full texture.
     *
     * <p>The normal is passed in rather than derived from the quad's own plane. That is the point: a leaf
     * card lit by its true facing reads as a flat card and flips between bright and dark as the light
     * moves across it, whereas a normal borrowed from the surrounding volume makes a mass of cards read
     * as one rounded shape.
     */
    public void addQuad(Vector3f center, Vector3f right, Vector3f up, Vector3f normal, Vector3f tint) {
        Vector3f bottomLeft = new Vector3f(center).sub(right).sub(up);
        Vector3f bottomRight = new Vector3f(center).add(right).sub(up);
        Vector3f topRight = new Vector3f(center).add(right).add(up);
        Vector3f topLeft = new Vector3f(center).sub(right).add(up);

        // The quad's own right vector is exactly the direction U increases in.
        Vector3f tangent = new Vector3f(right).normalize();

        // V is flipped so the texture's top row lands at the quad's top: image rows run downward.
        int i0 = addVertex(bottomLeft, normal, 0f, 1f, tint, tangent);
        int i1 = addVertex(bottomRight, normal, 1f, 1f, tint, tangent);
        int i2 = addVertex(topRight, normal, 1f, 0f, tint, tangent);
        int i3 = addVertex(topLeft, normal, 0f, 0f, tint, tangent);

        addTriangle(i0, i1, i2);
        addTriangle(i0, i2, i3);
    }

    /**
     * A card that a shader may turn to face the camera about its centre: a quad as {@link #addQuad} lays it, with its
     * centre — not its right vector — in the tangent channel, so the vertex shader can find where to turn it about
     * (foliage.vert's {@code billboard}). For cards no normal map is drawn on: their tangent has nothing else to carry.
     * {@code right} and {@code up} must be square to each other and the same length, so the centre is the same distance
     * from every corner.
     */
    public void addCard(Vector3f center, Vector3f right, Vector3f up, Vector3f normal, Vector3f tint) {
        Vector3f centre = new Vector3f(center);
        int i0 = addVertex(new Vector3f(center).sub(right).sub(up), normal, 0f, 1f, tint, centre);
        int i1 = addVertex(new Vector3f(center).add(right).sub(up), normal, 1f, 1f, tint, centre);
        int i2 = addVertex(new Vector3f(center).add(right).add(up), normal, 1f, 0f, tint, centre);
        int i3 = addVertex(new Vector3f(center).sub(right).add(up), normal, 0f, 0f, tint, centre);
        addTriangle(i0, i1, i2);
        addTriangle(i0, i2, i3);
    }

    /**
     * A UV sphere about a centre, wrapped once in each axis.
     *
     * <p>Latitude-longitude rather than a subdivided icosahedron, because this needs a <b>UV layout</b>
     * and an icosphere has no natural one — its charm is even triangles, which is exactly what makes
     * texturing it awkward. The pinch at the poles is the known cost and is unobjectionable on a lump of
     * ore in a rock wall, which is never seen pole-on for long.
     *
     * <p>Vertices are duplicated along the wrap seam (the ring is closed by an extra column at U=1 rather
     * than by reusing the U=0 column), or the last quad would interpolate U backwards from 1 to 0 and
     * smear the whole texture across it.
     *
     * @param segments longitude divisions; rings is half that, which keeps quads roughly square
     */
    public void addSphere(Vector3f center, float radius, int segments, Vector3f tint) {
        int rings = Math.max(2, segments / 2);
        int first = vertexCount();

        for (int ring = 0; ring <= rings; ring++) {
            float v = ring / (float) rings;
            float phi = (float) (v * Math.PI);
            float y = (float) Math.cos(phi);
            float r = (float) Math.sin(phi);
            for (int seg = 0; seg <= segments; seg++) {
                float u = seg / (float) segments;
                float theta = (float) (u * 2.0 * Math.PI);
                float x = (float) (Math.cos(theta) * r);
                float z = (float) (Math.sin(theta) * r);

                // On a sphere the outward normal *is* the unit position, and the tangent is its derivative
                // with respect to longitude — both exact, so there is nothing to approximate per fragment.
                Vector3f normal = new Vector3f(x, y, z);
                Vector3f tangent = new Vector3f((float) -Math.sin(theta), 0f, (float) Math.cos(theta));
                if (tangent.lengthSquared() < 1e-8f) {
                    tangent.set(1f, 0f, 0f);
                }
                Vector3f position = new Vector3f(normal).mul(radius).add(center);
                addVertex(position, normal, u, v, tint, tangent.normalize());
            }
        }

        int stride = segments + 1;
        for (int ring = 0; ring < rings; ring++) {
            for (int seg = 0; seg < segments; seg++) {
                int a = first + ring * stride + seg;
                int b = a + stride;
                // Wound so the front faces point out, as a tube's do: the other way round, culling shows the inside of
                // the far wall and the whole ball goes dark.
                addTriangle(a, a + 1, b);
                addTriangle(a + 1, b + 1, b);
            }
        }
    }

    /**
     * How a tube is swept: its radii, resolution, texture wrapping and end caps.
     *
     * <p>Grouped into a record because the parameter list had grown past the point where a reader could
     * tell which {@code float} was which at a call site.
     *
     * @param taperExponent shape of the taper. 1 is a straight cone; above 1 holds the root radius for
     *                      longer and then thins quickly near the tip, which is how an actual trunk is
     *                      shaped — a linear taper reads as a sharpened pencil
     * @param uRepeat       whole tiles of the texture around the circumference. Whole because the ring
     *                      closes back at U=0, so a fractional repeat leaves a mismatched strip however
     *                      well the art tiles
     * @param capRoot       close the {@code t=0} end; unnecessary when it is buried inside another tube
     */
    public record TubeProfile(float rootRadius, float tipRadius, float taperExponent,
                              int radialSegments, int rings, float textureWorldHeight, int uRepeat,
                              boolean capRoot, boolean capTip) {
    }

    /**
     * Radius of a tapering tube at parameter {@code t}.
     *
     * <p>The single definition of the taper curve: the mesh sweep and anything reasoning about the
     * structure (where a branch attaches, how thick it can be there) have to agree exactly, and two
     * copies of this formula would eventually not.
     */
    public static float taperedRadius(float rootRadius, float tipRadius, float taperExponent, float t) {
        float clamped = Math.clamp(t, 0f, 1f);
        float profile = 1f - (float) Math.pow(clamped, taperExponent);
        return tipRadius + (rootRadius - tipRadius) * profile;
    }

    /**
     * Sweeps a tapering tube along a spline.
     *
     * <h2>Rotation-minimising frames</h2>
     * Each ring's orientation is carried forward from the previous ring by projecting it onto the new
     * ring's plane, rather than rebuilt from {@code cross(tangent, worldUp)}. A world-up frame degenerates
     * as the tangent approaches vertical — which is exactly what a tree trunk does — and flips the ring
     * by up to 180° between two steps, shearing the bark across a single band of the trunk.
     *
     * <h2>Wrapping</h2>
     * U runs 0 → {@code uRepeat} around the circumference and relies on the texture tiling horizontally.
     * {@code uRepeat} <b>must be a whole number</b>: the ring closes by returning to U=0, so a fractional
     * repeat leaves a mismatched strip down one side of the trunk no matter how well the art tiles.
     *
     * <p>V is driven by accumulated <b>arc length</b> divided by {@code textureWorldHeight}, so bark tiles
     * at one physical scale everywhere instead of stretching over long branches and compressing on short
     * ones.
     *
     */
    public void addTube(Spline spline, TubeProfile profile, Vector3f tint) {
        addTube(spline, profile, tint, tint);
    }

    /**
     * Sweeps a tube whose end caps are tinted differently from its walls.
     *
     * <p>A cut face is a different material from the surface it interrupts — sawn wood against bark — and
     * with one texture serving both, the tint is what tells them apart.
     */
    public void addTube(Spline spline, TubeProfile profile, Vector3f tint, Vector3f capTint) {
        int radialSegments = profile.radialSegments();
        int rings = profile.rings();
        int uRepeat = profile.uRepeat();
        float textureWorldHeight = profile.textureWorldHeight();

        if (rings < 1 || radialSegments < 3) {
            throw new IllegalArgumentException("a tube needs at least 1 ring and 3 radial segments");
        }
        if (uRepeat < 1) {
            throw new IllegalArgumentException("uRepeat must be a positive whole number, got " + uRepeat);
        }

        Vector3f reference = initialReference(spline.tangentAt(0f));

        int previousRingStart = -1;
        float arcLength = 0f;
        Vector3f previousCenter = spline.pointAt(0f);

        for (int ring = 0; ring <= rings; ring++) {
            float t = ring / (float) rings;
            Vector3f center = spline.pointAt(t);
            Vector3f tangent = spline.tangentAt(t);

            // Parallel transport: re-orthogonalise the previous frame against the new tangent instead of
            // choosing a fresh one. Small rotations accumulate smoothly; no step can flip the frame.
            reference = orthogonalise(reference, tangent);
            Vector3f binormal = new Vector3f(tangent).cross(reference).normalize();

            arcLength += previousCenter.distance(center);
            previousCenter = center;
            float v = textureWorldHeight > 0f ? arcLength / textureWorldHeight : t;

            float radius = taperedRadius(profile.rootRadius(), profile.tipRadius(),
                    profile.taperExponent(), t);
            int ringStart = vertexCount();

            for (int segment = 0; segment <= radialSegments; segment++) {
                double angle = 2.0 * Math.PI * segment / radialSegments;
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);

                Vector3f outward = new Vector3f(reference).mul(cos).fma(sin, binormal).normalize();
                Vector3f position = new Vector3f(center).fma(radius, outward);

                // Exact tangent: the derivative of the ring with respect to the angle, which is the
                // direction U increases in. No need to approximate it from neighbouring triangles.
                Vector3f ringTangent = new Vector3f(reference).mul(-sin).fma(cos, binormal).normalize();

                float u = uRepeat * segment / (float) radialSegments;
                addVertex(position, outward, u, v, tint, ringTangent);
            }

            if (previousRingStart >= 0) {
                for (int segment = 0; segment < radialSegments; segment++) {
                    int a = previousRingStart + segment;
                    int b = previousRingStart + segment + 1;
                    int c = ringStart + segment;
                    int d = ringStart + segment + 1;
                    // Wound so the front face points OUT of the tube. Reversed, the vertex normals still
                    // point outward and the surface still shades correctly, so the mistake is invisible in
                    // a screenshot — but back-face culling then removes the near wall and you look
                    // straight through the trunk at the inside of its far side.
                    addTriangle(a, b, c);
                    addTriangle(b, d, c);
                }
            }
            previousRingStart = ringStart;
        }

        if (profile.capRoot()) {
            addCap(spline, profile.rootRadius(), radialSegments,
                    initialReference(spline.tangentAt(0f)), 0f, capTint);
        }
        if (profile.capTip()) {
            addCap(spline, profile.tipRadius(), radialSegments, reference, 1f, capTint);
        }
    }

    /**
     * Closes an end of a tube with a triangle fan.
     *
     * <p>Without it a limb is an open pipe: you can see down the hollow inside of every branch tip, and
     * down the trunk itself from any vantage point above it.
     */
    private void addCap(Spline spline, float radius, int radialSegments, Vector3f reference, float t,
                        Vector3f tint) {
        Vector3f center = spline.pointAt(t);
        Vector3f tangent = spline.tangentAt(t);
        boolean isTip = t > 0.5f;
        Vector3f outward = isTip ? new Vector3f(tangent) : new Vector3f(tangent).negate();
        Vector3f binormal = new Vector3f(tangent).cross(reference).normalize();

        int centerIndex = addVertex(center, outward, 0.5f, 0.5f, tint, reference);
        int ringStart = vertexCount();
        for (int segment = 0; segment <= radialSegments; segment++) {
            double angle = 2.0 * Math.PI * segment / radialSegments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            Vector3f radial = new Vector3f(reference).mul(cos).fma(sin, binormal).normalize();
            addVertex(new Vector3f(center).fma(radius, radial), outward,
                    0.5f + 0.5f * cos, 0.5f + 0.5f * sin, tint, reference);
        }

        for (int segment = 0; segment < radialSegments; segment++) {
            // The tip cap faces along +tangent and the root cap along -tangent, so their fans wind
            // opposite ways for both to end up facing outward.
            if (isTip) {
                addTriangle(centerIndex, ringStart + segment, ringStart + segment + 1);
            } else {
                addTriangle(centerIndex, ringStart + segment + 1, ringStart + segment);
            }
        }
    }

    /** Any unit vector perpendicular to the tangent; the axis chosen is arbitrary and never visible. */
    private static Vector3f initialReference(Vector3f tangent) {
        Vector3f candidate = Math.abs(tangent.y) < 0.9f
                ? new Vector3f(0f, 1f, 0f)
                : new Vector3f(1f, 0f, 0f);
        return orthogonalise(candidate, tangent);
    }

    private static Vector3f orthogonalise(Vector3f reference, Vector3f tangent) {
        Vector3f result = new Vector3f(reference).fma(-reference.dot(tangent), tangent);
        if (result.lengthSquared() < 1e-8f) {
            // The frame ended up parallel to the tangent (a near-180° turn in one step). Any
            // perpendicular will do; continuity is already lost at that point regardless.
            return initialReference(tangent);
        }
        return result.normalize();
    }

    /** Snapshots what has been added so far into upload-ready buffers. No GL involved. */
    public TexturedMeshData buildData() {
        ByteBuffer vertexBuffer = ByteBuffer.allocateDirect(vertexFloats * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        vertexBuffer.asFloatBuffer().put(vertexData, 0, vertexFloats);
        vertexBuffer.limit(vertexFloats * Float.BYTES);

        IntBuffer indexBuffer = ByteBuffer.allocateDirect(Math.max(1, indexCount) * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        indexBuffer.put(indices, 0, indexCount);
        indexBuffer.flip();

        return new TexturedMeshData(vertexBuffer, indexBuffer);
    }

    /** Must run on the GL thread. */
    public TexturedMesh build() {
        return buildData().upload();
    }
}
