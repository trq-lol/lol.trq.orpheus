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
        Track plain = track("Example Song", 201_000, "The Examples");
        assertTrue(plain.sameSong(track("Example Song (feat. Someone) - Remastered 2011", 202_500, "Examples")));
        assertTrue(plain.sameSong(track("EXAMPLE SONG", -1, "The Examples, Someone")));
        assertTrue(plain.sameSong(track("Ｅxample Song", 201_000)));
    }

    @Test
    void differentArtistLengthOrTitleIsADifferentSong() {
        Track plain = track("Example Song", 201_000, "The Examples");
        assertFalse(plain.sameSong(track("Example Song", 201_000, "Somebody Else")));
        assertFalse(plain.sameSong(track("Example Song", 260_000, "The Examples")));
        assertFalse(plain.sameSong(track("Example Songs", 201_000, "The Examples")));
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

    @Test
    void bracketedDecorationsAreDroppedAndHostileTitlesStayCheap() {
        assertEquals("song", lol.trq.orpheus.internal.Text.title("Song (feat. Someone) [Remastered 2011]"));
        assertEquals("song part one", lol.trq.orpheus.internal.Text.title("Song (Part One)"));
        assertEquals("song", lol.trq.orpheus.internal.Text.title("Song ((Live)"));
        assertEquals("a b live", lol.trq.orpheus.internal.Text.title("a) b (live"));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 200; i++) {
                lol.trq.orpheus.internal.Text.title("(live ".repeat(100_000));
            }
        });
        assertEquals(
                64,
                new Track(null, "t", java.util.Collections.nCopies(5000, "A"), "", "", 1, 0, null, MediaKind.UNKNOWN)
                        .artists()
                        .size());
    }
}
