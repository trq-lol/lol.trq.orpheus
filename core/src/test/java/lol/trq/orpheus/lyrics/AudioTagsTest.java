/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import lol.trq.orpheus.testkit.Media;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioTagsTest {
    @TempDir
    Path dir;

    private AudioTags read(byte[] data) throws Exception {
        Path file = dir.resolve("f.bin");
        Files.write(file, data);
        return AudioTags.read(file);
    }

    @Test
    void readsLengthAndTextFromEachContainer() throws Exception {
        AudioTags flac = read(Media.flac(44_100, 44_100L * 215, "title=Lower Key", "ARTIST=One", "ARTIST=Two"));
        assertEquals("Lower Key", flac.title());
        assertEquals("One, Two", flac.artist());
        assertEquals(215_000, flac.durationMillis());

        AudioTags mp3 = read(Media.id3(3, Media.id3Utf16Text(3, "TIT2", "東京"), Media.id3Text(3, "TLEN", "123456")));
        assertEquals("東京", mp3.title());
        assertEquals(123_456, mp3.durationMillis());

        AudioTags prefixed = read(Media.concat(
                Media.id3(4, Media.id3Text(4, "TIT2", "ignored")), Media.flac(48_000, 48_000L * 10, "TITLE=Inner")));
        assertEquals("Inner", prefixed.title(), "a FLAC file with an ID3 block in front is still a FLAC file");

        assertEquals(61_000, read(Media.mp4("T", "A", "", 61)).durationMillis());
    }

    @Test
    void truncatedAndCorruptedFilesNeverThrow() throws Exception {
        List<byte[]> valid = List.of(
                Media.flac(44_100, 44_100L * 215, "TITLE=T", "LYRICS=[00:01.00]x"),
                Media.id3(
                        4,
                        Media.id3Text(4, "TIT2", "T"),
                        Media.id3Unsynced(4, "words"),
                        Media.id3Synced(4, 1000, "a", 2000, "b")),
                Media.id3(3, Media.id3Utf16Text(3, "TIT2", "T"), Media.id3Synced(3, 1000, "a")),
                Media.mp4("T", "A", "[00:01.00]x", 30));
        Random random = new Random(11);
        for (byte[] data : valid) {
            for (int cut = 0; cut < data.length; cut++) {
                assertNotNull(read(Arrays.copyOf(data, cut)));
            }
            for (int i = 0; i < 400; i++) {
                byte[] broken = data.clone();
                for (int flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    broken[random.nextInt(broken.length)] = (byte) random.nextInt(256);
                }
                assertNotNull(read(broken));
            }
        }
    }
}
