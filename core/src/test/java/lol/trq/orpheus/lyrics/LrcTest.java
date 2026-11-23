/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LrcTest {
    @Test
    void readsEveryTimestampShapeSeenInTheWild() {
        assertEquals(83_000, Lrc.time("01:23"));
        assertEquals(83_400, Lrc.time("01:23.4"));
        assertEquals(83_450, Lrc.time("01:23.45"));
        assertEquals(83_456, Lrc.time("01:23.456"));
        assertEquals(83_456, Lrc.time("01:23.4567"));
        assertEquals(83_450, Lrc.time("01:23:45"));
        assertEquals(83_450, Lrc.time("1:23,45"));
        assertEquals(3_683_500, Lrc.time("1:01:23.5"));
        assertEquals(7_380_000, Lrc.time("123:00"));
        assertEquals(-1, Lrc.time("ti:Title"));
        assertEquals(-1, Lrc.time("01:75"));
        assertEquals(-1, Lrc.time("1:2:3:4"));
        assertEquals(-1, Lrc.time(":"));
    }

    @Test
    void tagsLinesAndRepeatedStampsComeOutInTimeOrder() {
        Lrc lrc = Lrc.parse("[ti: A Song ]\r\n[ar:Someone]\n[length: 3:05]\n[by:tool]\n"
                + "[00:20.00][01:10.00]Chorus\n[00:05.00]First\n\n[00:30.00]\nnot timed, skipped\n");
        assertEquals("A Song", lrc.title());
        assertEquals("Someone", lrc.artist());
        assertEquals(185_000, lrc.lengthMillis());
        Lyrics lyrics = lrc.lyrics();
        assertEquals(Lyrics.Kind.LINE, lyrics.kind());
        assertEquals(
                List.of("First", "Chorus", "", "Chorus"),
                lyrics.lines().stream().map(LyricLine::text).toList());
        assertEquals(20_000, lyrics.line(0).endMillis());
        assertEquals(-1, lyrics.line(3).endMillis());
    }

    @Test
    void lineLookupIsExactAtEveryEdge() {
        Lyrics lyrics = Lrc.parse("[00:05.00]a\n[00:10.00]b\n[00:15.00]c").lyrics();
        assertEquals(-1, lyrics.lineAt(0));
        assertEquals(-1, lyrics.lineAt(4_999));
        assertEquals(0, lyrics.lineAt(5_000));
        assertEquals(0, lyrics.lineAt(9_999));
        assertEquals(1, lyrics.lineAt(10_000));
        assertEquals(2, lyrics.lineAt(15_000));
        assertEquals(2, lyrics.lineAt(Long.MAX_VALUE / 2));
        assertEquals(-1, Lyrics.NONE.lineAt(5_000));
    }

    @Test
    void aPositiveOffsetShowsLinesEarlier() {
        Lyrics lyrics = Lrc.parse("[offset:+500]\n[00:05.00]a\n[00:10.00]b").lyrics();
        assertEquals(500, lyrics.offsetMillis());
        assertEquals(0, lyrics.lineAt(4_500));
        assertEquals(-1, lyrics.lineAt(4_499));
        assertEquals(-1, lyrics.withOffset(-500).lineAt(5_499));
        assertEquals(0, lyrics.withOffset(-500).lineAt(5_500));
    }

    @Test
    void wordStampsGiveWordSyncedLines() {
        Lyrics lyrics = Lrc.parse(
                        "[00:10.00]<00:10.00>Hello <00:10.50>big <00:11.20>world<00:12.00>\n[00:13.00]plain a<b line")
                .lyrics();
        assertEquals(Lyrics.Kind.WORD, lyrics.kind());
        LyricLine line = lyrics.line(0);
        assertEquals("Hello big world", line.text());
        assertEquals(
                List.of("Hello ", "big ", "world"),
                line.words().stream().map(LyricWord::text).toList());
        assertEquals(-1, lyrics.wordAt(0, 9_999));
        assertEquals(0, lyrics.wordAt(0, 10_000));
        assertEquals(1, lyrics.wordAt(0, 10_500));
        assertEquals(2, lyrics.wordAt(0, 60_000));
        assertEquals("plain a<b line", lyrics.line(1).text(), "a stray angle bracket is text");
        assertEquals(-1, lyrics.wordAt(1, 14_000));
        assertEquals(-1, lyrics.wordAt(7, 14_000));
    }

    @Test
    void linesSharingAStartBecomeLineAndTranslation() {
        Lyrics lyrics =
                Lrc.parse("[00:05.00]Bonjour\n[00:05.00]Hello\n[00:09.00]Salut").lyrics();
        assertEquals(2, lyrics.size());
        assertEquals("Bonjour", lyrics.line(0).text());
        assertEquals("Hello", lyrics.line(0).translation());
        assertEquals("", lyrics.line(1).translation());
    }

    @Test
    void untimedTextIsPlainLyricsAndNothingIsNone() {
        Lyrics plain = Lrc.parse("first line\nsecond line\n").lyrics();
        assertEquals(Lyrics.Kind.PLAIN, plain.kind());
        assertEquals(2, plain.size());
        assertEquals(-1, plain.lineAt(1000));
        assertSame(Lyrics.NONE, Lrc.parse("").lyrics());
        assertSame(Lyrics.NONE, Lrc.parse("[ti:only tags]\n\n").lyrics());
    }

    @Test
    void byteOrderMarksSelectTheEncoding() {
        String text = "[00:01.00]東京 مرحبا";
        byte[] utf8 = ("﻿" + text).getBytes(StandardCharsets.UTF_8);
        byte[] utf16le = ("﻿" + text).getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16be = ("﻿" + text).getBytes(StandardCharsets.UTF_16BE);
        for (byte[] data : List.of(utf8, utf16le, utf16be, text.getBytes(StandardCharsets.UTF_8))) {
            Lyrics lyrics = Lrc.read(data).lyrics();
            assertEquals(1, lyrics.size());
            assertEquals("東京 مرحبا", lyrics.line(0).text());
        }
    }

    @Test
    void garbageNeverThrowsAndSizeLimitsHold() {
        String[] nasty = {
            "[",
            "]",
            "[]",
            "[[[[",
            "[00:00.00",
            "[00:00.00]]]",
            "[:]",
            "[0:0]x",
            "<<<>>>",
            "[00:01.00]<",
            "[00:01.00]<>",
            "[00:01.00]<00:02.00",
            "[offset:abc]",
            "[length:]",
            "[99999999:00]x",
            "[00:01.00][",
            "\u0000\u0001"
        };
        for (String text : nasty) {
            Lrc.parse(text);
        }
        Random random = new Random(7);
        String alphabet = "[]<>:.0123456789abc \n\r﻿";
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = random.nextInt(60); j > 0; j--) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            Lrc.parse(sb.toString());
        }
        assertTrue(
                Lrc.parse("[00:01.00]x\n".repeat(Lrc.MAX_LINES + 500)).lyrics().size() <= Lrc.MAX_LINES);
        assertSame(Lyrics.NONE, Lrc.read(new byte[Lrc.MAX_BYTES + 1]).lyrics());
        assertEquals(
                1024,
                Lrc.parse("[00:01.00]" + "y".repeat(5000))
                                .lyrics()
                                .line(0)
                                .text()
                                .length()
                        + 10);
    }

    @Test
    void manyStampsOnTheSameTimeStayBounded() {
        String line = "[0:0.0]".repeat(70) + "x".repeat(500) + "\n";
        Lrc lrc = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10), () -> Lrc.parse(line.repeat(2000)));
        for (LyricLine each : lrc.lyrics().lines()) {
            org.junit.jupiter.api.Assertions.assertTrue(each.translation().length() < 10_000);
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                Lyrics.Kind.NONE,
                Lrc.parse("[0:1.0]a\n".repeat(400_000)).lyrics().kind());
    }
}
