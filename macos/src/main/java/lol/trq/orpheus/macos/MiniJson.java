/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.macos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the lines the helper process prints. Objects become {@code Map}, arrays {@code List},
 * numbers {@code Double}, plus {@code String}, {@code Boolean} and null. Input is untrusted: anything malformed or
 * too deeply nested throws {@link IllegalArgumentException} and nothing else.
 */
final class MiniJson {
    private static final int MAX_DEPTH = 32;

    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        try {
            MiniJson parser = new MiniJson(text);
            Object value = parser.value(0);
            parser.space();
            if (parser.at != text.length()) {
                throw new IllegalArgumentException("text after the value");
            }
            return value;
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("truncated or malformed JSON");
        }
    }

    private void space() {
        while (at < text.length() && text.charAt(at) <= ' ') {
            at++;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nesting too deep");
        }
        space();
        char c = text.charAt(at);
        switch (c) {
            case '{': {
                at++;
                Map<String, Object> map = new LinkedHashMap<>();
                space();
                if (text.charAt(at) == '}') {
                    at++;
                    return map;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    map.put(key, value(depth + 1));
                    space();
                    if (text.charAt(at) == ',') {
                        at++;
                    } else {
                        expect('}');
                        return map;
                    }
                }
            }
            case '[': {
                at++;
                List<Object> list = new ArrayList<>();
                space();
                if (text.charAt(at) == ']') {
                    at++;
                    return list;
                }
                while (true) {
                    list.add(value(depth + 1));
                    space();
                    if (text.charAt(at) == ',') {
                        at++;
                    } else {
                        expect(']');
                        return list;
                    }
                }
            }
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default: {
                int start = at;
                while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                    at++;
                }
                if (start == at) {
                    throw new IllegalArgumentException("unexpected character");
                }
                return Double.parseDouble(text.substring(start, at));
            }
        }
    }

    private void expect(char c) {
        if (text.charAt(at) != c) {
            throw new IllegalArgumentException("expected " + c);
        }
        at++;
    }

    private void literal(String word) {
        if (!text.startsWith(word, at)) {
            throw new IllegalArgumentException("unexpected word");
        }
        at += word.length();
    }

    private String string() {
        expect('"');
        StringBuilder out = null;
        int start = at;
        while (true) {
            char c = text.charAt(at);
            if (c == '"') {
                String result = out == null
                        ? text.substring(start, at)
                        : out.append(text, start, at).toString();
                at++;
                return result;
            }
            if (c == '\\') {
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(text, start, at);
                char escape = text.charAt(at + 1);
                at += 2;
                switch (escape) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    case '"', '\\', '/' -> out.append(escape);
                    default -> throw new IllegalArgumentException("bad escape");
                }
                start = at;
            } else {
                at++;
            }
        }
    }
}
