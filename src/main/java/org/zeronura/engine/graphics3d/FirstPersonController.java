package org.zeronura.engine.graphics3d;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.gui.Gui;
import org.zeronura.engine.input.Input;
import org.zeronura.engine.input.StandardActions;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.scene.Component;

/**
 * First-person player input: mouse look, WASD / arrow keys to move, Shift to sprint, Space to jump.
 * Requires a {@link CharacterController3D} on the same entity and puts the world camera at its eyes.
 */
public class FirstPersonController extends Component {

    /** Radians per pixel of mouse movement. */
    public float sensitivity = 0.0022f;
    /** Capture the mouse when the window is clicked. */
    public boolean captureOnClick = true;

    private final CursorLook look = new CursorLook();
    private final Vector3f eye = new Vector3f();
    private CharacterController3D controller;

    @Override
    protected void start() {
        controller = entity().require(CharacterController3D.class);
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

        Gui gui = engine.gui();
        if (gui.hasKeyboardFocus()) {
            controller.move(0f, 0f);
            return;
        }
        float forward = input.actions().axis(StandardActions.MOVE_BACK, StandardActions.MOVE_FORWARD);
        float strafe = input.actions().axis(StandardActions.MOVE_LEFT, StandardActions.MOVE_RIGHT);
        float yaw = camera.getYaw();
        float moveX = (float) Math.cos(yaw) * forward - (float) Math.sin(yaw) * strafe;
        float moveZ = (float) Math.sin(yaw) * forward + (float) Math.cos(yaw) * strafe;
        float length = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (length > 1f) {
            moveX /= length;
            moveZ /= length;
        }
        float speed = controller.moveSpeed * (input.actions().isDown(StandardActions.SPRINT) ? controller.sprintMultiplier : 1f);
        controller.move(moveX * speed, moveZ * speed);
        if (input.actions().isPressed(StandardActions.JUMP)) {
            controller.jump();
        }
    }

    @Override
    protected void preRender(float alpha) {
        if (controller == null) {
            return;
        }
        controller.interpolatedPosition(alpha, eye).add(0f, controller.eyeHeight, 0f);
        scene().world3D().camera().setPosition(eye);
    }
}
