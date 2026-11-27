package org.zeronura.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

public class Window {

    private final String title;
    private int width;
    private int height;
    private long handle;

    /** Set by the key callback, consumed by {@link #takePressedKey}. -1 when nothing is waiting. */
    private int lastKeyPressed = -1;

    /** Wheel notches since the last {@link #takeScroll}, summed by the scroll callback. */
    private double scrollAccumulator;

    /** Characters typed since the last {@link #takeTypedText}, already mapped through the keyboard layout. */
    private final StringBuilder typedText = new StringBuilder();

    /**
     * Keys that went down or auto-repeated since the last {@link #takeTypedKeys}: what a text editor moves its caret
     * and deletes by, since holding an arrow key should keep moving it at the system's repeat rate.
     */
    private final java.util.BitSet typedKeys = new java.util.BitSet();

    /** The pointer shapes made so far, by GLFW shape; see {@link #setCursorShape}. */
    private final java.util.Map<Integer, Long> cursors = new java.util.HashMap<>();
    private int cursorShape = GLFW_ARROW_CURSOR;

    /** The pointer's look over a window: the arrow, a text caret, a hand, or a resize grip. */
    public enum CursorShape {
        ARROW(GLFW_ARROW_CURSOR), TEXT(GLFW_IBEAM_CURSOR), HAND(GLFW_HAND_CURSOR), CROSSHAIR(GLFW_CROSSHAIR_CURSOR),
        RESIZE_HORIZONTAL(GLFW_HRESIZE_CURSOR), RESIZE_VERTICAL(GLFW_VRESIZE_CURSOR);

        final int glfw;

        CursorShape(int glfw) {
            this.glfw = glfw;
        }
    }

    /** Where the window was before going fullscreen, so leaving fullscreen can put it back. */
    private int windowedX;
    private int windowedY;
    private int windowedWidth;
    private int windowedHeight;
    private boolean vsync = true;

    public Window(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
        this.windowedWidth = width;
        this.windowedHeight = height;
    }

    /**
     * Sets the size the window will be created at. Only meaningful before {@link #init} — afterwards the
     * window exists and {@link #setWindowedSize} is the way to change it.
     *
     * <p>This exists so the settings can be read before the window is made: creating it at the default
     * size and immediately resizing works, but the player watches it happen.
     */
    public void setInitialSize(int width, int height) {
        this.width = width;
        this.height = height;
        this.windowedWidth = width;
        this.windowedHeight = height;
    }

    public void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        handle = glfwCreateWindow(width, height, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (handle == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }

        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> {
            width = w;
            height = h;
            glViewport(0, 0, w, h);
        });

