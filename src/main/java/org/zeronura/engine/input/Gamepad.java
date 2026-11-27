package org.zeronura.engine.input;

import org.lwjgl.glfw.GLFWGamepadState;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The first connected game controller, in the standard layout every pad is mapped to: an Xbox-style face (A at the
 * bottom, B right, X left, Y top), two sticks, two triggers, two bumpers, a D-pad, Back, Start and the stick clicks.
 *
 * <p>GLFW maps the pad through SDL's controller database, so an Xbox, PlayStation, Switch Pro or generic pad all read
 * the same; a Steam Deck's own controls, under Steam Input, arrive as an Xbox pad. Polled once a frame like the
 * keyboard, with the same pressed / down / released answers per button. The sticks have a dead zone so a resting stick
 * that sits slightly off-centre reads as exactly zero.
 */
public final class Gamepad {

    // Buttons, by their GLFW index.
    public static final int A = GLFW_GAMEPAD_BUTTON_A;
    public static final int B = GLFW_GAMEPAD_BUTTON_B;
    public static final int X = GLFW_GAMEPAD_BUTTON_X;
    public static final int Y = GLFW_GAMEPAD_BUTTON_Y;
    public static final int LEFT_BUMPER = GLFW_GAMEPAD_BUTTON_LEFT_BUMPER;
    public static final int RIGHT_BUMPER = GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER;
    public static final int BACK = GLFW_GAMEPAD_BUTTON_BACK;
    public static final int START = GLFW_GAMEPAD_BUTTON_START;
    public static final int GUIDE = GLFW_GAMEPAD_BUTTON_GUIDE;
    public static final int LEFT_STICK = GLFW_GAMEPAD_BUTTON_LEFT_THUMB;
    public static final int RIGHT_STICK = GLFW_GAMEPAD_BUTTON_RIGHT_THUMB;
    public static final int DPAD_UP = GLFW_GAMEPAD_BUTTON_DPAD_UP;
    public static final int DPAD_RIGHT = GLFW_GAMEPAD_BUTTON_DPAD_RIGHT;
    public static final int DPAD_DOWN = GLFW_GAMEPAD_BUTTON_DPAD_DOWN;
    public static final int DPAD_LEFT = GLFW_GAMEPAD_BUTTON_DPAD_LEFT;
    /** The triggers, read as buttons once pulled past half way. */
    public static final int LEFT_TRIGGER = GLFW_GAMEPAD_BUTTON_LAST + 1;
    public static final int RIGHT_TRIGGER = GLFW_GAMEPAD_BUTTON_LAST + 2;
    public static final int BUTTON_COUNT = GLFW_GAMEPAD_BUTTON_LAST + 3;

    // Axes, by their GLFW index. Stick y is positive downward, as GLFW reports it.
    public static final int LEFT_X = GLFW_GAMEPAD_AXIS_LEFT_X;
    public static final int LEFT_Y = GLFW_GAMEPAD_AXIS_LEFT_Y;
    public static final int RIGHT_X = GLFW_GAMEPAD_AXIS_RIGHT_X;
    public static final int RIGHT_Y = GLFW_GAMEPAD_AXIS_RIGHT_Y;
    public static final int LEFT_TRIGGER_AXIS = GLFW_GAMEPAD_AXIS_LEFT_TRIGGER;
    public static final int RIGHT_TRIGGER_AXIS = GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER;
    public static final int AXIS_COUNT = GLFW_GAMEPAD_AXIS_LAST + 1;

    /** How far a stick must lean before it counts at all. */
    public static final float DEAD_ZONE = 0.2f;
    private static final float TRIGGER_PRESS = 0.5f;

    private final boolean[] buttons = new boolean[BUTTON_COUNT];
    private final boolean[] previous = new boolean[BUTTON_COUNT];
    private final float[] axes = new float[AXIS_COUNT];
    private GLFWGamepadState state;
    private boolean connected;
    private String name = "";

