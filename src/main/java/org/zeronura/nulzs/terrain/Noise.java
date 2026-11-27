package org.zeronura.nulzs.terrain;

import java.util.SplittableRandom;

/**
 * Seeded 2D gradient (Perlin) noise and the fractal sums built from it — the raw material for
 * terrain, cloud cover, biome maps and anything else that should vary smoothly but unpredictably.
 *
 * <p>Immutable and thread-safe, so terrain chunks can be generated on several threads from one instance.
 * The same seed always produces the same world.
 */
public final class Noise {

    private final int[] permutation = new int[512];

    public Noise(long seed) {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            table[i] = i;
        }
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = table[i];
            table[i] = table[j];
            table[j] = swap;
        }
        for (int i = 0; i < 512; i++) {
            permutation[i] = table[i & 255];
        }
    }

    /** Gradient noise in roughly [-1, 1], smooth at every scale, zero at integer lattice points. */
    public float perlin(float x, float y) {
        int xi = fastFloor(x);
        int yi = fastFloor(y);
        float xf = x - xi;
        float yf = y - yi;
        int cellX = xi & 255;
        int cellY = yi & 255;

        float u = fade(xf);
        float v = fade(yf);

        int aa = permutation[permutation[cellX] + cellY];
        int ab = permutation[permutation[cellX] + cellY + 1];
        int ba = permutation[permutation[cellX + 1] + cellY];
        int bb = permutation[permutation[cellX + 1] + cellY + 1];

        float bottom = lerp(gradient(aa, xf, yf), gradient(ba, xf - 1f, yf), u);
        float top = lerp(gradient(ab, xf, yf - 1f), gradient(bb, xf - 1f, yf - 1f), u);
        return Math.max(-1f, Math.min(1f, lerp(bottom, top, v)));
    }

    /**
     * Fractal Brownian motion: several octaves of {@link #perlin}, each finer and fainter. Normalised to
     * roughly [-1, 1]. Five octaves with lacunarity 2 and gain 0.5 is a good natural default.
     */
    public float fbm(float x, float y, int octaves, float lacunarity, float gain) {
        float sum = 0f;
        float amplitude = 1f;
        float frequency = 1f;
        float total = 0f;
        for (int i = 0; i < octaves; i++) {
            sum += perlin(x * frequency, y * frequency) * amplitude;
            total += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return total == 0f ? 0f : sum / total;
    }

    /** Ridged multifractal in [0, 1]: sharp crests where plain noise crosses zero. Mountain ranges. */
    public float ridged(float x, float y, int octaves, float lacunarity, float gain) {
        float sum = 0f;
        float amplitude = 1f;
        float frequency = 1f;
        float total = 0f;
        for (int i = 0; i < octaves; i++) {
            float ridge = 1f - Math.abs(perlin(x * frequency, y * frequency));
            sum += ridge * ridge * amplitude;
            total += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return total == 0f ? 0f : sum / total;
    }

    private static float gradient(int hash, float x, float y) {
        return switch (hash & 7) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int fastFloor(float value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
