package org.zeronura.engine.physics2d;

/**
 * Where a ray first hit something.
 *
 * @param body     the body hit, or null when the ray hit a tilemap tile
 * @param normalX  surface normal at the hit; (0, 0) when the ray started inside the shape
 * @param distance from the ray origin, in world units
 */
public record RaycastHit2D(Body2D body, float x, float y, float normalX, float normalY, float distance) {
}
