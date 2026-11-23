/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.source;

import java.util.Set;
import lol.trq.orpheus.Control;
import lol.trq.orpheus.PlaybackState;
import lol.trq.orpheus.Repeat;
import lol.trq.orpheus.Shuffle;
import lol.trq.orpheus.SourceApp;
import lol.trq.orpheus.Track;

/**
 * One session as a source sees it.
 *
 * @param sessionId unique inside the source while the session lives
 * @param app the application behind it
 * @param track what it plays
 * @param state its playback state
 * @param positionMillis the position when it was measured
 * @param measuredNanos when the position was measured, as a {@link System#nanoTime()} reading
 * @param rate playback speed
 * @param shuffle shuffle mode
 * @param repeat repeat mode
 * @param volume 0 to 1, or NaN
 * @param controls the commands the session accepts now
 * @param cover the encoded cover, or null
 * @param coverUri a cover location the source did not load, or null
 */
public record SessionUpdate(
        String sessionId,
        SourceApp app,
        Track track,
        PlaybackState state,
        long positionMillis,
        long measuredNanos,
        double rate,
        Shuffle shuffle,
        Repeat repeat,
        double volume,
        Set<Control> controls,
        CoverData cover,
        String coverUri) {}
