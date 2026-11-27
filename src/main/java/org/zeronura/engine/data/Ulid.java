package org.zeronura.engine.data;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A ULID: a 26-character identifier that sorts by the moment it was made — 48 bits of milliseconds since 1970, then 80
 * random bits, in Crockford's base 32. It says both "which one" and "when", so a record keeps a single field for each.
 * Ones made in the same millisecond still sort in the order they were made.
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static long lastTime = -1L;
    private static final byte[] lastRandom = new byte[10];

    private Ulid() {
    }

    /** A new ULID for now. */
    public static String next() {
        return at(System.currentTimeMillis());
    }

    /** A new ULID for a moment, in milliseconds since 1970. */
    public static synchronized String at(long millis) {
        byte[] random = new byte[10];
        if (millis == lastTime) {
            // Same millisecond: count up from the last one, so they still sort in order.
            System.arraycopy(lastRandom, 0, random, 0, 10);
            for (int i = 9; i >= 0 && ++random[i] == 0; i--) {
                // carry
            }
        } else {
            RANDOM.nextBytes(random);
            lastTime = millis;
        }
        System.arraycopy(random, 0, lastRandom, 0, 10);

        char[] out = new char[26];
        long time = millis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 random bits as 16 characters: two 40-bit halves.
        long high = 0L;
        long low = 0L;
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (random[i] & 0xFF);
            low = (low << 8) | (random[i + 5] & 0xFF);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (high & 31)];
            high >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (low & 31)];
            low >>>= 5;
        }
        return new String(out);
    }

    /** Whether text is a well-formed ULID. */
    public static boolean isValid(String text) {
        if (text == null || text.length() != 26 || text.charAt(0) > '7') {
            return false;
        }
        for (int i = 0; i < 26; i++) {
            if (decode(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** The moment a ULID was made, in milliseconds since 1970. */
    public static long millis(String ulid) {
        if (!isValid(ulid)) {
            throw new IllegalArgumentException("not a ULID: " + ulid);
        }
        long time = 0L;
        for (int i = 0; i < 10; i++) {
            time = (time << 5) | decode(ulid.charAt(i));
        }
        return time;
    }

    /** When a ULID was made, as a local date and 24-hour time: {@code 2026-09-28 14:05}. */
    public static String displayTime(String ulid) {
        return DISPLAY.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis(ulid)), ZoneId.systemDefault()));
    }

    private static int decode(char c) {
        char upper = Character.toUpperCase(c);
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == upper) {
                return i;
            }
        }
        return -1;
    }
}
