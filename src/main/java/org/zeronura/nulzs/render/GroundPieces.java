package org.zeronura.nulzs.render;

import java.nio.ByteBuffer;

/**
 * Whole pictures laid over a tiled map's ground, one per square block of tiles and chosen block by block: the pieces of
 * a path that winds from block to block, a straight here and a bend there.
 *
 * <p>{@link GroundTextures} repeat one picture endlessly over every patch of a material, which suits grass but not a
 * path, whose every block has to join the blocks beside it. Here a small layout texture names, for each block, the
 * layer of {@link #albedo} to stretch once across that block's square. A block it names nothing for keeps whatever the
 * ground textures gave it, and only ground facing up wears a piece, so the wall of a step keeps its own look.
 *
 * <p>The pieces are drawn the way the art is, not tinted by the palette: a path piece carries its own grass round the
 * dirt, and that grass has to match the grass around it.
 *
 * @param albedo         array texture, one layer per piece. Its rows run toward +z: the top of a picture lies along
 *                       the block's low-z edge
 * @param normalMap      matching relief array, or null for colour only
 * @param layout         one texel per block, read exactly (never filtered): red is the piece's layer + 1, 0 for none.
 *                       See {@link #layoutTexture}
 * @param originX        world x of the first block's low-x edge
 * @param originZ        world z of the first block's low-z edge
 * @param blockSize      the side of a block, in world units
 * @param normalStrength how much of the relief shows, 0 to 1
 * @param fadeStart      distance at which the pieces start fading back to the ground under them
 * @param fadeEnd        distance past which they are not drawn
 */
public record GroundPieces(Texture albedo, Texture normalMap, Texture layout, float originX, float originZ,
                           float blockSize, float normalStrength, float fadeStart, float fadeEnd) {

    /** No pieces — the default, and what the prop pass installs. */
    public static final GroundPieces NONE = new GroundPieces(null, null, null, 0f, 0f, 1f, 0f, 0f, 1f);

    /** Whether there is anything to draw. */
    public boolean isPresent() {
        return albedo != null && layout != null && blockSize > 0f;
    }

    /**
     * A layout texture from one byte per block, row by row along z: the piece's layer + 1, or 0 for none.
     *
     * @param pieces {@code columns * rows} entries, each 0 to 255
     */
    public static Texture layoutTexture(byte[] pieces, int columns, int rows) {
        if (pieces.length != columns * rows) {
            throw new IllegalArgumentException("expected " + columns * rows + " blocks, got " + pieces.length);
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(pieces.length * 4);
        for (byte piece : pieces) {
            pixels.put(piece).put((byte) 0).put((byte) 0).put((byte) 0xFF);
        }
        pixels.flip();
        return Texture.fromPixels(pixels, columns, rows);
    }
}
