package org.zeronura.engine.studio;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.gui.Gui;
import org.zeronura.engine.ui.Rect;

/** The grey checks drawn behind a picture or colour with see-through parts. */
final class Checker {

    private static final Color LIGHT = Color.rgb(0x4A4D54);
    private static final Color DARK = Color.rgb(0x3A3C42);

    private Checker() {
    }

    static void draw(Gui gui, Rect rect) {
        float cell = Math.max(6f, Math.min(16f, Math.min(rect.width(), rect.height()) / 4f));
        gui.rect(rect, DARK);
        gui.pushClip(rect);
        for (float y = rect.y(), row = 0; y < rect.y() + rect.height(); y += cell, row++) {
            for (float x = rect.x() + ((row % 2 == 0) ? 0f : cell); x < rect.x() + rect.width(); x += cell * 2f) {
                gui.rect(x, y, cell, cell, LIGHT);
            }
        }
        gui.popClip();
    }
}
