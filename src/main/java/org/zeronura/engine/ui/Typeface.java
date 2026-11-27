package org.zeronura.engine.ui;

import java.util.List;

/**
 * Which of the UI's faces a piece of text is set in: running text, headings, or the display face of titles and
 * names. Each is a font file in {@link BitmapFont#FONT_DIRECTORY}, the first of its {@link #files} found; one whose
 * files are all missing falls back to the face below it, and {@link #BODY} to the built-in glyphs, so a project that
 * ships one font file draws everything in it.
 *
 * <p>The files named are the engine's own UI faces: Montserrat for text and headings, Grandstander for display,
 * DejaVu Sans Mono for code.
 */
public enum Typeface {

    /** Running text, numbers and small print — the default every widget measures and draws with. */
    BODY(List.of("Montserrat-SemiBold.ttf", "fantasy.ttf", "ui.ttf", "Metamorphous-Regular.ttf")),
    /** Headings, buttons and labels: heavier, and often set in capitals. */
    HEADING(List.of("Montserrat-ExtraBold.ttf")),
    /** Titles, logos and names: the rounded display face. */
    DISPLAY(List.of("Grandstander-Black.ttf")),
    /** Code and anything else that lines up in columns: every character the same width. */
    MONO(List.of("DejaVuSansMono.ttf"));

    private final List<String> files;

    Typeface(List<String> files) {
        this.files = files;
    }

    /** The font files tried, in order. */
    public List<String> files() {
        return files;
    }

    /** The face used when none of this one's files can be loaded. */
    Typeface fallback() {
        return switch (this) {
            case BODY -> BODY;
            case HEADING -> BODY;
            case DISPLAY -> HEADING;
            case MONO -> BODY;
        };
    }
}
