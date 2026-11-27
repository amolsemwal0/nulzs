package org.zeronura.engine.core;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.stb.STBImageWrite.stbi_write_png;

/** Saves the current back buffer as a PNG. Call after rendering and before the buffers are swapped. */
public final class Screenshot {

    private Screenshot() {
    }

    public static Path capture(int width, int height, Path file) {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        ByteBuffer flipped = MemoryUtil.memAlloc(width * height * 4);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glReadBuffer(GL_BACK);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

            // GL rows start at the bottom, PNG rows at the top. Alpha is forced opaque: the back buffer's
            // alpha is whatever the last blend left there, and a PNG viewer would show it as holes.
            int stride = width * 4;
            for (int y = 0; y < height; y++) {
                int src = (height - 1 - y) * stride;
                int dst = y * stride;
                for (int x = 0; x < stride; x += 4) {
                    flipped.put(dst + x, pixels.get(src + x));
                    flipped.put(dst + x + 1, pixels.get(src + x + 1));
                    flipped.put(dst + x + 2, pixels.get(src + x + 2));
                    flipped.put(dst + x + 3, (byte) 255);
                }
            }

            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!stbi_write_png(file.toString(), width, height, 4, flipped, stride)) {
                throw new IOException("stb could not write " + file);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            MemoryUtil.memFree(pixels);
            MemoryUtil.memFree(flipped);
        }
    }
}
