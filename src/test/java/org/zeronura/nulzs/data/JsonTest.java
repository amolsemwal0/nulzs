package org.zeronura.nulzs.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reading and writing JSON, and how a damaged file is reported. */
class JsonTest {

    @Test
    void textWithEscapesReadsBackAsItWasWritten() {
        Map<String, Object> object = Map.of("name", "Sir \"Bubbles\"\n\\ \u0001 caf\u00e9 \uD83D\uDE00");
        assertEquals(object, Json.parseObject(Json.compact(object)));
        assertEquals("A", Json.parse("\"\\u0041\""));
    }

    @Test
    void numbersAndListsReadBack() {
        Map<String, Object> object = Json.parseObject("{\"n\": -12, \"x\": 1.5e2, \"list\": [true, false, null]}");
        assertEquals(-12L, object.get("n"));
        assertEquals(150.0, object.get("x"));
        assertEquals(java.util.Arrays.asList(true, false, null), object.get("list"));
        assertEquals(List.of(), Json.parse("[]"));
    }

    /**
     * Every loader of the game's files catches IllegalArgumentException to report a bad file and carry on; a file cut
     * short inside an escape once threw StringIndexOutOfBoundsException instead, which none of them catch.
     */
    @Test
    void aFileCutShortInsideAnEscapeIsReportedAsBadJson() {
        for (String damaged : List.of("{\"name\": \"abc\\", "\"\\u00", "\"\\u12\"", "\"\\uZZZZ\"", "{\"a\": \"b")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse(damaged), damaged);
            assertTrue(e.getMessage().startsWith("JSON "), e.getMessage());
        }
    }
}
