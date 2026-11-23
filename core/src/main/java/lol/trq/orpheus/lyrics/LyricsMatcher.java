/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import java.util.List;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.internal.Text;

/** Scores how well a lyrics candidate fits a track. A wrong match is worse than none, so the bar is high. */
public final class LyricsMatcher {
    /** The lowest score at which lyrics are shown. */
    public static final double ACCEPT = 0.8;

    private LyricsMatcher() {}

    /**
     * Scores a candidate described by its own title, artists and length.
     *
     * @param track the playing track
     * @param title the candidate title
     * @param artists the candidate artists, possibly empty
     * @param durationMillis the candidate length, or -1
     * @return a score; {@link #ACCEPT} or more means "show it"
     */
    public static double score(Track track, String title, List<String> artists, long durationMillis) {
        return score(track, Text.title(track.title()), title, Text.title(title), artists, durationMillis);
    }

    /** The same with both title keys already worked out, for scoring many candidates. */
    static double score(
            Track track, String want, String title, String have, List<String> artists, long durationMillis) {
        if (want.isEmpty() || have.isEmpty()) {
            return 0;
        }
        int points;
        if (want.equals(have)) {
            points = 60;
        } else if (Math.min(want.length(), have.length()) >= 4 && (want.contains(have) || have.contains(want))) {
            points = 35;
        } else {
            return 0;
        }
        if (artists.isEmpty() || track.artists().isEmpty()) {
            points += 10;
        } else if (Text.shareArtist(track.artists(), artists)) {
            points += 30;
        }
        if (track.durationMillis() <= 0 || durationMillis <= 0) {
            points += 5;
        } else {
            long apart = Math.abs(track.durationMillis() - durationMillis);
            if (apart <= 2000) {
                points += 10;
            } else if (apart > 5000) {
                points -= 50;
            }
        }
        if (!Text.versions(track.title()).equals(Text.versions(title))) {
            points -= 30;
        }
        return Math.max(0, Math.min(100, points)) / 100.0;
    }
}
