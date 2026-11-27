package org.zeronura.engine.terrain;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.render.MeshData;
import org.zeronura.engine.render.StaticMesh;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

class TerrainTest {

    private static final TerrainColors WHITE = (x, height, z, slope) -> Color.WHITE;

    @Test
    void aChunkIsAGridOfVerticesWithTwoTrianglesPerCell() {
        MeshData data = Terrain.buildChunkMesh(HeightFunction.flat(2f), WHITE, 0f, 0f, 4, 1f);
        assertEquals(25 * StaticMesh.STRIDE, data.vertexData().remaining());
        assertEquals(4 * 4 * 6, data.indexData().remaining());
    }

    @Test
    void flatGroundFacesStraightUp() {
        MeshData data = Terrain.buildChunkMesh(HeightFunction.flat(2f), WHITE, 0f, 0f, 4, 1f);
        FloatBuffer vertices = data.vertexData().asFloatBuffer();
        for (int v = 0; v < 25; v++) {
            assertEquals(2f, vertices.get(v * 9 + 1), 1e-5f);
            assertEquals(0f, vertices.get(v * 9 + 3), 1e-5f);
            assertEquals(1f, vertices.get(v * 9 + 4), 1e-5f);
        }
    }

    @Test
    void everyTriangleFacesUpEvenOnASlope() {
        MeshData data = Terrain.buildChunkMesh((x, z) -> x * 0.7f - z * 0.3f, WHITE, -8f, 3f, 6, 2f);
        FloatBuffer v = data.vertexData().asFloatBuffer();
        IntBuffer indices = data.indexData();
        for (int t = 0; t < indices.remaining() / 3; t++) {
            int a = indices.get(t * 3) * 9;
            int b = indices.get(t * 3 + 1) * 9;
            int c = indices.get(t * 3 + 2) * 9;
            float e1x = v.get(b) - v.get(a);
            float e1z = v.get(b + 2) - v.get(a + 2);
            float e2x = v.get(c) - v.get(a);
            float e2z = v.get(c + 2) - v.get(a + 2);
            float crossY = e1z * e2x - e1x * e2z;
            assertTrue(crossY > 0f, "triangle " + t + " faces down");
        }
    }

    @Test
    void theWalkableSurfaceMatchesTheRenderedTriangles() {
        HeightFunction bowl = (x, z) -> x * x * 0.1f + z;
        try (Terrain terrain = new Terrain(bowl, WHITE, new Terrain.Settings(8, 2f, 1, 1, Float.NaN))) {
            assertEquals(bowl.heightAt(4f, 6f), terrain.heightAt(4f, 6f), 1e-4f, "exact at grid points");
            assertEquals(8.6f, terrain.heightAt(5f, 6f), 1e-4f, "linear along a cell edge, not the curve");
            assertTrue(terrain.isSolid(5f, 8.5f, 6f));
            assertFalse(terrain.isSolid(5f, 8.7f, 6f));
        }
    }

    @Test
    void noiseIsRepeatableBoundedAndSeedDependent() {
        Noise a = new Noise(1);
        Noise b = new Noise(1);
        Noise c = new Noise(2);
        boolean anyDifferent = false;
        for (int i = 0; i < 2000; i++) {
            float x = i * 0.137f - 50f;
            float y = i * 0.071f + 13f;
            float value = a.perlin(x, y);
            assertEquals(value, b.perlin(x, y));
            assertTrue(value >= -1f && value <= 1f);
            float ridge = a.ridged(x, y, 4, 2f, 0.5f);
            assertTrue(ridge >= 0f && ridge <= 1f);
            anyDifferent |= value != c.perlin(x, y);
        }
        assertTrue(anyDifferent);
    }

    @Test
    void perlinNoiseIsZeroAtLatticePoints() {
        Noise noise = new Noise(99);
        assertEquals(0f, noise.perlin(3f, -7f), 1e-6f);
    }
}
