/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import lol.trq.orpheus.cover.Cover;
import lol.trq.orpheus.lyrics.Lyrics;

/**
 * Everything about the current instant, read in one step so the parts always belong together.
 *
 * @param playback the selected session, or {@link Playback#NONE}
 * @param lyrics lyrics of the playing track, or {@link Lyrics#NONE}
 * @param cover the decoded cover of the playing track, or null
 * @param bpm beats per minute of the playing track from the {@link BpmProvider}, or 0 when unknown
 */
public record Snapshot(Playback playback, Lyrics lyrics, Cover cover, float bpm) {
    public static final Snapshot NONE = new Snapshot(Playback.NONE, Lyrics.NONE, null);

    public Snapshot(Playback playback, Lyrics lyrics, Cover cover) {
        this(playback, lyrics, cover, 0);
    }
}
