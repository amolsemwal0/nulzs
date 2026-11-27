package org.zeronura.nulzs.graphics2d;

import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Scene;

/**
 * Puts a {@link Tilemap} in a scene: draws it, and registers its solid tiles with 2D physics.
 *
 * <p>The map's origin is taken from the entity's position when it enters the scene. Tilemaps are level
 * geometry and are not expected to move afterwards.
 */
public class TilemapRenderer extends Component implements Renderable2D {

    private final Tilemap tilemap;
    /** Drawn behind sprites by default. */
    public int layer = -100;
    private final boolean collidable;

    public TilemapRenderer(Tilemap tilemap) {
        this(tilemap, true);
    }

    public TilemapRenderer(Tilemap tilemap, boolean collidable) {
        this.tilemap = tilemap;
        this.collidable = collidable;
    }

    public Tilemap tilemap() {
        return tilemap;
    }

    @Override
    protected void onEnterScene(Scene scene) {
        tilemap.setOrigin(transform().x(), transform().y());
        if (collidable) {
            scene.physics2D().addTilemap(tilemap);
        }
    }

    @Override
    protected void onExitScene(Scene scene) {
        if (collidable) {
            scene.physics2D().removeTilemap(tilemap);
        }
    }

    @Override
    public int layer() {
        return layer;
    }

    @Override
    public void draw(SpriteBatch batch, Camera2D camera) {
        tilemap.draw(batch, camera);
    }
}
