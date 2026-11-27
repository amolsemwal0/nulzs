package org.zeronura.engine.nodes.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeBakerTest {

    private static byte[] pixels(int... rgba) {
        byte[] out = new byte[rgba.length];
        for (int i = 0; i < rgba.length; i++) {
            out[i] = (byte) rgba[i];
        }
        return out;
    }

    @Test
    void colourIsAveragedOverEachBlock() {
        // A 2x2 picture down to one pixel: the mean of the four.
        byte[] picture = pixels(0, 0, 0, 255, 100, 200, 40, 255, 200, 100, 0, 255, 100, 100, 160, 255);
        byte[] shrunk = NodeBaker.shrink(picture, 2, 2, false);
        assertEquals(100, shrunk[0] & 0xFF);
        assertEquals(100, shrunk[1] & 0xFF);
        assertEquals(50, shrunk[2] & 0xFF);
        assertEquals(255, shrunk[3] & 0xFF);
    }

    @Test
    void normalsAreMadeUnitAgainAfterAveraging() {
        // Two normals leaning opposite ways along x average to a short vector straight up; it comes out full length.
        int[] left = {(int) ((-0.6f + 1f) * 127.5f), 128, (int) ((0.8f + 1f) * 127.5f), 255};
        int[] right = {(int) ((0.6f + 1f) * 127.5f), 128, (int) ((0.8f + 1f) * 127.5f), 255};
        byte[] picture = pixels(left[0], left[1], left[2], left[3], right[0], right[1], right[2], right[3],
                left[0], left[1], left[2], left[3], right[0], right[1], right[2], right[3]);
        byte[] shrunk = NodeBaker.shrink(picture, 2, 2, true);
        float nx = (shrunk[0] & 0xFF) / 127.5f - 1f;
        float nz = (shrunk[2] & 0xFF) / 127.5f - 1f;
        assertEquals(0f, nx, 0.02f);
        assertEquals(1f, nz, 0.02f);
    }
}
