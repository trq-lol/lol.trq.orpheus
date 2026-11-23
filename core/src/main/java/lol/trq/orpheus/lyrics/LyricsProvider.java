/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import lol.trq.orpheus.Track;

/**
 * Finds lyrics for a track. Called on a background thread of the library and may block; it is given one track at a
 * time. Lyrics are copyrighted works: a provider is responsible for having the right to show what it returns.
 */
public interface LyricsProvider {
    /**
     * @return the best match, or null when there is none
     * @throws Exception treated as "no match"
     */
    LyricsMatch find(Track track) throws Exception;

    /** Registers a callback the provider runs when it may have new answers, so a failed lookup can be retried. */
    default void onChange(Runnable listener) {}
}
