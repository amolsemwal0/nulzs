package org.zeronura.nulzs.editor;

import org.joml.Vector4f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.gui.Gui;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.ui.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * The pieces the in-engine editors share, so a layer, a lock or a card of assets looks and works the same in each:
 * eyes and padlocks for layers, cards with a picture and a name, grids of cards that scroll, and text cut or wrapped to
 * fit a panel.
 */
public final class EditorWidgets {

    /** What is chosen, and the outline round it. */
    public static final Color SELECTED = Color.rgb(0xFFD23F);
    /** A locked layer's padlock and its notes. */
    public static final Color LOCKED = Color.rgb(0xF0A050);
    /** The columns a {@link #grid} lays cards out in, and the gap between them. */
    public static final int CARD_COLUMNS = 3;
    public static final float CARD_GAP = 8f;

    /** Draws one card of a {@link #grid}. */
    @FunctionalInterface
    public interface CardDrawer<T> {
        void draw(T item, Rect card);
    }

    /** Draws a picture into a card's frame, for pictures that are not a whole texture: a frame of a sprite sheet. */
    @FunctionalInterface
    public interface Picture {
        void draw(Gui gui, Rect frame);
    }

    private EditorWidgets() {
    }

    /** An eye: open, or shut with a line through it. */
    public static void eye(Gui gui, Rect r, boolean open, Color color) {
        Vector4f c = color.withAlpha(open ? 0.95f : 0.45f).toVector4f(new Vector4f());
        Vector4f dark = new Vector4f(0.07f, 0.08f, 0.1f, 1f);
        float cx = r.centerX();
        float cy = r.centerY();
        float w = r.width() * 0.32f;
        float h = r.height() * 0.17f;
        var ui = gui.renderer();
        // The lens: an almond of eight triangles about the middle.
        int steps = 8;
        for (int k = 0; k < steps; k++) {
            float a0 = (float) (Math.PI * 2 * k / steps);
            float a1 = (float) (Math.PI * 2 * (k + 1) / steps);
            ui.triangle(cx, cy, cx + (float) Math.cos(a0) * w, cy + (float) Math.sin(a0) * h * lens(a0),
                    cx + (float) Math.cos(a1) * w, cy + (float) Math.sin(a1) * h * lens(a1), c);
        }
        if (open) {
            float pupil = h * 0.85f;
            for (int k = 0; k < steps; k++) {
                float a0 = (float) (Math.PI * 2 * k / steps);
                float a1 = (float) (Math.PI * 2 * (k + 1) / steps);
                ui.triangle(cx, cy, cx + (float) Math.cos(a0) * pupil, cy + (float) Math.sin(a0) * pupil,
                        cx + (float) Math.cos(a1) * pupil, cy + (float) Math.sin(a1) * pupil, dark);
            }
        } else {
            gui.rotate((float) Math.toRadians(-35), cx, cy);
            gui.rect(cx - w * 1.15f, cy - 1.5f, w * 2.3f, 3f, color.withAlpha(0.9f));
            gui.resetRotation();
        }
    }

    /** Fattens the almond toward its middle, so it reads as an eye rather than a diamond. */
    private static float lens(float angle) {
        return 1f + 0.35f * (float) Math.abs(Math.sin(angle));
    }

    /** A padlock: shut and lit when locked, its shackle up and open when not. */
    public static void lock(Gui gui, Rect r, boolean shut) {
        Color color = shut ? LOCKED : new Color(1f, 1f, 1f, 0.4f);
        float bodyW = r.width() * 0.4f;
        float bodyH = r.height() * 0.3f;
        float bx = r.centerX() - bodyW * 0.5f;
        float by = r.centerY() - bodyH * 0.1f;
        gui.rect(bx, by, bodyW, bodyH, color);
        float bar = Math.max(2f, r.width() * 0.07f);
        float lift = shut ? 0f : r.height() * 0.1f;
        float top = by - r.height() * 0.22f - lift;
        gui.rect(bx + bodyW * 0.12f, top, bar, by - top + (shut ? 0f : -lift), color);
        gui.rect(bx + bodyW * 0.12f, top, bodyW * 0.76f, bar, color);
        if (shut) {
            gui.rect(bx + bodyW * 0.88f - bar, top, bar, by - top, color);
        } else {
            gui.rect(bx + bodyW * 0.88f - bar, top, bar, r.height() * 0.08f, color);
        }
    }

