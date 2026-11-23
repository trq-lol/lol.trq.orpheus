/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrackTest {
    private static Track track(String title, long duration, String... artists) {
        return new Track(null, title, List.of(artists), "", "", duration, 0, null, null);
    }

    @Test
    void decorationsDoNotMakeADifferentSong() {
        Track plain = track("Night Drive", 201_000, "The Examples");
        assertTrue(plain.sameSong(track("Night Drive (feat. Someone) - Remastered 2011", 202_500, "Examples")));
        assertTrue(plain.sameSong(track("NIGHT DRIVE", -1, "The Examples, Someone")));
        assertTrue(plain.sameSong(track("Ｎｉｇｈｔ Drive", 201_000)));
    }

    @Test
    void differentArtistLengthOrTitleIsADifferentSong() {
        Track plain = track("Night Drive", 201_000, "The Examples");
        assertFalse(plain.sameSong(track("Night Drive", 201_000, "Somebody Else")));
        assertFalse(plain.sameSong(track("Night Drive", 260_000, "The Examples")));
        assertFalse(plain.sameSong(track("Night Drives", 201_000, "The Examples")));
        assertFalse(Track.NONE.sameSong(Track.NONE));
    }

    @Test
    void hostileTextIsCappedAndCleaned() {
        Track track = track("a\u0000b\nc" + "x".repeat(5000), -1, "  ", "ok");
        assertEquals(1024, track.title().length());
        assertTrue(track.title().startsWith("a b c"));
        assertEquals(List.of("ok"), track.artists());
        assertEquals("ok", track.artistLine());
    }
}
