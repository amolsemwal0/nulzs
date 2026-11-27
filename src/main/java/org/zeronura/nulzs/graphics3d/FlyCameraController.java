package org.zeronura.nulzs.graphics3d;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.zeronura.nulzs.core.Engine;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.scene.Component;

/**
 * A free-flying camera for exploring, level design and editor viewports: mouse to look, WASD to move,
 * E / Space to rise, Q / Ctrl to sink, Shift to go fast. Starts at its entity's position and keeps the
 * entity's position in step with the camera.
 */
public class FlyCameraController extends Component {

    public float speed = 14f;
    public float fastMultiplier = 5f;
    public float sensitivity = 0.0022f;
    public boolean captureOnClick = true;

    private final CursorLook look = new CursorLook();
    private final Vector3f forward = new Vector3f();
    private final Vector3f move = new Vector3f();

    @Override
    protected void start() {
        scene().world3D().camera().setPosition(transform().position);
    }

    @Override
    protected void update(float dt) {
        Engine engine = engine();
        if (engine == null) {
            return;
        }
        Input input = engine.input();
        Camera camera = scene().world3D().camera();

        Vector2f turn = look.update(engine, sensitivity, captureOnClick, true);
        if (turn.x != 0f || turn.y != 0f) {
            camera.rotate(turn.x, turn.y);
        }
        if (engine.gui().hasKeyboardFocus()) {
            return;
        }

        float forwardInput = CursorLook.axis(input, Key.S, Key.DOWN, Key.W, Key.UP);
        float strafeInput = CursorLook.axis(input, Key.A, Key.LEFT, Key.D, Key.RIGHT);
        float riseInput = CursorLook.axis(input, Key.Q, Key.LEFT_CONTROL, Key.E, Key.SPACE);
        float yaw = camera.getYaw();
        camera.getForward(forward);
        move.set(forward).mul(forwardInput)
                .add(-(float) Math.sin(yaw) * strafeInput, riseInput, (float) Math.cos(yaw) * strafeInput);
        if (move.lengthSquared() > 1e-8f) {
            float rate = speed * (input.isKeyDown(Key.LEFT_SHIFT) ? fastMultiplier : 1f);
            move.normalize(rate * dt);
            camera.translate(move);
        }
        transform().position.set(camera.getPosition());
    }
}
