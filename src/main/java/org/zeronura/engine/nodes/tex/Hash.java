package org.zeronura.engine.nodes.tex;

/**
 * Bob Jenkins' lookup3 hash ("final" and "mix" over three 32-bit words), the hash Blender's procedural textures,
 * Random Value and point distribution are all built on. Matching it bit for bit is what makes a scatter or a
 * noise in the engine land exactly where it landed in Blender.
 *
 * <p>All arithmetic is unsigned 32-bit, which Java's int wraps exactly like; only the conversions to float need
 * care ({@link #toUnit}).
 */
public final class Hash {

    private Hash() {
    }

    private static int rot(int x, int k) {
        return (x << k) | (x >>> (32 - k));
    }

    private static final int INIT = 0xdeadbeef;

    public static int hash(int kx) {
        int a, b, c;
        a = b = c = INIT + (1 << 2) + 13;
        a += kx;
        // final(a, b, c)
        c ^= b; c -= rot(b, 14);
        a ^= c; a -= rot(c, 11);
        b ^= a; b -= rot(a, 25);
        c ^= b; c -= rot(b, 16);
        a ^= c; a -= rot(c, 4);
        b ^= a; b -= rot(a, 14);
        c ^= b; c -= rot(b, 24);
        return c;
    }

    public static int hash(int kx, int ky) {
        int a, b, c;
        a = b = c = INIT + (2 << 2) + 13;
        b += ky;
        a += kx;
        c ^= b; c -= rot(b, 14);
        a ^= c; a -= rot(c, 11);
        b ^= a; b -= rot(a, 25);
        c ^= b; c -= rot(b, 16);
        a ^= c; a -= rot(c, 4);
        b ^= a; b -= rot(a, 14);
        c ^= b; c -= rot(b, 24);
        return c;
    }

    public static int hash(int kx, int ky, int kz) {
        int a, b, c;
        a = b = c = INIT + (3 << 2) + 13;
        c += kz;
        b += ky;
        a += kx;
        c ^= b; c -= rot(b, 14);
        a ^= c; a -= rot(c, 11);
        b ^= a; b -= rot(a, 25);
        c ^= b; c -= rot(b, 16);
        a ^= c; a -= rot(c, 4);
        b ^= a; b -= rot(a, 14);
        c ^= b; c -= rot(b, 24);
        return c;
    }

    public static int hash(int kx, int ky, int kz, int kw) {
        int a, b, c;
        a = b = c = INIT + (4 << 2) + 13;
        a += kx;
        b += ky;
        c += kz;
        // mix(a, b, c)
        a -= c; a ^= rot(c, 4); c += b;
        b -= a; b ^= rot(a, 6); a += c;
        c -= b; c ^= rot(b, 8); b += a;
        a -= c; a ^= rot(c, 16); c += b;
        b -= a; b ^= rot(a, 19); a += c;
        c -= b; c ^= rot(b, 4); b += a;
        a += kw;
        c ^= b; c -= rot(b, 14);
        a ^= c; a -= rot(c, 11);
        b ^= a; b -= rot(a, 25);
        c ^= b; c -= rot(b, 16);
        a ^= c; a -= rot(c, 4);
        b ^= a; b -= rot(a, 14);
        c ^= b; c -= rot(b, 24);
        return c;
    }

    /** An unsigned 32-bit hash as a float in [0, 1], the way Blender divides by 0xFFFFFFFF. */
    public static float toUnit(int hash) {
        return (float) ((double) Integer.toUnsignedLong(hash) / 4294967295.0);
    }

    public static float hashToFloat(int kx) {
        return toUnit(hash(kx));
    }

    public static float hashToFloat(int kx, int ky) {
        return toUnit(hash(kx, ky));
    }

    public static float hashToFloat(int kx, int ky, int kz) {
        return toUnit(hash(kx, ky, kz));
    }

    public static float hashToFloat(int kx, int ky, int kz, int kw) {
        return toUnit(hash(kx, ky, kz, kw));
    }

    private static int bits(float f) {
        return Float.floatToRawIntBits(f);
    }

    public static float floatToFloat(float k) {
        return hashToFloat(bits(k));
    }

    public static float vec2ToFloat(float x, float y) {
        return hashToFloat(bits(x), bits(y));
    }

    public static float vec3ToFloat(float x, float y, float z) {
        return hashToFloat(bits(x), bits(y), bits(z));
    }

    public static float vec4ToFloat(float x, float y, float z, float w) {
        return hashToFloat(bits(x), bits(y), bits(z), bits(w));
    }

    /** Blender's hash of a float vector, used for point ids: the hash of the raw bits of each component. */
    public static int hashFloat(float x, float y, float z) {
        return hash(bits(x), bits(y), bits(z));
    }

    public static int hashFloat(float x, float y) {
        return hash(bits(x), bits(y));
    }

    public static int hashFloat(float x) {
        return hash(bits(x));
    }

    /** The offset Blender adds between the channels of a noise so they are uncorrelated. */
    public static float randomFloatOffset(float seed) {
        return 100f + floatToFloat(seed) * 100f;
    }

    public static void randomVec2Offset(float seed, float[] out) {
        out[0] = 100f + vec2ToFloat(seed, 0f) * 100f;
        out[1] = 100f + vec2ToFloat(seed, 1f) * 100f;
    }

    public static void randomVec3Offset(float seed, float[] out) {
        out[0] = 100f + vec2ToFloat(seed, 0f) * 100f;
        out[1] = 100f + vec2ToFloat(seed, 1f) * 100f;
        out[2] = 100f + vec2ToFloat(seed, 2f) * 100f;
    }

    public static void randomVec4Offset(float seed, float[] out) {
        out[0] = 100f + vec2ToFloat(seed, 0f) * 100f;
        out[1] = 100f + vec2ToFloat(seed, 1f) * 100f;
        out[2] = 100f + vec2ToFloat(seed, 2f) * 100f;
        out[3] = 100f + vec2ToFloat(seed, 3f) * 100f;
    }
}
