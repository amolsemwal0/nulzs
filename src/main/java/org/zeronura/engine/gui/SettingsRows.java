package org.zeronura.engine.gui;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.ui.Rect;

import java.util.List;

/**
 * Lays out an options screen as rows down a rectangle, scrolled by whole pixels: a heading, then a label on the left
 * of each row and its control on the right — a stepper through choices, an on/off switch, a slider or a button.
 *
 * <p>Each call takes the next row. A row that is not wholly inside the rectangle is skipped rather than drawn, since
 * the GUI has no clipping, so a scrolled list never spills over what is above or below it. Make one per frame, call
 * the rows in order, then {@link #finish} to draw the scroll bar and learn how far it may scroll.
 */
public final class SettingsRows {

    public static final float ROW_HEIGHT = 56f;
    public static final float ROW_GAP = 10f;
    public static final float LABEL_SCALE = 2.5f;
    private static final float HEADING_HEIGHT = 58f;
    private static final float SCROLL_BAR = 8f;
    private static final Color ROW = new Color(1f, 1f, 1f, 0.035f);
    private static final Color ROW_HOVER = new Color(1f, 1f, 1f, 0.07f);

    private final Gui gui;
    private final Rect area;
    private final float scroll;
    /** Where the next row goes, before scrolling, from the top of the area. */
    private float cursor;
    /** Width the controls take at the right of each row. */
    private final float controlWidth;

    /**
     * @param scroll how far down the rows the area is scrolled, in pixels
     */
    public SettingsRows(Gui gui, Rect area, float scroll) {
        this.gui = gui;
        this.area = area;
        this.scroll = scroll;
        this.controlWidth = Math.min(460f, (area.width() - SCROLL_BAR - 24f) * 0.48f);
    }

    /** The width rows are laid out in, leaving room for the scroll bar. */
    public float width() {
        return area.width() - SCROLL_BAR - 20f;
    }

    /**
     * The next {@code height} pixels of rows, or null when that space is scrolled out of the area: draw into it only
     * when it is not null.
     */
    public Rect take(float height) {
        float top = area.y() + cursor - scroll;
        cursor += height + ROW_GAP;
        if (top < area.y() - 0.5f || top + height > area.y() + area.height() + 0.5f) {
            return null;
        }
        return new Rect(area.x(), top, width(), height);
    }

    /** A heading over the rows that follow, with a rule under it. */
    public void heading(String text) {
        if (cursor > 0f) {
            cursor += 12f;
        }
        Rect row = take(HEADING_HEIGHT - ROW_GAP);
        if (row != null) {
            gui.text(text, row.x(), row.y() + 8f, 2.9f, gui.style().accent);
            gui.rounded(new Rect(row.x(), row.y() + row.height() - 3f, row.width(), 2f), 1f, gui.style().outline);
        }
    }

    /** Muted text wrapped across the rows' width, as a note under a setting. */
    public void note(String text) {
        float scale = 2f;
        List<String> lines = wrap(text, width() - 16f, scale);
        float lineHeight = gui.textHeight(scale) * 1.5f;
        Rect row = take(lines.size() * lineHeight);
        if (row != null) {
            for (int i = 0; i < lines.size(); i++) {
                gui.text(lines.get(i), row.x() + 16f, row.y() + i * lineHeight, scale, gui.style().mutedText);
            }
        }
    }

    /** A row's background and label; returns the rectangle its control goes in, at the right. */
    private Rect frame(Rect row, String label, String detail) {
        boolean hover = gui.hoveredButton(row);
        gui.rounded(row, gui.style().radius, hover ? ROW_HOVER : ROW);
        if (hover) {
            gui.roundedOutline(row, gui.style().radius, 1.5f, gui.style().accent.withAlpha(0.6f));
        }
        float textY = row.centerY() - gui.textHeight(LABEL_SCALE) * 0.5f;
        float after = gui.text(label, row.x() + 16f, textY, LABEL_SCALE, gui.style().text);
        if (detail != null && !detail.isEmpty()) {
            gui.text(detail, after + 14f, row.centerY() - gui.textHeight(1.9f) * 0.5f, 1.9f, gui.style().mutedText);
        }
        return new Rect(row.x() + row.width() - controlWidth - 8f, row.y() + 6f, controlWidth, row.height() - 12f);
    }

    /**
     * A stepper through {@code options}: arrows either side of the one chosen, which a click on it also moves past.
     * Both arrows go round, the first option after the last and the last before the first, so neither is ever dead.
     * Returns the index now chosen.
     */
    public int choice(String id, String label, List<String> options, int selected) {
        return choice(id, label, null, options, selected);
    }

