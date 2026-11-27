package org.zeronura.nulzs.gui;

import org.joml.Vector4f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.Gamepad;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.ui.BitmapFont;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.ui.Typeface;
import org.zeronura.nulzs.ui.UIRenderer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Immediate-mode UI: menus, HUDs, dialogs, inventories, card-game controls, editor panels.
 *
 * <p>Widgets are function calls made every frame from {@code Scene.onGui}; a button is drawn and asked
 * whether it was clicked in the same line, so there is no widget tree to keep in sync with game state:
 * <pre>{@code
 * protected void onGui(Gui gui) {
 *     gui.panel(new Rect(40, 40, 400, 300));
 *     gui.text("Score: " + score, 60, 60);
 *     if (gui.button("Restart", new Rect(60, 260, 200, 60))) restart();
 * }
 * }</pre>
 *
 * <p>Coordinates are framebuffer pixels, origin top-left, y down. Anything drawn under the mouse marks the
 * pointer as taken, so world clicks behind a panel are not delivered to the scene.
 */
public final class Gui {

    /** Colours and sizes shared by every widget. Change fields to restyle the whole UI. */
    public static final class Style {
        public float textScale = 3f;
        public float padding = 16f;
        public Color text = Color.rgb(0xECEFF4);
        public Color mutedText = Color.rgb(0x9AA3B2);
        public Color panel = new Color(0.07f, 0.08f, 0.11f, 0.88f);
        public Color button = Color.rgb(0x3B4252);
        public Color buttonHover = Color.rgb(0x4C566A);
        public Color buttonPressed = Color.rgb(0x5E81AC);
        public Color accent = Color.rgb(0x88C0D0);
        public Color field = Color.rgb(0x232831);
        public Color outline = Color.rgb(0x4C566A);
        /** A switch's track while it is on. */
        public Color toggleOn = Color.rgb(0x88C0D0);
        /** How round the corners of buttons, fields and rows are; 0 for square. */
        public float radius = 12f;
        /** A game's own way of drawing {@link Gui#button}; null for the plain rounded one. */
        public ButtonPainter buttonPainter;
        /**
         * A sharp shadow under every line of text, a little below it, for words on a bright or changing background;
         * null for none.
         */
        public Color textShadow;
    }

    /** Draws a push button in a game's own look. The widget still does the hovering and clicking. */
    @FunctionalInterface
    public interface ButtonPainter {
        /**
         * @param hover whether the pointer is over it
         * @param held  whether it is being pressed
         */
        void paint(Gui gui, Rect rect, String label, boolean hover, boolean held);
    }

    /** Hands out equal-height rows down a column, for laying out menus without arithmetic. */
    public static final class Column {
        private final float x;
        private final float width;
        private final float rowHeight;
        private final float spacing;
        private float y;

        public Column(float x, float y, float width, float rowHeight, float spacing) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.rowHeight = rowHeight;
            this.spacing = spacing;
        }

        public Rect next() {
            return next(rowHeight);
        }

        public Rect next(float height) {
            Rect row = new Rect(x, y, width, height);
            y += height + spacing;
            return row;
        }

        public void skip(float amount) {
            y += amount;
        }

        public float y() {
            return y;
        }

        public float x() {
            return x;
        }

        public float width() {
            return width;
        }

