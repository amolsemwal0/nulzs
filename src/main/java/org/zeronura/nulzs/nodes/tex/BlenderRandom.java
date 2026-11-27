package org.zeronura.nulzs.nodes.tex;

/**
 * Blender's RandomNumberGenerator: the drand48 linear congruential generator, seeded as Blender seeds it. Point
 * distribution draws from one of these per triangle, so the engine places the same points Blender does.
 */
public final class BlenderRandom {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = 0x0000FFFFFFFFFFFFL;

    private long x;

    public BlenderRandom(int seed) {
        x = (Integer.toUnsignedLong(seed) << 16) | 0x330EL;
    }

    private void step() {
        x = (MULTIPLIER * x + ADDEND) & MASK;
    }

    public int nextInt() {
        step();
        return (int) (x >>> 17);
    }

    /** In [0, 1). */
    public float nextFloat() {
        return (float) nextInt() / 0x80000000L;
    }

    /** {@code x} rounded down or up at random, up with the probability of its fractional part. */
    public int roundProbabilistic(float value) {
        float fraction = value - (float) Math.floor(value);
        boolean up = fraction > nextFloat();
        return (int) value + (up ? 1 : 0);
    }

    /** A uniformly random point of a triangle, as barycentric weights. */
    public void barycentric(float[] out) {
        float r1 = nextFloat();
        float r2 = nextFloat();
        if (r1 + r2 > 1f) {
            r1 = 1f - r1;
            r2 = 1f - r2;
        }
        out[0] = r1;
        out[1] = r2;
        out[2] = 1f - r1 - r2;
    }
}
