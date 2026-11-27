package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.scene.ComponentFields;
import org.zeronura.nulzs.ui.Rect;

import java.util.function.Consumer;

/**
 * A colour setting: a swatch that opens a picker with a saturation and brightness square, a hue strip, an opacity
 * slider and the colour as hex.
 */
final class ColorPicker {

    private static float hue;
    private static float saturation;
    private static float value;
    private static float alpha;
    private static String dragging;
    private static String openFor;

    private ColorPicker() {
    }

    static void field(Ui ui, String id, Rect rect, Color color, Consumer<Color> set) {
        Color shown = color == null ? Color.WHITE : color;
        Rect swatch = new Rect(rect.x(), rect.y(), rect.width() - 82f, rect.height());
        Checker.draw(ui.gui, swatch);
        ui.gui.rect(swatch, shown);
        ui.gui.rect(new Rect(swatch.x(), swatch.y() + swatch.height() - 3f, swatch.width(), 3f),
                new Color(shown.r(), shown.g(), shown.b(), 1f));
        ui.gui.outline(swatch, 1f, ui.hover(swatch) ? Theme.TEXT_MUTED : Theme.FIELD_BORDER);
        String hex = ComponentFields.colorToHex(shown);
        ui.monoField(id + ":hex", new Rect(rect.x() + rect.width() - 78f, rect.y(), 78f, rect.height()),
                hex.endsWith("FF") ? hex.substring(0, 7) : hex, null, text -> {
                    try {
                        set.accept(ComponentFields.colorFromHex(text.trim().startsWith("#") ? text.trim() : "#" + text.trim()));
                    } catch (RuntimeException ignored) {
                        // not a colour; left as it was
                    }
                });
        if (ui.clicked(id + ":swatch", swatch)) {
            float[] hsv = toHsv(shown);
            hue = hsv[0];
            saturation = hsv[1];
            value = hsv[2];
            alpha = shown.a();
            openFor = id;
            ui.openPopup("colorPicker", rect.x() - 60f, rect.y() + rect.height() + 2f,
                    (u, px, py) -> picker(u, id, px, py, set));
        }
    }

    /** Just the swatch, for a cramped row: a click opens the same picker. */
    static void swatch(Ui ui, String id, Rect rect, Color color, Consumer<Color> set) {
        Color shown = color == null ? Color.WHITE : color;
        Checker.draw(ui.gui, rect);
        ui.gui.rect(rect, shown);
        ui.gui.outline(rect, 1f, ui.hover(rect) ? Theme.TEXT_MUTED : Theme.FIELD_BORDER);
        if (ui.clicked(id + ":swatch", rect)) {
            float[] hsv = toHsv(shown);
            hue = hsv[0];
            saturation = hsv[1];
            value = hsv[2];
            alpha = shown.a();
            openFor = id;
            ui.openPopup("colorPicker", rect.x(), rect.y() + rect.height() + 2f, (u, px, py) -> picker(u, id, px, py, set));
        }
    }

