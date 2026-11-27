package org.zeronura.engine.input;

import org.zeronura.engine.input.InputMap.Binding;

/**
 * The actions the engine's own controllers read — walking, running and jumping — so a game's controls screen can
 * rebind them alongside its own. The engine defines them before a game starts.
 */
public final class StandardActions {

    public static final String MOVEMENT = "Movement";

    public static final String MOVE_FORWARD = "move_forward";
    public static final String MOVE_BACK = "move_back";
    public static final String MOVE_LEFT = "move_left";
    public static final String MOVE_RIGHT = "move_right";
    public static final String SPRINT = "sprint";
    public static final String JUMP = "jump";

    private StandardActions() {
    }

    /** Defines the standard actions on their default keys, or on what the player saved for them. */
    public static void define(InputMap actions) {
        actions.define(MOVE_FORWARD, "Move forward", MOVEMENT, Binding.key(Key.W), Binding.key(Key.UP));
        actions.define(MOVE_BACK, "Move back", MOVEMENT, Binding.key(Key.S), Binding.key(Key.DOWN));
        actions.define(MOVE_LEFT, "Move left", MOVEMENT, Binding.key(Key.A), Binding.key(Key.LEFT));
        actions.define(MOVE_RIGHT, "Move right", MOVEMENT, Binding.key(Key.D), Binding.key(Key.RIGHT));
        actions.define(SPRINT, "Run", MOVEMENT, Binding.key(Key.LEFT_SHIFT));
        actions.define(JUMP, "Jump", MOVEMENT, Binding.key(Key.SPACE));
        // The controller: the left stick walks, clicking it in runs.
        actions.bindStick(MOVE_FORWARD, Gamepad.LEFT_Y, -1f);
        actions.bindStick(MOVE_BACK, Gamepad.LEFT_Y, 1f);
        actions.bindStick(MOVE_LEFT, Gamepad.LEFT_X, -1f);
        actions.bindStick(MOVE_RIGHT, Gamepad.LEFT_X, 1f);
        actions.bindPad(SPRINT, Gamepad.LEFT_STICK);
    }
}
