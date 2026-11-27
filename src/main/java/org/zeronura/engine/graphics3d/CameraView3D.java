package org.zeronura.engine.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.Range;
import org.zeronura.engine.scene.Scene;

/**
 * The camera a 3D scene is seen through: each frame it puts the world's camera where its entity is, looking along the
 * entity's forward (-Z) axis. Move or turn the entity — by hand in the editor, or from a script — to move the view.
 * With several, the last one enabled wins.
 */
public class CameraView3D extends Component {

    @Range(min = 30, max = 110)
    public float fieldOfView = 60f;
    @Range(min = 10, max = 5000)
    public float farPlane = 700f;

    private final Matrix4f world = new Matrix4f();
    private final Vector3f position = new Vector3f();
    private final Vector3f forward = new Vector3f();

    @Override
    protected void preRender(float alpha) {
        Scene scene = scene();
        if (scene == null || !scene.has3D()) {
            return;
        }
        aim(scene.world3D().camera());
    }

    /** Puts {@code camera} where this view is. */
    public void aim(Camera camera) {
        transform().worldMatrix(world);
        world.getTranslation(position);
        world.transformDirection(forward.set(0f, 0f, -1f));
        if (forward.lengthSquared() < 1e-8f) {
            forward.set(0f, 0f, -1f);
        }
        forward.normalize();
        camera.setPosition(position);
        camera.setOrientation((float) Math.atan2(forward.z, forward.x), (float) Math.asin(Math.clamp(forward.y, -1f, 1f)));
        camera.setFovDegrees(fieldOfView);
        camera.setFarPlane(farPlane);
    }
}
