package org.zeronura.nulzs.gui;

import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.Gamepad;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.ui.Rect;

import java.util.List;

/**
 * On-screen controls for a touch screen: a stick on the left that walks, a drag anywhere on the right that turns the
 * camera, and buttons on the right — A, B, the menu and the map. They act exactly as a controller's would (they lean
 * its sticks and hold its buttons), so whatever a game does with a pad it does with a finger.
 *
 * <p>Shown only while the player is playing rather than in a menu — when the top scene has nothing to tap. In a menu
 * the fingers tap its buttons directly, as a mouse clicks them.
 *
 * <p>On a desktop, start with {@code -Dengine.touch=true} and the mouse is the finger, to try touch play without a
 * phone.
 */
public final class TouchControls {

    /** A round on-screen button and the pad button it holds. */
    private record Pad(String label, int button, float rightX, float bottomY, float radius) {
    }

    private static final Pad[] BUTTONS = {
            new Pad("A", Gamepad.A, 170f, 200f, 62f),
            new Pad("B", Gamepad.B, 60f, 290f, 48f),
            new Pad("Y", Gamepad.Y, 280f, 300f, 48f),
            new Pad("Menu", Gamepad.START, 120f, 470f, 44f),
            new Pad("Map", Gamepad.BACK, 240f, 470f, 44f),
    };
    private static final float STICK_RADIUS = 120f;
    private static final float CAMERA_SPEED = 0.012f;
    private static final Color FILL = new Color(1f, 1f, 1f, 0.16f);
    private static final Color HELD = new Color(1f, 1f, 1f, 0.38f);
    private static final Color RIM = new Color(1f, 1f, 1f, 0.45f);

    private final boolean[] held = new boolean[Gamepad.BUTTON_COUNT];
    private final float[] axes = new float[Gamepad.AXIS_COUNT];
    private int stickFinger = -1;
    private float stickX;
    private float stickY;
    private float knobX;
    private float knobY;
    private int cameraFinger = -1;
    private float cameraLastX;
    private float cameraLastY;
    private boolean visible;

    /**
     * Reads the fingers and feeds the virtual pad. Called by the engine after the GUI's navigation; {@code playing} is
     * whether the top scene drew nothing to tap last frame.
     */
    public void update(Input input, Gui gui, boolean playing) {
        java.util.Arrays.fill(held, false);
        java.util.Arrays.fill(axes, 0f);
        visible = input.isTouchMode() && playing;
        if (!visible) {
            stickFinger = -1;
            cameraFinger = -1;
            return;
        }
        float scale = gui.scale();
        float width = gui.width();
        float height = gui.height();
        List<Input.Touch> touches = input.touches();
        boolean stickStill = false;
        boolean cameraStill = false;
        for (Input.Touch touch : touches) {
            float x = touch.x() / scale;
            float y = touch.y() / scale;
            Pad pad = padAt(x, y, width, height);
            if (pad != null) {
                held[pad.button()] = true;
                continue;
            }
            if (touch.id() == stickFinger || touch.pressed() && stickFinger < 0 && x < width * 0.4f) {
                if (touch.pressed() && stickFinger < 0) {
                    stickFinger = touch.id();
                    stickX = x;
                    stickY = y;
                }
                stickStill = true;
                float dx = x - stickX;
                float dy = y - stickY;
                float length = (float) Math.sqrt(dx * dx + dy * dy);
                float clamped = Math.min(length, STICK_RADIUS);
                float nx = length > 0f ? dx / length : 0f;
                float ny = length > 0f ? dy / length : 0f;
                knobX = stickX + nx * clamped;
                knobY = stickY + ny * clamped;
                axes[Gamepad.LEFT_X] = nx * clamped / STICK_RADIUS;
                axes[Gamepad.LEFT_Y] = ny * clamped / STICK_RADIUS;
            } else if (touch.id() == cameraFinger || touch.pressed() && cameraFinger < 0) {
                if (touch.pressed() && cameraFinger < 0) {
                    cameraFinger = touch.id();
                    cameraLastX = x;
                    cameraLastY = y;
                }
                cameraStill = true;
                // A drag turns the camera by its length, through the right stick for one frame.
                axes[Gamepad.RIGHT_X] = Math.max(-1f, Math.min(1f, (x - cameraLastX) * CAMERA_SPEED * 8f));
                axes[Gamepad.RIGHT_Y] = Math.max(-1f, Math.min(1f, (y - cameraLastY) * CAMERA_SPEED * 8f));
                cameraLastX = x;
                cameraLastY = y;
            }
        }
        if (!stickStill) {
            stickFinger = -1;
        }
        if (!cameraStill) {
            cameraFinger = -1;
        }
        input.applyVirtualPad(held, axes);
    }

    private static Pad padAt(float x, float y, float width, float height) {
        for (Pad pad : BUTTONS) {
            float cx = width - pad.rightX();
            float cy = height - pad.bottomY();
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= pad.radius() * pad.radius()) {
                return pad;
            }
        }
        return null;
    }

    /** Draws the controls over the frame. */
    public void draw(Gui gui) {
        if (!visible) {
            return;
        }
        float width = gui.width();
        float height = gui.height();
        if (stickFinger >= 0) {
            disc(gui, stickX, stickY, STICK_RADIUS, FILL);
            disc(gui, knobX, knobY, 52f, HELD);
        } else {
            float cx = 230f;
            float cy = height - 230f;
            disc(gui, cx, cy, STICK_RADIUS, FILL);
            disc(gui, cx, cy, 52f, FILL);
        }
        for (Pad pad : BUTTONS) {
            float cx = width - pad.rightX();
            float cy = height - pad.bottomY();
            disc(gui, cx, cy, pad.radius(), held[pad.button()] ? HELD : FILL);
            ring(gui, cx, cy, pad.radius());
            float scale = pad.label().length() > 1 ? 2.2f : 3.4f;
            gui.textCentered(pad.label(), new Rect(cx - pad.radius(), cy - pad.radius(), 2f * pad.radius(), 2f * pad.radius()),
                    scale, Color.WHITE);
        }
    }

    private static void disc(Gui gui, float cx, float cy, float r, Color color) {
        var c = color.toVector4f();
        int segments = 28;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments;
            double a1 = (i + 1) * Math.PI * 2 / segments;
            gui.renderer().triangle(cx, cy, cx + r * (float) Math.cos(a0), cy + r * (float) Math.sin(a0),
                    cx + r * (float) Math.cos(a1), cy + r * (float) Math.sin(a1), c);
        }
    }

    private static void ring(Gui gui, float cx, float cy, float r) {
        var c = RIM.toVector4f();
        int segments = 28;
        float inner = r - 3f;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments;
            double a1 = (i + 1) * Math.PI * 2 / segments;
            float ox0 = cx + r * (float) Math.cos(a0), oy0 = cy + r * (float) Math.sin(a0);
            float ox1 = cx + r * (float) Math.cos(a1), oy1 = cy + r * (float) Math.sin(a1);
            float ix0 = cx + inner * (float) Math.cos(a0), iy0 = cy + inner * (float) Math.sin(a0);
            float ix1 = cx + inner * (float) Math.cos(a1), iy1 = cy + inner * (float) Math.sin(a1);
            gui.renderer().triangle(ox0, oy0, ox1, oy1, ix1, iy1, c);
            gui.renderer().triangle(ox0, oy0, ix1, iy1, ix0, iy0, c);
        }
    }
}
