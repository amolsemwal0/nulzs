package org.zeronura.engine.input;

import org.zeronura.engine.Window;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST;

/**
 * Keyboard, mouse and game controller state, sampled once per frame.
 *
 * <p>Polled rather than event-driven so every question has a per-frame answer: "is it down", "did it go
 * down this frame", "did it come up this frame". Those are what gameplay code actually asks, and answering
 * them from a snapshot means two systems asking in the same frame always agree.
 *
 * <p><b>Edge queries belong in {@code update}, not {@code fixedUpdate}.</b> A frame can run zero or several
 * fixed steps, so a press read there can be missed or seen twice. Read input in {@code update}, store the
 * intent, and apply it in {@code fixedUpdate}.
 */
public final class Input {

    public static final int KEY_COUNT = GLFW_KEY_LAST + 1;
    public static final int MOUSE_BUTTON_COUNT = GLFW_MOUSE_BUTTON_LAST + 1;

    private final boolean[] keys = new boolean[KEY_COUNT];
    private final boolean[] previousKeys = new boolean[KEY_COUNT];
    private final boolean[] buttons = new boolean[MOUSE_BUTTON_COUNT];
    private final boolean[] previousButtons = new boolean[MOUSE_BUTTON_COUNT];

    private final float[] cursor = new float[2];
    private float mouseX;
    private float mouseY;
    private float previousMouseX;
    private float previousMouseY;
    private double cursorX;
    private double cursorY;
    private float scroll;
    private String typedText = "";
    /** Keys that went down or auto-repeated this frame; see {@link #isKeyTyped}. */
    private java.util.BitSet typedKeys = new java.util.BitSet();

    private Window window;
    private final InputMap actions = new InputMap(this);
    private final Gamepad gamepad = new Gamepad();
    /** Keys a controller button stands in for, by button: B is Escape everywhere, the bumpers the arrow keys. */
    private final int[] padKeys = new int[Gamepad.BUTTON_COUNT];
    /** Buttons the GUI has taken this frame (A clicking a highlighted button), which actions then ignore. */
    private final boolean[] claimedPad = new boolean[Gamepad.BUTTON_COUNT];
    private Device device = Device.KEYBOARD_MOUSE;
    private boolean virtualPointer;
    private float realMouseX;
    private float realMouseY;

    /** What the player last used, which decides the prompts shown and whether the GUI highlights a button. */
    public enum Device { KEYBOARD_MOUSE, GAMEPAD, TOUCH }

    public Input() {
        java.util.Arrays.fill(padKeys, -1);
        padKeys[Gamepad.B] = Key.ESCAPE;
        padKeys[Gamepad.LEFT_BUMPER] = Key.LEFT;
        padKeys[Gamepad.RIGHT_BUMPER] = Key.RIGHT;
    }

    /** Samples the window. Called by the engine at the top of every frame. */
    public void poll(Window window) {
        this.window = window;
        beginFrame();
        for (int key = GLFW_KEY_SPACE; key < KEY_COUNT; key++) {
            keys[key] = window.isKeyDown(key);
        }
        for (int button = 0; button < MOUSE_BUTTON_COUNT; button++) {
            buttons[button] = window.isMouseButtonDown(button);
        }
        window.getCursorInFramebuffer(cursor);
        boolean mouseMoved = Math.abs(cursor[0] - realMouseX) + Math.abs(cursor[1] - realMouseY) > 2f;
        realMouseX = cursor[0];
        realMouseY = cursor[1];
        mouseX = realMouseX;
        mouseY = realMouseY;
        cursorX = window.getCursorX();
        cursorY = window.getCursorY();
        scroll = (float) window.takeScroll();
        typedText = window.takeTypedText();
        typedKeys = window.takeTypedKeys();
        java.util.List<Touch> fingers = window.touches();
        if (fingers != null) {
            setTouches(fingers);
        }

        if (!pendingText.isEmpty()) {
            typedText = typedText + pendingText;
            pendingText = "";
        }
        if (pendingBackspace) {
            keys[Key.BACKSPACE] = true;
            pendingBackspace = false;
        }
        gamepad.poll();
        playPadScript();
        playKeyScript();
        for (int button = 0; button < Gamepad.BUTTON_COUNT; button++) {
            if (padKeys[button] >= 0 && gamepad.isDown(button)) {
                keys[padKeys[button]] = true;
            }
        }
        if (gamepad.wasUsed()) {
            device = Device.GAMEPAD;
        } else if (mouseMoved || anyKeyPressed() || pressedMouseButton() >= 0 || scroll != 0f) {
            device = touchMode ? Device.TOUCH : Device.KEYBOARD_MOUSE;
        }
    }

