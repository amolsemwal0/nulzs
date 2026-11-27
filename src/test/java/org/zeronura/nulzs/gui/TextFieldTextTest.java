package org.zeronura.nulzs.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** What a text field does to the text in it: it never leaves half a character that a file can't hold. */
class TextFieldTextTest {

    private static final String GRIN = "😀";

    /** Every character one unit wide, so a width is a number of letters. */
    private static java.util.List<String> wrap(String text, float width) {
        return Gui.wrapLines(text, width, String::length);
    }

    @Test
    void aTextAreaBreaksAtSpacesAndLineBreaks() {
        assertEquals(java.util.List.of("one two ", "three"), wrap("one two three", 9f));
        assertEquals(java.util.List.of("one", "", "two"), wrap("one\n\ntwo", 9f));
    }

    @Test
    void aWordWiderThanTheAreaIsCutAndNothingIsLost() {
        assertEquals(java.util.List.of("abcd", "efgh", "ij"), wrap("abcdefghij", 4f));
        assertEquals(java.util.List.of("a", "b"), wrap("ab", 0f));
    }

    @Test
    void theCaretHasALineToSitOnAfterALineBreakOrWhenEmpty() {
        assertEquals(java.util.List.of("one", ""), wrap("one\n", 9f));
        assertEquals(java.util.List.of(""), wrap("", 9f));
    }

    @Test
    void backspaceTakesOffAWholeEmoji() {
        assertEquals("Ash", Gui.withoutLastCharacter("Ash" + GRIN));
        assertEquals("As", Gui.withoutLastCharacter("Ash"));
        assertEquals("", Gui.withoutLastCharacter(""));
    }

    @Test
    void halfAnEmojiCutOffAtTheLongestIsDropped() {
        String cut = ("Ash" + GRIN).substring(0, 4); // as cutting to a maximum length of 4 leaves it
        assertEquals("Ash", Gui.withoutBrokenPairs(cut));
        assertEquals("A" + GRIN + "b", Gui.withoutBrokenPairs("A" + GRIN.charAt(1) + GRIN + "b" + GRIN.charAt(0)));
        assertEquals("Ash" + GRIN, Gui.withoutBrokenPairs("Ash" + GRIN));
    }

    @Test
    void whatIsLeftCanAlwaysBeWrittenAsUtf8() {
        String text = Gui.withoutBrokenPairs(Gui.withoutLastCharacter("Rae" + GRIN + GRIN).substring(0, 4));
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(text), text);
    }
}