        // The key callback records the last key *pressed*, and nothing else — it does not decide what any
        // key means. That distinction is why ESC-closes-the-window was removed from here: the game owns
        // its bindings (game.input.KeyBinding). Polling with glfwGetKey cannot serve a rebinding screen,
        // because it can only answer about keys you already thought to ask about, and the whole point
        // there is to catch whichever key the player chooses to press.
        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS) {
                lastKeyPressed = key;
            }
            if ((action == GLFW_PRESS || action == GLFW_REPEAT) && key >= 0) {
                typedKeys.set(key);
            }
        });

        // The wheel has to be a callback for the same reason: GLFW reports it as discrete events and
        // there is nothing to poll. Accumulated rather than latched, so a fast spin that lands several
        // notches inside one frame counts as several — latching the last event would silently throw away
        // most of a flick. Like the key callback, it records and decides nothing.
        glfwSetScrollCallback(handle, (win, xOffset, yOffset) -> scrollAccumulator += yOffset);

        // Text entry is a separate stream from key presses: the char callback delivers what the layout
        // produced (shifted symbols, dead keys, non-Latin scripts), which no key code can reconstruct.
        glfwSetCharCallback(handle, (win, codepoint) -> typedText.appendCodePoint(codepoint));

        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var pWidth = stack.mallocInt(1);
            var pHeight = stack.mallocInt(1);
            glfwGetWindowSize(handle, pWidth, pHeight);

            var vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(
                    handle,
                    (vidMode.width() - pWidth.get(0)) / 2,
                    (vidMode.height() - pHeight.get(0)) / 2
            );
        }

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        glViewport(0, 0, width, height);

        glfwSwapInterval(1);
        glfwShowWindow(handle);
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    /** Asks the loop to end after the current frame — what the pause menu's QUIT entry calls. */
    public void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    public boolean isKeyDown(int glfwKeyCode) {
        return glfwGetKey(handle, glfwKeyCode) == GLFW_PRESS;
    }

    /**
     * The last key pressed since this was last called, or -1 — for the rebinding screen, which has to
     * catch whichever key the player chooses rather than ask about keys it already knows.
     *
     * <p>Consuming rather than peeking: a key press means one binding, and leaving it in place would let
     * the same press land again on the next row the player opened.
     */
    public int takePressedKey() {
        int key = lastKeyPressed;
        lastKeyPressed = -1;
        return key;
    }

    /** Keys pressed or auto-repeated since this was last called, by GLFW key code; see {@link #typedKeys}. */
    public java.util.BitSet takeTypedKeys() {
        java.util.BitSet taken = (java.util.BitSet) typedKeys.clone();
        typedKeys.clear();
        return taken;
    }

    /** The text on the system clipboard, or an empty string. */
    public String clipboard() {
        String text = glfwGetClipboardString(handle);
        return text == null ? "" : text;
    }

    public void setClipboard(String text) {
        glfwSetClipboardString(handle, text == null ? "" : text);
    }

    /** Sets the pointer's look while it is over the window. Cheap to call every frame: it only changes on a change. */
    public void setCursorShape(CursorShape shape) {
        if (handle == MemoryUtil.NULL || shape.glfw == cursorShape) {
            return;
        }
        cursorShape = shape.glfw;
        long cursor = cursors.computeIfAbsent(shape.glfw, GLFW::glfwCreateStandardCursor);
        glfwSetCursor(handle, cursor);
    }

    /** Fills the screen with the window, keeping its title bar: what an editor opens as. */
    public void maximize() {
        if (!isFullscreen()) {
            glfwMaximizeWindow(handle);
        }
    }

    /** Takes back a close the player asked for with the window's own button, so a tool can ask about unsaved work first. */
    public void cancelClose() {
        glfwSetWindowShouldClose(handle, false);
    }

    /** Drops any pending key press — call when opening a capture prompt, so a stale press cannot bind. */
    public void clearPressedKey() {
        lastKeyPressed = -1;
    }

    /**
     * Wheel notches scrolled since this was last called, positive away from the user, and zero if the
     * wheel has not moved.
     *
     * <p>Consuming rather than peeking, exactly like {@link #takePressedKey}: a notch is one zoom step,
     * and leaving it in place would apply the same flick again on every later frame. <b>Whoever reads
     * this owns it</b> — there is one accumulator, so a second reader in the same frame gets nothing, and
     * anything that wants to ignore the wheel while something else has it must simply not call.
     */
    public double takeScroll() {
        double scrolled = scrollAccumulator;
        scrollAccumulator = 0.0;
        return scrolled;
    }

    /** Puts back scroll already taken, for a game that reads the window itself after {@code Input} has polled it. */
    public void returnScroll(double notches) {
        scrollAccumulator += notches;
    }

    /**
     * Switches between a borderless-fullscreen window on the primary monitor and a windowed one.
     *
     * <p>Fullscreen uses the monitor's <b>current</b> video mode rather than the configured window size:
     * asking for a mode the monitor does not have is how a game ends up on a black screen, and the
     * resolution setting is about the size of a <i>window</i>. Leaving fullscreen restores the position
     * and size the window had before, because GLFW does not remember them and a window that reappears at
     * (0,0) with the monitor's size looks like the toggle did nothing.
     */
    public void setFullscreen(boolean fullscreen) {
        if (fullscreen == isFullscreen()) {
            return;
        }
        if (fullscreen) {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var x = stack.mallocInt(1);
                var y = stack.mallocInt(1);
                glfwGetWindowPos(handle, x, y);
                windowedX = x.get(0);
                windowedY = y.get(0);
                var w = stack.mallocInt(1);
                var h = stack.mallocInt(1);
                glfwGetWindowSize(handle, w, h);
                windowedWidth = w.get(0);
                windowedHeight = h.get(0);
            }
            long monitor = glfwGetPrimaryMonitor();
            var mode = glfwGetVideoMode(monitor);
            if (mode == null) {
                return;
            }
            glfwSetWindowMonitor(handle, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
        } else {
            glfwSetWindowMonitor(handle, MemoryUtil.NULL, windowedX, windowedY,
                    windowedWidth, windowedHeight, GLFW_DONT_CARE);
        }
        // The swap interval is a property of the context's current drawable, and changing monitor
        // resets it on some drivers — so it is reapplied rather than assumed to have survived.
        setVsync(vsync);
    }

    /** The primary monitor's current resolution, or null if GLFW cannot say. */
    public int[] monitorSize() {
        var mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        return mode == null ? null : new int[] {mode.width(), mode.height()};
    }

    public boolean isFullscreen() {
        return glfwGetWindowMonitor(handle) != MemoryUtil.NULL;
    }

    /**
     * Resizes the window. A no-op while fullscreen, where the monitor decides — applying it there would
     * silently change the size the player sees on the way back out.
     */
    public void setWindowedSize(int newWidth, int newHeight) {
        if (newWidth == windowedWidth && newHeight == windowedHeight) {
            return; // applied every time settings change, and a resize would undo a window the player dragged
        }
        windowedWidth = newWidth;
        windowedHeight = newHeight;
        if (!isFullscreen()) {
            glfwSetWindowSize(handle, newWidth, newHeight);
        }
    }

    /** Frame pacing: on caps the frame rate to the display and removes tearing, off runs unthrottled. */
    public void setVsync(boolean enabled) {
        this.vsync = enabled;
        glfwSwapInterval(enabled ? 1 : 0);
    }

    public boolean isVsync() {
        return vsync;
    }

    public boolean isMouseButtonDown(int glfwButton) {
        return glfwGetMouseButton(handle, glfwButton) == GLFW_PRESS;
    }

    /**
     * Hides and locks the cursor to the window (or releases it). While captured, GLFW reports unbounded
     * virtual cursor coordinates, which is what mouse-look needs; raw motion is enabled when available so
     * the deltas skip the desktop's pointer acceleration curve.
     */
    public void setCursorCaptured(boolean captured) {
        glfwSetInputMode(handle, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, captured ? GLFW_TRUE : GLFW_FALSE);
        }
    }

    public boolean isCursorCaptured() {
        return glfwGetInputMode(handle, GLFW_CURSOR) == GLFW_CURSOR_DISABLED;
    }

    public double getCursorX() {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var x = stack.mallocDouble(1);
            glfwGetCursorPos(handle, x, null);
            return x.get(0);
        }
    }

    public double getCursorY() {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var y = stack.mallocDouble(1);
            glfwGetCursorPos(handle, null, y);
            return y.get(0);
        }
    }

    /**
     * The cursor in <b>framebuffer</b> pixels, which is the coordinate space the UI lays out in.
     *
     * <p>GLFW reports the cursor in window content coordinates, and on a scaled display those are not
     * framebuffer pixels — the UI would draw a button at one place and accept clicks somewhere else, by
     * exactly the display's scale factor. Everything here renders and hit-tests against the framebuffer,
     * so the conversion happens once, at the boundary.
     *
     * @param destination two floats, x then y
     */
    public void getCursorInFramebuffer(float[] destination) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var cursorX = stack.mallocDouble(1);
            var cursorY = stack.mallocDouble(1);
            glfwGetCursorPos(handle, cursorX, cursorY);

            var windowWidth = stack.mallocInt(1);
            var windowHeight = stack.mallocInt(1);
            glfwGetWindowSize(handle, windowWidth, windowHeight);

            // Guard the divide rather than assuming: a minimised window reports a zero size on some
            // platforms, and a NaN cursor would silently make every hit test false.
            float scaleX = windowWidth.get(0) > 0 ? (float) width / windowWidth.get(0) : 1f;
            float scaleY = windowHeight.get(0) > 0 ? (float) height / windowHeight.get(0) : 1f;

            destination[0] = (float) cursorX.get(0) * scaleX;
            destination[1] = (float) cursorY.get(0) * scaleY;
        }
    }

    /**
     * The fingers on a touch screen this frame, or null where there is none: always null here. The Android build's own
     * Window hands its fingers to {@code Input} through this; on the desktop, touch mode makes the mouse the finger.
     */
    public java.util.List<org.zeronura.engine.input.Input.Touch> touches() {
        return null;
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public void destroy() {
        cursors.values().forEach(GLFW::glfwDestroyCursor);
        cursors.clear();
        glfwFreeCallbacks(handle);
        glfwDestroyWindow(handle);
        glfwTerminate();
        var callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
    }

    /** Text typed since this was last called, or an empty string. Consuming, like {@link #takeScroll}. */
    public String takeTypedText() {
        if (typedText.isEmpty()) {
            return "";
        }
        String text = typedText.toString();
        typedText.setLength(0);
        return text;
    }

    public void setTitle(String newTitle) {
        glfwSetWindowTitle(handle, newTitle);
    }

    public long getHandle() {
        return handle;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
