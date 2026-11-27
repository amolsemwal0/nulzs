package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.ui.Rect;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * A box in the middle of the window that has to be answered before anything else: a question with buttons, a name to
 * type, a list to pick from. While one is open the rest of the editor is dimmed and still.
 */
abstract class Dialog {

    final String title;
    final float width;
    final float height;
    boolean closed;

    Dialog(String title, float width, float height) {
        this.title = title;
        this.width = width;
        this.height = height;
    }

    /** Draws what is inside, in {@code content}. Call {@link #close} once it is answered. */
    abstract void body(Ui ui, Rect content);

    /** What Escape does: by default, close without answering. */
    void cancel() {
        close();
    }

    void close() {
        closed = true;
    }

    /** Draws the dialog over a dimmed window. */
    final void draw(Ui ui) {
        ui.beginModal();
        ui.gui.rect(0f, 0f, ui.gui.width(), ui.gui.height(), new Color(0f, 0f, 0f, 0.45f));
        float w = Math.min(width, ui.gui.width() - 40f);
        float h = Math.min(height, ui.gui.height() - 40f);
        Rect box = new Rect((ui.gui.width() - w) * 0.5f, (ui.gui.height() - h) * 0.42f, w, h);
        ui.gui.glow(box, 8f, 24f, new Color(0f, 0f, 0f, 0.5f));
        ui.gui.rounded(box, 8f, Theme.POPUP);
        ui.gui.roundedOutline(box, 8f, 1f, Theme.POPUP_BORDER);
        Rect titleBar = new Rect(box.x(), box.y(), box.width(), 40f);
        ui.heading(title, titleBar.x() + 16f, titleBar.centerY() - ui.textHeight(Theme.HEADING_SCALE) * 0.5f, Theme.TEXT_BRIGHT);
        ui.hline(box.x() + 1f, titleBar.y() + titleBar.height(), box.width() - 2f, Theme.DIVIDER);
        Rect close = new Rect(box.x() + box.width() - 34f, box.y() + 8f, 24f, 24f);
        if (ui.iconButton("dialogClose", Icon.CLOSE, close, null, false)) {
            cancel();
            return;
        }
        body(ui, new Rect(box.x() + 16f, titleBar.y() + titleBar.height() + 14f, box.width() - 32f,
                box.height() - titleBar.height() - 28f));
        if (!closed && !ui.typing() && ui.input.isKeyPressed(Key.ESCAPE)) {
            ui.input.consumeKey(Key.ESCAPE);
            cancel();
        }
    }

    /** The row of buttons along the foot of a dialog, the main one last and highlighted. Returns the one pressed or -1. */
    static int buttons(Ui ui, Rect content, List<String> labels, int primary) {
        float w = 0f;
        for (String label : labels) {
            w = Math.max(w, ui.width(label) + 32f);
        }
        w = Math.max(w, 96f);
        float x = content.x() + content.width();
        float y = content.y() + content.height() - 30f;
        int pressed = -1;
        for (int i = labels.size() - 1; i >= 0; i--) {
            x -= w;
            Rect button = new Rect(x, y, w, 30f);
            boolean clicked = i == primary ? ui.primaryButton("dialogButton:" + i, labels.get(i), button)
                    : ui.button("dialogButton:" + i, labels.get(i), button);
            if (clicked) {
                pressed = i;
            }
            x -= 8f;
        }
        return pressed;
    }

    // --- The usual kinds ------------------------------------------------------------------------------------------

    /** A message and some buttons; {@code answer} gets the index of the one pressed, or -1 for Escape. */
    static Dialog question(String title, String message, List<String> buttons, int primary, IntConsumer answer) {
        String[] lines = message.split("\n");
        return new Dialog(title, 520f, 150f + lines.length * 22f) {
            @Override
            void body(Ui ui, Rect content) {
                float y = content.y();
                for (String line : lines) {
                    ui.text(line, content.x(), y, Theme.TEXT);
                    y += 22f;
                }
                int pressed = buttons(ui, content, buttons, primary);
                if (pressed >= 0 || (!ui.typing() && ui.input.isKeyPressed(Key.ENTER))) {
                    close();
                    answer.accept(pressed >= 0 ? pressed : primary);
                }
            }

            @Override
            void cancel() {
                close();
                answer.accept(-1);
            }
        };
    }

    /**
     * A name to type. {@code check} says what is wrong with a name, or null if it will do; {@code done} gets it on OK.
     */
    static Dialog prompt(String title, String label, String initial, Function<String, String> check, Consumer<String> done) {
        String[] value = {initial};
        String fieldId = "dialogPrompt:" + title;
        return new Dialog(title, 480f, 196f) {
            private boolean focused;

            @Override
            void body(Ui ui, Rect content) {
                if (!focused) {
                    ui.focus(fieldId);
                    focused = true;
                }
                ui.text(label, content.x(), content.y(), Theme.TEXT_MUTED);
                Rect field = new Rect(content.x(), content.y() + 24f, content.width(), 28f);
                boolean wasEditing = ui.editing(fieldId);
                value[0] = ui.liveField(fieldId, field, value[0], null);
                boolean enter = wasEditing && !ui.editing(fieldId) && ui.input.isKeyPressed(Key.ENTER);
                String problem = check == null ? null : check.apply(value[0]);
                if (problem != null && !value[0].isEmpty()) {
                    ui.text(problem, content.x(), field.y() + field.height() + 8f, Theme.SMALL_SCALE, Theme.ERROR);
                }
                int pressed = buttons(ui, content, List.of("Cancel", "OK"), 1);
                if ((pressed == 1 || enter) && problem == null) {
                    close();
                    done.accept(value[0]);
                } else if (pressed == 0) {
                    close();
                }
            }
        };
    }

    /** Text to read, such as the list of shortcuts. */
    static Dialog text(String title, List<String> lines, float width) {
        return new Dialog(title, width, Math.min(760f, 110f + lines.size() * 21f)) {
            @Override
            void body(Ui ui, Rect content) {
                Rect view = new Rect(content.x(), content.y(), content.width(), content.height() - 40f);
                float contentHeight = lines.size() * 21f;
                float scroll = ui.beginScroll("dialogText:" + title, view, contentHeight);
                float y = view.y() - scroll;
                for (String line : lines) {
                    boolean heading = line.startsWith("# ");
                    String shown = heading ? line.substring(2) : line;
                    int tab = shown.indexOf('\t');
                    if (heading) {
                        ui.heading(shown, view.x(), y + 3f, Theme.TEXT_BRIGHT);
                    } else if (tab >= 0) {
                        ui.text(shown.substring(0, tab), view.x(), y + 3f, Theme.ACCENT.lerp(Color.WHITE, 0.3f));
                        ui.text(shown.substring(tab + 1), view.x() + 190f, y + 3f, Theme.TEXT);
                    } else {
                        ui.text(shown, view.x(), y + 3f, Theme.TEXT);
                    }
                    y += 21f;
                }
                ui.endScroll("dialogText:" + title, view, contentHeight);
                if (buttons(ui, content, List.of("Close"), 0) == 0) {
                    close();
                }
            }
        };
    }
}
