package org.zeronura.nulzs.render;

import org.joml.Vector3f;

/**
 * How ground cover parts around whoever is standing in it — the grass bending away from a walker's shins.
 *
 * <h2>Why this is a parameter and not a constant in the shader</h2>
 * The same reason {@link GrassCurve} is one: {@code terrain.vert} draws the terrain, every prop on it and
 * the cover, and it must have no opinion about which of those give way. The game says what is parting the
 * cover and how far it reaches; the shader applies it to the one pass it is switched on for. See
 * {@link Renderer#renderGroundCover}.
 *
 * <p>Frame state, unlike {@link GroundDetail} — the centre is a walking body, so it moves every frame and
 * whoever sets it owns keeping it current. {@link #NONE} is still air: no centre, nothing bends.
 *
 * @param centre   what is parting the cover, in world space — the feet, not the camera. A third-person
 *                 camera orbits the body by several units, and measuring from it would part the grass
 *                 around a point the player is not standing on
 * @param radius   how far out the parting reaches, in world units; 0 disables it entirely
 * @param strength how far a tip is shoved aside at the centre, in world units. The roots never move: the
 *                 displacement is weighted by how far up the cover a vertex sits, squared
 * @param height   the height the cover stands at, which is what turns a vertex's height above its own
 *                 roots into that weight
 */
public record CoverPush(Vector3f centre, float radius, float strength, float height) {

    /** Nothing parting it — what the terrain, the props and a renderer that was never given one use. */
    public static final CoverPush NONE = new CoverPush(new Vector3f(), 0f, 0f, 1f);

    /** Whether this actually bends anything, so a pass can skip setting the rest of it. */
    public boolean isPresent() {
        return radius > 0f && strength != 0f && height > 0f;
    }
}
