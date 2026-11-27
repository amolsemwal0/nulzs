package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry only — building buffers touches no GL, so all of this runs without a window.
 */
class TexturedMeshBuilderTest {

    private static final int FLOATS_PER_VERTEX = 14;

    /** Reads a vertex back out of the packed 56-byte layout. */
    private record Vertex(Vector3f position, Vector3f normal, float u, float v, Vector3f tint,
                          Vector3f tangent) {
    }

    private static List<Vertex> vertices(TexturedMeshData data) {
        // duplicate() resets byte order to big-endian regardless of the source buffer, so it has to be
        // set again here. The buffer handed to GL is the original, in native order, which is what
        // glBufferData expects.
        ByteBuffer buffer = data.vertexData().duplicate().order(ByteOrder.nativeOrder());
        List<Vertex> result = new ArrayList<>();
        while (buffer.remaining() >= TexturedMesh.STRIDE) {
            float[] f = new float[FLOATS_PER_VERTEX];
            for (int i = 0; i < FLOATS_PER_VERTEX; i++) {
                f[i] = buffer.getFloat();
            }
            result.add(new Vertex(new Vector3f(f[0], f[1], f[2]), new Vector3f(f[3], f[4], f[5]),
                    f[6], f[7], new Vector3f(f[8], f[9], f[10]), new Vector3f(f[11], f[12], f[13])));
        }
        return result;
    }

    private static Spline straightUp(float height) {
        return Spline.of(new Vector3f(0f, 0f, 0f), new Vector3f(0f, height * 0.5f, 0f),
                new Vector3f(0f, height, 0f));
    }

    /** The taper curve is the single definition both the sweep and the tree generator read. */
    @Test
    void theTaperCurveHoldsTheRootRadiusLongerAsTheExponentRises() {
        float linearMidpoint = TexturedMeshBuilder.taperedRadius(1f, 0f, 1f, 0.5f);
        float curvedMidpoint = TexturedMeshBuilder.taperedRadius(1f, 0f, 2.6f, 0.5f);

        assertEquals(0.5f, linearMidpoint, 1e-4f, "exponent 1 should be a straight cone");
        assertTrue(curvedMidpoint > linearMidpoint,
                "a higher exponent should still be near the root radius at the midpoint, got "
                        + curvedMidpoint);

        for (float exponent : new float[]{1f, 1.8f, 2.6f}) {
            assertEquals(1f, TexturedMeshBuilder.taperedRadius(1f, 0.2f, exponent, 0f), 1e-4f);
            assertEquals(0.2f, TexturedMeshBuilder.taperedRadius(1f, 0.2f, exponent, 1f), 1e-4f);
        }
    }

    @Test
    void aTubeTapersFromRootToTip() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addTube(straightUp(4f), new TexturedMeshBuilder.TubeProfile(
                0.5f, 0.1f, 1f, 6, 8, 1.6f, 1, false, false),
                new Vector3f(1f, 1f, 1f));

        List<Vertex> verts = vertices(builder.buildData());
        float rootRadius = 0f;
        float tipRadius = 0f;
        for (Vertex vertex : verts) {
            float radius = (float) Math.hypot(vertex.position().x, vertex.position().z);
            if (vertex.position().y < 0.01f) {
                rootRadius = Math.max(rootRadius, radius);
            }
            if (vertex.position().y > 3.99f) {
                tipRadius = Math.max(tipRadius, radius);
            }
        }

