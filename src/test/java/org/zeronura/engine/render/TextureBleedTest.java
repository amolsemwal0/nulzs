package org.zeronura.engine.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fix for dark halos around cutout foliage. Pure array work on a pixel buffer, so no GL is involved.
 *
 * <p>Why it matters: {@code leaf_maple.png} stores {@code (0,0,0,0)} in its transparent pixels. Those
 * texels are discarded at full resolution, but mipmap generation averages their black into the leaf's
 * edge texels, and the canopy grows a dark fringe as it recedes from the camera.
 */
class TextureBleedTest {

    private static ByteBuffer rgba(int width, int height) {
        return ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
    }

    private static void set(ByteBuffer pixels, int width, int x, int y, int r, int g, int b, int a) {
        int index = (y * width + x) * 4;
        pixels.put(index, (byte) r);
        pixels.put(index + 1, (byte) g);
        pixels.put(index + 2, (byte) b);
        pixels.put(index + 3, (byte) a);
    }

    private static int[] get(ByteBuffer pixels, int width, int x, int y) {
        int index = (y * width + x) * 4;
        return new int[]{
                pixels.get(index) & 0xFF,
                pixels.get(index + 1) & 0xFF,
                pixels.get(index + 2) & 0xFF,
                pixels.get(index + 3) & 0xFF};
    }

    @Test
    void colourSpreadsIntoTransparentPixelsWhileAlphaStaysPut() {
        int size = 5;
        ByteBuffer pixels = rgba(size, size);
        // One opaque green pixel in the middle of a transparent black field.
        set(pixels, size, 2, 2, 40, 200, 60, 255);

        int filled = Texture.bleedRgbIntoTransparent(pixels, size, size);

        assertEquals(size * size - 1, filled, "every transparent pixel should have been reached");

        int[] neighbour = get(pixels, size, 2, 1);
        assertEquals(40, neighbour[0]);
        assertEquals(200, neighbour[1]);
        assertEquals(60, neighbour[2]);
        assertEquals(0, neighbour[3], "alpha must not change: this affects averaging, never visibility");

        int[] corner = get(pixels, size, 0, 0);
        assertTrue(corner[1] > 100, "colour should have spread all the way to the corner, got " + corner[1]);
        assertEquals(0, corner[3]);

        int[] source = get(pixels, size, 2, 2);
        assertEquals(255, source[3], "the opaque pixel must be left alone");
        assertEquals(200, source[1]);
    }