    /**
     * Holds controller buttons and leans sticks from on-screen controls, this frame only. Called after {@link #poll}
     * and before anything reads input; see {@code TouchControls}.
     */
    public void applyVirtualPad(boolean[] heldButtons, float[] leanedAxes) {
        gamepad.overlay(heldButtons, leanedAxes);
        for (int button = 0; button < Gamepad.BUTTON_COUNT; button++) {
            if (padKeys[button] >= 0 && heldButtons[button]) {
                keys[padKeys[button]] = true;
            }
        }
    }

    void beginFrame() {
        System.arraycopy(keys, 0, previousKeys, 0, KEY_COUNT);
        System.arraycopy(buttons, 0, previousButtons, 0, MOUSE_BUTTON_COUNT);
        java.util.Arrays.fill(claimedPad, false);
        previousPointerVirtual = virtualPointer;
        virtualPointer = false;
        actions.endFrame();
        gamepad.beginFrame();
        previousMouseX = mouseX;
        previousMouseY = mouseY;
        scroll = 0f;
        typedText = "";
    }

    void setKey(int key, boolean down) {
        if (validKey(key)) {
            keys[key] = down;
        }
    }

    void setMouseButton(int button, boolean down) {
        if (validButton(button)) {
            buttons[button] = down;
        }
    }

    void setMousePosition(float x, float y) {
        mouseX = x;
        mouseY = y;
    }

    // --- Keyboard ---------------------------------------------------------------------------------------

    public boolean isKeyDown(int key) {
        return validKey(key) && keys[key];
    }

    /** True only on the frame the key went down. */
    public boolean isKeyPressed(int key) {
        return validKey(key) && keys[key] && !previousKeys[key];
    }

    /** True only on the frame the key came up. */
    public boolean isKeyReleased(int key) {
        return validKey(key) && !keys[key] && previousKeys[key];
    }

    /**
     * True on the frame a key went down and on every frame the system repeats it while held: what moves a text caret,
     * so holding an arrow or Backspace keeps going at the player's own repeat rate.
     */
    public boolean isKeyTyped(int key) {
        return isKeyPressed(key) || (validKey(key) && typedKeys.get(key) && keys[key]);
    }

    /** Whether either Control key is held. */
    public boolean ctrlDown() {
        return keys[Key.LEFT_CONTROL] || keys[Key.RIGHT_CONTROL];
    }

    /** Whether either Shift key is held. */
    public boolean shiftDown() {
        return keys[Key.LEFT_SHIFT] || keys[Key.RIGHT_SHIFT];
    }

    /** Whether either Alt key is held. */
    public boolean altDown() {
        return keys[Key.LEFT_ALT] || keys[Key.RIGHT_ALT];
    }

    /** The text on the system clipboard, or an empty string. */
    public String clipboard() {
        return window == null ? "" : window.clipboard();
    }

    public void setClipboard(String text) {
        if (window != null) {
            window.setClipboard(text);
        }
    }

    /** Sets the pointer's look over the window: a text caret over a field, a grip over a divider. */
    public void setCursorShape(Window.CursorShape shape) {
        if (window != null) {
            window.setCursorShape(shape);
        }
    }

    /** Whether any key went down this frame — "press any key". */
    public boolean anyKeyPressed() {
        for (int key = GLFW_KEY_SPACE; key < KEY_COUNT; key++) {
            if (keys[key] && !previousKeys[key]) {
                return true;
            }
        }
        return false;
    }

    /** The first key that went down this frame, or -1 if none did — for catching a key to bind. */
    public int pressedKey() {
        for (int key = GLFW_KEY_SPACE; key < KEY_COUNT; key++) {
            if (keys[key] && !previousKeys[key]) {
                return key;
            }
        }
        return -1;
    }

    /** The first mouse button that went down this frame, or -1 if none did. */
    public int pressedMouseButton() {
        for (int button = 0; button < MOUSE_BUTTON_COUNT; button++) {
            if (buttons[button] && !previousButtons[button]) {
                return button;
            }
        }
        return -1;
    }

    /**
     * Marks this frame's press or release of a key as handled, so later readers see neither edge.
     *
     * <p>For a key with two meanings in one frame: Escape releases a captured mouse <i>and</i> opens the
     * pause menu, and whichever acts first should stop the other seeing it.
     */
    public void consumeKey(int key) {
        if (validKey(key)) {
            previousKeys[key] = keys[key];
        }
    }

    // --- Mouse ------------------------------------------------------------------------------------------

    public boolean isMouseDown(int button) {
        return validButton(button) && buttons[button];
    }

    public boolean isMousePressed(int button) {
        return validButton(button) && buttons[button] && !previousButtons[button];
    }

    public boolean isMouseReleased(int button) {
        return validButton(button) && !buttons[button] && previousButtons[button];
    }

    public void consumeMouse(int button) {
        if (validButton(button)) {
            previousButtons[button] = buttons[button];
        }
    }

