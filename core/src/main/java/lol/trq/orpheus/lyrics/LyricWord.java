/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

/**
 * One timed piece of a line.
 *
 * @param startMillis when it starts
 * @param text the piece, including any trailing space
 */
public record LyricWord(long startMillis, String text) {}
