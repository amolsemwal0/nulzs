package org.zeronura.nulzs.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The painted shapes: each is really there, and none touches its neighbours. */
class ParticleAtlasTest {

    private static int alpha(byte[] pixels, int x, int y) {
        return pixels[(y * ParticleAtlas.WIDTH + x) * 4 + 3] & 0xFF;
    }

    private static int cellX(ParticleShape shape) {
        return shape.ordinal() % ParticleAtlas.COLUMNS * ParticleAtlas.CELL;
    }

    private static int cellY(ParticleShape shape) {
        return shape.ordinal() / ParticleAtlas.COLUMNS * ParticleAtlas.CELL;
    }

    @Test
    void everyShapeFitsInTheTexture() {
        assertTrue(ParticleShape.values().length <= ParticleAtlas.COLUMNS * ParticleAtlas.ROWS);
        assertEquals(ParticleAtlas.WIDTH * ParticleAtlas.HEIGHT * 4, ParticleAtlas.bake().length);
    }

    @Test
    void everyShapeIsPaintedAndLeavesItsBorderClear() {
        byte[] pixels = ParticleAtlas.bake();
        int cell = ParticleAtlas.CELL;
        for (ParticleShape shape : ParticleShape.values()) {
            int covered = 0;
            for (int j = 0; j < cell; j++) {
                for (int i = 0; i < cell; i++) {
                    int a = alpha(pixels, cellX(shape) + i, cellY(shape) + j);
                    if (a > 128) {
                        covered++;
                    }
                    if (i == 0 || j == 0 || i == cell - 1 || j == cell - 1) {
                        assertEquals(0, a, shape + " touches its border at " + i + "," + j);
                    }
                }
            }
            assertTrue(covered > cell * cell * 0.01f, shape + " is barely there: " + covered);
        }
    }

    @Test
    void theDotIsPlainWhiteSoATintedDotLooksAsItDid() {
        byte[] pixels = ParticleAtlas.bake();
        int centre = ParticleAtlas.CELL / 2;
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(255, pixels[(centre * ParticleAtlas.WIDTH + centre) * 4 + channel] & 0xFF);
        }
        assertTrue(alpha(pixels, centre, centre) > 240);
    }

    @Test
    void texelBoundsStayInsideTheirCell() {
        for (ParticleShape shape : ParticleShape.values()) {
            float left = (float) cellX(shape) / ParticleAtlas.WIDTH;
            float right = (float) (cellX(shape) + ParticleAtlas.CELL) / ParticleAtlas.WIDTH;
            assertTrue(ParticleAtlas.u0(shape) > left && ParticleAtlas.u1(shape) < right);
        }
    }
}