    /** Reads the first joystick that GLFW recognises as a gamepad; with none, everything reads released and centred. */
    void poll() {
        System.arraycopy(buttons, 0, previous, 0, BUTTON_COUNT);
        if (state == null) {
            state = GLFWGamepadState.create();
        }
        connected = false;
        for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_LAST; jid++) {
            if (glfwJoystickIsGamepad(jid) && glfwGetGamepadState(jid, state)) {
                connected = true;
                String found = glfwGetGamepadName(jid);
                name = found == null ? "Controller" : found;
                break;
            }
        }
        if (!connected) {
            java.util.Arrays.fill(buttons, false);
            java.util.Arrays.fill(axes, 0f);
            return;
        }
        ByteBuffer pressed = state.buttons();
        for (int b = 0; b <= GLFW_GAMEPAD_BUTTON_LAST; b++) {
            buttons[b] = pressed.get(b) == GLFW_PRESS;
        }
        FloatBuffer values = state.axes();
        for (int a = 0; a < AXIS_COUNT; a++) {
            axes[a] = values.get(a);
        }
        applyDeadZone(LEFT_X, LEFT_Y);
        applyDeadZone(RIGHT_X, RIGHT_Y);
        // Triggers rest at -1 and pull to 1; read them as 0 to 1.
        axes[LEFT_TRIGGER_AXIS] = (axes[LEFT_TRIGGER_AXIS] + 1f) * 0.5f;
        axes[RIGHT_TRIGGER_AXIS] = (axes[RIGHT_TRIGGER_AXIS] + 1f) * 0.5f;
        buttons[LEFT_TRIGGER] = axes[LEFT_TRIGGER_AXIS] > TRIGGER_PRESS;
        buttons[RIGHT_TRIGGER] = axes[RIGHT_TRIGGER_AXIS] > TRIGGER_PRESS;
    }

    /** A radial dead zone, rescaled so the stick still reaches 1 at full lean. */
    private void applyDeadZone(int xAxis, int yAxis) {
        float x = axes[xAxis];
        float y = axes[yAxis];
        float length = (float) Math.sqrt(x * x + y * y);
        if (length < DEAD_ZONE) {
            axes[xAxis] = 0f;
            axes[yAxis] = 0f;
            return;
        }
        float scaled = Math.min(1f, (length - DEAD_ZONE) / (1f - DEAD_ZONE));
        axes[xAxis] = x / length * scaled;
        axes[yAxis] = y / length * scaled;
    }

    /** Sets the state directly, for tests and for scripted input. */
    void set(int button, boolean down) {
        buttons[button] = down;
        connected = true;
    }

    void setAxis(int axis, float value) {
        axes[axis] = value;
        connected = true;
    }

    private final boolean[] virtualButtons = new boolean[BUTTON_COUNT];
    private final float[] virtualAxes = new float[AXIS_COUNT];

    /**
     * Layers on-screen controls over the pad for this frame: a virtual button held, a virtual stick leant. Applied after
     * {@link #wasUsed} is decided, so touching the screen never makes the game think a controller is in use.
     */
    void overlay(boolean[] pressedButtons, float[] leanedAxes) {
        for (int b = 0; b < BUTTON_COUNT; b++) {
            buttons[b] |= pressedButtons[b];
        }
        for (int a = 0; a < AXIS_COUNT; a++) {
            if (Math.abs(leanedAxes[a]) > Math.abs(axes[a])) {
                axes[a] = leanedAxes[a];
            }
        }
    }

    boolean[] virtualButtons() {
        return virtualButtons;
    }

    float[] virtualAxes() {
        return virtualAxes;
    }

    void beginFrame() {
        System.arraycopy(buttons, 0, previous, 0, BUTTON_COUNT);
    }

    public boolean isConnected() {
        return connected;
    }

    /** The pad's name as its maker gives it, e.g. "Xbox Controller", or "" with none connected. */
    public String name() {
        return connected ? name : "";
    }

    public boolean isDown(int button) {
        return valid(button) && buttons[button];
    }

    public boolean isPressed(int button) {
        return valid(button) && buttons[button] && !previous[button];
    }

    public boolean isReleased(int button) {
        return valid(button) && !buttons[button] && previous[button];
    }

    /** Marks this frame's press of a button as handled, so later readers don't see it. */
    public void consume(int button) {
        if (valid(button)) {
            previous[button] = buttons[button];
        }
    }

    /** A stick axis from -1 to 1 past the dead zone (y positive downward), or a trigger from 0 to 1. */
    public float axis(int axis) {
        return axis >= 0 && axis < AXIS_COUNT ? axes[axis] : 0f;
    }

    /** Whether anything on the pad was used this frame: a button pressed or a stick leant. */
    public boolean wasUsed() {
        for (int b = 0; b < BUTTON_COUNT; b++) {
            if (buttons[b] && !previous[b]) {
                return true;
            }
        }
        return Math.abs(axes[LEFT_X]) + Math.abs(axes[LEFT_Y]) + Math.abs(axes[RIGHT_X]) + Math.abs(axes[RIGHT_Y]) > 0.3f;
    }

    /** A button's name as a player reads it on the pad. */
    public static String label(int button) {
        return switch (button) {
            case A -> "A";
            case B -> "B";
            case X -> "X";
            case Y -> "Y";
            case LEFT_BUMPER -> "LB";
            case RIGHT_BUMPER -> "RB";
            case LEFT_TRIGGER -> "LT";
            case RIGHT_TRIGGER -> "RT";
            case BACK -> "View";
            case START -> "Menu";
            case GUIDE -> "Guide";
            case LEFT_STICK -> "L3";
            case RIGHT_STICK -> "R3";
            case DPAD_UP -> "D-pad up";
            case DPAD_RIGHT -> "D-pad right";
            case DPAD_DOWN -> "D-pad down";
            case DPAD_LEFT -> "D-pad left";
            default -> "Button " + button;
        };
    }

    /** The saved name of a button ("A", "LB", "DPAD_UP"), or -1 for none. */
    public static int code(String name) {
        for (int b = 0; b < BUTTON_COUNT; b++) {
            if (saveName(b).equalsIgnoreCase(name.trim())) {
                return b;
            }
        }
        return -1;
    }

    public static String saveName(int button) {
        return switch (button) {
            case DPAD_UP -> "DPAD_UP";
            case DPAD_RIGHT -> "DPAD_RIGHT";
            case DPAD_DOWN -> "DPAD_DOWN";
            case DPAD_LEFT -> "DPAD_LEFT";
            case BACK -> "BACK";
            case START -> "START";
            case GUIDE -> "GUIDE";
            default -> label(button);
        };
    }

    private static boolean valid(int button) {
        return button >= 0 && button < BUTTON_COUNT;
    }
}
