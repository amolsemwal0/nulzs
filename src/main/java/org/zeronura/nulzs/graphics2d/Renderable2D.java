package org.zeronura.nulzs.graphics2d;

/**
 * A component the scene draws in its 2D pass. Implement it to make a custom drawable — a particle trail,
 * a health bar above a unit — that sorts with sprites by layer.
 */
public interface Renderable2D {

    /** Draw order: lower layers are drawn first, so higher layers appear on top. */
    int layer();

    void draw(SpriteBatch batch, Camera2D camera);
}
