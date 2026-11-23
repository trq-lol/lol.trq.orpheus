/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.windows;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The byte layout is a contract with the native library; a damaged blob must never take the reader thread down. */
class BlobTest {
    private static final class Seen implements Blob.Handler {
        final List<String> records = new ArrayList<>();
        Blob.Session first;
        byte[] thumbnail;

        @Override
        public void sessions(long builtNanosAgo, List<Blob.Session> sessions) {
            records.add("sessions " + sessions.size() + " age " + builtNanosAgo);
            first = sessions.isEmpty() ? null : sessions.get(0);
        }

        @Override
        public void thumbnail(long key, byte[] bytes) {
            records.add("thumbnail " + key);
            thumbnail = bytes;
        }

        @Override
        public void result(long request, int status, String reason) {
            records.add("result " + request + " " + status + " " + reason);
        }

        @Override
        public void status(boolean ready, String reason) {
            records.add("status " + ready + " " + reason);
        }
    }

    private static void text(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.putInt(bytes.length).put(bytes);
    }

    private static byte[] blob() {
        ByteBuffer out = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) Blob.KIND_STATUS).put((byte) 1);
        text(out, "");
        out.put((byte) Blob.KIND_THUMBNAIL).putLong(77).putInt(3).put(new byte[] {1, 2, 3});
        out.put((byte) Blob.KIND_AGE).putLong(1234);
        out.put((byte) Blob.KIND_SESSIONS).putInt(1).putInt(9);
        text(out, "player.exe");
        text(out, "東京");
        text(out, "Artist");
        text(out, "Album");
        text(out, "Album Artist");
        out.putInt(4)
                .put((byte) 1)
                .put((byte) 2)
                .putLong(60_000)
                .putLong(240_000)
                .putLong(1500);
        out.putDouble(1.25).put((byte) 1).put((byte) 2).putInt(0b101).putLong(77);
        out.put((byte) Blob.KIND_RESULT).putLong(5).put((byte) 1);
        text(out, "no");
        return Arrays.copyOf(out.array(), out.position());
    }

    @Test
    void theOrderOfControlsIsPartOfTheNativeContract() {
        // The native side sends the supported controls as bits and receives a command as a number, both by
        // position in this list. Reordering the enum needs a matching change and a new ABI number there.
        assertEquals(
                List.of(
                        "PLAY",
                        "PAUSE",
                        "TOGGLE",
                        "STOP",
                        "NEXT",
                        "PREVIOUS",
                        "SEEK",
                        "FAST_FORWARD",
                        "REWIND",
                        "SHUFFLE",
                        "REPEAT",
                        "RATE",
                        "VOLUME",
                        "MUTE"),
                java.util.Arrays.stream(lol.trq.orpheus.Control.values())
                        .map(Enum::name)
                        .toList());
    }

    @Test
    void readsEveryRecordInOrder() {
        Seen seen = new Seen();
        Blob.read(blob(), seen);
        assertEquals(List.of("status true ", "thumbnail 77", "sessions 1 age 1234", "result 5 1 no"), seen.records);
        assertArrayEquals(new byte[] {1, 2, 3}, seen.thumbnail);
        Blob.Session s = seen.first;
        assertEquals(9, s.id());
        assertEquals("player.exe", s.app());
        assertEquals("東京", s.title());
        assertEquals("Album Artist", s.albumArtist());
        assertEquals(4, s.trackNumber());
        assertEquals(2, s.status());
        assertEquals(60_000, s.positionMillis());
        assertEquals(240_000, s.durationMillis());
        assertEquals(1500, s.ageMillis());
        assertEquals(1.25, s.rate());
        assertEquals(0b101, s.controls());
        assertEquals(77, s.thumbnail());
    }

    @Test
    void aDamagedBlobDeliversWhatCameBeforeAndNeverThrows() {
        byte[] whole = blob();
        for (int cut = 0; cut < whole.length; cut++) {
            Seen seen = new Seen();
            Blob.read(Arrays.copyOf(whole, cut), seen);
            assertEquals(
                    List.of("status true ", "thumbnail 77", "sessions 1 age 1234", "result 5 1 no")
                            .subList(0, seen.records.size()),
                    seen.records,
                    "cut at " + cut);
        }
        ByteBuffer huge = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        huge.put((byte) Blob.KIND_SESSIONS).putInt(Integer.MAX_VALUE);
        Seen seen = new Seen();
        Blob.read(huge.array(), seen);
        assertTrue(seen.records.isEmpty(), "an absurd session count must be refused, not allocated");
        ByteBuffer lying = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        lying.put((byte) Blob.KIND_THUMBNAIL).putLong(1).putInt(Integer.MAX_VALUE);
        Blob.read(lying.array(), seen);
        Blob.read(new byte[] {99, 1, 2, 3}, seen);
        assertTrue(seen.records.isEmpty());
    }
}