    /** Cursor x in framebuffer pixels, origin at the left. The space the GUI and 2D cameras use. */
    public float mouseX() {
        return mouseX;
    }

    /** Cursor y in framebuffer pixels, origin at the <b>top</b>. */
    public float mouseY() {
        return mouseY;
    }

    public float mouseDeltaX() {
        return mouseX - previousMouseX;
    }

    public float mouseDeltaY() {
        return mouseY - previousMouseY;
    }

    /**
     * Raw cursor position in window coordinates. Unbounded while the cursor is captured, which is what
     * mouse-look differences; see {@link org.zeronura.engine.MouseLook}.
     */
    public double cursorX() {
        return cursorX;
    }

    public double cursorY() {
        return cursorY;
    }

    /** Wheel notches this frame, positive away from the user. */
    public float scroll() {
        return scroll;
    }

    /** Text typed this frame, already mapped through the keyboard layout. For text fields. */
    public String typedText() {
        return typedText;
    }

    /** Hides and locks the cursor for mouse-look, or releases it. */
    public void setCursorCaptured(boolean captured) {
        if (window != null) {
            window.setCursorCaptured(captured);
        }
    }

    public boolean isCursorCaptured() {
        return window != null && window.isCursorCaptured();
    }

    // --- Controller -------------------------------------------------------------------------------------

    /** The first connected game controller; reads released and centred when there is none. */
    public Gamepad gamepad() {
        return gamepad;
    }

    /** What the player last used: keyboard and mouse, a controller, or touch. */
    public Device device() {
        return device;
    }

    public boolean usingGamepad() {
        return device == Device.GAMEPAD;
    }

    /**
     * Makes a controller button stand in for a key, so code that reads the key works with the pad unchanged; -1 for
     * none. By default B is Escape (step back) and the bumpers are the left and right arrow keys.
     */
    public void mapPadToKey(int button, int key) {
        padKeys[button] = key;
    }

    /** The key a controller button stands in for, or -1. */
    public int padKey(int button) {
        return padKeys[button];
    }

    /** Takes a controller button for this frame, so actions bound to it don't also see it (the GUI clicking with A). */
    public void claimPad(int button) {
        if (button >= 0 && button < claimedPad.length) {
            claimedPad[button] = true;
        }
    }

    public boolean isPadClaimed(int button) {
        return button >= 0 && button < claimedPad.length && claimedPad[button];
    }

    /**
     * Puts the pointer somewhere for this frame in place of the mouse, with the left button held or not: how a
     * controller works the GUI, by pointing at the highlighted button and clicking it. In framebuffer pixels.
     */
    public void setVirtualPointer(float x, float y, boolean leftDown) {
        virtualPointer = true;
        mouseX = x;
        mouseY = y;
        buttons[MouseButton.LEFT] = leftDown;
        if (!previousPointerVirtual) {
            // No drag from wherever the mouse was to the highlighted button.
            previousMouseX = x;
            previousMouseY = y;
        }
    }

    /** Whether the pointer this frame is the controller's, not the mouse. */
    public boolean isPointerVirtual() {
        return virtualPointer;
    }

    // Automation, like -Dengine.screenshot: -Dengine.padScript=120:DPAD_RIGHT,150:A holds each button for a few frames
    // from the frame given, as if a controller pressed it, so controller play can be tried and captured without one.
    private final String padScript = System.getProperty("engine.padScript", "");
    private long polls;

    private void playPadScript() {
        polls++;
        if (padScript.isEmpty()) {
            return;
        }
        for (String step : padScript.split(",")) {
            String[] parts = step.trim().split(":");
            if (parts.length != 2) {
                continue;
            }
            long frame = Long.parseLong(parts[0].trim());
            int button = Gamepad.code(parts[1]);
            if (button >= 0 && polls >= frame && polls < frame + 4) {
                gamepad.set(button, true);
            }
        }
    }

    // The same for the keyboard and mouse: -Dengine.keyScript=60:TAB,90:mouse@1200x600,95:click@1200x600,100:scroll@-3
    // presses a key (named as in Key) for the one frame given; mouse@XxY puts the pointer there, in framebuffer pixels,
    // from that frame on; click@XxY and rclick@XxY press a button there for a few frames; down@XxY holds the left button
    // from then until an up; scroll@N turns the wheel; hold@KEY holds a key down from then until a release@KEY, to walk
    // somewhere; text@WORD types a word into the text field being typed in. So an editor's tools and views, or a game's
    // world, can be tried and captured with no one at the keyboard.
    private final String keyScript = System.getProperty("engine.keyScript", "");
    private float scriptedMouseX = Float.NaN;
    private float scriptedMouseY;

