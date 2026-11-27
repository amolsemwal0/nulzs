package org.zeronura.engine.asset;

import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.lwjgl.stb.STBImage.stbi_failure_reason;
import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load;

/**
 * A picture file's pixels on the CPU, for looking at art rather than drawing it: where a sprite sheet's figures stand,
 * what is opaque, what colour a map sketch is where. Read with stb_image, the same decoder textures use, so it reads
 * the same files on every platform the engine runs on (Android has no {@code javax.imageio}); the methods are named as
 * {@code java.awt.image.BufferedImage}'s are.
 */
public final class Picture {

    private final int width;
    private final int height;
    private final int[] argb;
    private final boolean alpha;

    private Picture(int width, int height, int[] argb, boolean alpha) {
        this.width = width;
        this.height = height;
        this.argb = argb;
        this.alpha = alpha;
    }

    /** A picture from pixels in memory, row by row from the top left, 0xAARRGGBB each. */
    public static Picture of(int width, int height, int[] argb) {
        if (argb.length < width * height) {
            throw new IllegalArgumentException(argb.length + " pixels for " + width + "x" + height);
        }
        return new Picture(width, height, argb, true);
    }

    /** Reads a PNG, JPEG, BMP, TGA or GIF (its first frame). */
    public static Picture read(Path file) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer rgba = stbi_load(file.toString(), w, h, channels, 4);
            if (rgba == null) {
                throw new IOException("Could not read the picture " + file + ": " + stbi_failure_reason());
            }
            try {
                int width = w.get(0);
                int height = h.get(0);
                int[] argb = new int[width * height];
                for (int i = 0; i < argb.length; i++) {
                    int r = rgba.get(i * 4) & 0xFF;
                    int g = rgba.get(i * 4 + 1) & 0xFF;
                    int b = rgba.get(i * 4 + 2) & 0xFF;
                    int a = rgba.get(i * 4 + 3) & 0xFF;
                    argb[i] = a << 24 | r << 16 | g << 8 | b;
                }
                // Grey with alpha, or colour with alpha (a palette with transparency reads as that too).
                boolean alpha = channels.get(0) == 2 || channels.get(0) == 4;
                return new Picture(width, height, argb, alpha);
            } finally {
                stbi_image_free(rgba);
            }
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Whether the file has an alpha channel (whether or not anything in it is see-through). */
    public boolean hasAlpha() {
        return alpha;
    }

    /** A pixel as 0xAARRGGBB; (0, 0) is the top left. */
    public int getRGB(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") outside " + width + "x" + height);
        }
        return argb[y * width + x];
    }

    /** Sets a pixel, 0xAARRGGBB. */
    public void setRGB(int x, int y, int argbColour) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") outside " + width + "x" + height);
        }
        argb[y * width + x] = argbColour;
    }

    /**
     * A block of pixels as 0xAARRGGBB into {@code out} (made if null) from {@code offset}, rows {@code scan} apart, as
     * {@code BufferedImage.getRGB} does.
     */
    public int[] getRGB(int x0, int y0, int w, int h, int[] out, int offset, int scan) {
        int[] into = out != null ? out : new int[offset + h * scan];
        for (int y = 0; y < h; y++) {
            System.arraycopy(argb, (y0 + y) * width + x0, into, offset + y * scan, w);
        }
        return into;
    }
}
