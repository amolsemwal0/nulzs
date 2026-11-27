package org.zeronura.engine.render;

/**
 * The outline a {@link SurfaceDecal} paints on the ground.
 *
 * <p>Geometry rather than game vocabulary — the engine layer has no idea anything is being dug — so these
 * are named for the flat shapes they actually are, where the game's own {@code DigShape} is named for the
 * solids it subtracts.
 */
public enum DecalShape {
    CIRCLE,
    SQUARE
}