    private void playKeyScript() {
        if (keyScript.isEmpty()) {
            return;
        }
        boolean held = false;
        java.util.Set<Integer> heldKeys = new java.util.HashSet<>();
        for (String step : keyScript.split(",")) {
            int colon = step.indexOf(':');
            if (colon < 0) {
                continue;
            }
            long frame = Long.parseLong(step.substring(0, colon).trim());
            String action = step.substring(colon + 1).trim();
            int at = action.indexOf('@');
            String verb = at < 0 ? action : action.substring(0, at);
            String argument = at < 0 ? "" : action.substring(at + 1);
            switch (verb) {
                case "up" -> held &= polls < frame;
                case "hold", "release" -> {
                    // A key held from its hold's frame until a release of it later in the script: the real key is
                    // left alone otherwise.
                    if (polls >= frame) {
                        try {
                            int key = Key.class.getField(argument).getInt(null);
                            if (verb.equals("hold")) {
                                heldKeys.add(key);
                            } else {
                                heldKeys.remove(key);
                            }
                        } catch (ReflectiveOperationException e) {
                            System.err.println("[Input] No key named " + argument + " in engine.keyScript");
                        }
                    }
                }
                case "mouse", "click", "rclick", "down" -> {
                    String[] xy = argument.split("x");
                    if (polls >= frame) {
                        scriptedMouseX = Float.parseFloat(xy[0]);
                        scriptedMouseY = Float.parseFloat(xy[1]);
                    }
                    if (verb.equals("down")) {
                        held |= polls >= frame;
                    } else if (!verb.equals("mouse") && polls >= frame && polls < frame + 3) {
                        buttons[verb.equals("click") ? MouseButton.LEFT : MouseButton.RIGHT] = true;
                    }
                }
                case "scroll" -> {
                    if (polls == frame) {
                        scroll += Float.parseFloat(argument);
                    }
                }
                // text@Ash types "Ash" into whatever text field has the keyboard, as the on-screen keyboard does.
                case "text" -> {
                    if (polls == frame) {
                        typedText = typedText + argument;
                    }
                }
                default -> {
                    if (polls == frame) {
                        try {
                            keys[Key.class.getField(verb).getInt(null)] = true;
                        } catch (ReflectiveOperationException e) {
                            System.err.println("[Input] No key named " + verb + " in engine.keyScript");
                        }
                    }
                }
            }
        }
        if (held) {
            buttons[MouseButton.LEFT] = true;
        }
        for (int key : heldKeys) {
            keys[key] = true;
        }
        if (!Float.isNaN(scriptedMouseX)) {
            mouseX = realMouseX = scriptedMouseX;
            mouseY = realMouseY = scriptedMouseY;
        }
    }

    private String pendingText = "";
    private boolean pendingBackspace;

    /** Types text as if from the keyboard, arriving next frame: for an on-screen keyboard. */
    public void injectText(String text) {
        pendingText = pendingText + text;
    }

    /** Presses Backspace for one frame, next frame: for an on-screen keyboard. */
    public void injectBackspace() {
        pendingBackspace = true;
    }

    /** Adds to this frame's scroll, for scrolling a list with a stick. */
    public void addScroll(float notches) {
        scroll += notches;
    }

    // --- Touch ------------------------------------------------------------------------------------------

    private boolean touchMode;
    private boolean previousPointerVirtual;
    private final java.util.List<Touch> touches = new java.util.ArrayList<>();

    /** One finger on the screen, in framebuffer pixels; {@code id} stays the same while it stays down. */
    public record Touch(int id, float x, float y, boolean pressed) {
    }

    /**
     * Treats the pointer as touch: the GUI and on-screen controls behave as on a phone. On a desktop the mouse is the
     * one finger (-Dengine.touch=true), so touch play can be tried without a device; a phone feeds real fingers in
     * through {@link #setTouches}.
     */
    public void setTouchMode(boolean touch) {
        touchMode = touch;
        if (touch) {
            device = Device.TOUCH;
        }
    }

    public boolean isTouchMode() {
        return touchMode;
    }

    /** The fingers down this frame. On a desktop in touch mode, the mouse while its left button is down. */
    public java.util.List<Touch> touches() {
        if (touchMode && touches.isEmpty() && buttons[MouseButton.LEFT] && !virtualPointer) {
            return java.util.List.of(new Touch(0, realMouseX, realMouseY, !previousButtons[MouseButton.LEFT]));
        }
        return java.util.Collections.unmodifiableList(touches);
    }

    /** Hands in the fingers down this frame, for a platform with a touch screen. */
    public void setTouches(java.util.List<Touch> fingers) {
        touches.clear();
        touches.addAll(fingers);
    }

    /** Named, rebindable actions layered over the raw keys. */
    public InputMap actions() {
        return actions;
    }

    private static boolean validKey(int key) {
        return key >= 0 && key < KEY_COUNT;
    }

    private static boolean validButton(int button) {
        return button >= 0 && button < MOUSE_BUTTON_COUNT;
    }
}
