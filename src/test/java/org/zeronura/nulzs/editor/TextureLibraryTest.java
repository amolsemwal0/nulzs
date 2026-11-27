package org.zeronura.nulzs.editor;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class TextureLibraryTest {

    /** A picture as the library reads it: shrunk to its thumbnail, RGBA. */
    private static ByteBuffer thumbnail(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer pixels = ByteBuffer.allocate(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        ByteBuffer small = ByteBuffer.allocate(TextureLibrary.THUMBNAIL * TextureLibrary.THUMBNAIL * 4);
        TextureLibrary.shrink(pixels.flip(), width, height, small);
        return small;
    }

    private static BufferedImage stripes(boolean upAndDown) {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int band = (upAndDown ? x : y) / 32 % 2;
                image.setRGB(x, y, band == 0 ? 0xFF3A7D2C : 0xFFB08A5A);
            }
        }
        return image;
    }

    @Test
    void stripesRunningUpAndDownAreTurnedAndAcrossAreNot() {
        assertTrue(TextureLibrary.runsUpAndDown(thumbnail(stripes(true))));
        assertFalse(TextureLibrary.runsUpAndDown(thumbnail(stripes(false))));
        BufferedImage even = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                even.setRGB(x, y, (x + y) % 2 == 0 ? 0xFF505560 : 0xFF5A5F6A);
            }
        }
        assertFalse(TextureLibrary.runsUpAndDown(thumbnail(even)), "an even picture runs either way");
    }
}
