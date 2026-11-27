package org.zeronura.nulzs.scene;

/**
 * Implement on a component to receive mouse interaction with its entity's sprite: clicking a card,
 * dragging a puzzle piece, pressing an in-world button.
 *
 * <p>The scene hit-tests the pointer against every entity with a {@link org.zeronura.nulzs.graphics2d.SpriteRenderer}
 * and a component implementing this, topmost sprite first. Coordinates are in 2D world units. Clicks the
 * GUI has already taken are not delivered.
 */
public interface PointerHandler {

    default void onPointerDown(float worldX, float worldY) {
    }

    /** While held after a press on this entity, whenever the pointer moves. */
    default void onPointerDrag(float worldX, float worldY, float deltaX, float deltaY) {
    }

    default void onPointerUp(float worldX, float worldY) {
    }

    /** Pressed and released over this entity. */
    default void onClick() {
    }
}