    /**
     * Cards in rows of {@value #CARD_COLUMNS}, scrolled: only whole cards are drawn, and a thin bar down the right
     * says how far down the list the view is.
     *
     * @param scroll how far down the cards are scrolled, in pixels
     * @return the scroll kept within the cards
     */
    public static <T> float grid(Gui gui, Rect area, List<T> items, float scroll, CardDrawer<T> drawer) {
        float width = (area.width() - CARD_GAP * (CARD_COLUMNS - 1)) / CARD_COLUMNS;
        float height = width + 24f;
        int rows = Math.ceilDiv(items.size(), CARD_COLUMNS);
        float content = rows * (height + CARD_GAP) - CARD_GAP;
        scroll = Math.clamp(scroll, 0f, Math.max(0f, content - area.height()));
        for (int i = 0; i < items.size(); i++) {
            float y = area.y() + (i / CARD_COLUMNS) * (height + CARD_GAP) - scroll;
            if (y < area.y() - 0.5f || y + height > area.y() + area.height() + 0.5f) {
                continue;
            }
            drawer.draw(items.get(i), new Rect(area.x() + (i % CARD_COLUMNS) * (width + CARD_GAP), y, width, height));
        }
        if (content > area.height()) {
            float bar = area.height() * area.height() / content;
            float at = area.y() + (area.height() - bar) * scroll / (content - area.height());
            gui.rect(area.x() + area.width() + 6f, at, 3f, bar, new Color(1f, 1f, 1f, 0.3f));
        }
        return scroll;
    }

    /**
     * A card: a picture — or a colour where there is none — with its name under it, lit while it is the one chosen.
     *
     * @return whether it was clicked
     */
    public static boolean card(Gui gui, String id, Rect rect, Texture picture, Color fallback, String name, boolean chosen) {
        return card(gui, id, rect, picture == null ? null : (g, frame) -> g.image(picture, frame, Color.WHITE),
                fallback, name, chosen);
    }

    /**
     * A card whose picture is drawn by the caller into its square frame; the colour stands in for a null picture.
     *
     * @return whether it was clicked
     */
    public static boolean card(Gui gui, String id, Rect rect, Picture picture, Color fallback, String name, boolean chosen) {
        boolean clicked = gui.button(id, "", rect);
        Rect frame = frame(rect);
        if (picture != null) {
            picture.draw(gui, frame);
        } else if (fallback != null) {
            gui.rect(frame, fallback);
        } else {
            gui.textCentered("...", frame, 2f, gui.style().mutedText);
        }
        gui.textCentered(fit(gui, name, rect.width() - 8f, 1.4f), new Rect(rect.x(), rect.y() + rect.height() - 24f,
                rect.width(), 20f), 1.4f, chosen ? SELECTED : Color.WHITE);
        if (chosen) {
            gui.outline(rect, 3f, SELECTED);
        }
        return clicked;
    }

    /** The square a card's picture fills. */
    public static Rect frame(Rect card) {
        float side = Math.min(card.width() - 12f, card.height() - 30f);
        return new Rect(card.x() + (card.width() - side) * 0.5f, card.y() + 6f, side, side);
    }

    /** Text cut short with an ellipsis to fit a width. */
    public static String fit(Gui gui, String text, float width, float scale) {
        if (gui.textWidth(text, scale) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && gui.textWidth(cut + "...", scale) > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    /** Text broken into lines no wider than a width, at word breaks. */
    public static List<String> wrap(Gui gui, String text, float width, float scale) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (gui.textWidth(candidate, scale) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** A note in small text, wrapped to a column's width, taking as many of its rows as it needs. */
    public static void note(Gui gui, Gui.Column column, String text, Color color) {
        float scale = 1.5f;
        List<String> lines = wrap(gui, text, column.width(), scale);
        float lineHeight = gui.textHeight(scale) + 4f;
        Rect block = column.next(lines.size() * lineHeight);
        for (int i = 0; i < lines.size(); i++) {
            gui.text(lines.get(i), block.x(), block.y() + i * lineHeight, scale, color);
        }
    }
}
