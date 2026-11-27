package org.zeronura.nulzs.graphics3d;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.render.MeshData;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every shape must wind its triangles outward, or back-face culling deletes the visible side. All the
 * shapes are convex, so "outward" means facing away from any point inside.
 */
class MeshBuilder3DTest {

    @Test
    void aBoxHasSixFlatFaces() {
        MeshBuilder3D builder = new MeshBuilder3D().box(0f, 0f, 0f, 2f, 3f, 4f, Color.RED);
        assertEquals(24, builder.vertexCount());
        assertEquals(12, builder.triangleCount());
        assertFacesOutward(builder.buildData(), new Vector3f(0f, 0f, 0f));
    }

    @Test
    void roundShapesFaceOutward() {
        assertFacesOutward(new MeshBuilder3D().sphere(1f, 2f, 3f, 1.5f, 8, 12, Color.RED).buildData(),
                new Vector3f(1f, 2f, 3f));
        assertFacesOutward(new MeshBuilder3D().cylinder(0f, 0f, 0f, 1f, 3f, 12, Color.RED, true).buildData(),
                new Vector3f(0f, 1.5f, 0f));
        assertFacesOutward(new MeshBuilder3D().cone(0f, 0f, 0f, 1f, 3f, 12, Color.RED).buildData(),
                new Vector3f(0f, 0.5f, 0f));
        assertFacesOutward(new MeshBuilder3D().capsule(0f, 0f, 0f, 0.4f, 1.8f, 16, Color.RED).buildData(),
                new Vector3f(0f, 0.9f, 0f));
    }

    @Test
    void aPlaneFacesUp() {
        MeshData data = new MeshBuilder3D().plane(0f, 0f, 0f, 4f, 4f, Color.GREEN).buildData();
        assertFacesOutward(data, new Vector3f(0f, -1f, 0f));
    }

    private static void assertFacesOutward(MeshData data, Vector3f inside) {
        FloatBuffer v = data.vertexData().asFloatBuffer();
        IntBuffer indices = data.indexData();
        int triangles = indices.remaining() / 3;
        assertTrue(triangles > 0);
        for (int t = 0; t < triangles; t++) {
            Vector3f a = position(v, indices.get(t * 3));
            Vector3f b = position(v, indices.get(t * 3 + 1));
            Vector3f c = position(v, indices.get(t * 3 + 2));
            Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            if (normal.lengthSquared() < 1e-10f) {
                continue; // degenerate sliver at a pole
            }
            Vector3f centroid = new Vector3f(a).add(b).add(c).div(3f);
            assertTrue(normal.dot(centroid.sub(inside)) > 0f, "triangle " + t + " faces inward");
        }
    }

    private static Vector3f position(FloatBuffer vertices, int index) {
        return new Vector3f(vertices.get(index * 9), vertices.get(index * 9 + 1), vertices.get(index * 9 + 2));
    }
}
