package org.zeronura.nulzs.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes JSON as plain Java values: an object is a {@code Map<String, Object>} that keeps its keys in order,
 * an array a {@code List<Object>}, and the rest {@code String}, {@code Long} (whole numbers), {@code Double},
 * {@code Boolean} and {@code null}. Enough for game data and saves, which don't need a binding library.
 */
public final class Json {

    private Json() {
    }

    // --- Reading ----------------------------------------------------------------------------------------

    /** Parses a JSON document; throws {@link IllegalArgumentException} saying where it went wrong. */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.skipSpace();
        Object value = reader.value();
        reader.skipSpace();
        if (reader.position < text.length()) {
            throw reader.error("unexpected text after the end");
        }
        return value;
    }

    /** Parses a document that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Reader {
        final String text;
        int position;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            if (position >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            position++;
            skipSpace();
            if (peek() == '}') {
                position++;
                return map;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw error("expected a key in quotes");
                }
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                map.put(key, value());
                skipSpace();
                if (peek() == ',') {
                    position++;
                } else if (peek() == '}') {
                    position++;
                    return map;
                } else {
                    throw error("expected , or }");
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            position++;
            skipSpace();
            if (peek() == ']') {
                position++;
                return list;
            }
            while (true) {
                skipSpace();
                list.add(value());
                skipSpace();
                if (peek() == ',') {
                    position++;
                } else if (peek() == ']') {
                    position++;
                    return list;
                } else {
                    throw error("expected , or ]");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (position >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                // A file cut short inside an escape is reported like any other bad JSON — an IllegalArgumentException
                // with where it went wrong, which every loader catches — not an index out of bounds none of them do.
                if (position >= text.length()) {
                    throw error("unterminated string");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("unterminated \\u escape");
                        }
                        String hex = text.substring(position, position + 4);
                        if (!hex.chars().allMatch(h -> Character.digit(h, 16) >= 0)) {
                            throw error("bad \\u escape '\\u" + hex + "'");
                        }
                        out.append((char) Integer.parseInt(hex, 16));
                        position += 4;
                    }
                    default -> out.append(escape);
                }
            }
        }

        Object number() {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            String number = text.substring(start, position);
            if (number.isEmpty()) {
                throw error("unexpected '" + text.charAt(start) + "'");
            }
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw error("bad number '" + number + "'");
            }
        }

        Object literal(String word, Object value) {
            if (!text.startsWith(word, position)) {
                throw error("unexpected text");
            }
            position += word.length();
            return value;
        }

        char peek() {
            return position < text.length() ? text.charAt(position) : '\0';
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            position++;
        }

        void skipSpace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        IllegalArgumentException error(String message) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < Math.min(position, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new IllegalArgumentException("JSON " + message + " at line " + line + ", column " + column);
        }
    }

    // --- Writing ----------------------------------------------------------------------------------------

    /** The value as indented JSON, for files people read and edit. */
    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, true);
        return out.append('\n').toString();
    }

    /** The value as JSON on one line, for sending. */
    public static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, false);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth, boolean pretty) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Boolean b -> out.append(b);
            case Float f -> out.append(number(f.doubleValue()));
            case Double d -> out.append(number(d));
            case Number n -> out.append(n.longValue());
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    newline(out, depth + 1, pretty);
                    quote(String.valueOf(entry.getKey()), out);
                    out.append(pretty ? ": " : ":");
                    write(entry.getValue(), out, depth + 1, pretty);
                }
                newline(out, depth, pretty);
                out.append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                // Short lists of plain values stay on one line: a move list reads better that way.
                boolean flat = !pretty || list.stream().noneMatch(v -> v instanceof Map || v instanceof List);
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(flat && pretty ? ", " : ",");
                    }
                    if (!flat) {
                        newline(out, depth + 1, true);
                    }
                    write(list.get(i), out, depth + 1, pretty);
                }
                if (!flat) {
                    newline(out, depth, true);
                }
                out.append(']');
            }
            default -> quote(value.toString(), out);
        }
    }

    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.valueOf(value);
    }

    private static void newline(StringBuilder out, int depth, boolean pretty) {
        if (pretty) {
            out.append('\n').append("  ".repeat(depth));
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // --- Reading values out of parsed JSON --------------------------------------------------------------

    /** A field that must be present. */
    public static Object require(Map<String, Object> object, String key) {
        if (!object.containsKey(key)) {
            throw new IllegalArgumentException("missing \"" + key + "\"");
        }
        return object.get(key);
    }

    public static String string(Map<String, Object> object, String key) {
        Object value = require(object, key);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("\"" + key + "\" should be text");
        }
        return s;
    }

    /** A text field, or {@code fallback} when it is missing or null. */
    public static String string(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value == null ? fallback : value.toString();
    }

    public static int integer(Map<String, Object> object, String key) {
        Object value = require(object, key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("\"" + key + "\" should be a number");
        }
        return n.intValue();
    }

    public static int integer(Map<String, Object> object, String key, int fallback) {
        return object.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    public static float decimal(Map<String, Object> object, String key) {
        Object value = require(object, key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("\"" + key + "\" should be a number");
        }
        return n.floatValue();
    }

    public static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        return object.get(key) instanceof Boolean b ? b : fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("\"" + key + "\" should be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("\"" + key + "\" should be a list");
        }
        return (List<Object>) value;
    }

    /** The objects in a list field. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Map<String, Object> object, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list(object, key)) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("\"" + key + "\" should hold objects");
            }
            out.add((Map<String, Object>) item);
        }
        return out;
    }
}