    /** As {@link #choice(String, String, List, int)}, with a muted detail after the label. */
    public int choice(String id, String label, String detail, List<String> options, int selected) {
        Rect row = take(ROW_HEIGHT);
        if (row == null) {
            return selected;
        }
        Rect control = frame(row, label, detail);
        int count = options.size();
        int shown = Math.max(0, Math.min(count - 1, selected));
        int result = selected;
        float arrow = control.height();
        Rect left = new Rect(control.x(), control.y(), arrow, control.height());
        Rect right = new Rect(control.x() + control.width() - arrow, control.y(), arrow, control.height());
        Rect value = new Rect(left.x() + arrow + 6f, control.y(), control.width() - 2f * arrow - 12f, control.height());
        if (gui.button(id + ":less", "<", left)) {
            result = (shown - 1 + count) % count;
        }
        if (gui.button(id + ":more", ">", right)) {
            result = (shown + 1) % count;
        }
        boolean hover = gui.hoveredButton(value);
        boolean cycled = gui.clicked(id + ":value", value);
        gui.rounded(value, gui.style().radius, hover ? gui.style().buttonHover : gui.style().field);
        gui.textCentered(options.get(shown), value, LABEL_SCALE, gui.style().text);
        if (cycled) {
            result = (shown + 1) % count;
        }
        return result;
    }

    /** An on/off switch. Returns the new value. */
    public boolean toggle(String id, String label, boolean value) {
        return toggle(id, label, null, value);
    }

    /** As {@link #toggle(String, String, boolean)}, with a muted detail after the label. */
    public boolean toggle(String id, String label, String detail, boolean value) {
        Rect row = take(ROW_HEIGHT);
        if (row == null) {
            return value;
        }
        Rect control = frame(row, label, detail);
        float switchWidth = 112f;
        Rect pill = new Rect(control.x() + control.width() - switchWidth, control.y() + 4f, switchWidth, control.height() - 8f);
        boolean clicked = gui.clicked(id, row);
        boolean result = clicked != value;
        float round = pill.height() * 0.5f;
        gui.rounded(pill, round, result ? gui.style().toggleOn : gui.style().field);
        gui.roundedOutline(pill, round, 1.5f, result ? gui.style().toggleOn.lerp(Color.WHITE, 0.3f) : gui.style().outline);
        float knob = (pill.height() - 10f) * 0.5f;
        float knobX = result ? pill.x() + pill.width() - knob - 5f : pill.x() + knob + 5f;
        gui.circle(knobX, pill.centerY(), knob, result ? Color.WHITE : gui.style().mutedText);
        String state = result ? "On" : "Off";
        gui.textRight(state, pill.x() - 14f, pill.centerY() - gui.textHeight(2.2f) * 0.5f, 2.2f,
                result ? gui.style().text : gui.style().mutedText);
        return result;
    }

    /** A slider with its value written beside it. Returns the new value. */
    public float slider(String id, String label, float value, float min, float max, String shown) {
        Rect row = take(ROW_HEIGHT);
        if (row == null) {
            return value;
        }
        Rect control = frame(row, label, null);
        float valueWidth = 96f;
        float result = gui.slider(id, new Rect(control.x(), control.y() + 8f, control.width() - valueWidth - 12f,
                control.height() - 16f), value, min, max);
        gui.textRight(shown, control.x() + control.width(), control.centerY() - gui.textHeight(LABEL_SCALE) * 0.5f,
                LABEL_SCALE, gui.style().text);
        return result;
    }

    /** A button at the left of its own row. Returns whether it was clicked. */
    public boolean button(String id, String label) {
        Rect row = take(ROW_HEIGHT);
        if (row == null) {
            return false;
        }
        float buttonWidth = gui.textWidth(label, gui.style().textScale) + 60f;
        return gui.button(id, label, new Rect(row.x(), row.y(), buttonWidth, row.height()));
    }

    /** A fixed line: what does something on the left, what it does on the right, unchangeable. */
    public void fixed(String input, String meaning) {
        Rect row = take(ROW_HEIGHT - 12f);
        if (row == null) {
            return;
        }
        gui.rounded(row, gui.style().radius, ROW);
        float textY = row.centerY() - gui.textHeight(2.2f) * 0.5f;
        gui.text(meaning, row.x() + 16f, textY, 2.2f, gui.style().text);
        Rect control = new Rect(row.x() + row.width() - controlWidth - 8f, row.y() + 5f, controlWidth, row.height() - 10f);
        gui.textCentered(input, control, 2.2f, gui.style().mutedText);
    }

    /** A labelled row whose control the caller draws, in the rectangle returned; null when scrolled out of view. */
    public Rect custom(String label, String detail) {
        Rect row = take(ROW_HEIGHT);
        return row == null ? null : frame(row, label, detail);
    }

    /**
     * Draws the scroll bar if the rows overflow the area. Returns the furthest it may scroll, for the caller to keep
     * its scroll inside.
     */
    public float finish() {
        float content = cursor - ROW_GAP;
        float maxScroll = Math.max(0f, content - area.height());
        if (maxScroll > 0f) {
            float trackX = area.x() + area.width() - SCROLL_BAR;
            gui.rounded(new Rect(trackX, area.y(), SCROLL_BAR, area.height()), SCROLL_BAR * 0.5f, gui.style().field);
            float thumb = Math.max(40f, area.height() * area.height() / content);
            float at = area.y() + (area.height() - thumb) * Math.min(1f, scroll / maxScroll);
            gui.rounded(new Rect(trackX, at, SCROLL_BAR, thumb), SCROLL_BAR * 0.5f, gui.style().outline);
        }
        return maxScroll;
    }

    private List<String> wrap(String text, float maxWidth, float scale) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (gui.textWidth(candidate, scale) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
