package org.zeronura.engine.core;

import org.lwjgl.BufferUtils;
import org.zeronura.engine.graphics2d.Color;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.stb.STBImageWrite.stbi_write_png;

/**
 * Draws into a picture of its own size, off the screen, and reads it back: for exporting what a scene shows at a set
 * size whatever the window's, a layer at a time. Drawing goes through the usual sprite batch and GUI; they draw into
 * whatever is bound, and this binds its own target and viewport for the length of one drawing.
 *
 * <h2>Transparency</h2>
 * The sprite batch and the GUI blend for the screen, where what is underneath is always opaque, so the alpha they leave
 * in a target is not the picture's. A transparent layer is drawn twice instead, once over black and once over white:
 * where it covers a fraction {@code a} of a pixel in a colour {@code c}, it leaves {@code a·c} over black and
 * {@code a·c + 1 - a} over white, so the difference gives {@code a} and the black one then gives {@code c}. Whatever the
 * drawing — soft shadows, text, a veil over everything — it comes out as the straight-alpha picture an image editor
 * would make of it. The drawing must come out the same both times, so nothing may move between the two.
 *
 * <p>Needs the GL context: use it from a scene's update or draw, not from another thread.
 */
public final class OffscreenCapture implements AutoCloseable {

    private final int width;
    private final int height;
    private final int framebuffer;
    private final int colour;
    private final int depth;
    private final ByteBuffer pixels;

    public OffscreenCapture(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("a picture needs a size, not " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        pixels = BufferUtils.createByteBuffer(width * height * 4);
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        colour = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colour);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colour, 0);
        depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        boolean complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_FRAMEBUFFER, previous);
        if (!complete) {
            close();
            throw new IllegalStateException("off-screen framebuffer incomplete");
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Draws over a solid background and returns the picture: RGBA, a byte a channel, the top row first. */
    public byte[] opaque(Color background, Runnable drawing) {
        byte[] picture = draw(background.r(), background.g(), background.b(), drawing);
        for (int i = 3; i < picture.length; i += 4) {
            picture[i] = (byte) 255;
        }
        return picture;
    }

    /**
     * Draws over a solid background and returns the picture as the drawing wrote it, its alpha too: for drawing done
     * without blending, whose shader's alpha is the picture's (a baked material's Alpha, say). RGBA, a byte a channel,
     * the top row first; where nothing was drawn, the background with alpha 1.
     */
    public byte[] asDrawn(Color background, Runnable drawing) {
        return draw(background.r(), background.g(), background.b(), drawing);
    }

    /**
     * Draws on nothing and returns the picture with its transparency (see the class note): RGBA with straight alpha, a
     * byte a channel, the top row first. The drawing runs twice.
     */
    public byte[] transparent(Runnable drawing) {
        return unmatte(draw(0f, 0f, 0f, drawing), draw(1f, 1f, 1f, drawing));
    }

    /**
     * The straight-alpha picture that, laid over black and over white, looks like {@code black} and {@code white}: see the
     * class note. Both are RGBA, a byte a channel; their own alpha is ignored.
     */
    static byte[] unmatte(byte[] black, byte[] white) {
        byte[] picture = new byte[black.length];
        for (int i = 0; i < black.length; i += 4) {
            int r = black[i] & 0xFF;
            int g = black[i + 1] & 0xFF;
            int b = black[i + 2] & 0xFF;
            // Each channel says how much of white shows through; they agree but for rounding, so take their average.
            int through = (white[i] & 0xFF) - r + (white[i + 1] & 0xFF) - g + (white[i + 2] & 0xFF) - b;
            int alpha = Math.clamp(255 - Math.round(through / 3f), 0, 255);
            if (alpha == 0) {
                continue;
            }
            picture[i] = (byte) Math.min(255, Math.round(r * 255f / alpha));
            picture[i + 1] = (byte) Math.min(255, Math.round(g * 255f / alpha));
            picture[i + 2] = (byte) Math.min(255, Math.round(b * 255f / alpha));
            picture[i + 3] = (byte) alpha;
        }
        return picture;
    }

    private byte[] draw(float r, float g, float b, Runnable drawing) {
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        float[] clear = new float[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, width, height);
        try {
            glClearColor(r, g, b, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            drawing.run();
            pixels.clear();
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, previous);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glClearColor(clear[0], clear[1], clear[2], clear[3]);
        }
        // GL reads the bottom row first; pictures are stored top row first.
        byte[] picture = new byte[width * height * 4];
        int stride = width * 4;
        for (int row = 0; row < height; row++) {
            pixels.get((height - 1 - row) * stride, picture, row * stride, stride);
        }
        return picture;
    }

    /** Writes a picture from {@link #opaque} or {@link #transparent} as a PNG, making its folder if need be. */
    public static Path writePng(Path file, int width, int height, byte[] rgba) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(rgba.length);
        buffer.put(rgba).flip();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!stbi_write_png(file.toString(), width, height, 4, buffer, width * 4)) {
                throw new IOException("stb could not write " + file);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        glDeleteFramebuffers(framebuffer);
        glDeleteTextures(colour);
        glDeleteRenderbuffers(depth);
    }
}
