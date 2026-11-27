package org.zeronura.engine.graphics3d;

import org.joml.Vector2f;
import org.zeronura.engine.MouseLook;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.core.UserSettings;
import org.zeronura.engine.input.Gamepad;
import org.zeronura.engine.input.Input;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;

/**
 * Mouse capture and look deltas shared by the camera controllers: click in the window to capture the
 * mouse, Escape to release it. Escape is consumed when it releases, so the game's own Escape handling
 * (a pause menu, leaving the level) only sees presses made while the mouse is already free — unless the
 * controller leaves Escape to the game, which then lets the mouse go itself (its pause menu does).
 *
 * <p>A controller's right stick turns the camera too, at up to {@value #STICK_TURN_SPEED} radians a second at full lean
 * (times the player's sensitivity), with no capturing needed.
 */
final class CursorLook {

    /** Radians a second the camera turns with the right stick fully leant. */
    static final float STICK_TURN_SPEED = 2.6f;

    private final MouseLook look = new MouseLook();
    private final Vector2f delta = new Vector2f();

    /** @return this frame's yaw/pitch change in radians */
    Vector2f update(Engine engine, float sensitivity, boolean captureOnClick, boolean escapeReleases) {
        Input input = engine.input();
        // On a touch screen a tap is a tap, not a request to lock the pointer for mouse-look.
        if (captureOnClick && !input.isTouchMode() && !input.isCursorCaptured() && input.isMousePressed(MouseButton.LEFT)
                && !engine.guiWantsPointer()) {
            input.setCursorCaptured(true);
            input.consumeMouse(MouseButton.LEFT);
        } else if (escapeReleases && input.isCursorCaptured() && input.isKeyPressed(Key.ESCAPE)) {
            input.setCursorCaptured(false);
            input.consumeKey(Key.ESCAPE);
        }
        // The player's multiplier on top of the controller's own feel, from the settings screen.
        UserSettings settings = engine.settings();
        look.update(input.isCursorCaptured(), input.cursorX(), input.cursorY(),
                sensitivity * settings.mouseSensitivity(), delta);
        Gamepad pad = input.gamepad();
        float dt = engine.time().delta();
        float stickX = pad.axis(Gamepad.RIGHT_X);
        float stickY = pad.axis(Gamepad.RIGHT_Y);
        // Squared, so small leans make fine adjustments and a full lean still turns quickly.
        float turn = STICK_TURN_SPEED * settings.mouseSensitivity() * dt;
        delta.x += Math.signum(stickX) * stickX * stickX * turn;
        delta.y -= Math.signum(stickY) * stickY * stickY * turn;
        if (settings.invertY()) {
            delta.y = -delta.y;
        }
        return delta;
    }

    static float axis(Input input, int negativeA, int negativeB, int positiveA, int positiveB) {
        float value = 0f;
        if (input.isKeyDown(positiveA) || input.isKeyDown(positiveB)) {
            value += 1f;
        }
        if (input.isKeyDown(negativeA) || input.isKeyDown(negativeB)) {
            value -= 1f;
        }
        return value;
    }
}
