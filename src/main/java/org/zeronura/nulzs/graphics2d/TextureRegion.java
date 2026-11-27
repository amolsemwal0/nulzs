package org.zeronura.nulzs.graphics2d;

import org.zeronura.nulzs.render.Texture;

/**
 * A rectangle of a texture: one frame of a sprite sheet, one tile of a tileset, or the whole image.
 *
 * <p>UVs follow the image as stored — {@code (u0, v0)} is the <b>top-left</b> of the region — so regions
 * are written in the same coordinates an artist reads off the sheet.
 *
 * @param texture the image, or null to draw untextured (solid colour)
 * @param width   the region's size in texels, useful for keeping art at its native proportions
 */
public record TextureRegion(Texture texture, float u0, float v0, float u1, float v1, float width, float height) {

    /** The whole texture. */
    public static TextureRegion of(Texture texture) {
        return new TextureRegion(texture, 0f, 0f, 1f, 1f, texture.getWidth(), texture.getHeight());
    }

    /** A pixel rectangle of a texture, measured from its top-left corner. */
    public static TextureRegion of(Texture texture, int x, int y, int width, int height) {
        float textureWidth = texture.getWidth();
        float textureHeight = texture.getHeight();
        return new TextureRegion(texture, x / textureWidth, y / textureHeight,
                (x + width) / textureWidth, (y + height) / textureHeight, width, height);
    }

    /**
     * Cuts a sprite sheet into equal frames, reading left to right, top to bottom — the order sheets are
     * conventionally laid out in.
     */
    public static TextureRegion[] grid(Texture texture, int frameWidth, int frameHeight) {
        int columns = texture.getWidth() / frameWidth;
        int rows = texture.getHeight() / frameHeight;
        TextureRegion[] frames = new TextureRegion[columns * rows];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                frames[row * columns + column] =
                        of(texture, column * frameWidth, row * frameHeight, frameWidth, frameHeight);
            }
        }
        return frames;
    }
}
