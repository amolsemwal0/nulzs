package org.zeronura.engine.gui;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.ui.Rect;

/**
 * Keys drawn across the bottom of the screen while a text field is being typed into with a controller or a finger,
 * where there is no keyboard to hand: a Steam Deck in its handheld mode, a phone, a pad on the sofa. Each key is a
 * button — moved between with the D-pad and pressed with A, or tapped — and types into the focused field as the
 * physical keyboard would. Done (or B) closes it.
 */
final class OnScreenKeyboard {

    private static final String[] ROWS = {"1234567890", "QWERTYUIOP", "ASDFGHJKL'", "ZXCVBNM-.!"};
    private static final float KEY = 78f;
    private static final float GAP = 8f;
    private static final Color BACKDROP = new Color(0.04f, 0.05f, 0.07f, 0.94f);

    private boolean upper = true;

    void draw(Gui gui, String text) {
        float width = gui.width();
        float height = gui.height();
        float rowWidth = 10f * KEY + 9f * GAP;
        float panelHeight = 5f * KEY + 6f * GAP + 70f;
        Rect panel = new Rect(0f, height - panelHeight, width, panelHeight);
        gui.rect(panel, BACKDROP);
        float left = (width - rowWidth) * 0.5f;
        float top = panel.y() + 14f;

        // What has been typed so far, since the field itself may be under the keys.
        Rect preview = new Rect(left, top, rowWidth, 48f);
        gui.rect(preview, gui.style().field);
        gui.outline(preview, 2f, gui.style().accent);
        gui.text(text + "_", preview.x() + 14f, preview.centerY() - gui.textHeight(3f) * 0.5f, 3f, Color.WHITE);
        top += 62f;

        for (int r = 0; r < ROWS.length; r++) {
            String row = ROWS[r];
            for (int k = 0; k < row.length(); k++) {
                char c = row.charAt(k);
                String shown = upper || !Character.isLetter(c) ? String.valueOf(c) : String.valueOf(Character.toLowerCase(c));
                Rect key = new Rect(left + k * (KEY + GAP), top + r * (KEY + GAP), KEY, KEY);
                if (gui.button("osk:" + r + ":" + k, shown, key)) {
                    gui.input().injectText(shown);
                    if (upper && Character.isLetter(c)) {
                        upper = false;   // Capitals for the first letter, as on a phone.
                    }
                }
            }
        }
        float y = top + 4f * (KEY + GAP);
        float wide = (rowWidth - 3f * GAP) / 5f;
        if (gui.button("osk:shift", upper ? "abc" : "ABC", new Rect(left, y, wide, KEY))) {
            upper = !upper;
        }
        if (gui.button("osk:space", "Space", new Rect(left + wide + GAP, y, 2f * wide, KEY))) {
            gui.input().injectText(" ");
        }
        if (gui.button("osk:back", "Delete", new Rect(left + 3f * wide + 2f * GAP, y, wide, KEY))) {
            gui.input().injectBackspace();
        }
        if (gui.button("osk:done", "Done", new Rect(left + 4f * wide + 3f * GAP, y, wide, KEY))) {
            gui.finishTyping();
            upper = true;
        }
    }
}