    /** Without this, a mipmapped leaf edge averages toward black — the halo. */
    @Test
    void noTransparentPixelIsLeftBlackNextToAVisibleOne() {
        int size = 8;
        ByteBuffer pixels = rgba(size, size);
        for (int y = 3; y < 5; y++) {
            for (int x = 3; x < 5; x++) {
                set(pixels, size, x, y, 200, 30, 30, 255);
            }
        }

        Texture.bleedRgbIntoTransparent(pixels, size, size);

        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                int[] pixel = get(pixels, size, x, y);
                assertTrue(pixel[0] > 0, "pixel (" + x + "," + y + ") is still black beside a visible one");
            }
        }
    }

    @Test
    void aFullyOpaqueImageIsUntouched() {
        int size = 4;
        ByteBuffer pixels = rgba(size, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                set(pixels, size, x, y, 10, 20, 30, 255);
            }
        }

        assertEquals(0, Texture.bleedRgbIntoTransparent(pixels, size, size));
    }

    /** A fully transparent image has nothing to spread; it must terminate rather than loop. */
    @Test
    void aFullyTransparentImageTerminates() {
        int size = 4;
        ByteBuffer pixels = rgba(size, size);

        assertEquals(0, Texture.bleedRgbIntoTransparent(pixels, size, size));
    }

    /**
     * The frontier walk fills exactly what sweeping the whole image would have.
     *
     * <p>This is the assertion the rewrite rests on, and it is worth having rather than believing: the
     * old form scanned every pixel on every pass, which for a 1536x1024 inventory icon with a 330-pixel
     * transparent margin was about four billion operations and <b>7.8 seconds across the icon set</b>,
     * paid on the first frame that drew the HUD. The frontier form is linear in the image instead. The
     * only way that is a safe trade is if it produces the same pixels, and "looks the same" is not a
     * check — a fill that smeared along the scan order would still produce a perfectly plausible icon.
     *
     * <p>The fixture is a small block of colour marooned in a large transparent field, which is exactly
     * the shape that made the old version slow: the fill has to travel much further than the content is
     * wide.
     */
    @Test
    void theFrontierWalkFillsExactlyWhatAWholeImageSweepWould() {
        int width = 48;
        int height = 40;
        ByteBuffer mine = image(width, height);
        ByteBuffer reference = image(width, height);

        int filled = Texture.bleedRgbIntoTransparent(mine, width, height);
        int referenceFilled = sweepWholeImage(reference, width, height);

        assertEquals(referenceFilled, filled, "a different number of pixels was filled");
        for (int i = 0; i < width * height * 4; i++) {
            assertEquals(reference.get(i), mine.get(i),
                    "byte " + i + " (pixel " + i / 4 + ") differs from the whole-image sweep");
        }
    }

    /** A block of varied colour in the middle of a large transparent field. */
    private static ByteBuffer image(int width, int height) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean opaque = x >= 20 && x < 26 && y >= 17 && y < 22;
                int i = (y * width + x) * 4;
                pixels.put(i, (byte) (opaque ? 30 + x * 7 : 0));
                pixels.put(i + 1, (byte) (opaque ? 200 - y * 5 : 0));
                pixels.put(i + 2, (byte) (opaque ? 90 + ((x * y) % 60) : 0));
                pixels.put(i + 3, (byte) (opaque ? 255 : 0));
            }
        }
        return pixels;
    }

    /**
     * The implementation that was replaced, kept here as the thing to agree with.
     *
     * <p>Deliberately a transcription rather than a tidy rewrite: its value is entirely in being the old
     * behaviour, so anything "improved" about it would make the comparison meaningless.
     */
    private static int sweepWholeImage(ByteBuffer pixels, int width, int height) {
        boolean[] known = new boolean[width * height];
        for (int i = 0; i < known.length; i++) {
            known[i] = (pixels.get(i * 4 + 3) & 0xFF) != 0;
        }
        int filledTotal = 0;
        int[] neighbourX = {-1, 1, 0, 0, -1, 1, -1, 1};
        int[] neighbourY = {0, 0, -1, 1, -1, -1, 1, 1};
        for (int pass = 0; pass < Math.max(width, height); pass++) {
            boolean[] filledThisPass = new boolean[width * height];
            int filled = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (known[index]) {
                        continue;
                    }
                    int r = 0;
                    int g = 0;
                    int b = 0;
                    int count = 0;
                    for (int n = 0; n < neighbourX.length; n++) {
                        int nx = x + neighbourX[n];
                        int ny = y + neighbourY[n];
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                            continue;
                        }
                        int neighbour = ny * width + nx;
                        if (!known[neighbour]) {
                            continue;
                        }
                        r += pixels.get(neighbour * 4) & 0xFF;
                        g += pixels.get(neighbour * 4 + 1) & 0xFF;
                        b += pixels.get(neighbour * 4 + 2) & 0xFF;
                        count++;
                    }
                    if (count == 0) {
                        continue;
                    }
                    pixels.put(index * 4, (byte) (r / count));
                    pixels.put(index * 4 + 1, (byte) (g / count));
                    pixels.put(index * 4 + 2, (byte) (b / count));
                    filledThisPass[index] = true;
                    filled++;
                }
            }
            if (filled == 0) {
                break;
            }
            for (int i = 0; i < known.length; i++) {
                known[i] |= filledThisPass[i];
            }
            filledTotal += filled;
        }
        return filledTotal;
    }
}
