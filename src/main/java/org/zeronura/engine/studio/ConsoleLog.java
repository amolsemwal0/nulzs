package org.zeronura.engine.studio;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The lines the Console panel shows: what the editor prints, what compiling says, and what a game started from the
 * editor prints. Lines arrive from other threads (a running game's output, a build), so everything here is synchronised.
 */
final class ConsoleLog {

    enum Level { INFO, WARNING, ERROR }

    record Line(long id, double time, Level level, String source, String text) {
    }

    private static final int MAX_LINES = 6000;

    private final List<Line> lines = new ArrayList<>();
    private long nextId = 1;
    private final long started = System.nanoTime();
    private int warnings;
    private int errors;
    private PrintStream originalOut;
    private PrintStream originalErr;

    synchronized void add(Level level, String source, String text) {
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        for (String part : body.split("\n", -1)) {
            String clean = part.replace("\r", "").replace("\t", "    ");
            lines.add(new Line(nextId++, (System.nanoTime() - started) / 1e9, level, source, clean));
            if (level == Level.WARNING) {
                warnings++;
            } else if (level == Level.ERROR) {
                errors++;
            }
        }
        if (lines.size() > MAX_LINES) {
            lines.subList(0, lines.size() - MAX_LINES).clear();
        }
    }

    void info(String text) {
        add(Level.INFO, "Editor", text);
    }

    void warn(String text) {
        add(Level.WARNING, "Editor", text);
    }

    void error(String text) {
        add(Level.ERROR, "Editor", text);
    }

    synchronized List<Line> snapshot() {
        return List.copyOf(lines);
    }

    synchronized Line last() {
        return lines.isEmpty() ? null : lines.getLast();
    }

    synchronized void clear() {
        lines.clear();
        warnings = 0;
        errors = 0;
    }

    synchronized int warnings() {
        return warnings;
    }

    synchronized int errors() {
        return errors;
    }

    /** The level a line of program output reads as: errors and exceptions red, warnings yellow. */
    static Level levelOf(String text, boolean fromErr) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("exception") || lower.contains("error") || lower.startsWith("\tat ") || lower.startsWith("    at ")
                || lower.startsWith("caused by")) {
            return Level.ERROR;
        }
        if (lower.contains("warn") || fromErr) {
            return Level.WARNING;
        }
        return Level.INFO;
    }

    /** From here on, what the editor prints also comes here, line by line. It still goes where it went. */
    void captureSystemStreams() {
        if (originalOut != null) {
            return;
        }
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(new LineTee(originalOut, false), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LineTee(originalErr, true), true, StandardCharsets.UTF_8));
    }

    /** Puts the streams back as they were, for when the editor closes. */
    void releaseSystemStreams() {
        if (originalOut != null) {
            System.setOut(originalOut);
            System.setErr(originalErr);
            originalOut = null;
            originalErr = null;
        }
    }

    private final class LineTee extends OutputStream {
        private final PrintStream original;
        private final boolean err;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineTee(PrintStream original, boolean err) {
            this.original = original;
            this.err = err;
        }

        @Override
        public synchronized void write(int b) {
            original.write(b);
            if (b == '\n') {
                flushLine();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            original.write(bytes, offset, length);
            for (int i = offset; i < offset + length; i++) {
                if (bytes[i] == '\n') {
                    flushLine();
                } else {
                    line.write(bytes[i]);
                }
            }
        }

        @Override
        public void flush() {
            original.flush();
        }

        private void flushLine() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            add(levelOf(text, err), "Editor", text);
        }
    }
}