        assertEquals(0.5f, rootRadius, 0.01f, "root should be at the root radius");
        assertEquals(0.1f, tipRadius, 0.01f, "tip should be at the tip radius");
        assertTrue(tipRadius < rootRadius, "a tube must never get thicker toward its tip");
    }

    /**
     * U wraps a whole number of tiles around the circumference.
     *
     * <p>The ring closes by returning to U=0, so the tile count has to be an integer: a fractional repeat
     * leaves a mismatched strip down one side of the trunk however well the texture itself tiles. (An
     * earlier version of the art did not tile horizontally at all, and U was mirrored 0→1→0 to hide that;
     * the updated {@code tree_oak.png} tiles, so it wraps plainly.)
     */
    @Test
    void tubeUCoordinatesWrapAWholeNumberOfTiles() {
        for (int uRepeat : new int[]{1, 2, 3}) {
            TexturedMeshBuilder builder = new TexturedMeshBuilder();
            builder.addTube(straightUp(2f), new TexturedMeshBuilder.TubeProfile(
                0.3f, 0.3f, 1f, 8, 1, 1.6f, uRepeat, false, false),
                new Vector3f(1f, 1f, 1f));

            List<Vertex> ring = vertices(builder.buildData()).subList(0, 9);

            assertEquals(0f, ring.getFirst().u(), 1e-5f, "the ring should start at U=0");
            assertEquals(uRepeat, ring.getLast().u(), 1e-5f,
                    "and close on a whole tile boundary, so the seam matches");
            assertEquals(uRepeat * 0.5f, ring.get(4).u(), 1e-5f, "half way round is half the tiles");
        }
    }

    /** Tangents must be usable as a TBN basis: unit length and perpendicular to the surface normal. */
    @Test
    void tubeTangentsAreUnitLengthAndPerpendicularToTheNormal() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addTube(straightUp(3f), new TexturedMeshBuilder.TubeProfile(
                0.4f, 0.2f, 1f, 8, 6, 1.2f, 2, false, false),
                new Vector3f(1f, 1f, 1f));

        for (Vertex vertex : vertices(builder.buildData())) {
            assertEquals(1f, vertex.tangent().length(), 1e-4f, "tangent is not normalised");
            assertEquals(0f, vertex.tangent().dot(vertex.normal()), 1e-4f,
                    "tangent is not perpendicular to the normal, so the TBN frame would be skewed");
        }
    }

    /**
     * The frame-flip case. A tube whose tangent passes through vertical would, with a fixed world-up
     * reference frame, spin its ring by up to 180° between two steps and shear the bark across one band.
     * Parallel transport keeps consecutive rings aligned; this measures that alignment directly.
     */
    @Test
    void ringsStayAlignedThroughAVerticalTangent() {
        // An S-curve that leans one way, passes through vertical, and leans back the other.
        Spline spline = Spline.of(
                new Vector3f(-1f, 0f, 0f),
                new Vector3f(-0.3f, 1f, 0f),
                new Vector3f(0f, 2f, 0f),
                new Vector3f(0.3f, 3f, 0f),
                new Vector3f(1f, 4f, 0f));

        int radialSegments = 8;
        int rings = 16;
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addTube(spline, new TexturedMeshBuilder.TubeProfile(
                0.3f, 0.3f, 1f, radialSegments, rings, 1.6f, 1, false, false),
                new Vector3f(1f, 1f, 1f));

        List<Vertex> verts = vertices(builder.buildData());
        int perRing = radialSegments + 1;

        for (int ring = 1; ring <= rings; ring++) {
            Vector3f previousSpoke = spokeOf(verts, (ring - 1) * perRing);
            Vector3f currentSpoke = spokeOf(verts, ring * perRing);
            float alignment = previousSpoke.dot(currentSpoke);

            // Consecutive rings differ only by the curve's own bend. A flipped frame would show up as a
            // dot product near -1; anything below zero is already a quarter-turn of unwanted twist.
            assertTrue(alignment > 0.8f,
                    "ring " + ring + " twisted relative to ring " + (ring - 1) + ", alignment=" + alignment);
        }
    }

    /**
     * Triangle winding, which decides which side of the surface gets culled.
     *
     * <p>This shipped inverted and was invisible in every screenshot: the <i>vertex</i> normals still
     * pointed outward, so the bark shaded correctly, but back-face culling then removed the near wall of
     * every trunk and left the player looking through it at the inside of the far wall — which also made
     * the texture appear to counter-rotate as the camera orbited, since the visible surface was the
     * opposite side of the cylinder. Geometric winding and vertex normals have to be checked separately,
     * because agreeing with each other is exactly what they failed to do.
     */
    @Test
    void tubeTrianglesAreWoundSoTheirFrontFacesPointOutward() {
        int radialSegments = 8;
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addTube(straightUp(4f), new TexturedMeshBuilder.TubeProfile(
                0.5f, 0.5f, 1f, radialSegments, 4, 1.6f, 1, false, false),
                new Vector3f(1f, 1f, 1f));

        TexturedMeshData data = builder.buildData();
        List<Vertex> verts = vertices(data);
        IntBuffer indices = data.indexData().duplicate();

        int inward = 0;
        int total = 0;
        while (indices.remaining() >= 3) {
            Vector3f a = verts.get(indices.get()).position();
            Vector3f b = verts.get(indices.get()).position();
            Vector3f c = verts.get(indices.get()).position();

            Vector3f geometric = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).normalize();
            Vector3f radial = new Vector3f(a.x, 0f, a.z).normalize();
            if (geometric.dot(radial) <= 0f) {
                inward++;
            }
            total++;
        }

        assertTrue(total > 0, "no triangles were emitted");
        assertEquals(0, inward, inward + " of " + total + " tube triangles face inward");
    }

    /** Vertex normals must agree with the winding, or lighting and silhouette disagree. */
    @Test
    void tubeVertexNormalsPointAwayFromTheAxis() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addTube(straightUp(4f), new TexturedMeshBuilder.TubeProfile(
                0.5f, 0.5f, 1f, 8, 4, 1.6f, 1, false, false),
                new Vector3f(1f, 1f, 1f));

        for (Vertex vertex : vertices(builder.buildData())) {
            Vector3f radial = new Vector3f(vertex.position().x, 0f, vertex.position().z).normalize();
            assertTrue(vertex.normal().dot(radial) > 0.99f,
                    "a wall normal points inward: " + vertex.normal());
        }
    }

    /** An uncapped limb is an open pipe you can see down; caps close both ends. */
    @Test
    void cappingClosesTheEndsOfATube() {
        TexturedMeshBuilder open = new TexturedMeshBuilder();
        open.addTube(straightUp(3f), new TexturedMeshBuilder.TubeProfile(
                0.4f, 0.2f, 1f, 8, 4, 1.6f, 1, false, false),
                new Vector3f(1f, 1f, 1f));

        TexturedMeshBuilder capped = new TexturedMeshBuilder();
        capped.addTube(straightUp(3f), new TexturedMeshBuilder.TubeProfile(
                0.4f, 0.2f, 1f, 8, 4, 1.6f, 1, true, true),
                new Vector3f(1f, 1f, 1f));

        assertEquals(open.triangleCount() + 16, capped.triangleCount(),
                "each cap should add one triangle per radial segment");
    }

    /** Caps face out of the tube, not into it — the same trap as the walls. */
    @Test
    void capTrianglesFaceOutOfTheTube() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addTube(straightUp(4f), new TexturedMeshBuilder.TubeProfile(
                0.5f, 0.5f, 1f, 8, 2, 1.6f, 1, true, true),
                new Vector3f(1f, 1f, 1f));

        TexturedMeshData data = builder.buildData();
        List<Vertex> verts = vertices(data);
        IntBuffer indices = data.indexData().duplicate();

        while (indices.remaining() >= 3) {
            Vector3f a = verts.get(indices.get()).position();
            Vector3f b = verts.get(indices.get()).position();
            Vector3f c = verts.get(indices.get()).position();

            // Only look at the flat end triangles: every vertex of one shares a y with the tube's end.
            boolean atRoot = a.y < 0.01f && b.y < 0.01f && c.y < 0.01f;
            boolean atTip = a.y > 3.99f && b.y > 3.99f && c.y > 3.99f;
            if (!atRoot && !atTip) {
                continue;
            }
            Vector3f geometric = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).normalize();
            float expected = atTip ? 1f : -1f;
            assertEquals(expected, geometric.y, 0.01f,
                    (atTip ? "tip" : "root") + " cap faces the wrong way");
        }
    }

    /** The direction from a ring's centre to its first vertex — the ring's roll about the curve. */
    private static Vector3f spokeOf(List<Vertex> verts, int ringStart) {
        return new Vector3f(verts.get(ringStart).normal()).normalize();
    }

    @Test
    void aQuadIsTwoTrianglesCoveringTheWholeTexture() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addQuad(new Vector3f(0f, 1f, 0f), new Vector3f(0.5f, 0f, 0f), new Vector3f(0f, 0.5f, 0f),
                new Vector3f(0f, 0f, 1f), new Vector3f(1f, 1f, 1f));

        TexturedMeshData data = builder.buildData();
        assertEquals(4, data.vertexCount());
        assertEquals(2, data.triangleCount());

        List<Vertex> verts = vertices(data);
        assertEquals(0f, verts.stream().map(Vertex::u).min(Float::compare).orElseThrow(), 1e-5f);
        assertEquals(1f, verts.stream().map(Vertex::u).max(Float::compare).orElseThrow(), 1e-5f);
        assertEquals(0f, verts.stream().map(Vertex::v).min(Float::compare).orElseThrow(), 1e-5f);
        assertEquals(1f, verts.stream().map(Vertex::v).max(Float::compare).orElseThrow(), 1e-5f);
    }

    /** All four corners share the normal they were given, rather than each using the quad's own plane. */
    @Test
    void quadNormalsComeFromTheCallerNotTheQuadPlane() {
        Vector3f canopyOutward = new Vector3f(0.6f, 0.8f, 0f).normalize();
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        builder.addQuad(new Vector3f(), new Vector3f(0.5f, 0f, 0f), new Vector3f(0f, 0.5f, 0f),
                canopyOutward, new Vector3f(1f, 1f, 1f));

        for (Vertex vertex : vertices(builder.buildData())) {
            assertEquals(0f, vertex.normal().distance(canopyOutward), 1e-5f);
            assertEquals(1f, vertex.tangent().length(), 1e-4f, "a quad's tangent is its own right vector");
        }
    }

    @Test
    void degenerateTubeParametersAreRejected() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        Spline spline = straightUp(1f);
        Vector3f tint = new Vector3f(1f, 1f, 1f);

        assertThrows(IllegalArgumentException.class,
                () -> builder.addTube(spline, new TexturedMeshBuilder.TubeProfile(
                        1f, 1f, 1f, 2, 4, 1f, 1, false, false), tint));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addTube(spline, new TexturedMeshBuilder.TubeProfile(
                        1f, 1f, 1f, 6, 0, 1f, 1, false, false), tint));
        // A fractional repeat would leave a mismatched strip down one side, so it is rejected outright.
        assertThrows(IllegalArgumentException.class,
                () -> builder.addTube(spline, new TexturedMeshBuilder.TubeProfile(
                        1f, 1f, 1f, 6, 4, 1f, 0, false, false), tint));
    }

    /** A card the anime leaf shader turns to the camera: the tangent carries its centre, every corner as far from it. */
    @Test
    void aCardCarriesItsCentreInTheTangent() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        Vector3f centre = new Vector3f(1f, 5f, -2f);
        builder.addCard(centre, new Vector3f(0.6f, 0f, 0f), new Vector3f(0f, 0.6f, 0f), new Vector3f(0f, 0f, 1f),
                new Vector3f(1f, 1f, 1f));
        List<Vertex> verts = vertices(builder.buildData());
        assertEquals(4, verts.size());
        for (Vertex vertex : verts) {
            assertEquals(0f, vertex.tangent().distance(centre), 1e-6f, "the centre, at every corner");
            assertEquals(0.6f * (float) Math.sqrt(2), vertex.position().distance(centre), 1e-5f,
                    "half the diagonal, which foliage.vert reads the card's size from");
        }
        // As addQuad lays its corners: v = 1 at the bottom, u = 1 on the right.
        Vertex bottomLeft = verts.get(0);
        assertEquals(0f, bottomLeft.u(), 0f);
        assertEquals(1f, bottomLeft.v(), 0f);
        assertTrue(bottomLeft.position().x < centre.x && bottomLeft.position().y < centre.y);
    }
}
