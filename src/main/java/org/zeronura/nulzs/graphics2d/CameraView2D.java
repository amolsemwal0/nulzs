package org.zeronura.nulzs.graphics2d;

import org.joml.Vector3f;
import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.scene.Tooltip;

/**
 * The camera a 2D scene is seen through: each frame it centres the scene's {@link Camera2D} on its entity and turns
 * it with the entity. Move the entity to move the view.
 */
public class CameraView2D extends Component {

    @Tooltip("How many world units fit top to bottom; 0 shows one unit per pixel")
    public float visibleHeight = 0f;
    @Tooltip("Magnification on top of Visible Height: 2 shows half as much, twice as big")
    public float zoom = 1f;

    private final Vector3f position = new Vector3f();

    @Override
    protected void preRender(float alpha) {
        Scene scene = scene();
        if (scene != null) {
            aim(scene.camera2D());
        }
    }

    /** Puts {@code camera} where this view is. */
    public void aim(Camera2D camera) {
        transform().worldPosition(position);
        camera.position.set(position.x, position.y);
        if (visibleHeight > 0f) {
            camera.setVisibleHeight(visibleHeight / Math.max(0.01f, zoom));
        } else {
            camera.setVisibleHeight(0f);
            camera.setZoom(Math.max(0.01f, zoom));
        }
        camera.setRotation(transform().rotation2D());
    }
}
