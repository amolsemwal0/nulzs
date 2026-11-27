package org.zeronura.engine.studio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A text file open in the code editor: its lines, the caret and selection, what has changed since it was saved, and its
 * undo history. Tabs are shown as four spaces and saved that way; line endings are kept as the file had them.
 */
final class CodeDocument {

    private static final int UNDO_LIMIT = 400;

    final Path file;
    final String extension;
    List<String> lines = new ArrayList<>();
    private boolean crlf;
    private String saved;

    int caretLine;
    int caretColumn;
    int anchorLine;
    int anchorColumn;
    /** The column the caret keeps to going up and down past shorter lines. */
    int wantedColumn;
    float scrollX;

    /** Compile problems on lines of this file, by line from 0, for underlining. */
    final java.util.Map<Integer, String> problems = new java.util.HashMap<>();
    final java.util.Set<Integer> errorLines = new java.util.HashSet<>();

    // Highlighting: the state each line starts in, worked out as far as needed.
    private int[] lineStates = new int[]{JavaSyntax.NORMAL};
    private int statesValid = 1;

    private record Snapshot(List<String> lines, int caretLine, int caretColumn) {
    }

    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private long lastEditTime;
    private String lastEditKind = "";

    CodeDocument(Path file) throws IOException {
        this.file = file;
        this.extension = AssetKind.extension(file);
        reload();
    }

    void reload() throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        crlf = text.contains("\r\n");
        lines = new ArrayList<>(List.of(text.replace("\r\n", "\n").replace("\t", "    ").split("\n", -1)));
        saved = String.join("\n", lines);
        invalidate(0);
        undo.clear();
        redo.clear();
        caretLine = Math.min(caretLine, lines.size() - 1);
        caretColumn = Math.min(caretColumn, lines.get(caretLine).length());
        anchorLine = caretLine;
        anchorColumn = caretColumn;
    }

    String text() {
        return String.join("\n", lines);
    }

    boolean dirty() {
        return !text().equals(saved);
    }

    void save() throws IOException {
        String text = text();
        Files.writeString(file, crlf ? text.replace("\n", "\r\n") : text, StandardCharsets.UTF_8);
        saved = text;
    }

    String name() {
        return file.getFileName().toString();
    }

    // --- Highlighting -------------------------------------------------------------------------------------------

    void invalidate(int fromLine) {
        statesValid = Math.min(statesValid, Math.max(1, fromLine + 1));
    }

    /** The state line {@code index} starts in. */
    int stateAt(int index) {
        if (lineStates.length < lines.size() + 1) {
            lineStates = java.util.Arrays.copyOf(lineStates, lines.size() + 64);
        }
        while (statesValid <= index && statesValid <= lines.size()) {
            int line = statesValid - 1;
            lineStates[statesValid] = JavaSyntax.colour(lines.get(line), lineStates[line]).endState();
            statesValid++;
        }
        return lineStates[Math.min(index, statesValid - 1)];
    }

    // --- Selection ----------------------------------------------------------------------------------------------

    boolean hasSelection() {
        return caretLine != anchorLine || caretColumn != anchorColumn;
    }

    /** The selection's start and end as line, column, line, column, start first. */
    int[] selection() {
        boolean caretFirst = caretLine < anchorLine || (caretLine == anchorLine && caretColumn < anchorColumn);
        return caretFirst ? new int[]{caretLine, caretColumn, anchorLine, anchorColumn}
                : new int[]{anchorLine, anchorColumn, caretLine, caretColumn};
    }

    String selectedText() {
        if (!hasSelection()) {
            return "";
        }
        int[] s = selection();
        if (s[0] == s[2]) {
            return lines.get(s[0]).substring(s[1], s[3]);
        }
        StringBuilder out = new StringBuilder(lines.get(s[0]).substring(s[1]));
        for (int line = s[0] + 1; line < s[2]; line++) {
            out.append('\n').append(lines.get(line));
        }
        out.append('\n').append(lines.get(s[2]), 0, s[3]);
        return out.toString();
    }

    void moveCaret(int line, int column, boolean select) {
        caretLine = Math.clamp(line, 0, lines.size() - 1);
        caretColumn = Math.clamp(column, 0, lines.get(caretLine).length());
        if (!select) {
            anchorLine = caretLine;
            anchorColumn = caretColumn;
        }
    }

    void selectAll() {
        anchorLine = 0;
        anchorColumn = 0;
        caretLine = lines.size() - 1;
        caretColumn = lines.get(caretLine).length();
    }

    // --- Editing ------------------------------------------------------------------------------------------------

    /**
     * Records the text before an edit so it can be undone. Edits of the same kind in quick succession — letters typed,
     * Backspace held — go together as one step.
     */
    void beginEdit(String kind) {
        long now = System.currentTimeMillis();
        boolean continues = kind.equals(lastEditKind) && now - lastEditTime < 1200 && !kind.equals("other");
        lastEditTime = now;
        lastEditKind = kind;
        if (continues) {
            return;
        }
        undo.push(new Snapshot(List.copyOf(lines), caretLine, caretColumn));
        if (undo.size() > UNDO_LIMIT) {
            undo.removeLast();
        }
        redo.clear();
    }

    void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(new Snapshot(List.copyOf(lines), caretLine, caretColumn));
        restore(undo.pop());
    }

    void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(new Snapshot(List.copyOf(lines), caretLine, caretColumn));
        restore(redo.pop());
    }

    private void restore(Snapshot snapshot) {
        lines = new ArrayList<>(snapshot.lines());
        moveCaret(snapshot.caretLine(), snapshot.caretColumn(), false);
        lastEditKind = "";
        invalidate(0);
    }

    /** Deletes the selection, if there is one. */
    void deleteSelection() {
        if (!hasSelection()) {
            return;
        }
        int[] s = selection();
        String head = lines.get(s[0]).substring(0, s[1]);
        String tail = lines.get(s[2]).substring(s[3]);
        for (int line = s[2]; line > s[0]; line--) {
            lines.remove(line);
        }
        lines.set(s[0], head + tail);
        moveCaret(s[0], s[1], false);
        invalidate(s[0]);
    }

    /** Puts text in place of the selection, at the caret. */
    void insert(String text) {
        deleteSelection();
        String line = lines.get(caretLine);
        String head = line.substring(0, caretColumn);
        String tail = line.substring(caretColumn);
        String[] parts = text.replace("\r\n", "\n").replace("\t", "    ").split("\n", -1);
        if (parts.length == 1) {
            lines.set(caretLine, head + parts[0] + tail);
            moveCaret(caretLine, caretColumn + parts[0].length(), false);
        } else {
            lines.set(caretLine, head + parts[0]);
            for (int i = 1; i < parts.length; i++) {
                lines.add(caretLine + i, parts[i] + (i == parts.length - 1 ? tail : ""));
            }
            int lastLine = caretLine + parts.length - 1;
            moveCaret(lastLine, parts[parts.length - 1].length(), false);
        }
        invalidate(Math.max(0, caretLine - parts.length));
        wantedColumn = caretColumn;
    }

    /** The leading spaces of a line. */
    static String indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(0, i);
    }

    /** The start of the word before a column, for Ctrl+Left and Ctrl+Backspace. */
    int wordLeft(int line, int column) {
        String text = lines.get(line);
        int i = column;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
            while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
                i--;
            }
        } else if (i > 0) {
            i--;
        }
        return i;
    }

    int wordRight(int line, int column) {
        String text = lines.get(line);
        int i = column;
        if (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
            while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
                i++;
            }
        } else if (i < text.length()) {
            i++;
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
