/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import java.util.List;

/**
 * One line of lyrics.
 *
 * @param startMillis when the line starts, or -1 in unsynced lyrics
 * @param endMillis when the next line starts, or -1 when unknown
 * @param text the line; empty for an instrumental gap
 * @param words timed pieces of the line, empty unless the lyrics are word-synced
 * @param translation a translation of the line, or empty
 * @param romanization a romanized form of the line, or empty
 */
public record LyricLine(
        long startMillis,
        long endMillis,
        String text,
        List<LyricWord> words,
        String translation,
        String romanization) {}
