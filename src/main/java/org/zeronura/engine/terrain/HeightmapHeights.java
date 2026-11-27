package org.zeronura.engine.terrain;

import org.zeronura.engine.asset.HeightmapImage;

/**
 * A landscape read from a greyscale heightmap image or a sculpted lattice mesh — an authored map rather
 * than a generated one. Outside the image, the edge heights continue outward.
 *
 * <pre>{@code
 * HeightmapImage image = HeightmapImage.load("assets/maps/island.png");
 * HeightFunction island = new HeightmapHeights(image, -512, -512, 1024, 1024, -10, 90);
 * }</pre>
 */
public final class HeightmapHeights implements HeightFunction {

    private final HeightmapImage image;
    private final float originX;
    private final float originZ;
    private final float sizeX;
    private final float sizeZ;
    private final float baseHeight;
    private final float heightScale;

    /**
     * @param originX     world x of the image's left edge
     * @param sizeX       world width the image covers
     * @param baseHeight  world height of black
     * @param heightScale world height added by white
     */
    public HeightmapHeights(HeightmapImage image, float originX, float originZ, float sizeX, float sizeZ,
                            float baseHeight, float heightScale) {
        this.image = image;
        this.originX = originX;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.baseHeight = baseHeight;
        this.heightScale = heightScale;
    }

    @Override
    public float heightAt(float x, float z) {
        float u = Math.max(0f, Math.min(1f, (x - originX) / sizeX));
        float v = Math.max(0f, Math.min(1f, (z - originZ) / sizeZ));
        return baseHeight + image.sample(u, v) * heightScale;
    }
}
