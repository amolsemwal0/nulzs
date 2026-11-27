package org.zeronura.nulzs.asset;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * A greyscale heightmap decoded from an image file into a normalized elevation grid, sampled bilinearly.
 *
 * <p>Engine-layer and GL-free: it holds a plain {@code float[]} of elevations in {@code [0, 1]} and does
 * nothing but read from it, so it is immutable after construction and safe to share across the chunk
 * worker threads (which is how it is used — one instance behind the shared {@code HeightmapGenerator}).
 * Decoding goes through {@link ImageIO} rather than the STB path {@code Texture} uses because this is CPU
 * data, never a GL texture — there is no upload, and no native buffer to free.
 *
 * <p>16-bit greyscale PNGs (the usual heightmap export) decode to a {@code TYPE_USHORT_GRAY} raster, so the
 * full 0..65535 range survives — 8-bit would band a tall smooth slope into ~256 visible steps. Elevations
 * are normalized against the image's own maximum, and the location of that maximum (the peak) is recorded
 * so a caller can centre a feature on the summit wherever it sits in the frame.
 */
public final class HeightmapImage {

    private final int width;
    private final int height;
    private final float[] elevation;   // row-major, normalized [0, 1]
    private final float peakU;
    private final float peakV;

    private HeightmapImage(int width, int height, float[] elevation, float peakU, float peakV) {
        this.width = width;
        this.height = height;
        this.elevation = elevation;
        this.peakU = peakU;
        this.peakV = peakV;
    }

    /**
     * Builds a heightmap directly from a normalized (or arbitrary) elevation grid — for procedural sources
     * and for tests that need a known shape without an image file. Values are normalized against their own
     * maximum and the peak located, exactly as {@link #load} does.
     */
    public static HeightmapImage of(int width, int height, float[] elevation) {
        if (elevation.length != width * height) {
            throw new IllegalArgumentException("elevation length " + elevation.length
                    + " != " + width + "x" + height);
        }
        float maxVal = Float.MIN_NORMAL;
        for (float v : elevation) {
            if (v > maxVal) {
                maxVal = v;
            }
        }
        float inv = 1f / maxVal;
        float[] norm = new float[elevation.length];
        int peakIndex = 0;
        float peakValue = -1f;
        for (int i = 0; i < norm.length; i++) {
            float v = elevation[i] * inv;
            norm[i] = v;
            if (v > peakValue) {
                peakValue = v;
                peakIndex = i;
            }
        }
        return new HeightmapImage(width, height, norm,
                ((peakIndex % width) + 0.5f) / width, ((peakIndex / width) + 0.5f) / height);
    }

    /**
     * Decodes a greyscale image file into a normalized elevation grid: its first channel, at the file's own depth (16
     * bits where it has them), read with stb_image as textures are.
     */
    public static HeightmapImage load(String path) throws IOException {
        int w;
        int h;
        int[] samples;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pw = stack.mallocInt(1);
            IntBuffer ph = stack.mallocInt(1);
            IntBuffer pc = stack.mallocInt(1);
            if (STBImage.stbi_is_16_bit(path)) {
                ShortBuffer data = STBImage.stbi_load_16(path, pw, ph, pc, 0);
                if (data == null) {
                    throw new IOException("could not decode " + path + ": " + STBImage.stbi_failure_reason());
                }
                try {
                    w = pw.get(0);
                    h = ph.get(0);
                    int channels = pc.get(0);
                    samples = new int[w * h];
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = data.get(i * channels) & 0xFFFF;
                    }
                } finally {
                    STBImage.stbi_image_free(data);
                }
            } else {
                ByteBuffer data = STBImage.stbi_load(path, pw, ph, pc, 0);
                if (data == null) {
                    throw new IOException("could not decode " + path + ": " + STBImage.stbi_failure_reason());
                }
                try {
                    w = pw.get(0);
                    h = ph.get(0);
                    int channels = pc.get(0);
                    samples = new int[w * h];
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = data.get(i * channels) & 0xFF;
                    }
                } finally {
                    STBImage.stbi_image_free(data);
                }
            }
        }

        int maxVal = 1;
        for (int s : samples) {
            if (s > maxVal) {
                maxVal = s;
            }
        }
        float inv = 1f / maxVal;

        float[] elev = new float[w * h];
        int peakIndex = 0;
        float peakValue = -1f;
        for (int i = 0; i < elev.length; i++) {
            float v = samples[i] * inv;
            elev[i] = v;
            if (v > peakValue) {
                peakValue = v;
                peakIndex = i;
            }
        }
        float peakU = ((peakIndex % w) + 0.5f) / w;
        float peakV = ((peakIndex / w) + 0.5f) / h;
        return new HeightmapImage(w, h, elev, peakU, peakV);
    }

    /** Bilinearly samples the normalized elevation at texture coordinate {@code (u, v)}, clamped to the edge. */
    public float sample(float u, float v) {
        u = Math.clamp(u, 0f, 1f);
        v = Math.clamp(v, 0f, 1f);
        float fx = u * (width - 1);
        float fy = v * (height - 1);
        int x0 = (int) fx;
        int y0 = (int) fy;
        int x1 = Math.min(x0 + 1, width - 1);
        int y1 = Math.min(y0 + 1, height - 1);
        float tx = fx - x0;
        float ty = fy - y0;

        float a = elevation[y0 * width + x0];
        float b = elevation[y0 * width + x1];
        float c = elevation[y1 * width + x0];
        float d = elevation[y1 * width + x1];
        float top = a + (b - a) * tx;
        float bottom = c + (d - c) * tx;
        return top + (bottom - top) * ty;
    }

    /** Grid columns. Useful for reporting what an asset actually cost to hold. */
    public int width() {
        return width;
    }

    /** Grid rows. */
    public int height() {
        return height;
    }

    /** Texture U of the highest pixel — the summit — so a feature can be centred on it. */
    public float peakU() {
        return peakU;
    }

    /** Texture V of the highest pixel. */
    public float peakV() {
        return peakV;
    }
}