        public float spacing() {
            return spacing;
        }
    }

    private final UIRenderer ui;
    private final Input input;
    private final Style style = new Style();
    private final Vector4f scratch = new Vector4f();

    public static final int REFERENCE_WIDTH = 1920;
    public static final int REFERENCE_HEIGHT = 1080;
    private static float scaleMultiplier = 1f;
    /** A scale that replaces fitting the screen, for tools laid out to the window; NaN to fit as usual. */
    private static float fixedScale = Float.NaN;

    private int width;
    private int height;
    private float uiScale = 1f;
    private boolean pointerOverUi;
    private boolean pointerOverUiLastFrame;
    private String active;
    private String focused;
    /** Whether a scene drew the {@link #focused} field this frame; see {@link #drawFrameOverlay}. */
    private boolean focusedDrawn;
    private float rotation;
    private float scale = 1f;
    private float squash = 1f;
    private float pivotX;
    private float pivotY;

    /**
     * What the GUI tells the game when the pointer comes onto something it can press, and when it presses it — for a
     * game's hover and click sounds. Every widget counts: buttons, toggles, {@link #clicked} areas and
     * {@link #hoveredButton}s, but not sliders, which are dragged rather than pressed.
     */
    public interface Feedback {
        void hovered();

        void clicked();
    }

    private Feedback feedback;
    /** Whether widgets drawn now are a scene's (between {@link #begin} and {@link #end}), not the overlay's. */
    private boolean inScene;
    /** What the top scene's pointer is over this frame, by widget id; each scene's {@link #begin} starts it afresh. */
    private final Set<String> pointedAt = new HashSet<>();
    private Set<String> pointedAtBefore = new HashSet<>();
    /** What the pointer was over when the left button went down, so a click is a press and release on the same thing. */
    private final Set<String> pressedOn = new HashSet<>();
    private float feedbackMouseX = Float.NaN;
    private float feedbackMouseY = Float.NaN;

    public Gui(Input input) {
        this.input = input;
        this.ui = new UIRenderer();
    }

    /** Sets what hearing about hovers and clicks; null for nothing. */
    public void setFeedback(Feedback feedback) {
        this.feedback = feedback;
    }

    public Style style() {
        return style;
    }

    /** Called by the engine once per frame, before any scene draws its UI. */
    public void newFrame() {
        giveFeedback();
        lastTargets = new ArrayList<>(targets);
        targets.clear();
        pointerOverUiLastFrame = pointerOverUi;
        pointerOverUi = false;
        if (!input.isMouseDown(MouseButton.LEFT) && !input.isMouseReleased(MouseButton.LEFT)) {
            active = null;
        }
    }

    public void begin(int framebufferWidth, int framebufferHeight) {
        uiScale = uiScale(framebufferWidth, framebufferHeight);
        width = Math.round(framebufferWidth / uiScale);
        height = Math.round(framebufferHeight / uiScale);
        rotation = 0f;
        scale = 1f;
        squash = 1f;
        // Only the scene drawn last — the one on top — can be moved around with a controller.
        targets.clear();
        hoverCount = 0;
        pointedAt.clear();
        inScene = true;
        ui.begin((float) width, (float) height);
    }

    /**
     * Reports last frame's hover and click, judged on the top scene alone, so a menu under a dialogue says nothing.
     *
     * <p>A hover is the pointer moving onto something it was not over; a widget appearing under a still pointer, or
     * the ids of {@link #hoveredButton}s shifting as a layout changes, is not one. At most one of each per frame.
     */
    private void giveFeedback() {
        float x = input.mouseX();
        float y = input.mouseY();
        boolean moved = x != feedbackMouseX || y != feedbackMouseY;
        feedbackMouseX = x;
        feedbackMouseY = y;
        if (feedback != null) {
            if (moved && !pointedAtBefore.containsAll(pointedAt)) {
                feedback.hovered();
            }
            if (input.isMouseReleased(MouseButton.LEFT) && !pressedOn.isEmpty()
                    && pointedAt.stream().anyMatch(pressedOn::contains)) {
                feedback.clicked();
            }
        }
        if (input.isMousePressed(MouseButton.LEFT)) {
            pressedOn.clear();
            pressedOn.addAll(pointedAt);
        } else if (!input.isMouseDown(MouseButton.LEFT)) {
            pressedOn.clear();
        }
        pointedAtBefore = new HashSet<>(pointedAt);
    }

    /**
     * How many screen pixels one GUI unit covers. Layouts are written for a {@value #REFERENCE_WIDTH} by
     * {@value #REFERENCE_HEIGHT} screen, so on any other the whole GUI is scaled to fit it: a Steam Deck's 1280x800 draws
     * the same layout two thirds the size (and a little taller, its screen being 16:10), a 4K screen twice the size.
     * {@link #setScaleMultiplier} lets the player make it bigger or smaller than that.
     */
    public static float uiScale(int framebufferWidth, int framebufferHeight) {
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return 1f;
        }
        if (!Float.isNaN(fixedScale)) {
            return fixedScale;
        }
        float fit = Math.min(framebufferWidth / (float) REFERENCE_WIDTH, framebufferHeight / (float) REFERENCE_HEIGHT);
        return Math.max(0.25f, fit * scaleMultiplier);
    }

    /** The player's own size for the GUI on top of fitting the screen: 1 for as laid out, 1.25 a quarter bigger. */
    public static void setScaleMultiplier(float multiplier) {
        scaleMultiplier = Math.clamp(multiplier, 0.5f, 2f);
    }

    public static float scaleMultiplier() {
        return scaleMultiplier;
    }

    /**
     * Draws the GUI at a set number of screen pixels per unit, whatever the window's size, instead of fitting a 1080p
     * layout to it: for an editor, whose panels share out the window rather than scale with it. NaN goes back to fitting.
     */
    public static void setFixedScale(float pixelsPerUnit) {
        fixedScale = Float.isNaN(pixelsPerUnit) ? Float.NaN : Math.clamp(pixelsPerUnit, 0.5f, 4f);
    }

    public static float fixedScale() {
        return fixedScale;
    }

    /** Screen pixels per GUI unit this frame; see {@link #uiScale(int, int)}. */
    public float scale() {
        return uiScale;
    }

    /** The pointer's x in GUI units — where {@link #hovered} sees it. */
    public float mouseX() {
        return input.mouseX() / uiScale;
    }

    /** The pointer's y in GUI units. */
    public float mouseY() {
        return input.mouseY() / uiScale;
    }

    public void end() {
        inScene = false;
        ui.end();
    }

    /**
     * Draws what goes over every scene, once a frame after they have all drawn: the on-screen keyboard while typing
     * with a controller or finger, and the controller's highlight. Called by the engine inside
     * {@link #beginOverlay}.
     */
    public void drawFrameOverlay() {
        resetRotation();
        if (!focusedDrawn) {
            // The field being typed in is gone — its scene closed on Enter before drawing it again, say — and nothing
            // left on screen can give the keys back, so gameplay would ignore them for good.
            focused = null;
        }
        focusedDrawn = false;
        keyboardShown = focused != null && (input.usingGamepad() || input.isTouchMode());
        if (keyboardShown) {
            // Typing with a controller or a finger: the keys take over from the scene's buttons.
            targets.clear();
            keyboard.draw(this, focusedText);
        }
        if (input.usingGamepad() && navFocus != null) {
            for (Target target : targets) {
                if (target.id().equals(navFocus)) {
                    float pulse = 0.65f + 0.35f * (float) Math.sin(System.nanoTime() / 1.6e8);
                    Rect ring = new Rect(target.x() - 5f, target.y() - 5f, target.w() + 10f, target.h() + 10f);
                    outline(ring, 4f, style.accent.withAlpha(pulse));
                    break;
                }
            }
        }
    }

    // --- Controller navigation ------------------------------------------------------------------------

    /** Something a controller can move to: a widget's rectangle on screen, in GUI units. */
    record Target(String id, float x, float y, float w, float h, boolean slider) {
        float centerX() {
            return x + w * 0.5f;
        }

        float centerY() {
            return y + h * 0.5f;
        }
    }

    private static final float REPEAT_DELAY = 0.35f;
    private static final float REPEAT_RATE = 0.11f;

    private final List<Target> targets = new ArrayList<>();
    private List<Target> lastTargets = new ArrayList<>();
    private final OnScreenKeyboard keyboard = new OnScreenKeyboard();
    private String navFocus;
    private final java.util.Set<String> navIds = new java.util.HashSet<>();
    private float navX = -1f;
    private float navY = -1f;
    private int heldDirection = -1;
    private float repeatTimer;
    private int sliderStep;
    private String focusedText = "";
    /** When a Backspace held down in a {@link #textArea} next takes off a character, on the system clock. */
    private long backspaceRepeatAt;
    private boolean keyboardShown;

    /**
     * Whether the pointer is over a rectangle that works as a button the caller draws and clicks itself; it is also
     * something a controller can highlight and press. {@link #hovered} for a test that isn't a button (a list to
     * scroll, a tooltip).
     */
    public boolean hoveredButton(Rect rect) {
        target("hover:" + hoverCount++, rect, false);
        return hovered(rect);
    }

    private int hoverCount;

    /**
     * Makes a rectangle something a controller can move to and press with A, for a control that draws itself and
     * reads clicks some other way than {@link #clicked}. Buttons, toggles, sliders, text fields and {@link #clicked}
     * areas already are.
     */
    public void focusable(String id, Rect rect) {
        target("focus:" + id, rect, false);
    }

    private void target(String id, Rect rect, boolean slider) {
        if (inScene && !slider && hovered(rect)) {
            pointedAt.add(id);
        }
        if (rotation == 0f && scale == 1f && squash == 1f) {
            targets.add(new Target(id, rect.x(), rect.y(), rect.width(), rect.height(), slider));
            return;
        }
        // Where the turned, scaled rectangle lands on screen: the box around its four corners.
        float cos = (float) Math.cos(rotation);
        float sin = (float) Math.sin(rotation);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float[][] corners = {{rect.x(), rect.y()}, {rect.x() + rect.width(), rect.y()},
                {rect.x(), rect.y() + rect.height()}, {rect.x() + rect.width(), rect.y() + rect.height()}};
        for (float[] corner : corners) {
            float u = (corner[0] - pivotX) * scale * squash;
            float v = (corner[1] - pivotY) * scale;
            float x = pivotX + u * cos - v * sin;
            float y = pivotY + u * sin + v * cos;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        targets.add(new Target(id, minX, minY, maxX - minX, maxY - minY, slider));
    }

    /** Whether a controller has this widget highlighted ({@link #focusable}'s id, or a button's label). */
    public boolean isHighlighted(String id) {
        return input.usingGamepad() && navFocus != null
                && (navFocus.equals("focus:" + id) || navFocus.equals("button:" + id) || navFocus.equals("click:" + id));
    }

    /**
     * Moves the controller's highlight with the D-pad and points the GUI at it, with A as the left mouse button: so
     * every button, card and field works with a controller without knowing about one. Called by the engine once a
     * frame after input is read, before scenes update; it works from the widgets the top scene drew last frame.
     * Does nothing while the player is on the mouse, or with nothing on screen to press — then A is the game's own
     * (talk, confirm).
     */
    public void navigate(float dt) {
        sliderStep = 0;
        if (!input.usingGamepad() || lastTargets.isEmpty()) {
            heldDirection = -1;
            return;
        }
        Gamepad pad = input.gamepad();
        Target current = find(navFocus);
        if (current == null) {
            // The same screen laid out afresh (cards sliding, a list scrolled) keeps the highlight where it was; a new
            // screen — none of the old buttons left — starts on its first button, which a screen draws as its main one.
            boolean sameScreen = navX >= 0f && lastTargets.stream().anyMatch(t -> navIds.contains(t.id()));
            current = sameScreen ? nearest(navX, navY) : lastTargets.getFirst();
        }
        int direction = heldDirection();
        int move = -1;
        if (direction >= 0 && direction != heldDirection) {
            move = direction;
            repeatTimer = REPEAT_DELAY;
        } else if (direction >= 0) {
            repeatTimer -= dt;
            if (repeatTimer <= 0f) {
                move = direction;
                repeatTimer = REPEAT_RATE;
            }
        }
        heldDirection = direction;
        if (move >= 0) {
            if (current.slider() && (move == 1 || move == 3)) {
                sliderStep = move == 1 ? 1 : -1;
            } else {
                Target next = best(current, move);
                if (next != null) {
                    current = next;
                }
            }
        }
        navFocus = current.id();
        navX = current.centerX();
        navY = current.centerY();
        navIds.clear();
        for (Target target : lastTargets) {
            navIds.add(target.id());
        }
        // The right stick scrolls whatever list the highlight is over.
        float stick = pad.axis(Gamepad.RIGHT_Y);
        if (stick != 0f) {
            input.addScroll(-stick * dt * 10f);
        }
        boolean held = pad.isDown(Gamepad.A);
        if (held || pad.isReleased(Gamepad.A)) {
            input.claimPad(Gamepad.A);
        }
        input.setVirtualPointer(navX * uiScale, navY * uiScale, held);
    }

    /** 0 up, 1 right, 2 down, 3 left, or -1: the D-pad, or the left stick pushed most of the way. */
    private int heldDirection() {
        Gamepad pad = input.gamepad();
        if (pad.isDown(Gamepad.DPAD_UP)) {
            return 0;
        }
        if (pad.isDown(Gamepad.DPAD_RIGHT)) {
            return 1;
        }
        if (pad.isDown(Gamepad.DPAD_DOWN)) {
            return 2;
        }
        if (pad.isDown(Gamepad.DPAD_LEFT)) {
            return 3;
        }
        float x = pad.axis(Gamepad.LEFT_X);
        float y = pad.axis(Gamepad.LEFT_Y);
        if (Math.max(Math.abs(x), Math.abs(y)) < 0.7f) {
            return -1;
        }
        return Math.abs(x) > Math.abs(y) ? (x > 0f ? 1 : 3) : (y > 0f ? 2 : 0);
    }

    private Target find(String id) {
        if (id == null) {
            return null;
        }
        for (Target target : lastTargets) {
            if (target.id().equals(id)) {
                return target;
            }
        }
        return null;
    }

    private Target nearest(float x, float y) {
        Target best = lastTargets.getFirst();
        float bestDistance = Float.MAX_VALUE;
        for (Target target : lastTargets) {
            float dx = target.centerX() - x;
            float dy = target.centerY() - y;
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = target;
            }
        }
        return best;
    }

    /**
     * The target to move to from {@code from} in a direction: the closest one ahead, favouring those in line with it —
     * a button straight below beats a nearer one off to the side.
     */
    private Target best(Target from, int direction) {
        float dirX = direction == 1 ? 1f : direction == 3 ? -1f : 0f;
        float dirY = direction == 2 ? 1f : direction == 0 ? -1f : 0f;
        Target best = null;
        float bestScore = Float.MAX_VALUE;
        for (Target target : lastTargets) {
            if (target.id().equals(from.id())) {
                continue;
            }
            float dx = target.centerX() - from.centerX();
            float dy = target.centerY() - from.centerY();
            float along = dx * dirX + dy * dirY;
            if (along <= 1f) {
                continue;
            }
            // How far off the line: nothing when the two overlap across it.
            float across;
            if (dirX != 0f) {
                float overlap = Math.min(target.y() + target.h(), from.y() + from.h()) - Math.max(target.y(), from.y());
                across = overlap > 0f ? 0f : Math.abs(dy);
            } else {
                float overlap = Math.min(target.x() + target.w(), from.x() + from.w()) - Math.max(target.x(), from.x());
                across = overlap > 0f ? 0f : Math.abs(dx);
            }
            float score = along + across * 2.5f;
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }
        return best;
    }

    /** Whether the top scene drew nothing to press last frame: the player is playing, not in a menu. */
    public boolean nothingToPress() {
        return lastTargets.isEmpty();
    }

    /** Starts drawing over everything the scenes drew, keeping their buttons for the controller. */
    public void beginOverlay(int framebufferWidth, int framebufferHeight) {
        uiScale = uiScale(framebufferWidth, framebufferHeight);
        width = Math.round(framebufferWidth / uiScale);
        height = Math.round(framebufferHeight / uiScale);
        resetRotation();
        ui.begin((float) width, (float) height);
    }

    public void endOverlay() {
        ui.end();
    }

    /** Whether the GUI is closing an on-screen keyboard's text field, for {@link OnScreenKeyboard}. */
    void finishTyping() {
        focused = null;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    /** Whether the UI has the mouse — the scene ignores world clicks while this is true. */
    public boolean wantsPointer() {
        return pointerOverUi || pointerOverUiLastFrame;
    }

    /** Whether a text field is taking keyboard input, so gameplay should ignore typing. */
    public boolean hasKeyboardFocus() {
        return focused != null;
    }

    public void clearFocus() {
        focused = null;
    }

    /** Gives a {@link #textField} keyboard focus, as a click on it would, so typing goes straight into it. */
    public void focusField(String id) {
        focused = "field:" + id;
    }

    private boolean pointerBlocked;

    /**
     * While set, nothing drawn counts as under the pointer: the panels of an editor under an open menu or dialog, which
     * are drawn first and must not take the click meant for what covers them.
     */
    public void setPointerBlocked(boolean blocked) {
        pointerBlocked = blocked;
    }

    public boolean pointerBlocked() {
        return pointerBlocked;
    }

    /**
     * Keeps what is drawn inside {@code rect} until {@link #popClip}, nesting inside any clip already in force; the
     * pointer only counts as over a widget in the part of it left showing, so a row scrolled out of a list can't be
     * clicked through the panel's edge.
     */
    public void pushClip(Rect rect) {
        ui.pushClip(rect.x(), rect.y(), rect.width(), rect.height());
    }

    public void popClip() {
        ui.popClip();
    }

    /** Drops every clip and turn in force: what drawing that failed part-way left behind. */
    public void resetClips() {
        ui.clearClips();
        resetRotation();
    }

    /** Whether the pointer is inside the clip in force (or nothing is clipped). */
    public boolean pointerInClip() {
        float[] clip = ui.clip();
        if (clip == null) {
            return true;
        }
        float x = mouseX();
        float y = mouseY();
        return x >= clip[0] && x < clip[2] && y >= clip[1] && y < clip[3];
    }

    /** A straight line {@code thickness} units wide. */
    public void line(float x1, float y1, float x2, float y2, float thickness, Color color) {
        ui.line(x1, y1, x2, y2, thickness, color.toVector4f(scratch));
    }

    /** A filled triangle. */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Color color) {
        ui.triangle(x1, y1, x2, y2, x3, y3, color.toVector4f(scratch));
    }

    /** Whether the mouse is over a rectangle — in the turned and scaled frame, while {@link #transform} is in effect. */
    public boolean hovered(Rect rect) {
        if (pointerBlocked || !pointerInClip()) {
            return false;
        }
        float x = mouseX();
        float y = mouseY();
        if (rotation != 0f || scale != 1f || squash != 1f) {
            float cos = (float) Math.cos(rotation);
            float sin = (float) Math.sin(rotation);
            float dx = x - pivotX;
            float dy = y - pivotY;
            x = pivotX + (dx * cos + dy * sin) / (scale * Math.max(1e-4f, squash));
            y = pivotY + (-dx * sin + dy * cos) / scale;
        }
        return rect.contains(x, y);
    }

    /**
     * Turns everything drawn after it by {@code radians} (clockwise) about a pivot, until {@link #resetRotation}.
     * Widgets inside are laid out upright as usual, and their hover and clicks follow the turned shape.
     */
    public void rotate(float radians, float pivotX, float pivotY) {
        transform(radians, 1f, pivotX, pivotY);
    }

    /** Like {@link #rotate}, and also scales everything drawn about the pivot, text included — a card held up to read. */
    public void transform(float radians, float scale, float pivotX, float pivotY) {
        transform(radians, scale, 1f, pivotX, pivotY);
    }

    /** Like {@link #transform(float, float, float, float)}, also squeezed sideways by {@code squash}: a card mid-flip. */
    public void transform(float radians, float scale, float squash, float pivotX, float pivotY) {
        rotation = radians;
        this.scale = scale;
        this.squash = squash;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        ui.setTransform(radians, scale, squash, pivotX, pivotY);
    }

    /** Ends {@link #rotate} or {@link #transform}. */
    public void resetRotation() {
        rotation = 0f;
        scale = 1f;
        squash = 1f;
        ui.resetRotation();
    }

    /** A rectangle of the given size centred on a point. */
    public static Rect centered(float centerX, float centerY, float rectWidth, float rectHeight) {
        return new Rect(centerX - rectWidth * 0.5f, centerY - rectHeight * 0.5f, rectWidth, rectHeight);
    }

    // --- Drawing ----------------------------------------------------------------------------------------

    public void rect(float x, float y, float rectWidth, float rectHeight, Color color) {
        ui.rect(x, y, rectWidth, rectHeight, color.toVector4f(scratch));
    }

    public void rect(Rect rect, Color color) {
        rect(rect.x(), rect.y(), rect.width(), rect.height(), color);
    }

    public void outline(Rect rect, float thickness, Color color) {
        ui.rectOutline(rect.x(), rect.y(), rect.width(), rect.height(), thickness, color.toVector4f(scratch));
    }

    /** A background panel. Blocks world clicks beneath it. */
    public void panel(Rect rect) {
        panel(rect, style.panel);
    }

    public void panel(Rect rect, Color color) {
        rect(rect, color);
        if (hovered(rect)) {
            pointerOverUi = true;
        }
    }

    public void image(Texture texture, Rect rect, Color tint) {
        ui.sprite(texture, rect.x(), rect.y(), rect.width(), rect.height(), tint.toVector4f(scratch));
    }

    /** A part of a texture, from (u0, v0) at its top-left to (u1, v1), as the image is stored. */
    public void image(Texture texture, Rect rect, float u0, float v0, float u1, float v1, Color tint) {
        ui.sprite(texture, rect.x(), rect.y(), rect.width(), rect.height(), u0, v0, u1, v1, tint.toVector4f(scratch));
    }

    /** A rectangle with rounded corners and a soft edge. */
    public void rounded(Rect rect, float radius, Color color) {
        rounded(rect, radius, color, color);
    }

    /** A rounded rectangle shaded from {@code top} down to {@code bottom}. */
    public void rounded(Rect rect, float radius, Color top, Color bottom) {
        ui.roundedRect(rect.x(), rect.y(), rect.width(), rect.height(), radius, top.toVector4f(new Vector4f()),
                bottom.toVector4f(new Vector4f()));
    }

    /** A rounded rectangle's border, {@code thickness} wide inside its edge. */
    public void roundedOutline(Rect rect, float radius, float thickness, Color color) {
        ui.roundedOutline(rect.x(), rect.y(), rect.width(), rect.height(), radius, thickness, color.toVector4f(scratch));
    }

    /** A soft glow or shadow round a rounded rectangle, fading out {@code spread} beyond its edge. */
    public void glow(Rect rect, float radius, float spread, Color color) {
        ui.roundedGlow(rect.x(), rect.y(), rect.width(), rect.height(), radius, spread, color.toVector4f(scratch));
    }

    /** A filled circle with a soft edge. */
    public void circle(float centerX, float centerY, float radius, Color color) {
        ui.circle(centerX, centerY, radius, color.toVector4f(scratch));
    }

    /**
     * Draws everything from here to {@link #endSilhouette} in one flat colour, keeping only each shape's own opacity:
     * the outline of a picture made of several colours, for a drop shadow under it.
     */
    public void silhouette(Color color) {
        ui.setFlatColor(color.toVector4f(new Vector4f()));
    }

    /** Ends a {@link #silhouette}: colours are their own again. */
    public void endSilhouette() {
        ui.setFlatColor(null);
    }

    /**
     * A filled convex shape with a soft edge, shaded from {@code top} at its highest point down to {@code bottom}:
     * {@code outline} is its corners as x, y pairs, clockwise on screen.
     */
    public void polygon(float[] outline, Color top, Color bottom) {
        ui.polygon(outline, top.toVector4f(new Vector4f()), bottom.toVector4f(new Vector4f()));
    }

    /** As {@link #polygon}, shaded across instead: from {@code left} at its leftmost point to {@code right} at its rightmost. */
    public void polygonAcross(float[] outline, Color left, Color right) {
        ui.polygonAcross(outline, left.toVector4f(new Vector4f()), right.toVector4f(new Vector4f()));
    }

    /** A convex shape's border, {@code thickness} wide inside its edge, shaded from {@code top} down to {@code bottom}. */
    public void polygonOutline(float[] outline, float thickness, Color top, Color bottom) {
        ui.polygonOutline(outline, thickness, top.toVector4f(new Vector4f()), bottom.toVector4f(new Vector4f()));
    }

    /** As {@link #polygonOutline}, shaded across instead: from {@code left} at its leftmost point to {@code right} at its rightmost. */
    public void polygonOutlineAcross(float[] outline, float thickness, Color left, Color right) {
        ui.polygonOutlineAcross(outline, thickness, left.toVector4f(new Vector4f()), right.toVector4f(new Vector4f()));
    }

    /** A soft glow or shadow round a convex shape, fading out {@code spread} beyond its edge. */
    public void polygonGlow(float[] outline, float spread, Color color) {
        ui.polygonGlow(outline, spread, color.toVector4f(scratch));
    }

    // --- Text -------------------------------------------------------------------------------------------

    /** Draws text with its top-left at (x, y) in the default style. Returns the x after the last glyph. */
    public float text(String text, float x, float y) {
        return text(text, x, y, style.textScale, style.text);
    }

    public float text(String text, float x, float y, float scale, Color color) {
        if (style.textShadow != null) {
            ui.text(text, x, y + shadowDrop(scale), scale, shadowUnder(color));
        }
        return ui.text(text, x, y, scale, color.toVector4f(scratch));
    }

    /** Text set in one of the UI's faces. Returns the x after the last glyph. */
    public float text(String text, float x, float y, float scale, Color color, Typeface face) {
        if (style.textShadow != null) {
            ui.text(text, x, y + shadowDrop(scale), scale, shadowUnder(color), face);
        }
        return ui.text(text, x, y, scale, color.toVector4f(scratch), face);
    }

    /** How far under a line of text the style's shadow falls. */
    private static float shadowDrop(float scale) {
        return Math.max(1.5f, Math.min(2.5f, scale));
    }

    /** The style's text shadow for words in {@code color}: no stronger than the words themselves. */
    private Vector4f shadowUnder(Color color) {
        return style.textShadow.withAlpha(style.textShadow.a() * color.a()).toVector4f(scratch);
    }

    /** Text in a face shaded from {@code top} at the top of its letters to {@code bottom} at their foot. */
    public float text(String text, float x, float y, float scale, Color top, Color bottom, Typeface face) {
        return ui.text(text, x, y, scale, top.toVector4f(new Vector4f()), bottom.toVector4f(new Vector4f()), face);
    }

    /** Text in a face, centred in a rectangle both ways. */
    public void textCentered(String text, Rect rect, float scale, Color color, Typeface face) {
        float y = rect.centerY() - textHeight(scale) * 0.5f;
        if (style.textShadow != null) {
            ui.textCentered(text, rect.centerX(), y + shadowDrop(scale), scale, shadowUnder(color), face);
        }
        ui.textCentered(text, rect.centerX(), y, scale, color.toVector4f(scratch), face);
    }

    public void textRight(String text, float rightX, float y, float scale, Color color, Typeface face) {
        if (style.textShadow != null) {
            ui.textRightAligned(text, rightX, y + shadowDrop(scale), scale, shadowUnder(color), face);
        }
        ui.textRightAligned(text, rightX, y, scale, color.toVector4f(scratch), face);
    }

    public float textWidth(String text, float scale, Typeface face) {
        return BitmapFont.widthOf(text, scale, face);
    }

    public void textCentered(String text, Rect rect) {
        textCentered(text, rect, style.textScale, style.text);
    }

    /** Text centred in a rectangle both ways. */
    public void textCentered(String text, Rect rect, float scale, Color color) {
        float y = rect.centerY() - textHeight(scale) * 0.5f;
        if (style.textShadow != null) {
            ui.textCentered(text, rect.centerX(), y + shadowDrop(scale), scale, shadowUnder(color));
        }
        ui.textCentered(text, rect.centerX(), y, scale, color.toVector4f(scratch));
    }

    public void textRight(String text, float rightX, float y, float scale, Color color) {
        if (style.textShadow != null) {
            ui.textRightAligned(text, rightX, y + shadowDrop(scale), scale, shadowUnder(color));
        }
        ui.textRightAligned(text, rightX, y, scale, color.toVector4f(scratch));
    }

    public float textWidth(String text, float scale) {
        return BitmapFont.widthOf(text, scale);
    }

    public float textHeight(float scale) {
        return BitmapFont.heightOf(scale);
    }

    // --- Widgets ----------------------------------------------------------------------------------------

    /** A push button. Returns true on the frame it is clicked (pressed and released over it). */
    public boolean button(String label, Rect rect) {
        return button(label, label, rect);
    }

    /** A button with an explicit id, for several buttons with the same label. */
    public boolean button(String id, String label, Rect rect) {
        String key = "button:" + id;
        target(key, rect, false);
        boolean hover = hovered(rect);
        if (hover) {
            pointerOverUi = true;
            if (input.isMousePressed(MouseButton.LEFT)) {
                active = key;
            }
        }
        boolean held = key.equals(active) && input.isMouseDown(MouseButton.LEFT);
        if (style.buttonPainter != null) {
            style.buttonPainter.paint(this, rect, label, hover, held);
        } else {
            Color fill = held ? style.buttonPressed : hover ? style.buttonHover : style.button;
            rounded(rect, style.radius, fill.lerp(Color.WHITE, 0.06f), fill);
            roundedOutline(rect, style.radius, 1.5f, hover ? style.accent : style.outline);
            textCentered(label, rect);
        }
        return hover && key.equals(active) && input.isMouseReleased(MouseButton.LEFT);
    }

    /**
     * Whether {@code rect} was clicked, as a button is — pressed and released over it — without drawing anything,
     * for a control that draws itself.
     */
    public boolean clicked(String id, Rect rect) {
        String key = "click:" + id;
        target(key, rect, false);
        boolean hover = hovered(rect);
        if (hover) {
            pointerOverUi = true;
            if (input.isMousePressed(MouseButton.LEFT)) {
                active = key;
            }
        }
        return hover && key.equals(active) && input.isMouseReleased(MouseButton.LEFT);
    }

    /** The input the GUI reads, for a control that needs more than clicks. */
    public Input input() {
        return input;
    }

    /** A checkbox with a label. Returns the new value. */
    public boolean toggle(String label, Rect rect, boolean value) {
        String key = "toggle:" + label;
        target(key, rect, false);
        boolean hover = hovered(rect);
        if (hover) {
            pointerOverUi = true;
            if (input.isMousePressed(MouseButton.LEFT)) {
                active = key;
            }
        }
        boolean clicked = hover && key.equals(active) && input.isMouseReleased(MouseButton.LEFT);
        boolean result = clicked != value;

        float box = rect.height();
        Rect check = new Rect(rect.x(), rect.y(), box, box);
        rounded(check, Math.min(style.radius, box * 0.3f), hover ? style.buttonHover : style.button);
        roundedOutline(check, Math.min(style.radius, box * 0.3f), 1.5f, result ? style.toggleOn : style.outline);
        if (result) {
            float inset = box * 0.25f;
            rounded(new Rect(check.x() + inset, check.y() + inset, box - 2f * inset, box - 2f * inset), box * 0.12f,
                    style.toggleOn);
        }
        float scale = style.textScale;
        text(label, rect.x() + box + style.padding, rect.centerY() - textHeight(scale) * 0.5f, scale, style.text);
        return result;
    }

    /** A horizontal slider. Returns the new value. */
    public float slider(String id, Rect rect, float value, float min, float max) {
        String key = "slider:" + id;
        target(key, rect, true);
        boolean hover = hovered(rect);
        if (hover) {
            pointerOverUi = true;
            if (input.isMousePressed(MouseButton.LEFT)) {
                active = key;
            }
        }
        float result = value;
        if (key.equals(navFocus) && sliderStep != 0 && max > min) {
            // A controller nudges a highlighted slider a twentieth of its range with the D-pad.
            result = Math.max(min, Math.min(max, value + sliderStep * (max - min) / 20f));
        } else if (key.equals(active) && input.isMouseDown(MouseButton.LEFT) && max > min
                && !input.isPointerVirtual()) {
            float t = Math.max(0f, Math.min(1f, (mouseX() - rect.x()) / rect.width()));
            result = min + t * (max - min);
        }
        float fraction = max > min ? (result - min) / (max - min) : 0f;
        float trackHeight = Math.max(6f, rect.height() * 0.3f);
        Rect track = new Rect(rect.x(), rect.centerY() - trackHeight * 0.5f, rect.width(), trackHeight);
        rounded(track, trackHeight * 0.5f, style.field);
        if (fraction > 0f) {
            rounded(new Rect(track.x(), track.y(), Math.max(trackHeight, track.width() * fraction), trackHeight),
                    trackHeight * 0.5f, style.accent.lerp(Color.WHITE, 0.25f), style.accent);
        }
        // A round knob rimmed in the accent, as a slider's thumb is drawn nearly everywhere.
        float knob = Math.min(rect.height(), 30f) * 0.5f;
        float knobX = rect.x() + rect.width() * fraction;
        boolean lit = hover || key.equals(active);
        circle(knobX, rect.centerY(), knob + 2f, style.accent);
        circle(knobX, rect.centerY(), knob - 2f, lit ? Color.WHITE : style.text);
        return result;
    }

    public void progressBar(Rect rect, float fraction, Color fill) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        float r = Math.min(style.radius, rect.height() * 0.5f);
        rounded(rect, r, style.field);
        if (clamped > 0f) {
            rounded(new Rect(rect.x(), rect.y(), Math.max(2f * r, rect.width() * clamped), rect.height()), r, fill);
        }
    }

    /**
     * A single-line text input. Click to focus; Enter or Escape unfocuses. Returns the edited text.
     */
    public String textField(String id, Rect rect, String value, int maxLength) {
        String key = "field:" + id;
        boolean isFocused = takeFieldFocus(key, rect);
        String result = value;
        if (isFocused) {
            result = typeInto(result, maxLength);
            if (input.isKeyPressed(Key.ENTER) || input.isKeyPressed(Key.ESCAPE)) {
                focused = null;
                input.consumeKey(Key.ESCAPE);
            }
            focusedText = result;
        }

        rounded(rect, style.radius, style.field);
        roundedOutline(rect, style.radius, 2f, isFocused ? style.accent : style.outline);
        float scale = style.textScale;
        float textY = rect.centerY() - textHeight(scale) * 0.5f;
        float textX = rect.x() + style.padding;
        // Text longer than the field shows its end while typing (where the caret is) and its start otherwise.
        float maxWidth = rect.width() - 2f * style.padding - 6f;
        String shown = result;
        if (textWidth(shown, scale) > maxWidth) {
            if (isFocused) {
                while (!shown.isEmpty() && textWidth(shown, scale) > maxWidth) {
                    shown = shown.substring(1);
                }
            } else {
                while (!shown.isEmpty() && textWidth(shown + "...", scale) > maxWidth) {
                    shown = shown.substring(0, shown.length() - 1);
                }
                shown += "...";
            }
        }
        text(shown, textX, textY, scale, style.text);
        boolean caretVisible = (System.nanoTime() / 500_000_000L) % 2 == 0;
        if (isFocused && caretVisible) {
            rect(textX + textWidth(shown, scale) + 2f, textY - 2f, 3f, textHeight(scale) + 4f, style.accent);
        }
        return result;
    }

    /**
     * A text input of several lines, wrapped to its width. Click to focus; Enter starts a new line; Escape or a click
     * elsewhere unfocuses. Text too long to fit shows its end while typing and its start otherwise. Returns the edited
     * text.
     */
    public String textArea(String id, Rect rect, String value, int maxLength) {
        String key = "field:" + id;
        boolean isFocused = takeFieldFocus(key, rect);
        String result = value;
        if (isFocused) {
            result = typeInto(result, maxLength);
            if (input.isKeyPressed(Key.BACKSPACE)) {
                backspaceRepeatAt = System.nanoTime() + 400_000_000L;
            } else if (input.isKeyDown(Key.BACKSPACE) && System.nanoTime() >= backspaceRepeatAt) {
                result = withoutLastCharacter(result);
                backspaceRepeatAt = System.nanoTime() + 40_000_000L;
            }
            // The on-screen keyboard has no Enter of its own; a controller's press is not a new line.
            if (input.isKeyPressed(Key.ENTER) && !keyboardShown && result.length() < maxLength) {
                result += "\n";
            }
            if (input.isKeyPressed(Key.ESCAPE)) {
                focused = null;
                input.consumeKey(Key.ESCAPE);
            }
        }

        rounded(rect, style.radius, style.field);
        roundedOutline(rect, style.radius, 2f, isFocused ? style.accent : style.outline);
        float scale = style.textScale;
        float lineStep = textHeight(scale) + scale * 4f;
        List<String> lines = wrapLines(result, rect.width() - 2f * style.padding - 6f, line -> textWidth(line, scale));
        int fits = Math.max(1, (int) ((rect.height() - 2f * style.padding) / lineStep));
        int first = isFocused ? Math.max(0, lines.size() - fits) : 0;
        float textX = rect.x() + style.padding;
        float textY = rect.y() + style.padding;
        for (int i = first; i < Math.min(lines.size(), first + fits); i++) {
            text(lines.get(i), textX, textY + (i - first) * lineStep, scale, style.text);
        }
        if (isFocused) {
            // The on-screen keyboard's preview is one line: the one being typed.
            focusedText = lines.getLast();
            if ((System.nanoTime() / 500_000_000L) % 2 == 0) {
                float caretY = textY + (lines.size() - 1 - first) * lineStep;
                rect(textX + textWidth(lines.getLast(), scale) + 2f, caretY - 2f, 3f, textHeight(scale) + 4f, style.accent);
            }
        }
        return result;
    }

    /** The clicks and controller presses that give a text input the keyboard, and take it away. Whether it has it now. */
    private boolean takeFieldFocus(String key, Rect rect) {
        target(key, rect, false);
        boolean hover = hovered(rect);
        if (hover) {
            pointerOverUi = true;
        }
        if (input.isMousePressed(MouseButton.LEFT)) {
            if (hover) {
                focused = key;
            } else if (key.equals(focused) && !keyboardShown) {
                // A click elsewhere ends typing — except on the on-screen keyboard's own keys.
                focused = null;
            }
        }
        return key.equals(focused);
    }

    /** {@code value} with this frame's typing and Backspace applied, for the focused text input. */
    private String typeInto(String value, int maxLength) {
        focusedDrawn = true;
        String result = value;
        String typed = input.typedText();
        if (!typed.isEmpty()) {
            result = result + typed;
            if (result.length() > maxLength) {
                result = result.substring(0, maxLength);
            }
        }
        if (input.isKeyPressed(Key.BACKSPACE)) {
            result = withoutLastCharacter(result);
        }
        return withoutBrokenPairs(result);
    }

    /**
     * Text split into the lines it takes up at a width: at its line breaks, then at spaces, and inside a word only when
     * the word is wider than a whole line. Spaces stay on the end of their line, so the last line ends where the caret
     * goes. Always at least one line.
     *
     * @param width how wide a piece of text is
     */
    static List<String> wrapLines(String text, float maxWidth, ToDoubleFunction<String> width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            int start = 0;
            while (true) {
                int end = start;
                int lastSpace = -1;
                while (end < paragraph.length() && width.applyAsDouble(paragraph.substring(start, end + 1)) <= maxWidth) {
                    if (paragraph.charAt(end) == ' ') {
                        lastSpace = end;
                    }
                    end++;
                }
                if (end == paragraph.length()) {
                    lines.add(paragraph.substring(start));
                    break;
                }
                int cut = lastSpace >= 0 ? lastSpace + 1 : Math.max(end, start + 1);
                lines.add(paragraph.substring(start, cut));
                start = cut;
                if (start == paragraph.length()) {
                    break;
                }
            }
        }
        return lines;
    }

    /**
     * Text with its last character taken off, as Backspace does: a whole character, since one outside the basic plane,
     * as an emoji, is two chars, and half of one left behind is text that can't be written out as UTF-8 — a save
     * holding it would fail every time.
     */
    static String withoutLastCharacter(String text) {
        return text.isEmpty() ? text : text.substring(0, text.offsetByCodePoints(text.length(), -1));
    }

    /**
     * Text with any half of a surrogate pair dropped: what cutting a field's text to its longest, or older code that
     * removed a char rather than a character, could leave. It can't be encoded, so a file it went into could not be
     * written.
     */
    static String withoutBrokenPairs(String text) {
        StringBuilder kept = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean paired = Character.isHighSurrogate(c) ? i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))
                    : !Character.isLowSurrogate(c) || i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
            if (!paired && kept == null) {
                kept = new StringBuilder(text.substring(0, i));
            } else if (paired && kept != null) {
                kept.append(c);
            }
        }
        return kept == null ? text : kept.toString();
    }

    public UIRenderer renderer() {
        return ui;
    }

    public void delete() {
        ui.delete();
    }
}
