/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lol.trq.orpheus.Track;
import lol.trq.orpheus.testkit.Media;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLyricsTest {
    @TempDir
    Path dir;

    private static Track track(String title, String artist, long duration) {
        return new Track(null, title, List.of(artist), "", "", duration, 0, null, null);
    }

    private LocalLyrics indexed() {
        LocalLyrics lyrics = LocalLyrics.in(dir);
        lyrics.index();
        return lyrics;
    }

    @Test
    void findsByTagsByFileNameAndRejectsTheWrongSong() throws Exception {
        Files.writeString(dir.resolve("whatever.lrc"), "[ti:Tagged Song]\n[ar:Tag Artist]\n[00:01.00]tagged");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/03. Name Artist - Named Song.lrc"), "[00:02.00]named");
        Files.writeString(dir.resolve("Lonely Title.lrc"), "[00:03.00]lonely");
        LocalLyrics lyrics = indexed();

        assertEquals(
                "tagged",
                lyrics.find(track("Tagged Song", "Tag Artist", -1))
                        .lyrics()
                        .line(0)
                        .text());
        LyricsMatch named = lyrics.find(track("Named Song", "Name Artist", -1));
        assertEquals("named", named.lyrics().line(0).text());
        assertEquals("03. Name Artist - Named Song.lrc", named.lyrics().source());
        assertNull(lyrics.find(track("Named Song", "Another Band", -1)));
        assertNull(lyrics.find(track("Lonely Title", "Anyone", -1)), "a bare title is not enough to show lyrics");
        assertNull(lyrics.find(track("Unknown", "Nobody", -1)));
    }

    @Test
    void anAudioFileNextToAnLrcGivesItReliableMetadata() throws Exception {
        Files.writeString(dir.resolve("track07.lrc"), "[00:01.00]sidecar");
        Files.write(
                dir.resolve("track07.flac"),
                Media.flac(44_100, 44_100L * 200, "TITLE=Real Title", "ARTIST=Real Artist"));
        LocalLyrics lyrics = indexed();
        assertEquals(
                "sidecar",
                lyrics.find(track("Real Title", "Real Artist", 200_000))
                        .lyrics()
                        .line(0)
                        .text());
        assertNull(
                lyrics.find(track("Real Title", "Real Artist", 300_000)), "the length from the audio file must count");
    }

    @Test
    void readsLyricsEmbeddedInEachContainer() throws Exception {
        Files.write(
                dir.resolve("a.flac"),
                Media.flac(48_000, 48_000L * 100, "TITLE=Flac Song", "ARTIST=F", "LYRICS=[00:01.00]from flac"));
        Files.write(
                dir.resolve("b.mp3"),
                Media.id3(
                        4,
                        Media.id3Text(4, "TIT2", "Mp3 Song"),
                        Media.id3Text(4, "TPE1", "M"),
                        Media.id3Unsynced(4, "plain one\nplain two")));
        Files.write(
                dir.resolve("c.mp3"),
                Media.id3(
                        3,
                        Media.id3Utf16Text(3, "TIT2", "Synced Song"),
                        Media.id3Text(3, "TPE1", "S"),
                        Media.id3Synced(3, 1000, "one", 2500, "two")));
        Files.write(dir.resolve("d.m4a"), Media.mp4("Mp4 Song", "P", "[00:04.00]from mp4", 90));
        Files.write(dir.resolve("e.flac"), Media.flac(48_000, 48_000L * 100, "TITLE=No Lyrics", "ARTIST=F"));
        LocalLyrics lyrics = indexed();

        assertEquals(
                "from flac",
                lyrics.find(track("Flac Song", "F", 100_000)).lyrics().line(0).text());
        LyricsMatch plain = lyrics.find(track("Mp3 Song", "M", -1));
        assertEquals(Lyrics.Kind.PLAIN, plain.lyrics().kind());
        assertEquals(2, plain.lyrics().size());
        Lyrics synced = lyrics.find(track("Synced Song", "S", -1)).lyrics();
        assertEquals(Lyrics.Kind.LINE, synced.kind());
        assertEquals(1, synced.lineAt(2500));
        assertEquals(
                "from mp4",
                lyrics.find(track("Mp4 Song", "P", 90_000)).lyrics().line(0).text());
        assertNull(lyrics.find(track("No Lyrics", "F", -1)));
    }

    @Test
    void theIndexThreadStartsOnDemandReportsBackAndStops() throws Exception {
        Files.writeString(dir.resolve("A - B.lrc"), "[00:01.00]x");
        LocalLyrics lyrics = LocalLyrics.in(dir, dir.resolve("missing-folder"));
        java.util.concurrent.CountDownLatch indexed = new java.util.concurrent.CountDownLatch(2);
        lyrics.onChange(indexed::countDown);
        lyrics.find(track("B", "A", -1));
        org.junit.jupiter.api.Assertions.assertTrue(
                indexed.await(10, java.util.concurrent.TimeUnit.SECONDS), "the provider never reported back");
        assertNotNull(lyrics.find(track("B", "A", -1)));
        lyrics.close();
        assertEquals(
                0,
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().equals("orpheus-index") && t.isAlive())
                        .count());
    }
}
