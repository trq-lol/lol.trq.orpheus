/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

/**
 * Lyrics a provider found, with how sure it is that they belong to the track.
 *
 * @param lyrics the lyrics
 * @param confidence 0 to 1; matches below {@link LyricsMatcher#ACCEPT} are not shown
 */
public record LyricsMatch(Lyrics lyrics, double confidence) {}
