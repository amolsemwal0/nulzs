package org.zeronura.engine.studio;

import org.zeronura.engine.input.Input;
import org.zeronura.engine.input.Key;

/**
 * The text being typed into a one-line field: its characters, the caret, and the selection from {@link #anchor} to the
 * caret. Keys move and edit it as in any text box — arrows, Home and End, Shift to select, Ctrl to go by words,
 * Ctrl+A/C/X/V.
 */
final class LineEdit {

    enum Result { NONE, COMMIT, CANCEL, TAB }

    final StringBuilder text = new StringBuilder();
    int caret;
    int anchor;
    /** How far the text is scrolled left to keep the caret in view, in GUI units. */
    float scroll;

    LineEdit(String initial, boolean selectAll) {
        text.append(initial == null ? "" : initial);
        caret = text.length();
        anchor = selectAll ? 0 : caret;
    }

    String value() {
        return text.toString();
    }

    boolean hasSelection() {
        return anchor != caret;
    }

    int selectionStart() {
        return Math.min(anchor, caret);
    }

    int selectionEnd() {
        return Math.max(anchor, caret);
    }

    void selectAll() {
        anchor = 0;
        caret = text.length();
    }

    void replaceSelection(String inserted) {
        String clean = inserted.replace("\r", "").replace("\n", " ").replace("\t", " ");
        int start = selectionStart();
        text.replace(start, selectionEnd(), clean);
        caret = start + clean.length();
        anchor = caret;
    }

    /** Applies this frame's keys. */
    Result handle(Input input) {
        boolean ctrl = input.ctrlDown();
        boolean shift = input.shiftDown();
        if (!ctrl && !input.typedText().isEmpty()) {
            replaceSelection(input.typedText());
        }
        if (input.isKeyTyped(Key.BACKSPACE)) {
            if (!hasSelection() && caret > 0) {
                anchor = ctrl ? wordLeft(caret) : caret - 1;
            }
            replaceSelection("");
        }
        if (input.isKeyTyped(Key.DELETE)) {
            if (!hasSelection() && caret < text.length()) {
                anchor = ctrl ? wordRight(caret) : caret + 1;
            }
            replaceSelection("");
        }
        if (input.isKeyTyped(Key.LEFT)) {
            move(hasSelection() && !shift ? selectionStart() : ctrl ? wordLeft(caret) : Math.max(0, caret - 1), shift);
        }
        if (input.isKeyTyped(Key.RIGHT)) {
            move(hasSelection() && !shift ? selectionEnd() : ctrl ? wordRight(caret) : Math.min(text.length(), caret + 1), shift);
        }
        if (input.isKeyTyped(Key.HOME)) {
            move(0, shift);
        }
        if (input.isKeyTyped(Key.END)) {
            move(text.length(), shift);
        }
        if (ctrl && input.isKeyPressed(Key.A)) {
            selectAll();
        }
        if (ctrl && (input.isKeyPressed(Key.C) || input.isKeyPressed(Key.X)) && hasSelection()) {
            input.setClipboard(text.substring(selectionStart(), selectionEnd()));
            if (input.isKeyPressed(Key.X)) {
                replaceSelection("");
            }
        }
        if (ctrl && input.isKeyTyped(Key.V)) {
            replaceSelection(input.clipboard());
        }
        if (input.isKeyPressed(Key.ENTER) || input.isKeyPressed(GLFW_KP_ENTER)) {
            return Result.COMMIT;
        }
        if (input.isKeyPressed(Key.ESCAPE)) {
            input.consumeKey(Key.ESCAPE);
            return Result.CANCEL;
        }
        if (input.isKeyPressed(Key.TAB)) {
            return Result.TAB;
        }
        return Result.NONE;
    }

    private static final int GLFW_KP_ENTER = 335;

    private void move(int to, boolean select) {
        caret = to;
        if (!select) {
            anchor = to;
        }
    }

    private int wordLeft(int from) {
        int i = from;
        while (i > 0 && !Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private int wordRight(int from) {
        int i = from;
        while (i < text.length() && !Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
