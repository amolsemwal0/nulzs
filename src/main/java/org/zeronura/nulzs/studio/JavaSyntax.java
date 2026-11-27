package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.graphics2d.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Colours Java (and the C-like languages near it — GLSL, JSON, JavaScript) a line at a time: keywords, strings, numbers,
 * comments, annotations, types and method calls. A line is coloured from the state the line before left — inside a
 * block comment or a text block, or not — so the editor only ever works on the lines on screen.
 */
final class JavaSyntax {

    static final int NORMAL = 0;
    static final int BLOCK_COMMENT = 1;
    static final int TEXT_BLOCK = 2;

    enum Kind {
        PLAIN(Color.rgb(0xBCBEC4)), KEYWORD(Color.rgb(0xCF8E6D)), STRING(Color.rgb(0x6AAB73)), NUMBER(Color.rgb(0x2AACB8)),
        COMMENT(Color.rgb(0x7A7E85)), DOC(Color.rgb(0x5F826B)), ANNOTATION(Color.rgb(0xB3AE60)), TYPE(Color.rgb(0xDFE1E5)),
        CALL(Color.rgb(0x56A8F5)), CONSTANT(Color.rgb(0xC77DBB)), PUNCTUATION(Color.rgb(0xA9ABB1));

        final Color color;

        Kind(Color color) {
            this.color = color;
        }
    }

    record Token(int start, int end, Kind kind) {
    }

    record Line(List<Token> tokens, int endState) {
    }

    private static final Set<String> KEYWORDS = Set.copyOf(java.util.List.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield", "sealed",
            "permits", "true", "false", "null",
            // GLSL and JavaScript, which share the rest of the shape
            "uniform", "in", "out", "inout", "layout", "vec2", "vec3", "vec4", "mat3", "mat4", "sampler2D", "precision",
            "function", "let", "of", "export", "from", "undefined"));

    private JavaSyntax() {
    }

    static Line colour(String text, int state) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        if (state == BLOCK_COMMENT) {
            int end = text.indexOf("*/");
            if (end < 0) {
                tokens.add(new Token(0, n, Kind.COMMENT));
                return new Line(tokens, BLOCK_COMMENT);
            }
            tokens.add(new Token(0, end + 2, Kind.COMMENT));
            i = end + 2;
        } else if (state == TEXT_BLOCK) {
            int end = text.indexOf("\"\"\"");
            if (end < 0) {
                tokens.add(new Token(0, n, Kind.STRING));
                return new Line(tokens, TEXT_BLOCK);
            }
            tokens.add(new Token(0, end + 3, Kind.STRING));
            i = end + 3;
        }
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                tokens.add(new Token(i, n, Kind.COMMENT));
                return new Line(tokens, NORMAL);
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                boolean doc = i + 2 < n && text.charAt(i + 2) == '*';
                int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    tokens.add(new Token(i, n, doc ? Kind.DOC : Kind.COMMENT));
                    return new Line(tokens, BLOCK_COMMENT);
                }
                tokens.add(new Token(i, end + 2, doc ? Kind.DOC : Kind.COMMENT));
                i = end + 2;
                continue;
            }
            if (c == '"' && text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    tokens.add(new Token(i, n, Kind.STRING));
                    return new Line(tokens, TEXT_BLOCK);
                }
                tokens.add(new Token(i, end + 3, Kind.STRING));
                i = end + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < n && text.charAt(end) != c) {
                    end += text.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(n, end + 1);
                tokens.add(new Token(i, end, Kind.STRING));
                i = end;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.'
                        || text.charAt(end) == '_')) {
                    end++;
                }
                tokens.add(new Token(i, end, Kind.NUMBER));
                i = end;
                continue;
            }
            if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                int end = i + 1;
                while (end < n && (Character.isJavaIdentifierPart(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(i, end, Kind.ANNOTATION));
                i = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(i, end);
                Kind kind;
                if (KEYWORDS.contains(word)) {
                    kind = Kind.KEYWORD;
                } else if (end < n && text.charAt(end) == '(') {
                    kind = Kind.CALL;
                } else if (word.length() > 1 && word.equals(word.toUpperCase(java.util.Locale.ROOT))
                        && Character.isLetter(word.charAt(0))) {
                    kind = Kind.CONSTANT;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    kind = Kind.TYPE;
                } else {
                    kind = Kind.PLAIN;
                }
                tokens.add(new Token(i, end, kind));
                i = end;
                continue;
            }
            tokens.add(new Token(i, i + 1, Kind.PUNCTUATION));
            i++;
        }
        return new Line(tokens, NORMAL);
    }

    /** Whether files of this extension are coloured as code. */
    static boolean colours(String extension) {
        return switch (extension) {
            case "java", "glsl", "vert", "frag", "geom", "comp", "json", "js", "jsx", "scene", "gradle", "kts" -> true;
            default -> false;
        };
    }
}
