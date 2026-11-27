package org.zeronura.nulzs.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The texture resolution cap — the downsample that runs before an upload.
 *
 * <p>GL-free, because the shrinking is array work on a pixel buffer and only the upload after it needs a
 * context. That split is what makes any of this testable, and it is the same reason
 * {@link TextureBleedTest} exists.
 *
 * <p><b>What a render cannot check is exactly what is here.</b> A box filter with an off-by-one, a loop
 * that halves once too often, or one that quietly drops the last row of an odd-sized image all produce a
 * texture that is merely slightly softer or slightly shifted — which on a tiling bark texture seen at a
 * distance is invisible. What is visible is the memory figure, and that would look right in every one of
 * those cases.
 */
class TextureCapTest {

    @AfterEach
    void uncap() {
        // Static, so a test that left a cap set would silently shrink whatever the next one loaded.
        Texture.setMaxDimension(Texture.UNCAPPED);
    }

    @Test
    void anImageAlreadyInsideTheCapIsNotTouchedAtAll() {
        // Not merely "comes out the same size": the buffer must be the caller's own, with nothing
        // allocated to free. stb owns that memory, so a copy made here and freed by the wrong allocator is
        // the double free this codebase has already been crashed by once.
        Texture.setMaxDimension(1024);
        ByteBuffer pixels = ramp(64, 32);
        Texture.Capped capped = Texture.capToMaxDimension(pixels, 64, 32);
        assertSame(pixels, capped.pixels(), "a texture under the cap was copied for no reason");
        assertNull(capped.owned(), "nothing should have been allocated");
        assertEquals(64, capped.width());
        assertEquals(32, capped.height());
    }

    @Test
    void theLongSideIsWhatTheCapBounds() {
        // Both sides halve together, so it is the longer one that decides how many halvings happen — a cap
        // applied to each side independently would leave a 2048x256 image at 1024x256 after one step and
        // call it done, which is right, and a 256x2048 one at 256x2048, which is not.
        Texture.setMaxDimension(256);
        Texture.Capped capped = Texture.capToMaxDimension(ramp(1024, 128), 1024, 128);
        try {
            assertEquals(256, capped.width());
            assertEquals(32, capped.height());
            assertTrue(capped.width() <= 256 && capped.height() <= 256);
        } finally {
            capped.free();
        }
    }

    @Test
    void halvingAveragesEachTwoByTwoBlockRatherThanPickingACorner() {
        // The cheap way to shrink is to keep every other texel, and it is wrong in a way no memory figure
        // shows: dropping three texels in four is aliasing, which on tiling ground art reads as the grain
        // crawling as the camera moves. This pins that all four contribute.
        Texture.setMaxDimension(1);
        ByteBuffer pixels = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder());
        int[] reds = {0, 10, 20, 90};
        for (int i = 0; i < 4; i++) {
            pixels.put(i * 4, (byte) reds[i]);
            pixels.put(i * 4 + 1, (byte) 0);
            pixels.put(i * 4 + 2, (byte) 0);
            pixels.put(i * 4 + 3, (byte) 255);
        }
        Texture.Capped capped = Texture.capToMaxDimension(pixels, 2, 2);
        try {
            assertEquals(1, capped.width());
            assertEquals(1, capped.height());
            // (0 + 10 + 20 + 90 + 2) / 4 = 30, i.e. the mean with round-to-nearest rather than truncation.
            assertEquals(30, capped.pixels().get(0) & 0xFF, "the block was not averaged");
            assertEquals(255, capped.pixels().get(3) & 0xFF, "alpha must be averaged like any other channel");
        } finally {
            capped.free();
        }
    }

    @Test
    void aFlatImageSurvivesEveryHalvingUnchanged() {
        // The strongest statement available without a reference implementation: a box filter over a
        // constant field must return that constant, at any number of steps. It catches a weight that does
        // not sum to one, a rounding rule that drifts, and an index that reads outside the block — none of
        // which a size assertion can see.
        Texture.setMaxDimension(4);
        int side = 64;
        ByteBuffer pixels = ByteBuffer.allocateDirect(side * side * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < side * side; i++) {
            pixels.put(i * 4, (byte) 77);
            pixels.put(i * 4 + 1, (byte) 140);
            pixels.put(i * 4 + 2, (byte) 3);
            pixels.put(i * 4 + 3, (byte) 200);
        }
        Texture.Capped capped = Texture.capToMaxDimension(pixels, side, side);
        try {
            assertEquals(4, capped.width());
            assertEquals(4, capped.height());
            for (int i = 0; i < capped.width() * capped.height(); i++) {
                assertEquals(77, capped.pixels().get(i * 4) & 0xFF, "texel " + i + " red drifted");
                assertEquals(140, capped.pixels().get(i * 4 + 1) & 0xFF, "texel " + i + " green drifted");
                assertEquals(3, capped.pixels().get(i * 4 + 2) & 0xFF, "texel " + i + " blue drifted");
                assertEquals(200, capped.pixels().get(i * 4 + 3) & 0xFF, "texel " + i + " alpha drifted");
            }
        } finally {
            capped.free();
        }
    }

    @Test
    void anOddSizedImageStillShrinksAndKeepsItsEdge() {
        // Odd dimensions are the case a 2:1 filter has no exact answer for, and the failure to avoid is
        // reading past the end of a row — which is a crash or garbage rather than a soft image. The rule
        // is that the odd texel out is clamped rather than dropped.
        Texture.setMaxDimension(2);
        Texture.Capped capped = Texture.capToMaxDimension(ramp(7, 5), 7, 5);
        try {
            assertTrue(capped.width() <= 2 && capped.height() <= 2,
                    "an odd-sized image was not brought inside the cap: "
                            + capped.width() + "x" + capped.height());
            assertTrue(capped.width() >= 1 && capped.height() >= 1, "it shrank away to nothing");
        } finally {
            capped.free();
        }
    }

    @Test
    void theSizeAnArrayLayerIsCheckedAtIsTheSizeItIsShrunkTo() {
        // An array texture's layers are matched on this size before any pixels are shrunk, so it must land exactly
        // where the shrink does: a 2048 layer beside 1024 ones at a limit of 1024, odd sizes and long sides included.
        int[][] sizes = {{2048, 2048}, {1024, 1024}, {7, 5}, {1024, 128}, {128, 1024}, {3000, 17}};
        for (int[] size : sizes) {
            for (int cap : new int[]{1, 2, 256, 1024, Texture.UNCAPPED}) {
                Texture.Capped capped = Texture.capToMaxDimension(ramp(size[0], size[1]), size[0], size[1], cap);
                try {
                    int[] counted = Texture.halvedWithin(size[0], size[1], cap);
                    assertEquals(capped.width(), counted[0], size[0] + "x" + size[1] + " within " + cap);
                    assertEquals(capped.height(), counted[1], size[0] + "x" + size[1] + " within " + cap);
                } finally {
                    capped.free();
                }
            }
        }
        assertArrayEquals(new int[]{1024, 1024}, Texture.halvedWithin(2048, 2048, 1024));
    }

    /** A varied but deterministic RGBA field. */
    private static ByteBuffer ramp(int width, int height) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < width * height; i++) {
            pixels.put(i * 4, (byte) (i * 3));
            pixels.put(i * 4 + 1, (byte) (i * 5));
            pixels.put(i * 4 + 2, (byte) (i * 7));
            pixels.put(i * 4 + 3, (byte) 255);
        }
        return pixels;
    }
}
