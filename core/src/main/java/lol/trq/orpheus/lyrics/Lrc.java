/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed {@code .lrc} file. The parser accepts what players write in practice and skips what it cannot read; it
 * never throws on bad input.
 *
 * @param title the {@code ti} tag, or empty
 * @param artist the {@code ar} tag, or empty
 * @param album the {@code al} tag, or empty
 * @param lengthMillis the {@code length} tag, or -1
 * @param lyrics the lines; {@link Lyrics#NONE} when the file holds none
 */
public record Lrc(String title, String artist, String album, long lengthMillis, Lyrics lyrics) {
    public static final int MAX_BYTES = 2 << 20;
    public static final int MAX_LINES = 10_000;
    private static final int MAX_LINE_LENGTH = 1024;
    private static final int MAX_TRANSLATION_LENGTH = 4096;

    private record Raw(long start, int order, String text, List<LyricWord> words) {}

    /** Decodes and parses file content. Honours a UTF-8 or UTF-16 byte order mark; otherwise assumes UTF-8. */
    public static Lrc read(byte[] data) {
        if (data.length > MAX_BYTES) {
            return parse("");
        }
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            skip = 3;
        } else if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        }
        return parse(new String(data, skip, data.length - skip, charset));
    }

    public static Lrc parse(String text) {
        if (text.length() > MAX_BYTES) {
            return parse("");
        }
        String title = "";
        String artist = "";
        String album = "";
        long length = -1;
        long offset = 0;
        List<Raw> timed = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        int count = 0;
        for (String rawLine : text.split("\n", -1)) {
            if (++count > MAX_LINES || timed.size() >= MAX_LINES) {
                break;
            }
            String line = rawLine.strip();
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH);
            }
            if (!line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1);
            }
            List<Long> stamps = new ArrayList<>(1);
            int pos = 0;
            boolean tag = false;
            while (pos < line.length() && line.charAt(pos) == '[') {
                int close = line.indexOf(']', pos);
                if (close < 0) {
                    break;
                }
                String inner = line.substring(pos + 1, close);
                long time = time(inner);
                if (time >= 0) {
                    stamps.add(time);
                    pos = close + 1;
                    continue;
                }
                int colon = inner.indexOf(':');
                if (stamps.isEmpty() && colon > 0 && isKey(inner, colon)) {
                    tag = true;
                    String value = inner.substring(colon + 1).strip();
                    switch (inner.substring(0, colon).strip().toLowerCase(java.util.Locale.ROOT)) {
                        case "ti" -> title = value;
                        case "ar" -> artist = value;
                        case "al" -> album = value;
                        case "length" -> length = length(value);
                        case "offset" -> offset = number(value);
                        default -> {}
                    }
                }
                break;
            }
            if (tag) {
                continue;
            }
            String rest = line.substring(pos).strip();
            if (stamps.isEmpty()) {
                if (!rest.isEmpty()) {
                    plain.add(rest);
                }
                continue;
            }
            List<LyricWord> words = new ArrayList<>();
            String shown = words(rest, stamps.get(0), words);
            for (int i = 0; i < stamps.size() && timed.size() < MAX_LINES; i++) {
                timed.add(new Raw(stamps.get(i), timed.size(), shown, i == 0 ? words : List.of()));
            }
        }
        return new Lrc(title, artist, album, length, build(timed, plain, offset));
    }

    private static Lyrics build(List<Raw> timed, List<String> plain, long offset) {
        if (timed.isEmpty()) {
            if (plain.isEmpty()) {
                return Lyrics.NONE;
            }
            List<LyricLine> lines = new ArrayList<>();
            for (String text : plain) {
                lines.add(new LyricLine(-1, -1, text, List.of(), "", ""));
            }
            return new Lyrics(Lyrics.Kind.PLAIN, lines, 0, "", 0);
        }
        timed.sort((a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : Integer.compare(a.order, b.order));
        List<Raw> merged = new ArrayList<>();
        List<String> translations = new ArrayList<>();
        for (Raw raw : timed) {
            int last = merged.size() - 1;
            if (last >= 0
                    && merged.get(last).start == raw.start
                    && !merged.get(last).text.isEmpty()) {
                String sofar = translations.get(last);
                if (!raw.text.isEmpty() && sofar.length() < MAX_TRANSLATION_LENGTH) {
                    translations.set(last, sofar.isEmpty() ? raw.text : sofar + "\n" + raw.text);
                }
            } else {
                merged.add(raw);
                translations.add("");
            }
        }
        boolean wordSynced = false;
        List<LyricLine> lines = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            Raw raw = merged.get(i);
            long end = i + 1 < merged.size() ? merged.get(i + 1).start : -1;
            wordSynced |= !raw.words.isEmpty();
            lines.add(new LyricLine(raw.start, end, raw.text, List.copyOf(raw.words), translations.get(i), ""));
        }
        return new Lyrics(wordSynced ? Lyrics.Kind.WORD : Lyrics.Kind.LINE, lines, offset, "", 0);
    }

    private static String words(String rest, long lineStart, List<LyricWord> words) {
        if (rest.indexOf('<') < 0) {
            return rest;
        }
        StringBuilder shown = new StringBuilder();
        long start = lineStart;
        int pos = 0;
        boolean any = false;
        while (pos <= rest.length()) {
            int open = rest.indexOf('<', pos);
            int close = open < 0 ? -1 : rest.indexOf('>', open);
            long time = close < 0 ? -1 : time(rest.substring(open + 1, close));
            int end = time >= 0 ? open : (open < 0 || close < 0 ? rest.length() : close + 1);
            String piece = rest.substring(pos, end);
            if (time < 0 && end < rest.length()) {
                shown.append(piece);
                if (!piece.isEmpty()) {
                    pending(words, start, piece);
                }
                pos = end;
                continue;
            }
            if (!piece.isEmpty()) {
                shown.append(piece);
                pending(words, start, piece);
            }
            if (time < 0) {
                break;
            }
            any = true;
            start = time;
            pos = close + 1;
        }
        if (!any) {
            words.clear();
        }
        return shown.toString().strip();
    }

    private static void pending(List<LyricWord> words, long start, String piece) {
        int last = words.size() - 1;
        if (last >= 0 && words.get(last).startMillis() == start) {
            words.set(last, new LyricWord(start, words.get(last).text() + piece));
        } else {
            words.add(new LyricWord(start, piece));
        }
    }

    private static boolean isKey(String inner, int colon) {
        for (int i = 0; i < colon; i++) {
            char c = inner.charAt(i);
            if (!Character.isLetter(c) && c != '_' && c != ' ' && c != '#') {
                return false;
            }
        }
        return true;
    }

    /** Parses mm:ss, mm:ss.f, mm:ss:ff and h:mm:ss.f. Returns -1 for anything else. */
    static long time(String s) {
        String[] parts = s.strip().split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            return -1;
        }
        String last = parts[parts.length - 1];
        int dot = Math.max(last.indexOf('.'), last.indexOf(','));
        long millis = 0;
        if (dot >= 0) {
            millis = fraction(last.substring(dot + 1));
            last = last.substring(0, dot);
        }
        long a = digits(parts[0]);
        long b = digits(parts.length == 3 ? parts[1] : last);
        if (a < 0 || b < 0 || millis < 0) {
            return -1;
        }
        if (parts.length == 2) {
            return b >= 60 ? -1 : (a * 60 + b) * 1000 + millis;
        }
        if (dot >= 0) {
            long c = digits(last);
            return c < 0 || b >= 60 || c >= 60 ? -1 : ((a * 60 + b) * 60 + c) * 1000 + millis;
        }
        long hundredths = fraction(parts[2]);
        return hundredths < 0 || b >= 60 ? -1 : (a * 60 + b) * 1000 + hundredths;
    }

    private static long digits(String s) {
        if (s.isEmpty() || s.length() > 6) {
            return -1;
        }
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static long fraction(String s) {
        if (s.isEmpty()) {
            return -1;
        }
        String padded = (s + "00").substring(0, 3);
        return digits(s) < 0 ? -1 : digits(padded);
    }

    private static long length(String value) {
        long time = time(value);
        if (time >= 0) {
            return time;
        }
        long seconds = digits(value);
        return seconds < 0 ? -1 : seconds * 1000;
    }

    private static long number(String value) {
        String v = value.strip();
        boolean negative = v.startsWith("-");
        long n = digits(negative || v.startsWith("+") ? v.substring(1) : v);
        return n < 0 ? 0 : negative ? -n : n;
    }
}
