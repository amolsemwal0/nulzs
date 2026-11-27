package org.zeronura.engine.terrain;

/**
 * An endless procedural landscape: broad lowlands and hills, with ridged mountain ranges rising where the
 * continental noise is high.
 *
 * @see Terrain
 */
public final class NoiseHeights implements HeightFunction {

    private final Noise noise;
    private final float scale;
    private final float baseHeight;
    private final float hillHeight;
    private final float mountainHeight;

    public NoiseHeights(long seed) {
        this(seed, 0.004f, 0f, 16f, 80f);
    }

    /**
     * @param scale          how quickly features change: smaller is broader, larger is busier
     * @param baseHeight     the average height of the lowlands
     * @param hillHeight     how far hills rise and valleys sink
     * @param mountainHeight how tall the tallest ridges get
     */
    public NoiseHeights(long seed, float scale, float baseHeight, float hillHeight, float mountainHeight) {
        this.noise = new Noise(seed);
        this.scale = scale;
        this.baseHeight = baseHeight;
        this.hillHeight = hillHeight;
        this.mountainHeight = mountainHeight;
    }

    @Override
    public float heightAt(float x, float z) {
        float sx = x * scale;
        float sz = z * scale;
        float continents = noise.fbm(sx * 0.35f, sz * 0.35f, 3, 2f, 0.5f);
        float hills = noise.fbm(sx * 2f + 31.7f, sz * 2f - 12.3f, 4, 2f, 0.5f);
        float ridges = noise.ridged(sx + 71.1f, sz + 19.9f, 5, 2f, 0.5f);
        float mountainMask = Noise.smoothstep(0.05f, 0.45f, continents);
        return baseHeight
                + continents * hillHeight * 1.5f
                + hills * hillHeight * 0.5f
                + ridges * ridges * mountainMask * mountainHeight;
    }
}