    private static Rect picker(Ui ui, String id, float x, float y, Consumer<Color> set) {
        if (!id.equals(openFor)) {
            return null;
        }
        Rect box = ui.popupBox(x, y, 250f, 276f);
        Rect square = new Rect(box.x() + 10f, box.y() + 10f, 196f, 196f);
        Rect strip = new Rect(square.x() + square.width() + 10f, square.y(), 24f, square.height());
        Rect alphaBar = new Rect(square.x(), square.y() + square.height() + 12f, 230f, 16f);
        Color pure = fromHsv(hue, 1f, 1f, 1f);
        float[] outline = {square.x(), square.y(), square.x() + square.width(), square.y(),
                square.x() + square.width(), square.y() + square.height(), square.x(), square.y() + square.height()};
        ui.gui.polygonAcross(outline, Color.WHITE, pure);
        ui.gui.rounded(square, 0f, new Color(0f, 0f, 0f, 0f), Color.BLACK);
        float sx = square.x() + saturation * square.width();
        float sy = square.y() + (1f - value) * square.height();
        Icon.ring(ui.gui, sx, sy, 6f, 2f, value > 0.5f && saturation < 0.5f ? Color.BLACK : Color.WHITE);

        Color[] stops = {fromHsv(0f, 1, 1, 1), fromHsv(1 / 6f, 1, 1, 1), fromHsv(2 / 6f, 1, 1, 1), fromHsv(3 / 6f, 1, 1, 1),
                fromHsv(4 / 6f, 1, 1, 1), fromHsv(5 / 6f, 1, 1, 1), fromHsv(1f, 1, 1, 1)};
        float segment = strip.height() / 6f;
        for (int i = 0; i < 6; i++) {
            ui.gui.rounded(new Rect(strip.x(), strip.y() + i * segment, strip.width(), segment + 0.5f), 0f, stops[i], stops[i + 1]);
        }
        float hy = strip.y() + hue * strip.height();
        ui.gui.outline(new Rect(strip.x() - 2f, hy - 3f, strip.width() + 4f, 6f), 2f, Color.WHITE);

        Checker.draw(ui.gui, alphaBar);
        Color opaque = fromHsv(hue, saturation, value, 1f);
        float[] alphaOutline = {alphaBar.x(), alphaBar.y(), alphaBar.x() + alphaBar.width(), alphaBar.y(),
                alphaBar.x() + alphaBar.width(), alphaBar.y() + alphaBar.height(), alphaBar.x(), alphaBar.y() + alphaBar.height()};
        ui.gui.polygonAcross(alphaOutline, opaque.withAlpha(0f), opaque);
        float ax = alphaBar.x() + alpha * alphaBar.width();
        ui.gui.outline(new Rect(ax - 3f, alphaBar.y() - 2f, 6f, alphaBar.height() + 4f), 2f, Color.WHITE);

        if (ui.input.isMousePressed(MouseButton.LEFT)) {
            if (ui.hover(square)) {
                dragging = "square";
            } else if (ui.hover(strip)) {
                dragging = "strip";
            } else if (ui.hover(alphaBar)) {
                dragging = "alpha";
            }
        }
        if (!ui.input.isMouseDown(MouseButton.LEFT)) {
            dragging = null;
        }
        if (dragging != null) {
            switch (dragging) {
                case "square" -> {
                    saturation = Math.clamp((ui.mouseX() - square.x()) / square.width(), 0f, 1f);
                    value = 1f - Math.clamp((ui.mouseY() - square.y()) / square.height(), 0f, 1f);
                }
                case "strip" -> hue = Math.clamp((ui.mouseY() - strip.y()) / strip.height(), 0f, 1f);
                default -> alpha = Math.clamp((ui.mouseX() - alphaBar.x()) / alphaBar.width(), 0f, 1f);
            }
            set.accept(fromHsv(hue, saturation, value, alpha));
        }
        Color now = fromHsv(hue, saturation, value, alpha);
        Rect preview = new Rect(box.x() + 10f, alphaBar.y() + alphaBar.height() + 10f, 40f, 20f);
        Checker.draw(ui.gui, preview);
        ui.gui.rect(preview, now);
        ui.text(ComponentFields.colorToHex(now), preview.x() + 50f, preview.y() + 4f, Theme.SMALL_SCALE, Theme.TEXT);
        return box;
    }

    static float[] toHsv(Color color) {
        float r = color.r();
        float g = color.g();
        float b = color.b();
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h;
        if (delta < 1e-6f) {
            h = 0f;
        } else if (max == r) {
            h = ((g - b) / delta) % 6f;
        } else if (max == g) {
            h = (b - r) / delta + 2f;
        } else {
            h = (r - g) / delta + 4f;
        }
        h /= 6f;
        if (h < 0f) {
            h += 1f;
        }
        return new float[]{h, max <= 0f ? 0f : delta / max, max};
    }

    static Color fromHsv(float h, float s, float v, float a) {
        float hh = (h % 1f + 1f) % 1f * 6f;
        int sector = (int) Math.floor(hh) % 6;
        float f = hh - (float) Math.floor(hh);
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        return switch (sector) {
            case 0 -> new Color(v, t, p, a);
            case 1 -> new Color(q, v, p, a);
            case 2 -> new Color(p, v, t, a);
            case 3 -> new Color(p, q, v, a);
            case 4 -> new Color(t, p, v, a);
            default -> new Color(v, p, q, a);
        };
    }
}
