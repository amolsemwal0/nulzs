package org.zeronura.engine.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The transparency an off-screen capture works out from a layer drawn over black and over white. */
class OffscreenCaptureTest {

    /** One pixel of a colour at an alpha, laid over a grey level as blending for the screen lays it. */
    private static byte[] over(int r, int g, int b, float alpha, int under) {
        return new byte[]{
                (byte) Math.round(r * alpha + under * (1f - alpha)),
                (byte) Math.round(g * alpha + under * (1f - alpha)),
                (byte) Math.round(b * alpha + under * (1f - alpha)),
                (byte) 255};
    }

    private static int[] unmatte(int r, int g, int b, float alpha) {
        byte[] picture = OffscreenCapture.unmatte(over(r, g, b, alpha, 0), over(r, g, b, alpha, 255));
        return new int[]{picture[0] & 0xFF, picture[1] & 0xFF, picture[2] & 0xFF, picture[3] & 0xFF};
    }

    @Test
    void anOpaquePixelKeepsItsColour() {
        assertArrayEquals(new int[]{200, 120, 40, 255}, unmatte(200, 120, 40, 1f));
    }

    @Test
    void nothingDrawnIsClear() {
        assertArrayEquals(new int[]{0, 0, 0, 0}, unmatte(200, 120, 40, 0f));
    }

    @Test
    void aSoftShadowComesOutAsAFaintBlack() {
        // The creatures' shadows: black at 22%.
        int[] shadow = unmatte(0, 0, 0, 0.22f);
        assertEquals(Math.round(0.22f * 255), shadow[3], 1);
        assertArrayEquals(new int[]{0, 0, 0}, new int[]{shadow[0], shadow[1], shadow[2]});
    }

    @Test
    void aHalfSeenColourIsRecoveredWithinRounding() {
        int[] veil = unmatte(240, 60, 90, 0.5f);
        assertEquals(128, veil[3], 1);
        assertEquals(240, veil[0], 3);
        assertEquals(60, veil[1], 3);
        assertEquals(90, veil[2], 3);
    }

    @Test
    void layingTheResultOverAnyBackgroundMatchesDrawingOverIt() {
        // What matters to a compositor: over a mid grey the picture must look as the layer drawn there would.
        int[][] colours = {{255, 255, 255}, {30, 200, 90}, {10, 10, 10}, {250, 140, 0}};
        float[] alphas = {0.1f, 0.35f, 0.62f, 0.9f};
        for (int[] c : colours) {
            for (float a : alphas) {
                int[] picture = unmatte(c[0], c[1], c[2], a);
                byte[] expected = over(c[0], c[1], c[2], a, 128);
                float alpha = picture[3] / 255f;
                for (int k = 0; k < 3; k++) {
                    int laid = Math.round(picture[k] * alpha + 128 * (1f - alpha));
                    assertEquals(expected[k] & 0xFF, laid, 2, "channel " + k + " of " + java.util.Arrays.toString(c) + " at " + a);
                }
            }
        }
    }
}
