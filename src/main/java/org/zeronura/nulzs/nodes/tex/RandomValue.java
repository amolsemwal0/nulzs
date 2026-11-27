package org.zeronura.nulzs.nodes.tex;

/**
 * The Random Value and Hash Value nodes: deterministic per-element randomness keyed by an ID and a seed, hashed
 * the way Blender hashes them so a scatter comes out identical.
 */
public final class RandomValue {

    private RandomValue() {
    }

    public static float floatValue(float min, float max, int id, int seed) {
        return Hash.hashToFloat(seed, id) * (max - min) + min;
    }

    /** An integer in [min, max]: the hash taken modulo the size of the range, unsigned, as Blender 5 does. */
    public static int intValue(int min, int max, int id, int seed) {
        long range = (long) max - min + 1;
        if (range <= 0) {
            return min;
        }
        return (int) (min + Integer.toUnsignedLong(Hash.hash(id, seed)) % range);
    }

    public static void vectorValue(float[] min, float[] max, int id, int seed, float[] out) {
        for (int c = 0; c < 3; c++) {
            out[c] = Hash.hashToFloat(seed, id, c) * (max[c] - min[c]) + min[c];
        }
    }

    public static boolean boolValue(float probability, int id, int seed) {
        return Hash.hashToFloat(id, seed) <= probability;
    }

    public static int hashInt(int value, int seed) {
        return Hash.hash(Hash.hash(value), seed);
    }

    public static int hashFloat(float value, int seed) {
        return Hash.hash(Hash.hashFloat(value), seed);
    }

    public static int hashVector(float x, float y, float z, int seed) {
        return Hash.hash(Hash.hashFloat(x, y, z), seed);
    }
}
