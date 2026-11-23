/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import lol.trq.orpheus.Track;
import org.junit.jupiter.api.Test;

class LyricsMatcherTest {
    private static Track track(String title, long duration, String... artists) {
        return new Track(null, title, List.of(artists), "", "", duration, 0, null, null);
    }

    private static boolean accepted(Track track, String title, long duration, String... artists) {
        return LyricsMatcher.score(track, title, List.of(artists), duration) >= LyricsMatcher.ACCEPT;
    }

    @Test
    void rightSongsAreAccepted() {
        Track playing = track("Night Drive", 201_000, "A, B");
        assertTrue(accepted(playing, "Night Drive", 201_500, "A"), "one credit string naming two artists");
        assertTrue(accepted(playing, "night drive (feat. B)", -1, "A & B"));
        assertTrue(accepted(playing, "Night Drive", 200_000), "no artist on the file, but the length agrees");
        assertTrue(accepted(track("Night Drive", -1, "A"), "Night Drive", -1, "A"), "no lengths at all");
    }

    @Test
    void wrongSongsAreRejected() {
        Track playing = track("Night Drive", 201_000, "A");
        assertTrue(!accepted(playing, "Night Drive", 201_000, "Somebody Else"), "same title, other artist");
        assertTrue(!accepted(playing, "Night Drive", -1), "title alone is not enough");
        assertTrue(!accepted(playing, "Night Drive", 260_000, "A"), "a minute longer is another recording");
        assertTrue(!accepted(playing, "Night Drive (Live)", -1, "A"), "live timings do not fit the studio cut");
        assertTrue(!accepted(track("Night Drive - Live", -1, "A"), "Night Drive", -1, "A"));
        assertTrue(!accepted(playing, "Night", -1, "A"), "a short fragment of the title");
        assertTrue(!accepted(track("!!!", -1, "A"), "???", -1, "A"), "titles that clean up to nothing");
        assertTrue(!accepted(Track.NONE, "", -1));
    }
}
