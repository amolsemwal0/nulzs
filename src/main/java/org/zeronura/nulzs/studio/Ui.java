package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.Window;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.gui.Gui;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.ui.Typeface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * The editor's widgets, drawn with the engine's immediate-mode {@link Gui}: text, buttons, fields that are typed into
 * and committed, numbers dragged left and right, scrolling areas, tooltips, popups, and dragging things from one panel
 * to another.
 *
 * <p>Overlays — menus, popups, dialogs — are drawn after the panels but must take the pointer from them. Each frame
 * starts by finding whether the pointer is over an overlay drawn last frame; if so, the panels are drawn with the
 * pointer blocked. A click outside every open overlay closes them and is swallowed, as menus do everywhere.
 */
final class Ui {

    final Gui gui;
    final Input input;

    private double now;

    // --- Overlays ---------------------------------------------------------------------------------------------
    private List<Rect> overlays = new ArrayList<>();
    private List<Rect> overlaysNow = new ArrayList<>();
    private boolean modal;
    private boolean modalNow;
    /** Whether this frame's press landed outside every overlay that was open: they close, and it goes nowhere. */
    private boolean outsideClick;
    private boolean swallowed;

    // --- Text editing -----------------------------------------------------------------------------------------
    private String editId;
    private LineEdit edit;
    private Consumer<String> editCommit;
    private boolean editDrawn;
    private boolean editDragging;
    /** A field to start editing as soon as it is drawn, as if it had been clicked. */
    private String focusRequest;
    /** Frames the request has waited: it may be made after its field has been drawn this frame. */
    private int focusRequestAge;

    // --- Dragging numbers ------------------------------------------------------------------------------------
    private String dragNumberId;
    private float dragStartValue;
    private float dragTravel;
    private float dragAccumulated;

    // --- Scrolling ------------------------------------------------------------------------------------------
    private final Map<String, Float> scrolls = new HashMap<>();
    private boolean scrollTaken;
    private String scrollbarDrag;
    private float scrollbarGrab;

    // --- Clicks ---------------------------------------------------------------------------------------------
    private String lastPressId;
    private double lastPressTime;

    // --- Tooltips -------------------------------------------------------------------------------------------
    private String tooltipKey;
    private double tooltipSince;
    private String tooltipText;
    private boolean tooltipSeen;

    // --- Drag and drop --------------------------------------------------------------------------------------
    private Object dragPayload;
    private String dragLabel;
    private Icon dragIcon;
    private float dragStartX;
    private float dragStartY;
    private boolean dragLive;
    private Object droppedPayload;

    // --- Popups ---------------------------------------------------------------------------------------------
    /** Something drawn over everything else until a click elsewhere closes it. */
    interface PopupBody {
        /** Draws the popup at (x, y) and returns where it was drawn; null closes it. */
        Rect draw(Ui ui, float x, float y);
    }

    private record Popup(String id, float x, float y, PopupBody body) {
    }

    private final List<Popup> popups = new ArrayList<>();
    private final List<Popup> popupsOpened = new ArrayList<>();

    private Window.CursorShape cursor = Window.CursorShape.ARROW;
    /** How large fields draw their text, for a canvas that zooms: 1 as usual. */
    private float widgetScale = 1f;

    Ui(Gui gui, Input input) {
        this.gui = gui;
        this.input = input;
    }

    // --- Frame ------------------------------------------------------------------------------------------------

    void beginFrame(double time) {
        now = time;
        overlays = overlaysNow;
        overlaysNow = new ArrayList<>();
        modal = modalNow;
        modalNow = false;
        scrollTaken = false;
        tooltipSeen = false;
        cursor = Window.CursorShape.ARROW;
        editDrawn = false;
        droppedPayload = null;
        boolean anyPress = input.isMousePressed(MouseButton.LEFT) || input.isMousePressed(MouseButton.RIGHT)
                || input.isMousePressed(MouseButton.MIDDLE);
        outsideClick = anyPress && !overlays.isEmpty() && !overOverlay();
        swallowed = outsideClick;
        if (outsideClick) {
            popups.clear();
        }
        if (!input.isMouseDown(MouseButton.LEFT)) {
            scrollbarDrag = null;
        }
        gui.setPointerBlocked(blockedForPanels());
    }

    /** Whether panels drawn now are under an overlay and must not take the pointer. */
    boolean blockedForPanels() {
        return modal || swallowed || overOverlay();
    }

