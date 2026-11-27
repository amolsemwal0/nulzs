package org.zeronura.engine.studio;

import org.zeronura.engine.Window;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.ui.Rect;
import org.zeronura.engine.ui.Typeface;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The script editor: a file's text with line numbers, colours for Java, the caret and selection, and the problems the
 * last compile found underlined in red. Typing indents new lines to match, Tab and Shift+Tab indent and outdent,
 * Ctrl+/ comments lines in and out, Ctrl+F finds, and a file changed on disk by something else is read again unless it
 * has changes of its own.
 */
final class CodeEditor {

    private static final float SCALE = Theme.CODE_SCALE;

    private final StudioScene studio;
    private boolean mouseSelecting;
    boolean findOpen;
    private String find = "";
    private final Map<CodeDocument, FileTime> seen = new HashMap<>();
    private double lastDiskCheck;

    private float lineHeight;
    private float charWidth;
    private float viewHeight = 400f;
    private float viewWidth = 600f;

    CodeEditor(StudioScene studio) {
        this.studio = studio;
    }

    void draw(Ui ui, Rect area, CodeDocument doc) {
        lineHeight = Math.round(ui.textHeight(SCALE) * 1.85f);
        charWidth = ui.gui.textWidth("0000000000", SCALE, Typeface.MONO) / 10f;
        watchDisk(ui, doc);
        boolean focused = studio.focus() == StudioScene.Focus.CODE;
        if (focused && !ui.typing() && !ui.modal()) {
            keys(ui, doc);
        }

        Rect bar = new Rect(area.x(), area.y(), area.width(), 26f);
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        ui.textIn(ui.fit(studio.displayPath(doc.file), bar.width() * 0.5f), bar, bar.x() + 10f, Theme.SMALL_SCALE,
                Theme.TEXT_MUTED);
        String where = "Ln " + (doc.caretLine + 1) + ", Col " + (doc.caretColumn + 1) + (doc.dirty() ? "   modified" : "");
        float right = bar.x() + bar.width() - 10f;
        ui.textRight(where, bar, right, Theme.SMALL_SCALE, doc.dirty() ? Theme.WARNING : Theme.TEXT_MUTED);
        if (findOpen) {
            boolean wasEditing = ui.editing("codeFind");
            float fw = Math.min(260f, bar.width() * 0.3f);
            Rect field = new Rect(right - ui.width(where, Theme.SMALL_SCALE) - fw - 70f, bar.y() + 2f, fw, 22f);
            find = ui.searchField("codeFind", field, find, "Find");
            if (wasEditing && (ui.input.isKeyPressed(Key.ENTER))) {
                findNext(doc, !ui.input.shiftDown());
                ui.focus("codeFind");
            }
            if (wasEditing && ui.input.isKeyPressed(Key.ESCAPE)) {
                findOpen = false;
            }
            Rect close = new Rect(field.x() + field.width() + 4f, field.y(), 22f, 22f);
            if (ui.iconButton("codeFindClose", Icon.CLOSE, close, "Close the find bar", false)) {
                findOpen = false;
            }
            int count = countMatches(doc);
            ui.textIn(count + " found", bar, close.x() + 26f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        }

        Rect body = new Rect(area.x(), area.y() + bar.height(), area.width(), area.height() - bar.height());
        ui.fill(body, Theme.FIELD);
        int digits = Math.max(3, Integer.toString(doc.lines.size()).length());
        float gutter = (digits + 2) * charWidth + 8f;
        Rect text = new Rect(body.x() + gutter, body.y(), body.width() - gutter, body.height());
        viewHeight = body.height();
        viewWidth = text.width();
        String scrollId = "code:" + doc.file;
        float content = doc.lines.size() * lineHeight + body.height() * 0.5f;

        // Pointer: place the caret, select with a drag or a double press, scroll sideways with Shift.
        boolean hoverText = ui.hover(text);
        if (hoverText) {
            ui.cursor(Window.CursorShape.TEXT);
        }
        if (ui.pressed(body) || ui.rightPressed(body)) {
            studio.setFocus(StudioScene.Focus.CODE);
        }
        if (hoverText && ui.input.shiftDown() && ui.input.scroll() != 0f && ui.takeScroll(text)) {
            doc.scrollX = Math.max(0f, doc.scrollX - ui.input.scroll() * charWidth * 6f);
        }
        float scroll = ui.scroll(scrollId);
        if (ui.doublePressed("codeText:" + doc.file, text)) {
            int[] at = position(ui, doc, text, scroll);
            String line = doc.lines.get(at[0]);
            int start = at[1];
            int end = at[1];
            while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
                start--;
            }
            while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
                end++;
            }
            doc.moveCaret(at[0], start, false);
            doc.moveCaret(at[0], end, true);
            mouseSelecting = false;
        } else if (ui.pressed(text)) {
            int[] at = position(ui, doc, text, scroll);
            doc.moveCaret(at[0], at[1], ui.input.shiftDown());
            doc.wantedColumn = doc.caretColumn;
            mouseSelecting = true;
        }
        if (mouseSelecting) {
            if (ui.input.isMouseDown(MouseButton.LEFT)) {
                int[] at = position(ui, doc, text, scroll);
                doc.moveCaret(at[0], at[1], true);
                if (ui.mouseY() < text.y()) {
                    ui.setScroll(scrollId, scroll - lineHeight);
                } else if (ui.mouseY() > text.y() + text.height()) {
                    ui.setScroll(scrollId, scroll + lineHeight);
                }
            } else {
                mouseSelecting = false;
            }
        }
        Rect gutterRect = new Rect(body.x(), body.y(), gutter, body.height());
        if (ui.pressed(gutterRect)) {
            int line = Math.clamp((int) ((ui.mouseY() - body.y() + scroll) / lineHeight), 0, doc.lines.size() - 1);
            doc.moveCaret(line, 0, false);
            doc.moveCaret(line + 1 < doc.lines.size() ? line + 1 : line,
                    line + 1 < doc.lines.size() ? 0 : doc.lines.get(line).length(), true);
        }
        if (ui.rightPressed(text)) {
            Menu.openAtPointer(ui, "codeMenu", contextMenu(doc));
        }

