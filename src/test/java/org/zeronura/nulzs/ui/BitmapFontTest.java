package org.zeronura.nulzs.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural checks on the hand-coded glyph table. The font's <i>shapes</i> were proof-read from
 * {@link BitmapFont#toAsciiArt}; what a test can still catch is a table that has drifted out of
 * alignment — a missing entry shifts every later character, so 'A' would render as '@'.
 *
 * <p>All of this runs without a GL context: only the atlas upload in the constructor needs one.
 */
class BitmapFontTest {

    @Test
    void thereIsExactlyOneGlyphPerPrintableAsciiCharacter() {
        assertEquals('~' - ' ' + 1, BitmapFont.glyphCount(),
                "a missing or extra entry silently shifts every glyph after it");
    }

    @Test
    void everyGlyphIsSevenRowsOfFive() {
        for (char c = ' '; c <= '~'; c++) {
            String[] rows = BitmapFont.toAsciiArt(c).split("\n");
            assertEquals(BitmapFont.GLYPH_HEIGHT, rows.length, "row count for '" + c + "'");
            for (String row : rows) {
                assertEquals(BitmapFont.GLYPH_WIDTH, row.length(), "row width for '" + c + "': " + row);
                assertTrue(row.matches("[#.]+"), "unexpected characters in '" + c + "': " + row);
            }
        }
    }

    /**
     * Alignment canary: if the table is off by one, these land on their neighbours. Chosen because each
     * has a shape no adjacent character shares.
     */
    @Test
    void keyGlyphsAreWhereTheyShouldBe() {
        assertEquals("""
                .....
                .....
                .....
                .....
                .....
                .....
                ..#..""", BitmapFont.toAsciiArt('.'), "full stop should be a single low pixel");

        assertEquals("""
                #####
                ..#..
                ..#..
                ..#..
                ..#..
                ..#..
                ..#..""", BitmapFont.toAsciiArt('T'), "T should be a bar over a stem");

        assertEquals("""
                ....#
                ...#.
                ...#.
                ..#..
                .#...
                .#...
                #....""", BitmapFont.toAsciiArt('/'), "the HUD toggle key's own glyph");
    }

    @Test
    void spaceIsBlankAndLettersAreNot() {
        assertFalse(BitmapFont.toAsciiArt(' ').contains("#"), "space must draw nothing");
        for (char c = 'A'; c <= 'Z'; c++) {
            assertTrue(BitmapFont.toAsciiArt(c).contains("#"), "'" + c + "' has no pixels set");
        }
        for (char c = '0'; c <= '9'; c++) {
            assertTrue(BitmapFont.toAsciiArt(c).contains("#"), "'" + c + "' has no pixels set");
        }
    }

    @Test
    void uncoveredCharactersFallBackToAVisibleBox() {
        assertFalse(BitmapFont.covers('é'));
        assertTrue(BitmapFont.toAsciiArt('é').contains("#"),
                "unmappable text must be visible as a box, not silently blank");
    }

    /**
     * Measuring text is a sum of advances and scales linearly — stated as properties, because the font is
     * proportional now and the old form of this test pinned the pixel font's arithmetic instead.
     *
     * <p>It asserted {@code widthOf("X", 2) == GLYPH_WIDTH * 2}, which is true of a fixed cell and
     * meaningless of a typeface. That is worth keeping in mind rather than just fixing: a test written
     * against <i>how</i> an answer was computed rather than <i>what</i> it means is one that fails the
     * first time the implementation legitimately changes, and says nothing useful when it does.
     */
    @Test
    void textMeasurementIsASumOfAdvances() {
        assertEquals(0f, BitmapFont.widthOf("", 2f), 1e-4f);

        float x = BitmapFont.widthOf("X", 2f);
        float y = BitmapFont.widthOf("Y", 2f);
        assertTrue(x > 0f, "a drawn character must have width");
        assertEquals(x + y, BitmapFont.widthOf("XY", 2f), 1e-3f,
                "a run must measure as the sum of its glyphs' advances");

        assertEquals(BitmapFont.widthOf("HELLO", 1f) * 3f, BitmapFont.widthOf("HELLO", 3f), 1e-3f,
                "scale must be a plain multiplier, or text and the box drawn around it diverge with size");
    }

    /**
     * A space takes width even though it draws nothing.
     *
     * <p>The renderer skips the quad for a blank glyph, which is a drawing decision; if it leaked into the
     * measurement, every string with a space in it would be measured short and every centred label would
     * sit off-centre by half a space per word.
     */
    @Test
    void aSpaceAdvancesEvenThoughItDrawsNothing() {
        float spaced = BitmapFont.widthOf("A B", 2f);
        float tight = BitmapFont.widthOf("AB", 2f);

        assertTrue(spaced > tight, "a space measured as nothing, so spaced text is measured too narrow");
    }

    /**
     * Digits share one advance, or every counter in the HUD jitters as it counts.
     *
     * <p>Health, hunger, the day number and every floating damage popup change while being looked at. A
     * proportional face whose figures were free to differ would shuffle the whole number sideways on each
     * change — the one place proportional spacing is actively wrong, and the reason
     * {@code GlyphSet.advance()} reports the zero's width rather than an average.
     */
    @Test
    void digitsAreAllOneWidth() {
        float zero = BitmapFont.widthOf("0", 1f);
        for (char c = '1'; c <= '9'; c++) {
            assertEquals(zero, BitmapFont.widthOf(String.valueOf(c), 1f), 1e-3f,
                    "'" + c + "' is a different width from '0', so counters will jitter as they change");
        }
    }

    /**
     * The longest strings the UI actually draws still fit the rectangles they are drawn in.
     *
     * <p>This is what {@link BitmapFont#WIDTH_SCALE} exists for, and it is the guard that decides whether
     * a newly dropped-in font is usable. The numbers are the ones CLAUDE.md records as having overflowed
     * something once already: a footer hint against a save panel's content box, and the item name an
     * inventory slot is sized around.
     *
     * <p>Deliberately about the <b>widest real strings</b> rather than a width limit per character — a
     * proportional face has no per-character budget to check, and the only question that matters is
     * whether the things the game draws fit where it draws them.
     */
    @Test
    void theWidestStringsTheUiDrawsStillFitTheirPanels() {
        // The save/load footer hint, against the 464px content box of a 520px panel.
        float hint = BitmapFont.widthOf("ESC BACK   ENTER SELECT   DEL ERASE", 1.5f);
        assertTrue(hint <= 464f, "the save menu's footer hint is " + hint
                + "px wide inside a 464px content box, so it will run past its own panel");

        // A full creature nameplate, which is drawn unconstrained above a slime's head.
        float nameplate = BitmapFont.widthOf("Lv 10 Volcanic Slime", 1.5f);
        assertTrue(nameplate <= 320f, "a nameplate is " + nameplate + "px wide");

        // Every panel here sizes itself from widthOf, so what must hold for the rest is only that a
        // typeface has not arrived so wide that the panels it drives stop fitting a small window.
        float title = BitmapFont.widthOf("DEVELOPER TOOLS", 3f);
        assertTrue(title <= 800f, "a title is " + title + "px wide and would not fit a 1280px window's panel");
    }

    /**
     * Title-sized text samples an atlas baked at least that large, rather than magnifying the body one.
     *
     * <p>The regression this guards is a soft title: "ELEMENTALS" drawn at scale 14 from an atlas baked
     * for 3. And the tiers must agree on metrics, since layout measures the base tier and draws another.
     */
    @Test
    void largeTextDrawsFromItsOwnAtlasWithTheSameMetrics() {
        GlyphSet set = BitmapFont.active();
        if (set.isPixelArt()) {
            return; // nearest-sampled pixels magnify cleanly and have one tier by design
        }
        assertEquals(0, set.tierFor(BitmapFont.MAX_UI_SCALE), "body text stays on the body atlas");
        int title = set.tierFor(14f);
        assertTrue(title > 0, "a scale-14 title would be magnified from the scale-3 atlas");
        GlyphSet large = set.tiers().get(title);
        for (char c = 'A'; c <= 'z'; c++) {
            float base = set.glyph(c).advance();
            assertEquals(base, large.glyph(c).advance(), base * 0.05f, "advance of '" + c + "' differs by tier");
        }
    }
}