    /** Whether the pointer is over a menu or popup drawn last frame. */
    private boolean overOverlay() {
        float x = gui.mouseX();
        float y = gui.mouseY();
        for (Rect rect : overlays) {
            if (rect.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a press this frame closed an open menu or popup, so whatever is under it must not act on it. */
    boolean outsideClick() {
        return outsideClick;
    }

    /** Called before drawing a modal dialog: from here the pointer is the dialog's unless a popup over it has it. */
    void beginModal() {
        modalNow = true;
        gui.setPointerBlocked(swallowed || overOverlay());
    }

    /** Whether a dialog was open last frame, so the panels and shortcuts under it stay still. */
    boolean modal() {
        return modal;
    }

    /** Marks a rectangle drawn over the panels — a menu, a dropdown — so next frame the panels under it ignore the pointer. */
    void overlay(Rect rect) {
        overlaysNow.add(rect);
    }

    void endFrame() {
        gui.setPointerBlocked(false);
        drawPopups();
        if (editId != null && !editDrawn) {
            // The field went away while being typed in — its panel closed, the selection moved on — so its text is
            // kept, as a click elsewhere would keep it.
            commitEdit();
        }
        if (focusRequest != null && editId == null && ++focusRequestAge > 2) {
            focusRequest = null;
        }
        drawTooltip();
        drawDragPayload();
        if (!input.isMouseDown(MouseButton.LEFT) && dragPayload != null) {
            dragPayload = null;
            dragLive = false;
        }
        if (!input.isMouseDown(MouseButton.LEFT)) {
            dragNumberId = null;
        }
        input.setCursorShape(cursor);
        if (!tooltipSeen) {
            tooltipKey = null;
        }
    }

    double time() {
        return now;
    }

    /** Draws the fields and dropdowns that follow with their text this much larger, until set back to 1. */
    void widgetScale(float scale) {
        widgetScale = Math.clamp(scale, 0.4f, 2f);
    }

    void cursor(Window.CursorShape shape) {
        cursor = shape;
    }

    // --- Text -------------------------------------------------------------------------------------------------

    float text(String text, float x, float y, Color color) {
        return gui.text(text, x, y, Theme.TEXT_SCALE, color);
    }

    float text(String text, float x, float y, float scale, Color color) {
        return gui.text(text, x, y, scale, color);
    }

    float heading(String text, float x, float y, Color color) {
        return gui.text(text, x, y, Theme.HEADING_SCALE, color, Typeface.HEADING);
    }

    /** Text with its capitals centred on a rectangle's middle line, starting at {@code x}. Returns the x after it. */
    float textIn(String text, Rect rect, float x, Color color) {
        return textIn(text, rect, x, Theme.TEXT_SCALE, color);
    }

    float textIn(String text, Rect rect, float x, float scale, Color color) {
        return gui.text(text, x, rect.centerY() - gui.textHeight(scale) * 0.5f, scale, color);
    }

    void textCentered(String text, Rect rect, Color color) {
        gui.textCentered(text, rect, Theme.TEXT_SCALE, color);
    }

    void textCentered(String text, Rect rect, float scale, Color color) {
        gui.textCentered(text, rect, scale, color);
    }

    void textRight(String text, Rect rect, float rightX, float scale, Color color) {
        gui.textRight(text, rightX, rect.centerY() - gui.textHeight(scale) * 0.5f, scale, color);
    }

    float width(String text) {
        return gui.textWidth(text, Theme.TEXT_SCALE);
    }

    /** {@code text} broken into lines no wider than {@code width}, at word gaps. */
    java.util.List<String> wrap(String text, float width, float scale) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(tried, scale) > width) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(tried);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    float width(String text, float scale) {
        return gui.textWidth(text, scale);
    }

    float textHeight(float scale) {
        return gui.textHeight(scale);
    }

    /** {@code text} cut short with "..." to fit a width. */
    String fit(String text, float maxWidth, float scale) {
        if (text == null) {
            return "";
        }
        if (gui.textWidth(text, scale) <= maxWidth) {
            return text;
        }
        int keep = text.length();
        while (keep > 0 && gui.textWidth(text.substring(0, keep) + "...", scale) > maxWidth) {
            keep--;
        }
        return text.substring(0, keep) + "...";
    }

    String fit(String text, float maxWidth) {
        return fit(text, maxWidth, Theme.TEXT_SCALE);
    }

    // --- Pointer ----------------------------------------------------------------------------------------------

    float mouseX() {
        return gui.mouseX();
    }

    float mouseY() {
        return gui.mouseY();
    }

    boolean hover(Rect rect) {
        return gui.hovered(rect);
    }

    boolean pressed(Rect rect) {
        return input.isMousePressed(MouseButton.LEFT) && hover(rect);
    }

    boolean rightPressed(Rect rect) {
        return input.isMousePressed(MouseButton.RIGHT) && hover(rect);
    }

    boolean rightClicked(Rect rect) {
        return input.isMouseReleased(MouseButton.RIGHT) && hover(rect);
    }

    /** Pressed and released over the same rectangle. */
    boolean clicked(String id, Rect rect) {
        return gui.clicked(id, rect);
    }

    /** A second press on the same thing soon after the first. */
    boolean doublePressed(String id, Rect rect) {
        if (!pressed(rect)) {
            return false;
        }
        boolean twice = id.equals(lastPressId) && now - lastPressTime < 0.4;
        lastPressId = twice ? null : id;
        lastPressTime = now;
        return twice;
    }

    // --- Buttons ----------------------------------------------------------------------------------------------

    boolean button(String id, String label, Rect rect) {
        boolean hover = hover(rect);
        boolean held = hover && input.isMouseDown(MouseButton.LEFT);
        gui.rounded(rect, Theme.RADIUS, held ? Theme.PRESSED : hover ? Theme.HOVER : Theme.SECTION);
        gui.roundedOutline(rect, Theme.RADIUS, 1f, Theme.FIELD_BORDER);
        textCentered(fit(label, rect.width() - 8f), rect, Theme.TEXT);
        return clicked(id, rect);
    }

    boolean primaryButton(String id, String label, Rect rect) {
        boolean hover = hover(rect);
        Color fill = hover ? Theme.ACCENT.lerp(Color.WHITE, 0.12f) : Theme.ACCENT;
        gui.rounded(rect, Theme.RADIUS, fill);
        textCentered(fit(label, rect.width() - 8f), rect, Theme.TEXT_BRIGHT);
        return clicked(id, rect);
    }

    /** A square button with an icon; {@code on} shows it pressed in, for tools and switches. */
    boolean iconButton(String id, Icon icon, Rect rect, String tooltip, boolean on) {
        return iconButton(id, icon, rect, tooltip, on, Theme.TEXT);
    }

    boolean iconButton(String id, Icon icon, Rect rect, String tooltip, boolean on, Color color) {
        boolean hover = hover(rect);
        if (on) {
            gui.rounded(rect, Theme.RADIUS, Theme.ACCENT_DIM);
        } else if (hover) {
            gui.rounded(rect, Theme.RADIUS, Theme.HOVER);
        }
        float size = Math.min(rect.width(), rect.height()) * 0.62f;
        icon.draw(gui, rect.centerX() - size * 0.5f, rect.centerY() - size * 0.5f, size, on ? Theme.TEXT_BRIGHT : color);
        if (tooltip != null) {
            tooltip(id, rect, tooltip);
        }
        return clicked(id, rect);
    }

    /** A labelled button that looks like a tab or toolbar item: flat until hovered. */
    boolean flatButton(String id, String label, Rect rect, boolean on) {
        boolean hover = hover(rect);
        if (on) {
            gui.rounded(rect, Theme.RADIUS, Theme.ACCENT_DIM);
        } else if (hover) {
            gui.rounded(rect, Theme.RADIUS, Theme.HOVER);
        }
        textCentered(label, rect, on ? Theme.TEXT_BRIGHT : Theme.TEXT);
        return clicked(id, rect);
    }

    boolean checkbox(String id, Rect rect, boolean value) {
        float size = Math.min(rect.height(), 16f);
        Rect box = new Rect(rect.x(), rect.centerY() - size * 0.5f, size, size);
        boolean hover = hover(rect);
        gui.rounded(box, 3f, value ? Theme.ACCENT : hover ? Theme.HOVER : Theme.FIELD);
        if (!value) {
            gui.roundedOutline(box, 3f, 1f, hover ? Theme.ACCENT : Theme.FIELD_BORDER);
        } else {
            Icon.CHECK.draw(gui, box.x() + 1f, box.y() + 1f, size - 2f, Theme.TEXT_BRIGHT);
        }
        return clicked(id, rect) != value;
    }

    // --- Typed fields -----------------------------------------------------------------------------------------

    /** Whether a field has the keyboard: shortcuts that are plain letters must leave the keys alone. */
    boolean typing() {
        return editId != null;
    }

    boolean editing(String id) {
        return id.equals(editId);
    }

    /** Starts editing the field with this id the next time it is drawn, all its text selected. */
    void focus(String id) {
        focusRequest = id;
        focusRequestAge = 0;
    }

    /** Stops editing, keeping what was typed. */
    void commitEdit() {
        if (editId == null) {
            return;
        }
        Consumer<String> commit = editCommit;
        String value = edit.value();
        editId = null;
        edit = null;
        editCommit = null;
        if (commit != null) {
            commit.accept(value);
        }
    }

    void cancelEdit() {
        editId = null;
        edit = null;
        editCommit = null;
    }

    /**
     * A text field: click to type, Enter or a click elsewhere to keep it, Escape to drop it. {@code set} is called with
     * the new text when it is kept.
     */
    void textField(String id, Rect rect, String value, String placeholder, Consumer<String> set) {
        field(id, rect, value, placeholder, set, false, false, false);
    }

    /** A field whose text counts as typed, without Enter: what a search box is. Returns the text now. */
    String searchField(String id, Rect rect, String value, String placeholder) {
        String[] live = {value};
        field(id, rect, value, placeholder, text -> live[0] = text, true, true, false);
        if (editing(id)) {
            live[0] = edit.value();
        }
        return live[0];
    }

    /** A plain field whose text counts as it is typed, for a dialog that checks it as it goes. Returns the text now. */
    String liveField(String id, Rect rect, String value, String placeholder) {
        String[] live = {value};
        field(id, rect, value, placeholder, text -> live[0] = text, false, true, false);
        if (editing(id)) {
            live[0] = edit.value();
        }
        return live[0];
    }

    /** {@link #textField} in the code face, for paths and numbers. */
    void monoField(String id, Rect rect, String value, String placeholder, Consumer<String> set) {
        field(id, rect, value, placeholder, set, false, false, true);
    }

    private void field(String id, Rect rect, String value, String placeholder, Consumer<String> set, boolean search,
                       boolean live, boolean mono) {
        boolean hover = hover(rect);
        if (hover) {
            cursor(Window.CursorShape.TEXT);
        }
        float scale = Theme.TEXT_SCALE * widgetScale;
        Typeface face = mono ? Typeface.MONO : Typeface.BODY;
        float padX = (search ? 22f : 6f) * widgetScale;
        float textX = rect.x() + padX;
        float textY = rect.centerY() - gui.textHeight(scale) * 0.5f;
        boolean editingNow = id.equals(editId);

        if (!editingNow && (id.equals(focusRequest) || (pressed(rect) && !outsideClick))) {
            commitEdit();
            editId = id;
            edit = new LineEdit(value, true);
            editingNow = true;
            editDragging = !id.equals(focusRequest);
            if (id.equals(focusRequest)) {
                focusRequest = null;
            } else if (live) {
                edit.caret = edit.anchor = caretAt(edit, textX, scale, face);
            }
        } else if (editingNow && pressed(rect)) {
            int at = caretAt(edit, textX, scale, face);
            edit.caret = at;
            if (!input.shiftDown()) {
                edit.anchor = at;
            }
            editDragging = true;
        }
        if (editingNow) {
            editCommit = set;
            editDrawn = true;
            if (editDragging && input.isMouseDown(MouseButton.LEFT)) {
                edit.caret = caretAt(edit, textX, scale, face);
            } else {
                editDragging = false;
            }
            if (input.isMousePressed(MouseButton.LEFT) && !hover && !search) {
                commitEdit();
                editingNow = false;
            } else if (input.isMousePressed(MouseButton.LEFT) && !hover) {
                cancelEdit();
                editingNow = false;
            } else {
                LineEdit.Result result = edit.handle(input);
                if (result == LineEdit.Result.COMMIT || result == LineEdit.Result.TAB) {
                    commitEdit();
                    editingNow = false;
                } else if (result == LineEdit.Result.CANCEL) {
                    if (search) {
                        edit.text.setLength(0);
                        commitEdit();
                    } else {
                        cancelEdit();
                    }
                    editingNow = false;
                } else if (live) {
                    set.accept(edit.value());
                }
            }
        }

        gui.rounded(rect, Theme.RADIUS, Theme.FIELD);
        gui.roundedOutline(rect, Theme.RADIUS, 1f, editingNow ? Theme.ACCENT : hover ? Theme.TEXT_DISABLED : Theme.FIELD_BORDER);
        if (search) {
            Icon.SEARCH.draw(gui, rect.x() + 5f, rect.centerY() - 7f, 14f, Theme.TEXT_MUTED);
        }
        gui.pushClip(new Rect(rect.x() + 2f, rect.y(), rect.width() - 4f, rect.height()));
        if (editingNow) {
            String text = edit.value();
            float caretX = gui.textWidth(text.substring(0, edit.caret), scale, face);
            float visible = rect.width() - padX - 6f;
            if (caretX - edit.scroll > visible) {
                edit.scroll = caretX - visible;
            } else if (caretX - edit.scroll < 0f) {
                edit.scroll = caretX;
            }
            float x0 = textX - edit.scroll;
            if (edit.hasSelection()) {
                float a = gui.textWidth(text.substring(0, edit.selectionStart()), scale, face);
                float b = gui.textWidth(text.substring(0, edit.selectionEnd()), scale, face);
                gui.rect(x0 + a, rect.y() + 3f, b - a, rect.height() - 6f, Theme.SELECTION);
            }
            gui.text(text, x0, textY, scale, Theme.TEXT_BRIGHT, face);
            if (((long) (now * 2.0)) % 2 == 0) {
                gui.rect(x0 + caretX, rect.y() + 4f, 1.5f, rect.height() - 8f, Theme.TEXT_BRIGHT);
            }
        } else if (value == null || value.isEmpty()) {
            if (placeholder != null) {
                gui.text(placeholder, textX, textY, scale, Theme.TEXT_DISABLED, face);
            }
        } else {
            gui.text(fit(value, rect.width() - padX - 6f, scale), textX, textY, scale, Theme.TEXT, face);
        }
        gui.popClip();
    }

    private int caretAt(LineEdit edit, float textX, float scale, Typeface face) {
        float x = mouseX() - textX + edit.scroll;
        String text = edit.value();
        for (int i = 0; i < text.length(); i++) {
            float a = gui.textWidth(text.substring(0, i), scale, face);
            float b = gui.textWidth(text.substring(0, i + 1), scale, face);
            if (x < (a + b) * 0.5f) {
                return i;
            }
        }
        return text.length();
    }

    // --- Numbers ----------------------------------------------------------------------------------------------

    /**
     * A number that changes as the pointer drags left and right over it ({@code speed} per unit moved; Shift for finer,
     * Ctrl for whole steps), or that is typed into when clicked without dragging.
     */
    void number(String id, Rect rect, double value, double speed, double min, double max, boolean integer,
                DoubleConsumer set) {
        if (id.equals(editId) || id.equals(focusRequest)) {
            field(id, rect, format(value, integer), null, text -> {
                Double parsed = parse(text);
                if (parsed != null) {
                    set.accept(clamp(integer ? Math.round(parsed) : parsed, min, max));
                }
            }, false, false, true);
            return;
        }
        boolean hover = hover(rect);
        if (hover) {
            cursor(Window.CursorShape.RESIZE_HORIZONTAL);
        }
        if (pressed(rect) && !outsideClick) {
            dragNumberId = id;
            dragStartValue = (float) value;
            dragTravel = 0f;
            dragAccumulated = 0f;
        }
        boolean dragging = id.equals(dragNumberId);
        if (dragging) {
            cursor(Window.CursorShape.RESIZE_HORIZONTAL);
            float dx = input.mouseDeltaX() / gui.scale();
            dragTravel += Math.abs(dx) + Math.abs(input.mouseDeltaY() / gui.scale());
            if (input.isMouseDown(MouseButton.LEFT)) {
                if (dragTravel > 3f && dx != 0f) {
                    float step = (float) speed * (input.shiftDown() ? 0.1f : 1f);
                    dragAccumulated += dx * step;
                    double next = dragStartValue + dragAccumulated;
                    if (integer) {
                        next = Math.round(next);
                    } else if (input.ctrlDown()) {
                        double snap = speed >= 0.05 ? 1.0 : 0.1;
                        next = Math.round(next / snap) * snap;
                    }
                    set.accept(clamp(next, min, max));
                }
            } else {
                dragNumberId = null;
                if (dragTravel <= 3f && hover) {
                    focus(id);
                }
            }
        }
        gui.rounded(rect, Theme.RADIUS, dragging ? Theme.PRESSED : hover ? Theme.HOVER : Theme.FIELD);
        gui.roundedOutline(rect, Theme.RADIUS, 1f, dragging ? Theme.ACCENT : Theme.FIELD_BORDER);
        gui.pushClip(rect);
        textIn(format(value, integer), rect, rect.x() + 6f * widgetScale, Theme.TEXT_SCALE * widgetScale, Theme.TEXT);
        gui.popClip();
    }

    /** A number between two values as a slider with the number typed beside it. */
    void slider(String id, Rect rect, double value, double min, double max, boolean integer, DoubleConsumer set) {
        float numberWidth = Math.min(64f, rect.width() * 0.35f);
        Rect track = new Rect(rect.x(), rect.y(), rect.width() - numberWidth - 6f, rect.height());
        float fraction = (float) ((value - min) / Math.max(1e-9, max - min));
        boolean hover = hover(track);
        String dragId = "slider:" + id;
        if (pressed(track) && !outsideClick) {
            dragNumberId = dragId;
        }
        if (dragId.equals(dragNumberId) && input.isMouseDown(MouseButton.LEFT)) {
            float t = Math.clamp((mouseX() - track.x()) / Math.max(1f, track.width()), 0f, 1f);
            double next = min + t * (max - min);
            set.accept(integer ? Math.round(next) : next);
            fraction = t;
        }
        float barHeight = 4f;
        Rect bar = new Rect(track.x(), track.centerY() - barHeight * 0.5f, track.width(), barHeight);
        gui.rounded(bar, 2f, Theme.FIELD);
        gui.rounded(new Rect(bar.x(), bar.y(), bar.width() * Math.clamp(fraction, 0f, 1f), barHeight), 2f, Theme.ACCENT);
        gui.circle(bar.x() + bar.width() * Math.clamp(fraction, 0f, 1f), bar.centerY(), 6f,
                hover || dragId.equals(dragNumberId) ? Theme.TEXT_BRIGHT : Theme.TEXT);
        number(id, new Rect(rect.x() + rect.width() - numberWidth, rect.y(), numberWidth, rect.height()), value,
                (max - min) / 200.0, min, max, integer, set);
    }

    static String format(double value, boolean integer) {
        if (integer) {
            return Long.toString(Math.round(value));
        }
        if (Math.abs(value) < 1e-9) {
            return "0";
        }
        String text = String.format(Locale.ROOT, "%.3f", value);
        text = text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
        return text.equals("-0") ? "0" : text;
    }

    static Double parse(String text) {
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Whether a number is being dragged or a field typed in, so a change in progress isn't yet one to undo. */
    boolean busy() {
        return dragNumberId != null || editId != null;
    }

    // --- Scrolling --------------------------------------------------------------------------------------------

    /**
     * Starts a scrolling area: clips to {@code view} and returns how far down it is scrolled. The wheel scrolls the
     * innermost area under the pointer. End it with {@link #endScroll}.
     */
    float beginScroll(String id, Rect view, float contentHeight) {
        float max = Math.max(0f, contentHeight - view.height());
        float scroll = Math.clamp(scrolls.getOrDefault(id, 0f), 0f, max);
        if (!scrollTaken && hover(view) && input.scroll() != 0f) {
            scroll = Math.clamp(scroll - input.scroll() * Theme.ROW * 2.5f, 0f, max);
            scrollTaken = true;
        }
        scrolls.put(id, scroll);
        gui.pushClip(view);
        return scroll;
    }

    void endScroll(String id, Rect view, float contentHeight) {
        gui.popClip();
        scrollbar(id, view, contentHeight);
    }

    /** Scrolls an area so that the span from {@code top} to {@code bottom} (content units) is in view. */
    void scrollTo(String id, float top, float bottom, float viewHeight) {
        float scroll = scrolls.getOrDefault(id, 0f);
        if (top < scroll) {
            scroll = top;
        } else if (bottom > scroll + viewHeight) {
            scroll = bottom - viewHeight;
        }
        scrolls.put(id, Math.max(0f, scroll));
    }

    void setScroll(String id, float scroll) {
        scrolls.put(id, Math.max(0f, scroll));
    }

    float scroll(String id) {
        return scrolls.getOrDefault(id, 0f);
    }

    /** Whether the wheel has already scrolled something this frame. */
    boolean takeScroll(Rect view) {
        if (scrollTaken || !hover(view) || input.scroll() == 0f) {
            return false;
        }
        scrollTaken = true;
        return true;
    }

    private void scrollbar(String id, Rect view, float contentHeight) {
        if (contentHeight <= view.height() + 0.5f) {
            return;
        }
        float max = contentHeight - view.height();
        float scroll = scrolls.getOrDefault(id, 0f);
        Rect track = new Rect(view.x() + view.width() - 8f, view.y() + 2f, 6f, view.height() - 4f);
        float thumbHeight = Math.max(24f, track.height() * view.height() / contentHeight);
        float thumbY = track.y() + (track.height() - thumbHeight) * (scroll / max);
        Rect thumb = new Rect(track.x(), thumbY, track.width(), thumbHeight);
        boolean hover = hover(new Rect(track.x() - 4f, track.y(), track.width() + 6f, track.height()));
        if (hover && input.isMousePressed(MouseButton.LEFT)) {
            scrollbarDrag = id;
            scrollbarGrab = thumb.contains(mouseX(), mouseY()) ? mouseY() - thumbY : thumbHeight * 0.5f;
        }
        if (id.equals(scrollbarDrag)) {
            float t = (mouseY() - scrollbarGrab - track.y()) / Math.max(1f, track.height() - thumbHeight);
            scrolls.put(id, Math.clamp(t, 0f, 1f) * max);
        }
        gui.rounded(thumb, 3f, id.equals(scrollbarDrag) ? Theme.TEXT_MUTED : hover ? Theme.TEXT_DISABLED : Theme.DIVIDER);
    }

    /** Whether a scrollbar is being dragged, so the area under it leaves the pointer alone. */
    boolean draggingScrollbar() {
        return scrollbarDrag != null;
    }

    // --- Tooltips ---------------------------------------------------------------------------------------------

    /** Shows {@code text} by the pointer once it has rested on {@code rect} a moment. */
    void tooltip(String key, Rect rect, String text) {
        if (text == null || !hover(rect) || input.isMouseDown(MouseButton.LEFT)) {
            return;
        }
        tooltipSeen = true;
        if (!key.equals(tooltipKey)) {
            tooltipKey = key;
            tooltipSince = now;
        }
        tooltipText = text;
    }

    private void drawTooltip() {
        if (tooltipKey == null || !tooltipSeen || now - tooltipSince < 0.55 || tooltipText == null) {
            return;
        }
        float scale = Theme.SMALL_SCALE;
        String[] lines = tooltipText.split("\n");
        float w = 0f;
        for (String line : lines) {
            w = Math.max(w, gui.textWidth(line, scale));
        }
        float lineHeight = gui.textHeight(scale) + 7f;
        float h = lines.length * lineHeight + 10f;
        float x = Math.min(mouseX() + 14f, gui.width() - w - 20f);
        float y = mouseY() + 20f;
        if (y + h > gui.height() - 4f) {
            y = mouseY() - h - 8f;
        }
        Rect box = new Rect(x, y, w + 16f, h);
        gui.glow(box, 4f, 8f, new Color(0f, 0f, 0f, 0.35f));
        gui.rounded(box, 4f, Theme.POPUP);
        gui.roundedOutline(box, 4f, 1f, Theme.POPUP_BORDER);
        for (int i = 0; i < lines.length; i++) {
            gui.text(lines[i], x + 8f, y + 7f + i * lineHeight, scale, Theme.TEXT);
        }
    }

    // --- Drag and drop ----------------------------------------------------------------------------------------

    /**
     * Offers something to drag — an asset, an entity — from a press on it. It becomes a drag once the pointer has moved
     * a few units with the button held; until then the press is an ordinary click.
     */
    void offerDrag(Object payload, String label, Icon icon) {
        if (dragPayload == null && input.isMousePressed(MouseButton.LEFT)) {
            dragPayload = payload;
            dragLabel = label;
            dragIcon = icon;
            dragStartX = mouseX();
            dragStartY = mouseY();
            dragLive = false;
        }
    }

    /** Whether something is being dragged. */
    boolean dragging() {
        if (dragPayload != null && !dragLive && input.isMouseDown(MouseButton.LEFT)
                && Math.abs(mouseX() - dragStartX) + Math.abs(mouseY() - dragStartY) > 6f) {
            dragLive = true;
        }
        return dragPayload != null && dragLive;
    }

    Object dragPayload() {
        return dragging() ? dragPayload : null;
    }

    /** Whether a drag of a {@code type} is over {@code rect}, so it can be shown ready to take it. */
    boolean dragOver(Rect rect, Class<?> type) {
        // Panels are blocked under overlays, but a drag must still see what it is over.
        return dragging() && type.isInstance(dragPayload) && rect.contains(mouseX(), mouseY()) && gui.pointerInClip();
    }

    /** The thing dropped on {@code rect} this frame, if it is a {@code type}; else null. */
    <T> T dropped(Rect rect, Class<T> type) {
        if (dragPayload != null && dragLive && input.isMouseReleased(MouseButton.LEFT) && type.isInstance(dragPayload)
                && rect.contains(mouseX(), mouseY()) && gui.pointerInClip() && droppedPayload == null) {
            droppedPayload = dragPayload;
            return type.cast(dragPayload);
        }
        return null;
    }

    private void drawDragPayload() {
        if (!dragging() || dragLabel == null) {
            return;
        }
        float w = gui.textWidth(dragLabel, Theme.SMALL_SCALE) + 34f;
        Rect box = new Rect(mouseX() + 14f, mouseY() + 8f, w, 24f);
        gui.rounded(box, 4f, Theme.POPUP.withAlpha(0.92f));
        gui.roundedOutline(box, 4f, 1f, Theme.ACCENT);
        if (dragIcon != null) {
            dragIcon.draw(gui, box.x() + 6f, box.y() + 4f, 16f, Theme.TEXT);
        }
        gui.text(dragLabel, box.x() + 26f, box.centerY() - gui.textHeight(Theme.SMALL_SCALE) * 0.5f, Theme.SMALL_SCALE,
                Theme.TEXT_BRIGHT);
    }

    // --- Popups -----------------------------------------------------------------------------------------------

    /** Opens a popup at (x, y), replacing one with the same id. */
    void openPopup(String id, float x, float y, PopupBody body) {
        popups.removeIf(p -> p.id().equals(id));
        popupsOpened.add(new Popup(id, x, y, body));
    }

    void closePopup(String id) {
        popups.removeIf(p -> p.id().equals(id) || p.id().startsWith(id + "/"));
        popupsOpened.removeIf(p -> p.id().equals(id));
    }

    void closePopups() {
        popups.clear();
        popupsOpened.clear();
    }

    /** Closes the popups opened from the one with this id — a menu's submenus. */
    void closePopupsUnder(String id) {
        popups.removeIf(p -> p.id().startsWith(id + "/"));
        popupsOpened.removeIf(p -> p.id().startsWith(id + "/"));
    }

    boolean popupOpen(String id) {
        for (Popup popup : popups) {
            if (popup.id().equals(id)) {
                return true;
            }
        }
        for (Popup popup : popupsOpened) {
            if (popup.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    boolean anyPopup() {
        return !popups.isEmpty() || !popupsOpened.isEmpty();
    }

    private void drawPopups() {
        popups.addAll(popupsOpened);
        popupsOpened.clear();
        for (Popup popup : List.copyOf(popups)) {
            if (!popups.contains(popup)) {
                continue;
            }
            Rect drawn = popup.body().draw(this, popup.x(), popup.y());
            if (drawn == null) {
                popups.remove(popup);
            } else {
                overlay(drawn);
            }
        }
    }

    /** A popup's box, with its shadow and border, kept on the screen. Returns the box, moved to fit. */
    Rect popupBox(float x, float y, float width, float height) {
        float fx = Math.max(2f, Math.min(x, gui.width() - width - 2f));
        float fy = Math.max(2f, Math.min(y, gui.height() - height - 2f));
        Rect box = new Rect(fx, fy, width, height);
        gui.glow(box, 5f, 12f, new Color(0f, 0f, 0f, 0.45f));
        gui.rounded(box, 5f, Theme.POPUP);
        gui.roundedOutline(box, 5f, 1f, Theme.POPUP_BORDER);
        return box;
    }

    /**
     * A dropdown: shows {@code current} and opens a list of {@code options} on a click. {@code choose} gets the index
     * picked.
     */
    void dropdown(String id, Rect rect, String current, List<String> options, java.util.function.IntConsumer choose) {
        boolean hover = hover(rect);
        boolean open = popupOpen(id);
        gui.rounded(rect, Theme.RADIUS, open ? Theme.PRESSED : hover ? Theme.HOVER : Theme.SECTION);
        gui.roundedOutline(rect, Theme.RADIUS, 1f, open ? Theme.ACCENT : Theme.FIELD_BORDER);
        float textScale = Theme.TEXT_SCALE * widgetScale;
        gui.pushClip(new Rect(rect.x(), rect.y(), rect.width() - 18f * widgetScale, rect.height()));
        textIn(fit(current, rect.width() - 26f * widgetScale, textScale), rect, rect.x() + 6f * widgetScale, textScale, Theme.TEXT);
        gui.popClip();
        float chevron = 12f * widgetScale;
        Icon.CHEVRON_DOWN.draw(gui, rect.x() + rect.width() - 18f * widgetScale, rect.centerY() - chevron * 0.5f, chevron,
                Theme.TEXT_MUTED);
        if (clicked(id, rect)) {
            if (open) {
                closePopup(id);
            } else {
                float width = rect.width();
                for (String option : options) {
                    width = Math.max(width, width(option) + 32f);
                }
                float popupWidth = width;
                openPopup(id, rect.x(), rect.y() + rect.height() + 2f, (ui, x, y) -> {
                    float visible = Math.min(options.size(), 14) * Theme.ROW + 8f;
                    Rect box = ui.popupBox(x, y, popupWidth, visible);
                    Rect list = new Rect(box.x() + 4f, box.y() + 4f, box.width() - 8f, box.height() - 8f);
                    float scroll = ui.beginScroll(id + ":list", list, options.size() * Theme.ROW);
                    for (int i = 0; i < options.size(); i++) {
                        Rect row = new Rect(list.x(), list.y() + i * Theme.ROW - scroll, list.width(), Theme.ROW);
                        if (row.y() + row.height() < list.y() || row.y() > list.y() + list.height()) {
                            continue;
                        }
                        boolean selected = options.get(i).equals(current);
                        if (ui.hover(row) || selected) {
                            ui.gui.rounded(row, 3f, ui.hover(row) ? Theme.ACCENT_DIM : Theme.HOVER);
                        }
                        ui.textIn(options.get(i), row, row.x() + 8f, Theme.TEXT);
                        if (ui.clicked(id + ":" + i, row)) {
                            choose.accept(i);
                            ui.endScroll(id + ":list", list, options.size() * Theme.ROW);
                            return null;
                        }
                    }
                    ui.endScroll(id + ":list", list, options.size() * Theme.ROW);
                    return box;
                });
            }
        }
    }

    // --- Drawing helpers --------------------------------------------------------------------------------------

    void fill(Rect rect, Color color) {
        gui.rect(rect, color);
    }

    void hline(float x, float y, float width, Color color) {
        gui.rect(x, y, width, 1f, color);
    }

    void vline(float x, float y, float height, Color color) {
        gui.rect(x, y, 1f, height, color);
    }

    /** A panel's title strip. */
    void panelHeader(Rect rect, String title, Icon icon) {
        gui.rect(rect, Theme.PANEL_HEADER);
        float x = rect.x() + Theme.PAD;
        if (icon != null) {
            icon.draw(gui, x, rect.centerY() - 7f, 14f, Theme.TEXT_MUTED);
            x += 20f;
        }
        textIn(title, rect, x, Theme.TEXT);
        hline(rect.x(), rect.y() + rect.height() - 1f, rect.width(), Theme.BORDER);
    }

    /** Whether a key is down this frame that is a shortcut for whatever has the focus, rather than text being typed. */
    boolean shortcut(int key, boolean ctrl, boolean shift) {
        return input.isKeyPressed(key) && input.ctrlDown() == ctrl && input.shiftDown() == shift && !input.altDown();
    }

    boolean escapePressed() {
        return input.isKeyPressed(Key.ESCAPE);
    }
}
