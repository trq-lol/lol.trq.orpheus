/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import java.util.Set;

/**
 * What one session is playing at one instant. Immutable; a new one replaces it on every change.
 *
 * @param sourceId the source the session came from
 * @param sessionId the session inside that source
 * @param app the application behind the session
 * @param track what is playing
 * @param state the playback state
 * @param timeline where playback is and how it advances
 * @param shuffle shuffle mode
 * @param repeat repeat mode
 * @param volume 0 to 1, or NaN when the source does not report it
 * @param controls the commands that work right now
 * @param coverKey the key of the cover image, or null when there is none
 * @param coverUri a remote cover location the library did not fetch, or null
 * @param shareable whether the caller opted in to passing this data on to other people
 * @param display rules attached by the source
 */
public record Playback(
        String sourceId,
        String sessionId,
        SourceApp app,
        Track track,
        PlaybackState state,
        Timeline timeline,
        Shuffle shuffle,
        Repeat repeat,
        double volume,
        Set<Control> controls,
        String coverKey,
        String coverUri,
        boolean shareable,
        DisplayRules display) {
    /** The snapshot when nothing is selected. */
    public static final Playback NONE = new Playback(
            "",
            "",
            SourceApp.UNKNOWN,
            Track.NONE,
            PlaybackState.NONE,
            Timeline.NONE,
            Shuffle.UNKNOWN,
            Repeat.UNKNOWN,
            Double.NaN,
            Set.of(),
            null,
            null,
            false,
            DisplayRules.NONE);

    public boolean isPresent() {
        return state != PlaybackState.NONE;
    }

    public boolean playing() {
        return state == PlaybackState.PLAYING;
    }

    /** Whether {@code control} works for this session right now. */
    public boolean can(Control control) {
        return controls.contains(control);
    }

    /** Whether this snapshot and {@code other} describe the same session. */
    public boolean sameSession(Playback other) {
        return sourceId.equals(other.sourceId) && sessionId.equals(other.sessionId);
    }
}