        scroll = ui.beginScroll(scrollId, body, content);
        int first = Math.max(0, (int) (scroll / lineHeight));
        int last = Math.min(doc.lines.size() - 1, (int) ((scroll + body.height()) / lineHeight) + 1);
        int[] selection = doc.hasSelection() ? doc.selection() : null;
        int[] bracket = matchingBracket(doc);
        float textX = text.x() + 4f - doc.scrollX;
        String needle = findOpen && !find.isEmpty() ? find.toLowerCase(Locale.ROOT) : null;
        float glyphTop = (lineHeight - ui.textHeight(SCALE)) * 0.5f;

        // The current line's band, under the text.
        if (selection == null) {
            float cy = body.y() + doc.caretLine * lineHeight - scroll;
            ui.gui.rect(body.x(), cy, body.width(), lineHeight, focused ? Theme.PANEL_ALT : Theme.PANEL.withAlpha(0.6f));
        }
        ui.gui.pushClip(text);
        for (int i = first; i <= last; i++) {
            float y = body.y() + i * lineHeight - scroll;
            String line = doc.lines.get(i);
            if (selection != null && i >= selection[0] && i <= selection[2]) {
                int from = i == selection[0] ? selection[1] : 0;
                int to = i == selection[2] ? selection[3] : line.length() + 1;
                ui.gui.rect(textX + from * charWidth, y, Math.max(charWidth * 0.4f, (to - from) * charWidth), lineHeight,
                        focused ? Theme.SELECTION : Theme.SELECTION_INACTIVE);
            }
            if (needle != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                for (int at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + needle.length())) {
                    ui.gui.rect(textX + at * charWidth, y + 1f, needle.length() * charWidth, lineHeight - 2f,
                            Theme.HIGHLIGHT.withAlpha(0.28f));
                }
            }
            if (bracket != null) {
                for (int k = 0; k < 4; k += 2) {
                    if (bracket[k] == i) {
                        ui.gui.outline(new Rect(textX + bracket[k + 1] * charWidth, y + 1f, charWidth, lineHeight - 2f), 1f,
                                Theme.TEXT_MUTED);
                    }
                }
            }
            drawLine(ui, doc, i, line, textX, y + glyphTop);
            if (doc.problems.containsKey(i)) {
                boolean error = doc.errorLines.contains(i);
                float underlineY = y + lineHeight - 3f;
                int start = CodeDocument.indentOf(line).length();
                float x0 = textX + start * charWidth;
                float x1 = textX + Math.max(start + 1, line.length()) * charWidth;
                for (float x = x0; x < x1; x += 4f) {
                    ui.gui.line(x, underlineY + ((int) ((x - x0) / 4f) % 2 == 0 ? 0f : 2f), Math.min(x1, x + 4f),
                            underlineY + ((int) ((x - x0) / 4f) % 2 == 0 ? 2f : 0f), 1.2f, error ? Theme.ERROR : Theme.WARNING);
                }
                ui.tooltip("problem:" + i, new Rect(text.x(), y, text.width(), lineHeight), doc.problems.get(i));
            }
        }
        // The caret, blinking while the editor has the keys.
        if (focused && ((long) (ui.time() * 1.8)) % 2 == 0) {
            float cx = textX + doc.caretColumn * charWidth;
            float cy = body.y() + doc.caretLine * lineHeight - scroll;
            ui.gui.rect(cx - 1f, cy + 2f, 2f, lineHeight - 4f, Theme.TEXT_BRIGHT);
        }
        ui.gui.popClip();

        // Line numbers.
        ui.gui.rect(gutterRect, Theme.FIELD);
        ui.vline(body.x() + gutter - 1f, body.y(), body.height(), Theme.DIVIDER);
        for (int i = first; i <= last; i++) {
            float y = body.y() + i * lineHeight - scroll;
            String number = Integer.toString(i + 1);
            Color color = i == doc.caretLine ? Theme.TEXT : Theme.TEXT_DISABLED;
            ui.gui.textRight(number, body.x() + gutter - 10f, y + glyphTop, SCALE * 0.9f, color, Typeface.MONO);
            if (doc.problems.containsKey(i)) {
                ui.gui.circle(body.x() + 7f, y + lineHeight * 0.5f, 4f, doc.errorLines.contains(i) ? Theme.ERROR : Theme.WARNING);
                ui.tooltip("gutter:" + i, new Rect(body.x(), y, gutter, lineHeight), doc.problems.get(i));
            }
        }
        ui.endScroll(scrollId, body, content);
    }

    private void drawLine(Ui ui, CodeDocument doc, int index, String line, float x, float y) {
        if (line.isEmpty()) {
            return;
        }
        // Only the part in view is drawn: long lines scrolled sideways cost nothing off screen.
        if (!JavaSyntax.colours(doc.extension)) {
            ui.gui.text(line, x, y, SCALE, JavaSyntax.Kind.PLAIN.color, Typeface.MONO);
            return;
        }
        JavaSyntax.Line coloured = JavaSyntax.colour(line, doc.stateAt(index));
        int drawnTo = 0;
        for (JavaSyntax.Token token : coloured.tokens()) {
            if (token.start() > drawnTo) {
                drawnTo = token.start();
            }
            ui.gui.text(line.substring(token.start(), token.end()), x + token.start() * charWidth, y, SCALE,
                    token.kind().color, Typeface.MONO);
            drawnTo = token.end();
        }
    }

    private int[] position(Ui ui, CodeDocument doc, Rect text, float scroll) {
        int line = Math.clamp((int) ((ui.mouseY() - text.y() + scroll) / lineHeight), 0, doc.lines.size() - 1);
        int column = Math.round((ui.mouseX() - text.x() - 4f + doc.scrollX) / charWidth);
        return new int[]{line, Math.clamp(column, 0, doc.lines.get(line).length())};
    }

    /** Keeps the caret on screen after it moves. */
    void reveal(Ui ui, CodeDocument doc, float viewHeight, float viewWidth) {
        String id = "code:" + doc.file;
        float top = doc.caretLine * lineHeight;
        ui.scrollTo(id, top - lineHeight, top + lineHeight * 2f, viewHeight);
        float x = doc.caretColumn * charWidth;
        if (x - doc.scrollX > viewWidth - charWidth * 4f) {
            doc.scrollX = x - viewWidth + charWidth * 8f;
        } else if (x < doc.scrollX + charWidth * 2f) {
            doc.scrollX = Math.max(0f, x - charWidth * 8f);
        }
    }

    /** Moves to a line and shows it in the middle of the view, as a click on a problem does. */
    void goToLine(Ui ui, CodeDocument doc, int line) {
        doc.moveCaret(line - 1, CodeDocument.indentOf(doc.lines.get(Math.clamp(line - 1, 0, doc.lines.size() - 1))).length(), false);
        float height = lineHeight > 0f ? lineHeight : 20f;
        ui.setScroll("code:" + doc.file, Math.max(0f, (line - 1) * height - 200f));
    }

    // --- Keys ---------------------------------------------------------------------------------------------------

    private void keys(Ui ui, CodeDocument doc) {
        var input = ui.input;
        boolean ctrl = input.ctrlDown();
        boolean shift = input.shiftDown();
        boolean moved = false;
        boolean edited = false;
        String typed = input.typedText();
        if (!typed.isEmpty() && !ctrl) {
            typed = typed.replace("\t", "    ");
            if (typed.equals("}") && !doc.hasSelection()
                    && doc.lines.get(doc.caretLine).substring(0, doc.caretColumn).isBlank() && doc.caretColumn >= 4) {
                doc.beginEdit("other");
                String line = doc.lines.get(doc.caretLine);
                doc.lines.set(doc.caretLine, line.substring(4));
                doc.moveCaret(doc.caretLine, doc.caretColumn - 4, false);
            } else {
                doc.beginEdit(Character.isLetterOrDigit(typed.charAt(0)) ? "type" : "other");
            }
            doc.insert(typed);
            edited = true;
        }
        if (input.isKeyTyped(Key.ENTER)) {
            doc.beginEdit("other");
            enter(doc);
            edited = true;
        }
        if (input.isKeyTyped(Key.TAB)) {
            doc.beginEdit("other");
            if (shift || doc.hasSelection() && doc.selection()[0] != doc.selection()[2]) {
                indentLines(doc, shift ? -1 : 1);
            } else {
                doc.insert(" ".repeat(4 - doc.caretColumn % 4));
            }
            edited = true;
        }
        if (input.isKeyTyped(Key.BACKSPACE)) {
            doc.beginEdit("delete");
            if (!doc.hasSelection()) {
                String line = doc.lines.get(doc.caretLine);
                if (ctrl) {
                    doc.anchorLine = doc.caretLine;
                    doc.anchorColumn = doc.caretColumn;
                    if (doc.caretColumn == 0 && doc.caretLine > 0) {
                        doc.moveCaret(doc.caretLine - 1, doc.lines.get(doc.caretLine - 1).length(), true);
                    } else {
                        doc.moveCaret(doc.caretLine, doc.wordLeft(doc.caretLine, doc.caretColumn), true);
                    }
                } else if (doc.caretColumn > 0 && line.substring(0, doc.caretColumn).isBlank()) {
                    int back = doc.caretColumn % 4 == 0 ? 4 : doc.caretColumn % 4;
                    doc.moveCaret(doc.caretLine, doc.caretColumn - back, true);
                } else if (doc.caretColumn > 0) {
                    doc.moveCaret(doc.caretLine, doc.caretColumn - 1, true);
                } else if (doc.caretLine > 0) {
                    doc.moveCaret(doc.caretLine - 1, doc.lines.get(doc.caretLine - 1).length(), true);
                }
            }
            doc.deleteSelection();
            edited = true;
        }
        if (input.isKeyTyped(Key.DELETE)) {
            doc.beginEdit("delete");
            if (!doc.hasSelection()) {
                int length = doc.lines.get(doc.caretLine).length();
                if (doc.caretColumn < length) {
                    doc.moveCaret(doc.caretLine, ctrl ? doc.wordRight(doc.caretLine, doc.caretColumn) : doc.caretColumn + 1, true);
                } else if (doc.caretLine < doc.lines.size() - 1) {
                    doc.moveCaret(doc.caretLine + 1, 0, true);
                }
            }
            doc.deleteSelection();
            edited = true;
        }
        if (input.isKeyTyped(Key.LEFT)) {
            if (doc.hasSelection() && !shift) {
                int[] s = doc.selection();
                doc.moveCaret(s[0], s[1], false);
            } else if (doc.caretColumn == 0 && doc.caretLine > 0) {
                doc.moveCaret(doc.caretLine - 1, doc.lines.get(doc.caretLine - 1).length(), shift);
            } else {
                doc.moveCaret(doc.caretLine, ctrl ? doc.wordLeft(doc.caretLine, doc.caretColumn) : doc.caretColumn - 1, shift);
            }
            doc.wantedColumn = doc.caretColumn;
            moved = true;
        }
        if (input.isKeyTyped(Key.RIGHT)) {
            int length = doc.lines.get(doc.caretLine).length();
            if (doc.hasSelection() && !shift) {
                int[] s = doc.selection();
                doc.moveCaret(s[2], s[3], false);
            } else if (doc.caretColumn >= length && doc.caretLine < doc.lines.size() - 1) {
                doc.moveCaret(doc.caretLine + 1, 0, shift);
            } else {
                doc.moveCaret(doc.caretLine, ctrl ? doc.wordRight(doc.caretLine, doc.caretColumn) : doc.caretColumn + 1, shift);
            }
            doc.wantedColumn = doc.caretColumn;
            moved = true;
        }
        int pageLines = Math.max(1, (int) (viewHeight / Math.max(1f, lineHeight)) - 2);
        if (input.isKeyTyped(Key.UP)) {
            doc.moveCaret(doc.caretLine - 1, doc.wantedColumn, shift);
            moved = true;
        }
        if (input.isKeyTyped(Key.DOWN)) {
            doc.moveCaret(doc.caretLine + 1, doc.wantedColumn, shift);
            moved = true;
        }
        if (input.isKeyTyped(Key.PAGE_UP)) {
            doc.moveCaret(doc.caretLine - pageLines, doc.wantedColumn, shift);
            moved = true;
        }
        if (input.isKeyTyped(Key.PAGE_DOWN)) {
            doc.moveCaret(doc.caretLine + pageLines, doc.wantedColumn, shift);
            moved = true;
        }
        if (input.isKeyTyped(Key.HOME)) {
            if (ctrl) {
                doc.moveCaret(0, 0, shift);
            } else {
                int indent = CodeDocument.indentOf(doc.lines.get(doc.caretLine)).length();
                doc.moveCaret(doc.caretLine, doc.caretColumn == indent ? 0 : indent, shift);
            }
            doc.wantedColumn = doc.caretColumn;
            moved = true;
        }
        if (input.isKeyTyped(Key.END)) {
            if (ctrl) {
                doc.moveCaret(doc.lines.size() - 1, Integer.MAX_VALUE, shift);
            } else {
                doc.moveCaret(doc.caretLine, Integer.MAX_VALUE, shift);
            }
            doc.wantedColumn = doc.caretColumn;
            moved = true;
        }
        if (ctrl && input.isKeyTyped(Key.SLASH)) {
            doc.beginEdit("other");
            toggleComment(doc);
            edited = true;
        }
        if (ctrl && input.isKeyPressed(Key.F)) {
            findOpen = true;
            if (doc.hasSelection() && doc.selection()[0] == doc.selection()[2]) {
                find = doc.selectedText();
            }
            ui.focus("codeFind");
        }
        if (input.isKeyPressed(Key.F3)) {
            findNext(doc, !shift);
            moved = true;
        }
        if (input.isKeyPressed(Key.ESCAPE) && findOpen) {
            findOpen = false;
        }
        if (moved || edited) {
            reveal(ui, doc, viewHeight, viewWidth);
        }
    }

    private void enter(CodeDocument doc) {
        doc.deleteSelection();
        String line = doc.lines.get(doc.caretLine);
        String before = line.substring(0, doc.caretColumn);
        String after = line.substring(doc.caretColumn);
        String indent = CodeDocument.indentOf(line);
        if (indent.length() > before.length()) {
            indent = indent.substring(0, before.length());
        }
        String trimmed = before.trim();
        boolean opens = trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[");
        if (opens && (after.trim().startsWith("}") || after.trim().startsWith(")") || after.trim().startsWith("]"))) {
            doc.insert("\n" + indent + "    \n" + indent);
            doc.moveCaret(doc.caretLine - 1, indent.length() + 4, false);
        } else {
            doc.lines.set(doc.caretLine, before);
            doc.lines.add(doc.caretLine + 1, (indent + (opens ? "    " : "")) + after.stripLeading());
            doc.invalidate(doc.caretLine);
            doc.moveCaret(doc.caretLine + 1, indent.length() + (opens ? 4 : 0), false);
        }
        doc.wantedColumn = doc.caretColumn;
    }

    private static void indentLines(CodeDocument doc, int direction) {
        int[] s = doc.hasSelection() ? doc.selection() : new int[]{doc.caretLine, doc.caretColumn, doc.caretLine, doc.caretColumn};
        int lastLine = s[3] == 0 && s[2] > s[0] ? s[2] - 1 : s[2];
        for (int i = s[0]; i <= lastLine; i++) {
            String line = doc.lines.get(i);
            if (direction > 0) {
                doc.lines.set(i, "    " + line);
            } else {
                int remove = Math.min(4, CodeDocument.indentOf(line).length());
                doc.lines.set(i, line.substring(remove));
            }
        }
        doc.invalidate(s[0]);
        int shift = direction > 0 ? 4 : -4;
        doc.anchorLine = s[0];
        doc.anchorColumn = Math.max(0, s[1] + shift);
        doc.moveCaret(lastLine, Math.max(0, (s[2] == lastLine ? s[3] : doc.lines.get(lastLine).length()) + shift), true);
    }

    private static void toggleComment(CodeDocument doc) {
        int[] s = doc.hasSelection() ? doc.selection() : new int[]{doc.caretLine, 0, doc.caretLine, 0};
        int lastLine = s[3] == 0 && s[2] > s[0] ? s[2] - 1 : s[2];
        boolean allCommented = true;
        int minIndent = Integer.MAX_VALUE;
        for (int i = s[0]; i <= lastLine; i++) {
            String line = doc.lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            minIndent = Math.min(minIndent, CodeDocument.indentOf(line).length());
            allCommented &= line.stripLeading().startsWith("//");
        }
        if (minIndent == Integer.MAX_VALUE) {
            return;
        }
        for (int i = s[0]; i <= lastLine; i++) {
            String line = doc.lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (allCommented) {
                int at = line.indexOf("//");
                String rest = line.substring(at + 2);
                doc.lines.set(i, line.substring(0, at) + (rest.startsWith(" ") ? rest.substring(1) : rest));
            } else {
                doc.lines.set(i, line.substring(0, minIndent) + "// " + line.substring(minIndent));
            }
        }
        doc.invalidate(s[0]);
        doc.moveCaret(doc.caretLine, doc.caretColumn, doc.hasSelection());
    }

    // --- Commands the Edit menu sends here when the editor has the focus ---------------------------------------------

    void copy(Ui ui, CodeDocument doc) {
        ui.input.setClipboard(doc.hasSelection() ? doc.selectedText() : doc.lines.get(doc.caretLine) + "\n");
    }

    void cut(Ui ui, CodeDocument doc) {
        copy(ui, doc);
        doc.beginEdit("other");
        if (!doc.hasSelection()) {
            doc.anchorLine = doc.caretLine;
            doc.anchorColumn = 0;
            if (doc.caretLine < doc.lines.size() - 1) {
                doc.moveCaret(doc.caretLine + 1, 0, true);
            } else {
                doc.moveCaret(doc.caretLine, Integer.MAX_VALUE, true);
            }
        }
        doc.deleteSelection();
    }

    void paste(Ui ui, CodeDocument doc) {
        String text = ui.input.clipboard();
        if (text.isEmpty()) {
            return;
        }
        doc.beginEdit("other");
        doc.insert(text);
        reveal(ui, doc, viewHeight, viewWidth);
    }

    void duplicateLine(CodeDocument doc) {
        doc.beginEdit("other");
        if (doc.hasSelection()) {
            String selected = doc.selectedText();
            int[] s = doc.selection();
            doc.moveCaret(s[2], s[3], false);
            doc.insert(selected);
            return;
        }
        String line = doc.lines.get(doc.caretLine);
        doc.lines.add(doc.caretLine + 1, line);
        doc.invalidate(doc.caretLine);
        doc.moveCaret(doc.caretLine + 1, doc.caretColumn, false);
    }

    private List<Menu.Item> contextMenu(CodeDocument doc) {
        Ui ui = studio.ui;
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Command("Cut", () -> cut(ui, doc)));
        items.add(new Menu.Command("Copy", () -> copy(ui, doc)));
        items.add(new Menu.Command("Paste", () -> paste(ui, doc)));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Command("Select All", doc::selectAll));
        items.add(new Menu.Command("Toggle Comment", () -> {
            doc.beginEdit("other");
            toggleComment(doc);
        }));
        items.add(new Menu.Command("Find", () -> {
            findOpen = true;
            ui.focus("codeFind");
        }));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Command("Save", () -> studio.saveCode(doc)));
        return items;
    }

    // --- Finding ------------------------------------------------------------------------------------------------

    private void findNext(CodeDocument doc, boolean forward) {
        if (find.isEmpty()) {
            return;
        }
        String needle = find.toLowerCase(Locale.ROOT);
        int lines = doc.lines.size();
        int startLine = doc.caretLine;
        int startColumn = forward ? doc.caretColumn : (doc.hasSelection() ? doc.selection()[1] : doc.caretColumn);
        for (int step = 0; step <= lines; step++) {
            int index = Math.floorMod(startLine + (forward ? step : -step), lines);
            String lower = doc.lines.get(index).toLowerCase(Locale.ROOT);
            int at;
            if (forward) {
                at = lower.indexOf(needle, step == 0 ? startColumn : 0);
            } else {
                at = step == 0 ? (startColumn > 0 ? lower.lastIndexOf(needle, startColumn - 1) : -1) : lower.lastIndexOf(needle);
            }
            if (at >= 0) {
                doc.moveCaret(index, at, false);
                doc.moveCaret(index, at + needle.length(), true);
                if (!forward) {
                    doc.anchorColumn = at + needle.length();
                    doc.caretColumn = at;
                }
                reveal(studio.ui, doc, viewHeight, viewWidth);
                return;
            }
        }
    }

    private int countMatches(CodeDocument doc) {
        if (find.isEmpty()) {
            return 0;
        }
        String needle = find.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String line : doc.lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (int at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + needle.length())) {
                count++;
            }
        }
        return count;
    }

    private static int[] matchingBracket(CodeDocument doc) {
        String line = doc.lines.get(doc.caretLine);
        int column = doc.caretColumn;
        for (int at : new int[]{column - 1, column}) {
            if (at < 0 || at >= line.length()) {
                continue;
            }
            char c = line.charAt(at);
            int open = "([{".indexOf(c);
            int close = ")]}".indexOf(c);
            if (open < 0 && close < 0) {
                continue;
            }
            char want = open >= 0 ? ")]}".charAt(open) : "([{".charAt(close);
            int direction = open >= 0 ? 1 : -1;
            int depth = 0;
            int l = doc.caretLine;
            int i = at;
            for (int guard = 0; guard < 20000; guard++) {
                String text = doc.lines.get(l);
                if (i >= 0 && i < text.length()) {
                    char here = text.charAt(i);
                    if (here == c) {
                        depth++;
                    } else if (here == want) {
                        depth--;
                        if (depth == 0) {
                            return new int[]{doc.caretLine, at, l, i};
                        }
                    }
                }
                i += direction;
                if (i < 0 || i >= text.length()) {
                    l += direction;
                    if (l < 0 || l >= doc.lines.size()) {
                        return null;
                    }
                    i = direction > 0 ? 0 : doc.lines.get(l).length() - 1;
                }
            }
            return null;
        }
        return null;
    }

    // --- The file on disk ---------------------------------------------------------------------------------------

    private void watchDisk(Ui ui, CodeDocument doc) {
        if (ui.time() - lastDiskCheck < 1.0) {
            return;
        }
        lastDiskCheck = ui.time();
        try {
            FileTime modified = Files.getLastModifiedTime(doc.file);
            FileTime known = seen.get(doc);
            if (known == null) {
                seen.put(doc, modified);
            } else if (!known.equals(modified)) {
                seen.put(doc, modified);
                if (!doc.dirty()) {
                    doc.reload();
                    studio.status("Reloaded " + doc.name() + ", changed on disk", false);
                } else {
                    studio.status(doc.name() + " changed on disk; your changes are kept", true);
                }
            }
        } catch (IOException ignored) {
            // the file went away; it stays open with its text
        }
    }

    /** Remembers the file's time after the editor itself saves it, so the save isn't taken for a change from outside. */
    void saved(CodeDocument doc) {
        try {
            seen.put(doc, Files.getLastModifiedTime(doc.file));
        } catch (IOException ignored) {
            // nothing to remember
        }
    }
}
