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
 */
public record Snapshot(Playback playback, Lyrics lyrics, Cover cover) {
    public static final Snapshot NONE = new Snapshot(Playback.NONE, Lyrics.NONE, null);
}
